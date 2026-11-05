# Mac 本地千问实时语音

本服务直接调用 mlx-qwen3-asr 的 Session 流式 API，不代理原来的 HTTP serve。
模型加载和推理共用一个工作线程；只允许一个活动录音会话，最长 10 分钟。

## Mac 启动

在已部署模型的 Python 环境中：

```sh
python -m pip install -r tools/local-asr-server/requirements.txt
cp -n tools/local-asr-server/.env.example tools/local-asr-server/.env
# 编辑 tools/local-asr-server/.env，填写 XIME_ASR_API_KEY
python -m uvicorn server:app --app-dir tools/local-asr-server --host 0.0.0.0 --port 8766 --ws-max-size 65536 --ws-max-queue 32
```

在 `.env` 中填写：

```dotenv
XIME_ASR_API_KEY=你的英文半角密钥
XIME_ASR_MODEL=Qwen/Qwen3-ASR-1.7B
```

服务自动读取脚本同目录的 `.env`，不依赖启动目录；已有系统环境变量优先。
如之前 export 过旧密钥，先执行 `unset XIME_ASR_API_KEY XIME_ASR_MODEL` 再启动。
`.env` 已加入 Git 忽略规则，修改后重启服务生效。
首次启动会加载模型；手机和 Mac 需互通且防火墙放行 8766。
本服务现同时提供 HTTP 和 WebSocket，共享同一模型，无需再启动旧 HTTP 进程。
如需继续使用原来的 8765 地址，先停止旧服务，将启动命令中的 --port 8766 改为 --port 8765，并同步修改插件 WebSocket 地址。

## Xime

1. 安装本次构建的主应用（新增了本插件专用的 30 秒收尾等待）。
2. 在插件中心导入 build/plugin-release/local-qwen-asr-1.0.4.xipk。
3. 进入插件设置：WebSocket 地址填写 ws://192.168.3.195:8766/ws/asr，API Key 与服务端环境变量一致。
4. 在语音设置关闭“本地识别”，选择“本地千问实时语音”作为在线插件。
5. 可选：填写纠错 Base URL（例如 http://模型地址:端口/v1）、模型名和密钥。留空禁用。纠错失败或超过 10 秒时回退原文。
6. 打开录音：约每 2 秒刷新识别文本；松手后完成尾部识别与纠错。Prompt 打开时沿用现有内部编辑框路由。

服务地址是自定义主机，保存插件配置后需按宿主要求授予网络访问权限。
局域网示例使用明文 WS；跨网络应通过可信 TLS 反向代理使用 WSS。

## 协议

握手 Authorization: Bearer <密钥>。
先发送 JSON {"type":"start"}，收到 ready 后发送 PCM16LE/16kHz/单声道二进制帧。
服务端 partial.text 为当前完整文本，替换临时结果，不追加重复全文。
发送 {"type":"stop"} 获取 final；cancel 或断连丢弃当前会话。

## 已验证与限制

插件编译打包、两项协议测试、Python 语法检查与 Android Debug 构建通过。
尚未连接真实 Mac 模型验证 Metal 推理及手机端录音；需按上面步骤完成实际联调。

## HTTP 文件转写（OpenAI 兼容）

同一端口提供 GET /v1/models 和 POST /v1/audio/transcriptions，均使用相同 Bearer 密钥。
Base URL 为 http://192.168.3.195:8766/v1；使用 8765 启动则将端口替换为 8765。

```sh
curl http://192.168.3.195:8766/v1/audio/transcriptions \
  -H "Authorization: Bearer <与.env一致的密钥>" \
  -F 'file=@recording.wav' \
  -F 'model=Qwen/Qwen3-ASR-1.7B' \
  -F 'language=Chinese'
```

返回 {"text":"识别文本"}。支持可选 prompt（热词/上下文），response_format=json 或 text。
HTTP 为上传文件后等待完整结果，不是实时音频上传；实时输入继续用 /ws/asr。
单文件最大 20 MiB；非 WAV 格式通常需 ffmpeg。HTTP 与 WS 互斥，忙时 HTTP 返回 503。
暂不提供旧 serve 的 /transcribe + /jobs 异步任务接口，不支持 timestamps、SSE 或其他 response_format。
重新安装 requirements.txt（新增 python-multipart）并重启 Mac 服务即可；无需更新 APK 或插件。
