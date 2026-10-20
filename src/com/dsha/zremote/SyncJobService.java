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
 * L3 兜底：即使 APP 进程已死，系统按周期拉起本 Job。
 * v2 起两级策略：
 *  · 直推开启 → 轻量模式：只拉一次 ntfy（一次 HTTP，不建 WebView，省电省流量），
 *    拉取失败才回落全量；
 *  · 直推关闭/失败 → 全量模式：离屏加载会话页（注入脚本自动跑 diff 回传）→ 挂 30 秒收事件。
 * 名义周期 15 分钟-2 小时可调；HyperOS/Doze 下实际可能被推迟，属预期内。
 */
public class SyncJobService extends JobService {

    public static final int JOB_ID = 4711;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mFinish;

    @Override
    public boolean onStartJob(JobParameters params) {
        Context app = getApplicationContext();
        NotifyEngine.init(app);

        if (NtfyReceiver.enabled(app)) {
            AppSettings.setLastCheck(app, System.currentTimeMillis(), "执行中（直推轻量）");
            new Thread(() -> {
                int n;
                try {
                    n = NtfyReceiver.pollOnce(app);
                } catch (Exception e) {
                    AppSettings.setLastCheck(app, System.currentTimeMillis(),
                            "直推失败，回落全量：" + e.getMessage());
                    runFull(app, params);
                    return;
                }
                AppSettings.setLastCheck(app, System.currentTimeMillis(), "直推拉取 +" + n);
                jobFinished(params, false);
            }, "zrc-job-ntfy").start();
            return true;
        }

        AppSettings.setLastCheck(app, System.currentTimeMillis(), "执行中（全量）");
        runFull(app, params);
        return true;
    }

    /**
     * 全量兜底：拉起离屏 WebView 让注入脚本自己 diff（v1 行为，原样保留）。
     * WebView 的创建与操作必须整体落在主线程（审查 P2-1）：无 Looper 的 Job 线程上
     * new WebView 会抛 RuntimeException，冷进程+离线时曾形成崩溃循环。
     */
    private void runFull(Context app, JobParameters params) {
        mHandler.post(() -> {
            PanelController.ensureCreated(app);
            WebView wv = PanelController.webview();
            if (wv != null) {
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
            }
        });

        mFinish = () -> {
            AppSettings.setLastCheck(app, System.currentTimeMillis(), "完成");
            jobFinished(params, false);
        };
        mHandler.postDelayed(mFinish, 30_000);
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
