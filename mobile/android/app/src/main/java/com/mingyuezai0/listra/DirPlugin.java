package com.mingyuezai0.listra;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;

/**
 * 「选文件夹」在安卓上的那一半（第二十批第 1 项）。
 *
 * 网页那套 pickDirectory() 在安卓上**一定没反应**，而且不是坏在按钮上：
 * window.showDirectoryPicker 是 File System Access API 的一部分，
 * 安卓 WebView 里压根没有这个东西 —— 恒等于 undefined。
 * 原来那句 `if (!window.showDirectoryPicker){ toast("这个浏览器不支持…") }`
 * 本该兜住的，可用户看到的是"点击没反应"，说明连那条提示都没走到
 * （见 index.html 里 pickDirectory 的注释，那边把顺序改了）。
 *
 * 安卓这边的正门是 SAF：ACTION_OPEN_DOCUMENT_TREE 挑一棵目录树，
 * 系统回一个 tree URI，之后凭这棵树的授权就能一路读下去。
 *
 * 为什么不让 JS 自己遍历：WebView 里拿不到 DocumentFile，而且就算拿到了，
 * 一个 content:// 也变不成 <input type=file> 认的东西。所以由这边**只列清单**
 * （名字/大小/改动时间/每个文件的 content URI），字节让页面按需 fetch ——
 * Capacitor 的 WebViewLocalServer 把 https://localhost/_capacitor_content_/xxx
 * 转成 contentResolver.openInputStream(content://xxx)，流式读，不占内存。
 * 页面那边认这个前缀的地方是 contentUrl()。
 *
 * ⚠️⚠️ 改这个文件之前先看完（和 PipPlugin / SharePlugin 开头那几条同源）：
 * ① @PluginMethod 里抛出去的异常会把整个进程干掉（Bridge 那个 Runnable 末尾是
 *    throw new RuntimeException）。每个方法体都得自己 try/catch 干净。
 * ② startActivityForResult 走的是 AndroidX 的 ActivityResultLauncher，
 *    它要在主线程上调。插件方法跑在后台 HandlerThread 上，所以得 runOnUiThread。
 * ③ takePersistableUriPermission 之前，Intent 上必须先带
 *    FLAG_GRANT_PERSISTABLE_URI_PERMISSION —— 少了它，这个调用会直接抛
 *    SecurityException，而且是在**用户已经选完文件夹之后**才抛，最难查。
 * ④ 递归要有深度上限。软链接/自引用的目录树在 SAF 上是能造出来的，
 *    不封顶就是一次栈溢出。
 *
 * ⚠️ 插件名占的是 "Dir"。和 Share 一样是个通用词，将来撞上同名的要先改掉。
 */
@CapacitorPlugin(name = "Dir")
public class DirPlugin extends Plugin {

    /** 递归深度上限（见 ④）。正常的音乐目录不会超过五六层。 */
    private static final int MAX_DEPTH = 12;

    @PluginMethod
    public void pick(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            // ⚠️ 三个 flag 缺一不可：
            //   READ        读得到
            //   PERSISTABLE 才能在回调里 takePersistableUriPermission（见 ③）
            //   PREFIX      授权盖住整棵子树，不然只能读用户点中的那一层
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                     | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                     | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            final Activity act = getActivity();
            if (act == null) {
                call.reject("窗口不在，打不开文件夹选择器");
                return;
            }
            act.runOnUiThread(() -> {
                try {
                    startActivityForResult(call, i, "picked");
                } catch (Exception e) {
                    call.reject("打不开系统的文件夹选择器", e);
                }
            });
        } catch (Exception e) {
            call.reject("打不开系统的文件夹选择器", e);
        }
    }

    /** pick() 的回调。取消也 resolve（canceled:true）—— 用户按返回不是出错。 */
    @ActivityCallback
    private void picked(PluginCall call, ActivityResult result) {
        try {
            if (call == null) return;
            Uri tree = (result != null && result.getData() != null) ? result.getData().getData() : null;
            if (result == null || result.getResultCode() != Activity.RESULT_OK || tree == null) {
                JSObject out = new JSObject();
                out.put("canceled", true);
                call.resolve(out);
                return;
            }
            try {
                // 不 take 的话，这次选完能用，进程一重启授权就没了 —— 而这条授权
                // 本来就是"以后还要用"的意思，所以必须 take。
                getContext().getContentResolver()
                    .takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // 有些第三方 provider 不支持持久化。这次的读取照样能用，只是下次得重选。
            }
            JSObject out = new JSObject();
            out.put("canceled", false);
            out.put("uri", tree.toString());
            JSArray files = new JSArray();
            collect(tree, DocumentsContract.getTreeDocumentId(tree), files, 0);
            out.put("files", files);
            call.resolve(out);
        } catch (Exception e) {
            call.reject("读不了这个文件夹", e);
        }
    }

    /**
     * 把这棵树下所有**文件**列出来（目录本身不进清单，只往下走）。
     * 用 DocumentsContract 而不是 androidx.documentfile 的 DocumentFile：
     * 后者要多引一个依赖，而这几行查询本来就不长。
     */
    private void collect(Uri tree, String parentDocId, JSArray out, int depth) {
        if (depth > MAX_DEPTH) return;
        Cursor c = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
            ContentResolver cr = getContext().getContentResolver();
            c = cr.query(children, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                }, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name  = c.getString(1);
                String mime  = c.getString(2);
                long size    = c.isNull(3) ? 0L : c.getLong(3);
                long mod     = c.isNull(4) ? 0L : c.getLong(4);
                if (name == null || docId == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // 和网页那边 scanDirectory 一个口径：藏起来的和 node_modules 不进去
                    if (name.startsWith(".") || name.equals("node_modules")) continue;
                    collect(tree, docId, out, depth + 1);
                } else {
                    // ⚠️ 要的是**带着树**的那个 URI（buildDocumentUriUsingTree），
                    // 不是裸的 buildDocumentUri —— 只有带树的那份才继承了
                    // "整棵子树可读"这条授权。
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                    JSObject o = new JSObject();
                    o.put("name", name);
                    o.put("size", size);
                    o.put("lastModified", mod);
                    o.put("uri", doc.toString());
                    out.put(o);
                }
            }
        } catch (Exception ignored) {
            // 某一层读不动（权限只剩一半、provider 抽风）不该把整棵树废掉
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }
}
