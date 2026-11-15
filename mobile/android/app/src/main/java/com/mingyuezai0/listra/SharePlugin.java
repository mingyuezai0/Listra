package com.mingyuezai0.listra;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 「分享文件」在安卓上的那一半。模板是隔壁那颗 AppInfoPlugin。
 *
 * 页面把文件本体存在 IndexedDB 里，Java 这头够不着 —— IndexedDB 是 WebView 的
 * 私有目录下一堆 LevelDB（见 AppInfoPlugin 的注释），从外面读等于自己实现一个
 * LevelDB。所以只能让 JS 把字节喂过来，这边收成一个临时文件，再交给系统去分享。
 *
 * 为什么是**三个方法**而不是一个大方法：喂进来的字节只能是 base64 字符串，
 * 而一部两小时的片子好几个 G，让 JS 拼成一整个大串再递进来，页面当场就爆了。
 * 所以拆成 begin（开文件）/ chunk（喂一片）/ finish（落地并弹选择器），
 * 每片 1 MB 原文。⚠️ 顺序有保证：Capacitor 的插件方法全跑在同一个后台
 * HandlerThread 上，是串行的；JS 那边也是一片 await 完再喂下一片。
 *
 * ⚠️⚠️ 四条会要命的，改这个文件之前先看完（和 PipPlugin 开头那四条同源）：
 * ① @PluginMethod 里抛出去的异常会把整个进程干掉。Bridge.callPluginMethod 那个
 *    Runnable 末尾是 `catch (Exception ex) { throw new RuntimeException(ex); }`，
 *    跑在 HandlerThread 上 —— 线程上没人接的异常 = 进程死。
 *    所以每个方法体都得自己 try/catch 干净，一句都不能漏在外面。
 * ② startActivity / AlertDialog 这类是 UI 线程的活儿，要 runOnUiThread 回去。
 *    在后台线程上调不一定崩，但可能**静默失败** —— "按了没反应"最难查。
 * ③ 文件一定放在 cacheDir 里。放 filesDir 的话用户清缓存清不掉，
 *    一部片子的副本就永远躺在那儿了。系统清 cacheDir 是随时的事，正合适。
 * ④ 每次 begin 都要顺手清掉过期的旧副本。不然分享十次就攒十份副本，
 *    而且都是几百兆的。但**不能**无条件清空：上一份可能正被外部播放器读着
 *    （见 clearOld，只按 CLEAR_TTL_MS 删老的）。
 *
 * ⚠️ 插件名占的是 "Share" 这个通用词。@capacitor/share 没有装，
 *    哪天真装了得先把这个改掉 —— 两个同名插件在 window.Capacitor.Plugins 下会打架。
 */
@CapacitorPlugin(name = "Share")
public class SharePlugin extends Plugin {

    /** 收到的每一片都落在这个目录下。纯 ASCII，免得某些 ROM 的文件名编码出岔子。 */
    private static final String DIR = "listra-share";

    /** 副本最长留 24 小时（第二十一批第 7 项）。 */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    /**
     * begin 里那次顺手清理的期限：12 小时，比 sweep() 的 24 小时短一截。
     * 为什么不是"全删"：上一份分享出去的文件可能正被外部播放器读着
     * （A 分享给播放器、再分享 B —— 无条件清空就把 A 正在读的副本抽走了，
     * 播放器那边表现为"文件没了"）。所以只清**明显过期**的，最近这几小时的不动。
     */
    private static final long CLEAR_TTL_MS = 12L * 60 * 60 * 1000;

    private File target = null;      // 正在收的那一份
    private OutputStream os = null;
    private String mime = "application/octet-stream";

