#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""异步出图任务子系统（JobStore + /api/generate/async + /api/jobs）的测试。

只用标准库（``unittest`` / ``http.client``），不引入任何新依赖，也不需要真的
ComfyUI 或 GPU：

* ``JobStoreTest``       —— 直接测任务表：幂等、按 client_job_id 解析、持久化重载、
                           重启中断修复、保留上限淘汰、坏文件兜底。
* ``JobHttpTest``        —— 在临时目录里真起一个 HTTP 服务（``port=0`` 让系统分配
                           端口），把 ``server.run_generate`` 换成假实现，跑完整链路：
                           提交 → 轮询 → 换 Host 查下载地址 → 列表 → 下载字节。

运行::

    cd refs/api-comfy && python3 -m unittest test_jobs -v

HTTP 测试结束会 ``shutdown()`` + ``server_close()`` 并 join 线程，不会留下常驻进程。
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
from unittest import mock
from urllib.parse import quote, urlparse

import server

# 一张"假 PNG"：内容不重要，重点是能真的落盘并被 /files/<name> 原样读回来
FAKE_PNG = b"\x89PNG\r\n\x1a\n" + b"async-job-payload" * 16
FAKE_FILENAME = "ComfyUI_00001_.png"
FAKE_PROMPT_ID = "fake-prompt-id-0001"
WAIT_TIMEOUT = 10.0


def wait_until(predicate, timeout=WAIT_TIMEOUT, interval=0.02):
    """轮询直到 ``predicate()`` 为真；超时返回最后一次的值（由调用方断言）。"""
    deadline = time.time() + timeout
    value = predicate()
    while not value and time.time() < deadline:
        time.sleep(interval)
        value = predicate()
    return value


