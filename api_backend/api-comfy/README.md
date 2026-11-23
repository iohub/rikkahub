# ComfyUI 图像服务 · 显存编排台

把本机已在的 ComfyUI（`Qwen-Image-Edit-Rapid-Aio V3` 工作流）包装成**按需独占显存的 HTTP 服务 + WebUI**：
收到出图请求时自动把 LLM/TTS 请出显存、拉起 ComfyUI、生成完把图片回给你，再**在后台**把显存还给 LLM/TTS。

* 只用 **Python 3.10 标准库**（`http.server` / `urllib` / `json` / `base64`），不装 requests / Flask / FastAPI。
* **不修改 ComfyUI 安装目录里的任何文件**：参考图走 `/upload/image`，结果走 `/view`，本服务再落一份到 `./outputs/`。
* 单卡 RTX 3090（24576 MiB）是唯一约束：ComfyUI 峰值 ≈22.5 GB，LLM ≈13 GB，TTS ≈3 GB，**两组不可能同时驻留**，
  所以所有"用显存"的动作都必须经过同一把编排锁。

---

## 目录

1. [架构](#1-架构)
2. [快速开始（推荐顺序）](#2-快速开始推荐顺序)
3. [CLI 参数](#3-cli-参数)
4. [HTTP 接口](#4-http-接口)
5. [显存编排时序](#5-显存编排时序)
6. [WebUI 使用说明](#6-webui-使用说明)
7. [常见问题](#7-常见问题)
8. [故障排查](#8-故障排查)
9. [附录：CLI 单次生成与工作流模板](#9-附录cli-单次生成与工作流模板)

---

## 1. 架构

### 1.1 组件关系（文字版）

```
                  浏览器
                    │  GET /  ·  GET /api/*  ·  POST /api/*
                    ▼
        ┌───────────────────────────────┐
        │ server.py                     │  ThreadingHTTPServer（HTTP/1.1, 标准库）
        │  · WebUI（内联 HTML/JS）       │
        │  · /api/generate 编排入口      │
        │  · /files/<落盘名> 图片回源     │
        └───────┬───────────────┬───────┘
                │               │
   编排（起停/互斥）│               │ 生成（子进程）
                ▼               ▼
   ┌────────────────────┐   ┌──────────────────────────────┐
   │ service_manager.py │   │ cli.py --json                │
   │  ServiceManager    │   │  → runner.build_prompt()     │
   │   · 编排锁 RLock    │   │  → comfy_client.ComfyClient  │
   │   · find_pids      │   │     /upload/image /prompt    │
   │   · nvidia-smi 显存 │   │     /history /queue /view    │
   │   · dry_run 模拟    │   └──────────────┬───────────────┘
   └─────┬───────┬──────┘                  │ HTTP
         │       │                          ▼
         │       │                 ┌──────────────────┐
         │       │                 │ ComfyUI :8188    │
         │       │                 └──────────────────┘
         │       └────────────────────────┐
         ▼                                ▼
   llm :6008 / tts :9080  ← 由 server 按需起停（run-*.sh）
```

### 1.2 目录结构

```
api-comfy/
├── server.py               # HTTP API + WebUI + 显存编排入口（本次重点）
├── service_manager.py      # GPU 互斥编排器：进程发现/起停/显存等待/互斥锁/dry-run
├── cli.py                  # 命令行入口：一次生成（server 内部也用它）
├── runner.py               # 业务封装：load_template / build_prompt / generate
├── comfy_client.py         # 纯标准库 ComfyUI 客户端（上传/排队/等待/下载）
├── ui_to_api.py            # UI 格式工作流 → API 格式转换器
├── workflows/
│   └── qwen_image_edit_api.json   # 归一化的 API 模板（11 个节点）
├── outputs/                # 生成结果落盘（下载源），*.png 不入库
├── state/                  # 异步任务表 jobs.json（不入库，--state-dir 可改）
├── logs/                   # 受管服务的日志：llm.log / tts.log / comfy.log
├── run-HuiHuiQwen3827B.sh  # LLM 启动脚本（前台一次性命令，:6008）
├── run-ttsQwenQ8.sh        # TTS 启动脚本（while true 重启壳，:9080）
├── test_jobs.py            # 异步任务子系统的单测 + HTTP 端到端测试（标准库 unittest）
└── README.md
```

### 1.3 模块职责

| 模块 | 对外 API | 说明 |
|---|---|---|
| `server.py` | `main(argv)` / `make_server(host, port, base_url, output_dir, template_path, *, manager, restore_scheduler, scheduler_enabled, public_base_url, config, token, cors_origin, state_dir, job_retention)` / `handle_generate(payload, ctx, *, public_base=None, progress=None)` / `prepare_generate_params(payload, ctx)` / `JobStore(state_dir, *, retention)` | HTTP 层 + 编排入口；`ctx` 是 `ComfyApiHandler.app`（客户端、模板、manager、scheduler、config、job_store…） |
| `service_manager.py` | `ServiceManager(specs, dry_run=..., comfy_min_free_mib=..., llm_min_free_mib=...)`；`ensure_mode()` / `comfy_session()` / `start_service()` / `stop_service()` / `snapshot()` / `log_tail()` / `shutdown()` | 只做编排，不含 HTTP 逻辑；`service_status/snapshot/current_mode/vram` **从不开锁**，UI 轮询不会被生成任务卡住 |
| `cli.py` | `python3 cli.py -p "..." --json` | 退出码 `0/1/2/3/4` = 成功/ComfyUI 报错/参数错/超时/连不上 |
| `runner.py` | `load_template()` / `build_prompt()` / `generate()` / `parse_size()` | 按语义定位节点（`TextEncode*` / `EmptyLatentImage` / `SaveImage`），不硬编码节点 id |
| `comfy_client.py` | `ComfyClient(base_url)`：`system_stats/upload_image/queue_prompt/wait/download_outputs` | 落盘用 `_unique_path()`：重名时写成 `xxx_1.png` |

### 1.4 一次 `POST /api/generate` 的数据流

```
HTTP POST /api/generate
  → handle_generate()                      参数校验（prompt/size/images/timeout…）
  → ServiceManager.comfy_session(restore=None)
        · 拿编排锁（拿不到 → ServiceBusy → 409）
        · ensure_mode("comfy")：停 llm/tts → 等 vram ≥ 16000 MiB → 起 ComfyUI
  → RestoreScheduler.comfy_wanted()        告诉调度器"正在用 ComfyUI"，让 grace 中的恢复让位
  → 子进程 cli.py --json --timeout <T> --base-url <ComfyUI>
        · build_prompt → /upload/image → /prompt → /history 轮询 → /view 下载 → 写 outputs/
  → _normalize_images()                    filename 取**落盘名**，补 download_url
  → 回包（HTTP 200）
  → finally：RestoreScheduler.schedule()    只起线程，不等它；grace 3s 后
                                           ensure_mode("llm")：停 ComfyUI → 等 vram ≥ 12000 → 起 llm/tts
```

> **重要**：恢复是由本服务在回包**之后**调度的（`comfy_session(restore=None)`），
> 所以 HTTP 响应不会等 LLM 那 11GB+ 模型重载。响应里的 `resource.restore_scheduled`
> 就是这个调度是否排上了。

---

## 2. 快速开始（推荐顺序）

> 三条命令对应三个风险等级。**强烈建议按顺序走一遍**，每步都确认上一步没问题再往下。

### 2.1 第 0 步：探活（只读）

```bash
python3 --version                                  # 需要 3.10+，无需 pip install
curl -s http://127.0.0.1:8188/system_stats | head -c 200   # ComfyUI 在不在
python3 service_manager.py --self-test             # 只读/纯函数/dry-run 自检，不碰 GPU
```

### 2.2 第 1 步：**模拟模式**验证接口（不启停任何进程、不占显存）

```bash
python3 server.py --port 8080 --service-dry-run --no-auto-start
# 打开 http://127.0.0.1:8080/ ，页面右上角会出现「DRY-RUN 模拟模式」徽章
curl -s http://127.0.0.1:8080/api/services | python3 -m json.tool | head -30
curl -s -X POST http://127.0.0.1:8080/api/services/comfy/start   # 只改内存状态
curl -s -X POST http://127.0.0.1:8080/api/mode/llm
```

这一步可以放心点所有按钮：`dry_run=True` 时 `_spawn/_stop_processes/_cleanup_residual/_wait_ready/_vram_step` 全部短路，
**不 spawn、不 kill、不读显存做判断**。

### 2.3 第 2 步：**真实编排，但不自动切换**（先手动点一次"切到 LLM 模式"）

```bash
python3 server.py --port 8080 --no-auto-start
# 打开页面 → 点「切到 LLM 模式」→ 确认 llm/tts 两个卡片变成绿色"运行中"
# 然后填提示词 → 点「生成」→ 拿到图片；几秒后状态行会显示"有待执行的恢复"→ 变回 LLM 模式
```

为什么先 `--no-auto-start`：自动编排会在启动瞬间就动显存。手动点一次能让你看清
`steps`（停哪些、等多少显存、起哪些）到底发生了什么。

### 2.4 第 3 步：正式运行（开机即切 LLM 模式）

```bash
python3 server.py --host 0.0.0.0 --port 8080        # --auto-start 默认开启
```

⚠️ `--auto-start` 会在启动后台线程里**先停止正在运行的 ComfyUI（若有），其排队/进行中的任务会被中断**，
然后再拉起 llm + tts。已经有人在用 ComfyUI 时不要随手重启本服务。

想放后台常驻：

```bash
nohup python3 server.py --port 8080 > logs/server.out 2>&1 &
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/health
```

---

## 3. CLI 参数

### 3.1 `server.py`（全部参数）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--host` | `127.0.0.1` | 监听地址。对外暴露请配合 `--token` 或反向代理 |
| `--port` | `8080` | 监听端口 |
| `--base-url` | `http://127.0.0.1:8188` | ComfyUI 地址（本服务通过它提交任务、下载图片） |
| `--output-dir` | `./outputs` | 结果图片落盘目录（`/files/<name>` 就是从这儿取） |
| `--state-dir` | `./state` | 异步任务表 `jobs.json` 的保存目录（与 `--output-dir` 一样相对 CWD） |
| `--job-retention` | `200` | 终态任务最多保留条数，超出按 `created_at` 淘汰最旧的（**不删** `output_dir` 里的图片） |
| `--photos-dir` | `<server.py 所在目录>/photos` | 照片库目录：`POST /api/photos` 上传的参考照片长期存这里（`photos/` 已在 `.gitignore` 里） |
| `--template` | `workflows/qwen_image_edit_api.json` | API 格式模板路径 |
| `--auto-start` | **开启** | 启动后后台线程切到 llm 模式（⚠ 会先停掉正在运行的 ComfyUI） |
| `--no-auto-start` | – | 不在启动时做模式编排（第 2 步推荐用它） |
| `--no-scheduler` | 未启用（即默认开启编排） | 加此参数关闭自动编排：`/api/generate` 不切模式，直接用当前运行的 ComfyUI |
| `--service-dry-run` | 关 | 透传 `ServiceManager(dry_run=True)`：只模拟启停，不碰真实进程与显存 |
| `--generate-engine` | `cli` | `cli` = 子进程跑 `cli.py --json`（推荐）；`internal` = 进程内 `runner.generate` |
| `--generate-timeout` | `1800` | 单次生成最长等待秒数。**同时决定** `cli.py --timeout`、编排锁等待（+600s）、子进程超时（+180s） |
| `--llm-script` | `run-HuiHuiQwen3827B.sh` | LLM 启动脚本（相对项目目录） |
| `--llm-port` | `6008` | LLM 端口 |
| `--tts-script` | `run-ttsQwenQ8.sh` | TTS 启动脚本（`while true` 壳） |
| `--tts-port` | `9080` | TTS 端口 |
| `--comfy-dir` | `/root/comfy/ComfyUI` | ComfyUI 安装目录（进程识别用 `main.py` 绝对路径） |
| `--comfy-python` | `/root/comfyenv/bin/python` | ComfyUI 解释器 |
| `--comfy-port` | `8188` | ComfyUI 端口 |
| `--comfy-cmd` | `/root/comfyenv/bin/python main.py --port 8188 --listen 127.0.0.1` | 自定义 ComfyUI 启动命令，支持 `{port}` / `{cwd}` 占位符；**你自己 ComfyUI 的启动参数就填这里** |
| `--logs-dir` | `./logs` | 受管服务日志目录（`llm.log` / `tts.log` / `comfy.log`） |
| `--public-base-url` | 空 | 下载地址的外部基址；缺省按请求 `Host` 头推导，再退回监听地址 |
| `--token` | 空（不鉴权） | 设置后 `/api/*` 与 `/files/*` 需要 `X-Auth-Token` 头或 `?token=`；`GET /` 仍公开 |
| `--cors-origin` | `*` | `Access-Control-Allow-Origin` 的取值，可收紧成具体源 |
| `--vram-comfy-min-mib` | `16000` | 拉起 ComfyUI 前要求的最小空闲显存（MiB） |
| `--vram-llm-min-mib` | `12000` | 拉起 LLM 前要求的最小空闲显存（MiB） |
| `--restore-target` | `llm` | 生成结束后恢复到的模式（`llm` / `none` 停在 comfy） |
| `--restore-mode` | `background` | `background`（回包后后台恢复）或 `blocking`（同步等模型重载） |
| `--restore-delay` | `3.0` | 后台恢复前的合并等待秒数（grace 窗口） |

### 3.2 `service_manager.py`（自检）

```bash
python3 service_manager.py --self-test
```

只读 / 纯函数 / dry-run 自检：读 `/proc`、探端口、探 HTTP、读 `nvidia-smi`，**不启停任何真实服务**。
第 5 项"6008 应为关闭"在 LLM 正在运行时必然 `FAIL`，属预期（它只是个信息项）。

### 3.3 `cli.py`（单次生成，见[附录 9.2](#92-cli-单次生成)）

---

## 4. HTTP 接口

### 4.1 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | WebUI（内联 HTML/JS；**不需要 token**，否则没入口录入令牌） |
| GET | `/api/health` | 服务 + ComfyUI 状态 + scheduler 只读快照。ComfyUI 不可达时返回 **503**（`ok:false`） |
| GET | `/api/services` | 三个受管服务 + 模式 + 显存 + 编排配置的全景快照 |
| GET | `/api/workflow` | 当前 API 模板 JSON 与模板路径 |
| GET | `/api/queue` | ComfyUI `/queue` 原始内容 |
| GET | `/api/services/<name>/log?lines=80` | 读日志尾部（`lines` 1~2000，默认 80） |
| GET | `/files/<filename>[?download=1]` | 从 `--output-dir` 下载图片（ComfyUI 关掉后仍可下）；**64KB 分块**发送 |
| POST | `/api/generate` | 编排式生成（自动切显存，回包后后台恢复） |
| POST | `/api/generate/async` | **异步**出图：立即 202（`client_job_id` 幂等命中回 200），任务表落 `jobs.json`，断线可重查 |
| GET | `/api/jobs/<key>` | 按 `job_id` 或 `client_job_id` 查任务进度/产物；`download_url` 按**当前请求 Host** 重算 |
| GET | `/api/jobs?limit=20&status=running,succeeded` | 最近任务列表（`created_at` 倒序，`limit` 1~100） |
| POST | `/api/photos` | 上传一张参考照片进**照片库**（长期保存），返回 `photo_id` 与下载地址 |
| GET | `/api/photos` | 照片库列表（`created_at` 倒序，每项带 `url` / `download_url`） |
| GET | `/api/photos/<id>[?download=1]` | 取照片原图；**64KB 分块**发送，`download=1` 走 `attachment` |
| DELETE | `/api/photos/<id>` | 删除照片（图片 + 元数据），不存在 → 404 |
| POST | `/api/prompt` | 只构建 API prompt、不执行（调试用） |
| POST | `/api/services/<name>/start` | 启动 `llm` / `tts` / `comfy` |
| POST | `/api/services/<name>/stop` | 停止 `llm` / `tts` / `comfy` |
| POST | `/api/mode/<llm\|comfy>` | 手动切模式（会先 `cancel_pending()` 取消挂起的后台恢复） |
| OPTIONS | `*` | CORS 预检，返回 204 |

`<name>` 只接受 `llm` / `tts` / `comfy`，其它一律 **404**。

### 4.2 curl 示例

```bash
# 健康检查（ComfyUI 不通时是 503）
curl -s http://127.0.0.1:8080/api/health | python3 -m json.tool

# 全景快照
curl -s http://127.0.0.1:8080/api/services | python3 -m json.tool

# 出图（最简）
curl -s -X POST http://127.0.0.1:8080/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"把背景换成雪山夜景","size":"768x768","steps":4}' | python3 -m json.tool

# 带参考图（本地路径会被上传到 ComfyUI 的 input/；也可直接填 input/ 里已有文件名）
curl -s -X POST http://127.0.0.1:8080/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"把外套换成红色","size":"1024x1024","images":["/data/ref1.png","already_in_input.jpg"]}'

# base64 / data URL（服务端落临时文件再传给 cli.py，绝不会塞进 argv）
curl -s -X POST http://127.0.0.1:8080/api/generate \
  -H 'Content-Type: application/json' \
  -d "{\"prompt\":\"换海边背景\",\"images\":[{\"base64\":\"$(base64 -w0 ref1.png)\"}]}"

# 异步出图（立即拿 job_id，断线也能用 client_job_id 找回）
curl -s -X POST http://127.0.0.1:8080/api/generate/async \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"把背景换成雪山夜景","size":"768x768","client_job_id":"app-20261123-0001"}'
# → 202 {"ok":true,"job_id":"20261123T203012-ab12cd34ef",...,"status_url":"/api/jobs/20261123T203012-ab12cd34ef"}

curl -s http://127.0.0.1:8080/api/jobs/app-20261123-0001 | python3 -m json.tool   # 也能用 job_id 查
curl -s 'http://127.0.0.1:8080/api/jobs?limit=10&status=running,succeeded' | python3 -m json.tool

# 手动切模式 / 起停单个服务
curl -s -X POST http://127.0.0.1:8080/api/mode/llm
curl -s -X POST http://127.0.0.1:8080/api/services/comfy/stop
curl -s 'http://127.0.0.1:8080/api/services/comfy/log?lines=40' | python3 -c 'import json,sys;print(json.load(sys.stdin)["log"])'

# 下载（--token 启用时加头或加查询参数）
curl -sO http://127.0.0.1:8080/files/ComfyUI_00009_.png
curl -s -H 'X-Auth-Token: abc' -OJ 'http://127.0.0.1:8080/files/ComfyUI_00009_.png?download=1'
```

### 4.3 `POST /api/generate` 请求字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `prompt` | string | **必填**，正向提示词 |
| `negative` | string | 负向提示词 |
| `images` | array | 1~2 张参考图。元素可以是：本地路径 / ComfyUI `input/` 内文件名 / `{"path":…}` / `{"filename":…}` / `{"base64":…}` / `{"data_url":…}` / 纯 `data:image/png;base64,…` 字符串 |
| `no_reference` | bool | `true` 时纯文生图（与 `images`、`full_body_photo_id` 互斥） |
| `full_body_photo_id` | string | 照片库里的照片 id（见 [4.7](#47-照片库postgetdelete-apiphotos)）。**固定占 `image1`**，本次 `images` 顺延占 `image2`；照片不存在时**不报错**，只是不生效（见响应的 `meta.full_body_photo`）。别名：`reference_photo_id` / `photo_id` |
| `seed` | int | 缺省随机 |
| `steps` / `cfg` / `denoise` | number | 采样参数 |
| `sampler` / `scheduler` | string | 如 `euler` / `simple` |
| `size` | string | `"WxH"`，按 8 对齐 |
| `width` / `height` | int | 与 `size` 二选一，必须同时给 |
| `batch_size` | int | 一次几张 |
| `filename_prefix` | string | SaveImage 前缀 |
| `timeout` | number | 覆盖 `--generate-timeout` |
| `return_base64` | bool | 响应里附带图片 base64 |

### 4.4 `POST /api/generate` 响应

```json
{
  "ok": true,
  "prompt_id": "766a0922-845f-47bc-b560-fc60b6970748",
  "elapsed_sec": 21.28,
  "elapsed_wall_sec": 21.9,
  "images": [
    {
      "filename": "ComfyUI_00009__1.png",
      "comfy_filename": "ComfyUI_00009_.png",
      "subfolder": "",
      "type": "output",
      "url": "http://127.0.0.1:8188/view?filename=ComfyUI_00009_.png&subfolder=&type=output",
      "saved_path": "/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png",
      "size_bytes": 731637,
      "download_url": "http://127.0.0.1:8080/files/ComfyUI_00009__1.png",
      "download_url_attachment": "http://127.0.0.1:8080/files/ComfyUI_00009__1.png?download=1"
    }
  ],
  "meta": {"seed": 2232042110076787348, "size": "768x768", "steps": 4, "cfg": 1},
  "output_dir": "/root/autodl-fs/api-comfy/outputs",
  "resource": {
    "mode": "comfy",
    "scheduler_enabled": true,
    "restore_target": "llm",
    "restore_mode": "background",
    "restore_scheduled": true,
    "entered_session": true,
    "timeout": 1800.0
  }
}
```

#### `download_url` 的语义（务必理解）

* `download_url` 指向的是**本服务落盘的副本**，不是 ComfyUI 的 `/view`；因此 ComfyUI 被停掉后链接依然有效。
* `filename` 是**真实落盘文件名**。`comfy_client._unique_path()` 在重名时会加 `_1`、`_2` 后缀，
  所以它可能和 ComfyUI 的原始名不同；原始名放在 `comfy_filename` 里方便对照。
  这样做的原因很实际：ComfyUI 重启后 `SaveImage` 计数会归零、重新产出 `ComfyUI_00009_.png`，
  如果下载地址沿用原始名，你点开拿到的会是**上一次的旧图**。
* 基址优先级：**合法**的请求 `Host` 头 → `--public-base-url` → 监听地址。
  非法 `Host`（含引号、`/`、`@`、尖括号等）会被忽略并回退，避免下载域名被劫持。
* 开 `--token` 后，`<img>`/`<a>` 这类没法加头的场景用 `?token=`；WebUI 会自动拼好。

### 4.5 错误码语义

统一响应体 `{"ok": false, "error": "…", "detail": …, ["resource": {…}]}`。

| HTTP | 场景 | 语义 / 你会看到什么 |
|---|---|---|
| 400 | 参数错误、`/files` 缺文件名、路径穿越、照片 id 非法 | `prompt` 为空、`size` 非法、images > 2 张、`no_reference` 与 `images` / `full_body_photo_id` 冲突、base64 解不出来 |
| 401 | `--token` 已启用但没给 / 给错 | `detail.auth_required=true`；带上 `X-Auth-Token` 或 `?token=` |
| 404 | 未知路径 / 未知服务名 | `/api/services/xyz/log` → `未知服务：xyz` |
| 409 | **服务正忙** | 编排锁被别的生成/切换占着，`lock_timeout` 内没拿到。**本次请求没动过显存，也不会取消别人已排的恢复**；等前一个任务结束后重试即可 |
| 411 | 缺 `Content-Length` | 手工构造请求才会遇到 |
| 413 | 请求体 > 32 MB | base64 大图建议改用路径 |
| 500 | 未知内部错误 | 服务不会因此退出，日志里有 traceback |
| 502 | **编排失败**或 ComfyUI 报错 | `detail.steps` 告诉你卡在哪一步（`stop:llm` / `vram` / `start:comfy` / `generate:comfy`…），`detail.log_tail` 是服务日志尾巴；**此时后台恢复已经被排上**（`resource.restore_scheduled=true`） |
| 503 | 连不上 ComfyUI | `--base-url` 错了 / ComfyUI 没起来 / 刚被停掉。同样是"已排恢复" |
| 504 | 等待超时 | `cli.py` 超过 `timeout` 没拿到结果；`detail.timeout` 是实际用的秒数 |

### 4.6 异步任务：`/api/generate/async` + `/api/jobs`

`POST /api/generate` 是**同步阻塞**的：请求线程里跑完整个生成才回包，客户端一断网
这次生成就"消失"了（图片还在 `output_dir` 里，但客户端拿不到 `prompt_id` 与地址）。
异步接口就是为了修这个：**提交与查询解耦，任务表落盘，重启后仍可追查**。

```
提交 POST /api/generate/async（字段完全同 /api/generate，外加可选 client_job_id）
  → 参数校验（失败 400，**不会**建任务）
  → client_job_id 已存在？ → 直接回该任务快照（200，幂等）
  → JobStore.create_or_get（锁内，同一 client_job_id 只会建一个任务）→ 起 daemon worker
  → 立即回 202 {"ok":true,"job_id":…,"status":"queued","status_url":"/api/jobs/<job_id>"}

worker: queued → orchestrating（拿编排锁/等显存/拉 ComfyUI）→ generating（已提交生成）
        → finalizing（整理产物/算下载地址）→ done | failed
        所有异常都落到任务记录上（ApiError → 它的 status/message/detail；其它 → 500）
```

请求字段：与 [4.3](#43-post-apigenerate-请求字段) 完全相同，另外加一个可选的

| 字段 | 类型 | 说明 |
|---|---|---|
| `client_job_id` | string | 客户端自己生成的幂等键，1~128 个 `[A-Za-z0-9._:-]`（非法 → 400）。同一个值重复提交只会有一个任务，命中时回 **200** 而不是 202 |

`GET /api/jobs/<key>` 的 `key` 先按 `job_id` 查，查不到再按 `client_job_id` 查——
所以**POST 响应丢包**时，客户端用自己生成的 `client_job_id` 照样能找回任务。
都查不到 → `404 {"ok":false,"error":"任务不存在：<key>"}`。

响应（单条）：

```json
{
  "ok": true,
  "job_id": "20261123T203012-ab12cd34ef",
  "client_job_id": "app-20261123-0001",
  "status": "succeeded",
  "stage": "done",
  "created_at": 1732345678.123,
  "started_at": 1732345679.2,
  "finished_at": 1732345700.3,
  "progress": {"elapsed_sec": 21.1, "timeout_sec": 1800.0},
  "prompt_id": "766a0922-845f-47bc-b560-fc60b6970748",
  "images": [
    {
      "filename": "ComfyUI_00009__1.png",
      "comfy_filename": "ComfyUI_00009_.png",
      "subfolder": "", "type": "output",
      "saved_path": "/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png",
      "size_bytes": 731637,
      "download_url": "http://192.168.1.7:8080/files/ComfyUI_00009__1.png",
      "download_url_attachment": "http://192.168.1.7:8080/files/ComfyUI_00009__1.png?download=1"
    }
  ],
  "error": null, "http_status": null, "detail": null,
  "resource": {"mode": "comfy", "scheduler_enabled": true, "restore_scheduled": true, "...": "…"},
  "meta": {"seed": 2232042110076787348, "size": "768x768", "steps": 4, "cfg": 1},
  "request": {"prompt": "把背景换成雪山夜景", "negative": null, "size": "1024x1024",
              "steps": 4, "seed": 42, "batch_size": 1, "has_reference_images": false},
  "status_url": "/api/jobs/20261123T203012-ab12cd34ef"
}
```

| 字段 | 语义 |
|---|---|
| `status` | `queued` / `running` / `succeeded` / `failed` / `interrupted`（重启打断） |
| `stage` | `queued` / `orchestrating` / `generating` / `finalizing` / `done` / `failed`；`interrupted` 时保留中断时所处的阶段 |
| `progress` | `elapsed_sec` 从 `started_at`（未开始则 `created_at`）算到 `finished_at`（未结束则"现在"）；`timeout_sec` 是这次生成实际用的超时 |
| `images` | **只有 `succeeded` 才带图**，其余状态一律 `[]` |
| `error` / `http_status` / `detail` | 失败时填：内部 `ApiError` 的 `message` / `status` / `detail`（`resource` 也一起透出） |

> **关键**：`download_url` 是**每次查询时**用当前请求的 `Host` 头重算的（复用了同步接口那套
> `_public_base` + `_normalize_images`）。客户端从 Wi-Fi 换到 4G 之后 IP 会变，绝不能沿用
> 提交时那台设备的地址——所以 `jobs.json` 里只存"落盘事实"，不存 URL。

`GET /api/jobs?limit=20&status=running,succeeded`：按 `created_at` 倒序列最近任务，
`limit` 默认 20（范围 1~100，越界会被 clamp），`status` 逗号分隔多值过滤（非法值 → 400）。
列表里为控体积省略了 `detail` 与 `request.prompt`。App 端本地状态丢失时用它把
"之前那次出图"找回来。

#### 持久化与重启

* 任务表在 `<state-dir>/jobs.json`（`--state-dir`，默认 `./state`）。整表快照写法：
  临时文件 → `fsync` → `os.replace`，因此文件任何时刻都是完整可解析的；所有读写都在
  同一把 `RLock` 里。
* 每次状态/阶段变化都落盘（阶段切换是低频事件）；`elapsed_sec` 是查询时现算的，
  不需要高频刷盘。
* **重启**：加载时把残留的 `queued`/`running` 一律标成 `interrupted`，
  `error="服务重启，任务已中断"`、`finished_at=now`（worker 线程已经不存在了，
  留着 running 会让客户端永远轮询）。
* **保留**：终态任务最多 `--job-retention`（默认 200）条，超出按 `created_at` 淘汰最旧的；
  运行中的任务永不淘汰；**`output_dir` 里的图片文件一个都不删**。
* `jobs.json` 损坏/不可解析时**不会让服务起不来**：打日志、把坏文件重命名成
  `jobs.json.bad`，按空表启动。

#### 测试

```bash
cd refs/api-comfy && python3 -m unittest test_jobs -v
```

`test_jobs.py` 只用标准库：`JobStoreTest` 直接测任务表（幂等 / 按 `client_job_id` 解析 /
持久化重载 / 重启中断修复 / 保留淘汰 / 坏文件兜底），`JobHttpTest` 在临时目录里用
`make_server("127.0.0.1", 0, …)` 起一个 **ephemeral 端口**的服务并 monkeypatch
`run_generate`，跑通"提交 → 轮询 → 换 Host 查下载地址 → 列表 → `/files` 下载"整条链路，
结束 `shutdown()` + `server_close()`，不留常驻进程。

### 4.7 照片库：`POST/GET/DELETE /api/photos`

**为什么要有照片库**：出图是"改造"而不是"从零画"，很多时候需要一张**固定的人像参考**
（例如助手自己的全身照）。把这种照片塞进每一次请求的 `images` 里既费流量（几百 KB 的
base64），又容易被客户端漏传。所以照片**上传一次、长期保存**，之后出图只带一个
`photo_id`，由服务端自己取文件。

落盘（`--photos-dir`，默认 `<server.py 所在目录>/photos`，已在 `.gitignore` 里）：

```
photos/<photo_id>.png     ← 图片本体（png/jpg/webp，按 magic bytes 嗅探）
photos/<photo_id>.json    ← 元数据（原始文件名 / mime / 大小 / 创建时间）
```

`photo_id` 是 `os.urandom(8).hex()`（16 位十六进制，不可枚举）；单张上限 **16 MB**。
**照片是私密内容**：对外响应里只有 `photo_id` 与下载地址，绝不暴露服务端路径。

```bash
# 1) 上传（data URL / 裸 base64 / 裸 base64 放 data_url 字段都行，filename 可选）
curl -s -X POST http://127.0.0.1:8080/api/photos \
  -H 'Content-Type: application/json' \
  -d "{\"image\":\"data:image/png;base64,$(base64 -w0 full_body.png)\",\"filename\":\"full_body.png\"}"
# → {"ok":true,"photo":{"photo_id":"9f2c…","filename":"full_body.png","mime":"image/png",
#                       "size_bytes":183920,"created_at":1764021234.5,
#                       "url":"http://127.0.0.1:8080/api/photos/9f2c…",
#                       "download_url":"…/api/photos/9f2c…?download=1"}}

# 2) 列表 / 取原图 / 下载 / 删除
curl -s http://127.0.0.1:8080/api/photos | python3 -m json.tool
curl -s http://127.0.0.1:8080/api/photos/9f2c… -o /tmp/same.png
curl -sOJ 'http://127.0.0.1:8080/api/photos/9f2c…?download=1'
curl -s -X DELETE http://127.0.0.1:8080/api/photos/9f2c…      # → {"ok":true,"deleted":true,…}

# 3) 出图时用上这张照片
curl -s -X POST http://127.0.0.1:8080/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"让她站在海边，全身照","full_body_photo_id":"9f2c…","size":"832x1216"}'
```

`full_body_photo_id` 的语义（**顺序很重要**）：

* workflow 的正向编码节点 `TextEncodeQwenImageEditPlus` 只有 `image1` / `image2` 两个
  图片输入，`runner.build_prompt()` 按列表顺序依次填槽，所以**照片固定占 `image1`**
  （"这个人长什么样"的锚点），本次 `images` 里的图顺延占 `image2`（"往哪改"的指令图）。
* 合并后超过 **2 张**的部分从尾部丢掉，`meta.full_body_photo.dropped` 会如实报出被丢掉
  的张数（绝不静默丢图）。
* 照片**不存在**（被删了 / id 写错）时**不报错**：本次就当作没有照片，响应里
  `meta.full_body_photo = {"photo_id":"9f2c…","used":false,"reason":"照片不存在"}`。
  出图失败的原因应该是 prompt/模型，而不是一张参考照片。
* `no_reference=true` 与 `full_body_photo_id` **互斥**（→ 400）；带了照片就一定会走
  "带参考图" 路径，`no_reference` 被强制为 `false`。
* 别名：`reference_photo_id` / `photo_id`。
* 响应顶层多一个 `reference_images`（整数）＝ 本次真正送给 workflow 的参考图张数
  （含照片，含截断后的真实值）；同时 `meta.full_body_photo` 回显照片是否生效。

#### 测试

```bash
cd refs/api-comfy && python3 -m unittest test_photos -v
```

`test_photos.py` 同样只用标准库：`PhotoStoreTest` 直接测落盘/嗅探/列表/删除/路径穿越拒绝，
`PhotoHttpTest` 用 `make_server(…, photos_dir=<临时目录>)` 起服务、monkeypatch
`run_generate`，跑通"上传 → 列表 → 取原图 → 下载头 → 删除 → 404"以及
"`/api/generate` 带照片时参数顺序"；`FullBodyPhotoTest` 纯单元测 `apply_full_body_photo()`
（照片占 `image1`、超限截断、`no_reference` 纠正、照片缺失不抛错）。

---

## 5. 显存编排时序

### 5.1 正常路径

```
启动
  │  server.py --auto-start（默认）
  ├─▶ [后台线程 auto-start] ensure_mode("llm")
  │      stop:comfy（若在跑）→ vram ≥ 12000 MiB → start:llm → start:tts
  ▼
稳态：mode = llm（llm:6008 + tts:9080 驻留显存）

POST /api/generate
  │
  ├─▶ ① handle_generate 校验参数，记下 attempted=True（"可能已动显存"）
  ├─▶ ② comfy_session 拿编排锁（拿不到 → 409，什么都不改）
  │      stop:llm  →  stop:tts
  │      vram   →  等空闲显存 ≥ 16000 MiB（最多等一段时间，超时就是 502）
  │      start:comfy → 等 GET /system_stats 就绪（ComfyUI 冷启动约 1~3 分钟）
  ├─▶ ③ RestoreScheduler.comfy_wanted()（告诉正在 grace 的恢复：让位）
  ├─▶ ④ cli.py --json --timeout T：build_prompt → /upload/image → /prompt
  │      → /history 轮询 → /view 下载 → 写 outputs/
  ├─▶ ⑤ 回包 HTTP 200（含 download_url / resource）
  └─▶ ⑥ finally：RestoreScheduler.schedule()
         grace 3s（期间又来出图请求 → 放弃本次恢复，省一次 22GB 重载）
         ensure_mode("llm")：stop:comfy → vram ≥ 12000 MiB → start:llm → start:tts
         （background 模式：只起线程，不等它；blocking 模式：同步等完）
```

### 5.2 失败路径（为什么现在都会恢复）

| 失败点 | 结果 | 恢复 |
|---|---|---|
| 编排锁没拿到 | 409 | **不恢复、也不取消别人的恢复**（本次根本没动显存） |
| `stop:llm` 之后 `start:comfy` 失败（420s 没就绪 / OOM / 缺 .so） | 502 + `detail.steps` | ✅ 已调度：把 llm/tts 重新拉起来 |
| `cli.py` 连不上 ComfyUI | 503 | ✅ 已调度 |
| `cli.py` 超时 | 504 | ✅ 已调度 |
| 回包之后恢复本身失败 | 只写 `[restore]` 日志 | 机器停在 comfy 模式，需要人工看一眼（`/api/mode/llm`） |

> 恢复判定用的是"**本次请求可能已经动过显存**"（`attempted`），而不是"是否成功进入会话"。
> 早期版本用后者，一旦 `ensure_mode("comfy")` 在停完 LLM/TTS 之后失败，两侧都不会恢复，
> 机器就停在"LLM/TTS 已停、ComfyUI 未起"的空档里。

### 5.3 三个安全阀

| 机制 | 作用 |
|---|---|
| `--restore-delay 3.0`（grace） | 连续出图时只恢复一次，避免 22GB 来回搬运 |
| `RestoreScheduler._generation` 代际 | 每次 `schedule()` 递增，旧线程立刻让位 → **任何时刻只有一个恢复线程在干活** |
| `_pending` 生命周期 | 只有真正起了线程才为 `True`；线程异常/取消/退出都会被清掉（`/api/services` 的 `pending_restore` 不会永久为 true） |

---

## 6. WebUI 使用说明

打开 `http://<host>:<port>/`（默认 `http://127.0.0.1:8080/`）。页面每 3 秒轮询 `/api/services`，
生成期间自动暂停轮询。

| 区域 | 内容 |
|---|---|
| **顶部状态条** | 模式徽章（空闲 / LLM 模式 / ComfyUI 模式 / 混合（显存风险） / 未知）、`生成中` 徽章、`DRY-RUN 模拟模式` 徽章、显存进度条（`used / total MiB`） |
| **访问令牌** | `--token` 启用时在这里填令牌（存 `localStorage`，随请求走 `X-Auth-Token`；图片链接自动拼 `?token=`）。未启用时留空即可 |
| **受管服务** | 三张卡片（llm / tts / comfy），每张显示：运行指示灯、端口、pid、显存提示、上次错误；按钮：`启动` / `停止` / `日志` |
| **模式切换** | `切到 LLM 模式` / `切到 ComfyUI 模式` / `刷新状态`。切换会停掉对面的服务并等显存，**可能要一两分钟**，按钮显示"处理中…"是正常的 |
| **生成面板** | prompt、negative、尺寸（含自定义 `WxH`）、steps、seed、batch_size、参考图（逗号分隔，最多 2 张）、`不使用参考图` 勾选；`生成` / `清空结果` |
| **结果区** | 每张图一个卡片：缩略图、**落盘文件名**、大小、`saved_path`（若与 ComfyUI 原名不同会额外标一行 `ComfyUI 原名`）、`下载` / `另存`；下方折叠区显示 `meta` / `resource` / `output_dir` |
| **日志弹窗** | 点服务卡片上的`日志`，弹出该服务日志末尾 80 行（只读） |
| **DRY-RUN 徽章** | 出现即表示 `--service-dry-run`：所有启停都是内存模拟，**不会真的动进程和显存** |
| **动作日志** | 出错时在"受管服务"卡片下方展开 JSON 错误体（含 `detail`），直接可复制 |

状态行文案示例：`自动编排：开启 · 恢复目标：llm（background，3s） · 引擎：cli · 有待执行的恢复`。

---

## 7. 常见问题

**Q1：第一次生成要 1~3 分钟？**
是的。冷路径包含：停 llm/tts（几秒）→ 等显存释放 → ComfyUI 冷启动 + 加载 ≈22GB 模型（1~3 分钟）→ 采样。
之后的请求只要 ComfyUI 还在跑就快得多。别把 `--generate-timeout` 调得比这个冷启动还短。

**Q2：我自己的 ComfyUI 启动参数怎么保留？**
用 `--comfy-cmd`，支持 `{port}` / `{cwd}` 占位符：

```bash
python3 server.py --comfy-cmd "/root/comfyenv/bin/python main.py --port {port} --listen 127.0.0.1 --highvram --preview-method auto"
```

换了 `--comfy-cmd` 后，进程识别改为按命令里的 `*.py`/`*.sh` 路径匹配（不再只看 `main.py` 绝对路径）。

**Q3：为什么有时返回 409「服务正忙」？**
编排锁同时只允许一个"用显存"的动作（生成或模式切换）。并发的第二个请求在 `timeout+600s` 内没拿到锁就返回 409。
**409 的请求没有动过显存，也不会取消别人已经排好的恢复** —— 等前一个任务结束重试即可。
如果你确实要并发出图，请改成一前一后（比如前端排队）。

**Q4：`run-ttsQwenQ8.sh` 里的 `while true` 会导致停不掉？**
这正是 `ServiceManager._stop_processes` 的设计原因：先 `SIGKILL` 掉 `.sh` 那个 shell（让循环彻底消失），
再对 `tts-server` 发 `SIGTERM` 优雅退出，超时再全 `SIGKILL`，最后**重新扫描**确认没有"复活"。
所以**不要手工只 kill `tts-server`**，它会在毫秒级被壳重新拉起；停服务请用 `/api/services/tts/stop` 或页面按钮。

**Q5：`--token` 到底管什么？**
默认不设 → 行为与以前完全一致（无鉴权）。设置后 `/api/*` 与 `/files/*` 要求 `X-Auth-Token` 或 `?token=`，
`GET /` 仍公开（否则没有入口录入令牌）。WebUI 顶栏填一次即可。

**Q6：默认无鉴权有什么风险？**
任何人只要能访问端口就能启停你的服务、消耗 GPU。所以：默认只监听 `127.0.0.1`；
对外暴露（`--host 0.0.0.0`）时**请加 `--token`**，或放在带鉴权的反向代理后面；必要时用 `--cors-origin` 收紧 CORS
（默认 `*` 是为兼容旧行为）。

**Q7：`find_pids` 有什么注意点？**
它靠 `/proc/<pid>/{cmdline,cwd,stat}` 匹配：ComfyUI 用 `main.py` 的**绝对路径**，LLM/TTS 用脚本名 + worker 名。
手工用别的方式起同名服务（比如 `cd /root/comfy/ComfyUI && python main.py` 之外的路径）可能被识别成"未运行"，
这时用 `--comfy-dir` / `--comfy-cmd` 对齐即可。**不建议改匹配逻辑**——它已被验证可靠。

**Q8：`service_manager.py --self-test` 第 5 项 FAIL？**
"6008 应为关闭"在 LLM 正在运行时必然 FAIL，属预期（它只是信息项，不影响编排）。

**Q9：模式显示「混合（显存风险）」？**
说明 ComfyUI 与 llm/tts 同时在跑（多半是有人手工起过服务）。24G 卡上这样通常会 OOM，
生成前建议先 `/api/mode/comfy` 或 `/api/mode/llm` 归一化。

**Q10：`--generate-timeout` 和请求里的 `timeout` 谁优先？**
请求字段优先，未传时用 `--generate-timeout`（默认 1800）。这个值同时决定 `cli.py --timeout`、
编排锁等待（+600s）和子进程超时（+180s），响应里 `resource.timeout` 会回显实际值。

---

## 8. 故障排查

### 8.1 日志在哪

| 日志 | 路径 | 内容 |
|---|---|---|
| 本服务 | 前台输出 / `logs/server.out`（`nohup` 时） | 每次请求一行 `GET /api/... 耗时 0.123s`、`[restore] ...`、`[auto-start] ...`、非法 Host 的 warn |
| ComfyUI | `logs/comfy.log` | ComfyUI 自己的 stdout（模型加载、CUDA OOM 都在这里） |
| LLM | `logs/llm.log` | `llama-server` 输出 |
| TTS | `logs/tts.log` | `tts-server` 输出 |
| 编排器 | 同本服务输出 + `/api/services` 的 `last_error` | `[svc] 停止 llm ...`、`[vram] ...` 等 |

### 8.2 怎么读错误体

```json
{
  "ok": false,
  "error": "资源编排失败：start comfy 超时",
  "detail": {
    "step": "start:comfy",
    "steps": [
      {"step": "stop:llm",  "ok": true,  "detail": {...}},
      {"step": "stop:tts",  "ok": true,  "detail": {...}},
      {"step": "vram",      "ok": true,  "detail": {"free_mib": 22100, "need_mib": 16000}},
      {"step": "start:comfy","ok": false,"detail": {"timeout": 420, "log_tail": "..."}}
    ],
    "mode": "comfy", "previous_mode": "llm",
    "log_tail": "……最后 40 行日志……"
  },
  "resource": {"mode": "idle", "restore_scheduled": true, "entered_session": true, "timeout": 1800.0}
}
```

读法：

1. `error` 一句话定性（编排失败 / 连不上 / 超时 / 参数错）。
2. `detail.step` 直接告诉你**卡在第几步**：`stop:*` → 停不下来；`vram` → 显存没释放；
   `start:comfy` → 启动超时；`generate:comfy` → 生成阶段。
3. `detail.steps[]` 里每条都有自己的 `detail`（pid 列表、信号、显存数字）。
4. `detail.log_tail` 是该服务日志的尾巴，**CUDA OOM / ImportError 都在这里**。
5. `resource.restore_scheduled=true` 表示后台已经开始把 LLM/TTS 拉回来，不用手工救。

### 8.3 排查表

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `502 编排失败：start comfy 超时` | ComfyUI 冷启动 > 420s；模型在 FUSE 盘上慢；缺 `.so`/缺模型 | 看 `logs/comfy.log` 尾部；核对 `--comfy-cmd` / `--comfy-python`；必要时预热一次 |
| `502 … vram` 步骤失败 | 有别的进程占着显存（手工起的服务、残留 worker） | `nvidia-smi` 看是谁；`find_pids` 识别不到的进程请手工收拾；再用 `/api/services/<name>/stop` |
| `503 连不上 ComfyUI` | ComfyUI 没起 / 端口不对 / 刚被停 | `curl http://127.0.0.1:8188/system_stats`；核对 `--base-url` 与 `--comfy-port` 是否一致 |
| `504 等待 ComfyUI 完成超时` | 冷启动 + 排队超过 timeout | 调大 `--generate-timeout`；`GET /api/queue` 看排队 |
| `409 服务正忙` | 另一个生成/切换占着编排锁 | 等它结束；前端串行化请求 |
| `400 参数错误` | prompt 空、size 非法、images > 2、no_reference 与 images 冲突 | 按 `error` 文案改请求 |
| 页面空白 / 一直"正在读取状态…" | `--token` 没填 / 填错（401） | 顶部"访问令牌"里填 `--token` 的值 |
| 出图成功但图片打不开 | 下载地址的基址不可达（`--host 0.0.0.0` + 客户端用 IP 访问时会用 Host 头，一般没问题） | 用 `--public-base-url http://<可达地址>:<port>` 显式指定 |
| 生成完迟迟不恢复 LLM | 恢复线程在重试（锁被占）或 `ensure_mode("llm")` 失败 | 看 `[restore]` 日志；`/api/services` 的 `pending_restore`；必要时 `POST /api/mode/llm` |
| `--self-test` 报 6008 应关闭 | LLM 正在运行 | 正常现象（信息项） |
| `/files/xxx.png` 404 | 文件名不是落盘名（`_1` 后缀）或已被清理 | 用响应里的 `filename` / `download_url`，别自己拼 ComfyUI 原名 |
| 发布到外网被扫描/滥用 | 未启用鉴权 | 加 `--token`，或反向代理 + 鉴权，`--cors-origin` 收紧 |

### 8.4 手工恢复

```bash
curl -s -X POST http://127.0.0.1:8080/api/mode/llm          # 把 LLM/TTS 拉回来
curl -s -X POST http://127.0.0.1:8080/api/services/comfy/stop
curl -s 'http://127.0.0.1:8080/api/services/llm/log?lines=60'
```

`server.py` 收到 `SIGTERM`/`Ctrl+C` 退出时**默认不停任何服务**（`shutdown(stop_all=False)`），
LLM/TTS 会继续在后台活着——重启 server 不必重新加载 11GB 模型。

---

## 9. 附录：CLI 单次生成与工作流模板

### 9.1 什么时候用 CLI

不想动编排（或者 ComfyUI 已经在跑）时，直接单次生成最省事——server 内部也是这么调 `cli.py` 的。

### 9.2 `cli.py` 单次生成

```bash
# 最简（模板默认 2 张参考图、1080x1920、4 步）
python3 cli.py -p "把背景换成雪山夜景"

# 小图快测 / 指定种子与尺寸
python3 cli.py -p "把背景换成晴朗海边" --size 768x768 -o /tmp/qwen_test --seed 123

# 换参考图（可重复 ≤3；本地路径会自动上传到 ComfyUI 的 input/）
python3 cli.py -p "把外套换成红色" -i ./ref1.png -i ./ref2.jpg

# 纯文生图 / 只看最终 API prompt / 机器可读
python3 cli.py -p "一位女性站在海边" --no-reference
python3 cli.py -p "test" --dry-run
python3 cli.py -p "test" --json | python3 -c 'import json,sys;print(json.load(sys.stdin)["images"][0]["path"])'
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `-p/--prompt` | **必填** | 正向提示词 |
| `-n/--negative` | 空串 | 负向提示词 |
| `-i/--image` | 模板默认 2 张 | 参考图，可重复 ≤3；本地路径自动上传，也可填 `input/` 内文件名 |
| `--no-reference` | false | 不用参考图（纯文生图） |
| `--seed` | 随机 | 随机种子 |
| `--steps` / `--cfg` / `--denoise` | 4 / 1 / 1 | 采样参数 |
| `--sampler` / `--scheduler` | `euler` / `simple` | 采样器与调度器 |
| `--size WxH` | `1080x1920` | 输出尺寸（按 8 对齐） |
| `--width` / `--height` | – | 也可分开传（必须同时给） |
| `--batch/--batch-size` | 1 | 一次几张 |
| `--filename-prefix` | `ComfyUI` | SaveImage 前缀 |
| `-o/--output-dir` | `./outputs` | 下载目录（自动创建） |
| `--timeout` / `--poll-interval` | 1800 / 1.0 | 等待超时与轮询间隔 |
| `--template` | `workflows/qwen_image_edit_api.json` | API 模板路径 |
| `--base-url` | `http://127.0.0.1:8188` | ComfyUI 地址 |
| `--dry-run` / `--json` / `-q` | – | 只打印 prompt / 只输出 JSON / 不打印进度 |

退出码：`0` 成功 · `1` ComfyUI 报错或没图 · `2` 参数/模板/路径错 · `3` 等待超时 · `4` 连不上 ComfyUI。

### 9.3 工作流模板是怎么来的

```bash
# UI 格式 → API 格式（读真实 ComfyUI 的 /object_info 做 widget 映射）
python3 ui_to_api.py "/root/comfy/ComfyUI/user/default/workflows/Qwen-Image-Edit-Rapid-Aio+V3版+best.json" \
  -o /tmp/qwen_ui_api.json --verbose
python3 ui_to_api.py <ui.json> --report        # 只看转换报告
python3 ui_to_api.py <ui.json> --offline -o api.json   # 连不上 ComfyUI 时用内置节点表
```

转换规则要点：节点 key = 原 node id；`mode=4`(bypass) 丢节点并尽量把上游直通下游；`mode=2`(mute) 丢节点不连线；
`Reroute` 穿透；`PrimitiveNode` 值写进下游 widget；`Note` 丢弃。

本项目模板相对原始工作流做了两处折叠：

1. 折叠 CR Text 链路，把 `3.prompt` 换成字面量 `"__PROMPT__"`，`41/42/43` 三个节点移除；
2. 用 `EmptyLatentImage{width,height,batch_size}` 替换 `40 CR SDXL Aspect Ratio`，去掉 Comfyroll 依赖。

最终 11 个节点：`46 47 48`（GGUF/CLIP/VAE）+ `7 8`（参考图）+ `3 4`（正/负文本编码）+ `2`（KSampler）
+ `40`（EmptyLatentImage）+ `5`（VAEDecode）+ `26`（SaveImage）。

### 9.4 换成别的工作流

1. 找到你的 UI 格式工作流 JSON（`ComfyUI/user/default/workflows/*.json`）；
2. `ui_to_api.py` 生成 API JSON，用 `--report` 检查 warning；
3. 放进 `workflows/`，然后用 `--template`（CLI 或 server 都支持）；
4. `runner.py` 靠语义定位节点，需要满足：
   * 正/负文本：从采样节点的 `positive`/`negative` 溯源，类名含 `TextEncode`；
   * 尺寸：`EmptyLatentImage` / `EmptySD3LatentImage` / `EmptyHiResLatentImage` / `CR SDXL Aspect Ratio`；
   * 输出：`SaveImage`；
   * 参考图：模板里 `image1/2/3` 指向 `LoadImage` 就复用，否则动态新建。

结构差异很大的工作流可能需要调 `runner.py` 的 `SAMPLER_CLASSES` / `LATENT_CLASSES` 常量。
