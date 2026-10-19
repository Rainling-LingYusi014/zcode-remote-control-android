package com.dsha.zremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.content.MutableContextWrapper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全进程单例的 WebView 容器。
 * 单例的原因：官方远程控制一次只允许一个手机连接，前台/后台必须共用同一会话实例。
 * 用 MutableContextWrapper 让同一个 WebView 实例能在 Activity 重建后继续复用。
 *
 * 安全边界（评审 P0-2）：JS 桥未来只对 zcode.z.ai 开放；文件/内容访问全部关闭；
 * 外部域名的 http(s) 一律抛给系统浏览器。
 */
public final class PanelController {

    public static final int REQ_FILE_CHOOSER = 4001;
    static final String HOME_URL = "https://zcode.z.ai/";
    private static final String ALLOWED_HOST = "zcode.z.ai";

    private static WebView sWebView;
    private static MutableContextWrapper sCtxWrapper;
    private static Activity sActivity;
    private static ValueCallback<Uri[]> sFileCb;
    private static boolean sLoadedOnce = false;
    private static SharedPreferences sPrefs;
    private static Button sImportPill;

    private PanelController() {}

    /** 取单例 WebView（必要时创建），包进带"导入配对链接"悬浮钮的容器后挂到当前 Activity 上。 */
    @SuppressLint("SetJavaScriptEnabled")
    static View obtainView(Activity activity) {
        ensureCreated(activity.getApplicationContext());
        sCtxWrapper.setBaseContext(activity);
        sActivity = activity;

        FrameLayout root = new FrameLayout(sCtxWrapper);
        root.addView(sWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sImportPill = buildImportPill();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) dp(28);
        root.addView(sImportPill, lp);
        return root;
    }

    /** 页面内搜索按钮（注入）→ 桥 → 此处派发到原生浮层。 */
    static Runnable sOnSearch;

