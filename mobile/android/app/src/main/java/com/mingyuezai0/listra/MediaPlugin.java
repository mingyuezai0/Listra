package com.mingyuezai0.listra;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「通知栏播放控制」在安卓上的那一半。模板是隔壁那颗 PipPlugin。
 *
 * 页面那边原来只有 navigator.mediaSession 一套 —— 那是**浏览器**接的线，
 * WebView 里没有那一层，所以在这台设备上它一行都没生效过（设置里那颗开关
 * 拨来拨去，通知栏永远是空的）。这颗插件把它补上：
 *
 *   页面 → 原生：setActive（开/关）/ setMeta（曲名、作者、专辑、封面、时长）
 *                / setState（在放没有、放哪儿了、几倍速）
 *   原生 → 页面：监听 "action"，收到 play / pause / stop / next / prev / seek
 *
 * ⚠️⚠️ 三条会要命的，改这个文件之前先看完：
 * ① Capacitor 的插件方法跑在**后台线程**上（Bridge 里那条名叫 "CapacitorPlugins"
 *    的 HandlerThread：callPluginMethod → taskHandler.post）。
 *    凡是碰界面、碰 Activity 的（申请权限最典型）都得 runOnUiThread 回主线程 ——
 *    在后台线程上调不崩，但会**静默失败**，也就是"按了没反应"。
 * ② @PluginMethod 里抛出去的异常会把整个进程干掉。Bridge.callPluginMethod 那个
 *    Runnable 末尾是 `catch (Exception ex) { throw new RuntimeException(ex); }`，
 *    跑在 HandlerThread 上 —— 线程上没人接的异常 = 进程死。
 *    所以每个方法体都得自己 try/catch 干净，一句都不能漏在外面。
 * ③ 真正的活儿在 MediaService 里，这颗插件只负责"翻译"。
 *    元数据/状态是**先写静态字段、再发一个空 Intent 叫醒服务**，
 *    不是把内容塞进 Intent —— 原因见 MediaService 类注释 ③（Binder 1MB 上限）。
 *    所以 send() 的顺序不能反：先写字段，后发 Intent。
 */
@CapacitorPlugin(name = "Media")
public class MediaPlugin extends Plugin {

    private static final int REQ_NOTIF = 7711;

    /** 服务工作在另一个组件里，回调得回到主线程再往页面上抛。 */
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        // 服务 → 页面那条路只有这一根线，见 MediaService 里 plugin() 那段
        try { MediaService.attach(this); } catch (Exception e){}
    }

    @Override
    protected void handleOnDestroy() {
        try { MediaService.attach(null); } catch (Exception e){}
    }

    /**
     * 设置里那颗开关。on=true 会真的起一条前台服务 ——
     * 不起的话 API 26 以上进程在后台一会儿就被掐，通知跟着消失，
     * 表现就是"切出去就没了"，跟没做一样。
     */
    @PluginMethod
    public void setActive(PluginCall call) {
        try {
            boolean on = Boolean.TRUE.equals(call.getBoolean("on", Boolean.FALSE));
            Context c = getContext();
            Intent i = new Intent(c, MediaService.class)
                    .setAction(on ? MediaService.A_ON : MediaService.A_OFF);
            if (on){
                // ⚠️ 13 起没有 POST_NOTIFICATIONS 的话，前台服务照跑，但**通知栏里不显示** ——
                //    用户看到的就是"开了开关也没东西"。开的时候顺手要一次。
                askNotif(getActivity());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
                else c.startService(i);
            } else {
                c.startService(i);
            }
            call.resolve();
        } catch (Exception e){
            call.reject("通知栏播放控制没能切换", e);
        }
    }

    /** 换曲目时推一次。art 是封面 base64（不带 data: 前缀），没有就传 null。 */
    @PluginMethod
    public void setMeta(PluginCall call) {
        try {
            // ⚠️ 先写字段再发 Intent，顺序不能反（见类注释 ③）
            MediaService.sTitle    = nz(call.getString("title", ""));
            MediaService.sArtist   = nz(call.getString("artist", ""));
            MediaService.sAlbum    = nz(call.getString("album", ""));
            MediaService.sDuration = num(call.getDouble("duration", 0.0));
            String art = call.getString("art", null);
            MediaService.sArt = (art == null || art.isEmpty()) ? null : art;
            poke(MediaService.A_META);
            call.resolve();
        } catch (Exception e){
            call.reject("曲目信息没送过去", e);
        }
    }

    /** 播放/暂停、拖了进度、改了倍速 —— 都是这条路。 */
    @PluginMethod
    public void setState(PluginCall call) {
        try {
            MediaService.sPlaying  = Boolean.TRUE.equals(call.getBoolean("playing", Boolean.FALSE));
            MediaService.sPosition = num(call.getDouble("position", 0.0));
            double sp = num(call.getDouble("speed", 1.0));
            MediaService.sSpeed = sp > 0 ? sp : 1;
            poke(MediaService.A_STATE);
            call.resolve();
        } catch (Exception e){
            call.reject("播放状态没送过去", e);
        }
    }

    /**
     * 只给 MediaService 调。故意**不加** @PluginMethod 注解 ——
     * 加了就会在 window.Capacitor.Plugins.Media 上多出一个 JS 入口，
     * 而这个方法只该由原生那边（通知栏按钮 / 耳机线控）触发。
     */
    public void onAction(final String cmd, final long posMs) {
        ui.post(() -> {
            try {
                JSObject d = new JSObject();
                d.put("action", cmd);
                // 秒。播放在页面那边，位置也得是页面那套单位。
                if (posMs >= 0) d.put("position", posMs / 1000.0);
                notifyListeners("action", d);
            } catch (Exception e){}
        });
    }

    /** 发一个空 Intent 把服务叫起来读静态字段。见类注释 ③。 */
    private void poke(String action) {
        try {
            Context c = getContext();
            Intent i = new Intent(c, MediaService.class).setAction(action);
            // 服务这会儿一定已经在跑（A_ON 先来过），所以是 startService 不是
            // startForegroundService —— 后者每次都要在 5 秒内重新 startForeground，
            // 白白多一条要求。服务没了也不怕：startService 会把它拉起来，
            // 起来时 A_META/A_STATE 落到 on == false 上，post() 直接返回。
            c.startService(i);
        } catch (Exception e){
            // 后台被系统拦住 startService 是正常的（那时服务本来就在跑，状态没丢），
            // 下一次前台交互会补上
        }
    }

    /** 13 起要 POST_NOTIFICATIONS。⚠️ requestPermissions 是 UI 线程的活儿（见类注释 ①）。 */
    private void askNotif(final Activity a) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || a == null) return;
            if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) return;
            a.runOnUiThread(() -> {
                try {
                    a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                } catch (Exception e){}
            });
        } catch (Exception e){}
    }

    private static String nz(String s){ return s == null ? "" : s; }
    private static double num(Double d){ return (d == null || d.isNaN() || d.isInfinite()) ? 0 : d; }
}
