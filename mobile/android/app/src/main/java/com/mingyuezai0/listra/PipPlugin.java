package com.mingyuezai0.listra;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.util.Rational;

import androidx.annotation.RequiresApi;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「悬浮小窗」在安卓上的那一半。模板是隔壁那颗 AppInfoPlugin。
 *
 * 为什么不用 Web 那套（video.requestPictureInPicture）：WebView 里没有它
 * （Blink 的 PictureInPictureController 只在浏览器 UI 那层接线，WebView 没接，
 * document.pictureInPictureEnabled 恒为 false）。只能走 Activity 的原生小窗：
 * **整个窗口**被系统缩进那个小方块里，所以页面那边还得把画面以外的东西收干净
 * （见 index.html 里 body.pip-on 那套 CSS）。
 *
 * 顺带一提：用户要的"能拖、拖边缘改大小"是系统给的，这个文件里没有一行代码管它。
 *
 * ⚠️⚠️ 四条会要命的，改这个文件之前先看完：
 * ① Capacitor 的插件方法跑在**后台线程**上（Bridge 里那条名叫 "CapacitorPlugins"
 *    的 HandlerThread：callPluginMethod → taskHandler.post）。
 *    enterPictureInPictureMode / setPictureInPictureParams 都是 UI 线程的活儿，
 *    必须 runOnUiThread 回主线程。在后台线程上调不会崩，但会**静默失败** ——
 *    表现是"按了没反应"，最难查的那种。
 * ② @PluginMethod 里抛出去的异常会把整个进程干掉。Bridge.callPluginMethod 那个
 *    Runnable 末尾是 `catch (Exception ex) { throw new RuntimeException(ex); }`，
 *    跑在 HandlerThread 上 —— 线程上没人接的异常 = 进程死。
 *    所以每个方法体都得自己 try/catch 干净，一句都不能漏在外面。
 * ③ 小窗是 API 26 才有的，minSdk 是 24。API 26 的类型只准出现在**方法体**里
 *    （方法签名上带着 @RequiresApi 也认），别拿来当字段类型 —— 字段是加载类的时候
 *    就要解析的，24/25 上会 NoClassDefFoundError。
 * ④ 宽高比只认 1/2.39 ~ 2.39，越界 enterPictureInPictureMode 直接
 *    IllegalArgumentException —— 配上 ② 就是一次崩溃。必须夹。
 *
 * notifyListeners 是 protected 的，MainActivity 够不着，所以转发那一步由插件自己
 * 出（public void onModeChanged，故意**不加** @PluginMethod 注解 —— 加了就会在
 * window.Capacitor.Plugins.Pip 上多出一个 JS 入口，而这个方法只该由原生事件触发）。
 */
@CapacitorPlugin(name = "Pip")
public class PipPlugin extends Plugin {

    private static final double MAX_RATIO = 2.39;
    private static final double MIN_RATIO = 1.0 / 2.39;

    /** 支持不支持 + 现在在不在小窗里。JS 进页面问一次，用来决定那颗按钮露不露脸。 */
    @PluginMethod
    public void isSupported(PluginCall call) {
        try {
            JSObject r = new JSObject();
            r.put("supported", pipSupported());
            Activity a = getActivity();
            r.put("inPip", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && a != null && a.isInPictureInPictureMode());
            call.resolve(r);
        } catch (Exception e){
            call.reject("问不出小窗的支持情况", e);
        }
    }

    /** 进小窗。JS 先把 body.pip-on 挂上再喊这一声（原因见 index.html 那边）。 */
    @PluginMethod
    public void enter(PluginCall call) {
        try {
            final Activity a = getActivity();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || a == null || !pipSupported()){
                call.reject("这台设备不支持悬浮小窗");
                return;
            }
            final PictureInPictureParams params = buildParams(
                    call.getInt("width"), call.getInt("height"),
                    call.getInt("x"), call.getInt("y"),
                    call.getInt("rectW"), call.getInt("rectH"));
            // ① 回主线程
            a.runOnUiThread(() -> {
                try {
                    if (a.enterPictureInPictureMode(params)) call.resolve();
                    else call.reject("现在进不了小窗");   // 窗口不在前台时系统会拒
                } catch (Exception e){
                    call.reject("进不去小窗", e);
                }
            });
        } catch (Exception e){
            call.reject("进不去小窗", e);
        }
    }

    /**
     * 小窗里换了一条分辨率不一样的片子（小窗上那颗"下一首"就是这条路），
     * 小窗的形状得跟着改 —— 不然还是照上一部片子的比例摆着。只能在小窗里调。
     */
    @PluginMethod
    public void setAspectRatio(PluginCall call) {
        try {
            final Activity a = getActivity();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || a == null){ call.resolve(); return; }
            final PictureInPictureParams params = buildParams(
                    call.getInt("width"), call.getInt("height"),
                    null, null, null, null);
            a.runOnUiThread(() -> {
                try { a.setPictureInPictureParams(params); } catch (Exception e){ /* 不在小窗里就算了 */ }
                call.resolve();
            });
        } catch (Exception e){
            call.resolve();
        }
    }

    /**
     * 退出小窗。安卓**没有**这个 API —— Activity 上压根没有 exitPictureInPictureMode
     * 这个东西（社区提过加一个，被 Google 拒了）。唯一的办法是把 Activity 重新叫到
     * 前台：singleTask + FLAG_ACTIVITY_REORDER_TO_FRONT。
     * 副作用是软件会从后台被拽到屏幕上 —— 所以 JS 那边只在确定自己在前台时才调。
     */
    @PluginMethod
    public void exit(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e){
            call.reject("退不出小窗", e);
        }
    }

    /** 只给 MainActivity 调。故意不加 @PluginMethod —— 见类注释。 */
    public void onModeChanged(boolean inPip) {
        try {
            JSObject d = new JSObject();
            d.put("inPip", inPip);
            notifyListeners("modeChanged", d);
        } catch (Exception e){ /* 页面还没把监听挂上时 notifyListeners 自己会吞，这里兜底 */ }
    }

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getContext().getPackageManager()
                       .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private PictureInPictureParams buildParams(Integer w, Integer h,
                                               Integer x, Integer y, Integer rw, Integer rh) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();

        // ④ 夹宽高比。画面那边是 object-fit:contain，夹完只是多两条黑边，不会变形。
        int vw = (w != null && w > 0) ? w : 16;
        int vh = (h != null && h > 0) ? h : 9;
        double ratio = (double) vw / (double) vh;
        if (ratio > MAX_RATIO)       vw = (int) Math.round(vh * MAX_RATIO);
        else if (ratio < MIN_RATIO)  vh = (int) Math.round(vw / MIN_RATIO);
        try { b.setAspectRatio(new Rational(vw, vh)); }
        catch (Exception e){ /* 万一还是不合规就不设，系统按窗口现在的形状来 */ }

        // 小窗从画面现在的位置长出来（Android 12 起这个矩形也管退出的动画）。
        // JS 递的是视口坐标（CSS px），乘 density 换成像素 —— 边到边之后
        // WebView 就是整个窗口，原点对得上。
        if (x != null && y != null && rw != null && rh != null && rw > 0 && rh > 0){
            float d = getContext().getResources().getDisplayMetrics().density;
            try {
                b.setSourceRectHint(new Rect(Math.round(x * d), Math.round(y * d),
                                             Math.round((x + rw) * d), Math.round((y + rh) * d)));
            } catch (Exception e){ /* 坐标不合法就别设，动画难看一点而已 */ }
        }
        return b.build();
    }
}
