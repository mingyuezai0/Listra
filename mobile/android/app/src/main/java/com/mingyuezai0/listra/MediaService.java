package com.mingyuezai0.listra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import java.lang.ref.WeakReference;

/**
 * 通知栏 / 锁屏上那条播放控制。模板是隔壁那颗 PipPlugin 和 AppInfoPlugin。
 *
 * 为什么非得有原生这一半：页面里那套 navigator.mediaSession 在 WebView 里**什么都不会发生**
 * —— 那是浏览器 UI 层接的线，WebView 没有那一层。所以在这台设备上，
 * 之前那套代码一行都没生效过，表现就是"通知栏里什么都没有"。
 *
 * ⚠️⚠️ 四条会要命的，改这个文件之前先看完：
 * ① **不用 media3**。整个工程零第三方依赖，media3 会拽进来一长串。
 *    这里用的全是框架自带的 android.media.session.MediaSession（API 21 起），
 *    配 Notification.MediaStyle，够用。
 * ② 服务里抛出去的异常没人接。onStartCommand / 回调里全部裹 try —— 见下面每一处。
 *    ⚠️ 图片解码要接 Throwable 不是 Exception：解一张大图能 OOM，而 OutOfMemoryError
 *    是 Error 不是 Exception，漏掉它这个进程就没了。
 * ③ 元数据/状态**不走 Intent 传**，走下面的静态字段。原因是 Binder 事务上限 1MB，
 *    而封面 base64 轻松几十万字符 —— 塞进 Intent 就是一次 TransactionTooLargeException。
 *    同一个进程里的两个组件，静态字段本来就够用。它们全部 volatile：
 *    插件方法跑在后台线程、服务读在主线程，double 在 32 位上还不保证原子写。
 * ④ 开启那条路必须 startForegroundService + 5 秒内 startForeground。
 *    只 startService 的话，API 26 以上后台一会儿就被掐，通知跟着消失。
 *    A_ON 一进来就 post()，post() 里第一件事就是 startForeground，够快。
 */
public class MediaService extends Service {

    private static final String CH_ID = "tingyu.playback";
    private static final int NOTIF_ID = 7710;

    static final String A_ON    = "com.mingyuezai0.listra.MEDIA_ON";
    static final String A_OFF   = "com.mingyuezai0.listra.MEDIA_OFF";
    static final String A_META  = "com.mingyuezai0.listra.MEDIA_META";
    static final String A_STATE = "com.mingyuezai0.listra.MEDIA_STATE";
    static final String A_CMD   = "com.mingyuezai0.listra.MEDIA_CMD";

    /* ---- 插件 → 服务：当前该显示什么（见类注释 ③） ---- */
    static volatile String  sTitle    = "";
    static volatile String  sArtist   = "";
    static volatile String  sAlbum    = "";
    static volatile String  sArt      = null;   // 封面 base64，不带 data: 前缀
    static volatile double  sDuration = 0;
    static volatile boolean sPlaying  = false;
    static volatile double  sPosition = 0;
    static volatile double  sSpeed    = 1;

    /** 服务 → 页面那条路只有这一根线：notifyListeners 是 protected，外面够不着。 */
    private static WeakReference<MediaPlugin> plugin = new WeakReference<>(null);
    static void attach(MediaPlugin p){ plugin = new WeakReference<>(p); }
    private static MediaPlugin plugin(){ return plugin.get(); }

    private MediaSession session;
    private boolean on = false;
    private boolean fg = false;

