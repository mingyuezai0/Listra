package com.mingyuezai0.listra;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 「设置 → 文件位置」那颗按钮在安卓上落到这儿。
 *
 * 安卓把音视频本体存在应用私有目录里（/data/data/&lt;包名&gt;/app_webview/Default/
 * IndexedDB/...），那不是"某个文件夹"—— Android 11 起整个 Android/data 树对第三方
 * 应用关闭，任何文件管理器（连系统的都不行）都进不去。这是系统的限制，不是没做。
 *
 * 所以退一步：跳到系统的「应用信息」页。用户在那儿能看到占了多少空间、也能清数据。
 * 这是不申请任何权限就能做到的最接近"打开文件位置"的一件事。
 *
 * 只写了这一个方法。加了别的就会在 Capacitor.Plugins.AppInfo 上多出对应的 JS 入口。
 */
@CapacitorPlugin(name = "AppInfo")
public class AppInfoPlugin extends Plugin {

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", getContext().getPackageName(), null));
            // 从 WebView 里发起的，不带 NEW_TASK 在某些 ROM 上会直接抛 ActivityNotFound
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("打不开应用信息页", e);
        }
    }
}