    static void fireSearch() {
        if (sOnSearch == null) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> {
            Runnable r = sOnSearch;
            if (r != null) r.run();
        });
    }

    /** 搜索数据源：页面注入脚本的实时任务清单 JSON。 */
    interface TaskListCallback {
        void onList(String json);
    }

    static void listTasks(TaskListCallback cb) {
        android.webkit.WebView wv = webview();
        if (wv == null) {
            cb.onList("[]");
            return;
        }
        wv.evaluateJavascript(
                "window.__zrcListTasks ? window.__zrcListTasks() : []",
                v -> cb.onList(v == null ? "[]" : v));
    }

    /** 无 UI 创建（Job/Service 用）：WebView 基上下文保持 application，可离屏加载会话。 */
    @SuppressLint("SetJavaScriptEnabled")
    static synchronized void ensureCreated(Context appCtx) {
        if (sWebView != null) return;
        sCtxWrapper = new MutableContextWrapper(appCtx.getApplicationContext());
        sPrefs = sCtxWrapper.getSharedPreferences("zremote", Context.MODE_PRIVATE);
        NotifyEngine.init(sCtxWrapper);
        sWebView = new WebView(sCtxWrapper);
        configure(sWebView);
    }

    /** 供 Job/Service 驱动的单例访问；未创建时返回 null。 */
    static android.webkit.WebView webview() {
        return sWebView;
    }

    /** 非 zcode.z.ai 页面（首页/错误页）时显示，点击后从剪贴板导入配对链接。 */
    private static Button buildImportPill() {
        Button b = new Button(sCtxWrapper);
        b.setText("⤓ 导入配对链接");
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFFCFD8DC);
        b.setBackgroundColor(0xE6202C33);
        b.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(8));
        b.setOnClickListener(v -> showImportDialog());
        b.setVisibility(View.GONE);
        return b;
    }

    /** Activity 销毁：摘下视图、解除 Activity 引用，WebView 本体和会话保留。 */
    static void detach() {
        if (sWebView != null) {
            ViewGroup parent = (ViewGroup) sWebView.getParent();
            if (parent != null) parent.removeView(sWebView);
        }
        sImportPill = null;
        if (sCtxWrapper != null) {
            sCtxWrapper.setBaseContext(sCtxWrapper.getApplicationContext());
        }
        sActivity = null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void configure(WebView wv) {
        // 调试通道默认关闭，设置页可开（排障/探针用）
        WebView.setWebContentsDebuggingEnabled(
                sPrefs.getBoolean("webview_debug", false));
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 安全边界：WebView 内禁止触碰本地文件与内容提供者
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 站点自带深色主题，禁用系统强制变暗避免二次反转（评审 P2-2）
        s.setForceDark(WebSettings.FORCE_DARK_OFF);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, false);

        // JS 桥：WebView 的 URL 路由白名单保证只有 zcode.z.ai 会在这里加载（错误页是我们自己的）
        wv.addJavascriptInterface(new NotifyEngine.Bridge(), NotifyEngine.BRIDGE_NAME);

        wv.setBackgroundColor(0xFF101418);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return route(req.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // 尽早注入：脚本自带"等任务卡出现"的重试，SPA 内路由切换不重新触发本回调，
                // observer 挂在 document.body 上跨路由存活
                if (url != null && url.startsWith("https://" + ALLOWED_HOST)) {
                    view.evaluateJavascript(NotifyEngine.INJECT_JS, null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showErrorPage(view, String.valueOf(err.getDescription()));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 只保存真正的会话链接（/remote/v4?sid=...），官网首页不算（仅存 APP 私有目录）
                if (url != null && url.startsWith("https://" + ALLOWED_HOST + "/remote")) {
                    sPrefs.edit().putString("last_url", url).apply();
                }
                updatePill(url);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (sFileCb != null) sFileCb.onReceiveValue(null); // 释放上次未完成的回调
                sFileCb = cb;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    sActivity.startActivityForResult(i, REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    sFileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny(); // 阶段1不开放摄像头/麦克风
            }
        });
        wv.setDownloadListener((url, ua, contentDisposition, mime, len) -> download(url, ua, contentDisposition, mime));
    }

    static void load(String url) {
        if (sWebView == null) throw new IllegalStateException("panel not attached");
        sLoadedOnce = true;
        sWebView.loadUrl(url);
        updatePill(url);
    }

    /** 分享/剪贴板/深链的统一入口：自家域名应用内打开，外部域名抛系统浏览器。 */
    static void openOrRoute(Uri u) {
        if (u == null) return;
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        String host = u.getHost() == null ? "" : u.getHost();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (ALLOWED_HOST.equals(host)) {
                load(u.toString());
            } else {
                openExternal(u);
            }
        } else {
            toast("不是有效的网页链接");
        }
    }

    private static void updatePill(String url) {
        if (sImportPill == null) return;
        // 远程控制链接带授权特征（token 参数或 /remote、/connect 路径）；官网/营销页一律显示导入钮
        String u = url == null ? "" : url.toLowerCase();
        boolean onSession = u.contains("token=") || u.contains("/remote") || u.contains("/connect");
        sImportPill.setVisibility(onSession ? View.GONE : View.VISIBLE);
    }

    private static void showImportDialog() {
        if (sActivity == null || sActivity.isFinishing()) return;
        final EditText et = new EditText(sActivity);
        et.setSingleLine(true);
        et.setHint("粘贴配对链接（https://zcode.z.ai/...）");
        try {
            ClipboardManager cm = (ClipboardManager)
                    sActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemAt(0) != null) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (!TextUtils.isEmpty(clip) && clip.toString().startsWith("http")) {
                    et.setText(clip);
                    et.setSelection(clip.length());
                }
            }
        } catch (Exception ignored) {
        }
        FrameLayout wrap = new FrameLayout(sActivity);
        int pad = (int) dp(20);
        wrap.setPadding(pad, (int) dp(6), pad, 0);
        wrap.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(sActivity)
                .setTitle("导入配对链接")
                .setMessage("桌面端 ZCode 手机图标的对话框里有\"复制链接\"；把链接发到手机（微信/QQ 均可）后复制，回到这里粘贴打开。\n\n一次性设置：系统设置 → 应用管理 → Zcode RC → 默认打开 → 开启\"打开支持的链接\"，之后扫码即可直接进 APP。")
                .setView(wrap)
                .setPositiveButton("打开", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.startsWith("http://") || t.startsWith("https://")) {
                        openOrRoute(Uri.parse(t));
                    } else {
                        toast("不是有效的网页链接");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                sCtxWrapper.getResources().getDisplayMetrics());
    }

    /** 首次载入用的地址：优先恢复上次会话，否则回首页。 */
    static String resolveHomeUrl() {
        String last = sPrefs == null ? null : sPrefs.getString("last_url", null);
        return last != null ? last : HOME_URL;
    }

    static boolean isFirstLoad() {
        return !sLoadedOnce;
    }

    static boolean canGoBack() {
        return sWebView != null && sWebView.canGoBack();
    }

    static void goBack() {
        if (sWebView != null) sWebView.goBack();
    }

    static void deliverFileChooserResult(Uri[] result) {
        if (sFileCb != null) {
            sFileCb.onReceiveValue(result);
            sFileCb = null;
        }
    }

    /** 从消息历史跳转到对应任务会话：列表页直接点卡；停在详情页则先退回列表重试。 */
    static void openTask(String testid) {
        android.webkit.WebView wv = webview();
        if (wv == null || testid == null || testid.isEmpty()) return;
        String js = "window.__zrcOpenTask && window.__zrcOpenTask('" + testid + "')";
        wv.evaluateJavascript(js, v -> {
            if (v != null && v.contains("nolist")) {
                wv.postDelayed(() -> {
                    try {
                        if (wv.canGoBack()) wv.goBack();
                    } catch (Exception ignored) {
                    }
                    wv.postDelayed(() -> wv.evaluateJavascript(js, v2 -> {
                        if (v2 != null && v2.contains("nolist")) {
                            if (sActivity != null) {
                                android.widget.Toast.makeText(sActivity,
                                        "任务列表中找不到该会话，请回桌面确认", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                    }), 1500);
                }, 400);
            }
        });
    }

    /** URL 路由：自家域名应用内打开，其余 http(s) 与外部协议全部抛给系统浏览器。 */
    private static boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = u.getHost() == null ? "" : u.getHost();
            if (ALLOWED_HOST.equals(host)) return false; // 应用内打开
            openExternal(u);
            return true;
        }
        openExternal(u);
        return true;
    }

    private static void openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sCtxWrapper.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
            // 没有应用能处理该协议，静默忽略
        }
    }

    private static void download(String url, String ua, String contentDisposition, String mime) {
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                openExternal(u);
                return;
            }
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(u);
            r.setTitle(name);
            if (mime != null) r.setMimeType(mime);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager)
                    sCtxWrapper.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            toast("已开始下载：" + name);
        } catch (Exception e) {
            openExternal(Uri.parse(url));
        }
    }

    private static void showErrorPage(WebView v, String msg) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#101418;color:#cfd8dc;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:92%;margin:0}"
                + "div{text-align:center}p{opacity:.6;font-size:13px}"
                + "button{margin-top:16px;padding:10px 36px;border-radius:10px;"
                + "border:1px solid #455a64;background:#1c262b;color:#cfd8dc;font-size:15px}</style></head>"
                + "<body><div><p>页面加载失败</p><p>" + escape(msg) + "</p>"
                + "<button onclick='location.reload()'>重试</button></div></body></html>";
        v.loadData(html, "text/html; charset=utf-8", null);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void toast(String msg) {
        if (sActivity != null) {
            Toast.makeText(sActivity, msg, Toast.LENGTH_SHORT).show();
        }
    }
}
