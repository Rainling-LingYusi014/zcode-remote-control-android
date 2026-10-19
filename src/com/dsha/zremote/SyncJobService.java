package com.dsha.zremote;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

/**
 * L3 兜底：即使 APP 进程已死，系统按周期拉起本 Job，
 * 离屏加载会话页（注入脚本自动跑 diff 回传）→ 挂 30 秒收事件 → 结束。
 * 名义周期 15-30 分钟；HyperOS/Doze 下实际可能被推迟，属预期内。
 */
public class SyncJobService extends JobService {

    public static final int JOB_ID = 4711;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mFinish;

    @Override
    public boolean onStartJob(JobParameters params) {
        Context app = getApplicationContext();
        NotifyEngine.init(app);
        AppSettings.setLastCheck(app, System.currentTimeMillis(), "执行中");

        PanelController.ensureCreated(app);
        WebView wv = PanelController.webview();
        if (wv != null) {
            Runnable kick = () -> {
                try {
                    String cur = wv.getUrl();
                    if (cur != null && cur.contains("/remote")) {
                        // 会话页还活着：直接触发一次 diff
                        wv.evaluateJavascript("if(window.__zrcFire)window.__zrcFire();", null);
                    } else {
                        wv.loadUrl(PanelController.resolveHomeUrl());
                    }
                } catch (Exception ignored) {
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                kick.run();
            } else {
                mHandler.post(kick);
            }
        }

        mFinish = () -> {
            AppSettings.setLastCheck(app, System.currentTimeMillis(), "完成");
            jobFinished(params, false);
        };
        mHandler.postDelayed(mFinish, 30_000);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mFinish != null) mHandler.removeCallbacks(mFinish);
        return false;
    }

    /** 注册/更新周期任务（同 JOB_ID 重复调度即替换）。 */
    static void schedule(Context ctx, int intervalMin) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, SyncJobService.class))
                .setPeriodic(Math.max(15, intervalMin) * 60_000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(JOB_ID);
    }
}
