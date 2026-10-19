package com.dsha.zremote;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.webkit.WebView;

/**
 * L2 实时模式（实验性）：前台服务保进程，离屏 WebView 保持会话在线，
 * 事件经注入管道近实时回传。不设硬验收——HyperOS 极端省电下仍可能被冻结。
 */
public class KeepAliveService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context app = getApplicationContext();
        NotifyEngine.init(app);
        startForeground(100, buildNotification());
        PanelController.ensureCreated(app);
        WebView wv = PanelController.webview();
        if (wv != null) {
            try {
                String cur = wv.getUrl();
                if (cur == null || !cur.contains("/remote")) {
                    wv.loadUrl(PanelController.resolveHomeUrl());
                }
            } catch (Exception ignored) {
            }
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, NotifyEngine.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("ZCode RC 实时模式")
                .setContentText("会话保持在线，任务变化将实时通知")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
    }
}
