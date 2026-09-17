package com.mingyuezai0.listra;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「悬浮歌词」在安卓上的那一半。模板是隔壁那颗 MediaPlugin。
 *
 *   页面 → 原生：canDraw（有没有权限）/ askDraw（领到系统设置那一页）
 *                / setActive（开 / 关）/ setText（上一句·这一句·下一句）
 *   原生 → 页面：监听 "state"，收到 {open:false} 就是用户点了浮窗上那颗 ×，
 *                页面该把设置里那颗开关拨回去
 *
 * ⚠️⚠️ 三条会要命的，改这个文件之前先看完：
 * ① Capacitor 的插件方法跑在**后台线程**上（Bridge 里那条名叫 "CapacitorPlugins"
 *    的 HandlerThread）。凡是碰界面、碰 Activity 的都得 runOnUiThread 回主线程 ——
 *    在后台线程上调不崩，但会**静默失败**，也就是"按了没反应"。
 * ② @PluginMethod 里抛出去的异常会把整个进程干掉。Bridge.callPluginMethod 那个
 *    Runnable 末尾是 `catch (Exception ex) { throw new RuntimeException(ex); }`，
 *    跑在 HandlerThread 上 —— 线程上没人接的异常 = 进程死。
 *    所以每个方法体都得自己 try/catch 干净，一句都不能漏在外面。
 * ③ 真正的活儿在 LyricService 里，这颗插件只负责"翻译"。
 *    文字是**先写静态字段、再发一个空 Intent 叫醒服务**，不是把内容塞进 Intent ——
 *    跟 MediaService 是同一套（那边是因为 Binder 1MB 上限，这边是保持一致）。
 *    所以 send() 的顺序不能反：先写字段，后发 Intent。
 */
@CapacitorPlugin(name = "Lyric")
public class LyricPlugin extends Plugin {

    /** 服务 → 页面那条路得回到主线程再往页面上抛。 */
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        try { LyricService.attach(this); } catch (Exception e){}
    }

    @Override
    protected void handleOnDestroy() {
        try { LyricService.attach(null); } catch (Exception e){}
    }

    /**
     * 有没有「显示在其他应用上层」这个权限。
     * ⚠️ canDrawOverlays 最省事，但它是 23 才有的 —— minSdk 是 24，所以安全。
     *    24/25 上它恒为 true（那时候这个权限是安装即给的），正好是要的行为。
     */
    @PluginMethod
    public void canDraw(PluginCall call) {
        try {
            boolean granted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                granted = Settings.canDrawOverlays(getContext());
            }
            JSObject r = new JSObject();
            r.put("granted", granted);
            call.resolve(r);
        } catch (Exception e){
            call.reject("问不到悬浮窗权限", e);
        }
    }

    /**
     * 把用户领到"显示在其他应用上层"那一页。
     * ⚠️ 这是 UI 线程的活儿（见类注释 ①）。而且这一跳是**离开本应用**，
     *    用户回不回来、给不给，页面那边都只能等下一次 canDraw —— 所以这里
     *    不做任何"给完了"的假设，resolve 的就是"我跳过去了"。
     */
    @PluginMethod
    public void askDraw(PluginCall call) {
        try {
            final Context c = getContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                ui.post(() -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + c.getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(i);
                    } catch (Exception e){
                        // 有些 ROM 没有这一页。退一步开应用详情页，用户还能自己找。
                        try {
                            Intent i2 = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + c.getPackageName()));
                            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(i2);
                        } catch (Exception e2){}
                    }
                });
            }
            call.resolve();
        } catch (Exception e){
            call.reject("打不开悬浮窗权限页", e);
        }
    }

    /** 设置里那颗开关。on=true 会真的起一条前台服务（理由见 LyricService 类注释 ①）。 */
    @PluginMethod
    public void setActive(PluginCall call) {
        try {
            boolean on = Boolean.TRUE.equals(call.getBoolean("on", Boolean.FALSE));
            Context c = getContext();
            Intent i = new Intent(c, LyricService.class)
                    .setAction(on ? LyricService.A_ON : LyricService.A_OFF);
            if (on){
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
                else c.startService(i);
            } else {
                c.startService(i);
            }
            call.resolve();
        } catch (Exception e){
            call.reject("悬浮歌词没能切换", e);
        }
    }

    /** 换句时推一次：上一句 / 这一句 / 下一句 + 在不在放。 */
    @PluginMethod
    public void setText(PluginCall call) {
        try {
            // ⚠️ 先写字段再发 Intent，顺序不能反（见类注释 ③）
            LyricService.sPrev    = nz(call.getString("prev", ""));
            LyricService.sCur     = nz(call.getString("cur", ""));
            LyricService.sNext    = nz(call.getString("next", ""));
            LyricService.sMsg     = nz(call.getString("msg", ""));
            LyricService.sPlaying = Boolean.TRUE.equals(call.getBoolean("playing", Boolean.FALSE));
            Context c = getContext();
            // 服务这会儿一定已经在跑（A_ON 先来过），所以是 startService 不是
            // startForegroundService —— 后者每次都要在 5 秒内重新 startForeground，
            // 白白多一条要求。服务没了也不怕：startService 会把它拉起来，
            // 起来时 A_TXT 落到 on == false 上，applyText 直接返回。
            c.startService(new Intent(c, LyricService.class).setAction(LyricService.A_TXT));
            call.resolve();
        } catch (Exception e){
            call.reject("歌词没送过去", e);
        }
    }

    /**
     * 只给 LyricService 调（用户点了浮窗上那颗 ×）。
     * 故意**不加** @PluginMethod 注解 —— 加了就会在 window.Capacitor.Plugins.Lyric 上
     * 多出一个 JS 入口，而这个方法只该由原生那边触发。
     */
    public void onClosed() {
        ui.post(() -> {
            try {
                JSObject d = new JSObject();
                d.put("open", false);
                notifyListeners("state", d);
            } catch (Exception e){}
        });
    }

    private static String nz(String s){ return s == null ? "" : s; }
}
