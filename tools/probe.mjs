// Zcode RC 阶段2a DOM 探针：经 CDP 在真机 WebView 里只读采样页面结构
// 用法：node probe.mjs ws://127.0.0.1:9222/devtools/page/<id>
import { readFileSync } from 'node:fs';

const wsUrl = process.argv[2];
if (!wsUrl) { console.error('usage: node probe.mjs <wsUrl>'); process.exit(1); }
const expr = readFileSync(
  process.argv[3] ? new URL('file:///' + process.argv[3].replace(/\\/g, '/')) : new URL('./probe-expression.js', import.meta.url),
  'utf8'
);

const ws = new WebSocket(wsUrl);
let seq = 0;
const pending = new Map();

function send(method, params = {}, timeoutMs = 25000) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); reject(new Error('timeout: ' + method)); }
    }, timeoutMs);
  });
}

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
  }
};
ws.onerror = (e) => { console.error('WS_ERROR', e.message || String(e)); process.exit(1); };

ws.onopen = async () => {
  try {
    const r = await send('Runtime.evaluate', {
      expression: expr, returnByValue: true, awaitPromise: true,
    });
    if (r.exceptionDetails) {
      console.error('PAGE_EXCEPTION', JSON.stringify(r.exceptionDetails, null, 2).slice(0, 3000));
    } else {
      console.log(JSON.stringify(r.result.value, null, 2));
    }
  } catch (e) {
    console.error('ERR', e.message);
  }
  process.exit(0);
};
