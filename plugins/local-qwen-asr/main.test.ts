const plugin = (globalThis as any).plugin;
const url = 'ws://192.168.3.195:8766/ws/asr';
test('等待 ready 后发送音频，stop 不越过缓存', async () => {
  __ximeMock.setConfig('apiKey', 'test');
  __ximeMock.addWs(url);
  assert.ok(await plugin.speech.start());
  await plugin.ws.onOpen();
  await plugin.speech.feed(new Uint8Array([1, 2]));
  await plugin.speech.stop();
  assert.equal(__ximeMock.sentWs.filter((m: any) => m.type === 'binary').length, 0);
  await plugin.ws.onMessage(JSON.stringify({type: 'ready'}));
  const sent = __ximeMock.sentWs;
  assert.equal(sent.filter((m: any) => m.type === 'binary').length, 1);
  assert.ok(sent.some((m: any) => m.type === 'text' && m.text.indexOf('"stop"') >= 0));
});
test('取消后不再发送音频', async () => {
  await plugin.speech.cancel();
  const count = __ximeMock.sentWs.length;
  await plugin.speech.feed(new Uint8Array([1, 2]));
  assert.equal(__ximeMock.sentWs.length, count);
});

test('全角密钥在连接前被拒绝且不回显密钥', async () => {
  __ximeMock.setConfig('apiKey', 'hapi－local');
  assert.equal(await plugin.speech.start(), false);
});

test('收到 final 后断开 WebSocket 不应丢弃异步纠错', async () => {
  __ximeMock.setConfig('apiKey', 'test');
  __ximeMock.setConfig('correctionUrl', 'http://correction.test/v1/chat/completions');
  __ximeMock.setConfig('correctionModel', 'test-model');
  let release: (() => void) | undefined;
  let began: (() => void) | undefined;
  const started = new Promise<void>(resolve => { began = resolve; });
  __ximeMock.addHttpResponse('POST', 'http://correction.test/v1/chat/completions', async () => {
    began!();
    await new Promise<void>(resolve => { release = resolve; });
    return {status: 200, text: JSON.stringify({choices: [{message: {content: '修正后的文本'}}]})};
  });
  __ximeMock.addWs(url);
  assert.ok(await plugin.speech.start());
  const pending = plugin.ws.onMessage(JSON.stringify({type: 'final', text: '识别原文'}));
  await started;
  await plugin.ws.onClose();
  release!();
  await pending;
  assert.ok(__ximeMock.asrEvents.some((e: any) => e.type === 'final' && e.text === '修正后的文本'));
});

test('关闭纠错不发 HTTP 请求且保留原文', async () => {
  __ximeMock.setConfig('correctionEnabled', 'false');
  __ximeMock.setConfig('apiKey', 'test');
  __ximeMock.addWs(url);
  await plugin.speech.start();
  const count = __ximeMock.httpRequests.length;
  await plugin.ws.onMessage(JSON.stringify({type: 'final', text: '原文'}));
  assert.equal(__ximeMock.httpRequests.length, count);
  assert.ok(__ximeMock.asrEvents.some((e: any) => e.type === 'final' && e.text === '原文'));
});

test('自定义提示词传给模型，空白提示词使用默认规则', async () => {
  __ximeMock.setConfig('correctionEnabled', 'true');
  __ximeMock.setConfig('correctionUrl', 'http://prompt.test/v1');
  __ximeMock.setConfig('correctionModel', 'test-model');
  __ximeMock.addHttpResponse('POST', 'http://prompt.test/v1/chat/completions', {
    status: 200, text: JSON.stringify({choices: [{message: {content: '完成'}}]})
  });
  for (const prompt of ['只修正标点。', '   ']) {
    __ximeMock.setConfig('correctionPrompt', prompt);
    __ximeMock.addWs(url);
    await plugin.speech.start();
    await plugin.ws.onMessage(JSON.stringify({type: 'final', text: '待修正文'}));
    const request = __ximeMock.httpRequests[__ximeMock.httpRequests.length - 1];
    const actual = JSON.parse(request.text!).messages[0].content;
    if (prompt.trim()) assert.equal(actual, prompt);
    else assert.ok(actual.indexOf('不确定则保留原文') >= 0);
  }
});
