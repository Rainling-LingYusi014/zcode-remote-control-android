package com.dsha.zremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 通知引擎：接收 JS 桥回传的任务事件 → 渠道化通知 + 历史落盘。
 * 事件解析逻辑（INJECT_JS）只依赖 data-testid 与徽章文本，见 docs/2a-探针结论.md；
 * 官方改版时只需重写本文件的 INJECT_JS 与快照逻辑。
 */
public final class NotifyEngine {

    public static final String CH_DONE = "task_done";
    public static final String CH_ALERT = "task_alert";
    public static final String CH_SERVICE = "service";
    public static final String BRIDGE_NAME = "zrcBridge";
    private static final int MAX_HISTORY = 200;

    private static Context sApp;
    private static final ArrayList<JSONObject> sHistory = new ArrayList<>();
    private static boolean sInited = false;
    private static HistoryListener sListener;

    /** 历史变化回调（UI 线程），供角标/列表实时刷新。 */
    public interface HistoryListener {
        void onHistoryChanged();
    }

    public static void setHistoryListener(HistoryListener l) {
        sListener = l;
    }

    private NotifyEngine() {}

    static synchronized void init(Context appCtx) {
        if (sInited) return;
        sInited = true;
        sApp = appCtx.getApplicationContext();
        NotificationManager nm = (NotificationManager)
                sApp.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel done = new NotificationChannel(
                CH_DONE, "任务完成", NotificationManager.IMPORTANCE_DEFAULT);
        done.setDescription("任务正常结束");
        nm.createNotificationChannel(done);
        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "任务出错", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("任务失败或需要处理");
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);
        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "实时模式", NotificationManager.IMPORTANCE_MIN);
        svc.setDescription("前台服务常驻通知");
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);
        loadHistory();
    }

    private static Context app() {
        return sApp;
    }

    /** JS 桥：页面里的注入脚本通过 window.zrcBridge.* 回传事件/快照。 */
    public static class Bridge {
        @JavascriptInterface
        public void onEvents(String json) {
            NotifyEngine.onTaskEvents(json);
        }

        /** 注入脚本冷启动时取上一次持久化快照，保证跨进程死亡也能 diff 出"死掉期间"的变化。 */
        @JavascriptInterface
        public String getLastSnapshot() {
            try {
                return app().getSharedPreferences("zremote", Context.MODE_PRIVATE)
                        .getString("last_snapshot", "null");
            } catch (Exception e) {
                return "null";
            }
        }

        @JavascriptInterface
        public void onSnapshot(String json) {
            try {
                app().getSharedPreferences("zremote", Context.MODE_PRIVATE)
                        .edit().putString("last_snapshot", json).apply();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void onTheme(boolean dark) {
            Theme.set(app(), dark);
        }

        /** 注入脚本回报"等待桌面端配对"状态（死会话等待页），原生据此显隐右上角扫码钮。 */
        @JavascriptInterface
        public void onPairingWait(boolean waiting) {
            PanelController.onPairingWait(waiting);
        }

        @JavascriptInterface
        public void onSearch() {
            PanelController.fireSearch();
        }
    }

    /** 解析事件数组：[{t,id,name,from,to}]，发通知 + 记历史。其余类型只记历史不通知。 */
    static synchronized void onTaskEvents(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ev = arr.getJSONObject(i);
                String to = ev.optString("to", "");
                String name = ev.optString("name", "任务");
                ev.put("ts", System.currentTimeMillis());
                sHistory.add(ev);
                if (to.contains("已完成")) {
                    post(ev, CH_DONE, "✅ 任务完成：" + name);
                } else if (to.contains("错误") || to.contains("失败")) {
                    post(ev, CH_ALERT, "⚠️ 任务出错：" + name);
                }
                // 新任务 / 变回运行中：只记历史，不打扰
            }
            trimAndSave();
            notifyHistoryListener();
        } catch (Exception ignored) {
            // 单条坏数据不至于拖垮引擎
        }
    }

    private static void notifyHistoryListener() {
        final HistoryListener l = sListener;
        if (l == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            HistoryListener cur = sListener;
            if (cur != null) cur.onHistoryChanged();
        });
    }

    private static String describe(JSONObject ev) {
        String from = ev.optString("from", "");
        String to = ev.optString("to", "");
        if (from.isEmpty()) return "新任务出现" + (to.isEmpty() ? "" : " · " + to);
        return from + " → " + to;
    }

    /** 静默时段判定：start/end 为当天分钟数，支持跨零点（如 23:00–7:00）。 */
    static boolean inQuietPeriod() {
        SharedPreferences p = app().getSharedPreferences("zremote", Context.MODE_PRIVATE);
        if (!p.getBoolean("quiet_on", false)) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int s = p.getInt("quiet_start", 23 * 60);
        int e = p.getInt("quiet_end", 7 * 60);
        return s <= e ? (now >= s && now < e) : (now >= s || now < e);
    }

    private static void post(JSONObject ev, String channel, String title) {
        try {
            // 静默时段：只记历史不弹通知
            if (inQuietPeriod()) return;
            NotificationManager nm = (NotificationManager)
                    app().getSystemService(Context.NOTIFICATION_SERVICE);
            Intent intent = app().getPackageManager().getLaunchIntentForPackage(app().getPackageName());
            PendingIntent pi = PendingIntent.getActivity(app(), 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(app(), channel)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(title)
                    .setContentText(describe(ev))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify((ev.optString("id", "x") + channel).hashCode(), n);
        } catch (Exception ignored) {
        }
    }

    // ---- 历史 ----

    /** 新→旧排列的历史副本。 */
    public static synchronized ArrayList<JSONObject> historyDesc() {
        ArrayList<JSONObject> copy = new ArrayList<>(sHistory);
        Collections.reverse(copy);
        return copy;
    }

    public static synchronized void clearHistory() {
        sHistory.clear();
        trimAndSave();
    }

    private static void trimAndSave() {
        while (sHistory.size() > MAX_HISTORY) sHistory.remove(0);
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : sHistory) arr.put(o);
            File f = new File(app().getFilesDir(), "history.json");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadHistory() {
        try {
            File f = new File(app().getFilesDir(), "history.json");
            if (!f.exists()) return;
            StringBuilder sb = new StringBuilder();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) sHistory.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    // ---- 注入脚本 ----

    /** 注入 JS：等任务卡出现→从原生取持久化快照→diff（>10 条视为换会话静默重建）→zrcBridge 回传。 */
    public static final String INJECT_JS =
        "(function(){" +
        "if (window.__zrcInstalled) return;" +
        "window.__zrcInstalled = true;" +
        "var RE = /^(已完成|运行中|错误|失败|等待|排队|进行中)$/;" +
        "function ownText(el){var t='';for(var i=0;i<el.childNodes.length;i++){var n=el.childNodes[i];if(n.nodeType===3)t+=n.textContent;}return t.trim();}" +
        "function statusOf(card){var els=card.querySelectorAll('*');for(var i=0;i<els.length;i++){var o=ownText(els[i]);if(RE.test(o))return o;}return '';}" +
        "function nameOf(card){var el=card.querySelector('.truncate');return el?el.textContent.trim():card.textContent.trim().slice(0,30);}" +
        "function snapshot(){var m={};var cs=document.querySelectorAll('[data-testid^=\"task-item-\"]');" +
        "for(var i=0;i<cs.length;i++){var c=cs[i];m[c.getAttribute('data-testid')]={n:nameOf(c),s:statusOf(c)};}return m;}" +
        "var last=null,timer=null;" +
        "function loadLast(){try{return JSON.parse(window.zrcBridge.getLastSnapshot())||null;}catch(e){return null;}}" +
        "function persist(){try{window.zrcBridge.onSnapshot(JSON.stringify(last));}catch(e){}}" +
        "function diff(oldS,newS){var ev=[];if(!oldS)return ev;" +
        "for(var id in newS){var o=oldS[id];var n=newS[id];" +
        "if(!o){ev.push({t:'new',id:id,name:n.n,to:n.s});}" +
        "else if(o.s!==n.s){ev.push({t:'status',id:id,name:n.n,from:o.s,to:n.s});}}" +
        "return ev;}" +
        "function fire(){timer=null;var s=snapshot();var ev=diff(last,s);last=s;persist();" +
        "try{window.__zrcKeepBtn();}catch(e){}" +
        "if(ev.length>10)return;" +
        "if(ev.length&&window.zrcBridge&&window.zrcBridge.onEvents){" +
        "try{window.zrcBridge.onEvents(JSON.stringify(ev));}catch(e){}}}" +
        "window.__zrcKeepBtn = function(){if(!document.getElementById('zrc-search-btn'))insertSearchBtn();};" +
        "function schedule(){if(timer)return;timer=setTimeout(fire,1500);}" +
        "function start(){new MutationObserver(schedule).observe(document.body," +
        "{subtree:true,childList:true,characterData:true,attributes:true});}" +
        "function init(){" +
        "if(!document.querySelector('[data-testid^=\"task-item-\"]'))return false;" +
        "last=loadLast();start();fire();return true;}" +
        "window.__zrcFire = fire;" +
        "function themeNow(){var bg='';" +
        "try{bg=getComputedStyle(document.body).backgroundColor;}catch(e){}" +
        "if(!bg||bg.indexOf('rgba(0, 0, 0, 0)')===0||bg==='transparent'){" +
        "try{bg=getComputedStyle(document.documentElement).backgroundColor;}catch(e){}}" +
        "var m=/rgba?\\((\\d+)[, ]+(\\d+)[, ]+(\\d+)/.exec(bg||'');" +
        "if(!m)return true;var lum=0.299*m[1]+0.587*m[2]+0.114*m[3];return lum<140;}" +
        "var lastTheme=null;var pwLast=null;" +
        "function pairWait(){var t=document.body?document.body.innerText:'';" +
        "var w=t.indexOf('等待桌面端')>=0;if(w!==pwLast){pwLast=w;" +
        "try{window.zrcBridge.onPairingWait(w);}catch(e){}}}" +
        "function checkTheme(){pairWait();var d=themeNow();if(d!==lastTheme){lastTheme=d;" +
        "try{window.zrcBridge.onTheme(d);}catch(e){}}}" +
        "window.__zrcOpenTask = function(tid){try{var c=document.querySelector('[data-testid=\"'+tid+'\"]');" +
        "if(c){c.click();return 'ok';}return 'nolist';}catch(e){return 'err';}};" +
        "window.__zrcListTasks = function(){var out=[];var cs=document.querySelectorAll('[data-testid^=\"task-item-\"]');" +
        "for(var i=0;i<cs.length;i++){out.push({tid:cs[i].getAttribute('data-testid'),n:nameOf(cs[i]),s:statusOf(cs[i])});}return out;};" +
        "window.__zrcCheckTheme = checkTheme;" +
        "function insertSearchBtn(){try{" +
        "if(document.getElementById('zrc-search-btn'))return;" +
        "var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n=null,t=null;" +
        "while((n=w.nextNode())){var s=(n.textContent||'').trim();" +
        "if(s.indexOf('当前设备上的工作区和任务')>=0&&s.length<40){t=s;break;}}" +
        "if(!t||!n)return;" +
        "var el=n.parentElement,row=null;" +
        "for(var i=0;i<6&&el;i++){if(el.querySelectorAll('button').length>=2){row=el;break;}el=el.parentElement;}" +
        "if(!row)return;" +
        "var bs=row.querySelectorAll('button');var last=bs[bs.length-1];" +
        "var b=last.cloneNode(false);b.id='zrc-search-btn';" +
        "b.setAttribute('aria-label','搜索会话');b.title='搜索会话';" +
        "b.innerHTML='<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/><line x1=\"21\" y1=\"21\" x2=\"16.3\" y2=\"16.3\"/></svg>';" +
        "b.addEventListener('click',function(){try{window.zrcBridge.onSearch();}catch(e){}});" +
        "last.parentElement.insertBefore(b,last.nextSibling);" +
        "}catch(e){}}" +
        "insertSearchBtn();setInterval(function(){try{window.__zrcKeepBtn();}catch(e){}},1000);" +
        "try{new MutationObserver(checkTheme).observe(document.documentElement," +
        "{attributes:true,attributeFilter:['class','style','data-theme']});}catch(e){}" +
        "try{window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',checkTheme);}catch(e){}" +
        "setInterval(checkTheme,3000);checkTheme();" +
        "var tries=0;" +
        "var iv=setInterval(function(){tries++;if(init()){clearInterval(iv);return;}" +
        "if(tries>120)clearInterval(iv);},1000);" +
        "})();";
}