    private File shareDir() {
        File d = new File(getContext().getCacheDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 超过 24 小时的副本自动清掉（第二十一批第 7 项）。
     * 光靠 begin 里那次 clearOld 不够：分享完就把软件扔一边的用户，
     * 那份几百兆的副本会一直躺在 cacheDir 里 —— 系统清缓存是"看它自己心情"的，
     * 不能指望。所以每次进前台都扫一遍。
     * ⚠️ 只按 lastModified 判，不看有没有别的应用正读着 —— 24 小时够长了，
     *    真有人隔一天还在播那个分享出去的文件，那已经不是"临时副本"的用法。
     */
    private void sweep() {
        try {
            File[] fs = shareDir().listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                if (now - f.lastModified() > TTL_MS) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
            // 扫不掉就扫不掉，下一趟再来
        }
    }

    @Override
    public void load() {
        super.load();
        sweep();
    }

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        sweep();
    }

    /**
     * 清一批过期的副本（按时间清，不是清空）。删不掉也不算错，下次 begin 还会再试。
     * ⚠️ 只删 lastModified 早于 CLEAR_TTL_MS 的：用户刚分享出去的那份
     *    很可能还在别的播放器里读着，删了就是"文件没了"。
     */
    private void clearOld() {
        closeQuietly();
        File d = shareDir();
        File[] fs = d.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            // 本次正在用的那份不动（同名覆盖时可能就是它自己）
            if (target != null && f.equals(target)) continue;
            if (now - f.lastModified() > CLEAR_TTL_MS) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private void closeQuietly() {
        if (os != null) {
            try {
                os.close();
            } catch (Exception ignored) {
            }
            os = null;
        }
    }

    /**
     * ⚠️ 文件名是**页面递进来的**，直接用就等于把路径交给页面管：带上 "/" 会往
     * 下一层目录写，".." 能写到分享目录外面去。页面那边已经洗过一遍
     * （index.html 的 safeShareName），这儿再兜一次 —— 两道闸不为过，
     * 这条路上写出去的东西最后是要交给别的应用执行的。
     */
    private static String safeName(String raw) {
        String n = (raw == null ? "" : raw).replaceAll("[\\\\/]+", "_");
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) n = n.substring(0, 120);
        return n.isEmpty() ? "audio" : n;
    }

    @PluginMethod
    public void begin(PluginCall call) {
        try {
            clearOld();
            String name = safeName(call.getString("name"));
            String m = call.getString("mime");
            mime = (m == null || m.isEmpty()) ? "application/octet-stream" : m;
            target = new File(shareDir(), name);
            os = new FileOutputStream(target);
            call.resolve();
        } catch (Exception e) {
            closeQuietly();
            target = null;
            call.reject("腾不出地方放这份文件", e);
        }
    }

    @PluginMethod
    public void chunk(PluginCall call) {
        try {
            if (os == null) {
                call.reject("还没开始收");
                return;
            }
            String data = call.getString("data");
            if (data == null || data.isEmpty()) {
                call.resolve();
                return;
            }
            // DEFAULT 而不是 NO_WRAP：这边解的是页面用 btoa 出来的标准 base64，
            // 中间本来就不带换行；DEFAULT 的解码器两种都吃，容错更宽。
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            os.write(bytes);
            call.resolve();
        } catch (Exception e) {
            closeQuietly();
            call.reject("这一片写不进去", e);
        }
    }

    @PluginMethod
    public void finish(PluginCall call) {
        final File f;
        try {
            if (os == null || target == null) {
                call.reject("还没开始收");
                return;
            }
            os.flush();
            closeQuietly();
            f = target;
            target = null;
        } catch (Exception e) {
            closeQuietly();
            call.reject("这份文件没能落盘", e);
            return;
        }
        // 权限要在**读**这一头授：我们写、对方读，所以是我们往外给读权限。
        // exported="false" 的 provider 只有配上这个 flag，接收方那个进程才打得开。
        final String authority = getContext().getPackageName() + ".fileprovider";
        final Activity act = getActivity();
        if (act == null) {
            call.reject("窗口不在，弹不出分享面板");
            return;
        }
        /* ⚠️ runOnUiThread 这一**句**本身也得在 try 里 —— 窗口正在销毁的时候它会抛
           （"Can't create handler inside thread" / Activity 已 detach），
           而这儿是 @PluginMethod 的方法体，抛出去就是进程死。
           里面那个 lambda 的 try 只管 lambda 自己，管不到这一句。 */
        try {
            act.runOnUiThread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(getContext(), authority, f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    // 从 WebView 里发起的，不带 NEW_TASK 在部分 ROM 上会直接抛 ActivityNotFound
                    Intent chooser = Intent.createChooser(send, "分享到");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContext().startActivity(chooser);
                    call.resolve();
                } catch (Exception e) {
                    call.reject("分享面板弹不出来", e);
                }
            });
        } catch (Exception e) {
            call.reject("分享面板弹不出来", e);
        }
    }
}
