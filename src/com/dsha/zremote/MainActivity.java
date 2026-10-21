package com.dsha.zremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
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

    // 消息页停留时周期重绑，相对时间才能跟着走（现在 → N分钟前 → …）
    private final android.os.Handler mMsgTick = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mMsgTickR = new Runnable() {
        @Override
        public void run() {
            if (mTab == TAB_MESSAGES && mAdapter != null) {
                mAdapter.notifyDataSetChanged();
                mMsgTick.postDelayed(this, 30_000L);
            }
        }
    };

    // 消息列表左滑删除手势状态（单手势单行，DOWN 定目标，MOVE 接管，UP 结算）
    private int mTouchSlop;
    private final int[] mSwipeDown = new int[2];
    private int mSwipeFlat = Integer.MIN_VALUE;
    private boolean mSwiping;
    private View mSwipeContent;
    private JSONObject mSwipeEvent;

    // 删除/清空反馈条：5 秒内可撤销（防手滑），替代一次性 Toast；单条与清空共用。
    // 节奏：前 4 秒维持 80% 不透明度，最后 1 秒渐隐至 0 后隐藏。
    private LinearLayout mUndoBar;
    private TextView mUndoMsg;
    private ArrayList<JSONObject> mUndoItems;
    private TextView mEmptyHint; // 消息页空态居中小字
    private final Runnable mUndoFadeR = new Runnable() {
        @Override
        public void run() {
            if (mUndoBar == null) return;
            mUndoBar.animate().alpha(0f).setDuration(1000L)
                    .withEndAction(() -> {
                        mUndoBar.setVisibility(View.GONE);
                        mUndoItems = null;
                    }).start();
        }
    };

    /** 立即收起反馈条（撤销点击/重建清理用）：停掉待触发的渐隐与进行中的动画。 */
    private void undoDismiss() {
        mContent.removeCallbacks(mUndoFadeR);
        if (mUndoBar != null) {
            mUndoBar.animate().cancel();
            mUndoBar.setVisibility(View.GONE);
        }
        mUndoItems = null;
    }

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
        // 消息页空态提示：居中小字，仅消息 Tab 且历史为空时可见
        mEmptyHint = new TextView(this);
        mEmptyHint.setText("暂无消息");
        mEmptyHint.setTextColor(Theme.textSecondary());
        mEmptyHint.setTextSize(14);
        mEmptyHint.setGravity(Gravity.CENTER);
        mEmptyHint.setVisibility(View.GONE);
        mContent.addView(mEmptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildUndoBar();

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
    /** 进程级锁需求标记：用量页（栈顶时）据此自查，堵住"Home 后重进停在用量页绕过 PIN"的旁路。 */
    static volatile boolean sLockRequired = false;
    private android.os.CancellationSignal mBiometricSignal;

    private void lockNow() {
        if (mLocked) return;
        mLocked = true;
        sLockRequired = true;
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
        sLockRequired = false;
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
                            .setTitle("解锁 " + appName())
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
        title.setText("🔒 " + appName() + " 已锁定");
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
        if (AppLock.enabled(this) && AppLock.hasPin(this)) sLockRequired = true;
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
            int pos = position - lv.getHeaderViewsCount(); // 列表头占位
            JSONObject ev = pos >= 0 ? mAdapter.eventAt(pos) : null;
            if (ev != null) PanelController.openTask(ev.optString("id", ""));
            showTab(TAB_PANEL);
        });
        mTouchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        // 左滑删除：DOWN 只记录不消费（不干扰列表滚动/点击），MOVE 横向位移超阈值才接管手势，
        // 接管瞬间给 ListView 发 CANCEL 防止同时滚动；UP 按滑动距离决定删除还是弹回。
        lv.setOnTouchListener((v, e) -> onListTouch(lv, e));

        // 消息页头部：提示语 + 一键清空（清空是不可恢复操作，按钮做成红色低饱和提示）
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) dp(16), (int) dp(12), (int) dp(8), (int) dp(12));
        mMsgHeader = new TextView(this);
        mMsgHeader.setText("点任意条目回到面板 · 条目左滑可删除");
        mMsgHeader.setTextColor(Theme.textSecondary());
        mMsgHeader.setTextSize(13);
        header.addView(mMsgHeader, weightLp());
        TextView clearAll = new TextView(this);
        clearAll.setText("清空所有消息");
        clearAll.setTextColor(0xFFE57373);
        clearAll.setTextSize(13);
        int hp = (int) dp(8);
        clearAll.setPadding(hp, hp, hp, hp);
        clearAll.setOnClickListener(v -> {
            ArrayList<JSONObject> cleared = NotifyEngine.historyDesc(); // 清空前留底，供撤销
            if (cleared.isEmpty()) {
                tipShow("没有消息可清空");
                return;
            }
            NotifyEngine.clearHistory();
            mAdapter.reload();
            updateMessageCount();
            showUndo(cleared, "已清空全部消息（" + cleared.size() + " 条）");
        });
        header.addView(clearAll);
        lv.addHeaderView(header, null, false);
        lv.setVisibility(View.GONE);
        return lv;
    }

    /** 消息列表左滑手势。返回 true 表示本次事件已被手势消费（不交给 ListView）。 */
    private boolean onListTouch(ListView lv, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwiping = false;
                mSwipeDown[0] = (int) e.getX();
                mSwipeDown[1] = (int) e.getY();
                mSwipeFlat = lv.pointToPosition(mSwipeDown[0], mSwipeDown[1]);
                mSwipeContent = null;
                mSwipeEvent = null;
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (mSwiping) {
                    float dx = e.getX() - mSwipeDown[0];
                    mSwipeContent.setTranslationX(Math.max(-dp(140), Math.min(0, dx)));
                    return true;
                }
                float dx = e.getX() - mSwipeDown[0];
                float dy = e.getY() - mSwipeDown[1];
                // 横向明显占优才接管，纵向让位给列表滚动
                if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f
                        && resolveSwipeTarget(lv)) {
                    MotionEvent c = MotionEvent.obtain(e.getDownTime(), e.getEventTime(),
                            MotionEvent.ACTION_CANCEL, e.getX(), e.getY(), e.getMetaState());
                    lv.onTouchEvent(c);
                    c.recycle();
                    mSwiping = true;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
                if (!mSwiping) return false;
                settleSwipe(mSwipeContent.getTranslationX() < -dp(96));
                mSwiping = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!mSwiping) return false;
                settleSwipe(false);
                mSwiping = false;
                return true;
            default:
                return false;
        }
    }

    /** DOWN 时定下的列表位置 → 命中一条可删事件与它的行内容视图；分组头/头部/无效位返回 false。 */
    private boolean resolveSwipeTarget(ListView lv) {
        if (mSwipeFlat < lv.getHeaderViewsCount()) return false;
        int pos = mSwipeFlat - lv.getHeaderViewsCount();
        if (pos >= mAdapter.getCount()) return false;
        Object it = mAdapter.getItem(pos);
        if (!(it instanceof JSONObject)) return false; // 日期分组头不可滑
        View row = lv.getChildAt(mSwipeFlat - lv.getFirstVisiblePosition());
        if (row == null || row.getTag() == null) return false;
        mSwipeEvent = (JSONObject) it;
        mSwipeContent = (View) row.getTag();
        return true;
    }

    private void settleSwipe(boolean delete) {
        final View content = mSwipeContent;
        final JSONObject ev = mSwipeEvent;
        if (content == null) return;
        content.animate().translationX(delete ? -content.getWidth() : 0).setDuration(180)
                .withEndAction(() -> {
                    if (delete && ev != null) {
                        NotifyEngine.removeByTs(ev.optLong("ts", 0));
                        updateMessageCount(); // 监听器会重载列表
                        ArrayList<JSONObject> one = new ArrayList<>();
                        one.add(ev);
                        showUndo(one, "已删除该条消息");
                    } else if (!delete) {
                        content.setTranslationX(0); // 弹回（若视图已被回收，getView 会复位，此处无害）
                    }
                }).start();
    }

    /** 底部删除/清空反馈条：前 4 秒 80% 透明度驻留，最后 1 秒渐隐，期间可「撤销」按原时间位置整批放回。 */
    private void showUndo(ArrayList<JSONObject> items, String text) {
        mUndoItems = items;
        mUndoMsg.setText(text);
        mContent.removeCallbacks(mUndoFadeR);
        mUndoBar.animate().cancel();
        mUndoBar.setAlpha(0.8f);
        mUndoBar.setVisibility(View.VISIBLE);
        mContent.postDelayed(mUndoFadeR, 4000L);
    }

    // 轻提示 pill：样式与节奏沿用退出拦截提示（深色圆角、1.5s 驻留 + 1s 渐隐）
    private TextView mTipPill;
    private final Runnable mTipFadeStart = new Runnable() {
        @Override
        public void run() {
            if (mTipPill == null) return;
            mTipPill.animate().alpha(0f).setDuration(1000)
                    .withEndAction(() -> {
                        if (mTipPill != null) {
                            mContent.removeView(mTipPill);
                            mTipPill = null;
                        }
                    }).start();
        }
    };

    private void tipShow(String text) {
        if (mTipPill != null) {
            mContent.removeCallbacks(mTipFadeStart);
            mTipPill.animate().cancel();
            mContent.removeView(mTipPill);
            mTipPill = null;
        }
        TextView tip = new TextView(this);
        tip.setText(text);
        tip.setTextColor(0xFFFFFFFF);
        tip.setTextSize(14);
        int ph = (int) dp(18), pv = (int) dp(10);
        tip.setPadding(ph, pv, ph, pv);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF0A0E12); // 同退出拦截提示的深色系，不随主题变
        bg.setCornerRadius(dp(12));
        tip.setBackground(bg);
        mTipPill = tip;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        int h = mContent.getHeight();
        lp.bottomMargin = h > 0 ? (int) (h * 0.25f) : (int) dp(160); // 屏幕中下部
        mContent.addView(tip, lp);
        mContent.postDelayed(mTipFadeStart, 1500); // 前 1.5s 全不透明
    }

    private void buildUndoBar() {
        mUndoBar = new LinearLayout(this);
        mUndoBar.setOrientation(LinearLayout.HORIZONTAL);
        mUndoBar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF232A32);
        bg.setCornerRadius(dp(10));
        mUndoBar.setBackground(bg);
        int p = (int) dp(14);
        mUndoBar.setPadding((int) dp(18), p, (int) dp(6), p);
        mUndoMsg = new TextView(this);
        mUndoMsg.setTextColor(0xFFFFFFFF);
        mUndoMsg.setTextSize(14);
        mUndoBar.addView(mUndoMsg, weightLp());
        TextView undo = new TextView(this);
        undo.setText("撤销");
        undo.setTextColor(0xFF7CB8FF);
        undo.setTextSize(14);
        undo.setTypeface(null, android.graphics.Typeface.BOLD);
        int q = (int) dp(10);
        undo.setPadding(q, q, q, q);
        undo.setOnClickListener(v -> {
            ArrayList<JSONObject> items = mUndoItems;
            undoDismiss();
            if (items != null) NotifyEngine.restoreItems(items);
        });
        mUndoBar.addView(undo);
        mUndoBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins((int) dp(16), 0, (int) dp(16), (int) dp(20));
        mContent.addView(mUndoBar, lp);
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
        mMsgTick.removeCallbacks(mMsgTickR);
        if (tab == TAB_MESSAGES) {
            mAdapter.reload();
            mMsgTick.postDelayed(mMsgTickR, 30_000L);
        }
        refreshEmptyHint();
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
            refreshEmptyHint();
        });
    }

    private void refreshEmptyHint() {
        boolean empty = NotifyEngine.historyDesc().isEmpty();
        mEmptyHint.setVisibility(mTab == TAB_MESSAGES && empty ? View.VISIBLE : View.GONE);
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
        } else if (mTab == TAB_MESSAGES || mTab == TAB_SETTINGS) {
            showTab(TAB_PANEL); // 设置页返回=回面板，不吃 WebView 后退（P3-13）
        } else if (android.os.SystemClock.uptimeMillis() - mExitArmAt < EXIT_ARM_MS) {
            exitAppNow();
        } else {
            // 面板返回：在具体会话视图里先回会话总览，总览才吃退出拦截。
            // 不用原生 canGoBack/WebView 后退：v4 是 SPA，任务卡点击不产生历史栈，
            // 而 OAuth 登录历史会让 canGoBack 误判并退回"正在连接中转服务"死页（7c0af41）。
            panelBackOrExit();
        }
    }

    /** 页面侧判定+导航：有聊天输入框=会话视图 → 点页面左上角自带返回钮，找不到就 history.back()；无输入框=总览/登录/错误页 → 原生走退出拦截。 */
    private static final String BACK_JS =
        "(function(){try{" +
        "var ta=document.querySelector('textarea,[contenteditable=\"true\"],[contenteditable=\"\"]');" +
        "if(!ta)return 'list';" +
        "var cs=document.querySelectorAll('button,[role=\"button\"],a');" +
        "for(var i=0;i<cs.length;i++){var b=cs[i];var r=b.getBoundingClientRect();" +
        "if(r.width<8||r.height<8||r.top>140||r.left>140)continue;" +
        "if(b.querySelector('svg')&&!b.textContent.trim()){b.click();return 'back';}}" +
        "history.back();return 'hist';" +
        "}catch(e){return 'err';}})()";

    private void panelBackOrExit() {
        android.webkit.WebView wv = PanelController.webview();
        if (wv == null) {
            exitTipShow();
            return;
        }
        final boolean[] done = {false};
        wv.evaluateJavascript(BACK_JS, v -> {
            done[0] = true;
            // back/hist=已交给页面导航；list/err=总览或异常 → 退出拦截
            if (v == null || !(v.contains("back") || v.contains("hist"))) exitTipShow();
        });
        // 页面无响应兜底：350ms 内没有判定结果就按退出拦截处理
        mContent.postDelayed(() -> {
            if (!done[0]) exitTipShow();
        }, 350);
    }

    // ---- 退出拦截：主界面按返回不直接退出，先弹小提示；5 秒窗口内再按才真正退出 ----

    private TextView mExitTip;
    private long mExitArmAt;
    private static final long EXIT_ARM_MS = 5000;

    private void exitTipShow() {
        mExitArmAt = android.os.SystemClock.uptimeMillis();
        if (mExitTip != null) { // 防御：窗口(5s)比提示寿命(2.5s)长，理论上不会走到
            mContent.removeCallbacks(mExitFadeStart);
            mExitTip.animate().cancel();
            mExitTip.setAlpha(1f);
            return;
        }
        TextView tip = new TextView(this);
        tip.setText("再次操作退出应用");
        tip.setTextColor(0xFFFFFFFF);
        tip.setTextSize(14);
        int ph = (int) dp(18), pv = (int) dp(10);
        tip.setPadding(ph, pv, ph, pv);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF0A0E12); // 比 ZCode 深色底 #101418 略深一档的同色系灰，非纯黑；不随主题变
        bg.setCornerRadius(dp(12));
        tip.setBackground(bg);
        mExitTip = tip;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        int h = mContent.getHeight();
        lp.bottomMargin = h > 0 ? (int) (h * 0.25f) : (int) dp(160); // 屏幕中下部
        mContent.addView(tip, lp);
        mContent.postDelayed(mExitFadeStart, 1500); // 前 1.5s 全不透明
    }

    private final Runnable mExitFadeStart = new Runnable() {
        @Override
        public void run() {
            if (mExitTip == null) return;
            mExitTip.animate().alpha(0f).setDuration(1000) // 第 3s 渐隐归零
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator a) {
                            if (mExitTip != null) {
                                mContent.removeView(mExitTip);
                                mExitTip = null;
                            }
                        }
                    }).start();
        }
    };

    private void exitAppNow() {
        if (mExitTip != null) {
            mContent.removeCallbacks(mExitFadeStart);
            mExitTip.animate().cancel();
            mContent.removeView(mExitTip);
            mExitTip = null;
        }
        finishAffinity();
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
        mMsgTick.removeCallbacks(mMsgTickR);
        mContent.removeCallbacks(mUndoFadeR);
        mContent.removeCallbacks(mTipFadeStart);
        // 身份比对后再清监听：重建时序"新 onCreate → 旧 onDestroy"，旧实例不能抹掉新实例的注册
        if (NotifyEngine.historyListener() == this) NotifyEngine.setHistoryListener(null);
        if (Theme.listener() == this) Theme.setListener(null);
        if (mExitTip != null) { // 清掉退出提示的渐隐回调/动画，避免销毁期再触碰视图树
            mContent.removeCallbacks(mExitFadeStart);
            mExitTip.animate().cancel();
        }
        // WebView 是全进程单例：只从视图树摘下，不销毁，下次启动复用同一会话
        PanelController.detach(this);
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
        // 「清空消息历史」已移入消息页右上角（一键直清，免确认），设置页不再保留入口
        box.addView(buttonRow("查看崩溃日志", v -> showCrashLog()));
        box.addView(buttonRow("用量信息（智谱官网）", v ->
                startActivity(new Intent(this, UsageActivity.class))));

        box.addView(sectionLabel("关于"));
        box.addView(buttonRow("隐私政策", v -> showPrivacyPolicy()));
        box.addView(buttonRow("GitHub 主页", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Rainling-LingYusi014")));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "无可用浏览器", android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
        TextView about = new TextView(this);
        about.setText(appName() + " v" + versionName() + "\n"
                + "ZCode 远程控制增强壳 · 非官方项目，与 Z.ai 无隶属关系");
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

    /** 当前构建的应用名（本地构建=ZCode RC，发行=ZRemote），跟随 Manifest label，用户可见文案一律用此。 */
    private String appName() {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(getPackageName(), 0)).toString();
        } catch (Exception e) {
            return "ZRemote";
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
                + "· z.ai 系官方域名（zcode.z.ai 远程控制；含 OAuth 登录授权域 chat.z.ai 等子域）\n"
                + "· bigmodel.cn 系官方域名（智谱官方用量页）\n"
                + "· 为支持官方 OAuth 登录链路，对上述官方子域启用了跨子域第三方 Cookie\n"
                + "· 页面内触发的文件下载经系统下载管理器进行，目标为上述官方域名主机\n"
                + "· 你配置的 ntfy 服务器（默认公共 ntfy.sh）：仅当开启\"桌面直推\"时，"
                + "任务标题（默认为会话首条输入的截断文本）、状态变化与错误消息摘要经其转发，"
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
                        + "① 自启动：设置 → 应用设置 → 应用管理 → " + appName() + " → 自启动，开启\n\n"
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
                            "设置→应用设置→应用管理→" + appName() + "：自启动=开，省电策略=无限制，允许通知=开"));
                    android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    // ---- 消息历史列表 ----
    // 显示结构：日期分组头（今天/昨天/M月d日）+ 事件行。事件行包在 FrameLayout 里，
    // 底层红底白字「删除」层平时被内容层盖住，左滑内容层露出（手势见 onListTouch）。

    private class HistoryAdapter extends BaseAdapter {
        private static final int TYPE_DAY = 0;
        private static final int TYPE_EVENT = 1;
        private final ArrayList<Object> items = new ArrayList<>(); // String=分组头 / JSONObject=事件

        void reload() {
            items.clear();
            long today0 = dayStart(0), yest0 = dayStart(1);
            String lastDay = null;
            for (JSONObject ev : NotifyEngine.historyDesc()) {
                String d = dayLabel(ev.optLong("ts", 0), today0, yest0);
                if (!d.equals(lastDay)) {
                    items.add(d);
                    lastDay = d;
                }
                items.add(ev);
            }
            notifyDataSetChanged();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        JSONObject eventAt(int position) {
            Object o = items.get(position);
            return o instanceof JSONObject ? (JSONObject) o : null;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? TYPE_DAY : TYPE_EVENT;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (getItemViewType(position) == TYPE_DAY) {
                TextView day = (convertView instanceof TextView) ? (TextView) convertView : buildDayRow();
                day.setText((String) items.get(position));
                return day;
            }
            FrameLayout wrap = (convertView instanceof FrameLayout)
                    ? (FrameLayout) convertView : buildEventRow();
            LinearLayout row = (LinearLayout) wrap.getTag();
            row.setTranslationX(0); // 复用复位：上一任持有者的滑动位移不能带过来
            JSONObject ev = (JSONObject) items.get(position);
            String to = ev.optString("to", "");
            String icon = to.contains("错误") || to.contains("失败") ? "⚠️"
                    : to.contains("已完成") ? "✅" : "🆕";
            String title = icon + "  " + ev.optString("name", "任务");
            String from = ev.optString("from", "");
            String sub = (from.isEmpty() ? "新任务" : from + " → " + to)
                    + " · " + relativeTime(ev.optLong("ts", 0));
            TextView t = (TextView) row.getChildAt(0);
            TextView s = (TextView) row.getChildAt(1);
            t.setText(title);
            t.setTextColor(Theme.textPrimary());
            s.setText(sub);
            s.setTextColor(Theme.textSecondary());
            return wrap;
        }

        private TextView buildDayRow() {
            TextView day = new TextView(MainActivity.this);
            day.setTextSize(12);
            day.setTextColor(Theme.textSecondary());
            day.setPadding((int) dp(16), (int) dp(14), (int) dp(16), (int) dp(4));
            return day;
        }

        private FrameLayout buildEventRow() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            int p = (int) dp(16);
            row.setPadding(p, (int) dp(10), p, (int) dp(10));
            row.setBackgroundColor(Theme.bg()); // 必须不透明：盖住底下的红色删除层
            TextView title = new TextView(MainActivity.this);
            title.setTextSize(15);
            TextView sub = new TextView(MainActivity.this);
            sub.setTextSize(12);
            sub.setPadding(0, (int) dp(3), 0, 0);
            row.addView(title);
            row.addView(sub);

            TextView del = new TextView(MainActivity.this);
            del.setText("删除");
            del.setTextColor(0xFFFFFFFF);
            del.setTextSize(14);
            del.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            del.setPadding(0, 0, (int) dp(24), 0);
            del.setBackgroundColor(0xFFD32F2F);

            FrameLayout wrap = new FrameLayout(MainActivity.this);
            // 红底删除层必须 MATCH_PARENT 铺满整行高：WRAP_CONTENT 只有一行文字高，盖不满
            wrap.addView(del, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wrap.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wrap.setTag(row);
            return wrap;
        }
    }

    private static long dayStart(int daysAgo) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() - daysAgo * 86_400_000L;
    }

    private static String dayLabel(long ts, long today0, long yest0) {
        if (ts >= today0) return "今天";
        if (ts >= yest0) return "昨天";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return (c.get(java.util.Calendar.MONTH) + 1) + "月" + c.get(java.util.Calendar.DAY_OF_MONTH) + "日";
    }

    /** 相对时间：现在 / N分钟前 / N小时前 / 昨天 HH:mm / M月d日 HH:mm（替代系统的「0分钟前」）。 */
    private static String relativeTime(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000L) return "现在";
        if (diff < 3_600_000L) return (diff / 60_000L) + "分钟前";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        String hm = String.format(java.util.Locale.CHINA, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        long yest0 = dayStart(1);
        if (diff < 86_400_000L && ts >= yest0) return (diff / 3_600_000L) + "小时前";
        if (ts >= yest0) return "昨天 " + hm;
        return (c.get(java.util.Calendar.MONTH) + 1) + "月"
                + c.get(java.util.Calendar.DAY_OF_MONTH) + "日 " + hm;
    }
}
