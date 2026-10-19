package com.dsha.zremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 原生配色，跟随页面主题（注入脚本检测页面背景亮度 → 桥回传）。
 */
public final class Theme {

    public interface Listener {
        void onThemeChanged(boolean dark);
    }

    public static volatile boolean dark = true;
    private static Listener sListener;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private Theme() {}

    static void load(Context c) {
        dark = c.getSharedPreferences("zremote", Context.MODE_PRIVATE)
                .getBoolean("theme_dark", true);
    }

    /** JS 桥线程调用；变更时在主线程回调监听者。 */
    static void set(Context c, boolean isDark) {
        boolean changed = Theme.dark != isDark;
        Theme.dark = isDark;
        if (changed) {
            c.getSharedPreferences("zremote", Context.MODE_PRIVATE)
                    .edit().putBoolean("theme_dark", isDark).apply();
        }
        if (changed && sListener != null) {
            sMain.post(() -> {
                Listener l = sListener;
                if (l != null) l.onThemeChanged(isDark);
            });
        }
    }

    static void setListener(Listener l) {
        sListener = l;
    }

    public static int bg() {
        return dark ? 0xFF101418 : 0xFFF3F5F7;
    }

    public static int surface() {
        return dark ? 0xE614191D : 0xEEFFFFFF;
    }

    public static int divider() {
        return dark ? 0xFF2A353B : 0xFFDDE3E8;
    }

    public static int textPrimary() {
        return dark ? 0xFFE1E9EE : 0xFF1A2329;
    }

    public static int textSecondary() {
        return dark ? 0xFF8A97A0 : 0xFF5F6B76;
    }

    public static int accent() {
        return dark ? 0xFF9FC4E8 : 0xFF2B6CB0;
    }

    public static int statusBar() {
        return dark ? 0xFF101418 : 0xFFE8EDF2;
    }
}
