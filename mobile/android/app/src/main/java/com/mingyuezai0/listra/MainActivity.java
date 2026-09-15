package com.mingyuezai0.listra;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ⚠️ 必须在 super.onCreate 之前。那边会 load() 出 bridge，而给页面的那份
        // 插件 JS（window.Capacitor.Plugins.xxx）是建 bridge 的时候生成的 ——
        // 建完再加的插件，页面上根本看不到。
        registerPlugin(AppInfoPlugin.class);
        registerPlugin(PipPlugin.class);
        super.onCreate(savedInstanceState);
    }

    /**
     * 进/出小窗，系统只喊这一声（API 26 起）。
     * ⚠️ 这一声**不是** onResume/onPause 那对：进小窗时 Activity 是 paused 但还活着，
     *    onStop 不会来，Capacitor 的 Bridge.onPause 里也没有任何一句会去 pause WebView ——
     *    所以画面和声音都不会断。页面那边就靠这一声挂/摘 body.pip-on：
     *    小窗里不该看见的东西全靠它收掉。
     */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        // 多这一句 if 是因为 minSdk 24：API 24/25 上压根没有这个方法，真机上它也不会被调，
        // 但把这句 super 调用明明白白圈在版本判断里更省事。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        }
        PipPlugin p = pip();
        if (p != null) p.onModeChanged(isInPictureInPictureMode);
    }

    /** bridge 是 BridgeActivity 的 protected 字段；插件实例挂在它自己的 PluginHandle 上。 */
    private PipPlugin pip() {
        if (bridge == null) return null;
        PluginHandle h = bridge.getPlugin("Pip");
        if (h == null) return null;
        return (h.getInstance() instanceof PipPlugin) ? (PipPlugin) h.getInstance() : null;
    }
}
