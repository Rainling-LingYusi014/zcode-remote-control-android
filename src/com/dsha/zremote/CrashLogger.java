package com.dsha.zremote;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** 未捕获异常落盘（filesDir/crash-*.txt），设置页可查看。 */
public final class CrashLogger implements Thread.UncaughtExceptionHandler {

    private static final int MAX_FILES = 5;
    private final Thread.UncaughtExceptionHandler mDefault;
    private final Context mApp;

    private CrashLogger(Context app) {
        mApp = app;
        mDefault = Thread.getDefaultUncaughtExceptionHandler();
    }

    static void install(Context c) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(c.getApplicationContext()));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String text = "时间: " + new java.util.Date() + "\n线程: " + t.getName()
                    + "\n\n" + sw;
            File dir = mApp.getFilesDir();
            File[] old = dir.listFiles((d, n) -> n.startsWith("crash-"));
            if (old != null && old.length >= MAX_FILES) {
                Arrays.sort(old, Comparator.comparingLong(File::lastModified));
                old[0].delete();
            }
            try (FileOutputStream out = new FileOutputStream(
                    new File(dir, "crash-" + System.currentTimeMillis() + ".txt"))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        if (mDefault != null) mDefault.uncaughtException(t, e);
    }

    static File latest(Context c) {
        File[] files = c.getFilesDir().listFiles((d, n) -> n.startsWith("crash-"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        return files[files.length - 1];
    }

    static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int r = in.read(buf);
            return new String(buf, 0, Math.max(0, r), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败: " + e;
        }
    }

    static void clear(Context c) {
        File[] files = c.getFilesDir().listFiles((d, n) -> n.startsWith("crash-"));
        if (files != null) for (File f : files) f.delete();
    }
}
