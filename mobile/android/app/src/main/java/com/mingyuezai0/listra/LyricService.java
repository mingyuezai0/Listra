package com.mingyuezai0.listra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 悬浮歌词（第二十二批第 7 项）。模板是隔壁的 MediaService。
 *
 * 为什么非得有原生这一半：安卓不允许一个应用在**自己的界面之外**画画。
 * WebView 里画得再花，切到别的应用就全看不见了。要浮在别人头上，
 * 只有 WindowManager + TYPE_APPLICATION_OVERLAY 这一条路，而且还得用户
 * 在系统设置里给「显示在其他应用上层」那个权限。
 *
 * ⚠️⚠️ 五条会要命的，改这个文件之前先看完：
 * ① **前台服务**。不加的话进程一进后台就被掐，浮窗跟着消失 —— 而"切出去看歌词"
 *    恰恰是它唯一的使用场景。所以必须有那条通知（systemui 不允许没有通知的前台服务）。
 * ② API 34 起前台服务必须报类型。这里报 specialUse，清单里对应
 *    FOREGROUND_SERVICE_SPECIAL_USE 权限 + 那个 <property> 子标签，少一样
 *    startForeground 直接抛。类型常量是 34 才有的，得判版本。
 * ③ 没有权限时 addView 会抛 WindowManager.BadTokenException —— 那不是 Exception
 *    之外的什么怪东西，但**必须当场收干净**，否则服务带着一个没建起来的浮窗活着，
 *    用户看到的就是"开关开了、什么都没有"。所以 addView 失败要 stopSelf。
 * ④ 文字走静态字段（和 MediaService 同一个理由：Binder 事务上限 1MB）。
 *    插件跑在后台线程、服务读在主线程，所以那几个字段全部 volatile。
 * ⑤ 服务里抛出去的异常没人接 = 进程死。每一处都自己 try。
 */
public class LyricService extends Service {

    private static final String CH_ID = "tingyu.lyric";
    private static final int NOTIF_ID = 7712;

    static final String A_ON  = "com.mingyuezai0.listra.LYRIC_ON";
    static final String A_OFF = "com.mingyuezai0.listra.LYRIC_OFF";
    static final String A_TXT = "com.mingyuezai0.listra.LYRIC_TXT";

    /* ---- 插件 → 服务：现在该显示哪三行（见类注释 ④） ---- */
    static volatile String  sPrev    = "";
    static volatile String  sCur     = "";
    static volatile String  sNext    = "";
    static volatile String  sMsg     = "";     // 没歌词时顶上来的那句话
    static volatile boolean sPlaying = false;

    /** 服务 → 页面那条路只有这一根线（notifyListeners 是 protected）。跟 MediaService 一个形状。 */
    private static WeakReference<LyricPlugin> plugin = new WeakReference<>(null);
    static void attach(LyricPlugin p){ plugin = new WeakReference<>(p); }
    private static LyricPlugin plugin(){ return plugin.get(); }

    /* 配色照听雨的规矩：深色半透明底 + 薄荷绿当信号色，不用蓝紫。 */
    private static final int C_BG     = 0xB30C1A15;   // 70% 的黑绿
    private static final int C_EDGE   = 0x337FE3C6;   // 20% 的薄荷
    private static final int C_CUR    = 0xFF7FE3C6;   // --mint-300
    private static final int C_SIDE   = 0x85E6EFEB;   // 两侧那两句淡下去
    private static final int C_IDLE   = 0x73E6EFEB;

    private WindowManager wm;
    private View root;
    private TextView tvPrev, tvCur, tvNext;
    private WindowManager.LayoutParams lp;
    private boolean on = false;
    private boolean fg = false;

