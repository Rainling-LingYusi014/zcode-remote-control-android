package com.dsha.zremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;
import java.util.ArrayList;

/**
 * Zcode RC — ZCode 远程控制的 Android 壳客户端。
 * 阶段2b：底部「面板 / 消息」双 Tab + 前台任务通知。
 */
public class MainActivity extends Activity
        implements NotifyEngine.HistoryListener, Theme.Listener {

    private static final int TAB_PANEL = 0;
    private static final int TAB_MESSAGES = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int REQ_NOTIF_PERM = 41;
    private static final int REQ_CAM_PERM = 42;
    private static final int REQ_SCAN = 43;

    private View mPanelView;
    private ListView mListView;
    private View mSettingsView;
    private TextView mMsgHeader;
    private TextView mHeartbeat;
    private TextView[] mTabButtons;
    private LinearLayout mBottomBar;
    private HistoryAdapter mAdapter;
    private TextView mNtfyStatus;
    private int mTab = TAB_PANEL;
    private LinearLayout mRoot;
    private FrameLayout mContent;
    private View mSearchOverlay;
    private android.widget.EditText mSearchInput;
    private ListView mSearchList;
    private final ArrayList<JSONObject> mSearchAll = new ArrayList<>();
    private final ArrayList<JSONObject> mSearchShown = new ArrayList<>();
    private android.widget.BaseAdapter mSearchAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.load(getApplicationContext());
        Window w = getWindow();
        w.setStatusBarColor(Theme.statusBar());
        w.setNavigationBarColor(Theme.statusBar());

        mPanelView = PanelController.obtainView(this);
        PanelController.sOnSearch = () -> showSearch();
        PanelController.sOnScan = this::startScanPairing;
        mAdapter = new HistoryAdapter();
        mListView = buildListView();
        mSettingsView = buildSettingsView();
        mBottomBar = buildBottomBar();
        NotifyEngine.setHistoryListener(this);
        Theme.setListener(this);
        AppSettings.bootstrap(this);

        // 行列结构：内容区(weight=1) + 底部按钮栏，物理不重叠（修遮挡）
        mContent = new FrameLayout(this);
        mContent.addView(mPanelView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mContent.addView(mListView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mContent.addView(mSettingsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.addView(mContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mRoot.addView(mBottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(mRoot);

        CrashLogger.install(getApplicationContext());
        if (AppLock.enabled(this) && AppLock.hasPin(this)) lockNow();
        mReturnLock = true;

        showTab(TAB_PANEL);
        requestNotificationPermissionOnce();
        handleIntent(getIntent());
    }

    // ---- 会话搜索 ----

    private void showSearch() {
        if (mSearchOverlay != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Theme.bg());
        overlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        mSearchInput = new android.widget.EditText(this);
        mSearchInput.setHint("🔍 搜索会话（名称/状态）…");
        mSearchInput.setSingleLine(true);
        mSearchInput.setTextSize(15);
        mSearchInput.setTextColor(Theme.textPrimary());
        int p = (int) dp(16);
        mSearchInput.setPadding(p, (int) dp(12), p, (int) dp(12));
        box.addView(mSearchInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mSearchList = new ListView(this);
        mSearchList.setDivider(new android.graphics.drawable.ColorDrawable(Theme.divider()));
        mSearchList.setDividerHeight(1);
        mSearchAdapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return mSearchShown.size();
            }

            @Override
            public JSONObject getItem(int position) {
                return mSearchShown.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = (convertView instanceof LinearLayout)
                        ? (LinearLayout) convertView : null;
                if (row == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    int pad = (int) dp(16);
                    row.setPadding(pad, (int) dp(12), pad, (int) dp(12));
                    TextView name = new TextView(MainActivity.this);
                    name.setTextSize(15);
                    name.setPadding(0, 0, (int) dp(10), 0);
                    TextView status = new TextView(MainActivity.this);
                    status.setTextSize(12);
                    row.addView(name, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(status);
                }
                JSONObject o = mSearchShown.get(position);
                TextView name = (TextView) row.getChildAt(0);
                TextView status = (TextView) row.getChildAt(1);
                name.setText(o.optString("n", "(未命名)"));
                name.setTextColor(Theme.textPrimary());
                status.setText(o.optString("s", ""));
                status.setTextColor(Theme.textSecondary());
                return row;
            }
        };
        mSearchList.setAdapter(mSearchAdapter);
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            JSONObject o = mSearchShown.get(position);
            dismissSearch();
            showTab(TAB_PANEL);
            PanelController.openTask(o.optString("tid", ""));
        });
        box.addView(mSearchList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView close = new TextView(this);
        close.setText("✕ 关闭搜索");
        close.setTextColor(Theme.textSecondary());
        close.setTextSize(13);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, (int) dp(10), 0, (int) dp(14));
        close.setClickable(true);
        close.setOnClickListener(v -> dismissSearch());
        box.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSearchOverlay = overlay;
        mContent.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mSearchInput.setText("");
        mSearchInput.requestFocus();
        PanelController.listTasks(json -> runOnUiThread(() -> {
            mSearchAll.clear();
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                for (int i = 0; i < a.length(); i++) mSearchAll.add(a.getJSONObject(i));
            } catch (Exception ignored) {
            }
            refilterSearch();
        }));
        mSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refilterSearch();
            }
        });
    }

    private void refilterSearch() {
        mSearchShown.clear();
        String q = mSearchInput == null ? "" : mSearchInput.getText().toString().trim().toLowerCase();
        for (JSONObject o : mSearchAll) {
            String hay = (o.optString("n", "") + " " + o.optString("s", "")).toLowerCase();
            if (q.isEmpty() || hay.contains(q)) mSearchShown.add(o);
        }
        if (mSearchAdapter != null) mSearchAdapter.notifyDataSetChanged();
    }

    private void dismissSearch() {
        if (mSearchOverlay != null) {
            mContent.removeView(mSearchOverlay);
            mSearchOverlay = null;
            mSearchInput = null;
        }
    }

    // ---- 应用锁 ----

    private View mLockOverlay;
    private boolean mReturnLock = false;
    private boolean mLocked = false;
    private android.os.CancellationSignal mBiometricSignal;

    private void lockNow() {
        if (mLocked) return;
        mLocked = true;
        if (mLockOverlay != null) mRoot.removeView(mLockOverlay);
        mLockOverlay = buildLockOverlay();
        mRoot.addView(mLockOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (AppLock.biometricEnabled(this)) {
            mLockOverlay.post(this::startBiometric);
        }
    }

    private void unlockNow() {
        mLocked = false;
        if (mLockOverlay != null) {
            mRoot.removeView(mLockOverlay);
            mLockOverlay = null;
        }
    }

    private boolean biometricReady() {
        try {
            if (Build.VERSION.SDK_INT < 29) return false;
            android.hardware.biometrics.BiometricManager bm =
                    getSystemService(android.hardware.biometrics.BiometricManager.class);
            return bm != null
                    && bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false; // 指纹相关的任何异常（权限/厂商实现）都不允许带崩应用
        }
    }

    private void startBiometric() {
        try {
            if (Build.VERSION.SDK_INT < 29 || mBiometricSignal != null) return;
            android.hardware.biometrics.BiometricManager bm =
                    getSystemService(android.hardware.biometrics.BiometricManager.class);
            if (bm == null || bm.canAuthenticate()
                    != android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                return;
            }
            mBiometricSignal = new android.os.CancellationSignal();
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(this)
                            .setTitle("解锁 ZCode RC")
                            .setSubtitle("验证指纹以继续，也可使用下方 PIN")
                            .setNegativeButton("使用 PIN", getMainExecutor(),
                                    (dialog, which) -> mBiometricSignal = null)
                            .build();
            prompt.authenticate(mBiometricSignal, getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            mBiometricSignal = null;
                            runOnUiThread(MainActivity.this::unlockNow);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence errString) {
                            mBiometricSignal = null;
                        }
                    });
        } catch (Exception e) {
            // 指纹不可用时静默退回 PIN 解锁
            mBiometricSignal = null;
        }
    }

    private View buildLockOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Theme.bg());
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        boxLp.setMargins((int) dp(32), 0, (int) dp(32), 0);
        overlay.addView(box, boxLp);

        TextView title = new TextView(this);
        title.setText("🔒 ZCode RC 已锁定");
        title.setTextColor(Theme.textPrimary());
        title.setTextSize(18);
        TextView sub = new TextView(this);
        sub.setText("输入 PIN 解锁（会话凭证受保护）");
        sub.setTextColor(Theme.textSecondary());
        sub.setTextSize(13);
        sub.setPadding(0, (int) dp(6), 0, 0);
        android.widget.EditText pin = new android.widget.EditText(this);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        pin.setTextSize(16);
        pin.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                (int) dp(220), ViewGroup.LayoutParams.WRAP_CONTENT);
        pinLp.topMargin = (int) dp(20);
        TextView btn = new TextView(this);
        btn.setText("解 锁");
        btn.setTextColor(Theme.accent());
        btn.setTextSize(16);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int) dp(40), (int) dp(10), (int) dp(40), (int) dp(10));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) dp(16);
        btn.setOnClickListener(v -> {
            if (AppLock.verify(this, pin.getText().toString())) {
                unlockNow();
            } else {
                android.widget.Toast.makeText(this, "PIN 不正确", android.widget.Toast.LENGTH_SHORT).show();
                pin.setText("");
            }
        });
        box.addView(title);
        box.addView(sub);
        box.addView(pin, pinLp);
        box.addView(btn, btnLp);
        if (AppLock.biometricEnabled(this) && biometricReady()) {
            TextView fp = new TextView(this);
            fp.setText("🔓 使用指纹解锁");
            fp.setTextColor(Theme.textSecondary());
            fp.setTextSize(13);
            fp.setPadding(0, (int) dp(18), 0, 0);
            fp.setClickable(true);
            fp.setOnClickListener(v -> startBiometric());
            box.addView(fp);
        }
        return overlay;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v2 直推：前台保持流式订阅（已开启且配置了 Topic 才真正生效）
        NtfyReceiver.subscribe(this);
        if (mReturnLock && !mLocked && AppLock.enabled(this) && AppLock.hasPin(this)) {
            lockNow();
        }
        mReturnLock = false;
    }

    @Override
    protected void onStop() {
        // 真正离开前台才武装重锁；系统弹窗（权限等）的短暂 pause 不触发
        mReturnLock = true;
        NtfyReceiver.unsubscribe();
        super.onStop();
    }

    // ---- Tab ----

    private ListView buildListView() {
        ListView lv = new ListView(this);
        lv.setBackgroundColor(Theme.bg());
        lv.setDivider(new android.graphics.drawable.ColorDrawable(Theme.divider()));
        lv.setDividerHeight(1);
        lv.setAdapter(mAdapter);
        lv.setOnItemClickListener((parent, view, position, id) -> {
            int pos = position - 1; // 列表头占一位
            if (pos >= 0 && pos < mAdapter.getCount()) {
                JSONObject ev = mAdapter.getItem(pos);
                PanelController.openTask(ev.optString("id", ""));
            }
            showTab(TAB_PANEL);
        });
        mMsgHeader = new TextView(this);
        mMsgHeader.setText("点任意条目回到面板");
        mMsgHeader.setTextColor(Theme.textSecondary());
        mMsgHeader.setTextSize(13);
        mMsgHeader.setPadding((int) dp(16), (int) dp(12), (int) dp(16), (int) dp(12));
        lv.addHeaderView(mMsgHeader, null, false);
        lv.setVisibility(View.GONE);
        return lv;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Theme.surface());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) dp(20);
        bar.setPadding(0, (int) dp(8), 0, pad);

        mTabButtons = new TextView[3];
        mTabButtons[TAB_PANEL] = tabButton("面板");
        mTabButtons[TAB_MESSAGES] = tabButton("消息");
        mTabButtons[TAB_SETTINGS] = tabButton("设置");
        mTabButtons[TAB_PANEL].setOnClickListener(v -> showTab(TAB_PANEL));
        mTabButtons[TAB_MESSAGES].setOnClickListener(v -> showTab(TAB_MESSAGES));
        mTabButtons[TAB_SETTINGS].setOnClickListener(v -> showTab(TAB_SETTINGS));
        bar.addView(mTabButtons[TAB_PANEL], weightLp());
        bar.addView(mTabButtons[TAB_MESSAGES], weightLp());
        bar.addView(mTabButtons[TAB_SETTINGS], weightLp());
        return bar;
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView tabButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int) dp(6), 0, (int) dp(6));
        tv.setClickable(true);
        return tv;
    }

    private void showTab(int tab) {
        mTab = tab;
        mPanelView.setVisibility(tab == TAB_PANEL ? View.VISIBLE : View.GONE);
        mListView.setVisibility(tab == TAB_MESSAGES ? View.VISIBLE : View.GONE);
        mSettingsView.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (tab == TAB_MESSAGES) mAdapter.reload();
        if (tab == TAB_SETTINGS) refreshHeartbeat();
        for (int i = 0; i < mTabButtons.length; i++) {
            mTabButtons[i].setTextColor(i == tab ? Theme.textPrimary() : Theme.textSecondary());
        }
        updateMessageCount();
    }

    private void updateMessageCount() {
        long n = NotifyEngine.historyDesc().size();
        mTabButtons[TAB_MESSAGES].setText(n > 0 ? "消息 (" + n + ")" : "消息");
    }

    @Override
    public void onHistoryChanged() {
        runOnUiThread(() -> {
            updateMessageCount();
            if (mTab == TAB_MESSAGES) mAdapter.reload();
        });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ---- Intent 与通知权限 ----

    /** 扫码/点链接进来（ACTION_VIEW）或浏览器分享进来（ACTION_SEND）→ 载入该地址；否则首次启动恢复会话。 */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            PanelController.openOrRoute(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            String url = firstUrlIn(intent.getStringExtra(Intent.EXTRA_TEXT));
            if (url != null) {
                PanelController.openOrRoute(Uri.parse(url));
            }
        } else if (PanelController.isFirstLoad()) {
            PanelController.load(PanelController.resolveHomeUrl());
        }
    }

    private static String firstUrlIn(String text) {
        if (text == null) return null;
        for (String tok : text.split("\\s+")) {
            if (tok.startsWith("http://") || tok.startsWith("https://")) return tok;
        }
        return null;
    }

    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        SharedPreferences p = getSharedPreferences("zremote", MODE_PRIVATE);
        if (p.getBoolean("notif_asked", false)) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            p.edit().putBoolean("notif_asked", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERM);
        }
    }

    // ---- 扫码配对 ----

    private void startScanPairing() {
        if (checkSelfPermission("android.permission.CAMERA")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchScan();
        } else {
            requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAM_PERM);
        }
    }

    private void launchScan() {
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法启动扫码", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showRePairDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("重新配对")
                .setMessage("桌面端 ZCode 打开「手机远程控制」显示二维码后，点「扫码配对」对准扫一下即可；\n\n剪贴板里已有链接可选「粘贴链接」；也可在手机浏览器打开链接后点「分享」选本应用导入。")
                .setPositiveButton("扫码配对", (d, w) -> startScanPairing())
                .setNeutralButton("粘贴链接", (d, w) -> PanelController.showImportDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAM_PERM) {
            if (grantResults.length > 0 && grantResults[0]
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchScan();
            } else {
                android.widget.Toast.makeText(this, "没有相机权限，无法扫码；可用「粘贴链接」代替",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (mSearchOverlay != null) {
            dismissSearch();
        } else if (mTab == TAB_MESSAGES) {
            showTab(TAB_PANEL);
        } else if (PanelController.canGoBack()) {
            PanelController.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showTab(TAB_PANEL);
        handleIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PanelController.REQ_FILE_CHOOSER) {
            // WebView 自带特权读取回调里的 content://，无需 androidx 的文件工具
            Uri[] result = (data != null)
                    ? WebChromeClient.FileChooserParams.parseResult(resultCode, data) : null;
            PanelController.deliverFileChooserResult(result);
            return;
        }
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String url = data.getStringExtra(ScanActivity.EXTRA_URL);
                if (url != null && !url.isEmpty()) {
                    showTab(TAB_PANEL);
                    PanelController.openOrRoute(Uri.parse(url));
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        NotifyEngine.setHistoryListener(null);
        Theme.setListener(null);
        // WebView 是全进程单例：只从视图树摘下，不销毁，下次启动复用同一会话
        PanelController.detach();
        super.onDestroy();
    }

    // ---- 设置页 ----

    private View buildSettingsView() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(Theme.bg());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(16);
        box.setPadding(p, (int) dp(12), p, (int) dp(16));

        box.addView(sectionLabel("通知"));
        box.addView(switchRow("桌面直推",
                "电脑端 watcher 实时推送任务状态（v2，取代旧版\"实时模式\"保活，更省电可靠）\n"
                        + "需在电脑部署 watcher 并填好 Topic，见 README「桌面直推」一节",
                AppSettings.ntfyOn(this), (b, on) -> {
                    AppSettings.setNtfyOn(this, on);
                    if (on) {
                        NtfyReceiver.subscribe(this);
                    } else {
                        NtfyReceiver.unsubscribe();
                    }
                    swapSettingsView(buildSettingsView());
                }));
        if (AppSettings.ntfyOn(this)) {
            box.addView(buttonRow("服务器：" + AppSettings.ntfyServer(this), v -> editNtfyServer()));
            box.addView(buttonRow("Topic：" + AppSettings.ntfyTopicMasked(this)
                    + "（随机长串=凭据，勿外传）", v -> editNtfyTopic()));
            box.addView(buttonRow("测试拉取（立即收一次桌面推送）", v -> testNtfyPull()));
            mNtfyStatus = new TextView(this);
            mNtfyStatus.setTextColor(0xFF8A97A0);
            mNtfyStatus.setTextSize(12);
            mNtfyStatus.setPadding(0, (int) dp(4), 0, 0);
            mNtfyStatus.setText("上次拉取：" + AppSettings.ntfyLastText(this));
            box.addView(mNtfyStatus);
        }
        String range = "每天 " + AppSettings.fmtTime(AppSettings.quietStart(this)) + " – "
                + AppSettings.fmtTime(AppSettings.quietEnd(this)) + " 只记历史不弹通知 · 点这里改时段";
        LinearLayout quietRow = switchRow("静默时段", range,
                AppSettings.quietOn(this), (b, on) -> AppSettings.setQuietOn(this, on));
        quietRow.setOnClickListener(v -> editQuietTimes());
        box.addView(quietRow);
        box.addView(switchRow("应用锁",
                "冷启动与返回前台时要求 PIN（界面级保护，不加密数据）"
                        + " · ⚠️ 忘记 PIN 只能清除应用数据重新配对，历史与登录态全部丢失",
                AppLock.enabled(this), (b, on) -> {
                    if (on) {
                        promptSetPin();
                    } else {
                        promptDisableLock();
                    }
                    swapSettingsView(buildSettingsView());
                }));
        if (AppLock.enabled(this) && AppLock.hasPin(this)) {
            boolean ready = biometricReady();
            box.addView(switchRow("指纹解锁",
                    ready ? "指纹优先，PIN 作后备（可随时关闭）" : "设备不支持或未录入指纹",
                    ready && AppLock.biometricEnabled(this),
                    (b, on) -> {
                        AppLock.setBiometricEnabled(this, on);
                        if (mLocked) lockNow();
                    }));
        }

        box.addView(sectionLabel("兜底检查"));
        int cur = AppSettings.intervalMin(this);
        int sel = cur <= 15 ? 0 : cur <= 30 ? 1 : cur <= 60 ? 2 : 3;
        box.addView(spinnerRow("检查间隔", new String[]{"每 15 分钟", "每 30 分钟", "每 60 分钟", "每 2 小时"}, sel,
                pos -> AppSettings.setIntervalMin(this, new int[]{15, 30, 60, 120}[pos])));
        mHeartbeat = new TextView(this);
        mHeartbeat.setTextColor(0xFF8A97A0);
        mHeartbeat.setTextSize(12);
        mHeartbeat.setPadding(0, (int) dp(8), 0, 0);
        box.addView(mHeartbeat);

        box.addView(sectionLabel("系统权限"));
        box.addView(buttonRow("后台权限引导（MIUI / HyperOS）", v -> showKeepAliveGuide()));

        box.addView(sectionLabel("会话"));
        box.addView(buttonRow("重新配对（扫码 / 粘贴链接）", v -> showRePairDialog()));

        box.addView(sectionLabel("数据"));
        box.addView(buttonRow("清空消息历史", v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("清空消息历史")
                    .setMessage("确定清空全部历史记录？通知不受影响。")
                    .setPositiveButton("清空", (d, w) -> {
                        NotifyEngine.clearHistory();
                        mAdapter.reload();
                        updateMessageCount();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }));
        box.addView(buttonRow("查看崩溃日志", v -> showCrashLog()));
        box.addView(buttonRow("用量信息（智谱官网）", v ->
                startActivity(new Intent(this, UsageActivity.class))));

        box.addView(sectionLabel("关于"));
        box.addView(buttonRow("隐私政策", v -> showPrivacyPolicy()));
        box.addView(buttonRow("GitHub 主页 · @Rainling-LingYusi014（支持/反馈）", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Rainling-LingYusi014")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "无可用浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
        TextView about = new TextView(this);
        about.setText("ZRemote v" + versionName() + "\n"
                + "ZCode 远程控制增强壳 · 非官方项目，与 Z.ai 无隶属关系\n"
                + "本地版图标版权归 Z.ai 所有，仅限本地个人使用");
        about.setTextColor(Theme.textSecondary());
        about.setTextSize(12);
        about.setPadding(0, (int) dp(4), 0, 0);
        box.addView(about);
        box.addView(switchRow("WebView 调试通道", "排障/探针用，日常保持关闭",
                getSharedPreferences("zremote", MODE_PRIVATE).getBoolean("webview_debug", false),
                (b, on) -> {
                    getSharedPreferences("zremote", MODE_PRIVATE)
                            .edit().putBoolean("webview_debug", on).apply();
                    android.webkit.WebView.setWebContentsDebuggingEnabled(on);
                }));

        sv.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void promptSetPin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(20);
        box.setPadding(p, (int) dp(8), p, 0);
        android.widget.EditText p1 = new android.widget.EditText(this);
        p1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        p1.setHint("PIN（至少 4 位数字）");
        android.widget.EditText p2 = new android.widget.EditText(this);
        p2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        p2.setHint("再输一遍");
        TextView warn = new TextView(this);
        warn.setText("⚠️ 忘记 PIN 将无法进入应用，只能清除全部数据（会话配对、历史、设置一并丢失）。请务必牢记。");
        warn.setTextColor(0xFFE0A030);
        warn.setTextSize(12);
        warn.setPadding(0, (int) dp(8), 0, 0);
        box.addView(p1);
        box.addView(p2);
        box.addView(warn);
        new android.app.AlertDialog.Builder(this)
                .setTitle("设置应用锁 PIN")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String a = p1.getText().toString(), b2 = p2.getText().toString();
                    if (a.length() < 4) {
                        android.widget.Toast.makeText(this, "PIN 至少 4 位，未开启",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!a.equals(b2)) {
                        android.widget.Toast.makeText(this, "两次输入不一致，未开启",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppLock.setPin(this, a);
                    AppLock.setEnabled(this, true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptDisableLock() {
        android.widget.EditText p = new android.widget.EditText(this);
        p.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        p.setHint("输入当前 PIN");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) dp(20), (int) dp(8), (int) dp(20), 0);
        box.addView(p);
        new android.app.AlertDialog.Builder(this)
                .setTitle("关闭应用锁")
                .setView(box)
                .setPositiveButton("关闭", (d, w) -> {
                    if (AppLock.verify(this, p.getText().toString())) {
                        AppLock.clear(this);
                    } else {
                        android.widget.Toast.makeText(this, "PIN 不正确，已保留应用锁",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPrivacyPolicy() {
        String policy = "一、本 APP 不收集任何数据\n"
                + "无统计 SDK、无广告、无自建服务器，不上传任何信息到开发者或第三方。\n\n"
                + "二、数据全部存储在本机\n"
                + "· 会话 Cookie：仅用于连接你自己的桌面端\n"
                + "· 任务快照与通知历史：仅存 APP 私有目录，可随时清空\n"
                + "· 崩溃日志：仅存本机，仅你主动查看\n"
                + "· 用量页登录态：bigmodel.cn 的 Cookie，仅本机保存\n\n"
                + "三、网络通信对象仅限\n"
                + "· zcode.z.ai（官方远程控制）\n"
                + "· bigmodel.cn（智谱官方用量页）\n"
                + "· 你配置的 ntfy 服务器（默认公共 ntfy.sh）：仅当开启\"桌面直推\"时，"
                + "任务标题（默认为会话首条输入的截断文本）与状态变化经其转发，"
                + "不含代码/文件/凭据；介意者可自建 ntfy 或关闭该功能\n"
                + "页面内数据的处理遵循智谱官方隐私条款，本 APP 不经手、不转发、不存储。\n\n"
                + "四、权限用途\n"
                + "· 网络：连接上述服务\n"
                + "· 通知：任务状态提醒\n"
                + "· 相机：仅在扫码配对时本地识别二维码，画面不存储、不上传\n\n"
                + "五、品牌与开源说明\n"
                + "非官方第三方工具，与 Z.ai 无隶属关系；代码开源时仓库不含官方品牌资产与账号凭据。";
        TextView tv = new TextView(this);
        tv.setText(policy);
        tv.setTextColor(Theme.textPrimary());
        tv.setTextSize(14);
        tv.setPadding((int) dp(20), (int) dp(16), (int) dp(20), (int) dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle("隐私政策")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showCrashLog() {
        java.io.File f = CrashLogger.latest(this);
        String msg = f == null ? "暂无崩溃记录，运行平稳。"
                : CrashLogger.read(f);
        if (msg.length() > 1200) msg = msg.substring(0, 1200) + "\n…（已截断）";
        final String shown = msg;
        new android.app.AlertDialog.Builder(this)
                .setTitle("崩溃日志（最新一条）")
                .setMessage(shown)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", shown));
                })
                .setNeutralButton("关闭", null)
                .setNegativeButton("清除记录", (d, w) -> CrashLogger.clear(this))
                .show();
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Theme.textSecondary());
        tv.setTextSize(12);
        tv.setPadding(0, (int) dp(18), 0, (int) dp(6));
        return tv;
    }

    private LinearLayout switchRow(String title, String sub, boolean checked,
                                   android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(8), 0, (int) dp(8));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Theme.textPrimary());
        t.setTextSize(15);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(Theme.textSecondary());
        s.setTextSize(12);
        s.setPadding(0, (int) dp(2), 0, 0);
        labels.addView(t);
        labels.addView(s);
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(labels, lp);
        row.addView(sw);
        return row;
    }

    private LinearLayout spinnerRow(String title, String[] options, int sel,
                                    java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(8), 0, (int) dp(8));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Theme.textPrimary());
        t.setTextSize(15);
        android.widget.Spinner sp = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Theme.textPrimary());
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(Theme.textPrimary());
                return v;
            }
        };
        sp.setAdapter(ad);
        sp.setSelection(sel);
        boolean[] first = {true};
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (first[0]) {
                    first[0] = false;
                    return;
                }
                onPick.accept(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(t, lp);
        row.addView(sp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView buttonRow(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Theme.accent());
        tv.setTextSize(15);
        tv.setPadding(0, (int) dp(10), 0, (int) dp(10));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private void refreshHeartbeat() {
        mHeartbeat.setText("上次兜底：" + AppSettings.lastCheckText(this) + "\n观察器：检查中…");
        android.webkit.WebView wv = PanelController.webview();
        if (wv == null) {
            mHeartbeat.append("\n观察器：WebView 未创建（APP 尚未加载过会话）");
            return;
        }
        wv.evaluateJavascript(
                "(function(){return JSON.stringify({i:!!window.__zrcInstalled,"
                        + "c:document.querySelectorAll('[data-testid^=\"task-item-\"]').length});})()",
                v -> {
                    boolean installed = v != null && v.contains("\"i\":true");
                    String cards = "?";
                    if (v != null && v.matches("(?s).*\"c\":\\d+.*")) {
                        cards = v.replaceAll("(?s).*\"c\":(\\d+).*", "$1");
                    }
                    String state = installed
                            ? "存活（" + cards + " 张任务卡）"
                            : "未注入（页面未加载或已改版）";
                    runOnUiThread(() -> mHeartbeat.append("\n观察器：" + state));
                });
    }

    // ---- 桌面直推（v2）设置 ----

    private void editNtfyServer() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(AppSettings.ntfyServer(this));
        et.setSingleLine(true);
        wrapDialog(et, "直推服务器", "ntfy 服务器地址。公共服 https://ntfy.sh；自建填自己的地址。")
                .setPositiveButton("保存", (d, w) -> {
                    AppSettings.setNtfyServer(this, et.getText().toString());
                    swapSettingsView(buildSettingsView());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editNtfyTopic() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(AppSettings.ntfyTopic(this));
        et.setSingleLine(true);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemAt(0) != null) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && !clip.toString().isEmpty() && clip.toString().startsWith("zrc-")) {
                    et.setText(clip); // 剪贴板里有新生成的 topic，直接带出
                }
            }
        } catch (Exception ignored) {
        }
        wrapDialog(et, "直推 Topic", "与桌面端 watcher 配置里的 topic 完全一致（随机长串，即凭据，勿外传）。")
                .setPositiveButton("保存", (d, w) -> {
                    AppSettings.setNtfyTopic(this, et.getText().toString());
                    swapSettingsView(buildSettingsView());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void testNtfyPull() {
        android.widget.Toast.makeText(this, "拉取中…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                int n = NtfyReceiver.pollOnce(this);
                msg = n > 0 ? "拉取成功，收到 " + n + " 条新事件"
                        : "拉取成功，暂无新事件（先在电脑端 curl /zrc/test 发一条试试）";
            } catch (Exception e) {
                msg = "拉取失败：" + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
                swapSettingsView(buildSettingsView());
            });
        }, "zrc-ntfy-test").start();
    }

    private android.app.AlertDialog.Builder wrapDialog(
            android.widget.EditText et, String title, String msg) {
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) dp(20);
        wrap.setPadding(pad, (int) dp(6), pad, 0);
        wrap.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg).setView(wrap);
    }

    /** 静默时段编辑：先选开始，再选结束（支持跨零点）。 */
    private void editQuietTimes() {
        android.app.TimePickerDialog start = new android.app.TimePickerDialog(this,
                (dp, h, m) -> {
                    int s = h * 60 + m;
                    android.app.TimePickerDialog end = new android.app.TimePickerDialog(this,
                            (dp2, h2, m2) -> {
                                AppSettings.setQuietTimes(this, s, h2 * 60 + m2);
                                swapSettingsView(buildSettingsView());
                            },
                            AppSettings.quietEnd(this) / 60, AppSettings.quietEnd(this) % 60, true);
                    end.setTitle("静默结束时间");
                    end.show();
                },
                AppSettings.quietStart(this) / 60, AppSettings.quietStart(this) % 60, true);
        start.setTitle("静默开始时间");
        start.show();
    }

    // ---- 主题跟随 ----

    @Override
    public void onThemeChanged(boolean dark) {
        runOnUiThread(this::applyTheme);
    }

    private void applyTheme() {
        getWindow().setStatusBarColor(Theme.statusBar());
        getWindow().setNavigationBarColor(Theme.statusBar());
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.setSystemBarsAppearance(
                        Theme.dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }
        mListView.setBackgroundColor(Theme.bg());
        mListView.setDivider(new android.graphics.drawable.ColorDrawable(Theme.divider()));
        mMsgHeader.setTextColor(Theme.textSecondary());
        mAdapter.notifyDataSetChanged();
        if (mSearchOverlay != null) {
            mContent.removeView(mSearchOverlay);
            mSearchOverlay = null;
            mSearchInput = null;
        }
        mBottomBar.setBackgroundColor(Theme.surface());
        swapSettingsView(buildSettingsView());
        showTab(mTab);
    }

    private void swapSettingsView(View newView) {
        int idx = mContent.indexOfChild(mSettingsView);
        mContent.removeView(mSettingsView);
        mContent.addView(newView, idx);
        mSettingsView = newView;
        mSettingsView.setVisibility(mTab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
    }

    private void showKeepAliveGuide() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("后台权限引导")
                .setMessage("HyperOS 会 aggressively 冻结后台，按下面三步设置后兜底通知才可靠：\n\n"
                        + "① 自启动：设置 → 应用设置 → 应用管理 → Zcode RC → 自启动，开启\n\n"
                        + "② 省电策略：同一页面 → 省电策略 → 无限制\n\n"
                        + "③ 通知权限：确认\"允许通知\"已开（含锁屏）\n\n"
                        + "需要的话我现在就带你去应用信息页。")
                .setPositiveButton("打开应用信息", (d, w) -> {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null));
                        startActivity(i);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "打开失败，请手动前往设置", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("复制设置路径", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("guide",
                            "设置→应用设置→应用管理→Zcode RC：自启动=开，省电策略=无限制，允许通知=开"));
                    android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    // ---- 消息历史列表 ----

    private class HistoryAdapter extends BaseAdapter {
        private ArrayList<JSONObject> items = new ArrayList<>();

        void reload() {
            items = NotifyEngine.historyDesc();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public JSONObject getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (convertView instanceof LinearLayout)
                    ? (LinearLayout) convertView : buildRow();
            JSONObject ev = getItem(position);
            String to = ev.optString("to", "");
            String icon = to.contains("错误") || to.contains("失败") ? "⚠️"
                    : to.contains("已完成") ? "✅" : "🆕";
            String title = icon + "  " + ev.optString("name", "任务");
            String from = ev.optString("from", "");
            String sub = (from.isEmpty() ? "新任务" : from + " → " + to)
                    + " · " + DateUtils.getRelativeTimeSpanString(
                            ev.optLong("ts", 0), System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS).toString();
            TextView t = (TextView) row.getChildAt(0);
            TextView s = (TextView) row.getChildAt(1);
            t.setText(title);
            t.setTextColor(Theme.textPrimary());
            s.setText(sub);
            s.setTextColor(Theme.textSecondary());
            return row;
        }

        private LinearLayout buildRow() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            int p = (int) dp(16);
            row.setPadding(p, (int) dp(10), p, (int) dp(10));
            TextView title = new TextView(MainActivity.this);
            title.setTextSize(15);
            TextView sub = new TextView(MainActivity.this);
            sub.setTextSize(12);
            sub.setPadding(0, (int) dp(3), 0, 0);
            row.addView(title);
            row.addView(sub);
            return row;
        }
    }
}
