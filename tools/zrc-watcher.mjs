#!/usr/bin/env node
/**
 * ZRC 桌面直推 watcher（ZRemote v2 桌面侧，零第三方依赖，Node >= 18）
 *
 * 监听 ZCode 桌面客户端的本地运行日志（~/.zcode/cli/log/zcode-YYYY-MM-DD.jsonl），
 * 提取 turn.started / turn.completed / turn.failed 生命周期事件，过滤出根任务会话
 * （db.sqlite: task_type='interactive' AND parent_id IS NULL），推送到 ntfy topic。
 *
 * 事件 JSON 与手机端 ZRemote 的 L1 事件同构（NotifyEngine.onTaskEvents 直接可吃）：
 *   {"t":"status","id":"task-item-sess_…","name":"标题","from":"运行中","to":"已完成",
 *    "uid":"turn_…:completed","dur":51328,"src":"watcher"}
 *
 * 用法（独立运行）：
 *   node zrc-watcher.mjs [--server https://ntfy.sh] [--topic <随机长串>] [--generate-topic]
 *                        [--log-dir <dir>] [--db <db.sqlite>] [--config <zrc-watch.json>]
 *                        [--test]   # 只推送一条链路测试事件然后退出
 * 不带参数则读取/创建 --config 指定的 JSON 配置（默认当前目录 zrc-watch.json）并开始监听。
 *
 * 作为模块（本机桥 bridge_8787.mjs 采用）：
 *   const { createWatcher } = await import('file:///…/zrc-watcher.mjs');
 *   const zrc = createWatcher({ configPath, logDir, dbPath });
 *   await zrc.start();  zrc.status();  zrc.setConfig({...});  zrc.testPush();
 *
 * 注意：日志格式是 ZCode 客户端内部格式（0.16.9 实测），非公开 API，改版风险集中在
 * 本文件的 extract() 一处；标题查询需要 Node >= 22.5（node:sqlite），低版本或库打不开时
 * 降级为「不过滤 + 短 id 代标题」，功能仍可用。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

let Sqlite = null;
try { Sqlite = (await import('node:sqlite')).DatabaseSync; } catch { /* Node < 22.5：降级 */ }

const TICK_MS = 2000;
const SEEN_MAX = 500;        // uid 去重环
const PENDING_MAX = 50;      // 推送失败暂存上限（超出丢最旧，L3 仍在兜底）
const TITLE_MAX = 60;        // 推送出去的标题截断（隐私：标题默认=会话首条输入）
const RETRY_DELAYS = [5000, 30000, 120000];
const FLUSH_INTERVAL = 5 * 60000;

