package com.dsha.zremote;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置存取 + 副作用（调度 Job / 启停实时服务）。 */
final class AppSettings {

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("zremote", Context.MODE_PRIVATE);
    }

    static int intervalMin(Context c) {
        return p(c).getInt("interval_min", 30);
    }

    static void setIntervalMin(Context c, int min) {
        p(c).edit().putInt("interval_min", min).apply();
        SyncJobService.schedule(c, min);
    }

    static boolean quietOn(Context c) {
        return p(c).getBoolean("quiet_on", false);
    }

    static void setQuietOn(Context c, boolean on) {
        p(c).edit().putBoolean("quiet_on", on).apply();
    }

    static int quietStart(Context c) {
        return p(c).getInt("quiet_start", 23 * 60);
    }

    static int quietEnd(Context c) {
        return p(c).getInt("quiet_end", 7 * 60);
    }

    static void setQuietTimes(Context c, int startMin, int endMin) {
        p(c).edit().putInt("quiet_start", startMin).putInt("quiet_end", endMin).apply();
    }

    static String fmtTime(int minOfDay) {
        return String.format(java.util.Locale.US, "%02d:%02d", minOfDay / 60, minOfDay % 60);
    }

    // ---- 桌面直推（v2）----

    static boolean ntfyOn(Context c) {
        return p(c).getBoolean("ntfy_on", false);
    }

    static void setNtfyOn(Context c, boolean on) {
        // 开启时重置时间水位：从此刻起的消息才需要送达（不吃 ntfy 12h 缓存积压）
        p(c).edit().putBoolean("ntfy_on", on)
                .putLong("ntfy_base_ts", System.currentTimeMillis() / 1000)
                .apply();
    }

    static String ntfyServer(Context c) {
        return p(c).getString("ntfy_server", "https://ntfy.sh");
    }

    static void setNtfyServer(Context c, String server) {
        String s = server == null ? "" : server.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://ntfy.sh";
        p(c).edit().putString("ntfy_server", s).apply();
    }

    static String ntfyTopic(Context c) {
        return p(c).getString("ntfy_topic", "");
    }

    static void setNtfyTopic(Context c, String topic) {
        p(c).edit().putString("ntfy_topic", topic == null ? "" : topic.trim()).apply();
    }

    /** Topic 展示用打码（凭据，避免设置页肩窥泄露全量）。 */
    static String ntfyTopicMasked(Context c) {
        String t = ntfyTopic(c);
        if (t.isEmpty()) return "未设置";
        return t.length() <= 8 ? t.substring(0, 2) + "…"
                : t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }

    static String ntfyLastText(Context c) {
        long ms = p(c).getLong("ntfy_last_at", 0);
        String r = p(c).getString("ntfy_last_result", "");
        if (ms == 0 || r.isEmpty()) return "从未拉取";
        long delta = (System.currentTimeMillis() - ms) / 60000;
        String ago = delta < 1 ? "刚刚" : delta + " 分钟前";
        return ago + " · " + r;
    }

    static void setLastCheck(Context c, long ms, String result) {
        p(c).edit().putLong("last_check_ms", ms).putString("last_check_result", result).apply();
    }

    static String lastCheckText(Context c) {
        long ms = p(c).getLong("last_check_ms", 0);
        if (ms == 0) return "从未执行（等第一次兜底）";
        long delta = (System.currentTimeMillis() - ms) / 60000;
        String ago = delta < 1 ? "刚刚" : delta + " 分钟前";
        return ago + " · " + p(c).getString("last_check_result", "");
    }

    /** 应用启动时按当前设置恢复调度；并清理 v1「实时模式」遗留偏好（L2 已在 v1.2.0 移除）。 */
    static void bootstrap(Context c) {
        SyncJobService.schedule(c, intervalMin(c));
        p(c).edit().remove("realtime").apply();
    }

    private AppSettings() {}
}
