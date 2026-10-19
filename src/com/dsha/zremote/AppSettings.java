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

    static boolean realtime(Context c) {
        return p(c).getBoolean("realtime", false);
    }

    static void setRealtime(Context c, boolean on) {
        p(c).edit().putBoolean("realtime", on).apply();
        if (on) {
            KeepAliveService.start(c);
        } else {
            KeepAliveService.stop(c);
        }
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

    /** 应用启动时按当前设置恢复调度与服务。 */
    static void bootstrap(Context c) {
        SyncJobService.schedule(c, intervalMin(c));
        if (realtime(c)) {
            KeepAliveService.start(c);
        }
    }

    private AppSettings() {}
}
