#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""照片库（PhotoStore + /api/photos + 「全身照片」参考图）的测试。

只用标准库（``unittest`` / ``http.client``），不引入任何新依赖，也不需要真的
ComfyUI 或 GPU：

* ``PhotoStoreTest``   —— 直接测照片库：落盘/取回、magic bytes 嗅探、列表排序、
                         删除、上限校验、路径穿越拒绝。
* ``PhotoHttpTest``    —— 在临时目录里真起一个 HTTP 服务（``port=0`` 让系统分配端口），
                         把 ``server.run_generate`` 换成假实现，跑完整链路：
                         上传 → 列表 → 取原图 → 下载头 → 删除 → 404，
                         以及 ``/api/generate`` 带 ``full_body_photo_id`` 时的参数形状。
* ``FullBodyPhotoTest`` —— 纯单元测试 ``apply_full_body_photo()``：照片占 image1、
                         用户的图顺延到 image2、``no_reference`` 被强制 False、
                         超出上限被截断并如实报告 ``dropped``。

运行::

    cd refs/api-comfy && python3 -m unittest test_photos -v

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

# 一张"假 PNG"（magic bytes 是真的）：内容不重要，重点是能真落盘并原样读回来
FAKE_PNG = b"\x89PNG\r\n\x1a\n" + b"photo-store-payload" * 24
# 一张"假 JPEG"：用来验证扩展名靠 magic bytes 嗅探而不是靠文件名
FAKE_JPEG = b"\xff\xd8\xff\xe0" + b"jpeg-payload" * 8
FAKE_IMAGES_NAME = "user-reference.png"


def data_url(blob: bytes, mime: str = "image/png") -> str:
    """把字节包成 ``data:image/png;base64,...``，模拟客户端的写法。"""
    import base64

    return "data:%s;base64,%s" % (mime, base64.b64encode(blob).decode("ascii"))


# --------------------------------------------------------------------------- #
# 1) 照片库单元测试
# --------------------------------------------------------------------------- #
class PhotoStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="photos-store-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.root = os.path.join(self.tmp, "photos")

    def _store(self) -> "server.PhotoStore":
        return server.PhotoStore(self.root)

    def test_save_writes_file_and_sidecar(self):
        store = self._store()
        info = store.save(FAKE_PNG, "我的照片.png")
        photo_id = info["photo_id"]
        self.assertRegex(photo_id, server.PHOTO_ID_RE)
        self.assertEqual(info["mime"], "image/png")
        self.assertEqual(info["size_bytes"], len(FAKE_PNG))
        self.assertEqual(info["filename"], "我的照片.png")
        # 图片本体 + sidecar 都在，且不留 .tmp
        names = sorted(os.listdir(self.root))
        self.assertEqual(names, sorted(["%s.png" % photo_id, "%s.json" % photo_id]))
        with open(os.path.join(self.root, "%s.png" % photo_id), "rb") as fh:
            self.assertEqual(fh.read(), FAKE_PNG)
        with open(os.path.join(self.root, "%s.json" % photo_id), "r", encoding="utf-8") as fh:
            meta = json.load(fh)
        self.assertEqual(meta["photo_id"], photo_id)
        self.assertEqual(meta["size_bytes"], len(FAKE_PNG))

    def test_ext_follows_magic_bytes_when_filename_missing(self):
        store = self._store()
        self.assertTrue(store.save(FAKE_JPEG)["path"].endswith(".jpg"))
        # 文件名瞎写（.txt）时以内容为准，再兜底 png
        self.assertTrue(store.save(FAKE_PNG, "胡写的名字.txt")["path"].endswith(".png"))
        self.assertTrue(store.save(b"not-an-image-at-all")["path"].endswith(".png"))

    def test_save_rejects_empty_and_oversize(self):
        store = self._store()
        with self.assertRaises(server.ApiError) as empty:
            store.save(b"")
        self.assertEqual(empty.exception.status, 400)
        with mock.patch.object(server, "PHOTO_MAX_BYTES", 16):
            with self.assertRaises(server.ApiError) as big:
                store.save(b"x" * 17)
        self.assertEqual(big.exception.status, 413)

    def test_path_of_list_and_describe(self):
        store = self._store()
        first = store.save(FAKE_PNG, "a.png")
        second = store.save(FAKE_JPEG, "b.jpg")
        self.assertIsNotNone(store.path_of(first["photo_id"]))
        self.assertIsNone(store.path_of("不存在"))
        listed = store.list()
        self.assertEqual([item["photo_id"] for item in listed], [second["photo_id"], first["photo_id"]])
        # 列表里没有 sidecar 造成的重复项，也不含 path 以外的结构漂移
        self.assertEqual(len(store), 2)
        described = store.describe(first["photo_id"])
        self.assertEqual(described["filename"], "a.png")
        self.assertEqual(described["size_bytes"], len(FAKE_PNG))

    def test_path_of_rejects_traversal(self):
        store = self._store()
        for evil in ("../../etc/passwd", "..", ".", "a/b", "", "ab", "x" * 65):
            self.assertIsNone(store.path_of(evil), evil)

    def test_delete_removes_file_and_sidecar(self):
        store = self._store()
        info = store.save(FAKE_PNG)
        photo_id = info["photo_id"]
        self.assertTrue(store.delete(photo_id))
        self.assertEqual(os.listdir(self.root), [])
        self.assertIsNone(store.path_of(photo_id))
        self.assertFalse(store.delete(photo_id))   # 再删一次：False（HTTP 层据此 404）

    def test_survives_restart(self):
        info = self._store().save(FAKE_PNG, "restart.png")
        reopened = self._store()
        self.assertEqual(reopened.describe(info["photo_id"])["filename"], "restart.png")