function localDateStr(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function defaultPaths() {
  const home = process.env.USERPROFILE || process.env.HOME || '';
  return {
    logDir: path.join(home, '.zcode', 'cli', 'log'),
    dbPath: path.join(home, '.zcode', 'cli', 'db', 'db.sqlite'),
  };
}

function createWatcher(opts = {}) {
  const def = defaultPaths();
  const onEvent = typeof opts.onEvent === 'function' ? opts.onEvent : null;
  const configPath = opts.configPath || path.join(process.cwd(), 'zrc-watch.json');
  const statePath = configPath.replace(/\.json$/i, '') + '.state.json';
  const logDir = opts.logDir || def.logDir;
  const dbPath = opts.dbPath || def.dbPath;

  // ---- 配置（topic 是凭据，只落本机文件，绝不入 git / 文档） ----
  let config = { enabled: false, server: 'https://ntfy.sh', topic: '' };
  try {
    Object.assign(config, JSON.parse(fs.readFileSync(configPath, 'utf8')));
  } catch { /* 首次运行 */ }
  const saveConfig = () => {
    try {
      fs.mkdirSync(path.dirname(configPath), { recursive: true });
      fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
    } catch { /* 配置写不进就只在内存生效 */ }
  };

  // ---- 状态（断点续读 + uid 去重，防桥重启后重放全天日志） ----
  // fresh=true 表示「跳到文件末尾，只推从现在起的事件」——首次部署与重新启用都置位，
  // 否则启用瞬间会把当天已积压的几十上百个 turn 全推出去（手机端会视为洪水）。
  let state = {
    file: '', offset: 0, partial: '', seenUids: [], sessionStatus: {},
    eventsSeen: 0, lastEventAt: 0, lastEvent: null,
    lastPushAt: 0, lastPushOk: null, lastPushErr: '',
    pending: [], fresh: true,
  };
  try {
    const loaded = JSON.parse(fs.readFileSync(statePath, 'utf8'));
    state = { ...state, ...loaded, fresh: !loaded.file };
  } catch { /* 首次运行 */ }
  let stateDirty = false;
  const saveState = () => {
    if (!stateDirty) return;
    stateDirty = false;
    try {
      fs.mkdirSync(path.dirname(statePath), { recursive: true });
      fs.writeFileSync(statePath, JSON.stringify(state));
    } catch { /* 丢了顶多重放一轮，手机端时间窗去重兜底 */ }
  };

  // ---- db.sqlite（只读；WAL 与客户端并发读安全；打不开则降级且每轮自愈重试） ----
  let db = null;
  const titleCache = new Map(); // sid -> {pass:boolean, title:string}，不确定的结果不缓存

  function openDb() {
    if (db || !Sqlite) return;
    try {
      db = new Sqlite(dbPath, { readOnly: true });
    } catch {
      db = null; // 客户端崩溃残留 WAL 等场景；下次有新会话查标题时再试
    }
  }

  function lookupSession(sid) {
    if (titleCache.has(sid)) return titleCache.get(sid);
    openDb();
    let r;
    if (db) {
      try {
        const row = db.prepare('SELECT title, task_type, parent_id FROM session WHERE id = ?').get(sid);
        r = row
          ? { pass: !row.parent_id && row.task_type === 'interactive', title: String(row.title || '') }
          : { pass: false, title: '' }; // 已归档/清理的会话不推
      } catch {
        db = null;
        r = { pass: null, title: '' }; // 查询失败：不过滤，宁多勿漏
      }
    } else {
      r = { pass: null, title: '' }; // 无 sqlite 模块 / 库暂时打不开：降级放行
    }
    if (r.pass !== null) titleCache.set(sid, r);
    return r;
  }

  // ---- 事件提取（ZCode 日志格式变化时只改这里） ----
  function extract(line) {
    if (!line.includes('"turn.') || !line.includes('core.runtime')) return null;
    let j;
    try { j = JSON.parse(line); } catch { return null; }
    const ev = j && j.event;
    if (ev !== 'turn.started' && ev !== 'turn.completed' && ev !== 'turn.failed') return null;
    if (!j.sessionId || !j.turnId) return null;
    if (ev === 'turn.started') return { to: '运行中', sessionId: j.sessionId, turnId: j.turnId, dur: 0, err: '' };
    if (ev === 'turn.completed') {
      return { to: '已完成', sessionId: j.sessionId, turnId: j.turnId, dur: j.durationMs || 0, err: '' };
    }
    if (j.status === 'cancelled') return { skip: true, sessionId: j.sessionId }; // 用户主动取消不推
    const m = j.error && (j.error.message || (j.error.data && j.error.data.message));
    return { to: '错误', sessionId: j.sessionId, turnId: j.turnId, dur: j.durationMs || 0, err: String(m || '').slice(0, 90) };
  }

  function handleExtracted(x, atMs) {
    const sid = x.sessionId;
    if (x.skip) { state.sessionStatus[sid] = '已取消'; stateDirty = true; return; }
    const uid = `${x.turnId}:${x.to}`;
    if (state.seenUids.includes(uid)) return;
    state.seenUids.push(uid);
    if (state.seenUids.length > SEEN_MAX) state.seenUids.splice(0, state.seenUids.length - SEEN_MAX);

    const s = lookupSession(sid);
    if (s.pass === false) return; // 侧聊/子代理/已清理会话
    const from = state.sessionStatus[sid] || '';
    state.sessionStatus[sid] = x.to;
    state.eventsSeen += 1;
    state.lastEventAt = atMs;
    const name = (s.title || `任务 ${sid.slice(5, 13)}`).slice(0, TITLE_MAX);
    const payload = {
      t: 'status', id: `task-item-${sid}`, name, from, to: x.to, uid, dur: x.dur, src: 'watcher',
    };
    if (x.err) payload.err = x.err;
    state.lastEvent = payload;
    stateDirty = true;

    const isErr = x.to === '错误';
    const isStart = x.to === '运行中';
    const human = isErr ? `⚠️ 任务出错：${name}` : isStart ? `▶️ 任务开始：${name}` : `✅ 任务完成：${name}`;
    push(human, payload, isErr ? 4 : (isStart ? 1 : 3));
  }

  // ---- 日志尾随：2s 轮询增量读取，跨零点自动换文件 ----
  function currentFile() { return path.join(logDir, `zcode-${localDateStr()}.jsonl`); }

  function tick() {
    try {
      if (!config.enabled || !config.topic) return;
      const file = currentFile();
      if (file !== state.file) { state.file = file; state.offset = 0; state.partial = ''; stateDirty = true; }
      let st;
      try { st = fs.statSync(file); } catch { return; } // 当天还没日志
      if (state.fresh) { // 跳过积压：首启/重启用，从当前末尾开始
        state.fresh = false;
        state.offset = st.size;
        state.partial = '';
        stateDirty = true;
        return;
      }
      if (st.size < state.offset) { state.offset = 0; state.partial = ''; stateDirty = true; }
      if (st.size === state.offset) return;
      const len = st.size - state.offset;
      const fd = fs.openSync(file, 'r');
      let buf;
      try {
        buf = Buffer.alloc(len);
        fs.readSync(fd, buf, 0, len, state.offset);
      } finally { fs.closeSync(fd); }
      state.offset = st.size;
      const lines = (state.partial + buf.toString('utf8')).split('\n');
      state.partial = lines.pop() || '';
      const atMs = Date.now();
      for (const line of lines) {
        const l = line.trim();
        if (!l) continue;
        const x = extract(l);
        if (x) handleExtracted(x, atMs);
      }
      stateDirty = true;
    } catch { /* 单轮失败不影响下一轮 */ }
  }

  // ---- ntfy 推送：失败退避重试 → pending 暂存 → 定期重 flush ----
  let flushing = false;

  async function postOne(item) {
    // 用 JSON publish API（POST 服务器根路径，topic 在 body）：标题含中文/emoji，
    // 放 HTTP 头会被 undici 拒收（non-ASCII header），放 body 无此问题
    const res = await fetch(config.server.replace(/\/+$/, ''), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        topic: config.topic,
        title: item.human,
        message: JSON.stringify(item.payload),
        priority: item.priority,
        tags: item.priority >= 4 ? ['warning'] : ['white_check_mark'],
      }),
      signal: AbortSignal.timeout(10000),
    });
    if (!res.ok) throw new Error(`ntfy HTTP ${res.status}`);
  }

  async function push(human, payload, priority) {
    state.pending.push({ human, payload, priority, tries: 0 });
    if (state.pending.length > PENDING_MAX + 20) state.pending.splice(0, state.pending.length - PENDING_MAX);
    stateDirty = true;
    try { onEvent && onEvent(payload, human); } catch { /* 宿主回调异常不影响推送 */ }
    await flush();
  }

  async function flush() {
    if (flushing) return;
    flushing = true;
    try {
      while (state.pending.length > 0) {
        const item = state.pending[0];
        try {
          await postOne(item);
          state.pending.shift();
          state.lastPushAt = Date.now();
          state.lastPushOk = true;
          state.lastPushErr = '';
        } catch (e) {
          state.lastPushOk = false;
          state.lastPushErr = String((e && e.message) || e);
          item.tries += 1;
          if (item.tries <= RETRY_DELAYS.length) {
            const delay = RETRY_DELAYS[Math.min(item.tries - 1, RETRY_DELAYS.length - 1)];
            setTimeout(() => { flush(); }, delay).unref?.();
            break; // 保留队首，退避后再来
          }
          state.pending.shift(); // 重试穷尽：放弃（手机端 L3 兜底仍在）
        }
        stateDirty = true;
      }
    } finally {
      flushing = false;
      saveState();
    }
  }

  // ---- 生命周期 ----
  let timer = null;
  let flushTimer = null;
  let started = false;

  async function start() {
    if (started) return;
    started = true;
    openDb();
    tick();
    timer = setInterval(() => { tick(); saveState(); }, TICK_MS);
    flushTimer = setInterval(() => { flush(); }, FLUSH_INTERVAL);
    flushTimer.unref?.();
  }

  function stop() {
    started = false;
    if (timer) clearInterval(timer);
    if (flushTimer) clearInterval(flushTimer);
    timer = flushTimer = null;
    saveState();
    try { db && db.close(); } catch { /* ignore */ }
    db = null;
  }

  function setConfig(patch = {}) {
    const wasEnabled = !!config.enabled;
    if (patch.generate && !patch.topic) {
      patch.topic = 'zrc-' + crypto.randomBytes(16).toString('hex');
    }
    for (const k of ['enabled', 'server', 'topic']) {
      if (patch[k] !== undefined) config[k] = patch[k];
    }
    config.enabled = !!config.enabled;
    config.server = String(config.server || 'https://ntfy.sh').replace(/\/+$/, '');
    config.topic = String(config.topic || '').trim();
    saveConfig();
    if (config.enabled && !wasEnabled) state.fresh = true; // 重新启用：不吃积压
    if (config.enabled) tick();
    return status();
  }

  async function testPush(name) {
    const payload = {
      t: 'status', id: 'task-item-zrc-e2e-test', name: name || '直推链路测试',
      from: '运行中', to: '已完成', uid: `zrc-test-${Date.now()}`, dur: 0, src: 'test',
    };
    state.pending.push({ human: `✅ 任务完成：${payload.name}`, payload, priority: 3, tries: 0 });
    stateDirty = true;
    await flush();
    return { ok: state.lastPushOk === true, err: state.lastPushErr, payload };
  }

  function status() {
    return {
      enabled: !!config.enabled,
      configured: !!(config.server && config.topic),
      server: config.server,
      topicLen: config.topic.length,
      topicMasked: config.topic ? `${config.topic.slice(0, 4)}…${config.topic.slice(-4)}` : '',
      file: state.file || currentFile(),
      offset: state.offset,
      dbAvailable: db !== null,
      sqliteAvailable: !!Sqlite,
      eventsSeen: state.eventsSeen,
      lastEventAt: state.lastEventAt,
      lastEvent: state.lastEvent,
      lastPushAt: state.lastPushAt,
      lastPushOk: state.lastPushOk,
      lastPushErr: state.lastPushErr,
      pending: state.pending.length,
    };
  }

  return { start, stop, status, setConfig, testPush, tickNow: tick };
}

// ---- CLI 独立运行 ----
const isMain = process.argv[1] &&
  import.meta.url === new URL(`file:///${path.resolve(process.argv[1]).replace(/\\/g, '/')}`).href;

if (isMain) {
  const args = {};
  for (let i = 2; i < process.argv.length; i += 2) {
    args[String(process.argv[i] || '').replace(/^--/, '')] = process.argv[i + 1];
  }
  const zrc = createWatcher({ configPath: args.config, logDir: args['log-dir'], dbPath: args.db });
  const patch = {};
  if (args.server) patch.server = args.server;
  if (args.topic) patch.topic = args.topic;
  if (args['generate-topic'] !== undefined) patch.generate = true;
  if (Object.keys(patch).length) zrc.setConfig(patch);
  if (process.argv.includes('--test')) {
    const r = await zrc.testPush();
    console.log(JSON.stringify(r, null, 2));
    process.exit(r.ok ? 0 : 1);
  }
  zrc.setConfig({ enabled: true });
  await zrc.start();
  console.log('[zrc-watcher] running ·', JSON.stringify(zrc.status(), null, 2));
}

export { createWatcher };
