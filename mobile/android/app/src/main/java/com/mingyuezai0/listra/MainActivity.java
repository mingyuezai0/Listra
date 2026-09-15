package com.mingyuezai0.listra;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ⚠️ 必须在 super.onCreate 之前。那边会 load() 出 bridge，而给页面的那份
        // 插件 JS（window.Capacitor.Plugins.xxx）是建 bridge 的时候生成的 ——
        // 建完再加的插件，页面上根本看不到。
        registerPlugin(AppInfoPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