# --------------------------------------------------------------------------- #
# 2) /api/photos 与 /api/generate 的 HTTP 端到端测试
# --------------------------------------------------------------------------- #
class PhotoHttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="photos-http-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.out_dir = os.path.join(self.tmp, "outputs")
        self.state_dir = os.path.join(self.tmp, "state")
        self.photos_dir = os.path.join(self.tmp, "photos")
        self.seen_params = []
        self.httpd = None
        self.thread = None

    # -------------------------- 服务起停 -------------------------- #
    def _start_server(self):
        patcher = mock.patch.object(server, "run_generate", self._fake_run_generate)
        patcher.start()
        self.addCleanup(patcher.stop)
        # 测试里不需要访问日志与 [jobs] 状态日志，静音后失败信息才看得清
        for target, attr, stub in (
            (server, "_log_jobs", lambda message: None),
            (server.ComfyApiHandler, "log_message", lambda *args, **kwargs: None),
        ):
            quiet = mock.patch.object(target, attr, stub)
            quiet.start()
            self.addCleanup(quiet.stop)
        self.httpd = server.make_server(
            "127.0.0.1",
            0,                      # 端口 0：让内核分配空闲端口
            "http://127.0.0.1:1",   # 假 ComfyUI 地址，永远不会被真的连接
            self.out_dir,
            None,
            # manager=None 等价于 --service-dry-run：绝不碰真实进程/显存
            scheduler_enabled=False,
            config={"generate_timeout": 30},
            state_dir=self.state_dir,
            photos_dir=self.photos_dir,
        )
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, name="test-httpd", daemon=True)
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

    def _fake_run_generate(self, ctx, params):
        """伪造一次成功生成：图片真的写进 output_dir，并把参数记下来供断言。"""
        self.seen_params.append(params)
        path = os.path.join(ctx["output_dir"], "gen-out.png")
        with open(path, "wb") as fh:
            fh.write(FAKE_PNG)
        return {
            "prompt_id": "fake-prompt-id",
            "elapsed_sec": 0.01,
            "images": [
                {
                    "path": path,
                    "filename": "gen-out.png",
                    "subfolder": "",
                    "type": "output",
                    "size_bytes": len(FAKE_PNG),
                }
            ],
            "meta": {"seed": 42},
        }

    # -------------------------- HTTP 小工具 -------------------------- #
    def _request(self, method, path, body=None, headers=None):
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
            return response.status, data, raw, dict(response.getheaders())
        finally:
            conn.close()

    def _upload(self, blob=FAKE_PNG, filename=None):
        body = {"image": data_url(blob)}
        if filename:
            body["filename"] = filename
        status, data, _, _ = self._request("POST", "/api/photos", body)
        self.assertEqual(status, 200, data)
        self.assertTrue(data["ok"])
        return data["photo"]

    def _wait_job(self, key, timeout=10.0):
        """轮询 GET /api/jobs/<key> 直到终态（异步 worker 在别的线程里跑）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            status, data, _, _ = self._request("GET", "/api/jobs/%s" % key)
            self.assertEqual(status, 200, data)
            if data["status"] in server.JOB_TERMINAL_STATUSES:
                return data
            time.sleep(0.02)
        self.fail("任务 %s 在 %.0fs 内没有进入终态" % (key, timeout))

    # -------------------------- 用例 -------------------------- #
    def test_upload_then_download_same_bytes(self):
        self._start_server()
        photo = self._upload(filename="助手全身照.png")
        photo_id = photo["photo_id"]
        self.assertRegex(photo_id, server.PHOTO_ID_RE)
        self.assertEqual(photo["size_bytes"], len(FAKE_PNG))
        self.assertEqual(photo["mime"], "image/png")
        # 对外响应里绝不暴露服务端路径，但一定给得出下载地址
        self.assertNotIn("path", photo)
        self.assertEqual(urlparse(photo["url"]).path, "/api/photos/%s" % photo_id)
        self.assertIn("download=1", photo["download_url"])
        self.assertEqual(urlparse(photo["url"]).hostname, "127.0.0.1")

        status, _, raw, headers = self._request("GET", "/api/photos/%s" % photo_id)
        self.assertEqual(status, 200)
        self.assertEqual(raw, FAKE_PNG)
        self.assertEqual(headers.get("Content-Type"), "image/png")
        self.assertEqual(headers.get("Cache-Control"), "no-store")

        # ?download=1 走 attachment，文件名沿用上传时的原始名
        status, _, raw_dl, headers = self._request(
            "GET", "/api/photos/%s?download=1" % photo_id
        )
        self.assertEqual(status, 200)
        self.assertEqual(raw_dl, FAKE_PNG)
        self.assertIn("attachment", headers.get("Content-Disposition", ""))

    def test_list_contains_uploaded_photo(self):
        self._start_server()
        self.assertEqual(self._request("GET", "/api/photos")[1]["count"], 0)
        photo = self._upload()
        status, data, _, _ = self._request("GET", "/api/photos")
        self.assertEqual(status, 200)
        self.assertTrue(data["ok"])
        self.assertEqual(data["count"], 1)
        self.assertEqual([item["photo_id"] for item in data["photos"]], [photo["photo_id"]])
        self.assertTrue(data["photos"][0]["url"].endswith("/api/photos/%s" % photo["photo_id"]))

    def test_bad_and_traversal_ids_are_rejected(self):
        self._start_server()
        photo = self._upload()
        # 相对路径穿越：URL 编码与不编码两种写法都必须被 id 白名单挡住
        for evil in ("../../etc/passwd", "..%2F..%2Fetc%2Fpasswd", "ab", "%2e%2e"):
            status, data, raw, _ = self._request("GET", "/api/photos/%s" % evil)
            self.assertIn(status, (400, 404), (evil, status, data))
            self.assertNotIn(b"root:", raw)     # 绝不能把 /etc/passwd 读出来
            self.assertFalse(data["ok"])
        # 不支持的 PUT：绝不返回照片内容
        status, _, raw, _ = self._request("PUT", "/api/photos/%s" % photo["photo_id"], {"x": 1})
        self.assertNotEqual(status, 200)
        self.assertNotEqual(raw, FAKE_PNG)

    def test_delete_then_get_404(self):
        self._start_server()
        photo = self._upload()
        photo_id = photo["photo_id"]
        status, data, _, _ = self._request("DELETE", "/api/photos/%s" % photo_id)
        self.assertEqual(status, 200, data)
        self.assertTrue(data["deleted"])
        status, data, _, _ = self._request("GET", "/api/photos/%s" % photo_id)
        self.assertEqual(status, 404)
        self.assertFalse(data["ok"])
        # 再删一次也是 404（幂等语义：不存在就是 404）
        self.assertEqual(self._request("DELETE", "/api/photos/%s" % photo_id)[0], 404)
        self.assertEqual(self._request("GET", "/api/photos")[1]["count"], 0)

    def test_generate_with_missing_photo_does_not_fail(self):
        self._start_server()
        status, data, _, _ = self._request(
            "POST", "/api/generate", {"prompt": "换成雪山背景", "full_body_photo_id": "deadbeefdeadbeef"}
        )
        self.assertEqual(status, 200, data)
        photo_meta = data["meta"]["full_body_photo"]
        self.assertFalse(photo_meta["used"])
        self.assertEqual(photo_meta["reason"], "照片不存在")
        self.assertEqual(photo_meta["photo_id"], "deadbeefdeadbeef")
        self.assertEqual(data["reference_images"], 0)
        # 生成照样发生（照片缺失只是"不用参考照片"，不是错误）
        self.assertEqual(len(self.seen_params), 1)
        self.assertEqual(self.seen_params[0]["images"], None)
        self.assertEqual(data["meta"]["seed"], 42)   # 原有 meta 字段一个都不能丢

    def test_generate_puts_photo_first_then_user_images(self):
        self._start_server()
        photo = self._upload()
        photo_path = server.PhotoStore(self.photos_dir).path_of(photo["photo_id"])
        self.assertTrue(photo_path and os.path.isfile(photo_path))
        user_image = os.path.join(self.tmp, FAKE_IMAGES_NAME)
        with open(user_image, "wb") as fh:
            fh.write(FAKE_PNG)

        status, data, _, _ = self._request(
            "POST",
            "/api/generate",
            {"prompt": "让她站在海边", "full_body_photo_id": photo["photo_id"], "images": [user_image]},
        )
        self.assertEqual(status, 200, data)
        sent = self.seen_params[-1]
        # 顺序契约：照片占 image1，用户本次传入的图顺延占 image2
        self.assertEqual(sent["images"], [photo_path, user_image])
        self.assertFalse(sent["no_reference"])
        self.assertEqual(data["reference_images"], 2)
        self.assertTrue(data["meta"]["full_body_photo"]["used"])
        self.assertEqual(data["meta"]["full_body_photo"]["dropped"], 0)
        self.assertEqual(data["meta"]["full_body_photo"]["photo_id"], photo["photo_id"])

    def test_generate_truncates_to_reference_limit(self):
        self._start_server()
        photo = self._upload()
        photo_path = server.PhotoStore(self.photos_dir).path_of(photo["photo_id"])
        user_image = os.path.join(self.tmp, FAKE_IMAGES_NAME)
        with open(user_image, "wb") as fh:
            fh.write(FAKE_PNG)
        status, data, _, _ = self._request(
            "POST",
            "/api/generate",
            {
                "prompt": "让她站在海边",
                "full_body_photo_id": photo["photo_id"],
                "images": [user_image, user_image],
            },
        )
        self.assertEqual(status, 200, data)
        sent = self.seen_params[-1]
        self.assertEqual(len(sent["images"]), server.MAX_REFERENCE_IMAGES)
        self.assertEqual(sent["images"][0], photo_path)   # 照片永远不被截掉
        self.assertEqual(data["meta"]["full_body_photo"]["dropped"], 1)
        self.assertEqual(data["reference_images"], server.MAX_REFERENCE_IMAGES)

    def test_generate_rejects_bad_photo_id_and_conflicts(self):
        self._start_server()
        # 非法 id
        status, data, _, _ = self._request(
            "POST", "/api/generate", {"prompt": "x", "full_body_photo_id": "../../etc/passwd"}
        )
        self.assertEqual(status, 400, data)
        self.assertIn("照片 id 非法", data["error"])
        # 非字符串
        status, data, _, _ = self._request(
            "POST", "/api/generate", {"prompt": "x", "full_body_photo_id": 123}
        )
        self.assertEqual(status, 400, data)
        # 与 no_reference 互斥
        photo = self._upload()
        status, data, _, _ = self._request(
            "POST",
            "/api/generate",
            {"prompt": "x", "full_body_photo_id": photo["photo_id"], "no_reference": True},
        )
        self.assertEqual(status, 400, data)
        self.assertIn("互斥", data["error"])
        # 参数错了就绝不该跑生成
        self.assertEqual(self.seen_params, [])

    def test_generate_without_photo_keeps_old_behaviour(self):
        self._start_server()
        status, data, _, _ = self._request("POST", "/api/generate", {"prompt": "一只猫"})
        self.assertEqual(status, 200, data)
        self.assertEqual(
            data["meta"]["full_body_photo"],
            {"photo_id": None, "used": False, "reason": None},
        )
        self.assertEqual(data["reference_images"], 0)
        self.assertEqual(self.seen_params[-1]["images"], None)

    def test_images_over_new_limit_rejected(self):
        self._start_server()
        status, data, _, _ = self._request(
            "POST", "/api/generate", {"prompt": "x", "images": ["a.png", "b.png", "c.png"]}
        )
        self.assertEqual(status, 400, data)
        self.assertIn("参考图最多 %d 张" % server.MAX_REFERENCE_IMAGES, data["error"])

    def test_async_job_records_photo_meta(self):
        """异步链路也必须把照片生效信息落进任务记录（GET /api/jobs 能看到）。"""
        self._start_server()
        photo = self._upload()
        user_image = os.path.join(self.tmp, FAKE_IMAGES_NAME)
        with open(user_image, "wb") as fh:
            fh.write(FAKE_PNG)
        status, data, _, _ = self._request(
            "POST",
            "/api/generate/async",
            {"prompt": "全身照", "full_body_photo_id": photo["photo_id"], "images": [user_image]},
        )
        self.assertEqual(status, 202, data)
        final = self._wait_job(data["job_id"])
        self.assertEqual(final["status"], server.JOB_STATUS_SUCCEEDED, final)
        self.assertTrue(final["meta"]["full_body_photo"]["used"])
        self.assertEqual(final["meta"]["full_body_photo"]["photo_id"], photo["photo_id"])
        self.assertEqual(final["request"]["full_body_photo_id"], photo["photo_id"])
        self.assertTrue(final["request"]["has_reference_images"])
        # 照片确实被放到了 image1 的位置
        sent = self.seen_params[-1]
        self.assertEqual(sent["images"][0], server.PhotoStore(self.photos_dir).path_of(photo["photo_id"]))


# --------------------------------------------------------------------------- #
# 3) apply_full_body_photo 单元测试
# --------------------------------------------------------------------------- #
class FullBodyPhotoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="photos-apply-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.store = server.PhotoStore(os.path.join(self.tmp, "photos"))
        self.info = self.store.save(FAKE_PNG)
        self.photo_path = self.store.path_of(self.info["photo_id"])

    def _ctx(self, with_store=True):
        return {"photo_store": self.store} if with_store else {}

    def test_no_photo_id_is_noop(self):
        params = {"images": None, "no_reference": False, "full_body_photo_id": None}
        info = server.apply_full_body_photo(params, self._ctx())
        self.assertEqual(info, {"photo_id": None, "used": False, "reason": None})
        self.assertIsNone(params["images"])
        self.assertEqual(params["full_body_photo"], info)

    def test_photo_store_missing(self):
        params = {"images": None, "full_body_photo_id": self.info["photo_id"]}
        info = server.apply_full_body_photo(params, self._ctx(with_store=False))
        self.assertFalse(info["used"])
        self.assertEqual(info["reason"], "未启用照片库")

    def test_photo_goes_first_and_no_reference_forced_false(self):
        params = {
            "images": ["user-1.png", "user-2.png"],
            "no_reference": True,     # 就算上游真给了 True，也要被照片纠正回来
            "full_body_photo_id": self.info["photo_id"],
        }
        info = server.apply_full_body_photo(params, self._ctx())
        self.assertTrue(info["used"])
        self.assertIsNone(info["reason"])
        self.assertEqual(info["filename"], os.path.basename(self.photo_path))
        self.assertEqual(info["path"], self.photo_path)
        # 顺序契约：照片先占 image1；上限 2 张，所以只保留照片 + 用户第一张
        self.assertEqual(params["images"], [self.photo_path, "user-1.png"])
        self.assertFalse(params["no_reference"])
        self.assertEqual(info["dropped"], 1)

    def test_missing_photo_reports_reason_without_raising(self):
        params = {"images": ["user-1.png"], "full_body_photo_id": "0123456789abcdef"}
        info = server.apply_full_body_photo(params, self._ctx())
        self.assertFalse(info["used"])
        self.assertEqual(info["reason"], "照片不存在")
        self.assertEqual(params["images"], ["user-1.png"])   # 用户的图原样保留


if __name__ == "__main__":
    unittest.main()
