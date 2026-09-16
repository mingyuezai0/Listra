package com.mingyuezai0.listra;

import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「系统通知栏」和「home 键那条手势条」的配色跟着软件主题走（第二十一批第 5 项）。
 *
 * 为什么不用 Capacitor 自带的 SystemBars：
 * 它只有 setStyle / show / hide / setAnimation 四个 @PluginMethod，颜色是它从**主题**里
 * 现读的（SystemBars.java 里 setBackgroundColor(getThemeColor(android.R.attr.windowBackground))），
 * JS 这边递不进去。图标深浅那一半仍旧交给它（那边是官方路子，setAppearanceLightStatusBars），
 * 颜色这一半只能自己写一颗。两颗的分工写在 index.html 那段 chromeNative 里。
 *
 * 四处一起刷，是因为它们各自管一段：
 * ① setStatusBarColor / setNavigationBarColor —— API 34 及以下管用。targetSdk 是 36，
 *    在 Android 15+ 上这两句会被系统忽略（强制 edge-to-edge），留着是为了照顾老机器。
 * ② decorView 的底色 —— edge-to-edge 之后系统栏是透明的，露出来的就是它。
 *    Capacitor 的 SystemBars 也是刷的这一层，所以必须先跑它、后跑我们。
 * ③ WebView 自己的底色 —— 页面没铺满时（橡皮筋回弹、窗口比内容高）露出来的是它，
 *    默认恒为白，黑夜模式下就是那道白边。
 * ④ WindowInsetsControllerCompat 的图标深浅 —— 和 SystemBars.setStyle 重复了一遍，
 *    是故意留的兜底：万一哪天那边改了行为，这儿还能自己站住。
 *
 * 只写了这一个方法。加了别的就会在 Capacitor.Plugins.Tint 上多出对应的 JS 入口。
 */
@CapacitorPlugin(name = "Tint")
public class TintPlugin extends Plugin {

    @PluginMethod
    public void set(PluginCall call) {
        String hex = call.getString("color", "#FFFFFF");
        final int c;
        try {
            c = Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            call.reject("颜色认不出来：" + hex);
            return;
        }
        // 颜色亮不亮决定图标画深色还是浅色。用现成的 luma 公式，不引 ColorUtils。
        final boolean lightBg = luma(c) > 0.6;

        if (getActivity() == null) { call.reject("拿不到窗口"); return; }
        getActivity().runOnUiThread(() -> {
            try {
                Window w = getActivity().getWindow();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    // Android 15 起这两句是废的（系统直接忽略），调了也只是白调，所以圈起来。
                    w.setStatusBarColor(c);
                    w.setNavigationBarColor(c);
                }
                w.getDecorView().setBackgroundColor(c);
                View web = getBridge() != null ? getBridge().getWebView() : null;
                if (web != null) web.setBackgroundColor(c);

                View decor = w.getDecorView();
                WindowInsetsControllerCompat ctl =
                    new WindowInsetsControllerCompat(w, decor);
                // 名字和外观是反的：setAppearanceLightXxxBars(true) 指的是"浅色背景"，
                // 落下去是**深色图标**。和 SystemBars 那档 LIGHT 是同一个意思。
                ctl.setAppearanceLightStatusBars(lightBg);
                ctl.setAppearanceLightNavigationBars(lightBg);
                call.resolve();
            } catch (Exception e) {
                call.reject("刷不上系统栏", e);
            }
        });
    }

    /** 0（全黑）到 1（全白）。就是 BT.709 那套权重，够用了。 */
    private static double luma(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }
}