    // 本地快照。每次 post() 前由 syncStatics() 从上面那组静态字段抄一遍 ——
    // 这样解码封面、拼通知这些事都只碰本地字段，不用满篇 volatile。
    private String title = "", artist = "", album = "";
    private double duration = 0, position = 0, speed = 1;
    private boolean playing = false;
    private Bitmap art = null;
    private String artKey = null;               // 上次解码用的那串，一样就不重复解

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null){
                // IMPORTANCE_LOW：一条播放控制不该"叮"一声。锁屏上一样看得见。
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            session = new MediaSession(this, "tingyu");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                           | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(cb);
            session.setSessionActivity(contentIntent());
        } catch (Exception e){
            // 建不起来就当没有 —— 页面那边还有网页版那一套兜着，不该因此崩掉
        }
    }

    /* 耳机线控 / 蓝牙 / 锁屏，走的是回调；通知栏那三颗按钮也绕到这儿来（见 cmd()），
       于是所有入口只有这一条路，不会出现"通知栏能暂停、耳机不能"这种分叉。
       回调默认就落在建 session 的那个线程 —— onCreate 是主线程，所以这里是主线程。 */
    private final MediaSession.Callback cb = new MediaSession.Callback() {
        @Override public void onPlay()          { toPage("play", -1); }
        @Override public void onPause()         { toPage("pause", -1); }
        @Override public void onStop()          { toPage("stop", -1); }
        @Override public void onSkipToNext()    { toPage("next", -1); }
        @Override public void onSkipToPrevious(){ toPage("prev", -1); }
        @Override public void onSeekTo(long pos){ toPage("seek", pos); }
    };

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        try {
            if (it != null){
                String a = it.getAction();
                if (A_ON.equals(a))              setOn(true);
                else if (A_OFF.equals(a))        setOn(false);
                else if (A_META.equals(a))       { syncStatics(); post(); }
                else if (A_STATE.equals(a))      { syncStatics(); post(); }
                else if (A_CMD.equals(a))        cmd(it.getStringExtra("cmd"));
            }
        } catch (Exception e){
            // 服务里没人接的异常就是一次崩溃，这一层是最后的兜底
        }
        // 别自己复活：页面没了，留一条空通知杵在通知栏里没有任何意义
        return START_NOT_STICKY;
    }

    private void setOn(boolean want) {
        if (want){
            on = true;
            try { if (session != null) session.setActive(true); } catch (Exception e){}
            syncStatics();
            post();
            return;
        }
        on = false;
        fg = false;
        try { if (session != null) session.setActive(false); } catch (Exception e){}
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception e){}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception e){}
        stopSelf();
    }

    /** 通知栏那三颗按钮点下来。绕一圈回到 session 的回调，和耳机线控同一条路。 */
    private void cmd(String c) {
        try {
            if (c == null || session == null) return;
            // ⚠️ TransportControls 挂在 **MediaController** 上，不是 MediaSession 上
            //    （MediaSession.TransportControls 这个类压根不存在，编译期才炸）
            MediaController.TransportControls t = session.getController().getTransportControls();
            if ("play".equals(c))       t.play();
            else if ("pause".equals(c)) t.pause();
            else if ("next".equals(c))  t.skipToNext();
            else if ("prev".equals(c))  t.skipToPrevious();
        } catch (Exception e){}
    }

    /** 把静态字段抄进本地，顺带按需解一次封面。 */
    private void syncStatics() {
        title = sTitle; artist = sArtist; album = sAlbum;
        duration = sDuration; playing = sPlaying; position = sPosition; speed = sSpeed;

        String want = sArt;
        if (want == null || want.isEmpty()){
            art = null; artKey = null;
        } else if (!want.equals(artKey)){
            artKey = want;
            art = decode(want);
        }
    }

    /**
     * ⚠️ 先量尺寸再按 inSampleSize 解 —— 直接 decodeByteArray 一张 4000×4000 的图
     * 就是几十兆，手机上必 OOM，而 OOM 在服务里等于整个进程没了（见类注释 ②）。
     * 通知栏那个大图标撑死显示到 512 上下，再大纯属浪费。
     */
    private Bitmap decode(String b64) {
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int big = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (big / sample > 512) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
        } catch (Throwable t){
            // ⚠️ Throwable 不是 Exception：OOM 是 Error，漏掉它进程就没了
            return null;
        }
    }

    /** 把当前这一份状态推给系统：session 的元数据/播放态 + 那条通知。 */
    private void post() {
        if (!on) return;
        try {
            if (session != null){
                MediaMetadata.Builder m = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (duration * 1000));
                if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
                session.setMetadata(m.build());

                session.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                  | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                                  | PlaybackState.ACTION_SKIP_TO_NEXT
                                  | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                  | PlaybackState.ACTION_SEEK_TO)
                        .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                  (long) (position * 1000), (float) speed)
                        .build());
            }

            Notification n = build();
            if (n == null) return;
            if (!fg){
                // ⚠️ API 34 起必须报类型，且清单里得有 FOREGROUND_SERVICE_MEDIA_PLAYBACK，
                //    否则这一句直接抛。类型是 29 才有的常量，得判版本。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                fg = true;
            } else {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, n);
            }
        } catch (Exception e){
            // 起不来前台服务也不能崩。真起不来的话通知栏就是空的，别的照旧。
        }
    }

    private Notification build() {
        try {
            if (session == null) return null;
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            String sub = artist.isEmpty() ? album : (album.isEmpty() ? artist : artist + " · " + album);
            b.setSmallIcon(android.R.drawable.ic_media_play)   // 框架自带，单色，正好是播放器的样子
             .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
             .setContentText(sub)
             .setContentIntent(contentIntent())
             .setOngoing(playing)          // 放着的时候别让人手滑划掉；暂停了就允许划走
             .setShowWhen(false)
             .setVisibility(Notification.VISIBILITY_PUBLIC);
            if (art != null) b.setLargeIcon(art);
            b.addAction(action(android.R.drawable.ic_media_previous, "上一首", "prev"));
            b.addAction(playing
                    ? action(android.R.drawable.ic_media_pause, "暂停", "pause")
                    : action(android.R.drawable.ic_media_play,  "播放", "play"));
            b.addAction(action(android.R.drawable.ic_media_next, "下一首", "next"));
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
            return b.build();
        } catch (Exception e){
            return null;
        }
    }

    private Notification.Action action(int icon, String title, String cmd) {
        Intent i = new Intent(this, MediaService.class).setAction(A_CMD).putExtra("cmd", cmd);
        PendingIntent pi = PendingIntent.getService(this, cmd.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build();
    }

    /** 点通知本体：把软件叫回前台（singleTask + REORDER_TO_FRONT，和小窗那颗一样）。 */
    private PendingIntent contentIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 把动作递回页面。**自己不动播放** —— 播放那头在 WebView 里，
     * 页面收到之后该怎么走还怎么走（换下一首、记播放次数、落盘位置都在那边）。
     * 这里要是也改一次状态，两边就会打架。
     */
    private void toPage(String cmd, long posMs) {
        try {
            MediaPlugin p = plugin();
            if (p != null) p.onAction(cmd, posMs);
        } catch (Exception e){}
    }

    @Override
    public void onDestroy() {
        try {
            if (session != null){
                session.setActive(false);
                session.release();
            }
        } catch (Exception e){}
        session = null;
        art = null;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception e){}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