    // 拖动用的三个数：按下那一刻的手指位置 + 窗口位置
    private float downX, downY;
    private int winX, winY;
    private boolean moved;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null){
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, getString(R.string.lyric_channel), NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        } catch (Exception e){
            // 建不起来就当没有。页面那边还有唱片页那一套兜着，不该因此崩掉。
        }
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        try {
            if (it != null){
                String a = it.getAction();
                if (A_ON.equals(a))       setOn(true);
                else if (A_OFF.equals(a)) setOn(false);
                else if (A_TXT.equals(a)) applyText();
            }
        } catch (Exception e){
            // 服务里没人接的异常就是一次崩溃，这一层是最后的兜底
        }
        // 别自己复活：页面没了，留一条浮窗杵在别人应用头上没有任何意义
        return START_NOT_STICKY;
    }

    private void setOn(boolean want) {
        if (want){
            on = true;
            // ⚠️ 前台服务先起，再建浮窗。反过来的话，建窗那一下到 startForeground
            //    之间有个空档，系统正好在这时候回收进程就是"浮窗一闪就没了"。
            postNotif(true);
            addOverlay();
            applyText();
            return;
        }
        on = false;
        fg = false;
        removeOverlay();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception e){}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception e){}
        stopSelf();
    }

    /* ---------- 那一块浮窗 ---------- */

    private void addOverlay() {
        if (root != null) return;
        try {
            root = buildView();

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY     // 26 起唯一允许的那个
                    : WindowManager.LayoutParams.TYPE_PHONE;                  // 24/25 上还能用

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    // ⚠️ 必须 NOT_FOCUSABLE：不抢焦点，用户点到底下的东西还是点到底下的东西。
                    //    也不加 FLAG_NOT_TOUCHABLE —— 那样就没法拖了。
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 24;
            lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.62);   // 靠下但不贴底

            wm.addView(root, lp);
        } catch (Throwable t){
            // ⚠️ 没有「显示在其他应用上层」权限时就是 BadTokenException（见类注释 ③）。
            //    收干净并自尽 —— 别让服务带着一块没建起来的浮窗活着。
            root = null;
            on = false;
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception e){}
            stopSelf();
        }
    }

    private void removeOverlay() {
        try {
            if (root != null && wm != null) wm.removeView(root);
        } catch (Exception e){}
        root = null;
        tvPrev = tvCur = tvNext = null;
    }

    private View buildView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(9), dp(16), dp(11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BG);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), C_EDGE);       // 一条细薄荷边，深色桌面上才看得出边界
        box.setBackground(bg);

        // 顶上一条：右边一颗 ×。点它等于关掉悬浮歌词，并回报给页面。
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setTextColor(C_SIDE);
        close.setPadding(dp(10), 0, dp(2), dp(2));
        close.setOnClickListener(v -> { requestOff(); });
        bar.addView(close);
        box.addView(bar);

        tvPrev = line(12, C_SIDE, Typeface.NORMAL);
        tvCur  = line(17, C_CUR,  Typeface.BOLD);
        tvNext = line(12, C_SIDE, Typeface.NORMAL);
        box.addView(tvPrev);
        box.addView(tvCur);
        box.addView(tvNext);

        // 整块拖走。⚠️ 拖动和点击要分得开：位移超过一个阈值才算拖，
        //    否则手指一点点抖就把点击吃掉了（那颗 × 就按不动了）。
        box.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    winX = lp.x; winY = lp.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.abs(dx) + Math.abs(dy) < dp(6)) return true;
                    moved = true;
                    try {
                        lp.x = winX + (int) dx;
                        lp.y = winY + (int) dy;
                        wm.updateViewLayout(root, lp);
                    } catch (Exception ex){}
                    return true;
                }
                default:
                    return moved;      // 没拖过就放行给子 View，那颗 × 才点得着
            }
        });
        return box;
    }

    private TextView line(int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(300));            // 长句截断，别让它横着长到屏幕外面
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (style == Typeface.BOLD) t.setShadowLayer(6f, 0f, 1f, 0x99000000);
        return t;
    }

    private void applyText() {
        if (root == null) return;
        try {
            boolean has = !sCur.isEmpty() || !sPrev.isEmpty() || !sNext.isEmpty();
            tvPrev.setText(has ? sPrev : "");
            tvNext.setText(has ? sNext : "");
            if (has){
                tvCur.setText(sCur);
                // 暂停时当前行褪成灰白 —— 浮在别人头上不动的东西，
                // 得让人一眼分清是暂停了还是卡住了
                tvCur.setTextColor(sPlaying ? C_CUR : C_IDLE);
            } else {
                tvCur.setText(sMsg);
                tvCur.setTextColor(C_IDLE);
            }
        } catch (Exception e){}
    }

    /** 用户点了那颗 ×：收掉浮窗，并回报页面把设置里那颗开关拨回去。 */
    private void requestOff() {
        try {
            LyricPlugin p = plugin();
            if (p != null) p.onClosed();
        } catch (Exception e){}
        setOn(false);
    }

    /* ---------- 那条"我还在"的通知 ---------- */

    private void postNotif(boolean first) {
        try {
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(android.R.drawable.ic_menu_sort_by_size)   // 框架自带，单色
             .setContentTitle(getString(R.string.app_name))
             .setContentText(getString(R.string.lyric_notif))
             .setContentIntent(pi)
             .setOngoing(true)
             .setShowWhen(false)
             .setPriority(Notification.PRIORITY_MIN);
            Notification n = b.build();

            if (first && !fg){
                // ⚠️ API 34 起必须报类型（见类注释 ②）。类型常量是 34 才有的，得判版本。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                    startForeground(NOTIF_ID, n);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                fg = true;
            } else {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, n);
            }
        } catch (Exception e){
            // 起不来前台服务也不能崩。真起不来就是浮窗会被系统提前收走，别的照旧。
        }
    }

    private int dp(int v){
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroy() {
        on = false;
        removeOverlay();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception e){}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
