package com.dsha.zremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 用量信息：直接打开智谱官方的编码套餐用量页（曲线救国）。
 * 登录态存在 APP 全局 Cookie（CookieManager），登录一次后续免登。
 * 与主面板的 URL 白名单互不影响：本 Activity 是独立的受限浏览页，仅允许 bigmodel.cn。
 */
public class UsageActivity extends Activity {

    static final String USAGE_URL = "https://bigmodel.cn/coding-plan/personal/usage";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Theme.statusBar());
        w.setNavigationBarColor(Theme.statusBar());

        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setForceDark(WebSettings.FORCE_DARK_OFF);
        CookieManager.getInstance().setAcceptCookie(true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                // 仅允许智谱系域名留在页内，其余抛给系统浏览器
                if (host.equals("bigmodel.cn") || host.endsWith(".bigmodel.cn")
                        || host.equals("z.ai") || host.endsWith(".z.ai")
                        || host.equals("open.bigmodel.cn")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        wv.loadUrl(USAGE_URL);
        setContentView(wv);
    }

    @Override
    public void onBackPressed() {
        WebView wv = findWebView();
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private WebView findWebView() {
        android.view.View v = findViewById(android.R.id.content);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            if (vg.getChildCount() > 0 && vg.getChildAt(0) instanceof WebView) {
                return (WebView) vg.getChildAt(0);
            }
        }
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush(); // 保登录态
    }
}
