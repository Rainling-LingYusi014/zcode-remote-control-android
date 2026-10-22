package com.dsha.zremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v2 桌面直推接收器：消费 ntfy JSON API（标准库 HttpURLConnection，零第三方依赖）。
 * 两种模式：
 *  · 前台流式订阅（subscribe/unsubscribe）：GET {server}/{topic}/json?since=… 不带 poll，
 *    长连接逐行读，秒级到达，断线 15s 重连；
 *  · 一次性拉取（pollOnce）：带 poll=1，SyncJobService 兜底与设置页「测试拉取」用。
 * 消息体是 watcher 发的事件 JSON（与 L1 注入脚本同构）→ 直接喂 NotifyEngine；
 * 处理后把终态写回 last_snapshot，抑制 L3 WebView 兜底的重复 diff。
 */
final class NtfyReceiver {

    private static final String TAG = "ZRemote";
    /** 流式订阅读超时：兜底防止长连接假死后再也醒不过来（审查 #11）。 */
    private static final int STREAM_READ_TIMEOUT_MS = 10 * 60_000;

    private static Thread sSubThread;
    private static volatile boolean sSubWanted = false;
    /** 当前流式连接：unsubscribe 时主动断开，让阻塞在 readLine 的线程立刻退出。 */
    private static volatile HttpURLConnection sConn;

    private NtfyReceiver() {}

    static boolean enabled(Context c) {
        return AppSettings.ntfyOn(c) && !AppSettings.ntfyTopic(c).isEmpty();
    }

    // ---- 前台流式订阅 ----

    static void subscribe(Context c) {
        if (!enabled(c) || sSubWanted) return;
        sSubWanted = true;
        startThread(c.getApplicationContext());
    }

    static void unsubscribe() {
        sSubWanted = false;
        HttpURLConnection c = sConn;
        if (c != null) {
            try {
                c.disconnect(); // 打断阻塞中的 readLine，线程立即走退出分支
            } catch (Exception ignored) {
            }
        }
    }

    private static synchronized void startThread(final Context app) {
        if (sSubThread != null && sSubThread.isAlive()) return;
        sSubThread = new Thread(() -> {
            while (sSubWanted) {
                try {
                    streamOnce(app);
                    setResult(app, "订阅正常结束，重连中");
                } catch (Exception e) {
                    setResult(app, "订阅断开：" + e.getMessage() + "，15s 后重连");
                }
                if (!sSubWanted) break;
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "zrc-ntfy-sub");
        sSubThread.setDaemon(true);
        sSubThread.start();
    }

    private static void streamOnce(Context app) throws Exception {
        HttpURLConnection conn = open(app, false);
        sConn = conn;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (sSubWanted && (line = r.readLine()) != null) {
                handleLine(app, line);
            }
            advanceBase(app);
        } finally {
            sConn = null;
            conn.disconnect();
        }
    }

    // ---- 一次性拉取（Job 兜底 / 设置页测试）----

    /** 拉取并处理新消息，返回本次处理的事件数；失败抛异常由调用方决定回落策略。 */
    static int pollOnce(Context c) throws Exception {
        Context app = c.getApplicationContext();
        HttpURLConnection conn = open(app, true);
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (handleLine(app, line)) n++;
            }
        } finally {
            conn.disconnect();
        }
        advanceBase(app);
        setResult(app, "拉取成功 +" + n);
        return n;
    }

    private static HttpURLConnection open(Context app, boolean poll) throws Exception {
        String server = AppSettings.ntfyServer(app).replaceAll("/+$", "");
        String topic = AppSettings.ntfyTopic(app);
        SharedPreferences p = prefs(app);
        String lastId = p.getString("ntfy_last_id", "");
        // 水位：优先消息 id（精确，无时钟偏差）；否则用启用/上次成功拉取的时间
        // （ntfy_base_ts）。不能在无 id 时直接用 now：会把"启用后~首次拉取前"到达的消息跳掉。
        // 手机与 ntfy 服务器有秒级时钟差时，该窗口内可能漏 1-2 条，属已知边界（有 id 后即精确）。
        String since = !lastId.isEmpty() ? lastId
                : String.valueOf(p.getLong("ntfy_base_ts", System.currentTimeMillis() / 1000));
        // topic / since 都要编码后再拼：特殊字符会改变请求形状（审查 #10）
        String urlText = server + "/" + enc(topic) + "/json?since=" + enc(since)
                + (poll ? "&poll=1" : "");
        URL u = new URL(urlText);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(poll ? 20000 : STREAM_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 成功读完一轮后推进时间水位：此后到达的消息一定晚于下次 since。 */
    private static void advanceBase(Context app) {
        prefs(app).edit()
                .putLong("ntfy_base_ts", System.currentTimeMillis() / 1000)
                .putLong("ntfy_last_at", System.currentTimeMillis())
                .apply();
    }

    /** 处理一行 ntfy JSON。是有效新事件返回 true。 */
    private static boolean handleLine(Context app, String line) {
        try {
            if (line.trim().isEmpty()) return false;
            JSONObject m = new JSONObject(line);
            if (!"message".equals(m.optString("event"))) return false;
            String id = m.optString("id", "");
            SharedPreferences p = prefs(app);
            if (!id.isEmpty() && id.equals(p.getString("ntfy_last_id", ""))) return false;
            JSONObject ev = new JSONObject(m.getString("message"));
            NotifyEngine.onTaskEvents("[" + ev.toString() + "]");
            p.edit().putString("ntfy_last_id", id)
                    .putLong("ntfy_last_at", System.currentTimeMillis()).apply();
            mergeSnapshot(app, ev);
            return true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "ntfy 坏行已跳过：" + e);
            return false;
        }
    }

    /** 终态写回 last_snapshot：L3 注入脚本 loadLast() 起 diff 时看不到变化，不再重复通知。 */
    private static void mergeSnapshot(Context app, JSONObject ev) {
        try {
            String tid = ev.optString("id", "");
            String to = ev.optString("to", "");
            if (tid.isEmpty() || to.isEmpty()) return;
            SharedPreferences p = prefs(app);
            JSONObject snap;
            try {
                snap = new JSONObject(p.getString("last_snapshot", "{}"));
            } catch (Exception e) {
                snap = new JSONObject();
            }
            snap.put(tid, new JSONObject().put("n", ev.optString("name", "")).put("s", to));
            p.edit().putString("last_snapshot", snap.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void setResult(Context c, String result) {
        prefs(c).edit()
                .putString("ntfy_last_result", AppSettings.redact(c, result))
                .putLong("ntfy_last_at", System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences("zremote", Context.MODE_PRIVATE);
    }
}