# --------------------------------------------------------------------------- #
# 1) 任务表单元测试
# --------------------------------------------------------------------------- #
class JobStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="jobs-store-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.state_dir = os.path.join(self.tmp, "state")

    def _store(self, **kwargs):
        return server.JobStore(self.state_dir, **kwargs)

    def test_submit_is_idempotent_per_client_job_id(self):
        store = self._store()
        first, created = store.create_or_get(
            client_job_id="app-1", request={"prompt": "第一次"}, timeout_sec=30
        )
        self.assertTrue(created)
        second, created_again = store.create_or_get(
            client_job_id="app-1", request={"prompt": "第二次"}, timeout_sec=30
        )
        self.assertFalse(created_again)
        self.assertEqual(first["job_id"], second["job_id"])
        self.assertEqual(len(store), 1)
        # 幂等命中不覆盖原任务的请求摘要
        self.assertEqual(second["request"]["prompt"], "第一次")

    def test_resolve_by_job_id_and_client_job_id(self):
        store = self._store()
        record, _ = store.create_or_get(client_job_id="app-2", request={}, timeout_sec=10)
        self.assertEqual(store.resolve(record["job_id"])["job_id"], record["job_id"])
        self.assertEqual(store.resolve("app-2")["job_id"], record["job_id"])
        self.assertIsNone(store.resolve("不存在"))
        self.assertIsNone(store.resolve(""))

    def test_terminal_job_survives_reload(self):
        store = self._store()
        record, _ = store.create_or_get(
            client_job_id="app-3", request={"prompt": "雪山"}, timeout_sec=45
        )
        store.mark_running(record["job_id"])
        store.set_stage(record["job_id"], "generating")
        store.finish_success(
            record["job_id"],
            prompt_id=FAKE_PROMPT_ID,
            images=[{"path": "/tmp/whatever.png", "filename": FAKE_FILENAME, "size_bytes": 42}],
            meta={"seed": 7},
        )

        reloaded = self._store()
        found = reloaded.resolve("app-3")
        self.assertIsNotNone(found)
        self.assertEqual(found["status"], server.JOB_STATUS_SUCCEEDED)
        self.assertEqual(found["stage"], "done")
        self.assertEqual(found["prompt_id"], FAKE_PROMPT_ID)
        self.assertEqual(found["images"][0]["path"], "/tmp/whatever.png")
        self.assertEqual(found["timeout_sec"], 45.0)   # resolve() 返回原始记录，不是快照
        self.assertIsNotNone(found["finished_at"])

        # jobs.json 必须是完整可解析的，且不留临时文件
        with open(reloaded.path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertEqual(data["version"], 1)
        self.assertEqual(len(data["jobs"]), 1)
        self.assertEqual(
            [name for name in os.listdir(self.state_dir) if name.endswith(".tmp")], []
        )

    def test_reload_marks_unfinished_jobs_interrupted(self):
        store = self._store()
        running, _ = store.create_or_get(client_job_id="app-4", request={}, timeout_sec=10)
        store.mark_running(running["job_id"])
        store.set_stage(running["job_id"], "generating")
        queued, _ = store.create_or_get(client_job_id="app-5", request={}, timeout_sec=10)
        store.flush()

        reloaded = self._store()
        for job_id in (running["job_id"], queued["job_id"]):
            found = reloaded.get(job_id)
            self.assertEqual(found["status"], server.JOB_STATUS_INTERRUPTED)
            self.assertEqual(found["error"], "服务重启，任务已中断")
            self.assertIsNotNone(found["finished_at"])
        # 阶段保留原值，客户端据此能看到"停在哪一步"
        self.assertEqual(reloaded.get(running["job_id"])["stage"], "generating")
        self.assertEqual(reloaded.get(queued["job_id"])["stage"], "queued")

    def test_retention_keeps_newest_terminal_jobs(self):
        store = self._store(retention=3)
        job_ids = []
        for index in range(6):
            record, _ = store.create_or_get(
                client_job_id="app-r%d" % index, request={}, timeout_sec=10
            )
            store.finish_success(record["job_id"], prompt_id="p%d" % index)
            job_ids.append(record["job_id"])
            time.sleep(0.01)   # 让 created_at 严格递增，淘汰顺序可断言

        remaining = [job["job_id"] for job in store.list_jobs(limit=100)]
        self.assertEqual(sorted(remaining), sorted(job_ids[-3:]))
        # 被淘汰任务的 client_job_id 也必须从索引里消失，避免幂等命中一个空任务
        self.assertIsNone(store.get_by_client_id("app-r0"))
        self.assertIsNotNone(store.get_by_client_id("app-r5"))

    def test_corrupt_jobs_json_does_not_break_startup(self):
        os.makedirs(self.state_dir, exist_ok=True)
        path = os.path.join(self.state_dir, "jobs.json")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("{ 这不是合法 JSON ")

        store = self._store()
        self.assertEqual(len(store), 0)
        self.assertTrue(os.path.exists(path + ".bad"))
        # 坏文件挪走后任务表照常工作
        record, created = store.create_or_get(
            client_job_id="app-6", request={}, timeout_sec=10
        )
        self.assertTrue(created)
        self.assertEqual(store.resolve("app-6")["job_id"], record["job_id"])

    def test_stage_callback_cannot_rewind_terminal_job(self):
        store = self._store()
        record, _ = store.create_or_get(client_job_id=None, request={}, timeout_sec=10)
        store.mark_running(record["job_id"])
        store.finish_failure(
            record["job_id"], error="编排失败", http_status=502, detail={"step": "stop:llm"}
        )
        store.set_stage(record["job_id"], "generating")   # 迟到的进度回调
        found = store.get(record["job_id"])
        self.assertEqual(found["status"], server.JOB_STATUS_FAILED)
        self.assertEqual(found["stage"], "failed")
        self.assertEqual(found["http_status"], 502)
        self.assertEqual(found["detail"], {"step": "stop:llm"})

    def test_list_jobs_filters_and_orders(self):
        store = self._store()
        first, _ = store.create_or_get(client_job_id="l1", request={}, timeout_sec=10)
        store.finish_success(first["job_id"])
        time.sleep(0.01)
        second, _ = store.create_or_get(client_job_id="l2", request={}, timeout_sec=10)
        store.finish_failure(second["job_id"], error="x", http_status=500)

        self.assertEqual(store.list_jobs(limit=10)[0]["job_id"], second["job_id"])
        self.assertEqual(
            [job["job_id"] for job in store.list_jobs(limit=10, statuses=["succeeded"])],
            [first["job_id"]],
        )
        self.assertEqual(store.list_jobs(limit=10, statuses=["running"]), [])
        self.assertEqual(len(store.list_jobs(limit=1)), 1)


# --------------------------------------------------------------------------- #
# 2) HTTP 端到端测试（ephemeral 端口，测试内自行起停）
# --------------------------------------------------------------------------- #
class JobHttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="jobs-http-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.out_dir = os.path.join(self.tmp, "outputs")
        self.state_dir = os.path.join(self.tmp, "state")
        os.makedirs(self.out_dir, exist_ok=True)
        self.httpd = None
        self.thread = None
        # 假生成用它来"卡住"，让提交后能在 running/generating 状态上稳定断言
        self.release = threading.Event()

    # -------------------------- 服务起停 -------------------------- #
    def _start_server(self, run_generate=None, job_retention=server.DEFAULT_JOB_RETENTION):
        patcher = mock.patch.object(server, "run_generate", run_generate or self._fake_run_generate)
        patcher.start()
        self.addCleanup(patcher.stop)   # addCleanup 是 LIFO：先 stop 服务，再还原 patch
        # 测试里不需要 HTTP 访问日志与 [jobs] 状态日志，静音后失败信息才看得清
        for target, attr, stub in (
            (server, "_log_jobs", lambda message: None),
            (server.ComfyApiHandler, "log_message", lambda *args, **kwargs: None),
        ):
            quiet = mock.patch.object(target, attr, stub)
            quiet.start()
            self.addCleanup(quiet.stop)
        self.httpd = server.make_server(
            "127.0.0.1",
            0,                      # 端口 0：让内核分配一个空闲端口
            "http://127.0.0.1:1",   # 假 ComfyUI 地址，永远不会被真的连接
            self.out_dir,
            None,
            scheduler_enabled=False,
            config={"generate_timeout": 30},
            state_dir=self.state_dir,
            job_retention=job_retention,
            # 照片库也指到临时目录，测试绝不碰仓库里的 photos/
            photos_dir=os.path.join(self.state_dir, "photos"),
        )
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(
            target=self.httpd.serve_forever, name="test-httpd", daemon=True
        )
        self.thread.start()
        self.addCleanup(self._stop_server)

    def _stop_server(self):
        if self.httpd is None:
            return
        self.httpd.shutdown()
        self.httpd.server_close()
        if self.thread is not None:
            self.thread.join(5)
        self.httpd = None
        self.thread = None

    # -------------------------- 假生成 -------------------------- #
    def _fake_run_generate(self, ctx, params):
        """伪造一次成功生成：图片真的写进 output_dir，供 /files 下载。"""
        self.release.wait(WAIT_TIMEOUT)   # 由测试放行，避免"提交即完成"的竞态
        path = os.path.join(ctx["output_dir"], FAKE_FILENAME)
        with open(path, "wb") as fh:
            fh.write(FAKE_PNG)
        return {
            "prompt_id": FAKE_PROMPT_ID,
            "elapsed_sec": 0.01,
            "images": [
                {
                    "path": path,
                    "filename": FAKE_FILENAME,
                    "subfolder": "",
                    "type": "output",
                    "url": "http://127.0.0.1:1/view?filename=%s" % FAKE_FILENAME,
                    "size_bytes": len(FAKE_PNG),
                }
            ],
            "meta": {"seed": 42, "size": "1024x1024"},
        }

    # -------------------------- HTTP 小工具 -------------------------- #
    def _request(self, method, path, body=None, headers=None):
        """发一个请求；``headers`` 里可以覆盖 Host（用于验证下载地址重算）。"""
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        try:
            hdrs = dict(headers or {})
            payload = None
            if body is not None:
                payload = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
                hdrs.setdefault("Content-Type", "application/json")
            conn.request(method, path, body=payload, headers=hdrs)
            response = conn.getresponse()
            raw = response.read()
            try:
                data = json.loads(raw.decode("utf-8")) if raw else None
            except (UnicodeDecodeError, ValueError):
                data = None
            return response.status, data, raw
        finally:
            conn.close()

    def _submit(self, payload):
        return self._request("POST", "/api/generate/async", payload)

    def _get_job(self, key, host=None):
        headers = {"Host": host} if host else None
        return self._request("GET", "/api/jobs/%s" % quote(str(key), safe=""), headers=headers)

    def _wait_job(self, key, timeout=WAIT_TIMEOUT):
        def terminal():
            status, body, _ = self._get_job(key)
            self.assertEqual(status, 200, body)
            if body["status"] in server.JOB_TERMINAL_STATUSES:
                return body
            return None

        found = wait_until(terminal, timeout=timeout)
        if not found:
            self.fail("任务 %s 在 %.0fs 内没有进入终态" % (key, timeout))
        return found

    # -------------------------- 用例 -------------------------- #
    def test_async_job_end_to_end(self):
        self._start_server()
        status, body, _ = self._submit(
            {
                "prompt": "把背景换成雪山夜景",
                "size": "1024x1024",
                "steps": 4,
                "seed": 42,
                "client_job_id": "app-abc:001",
            }
        )
        self.assertEqual(status, 202, body)
        self.assertTrue(body["ok"])
        self.assertEqual(body["client_job_id"], "app-abc:001")
        job_id = body["job_id"]
        self.assertTrue(job_id)
        self.assertEqual(body["status_url"], "/api/jobs/%s" % job_id)
        self.assertIsInstance(body["created_at"], float)
        # 契约：提交响应固定报 queued/queued（真实进度由 GET /api/jobs 反映）
        self.assertEqual(body["status"], server.JOB_STATUS_QUEUED)
        self.assertEqual(body["stage"], "queued")

        # 卡住的假生成：一定能观察到 running + generating
        self.assertTrue(
            wait_until(lambda: self._get_job("app-abc:001")[1]["status"] == server.JOB_STATUS_RUNNING)
        )
        running = self._get_job("app-abc:001")[1]
        self.assertEqual(running["stage"], "generating")
        self.assertEqual(running["progress"]["timeout_sec"], 30.0)
        self.assertEqual(running["images"], [])          # 非成功态不带图
        self.assertIsNone(running["finished_at"])

        self.release.set()   # 放行 → 生成"成功"
        final = self._wait_job("app-abc:001")
        self.assertEqual(final["status"], server.JOB_STATUS_SUCCEEDED)
        self.assertEqual(final["stage"], "done")
        self.assertEqual(final["prompt_id"], FAKE_PROMPT_ID)
        self.assertIsNotNone(final["finished_at"])
        self.assertIsNone(final["error"])
        self.assertEqual(final["request"]["steps"], 4)
        self.assertEqual(final["request"]["size"], "1024x1024")
        self.assertEqual(final["request"]["seed"], 42)
        self.assertFalse(final["request"]["has_reference_images"])
        self.assertEqual(len(final["images"]), 1)
        image = final["images"][0]
        self.assertEqual(image["filename"], FAKE_FILENAME)
        self.assertEqual(image["comfy_filename"], FAKE_FILENAME)
        self.assertEqual(image["size_bytes"], len(FAKE_PNG))

        # 下载地址必须按**当前请求的 Host** 重算（换网络/换 Host 后 IP 会变）
        default_host = "127.0.0.1:%d" % self.port
        self.assertEqual(urlparse(image["download_url"]).hostname, "127.0.0.1")
        wifi = self._get_job("app-abc:001", host="10.1.2.3:9999")[1]
        self.assertEqual(urlparse(wifi["images"][0]["download_url"]).hostname, "10.1.2.3")
        self.assertEqual(urlparse(wifi["images"][0]["download_url"]).port, 9999)
        cell = self._get_job("app-abc:001", host="192.168.1.7:8080")[1]
        self.assertEqual(urlparse(cell["images"][0]["download_url"]).hostname, "192.168.1.7")
        self.assertNotEqual(
            wifi["images"][0]["download_url"], cell["images"][0]["download_url"]
        )
        self.assertTrue(
            cell["images"][0]["download_url"].endswith("/files/" + FAKE_FILENAME)
        )
        self.assertIn("download=1", cell["images"][0]["download_url_attachment"])

        # 列表接口能查到它（compact 模式省掉 prompt 与 detail）
        status, listing, _ = self._request("GET", "/api/jobs")
        self.assertEqual(status, 200)
        self.assertTrue(listing["ok"])
        self.assertEqual([job["job_id"] for job in listing["jobs"]], [job_id])
        self.assertNotIn("prompt", listing["jobs"][0]["request"])
        self.assertIsNone(listing["jobs"][0]["detail"])

        status, filtered, _ = self._request("GET", "/api/jobs?limit=5&status=succeeded")
        self.assertEqual(status, 200)
        self.assertEqual([job["job_id"] for job in filtered["jobs"]], [job_id])
        status, filtered, _ = self._request("GET", "/api/jobs?status=failed,running")
        self.assertEqual(status, 200)
        self.assertEqual(filtered["jobs"], [])
        self.assertEqual(default_host, "127.0.0.1:%d" % self.port)

        # 产物能从 /files 原样下载回来
        status, _, raw = self._request("GET", "/files/" + FAKE_FILENAME)
        self.assertEqual(status, 200)
        self.assertEqual(raw, FAKE_PNG)

        # 幂等：同一 client_job_id 再提交一次 → 200 + 同一个 job_id
        status, again, _ = self._submit(
            {"prompt": "内容已经不重要了", "client_job_id": "app-abc:001"}
        )
        self.assertEqual(status, 200, again)
        self.assertTrue(again["ok"])
        self.assertEqual(again["job_id"], job_id)
        self.assertEqual(len(self._request("GET", "/api/jobs")[1]["jobs"]), 1)

        # 持久化：任务表落在 <state-dir>/jobs.json
        jobs_file = os.path.join(self.state_dir, "jobs.json")
        self.assertTrue(os.path.exists(jobs_file))
        with open(jobs_file, "r", encoding="utf-8") as fh:
            persisted = json.load(fh)
        self.assertEqual(len(persisted["jobs"]), 1)
        self.assertEqual(persisted["jobs"][0]["status"], server.JOB_STATUS_SUCCEEDED)

    def test_submit_validation_happens_before_task_creation(self):
        self._start_server()
        status, body, _ = self._submit({"prompt": "   "})
        self.assertEqual(status, 400)
        self.assertFalse(body["ok"])
        self.assertIn("prompt", body["error"])
        # 校验失败绝不能留下任务
        self.assertEqual(self._request("GET", "/api/jobs")[1]["jobs"], [])

        for bad in ("", "x" * 129, "has space", "a/b"):
            status, body, _ = self._submit({"prompt": "ok", "client_job_id": bad})
            self.assertEqual(status, 400, (bad, body))
            self.assertIn("client_job_id", body["error"])
        self.assertEqual(self._request("GET", "/api/jobs")[1]["jobs"], [])

        # 幂等命中时跳过校验：任务已存在 → 即使 prompt 为空也回 200 快照
        status, body, _ = self._submit({"prompt": "正常", "client_job_id": "keep-1"})
        self.assertEqual(status, 202, body)
        status, body, _ = self._submit({"client_job_id": "keep-1"})
        self.assertEqual(status, 200, body)
        self.release.set()

    def test_job_not_found(self):
        self._start_server()
        status, body, _ = self._request("GET", "/api/jobs/nope-404")
        self.assertEqual(status, 404)
        self.assertFalse(body["ok"])
        self.assertEqual(body["error"], "任务不存在：nope-404")

    def test_list_rejects_bad_query(self):
        self._start_server()
        status, body, _ = self._request("GET", "/api/jobs?status=bogus")
        self.assertEqual(status, 400)
        self.assertIn("status", body["error"])
        status, body, _ = self._request("GET", "/api/jobs?limit=abc")
        self.assertEqual(status, 400)
        self.assertIn("limit", body["error"])
        # limit 越界是 clamp（1..100），不是 400
        status, body, _ = self._request("GET", "/api/jobs?limit=0")
        self.assertEqual(status, 200)
        status, body, _ = self._request("GET", "/api/jobs?limit=101")
        self.assertEqual(status, 200)

    def test_failed_generation_lands_on_the_job(self):
        def boom(ctx, params):
            raise server.ApiError(
                503, "无法连接 ComfyUI：connection refused", {"hint": "起 ComfyUI"}, None
            )

        self._start_server(run_generate=boom)
        status, body, _ = self._submit({"prompt": "会失败", "client_job_id": "fail-1"})
        self.assertEqual(status, 202, body)
        final = self._wait_job("fail-1")
        self.assertEqual(final["status"], server.JOB_STATUS_FAILED)
        self.assertEqual(final["stage"], "failed")
        self.assertEqual(final["http_status"], 503)
        self.assertEqual(final["error"], "无法连接 ComfyUI：connection refused")
        self.assertEqual(final["detail"], {"hint": "起 ComfyUI"})
        self.assertEqual(final["images"], [])
        self.assertIsNotNone(final["finished_at"])
        self.assertIsInstance(final["resource"], dict)   # 编排快照照样透出

    def test_unexpected_worker_exception_becomes_500(self):
        def boom(ctx, params):
            raise RuntimeError("kaboom")

        self._start_server(run_generate=boom)
        status, body, _ = self._submit({"prompt": "会炸", "client_job_id": "boom-1"})
        self.assertEqual(status, 202, body)
        final = self._wait_job("boom-1")
        self.assertEqual(final["status"], server.JOB_STATUS_FAILED)
        self.assertEqual(final["http_status"], 500)
        self.assertIn("kaboom", final["error"])
        # 服务本身仍活着
        self.assertEqual(self._request("GET", "/api/workflow")[0], 200)

    def test_restart_marks_unfinished_job_interrupted(self):
        # 手写一份"上次进程正在跑"的任务表，再启动服务
        os.makedirs(self.state_dir, exist_ok=True)
        with open(os.path.join(self.state_dir, "jobs.json"), "w", encoding="utf-8") as fh:
            json.dump(
                {
                    "version": 1,
                    "jobs": [
                        {
                            "job_id": "job-from-last-boot",
                            "client_job_id": "cid-from-last-boot",
                            "status": "running",
                            "stage": "generating",
                            "created_at": time.time() - 60,
                            "started_at": time.time() - 50,
                            "timeout_sec": 120,
                            "images": [],
                        }
                    ],
                },
                fh,
            )
        self._start_server()

        status, body, _ = self._request("GET", "/api/jobs/cid-from-last-boot")
        self.assertEqual(status, 200, body)
        self.assertEqual(body["status"], server.JOB_STATUS_INTERRUPTED)
        self.assertEqual(body["error"], "服务重启，任务已中断")
        self.assertEqual(body["stage"], "generating")   # 保留中断时的阶段
        self.assertIsNotNone(body["finished_at"])
        self.assertGreaterEqual(body["progress"]["elapsed_sec"], 0.0)

    def test_keepalive_after_async_submit(self):
        """async 的 body 必须被读完：同一连接上的下一个请求不能错位。"""
        self._start_server()
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        try:
            payload = json.dumps({"prompt": "keep-alive", "client_job_id": "ka-1"}).encode()
            conn.request(
                "POST", "/api/generate/async", body=payload,
                headers={"Content-Type": "application/json"},
            )
            first = conn.getresponse()
            first.read()
            self.assertEqual(first.status, 202)

            conn.request("GET", "/api/workflow")
            second = conn.getresponse()
            data = json.loads(second.read().decode("utf-8"))
            self.assertEqual(second.status, 200)
            self.assertTrue(data["ok"])
        finally:
            conn.close()
            self.release.set()

    def test_progress_and_download_url_survive_reload(self):
        """成功任务在重启后仍能查到，并按新请求的 Host 给出下载地址。"""
        self._start_server(job_retention=5)
        status, body, _ = self._submit({"prompt": "重启也要能拿回图", "client_job_id": "reload-1"})
        self.assertEqual(status, 202, body)
        self.release.set()
        self.assertEqual(self._wait_job("reload-1")["status"], server.JOB_STATUS_SUCCEEDED)

        # 关掉服务，用同一个 state_dir 重新起一个（等价于进程重启）
        self._stop_server()
        self.release.set()
        self._start_server(job_retention=5)

        status, body, _ = self._get_job("reload-1", host="10.9.9.9:8000")
        self.assertEqual(status, 200, body)
        self.assertEqual(body["status"], server.JOB_STATUS_SUCCEEDED)
        self.assertEqual(len(body["images"]), 1)
        self.assertEqual(urlparse(body["images"][0]["download_url"]).hostname, "10.9.9.9")
        status, _, raw = self._request("GET", "/files/" + FAKE_FILENAME)
        self.assertEqual(status, 200)
        self.assertEqual(raw, FAKE_PNG)


if __name__ == "__main__":
    unittest.main(verbosity=2)
