"""Single-client streaming bridge for mlx-qwen3-asr."""
import asyncio
import functools
import json
import os
import secrets
import tempfile
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from dotenv import load_dotenv

import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, Form, Header, HTTPException, Depends
from fastapi.responses import PlainTextResponse
from mlx_qwen3_asr.session import Session

# 按脚本位置加载，不依赖启动时的工作目录；显式环境变量优先。
load_dotenv(Path(__file__).resolve().with_name('.env'), override=False)

pool = ThreadPoolExecutor(max_workers=1)
model = None
busy = False

async def infer(fn, *args, **kwargs):
    task = asyncio.get_running_loop().run_in_executor(pool, functools.partial(fn, *args, **kwargs))
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        # 推理不能中断；等待完成再释放会话与临时文件。
        await asyncio.shield(task)
        raise

@asynccontextmanager
async def lifespan(app):
    global model
    if not os.environ.get('XIME_ASR_API_KEY'):
        raise RuntimeError('Set XIME_ASR_API_KEY in tools/local-asr-server/.env or the environment')
    model = await infer(Session, os.getenv('XIME_ASR_MODEL', 'Qwen/Qwen3-ASR-1.7B'))
    yield
    pool.shutdown(wait=True)

app = FastAPI(lifespan=lifespan)

@app.websocket('/ws/asr')
async def transcribe(ws: WebSocket):
    global busy
    expected = 'Bearer ' + os.environ['XIME_ASR_API_KEY']
    if not secrets.compare_digest(ws.headers.get('authorization', ''), expected):
        await ws.close(code=1008)
        return
    await ws.accept()
    if busy:
        await ws.send_json({'type': 'error', 'message': 'ASR 正在处理另一个会话'})
        await ws.close(code=1013)
        return
    busy = True
    try:
        start = await asyncio.wait_for(ws.receive_json(), 10)
        if start.get('type') != 'start':
            raise ValueError('First message must be start')
        state = await infer(model.init_streaming, language=start.get('language') or None,
                            chunk_size_sec=2.0, max_context_sec=30.0)
        await ws.send_json({'type': 'ready'})
        total = 0
        while True:
            message = await asyncio.wait_for(ws.receive(), 60)
            if message['type'] == 'websocket.disconnect':
                break
            pcm = message.get('bytes')
            if pcm is not None:
                total += len(pcm)
                if len(pcm) % 2 or len(pcm) > 64000 or total > 16000 * 2 * 600:
                    raise ValueError('Invalid audio frame or session exceeds 10 minutes')
                audio = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768.0
                state = await infer(model.feed_audio, audio, state)
                await ws.send_json({'type': 'partial', 'text': state.text})
                continue
            command = json.loads(message.get('text') or '{}').get('type')
            if command == 'cancel':
                break
            if command == 'stop':
                state = await infer(model.finish_streaming, state)
                await ws.send_json({'type': 'final', 'text': state.text})
                break
    except WebSocketDisconnect:
        pass
    except Exception as error:
        try:
            await ws.send_json({'type': 'error', 'message': str(error)})
        except Exception:
            pass
    finally:
        busy = False
        try:
            await ws.close()
        except Exception:
            pass


def authenticate(authorization: str = Header(default="")):
    expected = 'Bearer ' + os.environ['XIME_ASR_API_KEY']
    if not secrets.compare_digest(authorization, expected):
        raise HTTPException(401, 'Invalid API key')


@app.get('/v1/models', dependencies=[Depends(authenticate)])
async def models():
    return {'object': 'list', 'data': [{'id': os.getenv('XIME_ASR_MODEL', 'Qwen/Qwen3-ASR-1.7B'), 'object': 'model', 'owned_by': 'local'}]}


@app.post('/v1/audio/transcriptions', dependencies=[Depends(authenticate)])
async def transcription(file: UploadFile = File(...), model: str = Form(''),
                        language: str = Form(''), prompt: str = Form(''),
                        response_format: str = Form('json')):
    if model and model != os.getenv('XIME_ASR_MODEL', 'Qwen/Qwen3-ASR-1.7B'):
        raise HTTPException(400, 'Model does not match the loaded model')
    return await transcribe_upload(file, language, prompt, response_format)


async def transcribe_upload(upload, language, prompt, response_format):
    global busy
    if response_format not in ('json', 'text'):
        raise HTTPException(400, 'Supported response_format: json, text')
    if busy:
        raise HTTPException(503, 'ASR is busy; retry after the current session')
    busy = True
    try:
        suffix = Path(upload.filename or 'audio.wav').suffix
        if suffix.lower() not in ('.wav', '.mp3', '.m4a', '.flac', '.ogg', '.webm', '.mp4'):
            raise HTTPException(400, 'Unsupported audio file extension')
        with tempfile.TemporaryDirectory(prefix='xime-asr-') as directory:
            path = Path(directory) / ('audio' + suffix)
            total = 0
            with path.open('wb') as output:
                while chunk := await upload.read(65536):
                    total += len(chunk)
                    if total > 20 * 1024 * 1024:
                        raise HTTPException(413, 'Audio exceeds 20 MiB')
                    output.write(chunk)
            if not total:
                raise HTTPException(400, 'Empty audio')
            result = await infer(globals()['model'].transcribe, str(path),
                                 language=language or None, context=prompt)
            text = result.text
            return PlainTextResponse(text) if response_format == 'text' else {'text': text}
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(500, 'Transcription failed; check server model and audio dependencies') from error
    finally:
        busy = False
        await upload.close()
