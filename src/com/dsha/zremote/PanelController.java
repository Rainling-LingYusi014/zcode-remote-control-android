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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

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
    static final String ALLOWED_HOST = "zcode.z.ai";

    private static WebView sWebView;
    private static MutableContextWrapper sCtxWrapper;
    private static Activity sActivity;
    private static ValueCallback<Uri[]> sFileCb;
    private static boolean sLoadedOnce = false;
    private static SharedPreferences sPrefs;
    private static ViewGroup sPillBox;
    private static TextView sLoginHint;
    private static View sCorner;
    private static View sHelp;
    private static View sGh;

    private PanelController() {}

    /** 取单例 WebView（必要时创建），包进带"扫码/粘贴导入"悬浮钮的容器后挂到当前 Activity 上。 */
    @SuppressLint("SetJavaScriptEnabled")
    static View obtainView(Activity activity) {
        ensureCreated(activity.getApplicationContext());
        sCtxWrapper.setBaseContext(activity);
        sActivity = activity;

        FrameLayout root = new FrameLayout(sCtxWrapper);
        // 重建时序下同一 WebView 可能还挂在旧容器上：先摘下再挂，避免
        // "The specified child already has a parent"（审查 P2：obtainView 无重挂防御）
        android.view.ViewParent oldParent = sWebView.getParent();
        if (oldParent instanceof ViewGroup) ((ViewGroup) oldParent).removeView(sWebView);
        root.addView(sWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右上角扫码钮：桌面端刷新二维码作废旧会话时随时可重连。
        // 平时隐藏（避免遮挡正常界面），注入脚本检测到"等待配对/连接中断/校验失败"等状态时出现
        View corner = newScanIcon(sCtxWrapper, () -> {
            if (sOnScan != null) sOnScan.run();
        });
        corner.setVisibility(View.GONE);
        sCorner = corner;
        FrameLayout.LayoutParams cornerLp = new FrameLayout.LayoutParams(
                (int) dp(40), (int) dp(40), Gravity.TOP | Gravity.END);
        cornerLp.topMargin = (int) dp(10);
        cornerLp.rightMargin = (int) dp(10);
        root.addView(corner, cornerLp);

        // 扫码钮左侧的 ? 帮助钮：点开看完整的配对/重连方式说明，与扫码钮同显隐
        View help = new QrHelpIconView(sCtxWrapper);
        help.setContentDescription("配对帮助");
        help.setOnClickListener(v -> showPairHelp());
        help.setVisibility(View.GONE);
        sHelp = help;
        FrameLayout.LayoutParams helpLp = new FrameLayout.LayoutParams(
                (int) dp(40), (int) dp(40), Gravity.TOP | Gravity.END);
        helpLp.topMargin = (int) dp(10);
        helpLp.rightMargin = (int) dp(58);
        root.addView(help, helpLp);

        // 帮助钮左侧的 GitHub 仓库入口：与扫码/帮助钮同尺寸同色同显隐
        android.widget.ImageView gh = new android.widget.ImageView(sCtxWrapper);
        gh.setImageResource(R.drawable.ic_github);
        gh.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int ghPad = (int) dp(8); // 40dp 视图中留出 24dp 内容区，视觉跨度与另两个图标一致
        gh.setPadding(ghPad, ghPad, ghPad, ghPad);
        gh.setContentDescription("GitHub 仓库");
        gh.setOnClickListener(v -> openExternal(
                Uri.parse("https://github.com/Rainling-LingYusi014/zcode-remote-control-android")));
        gh.setVisibility(View.GONE);
        sGh = gh;
        FrameLayout.LayoutParams ghLp = new FrameLayout.LayoutParams(
                (int) dp(40), (int) dp(40), Gravity.TOP | Gravity.END);
        ghLp.topMargin = (int) dp(10);
        ghLp.rightMargin = (int) dp(106);
        root.addView(gh, ghLp);

        LinearLayout pills = new LinearLayout(sCtxWrapper);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER);
        pills.addView(buildPillButton("扫码配对", v -> {
            if (sOnScan != null) sOnScan.run();
        }));
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pasteLp.leftMargin = (int) dp(10);
        pills.addView(buildPillButton("粘贴链接", v -> showImportDialog()), pasteLp);

        LinearLayout pillBox = new LinearLayout(sCtxWrapper);
        pillBox.setOrientation(LinearLayout.VERTICAL);
        pillBox.setGravity(Gravity.CENTER);
        pillBox.addView(pills);
        pillBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) dp(28);
        root.addView(pillBox, lp);
        sPillBox = pillBox;

        // 登录方式提示：仅在首次登录前（登录页/首页）显示。备用机实测：重扫同一二维码时
        // 单设备限制 + z.ai（全球）登录容易异常，推荐 bigmodel 国内账号
        TextView loginHint = new TextView(sCtxWrapper);
        loginHint.setText("建议优先使用 bigmodel（智谱国内）账号登录\n"
                + "z.ai（全球）登录在重扫二维码 / 更换设备时容易异常（同一会话仅支持一台设备）\n"
                + "若登录页无法打开，请到「设置」页右上角扫码登录");
        loginHint.setTextColor(0xFFD7E0E6);
        loginHint.setTextSize(12);
        loginHint.setGravity(Gravity.CENTER);
        loginHint.setLineSpacing(dp(3), 1f);
        int hintPad = (int) dp(14);
        loginHint.setPadding(hintPad, (int) dp(10), hintPad, (int) dp(10));
        // 80% 不透明深底（0xCC ≈ 204/255），保证提示在官方登录页任何底色上都读得清
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(0xCC0A0E12);
        hintBg.setCornerRadius(dp(10));
        loginHint.setBackground(hintBg);
        loginHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = (int) dp(56);
        hintLp.leftMargin = (int) dp(20);
        hintLp.rightMargin = (int) dp(20);
        root.addView(loginHint, hintLp);
        sLoginHint = loginHint;
        return root;
    }

    /** 页面内搜索按钮（注入）→ 桥 → 此处派发到原生浮层。 */
    static Runnable sOnSearch;

    /** 扫码 pill → 派发到 Activity（相机权限申请只能在 Activity 侧做）。 */
    static Runnable sOnScan;

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

    /** 主帧当前 URL 快照：onPageStarted/Finished 在主线程更新，桥线程只读快照、不碰 WebView。 */
    private static volatile String sCurrentUrl;

    /** 每次进入会话主帧重新生成；注入脚本随桥调用带回，跨域 iframe 读不到（审查 P1-1）。 */
    private static volatile String sBridgeToken;

    private static String newToken() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /**
     * 桥面信任判定：必须是会话主帧（https + zcode.z.ai 精确 host）且随调用带回本次 token。
     * 只查主帧 URL 挡不住同页嵌的第三方 iframe（addJavascriptInterface 对子帧也可见），
     * 所以再加 token：注入脚本运行在主帧，token 放在主帧 window 上，跨域 iframe 无法读取。
     */
    static boolean bridgeTrusted(String token) {
        String cur = sBridgeToken;
        if (cur == null || token == null || !cur.equals(token)) return false;
        return onTrustedOrigin();
    }

    /** 桥面信任域＝精确 zcode.z.ai；路由白名单（inAppHost，含 OAuth 域）是更宽的另一层边界。 */
    static boolean onTrustedOrigin() {
        String url = sCurrentUrl;
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            return "https".equals(u.getScheme()) && ALLOWED_HOST.equals(u.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** 会话主帧判定：https + 精确 host。前缀匹配会被 zcode.z.ai.evil.com 绕过（审查 P1-2）。 */
    static boolean isSessionUrl(String url) {
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            return "https".equals(u.getScheme()) && ALLOWED_HOST.equals(u.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** 会话页下发 token + 注入脚本；幂等（INJECT_JS 自带 __zrcInstalled 短路）。 */
    private static void injectSessionScript(WebView view) {
        String tok = sBridgeToken;
        if (tok == null) {
            tok = newToken();
            sBridgeToken = tok;
        }
        view.evaluateJavascript("window.__zrcTok=\"" + tok + "\";" + NotifyEngine.INJECT_JS, null);
    }

    /**
     * 带授权特征的会话链接：路径前缀 /remote、/connect，或精确的 token / access_token 参数名。
     * 只用于"是否算登录过"与底部操作钮显隐，安全判定仍走 {@link #isSessionUrl}。
     */
    private static boolean isSessionLikeUrl(String url) {
        if (!isSessionUrl(url)) return false;
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath();
            if (path != null && (path.startsWith("/remote") || path.startsWith("/connect"))) return true;
            String frag = u.getFragment();
            String fp = frag == null ? "" : frag;
            if (fp.startsWith("/")) fp = fp.substring(1);
            if (fp.startsWith("remote") || fp.startsWith("connect")) return true;
            return hasTokenParam(u.getQuery()) || hasTokenParam(frag);
        } catch (Exception e) {
            return false;
        }
    }

    /** 参数名完全相等才算（避免 xtoken= / token_x= 误命中）。 */
    private static boolean hasTokenParam(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String kv : s.split("[&#?;]")) {
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq);
            if ("token".equals(k) || "access_token".equals(k)) return true;
        }
        return false;
    }

    /** 隐藏右上角三个浮动钮（扫码/帮助/GitHub），离开会话页时调用。 */
    private static void hideCorner() {
        if (sCorner != null) sCorner.setVisibility(View.GONE);
        if (sHelp != null) sHelp.setVisibility(View.GONE);
        if (sGh != null) sGh.setVisibility(View.GONE);
    }

    /** 注入脚本回报"等待桌面端配对"：仅死会话/等待页显示右上角扫码钮，正常界面不遮挡。JS 桥线程调用。 */
    static void onPairingWait(boolean waiting) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> showCorner(waiting));
    }

    private static void showCorner(boolean show) {
        // 首次登录前一律不亮：错误页/等待页在未登录状态下扫码无意义
        if (!sPrefs.getBoolean("ever_logged_in", false)) show = false;
        if (sCorner != null) sCorner.setVisibility(show ? View.VISIBLE : View.GONE);
        if (sHelp != null) sHelp.setVisibility(show ? View.VISIBLE : View.GONE);
        if (sGh != null) sGh.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 扫码钮（面板右上角与设置页共用）：图案、行为完全一致，避免两处各画一份走样。 */
    static View newScanIcon(Context ctx, Runnable onClick) {
        View v = new QrScanIconView(ctx);
        v.setContentDescription("扫码配对");
        v.setOnClickListener(x -> {
            if (onClick != null) onClick.run();
        });
        return v;
    }

    /** 右上角 ? 帮助钮：圆环跨度与扫码框四角跨度一致（0.6s），线宽颜色同扫码线条。 */
    private static final class QrHelpIconView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

        QrHelpIconView(Context c) {
            super(c);
            setClickable(true);
            mText.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = getWidth();
            mPaint.setColor(0xE6CFD8DC);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1.5f, s * 0.06f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            cv.drawCircle(s / 2f, s / 2f, s * 0.30f, mPaint);
            mText.setColor(0xE6CFD8DC);
            mText.setTextSize(s * 0.30f);
            mText.setFakeBoldText(true);
            cv.drawText("?", s / 2f, s / 2f - (mText.ascent() + mText.descent()) / 2f, mText);
        }
    }

    /** 右上角 ? 帮助钮：完整列出全部配对/重连方式与适用场景。 */
    private static void showPairHelp() {
        if (sActivity == null || sActivity.isFinishing()) return;
        new AlertDialog.Builder(sActivity)
                .setTitle("配对与重连指南")
                .setMessage("【方式一 · 扫码（推荐）】\n"
                        + "桌面端 ZCode 点手机图标出示二维码 → 回本应用点右上角扫码钮，对准扫一下，识别后自动进入会话。\n\n"
                        + "【方式二 · 复制链接】\n"
                        + "桌面端配对对话框点「复制链接」→ 通过微信/QQ 发到手机 → 复制该链接 → 回本应用点底部「粘贴链接」。\n\n"
                        + "【方式三 · 浏览器分享】\n"
                        + "在手机浏览器打开配对链接 → 浏览器菜单点「分享」→ 选择本应用，一步导入。\n\n"
                        + "【方式四 · 链接直达（一次性设置）】\n"
                        + "系统设置 → 应用管理 → 本应用 → 默认打开 → 开启「打开支持的链接」。此后手机扫码会直接唤起本应用。\n\n"
                        + "【何时需要重新配对？】\n"
                        + "桌面端刷新二维码或重开会话后旧链接立即失效。手机端出现\"等待桌面端配对\"、\"连接中断\"、\"校验失败\"等提示时，点右上角扫码钮扫新二维码即可恢复；右上角按钮只在异常状态下出现，不会遮挡正常界面。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 非 zcode.z.ai 页面（首页/错误页）时显示的底部操作钮（扫码 / 粘贴导入）。 */
    private static Button buildPillButton(String text, View.OnClickListener l) {
        Button b = new Button(sCtxWrapper);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFFCFD8DC);
        b.setBackgroundColor(0xE6202C33);
        b.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(8));
        b.setOnClickListener(l);
        return b;
    }

    /** 右上角常驻扫码钮：四角取景框 + 中间扫线的"扫描"图形，半透明深色圆角底，深浅主题下都可见。 */
    private static final class QrScanIconView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        QrScanIconView(Context c) {
            super(c);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = getWidth();
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0x8C141A20);
            cv.drawRoundRect(0, 0, s, s, s * 0.22f, s * 0.22f, mPaint);
            mPaint.setColor(0xE6CFD8DC);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1.5f, s * 0.06f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            float m = s * 0.20f;
            float a = s * 0.22f; // 取景角臂长
            // 四角取景框
            cv.drawLine(m, m + a, m, m, mPaint);
            cv.drawLine(m, m, m + a, m, mPaint);
            cv.drawLine(s - m - a, m, s - m, m, mPaint);
            cv.drawLine(s - m, m, s - m, m + a, mPaint);
            cv.drawLine(m, s - m, m + a, s - m, mPaint);
            cv.drawLine(m, s - m - a, m, s - m, mPaint);
            cv.drawLine(s - m - a, s - m, s - m, s - m, mPaint);
            cv.drawLine(s - m, s - m - a, s - m, s - m, mPaint);
            // 中间扫线
            cv.drawLine(m + a * 0.5f, s / 2f, s - m - a * 0.5f, s / 2f, mPaint);
        }
    }

    /**
     * Activity 销毁：摘下视图、解除 Activity 引用，WebView 本体和会话保留。
     * expected 比对：语言/字体缩放等触发的重建时序是"新 onCreate → 旧 onDestroy"，
     * 旧实例不得拆掉新实例刚挂载的视图与引用（审查 P2-2）。
     */
    static void detach(Activity expected) {
        if (sActivity != null && sActivity != expected) return;
        if (sWebView != null) {
            ViewGroup parent = (ViewGroup) sWebView.getParent();
            if (parent != null) parent.removeView(sWebView);
        }
        sPillBox = null;
        sLoginHint = null;
        sCorner = null;
        sHelp = null;
        sGh = null;
        sOnSearch = null;
        sOnScan = null;
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
        // 新版远程控制页 OAuth 登录跳 chat.z.ai，跨子域授权链路需要第三方 Cookie
        cm.setAcceptThirdPartyCookies(wv, true);

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
                sCurrentUrl = url; // 先更新快照再做注入判定；302 链只在链首触发本回调，落点见 onPageCommitVisible
                if (isSessionUrl(url)) {
                    // 每次进入会话主帧换新 token，注入脚本带着它调用桥；跨域子帧拿不到
                    String tok = newToken();
                    sBridgeToken = tok;
                    // 尽早注入：脚本自带"等任务卡出现"的重试，SPA 内路由切换不重新触发本回调，
                    // observer 挂在 document.body 上跨路由存活
                    view.evaluateJavascript(
                            "window.__zrcTok=\"" + tok + "\";" + NotifyEngine.INJECT_JS, null);
                    return;
                }
                sBridgeToken = null; // 非会话主帧：桥面立即失效
                // 服务端 302 不经过 shouldOverrideUrlLoading：外域主帧不在应用内渲染，
                // 交给系统浏览器（官方 OAuth 域仍在 inAppHost 内，不受影响）（审查 P1-2）
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    Uri u = Uri.parse(url);
                    if (!inAppHost(u.getHost())) {
                        view.stopLoading();
                        openExternal(u);
                    }
                    hideCorner(); // 离开会话页（如 OAuth 中转）：角标不再有意义，避免旧状态滞留
                }
            }

            /**
             * 主帧提交时的落点校验（审查 R2）：服务端 302 链不会再次触发 onPageStarted，
             * 只有 commit 这一层能拿到最终落点。url 与 getUrl() 相同才算主帧提交——
             * 页内第三方子帧（如验证码容器）提交时两者不同，绝不能因此停掉整个页面。
             */
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                if (url == null) return;
                String cur = view.getUrl();
                if (cur != null && !cur.equals(url)) return; // 子帧提交，忽略
                sCurrentUrl = url;
                if (isSessionUrl(url)) {
                    injectSessionScript(view);
                    return;
                }
                sBridgeToken = null;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Uri u = Uri.parse(url);
                    if (!inAppHost(u.getHost())) {
                        view.stopLoading();
                        openExternal(u);
                        hideCorner();
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showErrorPage(view, String.valueOf(err.getDescription()));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                sCurrentUrl = url;
                if (isSessionUrl(url)) {
                    // 兜底：SPA/慢加载时 onPageStarted 的注入可能落空；302 中转进入会话页时
                    // onPageStarted 只在链首触发、token 已被上一跳清空——这里补生成再注入，
                    // 否则 OAuth 登录落地页没有脚本，通知/搜索/主题/断连检测全部失效（审查 R1）
                    injectSessionScript(view);
                    // 会话落地判定（审查 R4a）：路径前缀 /remote、/connect 或精确的 token 参数名，
                    // 不再用 contains("token=") 这类子串匹配（/blog/how-to-connect 会误命中）
                    if (isSessionLikeUrl(url)) {
                        // 落地过会话页＝完成过一次登录；登录前不亮扫码/粘贴入口（首次使用不挡登录 UI）
                        sPrefs.edit().putString("last_url", url)
                                .putBoolean("ever_logged_in", true).apply();
                    }
                } else {
                    sBridgeToken = null; // 落在非会话页：不保留上一页的 token（审查 R2a）
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
                // Job 冷启动时进程里可能没有 Activity：直接拒绝，别让页面等一个永远不来的回调（审查 #12）
                if (sActivity == null || sActivity.isFinishing()) {
                    sFileCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
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
                // targetSdk 33 禁明文：http 深链改写成 https 再载入（审查 #16）
                String target = "http".equals(scheme)
                        ? u.buildUpon().scheme("https").build().toString()
                        : u.toString();
                load(target);
            } else {
                openExternal(u);
            }
        } else {
            toast("不是有效的网页链接");
        }
    }

    private static void updatePill(String url) {
        if (sPillBox == null) return;
        // 远程控制链接带授权特征（/remote、/connect 路径或 token 参数）；官网/营销页一律显示操作钮
        boolean onSession = isSessionLikeUrl(url);
        // 首次使用（还没登录过）不显示操作钮：连账号都没有，扫码/粘贴只会挡登录页、徒增异常路径
        boolean firstRun = !sPrefs.getBoolean("ever_logged_in", false);
        sPillBox.setVisibility(onSession || firstRun ? View.GONE : View.VISIBLE);
        // 未登录阶段的登录方式提示：登录完成后（含进入会话页）即隐藏
        if (sLoginHint != null) {
            sLoginHint.setVisibility(!onSession && firstRun ? View.VISIBLE : View.GONE);
        }
    }

    /** 设置页「重新配对 → 粘贴链接」与面板 pill 共用的导入入口。 */
    static void showImportDialog() {
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
                .setMessage("最快：桌面端 ZCode 打开「手机远程控制」显示二维码后，用面板右上角的扫码钮（或底部「扫码配对」）直接扫。\n\n备选一：桌面端配对对话框点\"复制链接\"，把链接发到手机（微信/QQ 均可）后复制，回到这里粘贴打开。\n\n备选二：在手机浏览器打开配对链接，点「分享」选本应用即可导入。")
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
        String js = "window.__zrcOpenTask && window.__zrcOpenTask("
                + JSONObject.quote(testid) + ")";
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

    /**
     * URL 路由：z.ai / bigmodel.cn 官方系域名应用内打开，其余 http(s) 与外部协议全部抛给系统浏览器。
     * 白名单必须覆盖 OAuth 登录域（chat.z.ai）：新版远程控制页的登录跳转若被甩出 WebView，
     * CSRF nonce 与会话就分了家，回调校验必然失败（2026-10 桌面 3.16.2 实测）。
     */
    private static boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (inAppHost(u.getHost())) {
                if ("http".equals(scheme)) {
                    // 明文被 targetSdk 33 拒绝，改写为 https 再加载（审查 #16）
                    WebView wv = sWebView;
                    if (wv != null) {
                        wv.loadUrl(u.buildUpon().scheme("https").build().toString());
                    }
                    return true;
                }
                return false; // 应用内打开
            }
            openExternal(u);
            return true;
        }
        openExternal(u);
        return true;
    }

    static boolean inAppHost(String host) {
        if (host == null || host.isEmpty() || host.charAt(0) == '.') return false;
        return host.equals("z.ai") || host.endsWith(".z.ai")
                || host.equals("bigmodel.cn") || host.endsWith(".bigmodel.cn");
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
            if ("blob".equals(scheme) || "data".equals(scheme)) {
                // 这类下载只有页面自己能把数据落盘，交系统浏览器也无人接手（审查 #17）
                toast("暂不支持此类型文件下载，请在电脑端保存");
                return;
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                openExternal(u);
                return;
            }
            // 下载可被页面内任意脚本/iframe 触发：只允许官方域走 DownloadManager，
            // 其余交系统浏览器（它有独立的下载安全提示）（审查 P2）
            if (!inAppHost(u.getHost())) {
                openExternal(u);
                return;
            }
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            if (TextUtils.isEmpty(name) || name.contains("/") || name.contains("\\")) {
                name = "download";
            }
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
        showCorner(true); // 会话页整页加载失败＝很可能旧链接已失效，亮出扫码钮供直接重配
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
