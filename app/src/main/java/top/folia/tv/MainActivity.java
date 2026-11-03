// Folia TV 主界面：全屏 WebView 壳 + 遥控器（D-pad）焦点导航注入
package top.folia.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

public class MainActivity extends Activity {

    private static final String PREFS = "folia_tv";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_URL = "http://192.168.1.19:8080";

    private WebView webView;
    private String navScript;

    // MediaSession：接收系统路由的媒体键（遥控器播放/暂停/上一首/下一首），
    // 回调转 JS 控制 <audio>；同时轮询页面播放状态更新 MediaSession 元数据。
    private MediaSession mediaSession;
    private final android.os.Handler pollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);
        // 允许 Mac 端 chrome://inspect 远程调试 WebView，调遥控器导航用
        WebView.setWebContentsDebuggingEnabled(true);

        setupWebView();
        navScript = readAsset("tvnav.js");

        // 首次启动直接用内置默认地址并记住；之后可按菜单键改地址
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = prefs.getString(KEY_SERVER_URL, null);
        if (url == null) {
            url = DEFAULT_URL;
            prefs.edit().putString(KEY_SERVER_URL, url).apply();
        }
        webView.loadUrl(url);
        setupMediaSession();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 滚动前预栅格化屏外内容，减少白块和滚动掉帧（投影仪 GPU 弱，收益明显）
        s.setOffscreenPreRaster(true);
        // 按桌面宽版式布局并缩放到整屏，避免 TV 上网页按超大视口排版
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " FoliaTV/0.1");
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 所有导航都留在壳内
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectNav();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
    }

    private void injectNav() {
        if (navScript != null) {
            webView.evaluateJavascript(navScript, null);
        }
        // 调试探针：把页面状态打到 logcat（tag: TvBridge）
        webView.evaluateJavascript(
                "setTimeout(function(){try{var b=document.body;var info={title:document.title," +
                "w:window.innerWidth,h:window.innerHeight,text:(b?b.innerText||'':'').slice(0,120)," +
                "children:b?b.children.length:-1,buttons:document.querySelectorAll('button').length," +
                "links:document.querySelectorAll('a').length,focusables:window.tvNav?1:0};" +
                "var r=document.getElementById('root')||document.getElementById('app');" +
                "console.log('TVPROBE '+JSON.stringify(info));" +
                "console.log('TVPROBE2 root='+(r?r.tagName+' kids='+r.children.length:'none')+' html='+document.body.innerHTML.slice(0,400));" +
                "}catch(e){console.log('TVPROBE ERR '+e)}},6000);", null);
    }

    // ==================== MediaSession ====================

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "FoliaTV");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { jsTogglePlay(true); }
            @Override public void onPause() { jsTogglePlay(false); }
            @Override public void onSkipToNext() { jsSkipTrack(true); }
            @Override public void onSkipToPrevious() { jsSkipTrack(false); }
        });

        // 初始状态：停止（无媒体）
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_NONE, 0, 0f)
                .build());
        mediaSession.setActive(true);

        // 轮询页面播放状态（2 秒间隔，平衡实时性与开销）
        pollRunnable = new Runnable() {
            @Override public void run() {
                pollMetadata();
                pollHandler.postDelayed(this, 2000);
            }
        };
        pollHandler.postDelayed(pollRunnable, 3000);
    }

    /** 播放/暂停：遍历媒体元素，优先暂停正在播放的，否则播放第一个就绪的。 */
    private void jsTogglePlay(boolean wantPlay) {
        webView.evaluateJavascript(
                "(function(){" +
                "var ms=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<ms.length;i++){var m=ms[i];" +
                "if(!m.src)continue;" +
                "if(!m.paused&&!m.ended){" + (wantPlay ? "" : "m.pause();") + "return;}" +
                "}" +
                (wantPlay
                        ? "for(var i=0;i<ms.length;i++){var m=ms[i];" +
                          "if(m.src&&m.duration>0&&m.paused){m.play();return;}}"
                        : "") +
                "})();", null);
    }

    /**
     * 上一首/下一首：Folia 的 usePlaybackInteractionBridge 监听 window keydown，
     * Ctrl+ArrowLeft/Right 切歌（仅 player 视图生效）。synthetic 事件可达 window 监听器。
     */
    private void jsSkipTrack(boolean next) {
        String key = next ? "ArrowRight" : "ArrowLeft";
        int code = next ? 39 : 37;
        webView.evaluateJavascript(
                "window.dispatchEvent(new KeyboardEvent('keydown',{" +
                "key:'" + key + "',code:'" + key + "',keyCode:" + code + ",which:" + code + "," +
                "ctrlKey:true,bubbles:true,cancelable:true}));", null);
    }

    /**
     * 轮询页面播放状态 + 歌曲元数据，回传原生更新 MediaSession。
     * 歌曲名优先读 Web Media Session 的 metadata（Folia 会写，但投影仪 WebView 没有这个 API，
     * 实际只会走到 document.title 兜底；元数据在这类设备上不可见，能显示歌名的桌面浏览器才用得上）。
     */
    private void pollMetadata() {
        webView.evaluateJavascript(
                "(function(){" +
                "try{" +
                "var title='',artist='';" +
                "try{" +
                "var md=navigator.mediaSession&&navigator.mediaSession.metadata;" +
                "if(md){title=md.title||'';artist=md.artist||'';}" +
                "}catch(e){}" +
                "var ms=document.querySelectorAll('audio,video');" +
                "var cur=null;" +
                "for(var i=0;i<ms.length;i++){var m=ms[i];" +
                "if(!m.src)continue;" +
                "if(!m.paused&&!m.ended){cur=m;break;}" +
                "if(!cur&&m.duration>0)cur=m;}" +
                "if(!title&&!cur)return JSON.stringify({hasMedia:false});" +
                "return JSON.stringify({" +
                "hasMedia:true," +
                "playing:cur?!cur.paused:false," +
                "position:cur?Math.round(cur.currentTime*1000):0," +
                "duration:(cur&&cur.duration>0)?Math.round(cur.duration*1000):0," +
                "title:title||document.title||'Folia'," +
                "artist:artist" +
                "});" +
                "}catch(e){return JSON.stringify({hasMedia:false});}" +
                "})()",
                value -> {
                    if (value == null || value.equals("null")) return;
                    // evaluateJavascript 返回值带引号，去掉外层引号和转义
                    String json = value;
                    if (json.startsWith("\"") && json.endsWith("\"")) {
                        json = json.substring(1, json.length() - 1);
                        json = json.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
                    }
                    updateMediaSession(json);
                });
    }

    private void updateMediaSession(String json) {
        if (mediaSession == null) return;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            boolean hasMedia = obj.optBoolean("hasMedia", false);
            if (!hasMedia) {
                mediaSession.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SKIP_TO_NEXT
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(PlaybackState.STATE_NONE, 0, 0f)
                        .build());
                return;
            }
            boolean playing = obj.optBoolean("playing", false);
            long position = obj.optLong("position", 0);
            long duration = obj.optLong("duration", 0);
            String title = obj.optString("title", "Folia");
            String artist = obj.optString("artist", "");

            // 更新播放状态
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            position, playing ? 1f : 0f)
                    .build());

            // 更新元数据
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
            if (duration > 0) {
                mb.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            }
            mediaSession.setMetadata(mb.build());

        } catch (Exception e) {
            // JSON 解析失败，忽略
        }
    }

    private String readAsset(String name) {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
    }

    private void showServerSetup(final boolean firstRun) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_URL));
        input.setPadding(48, 32, 48, 32);
        // 坚果固件把 Theme_Material_Dialog 渲成白底，深色主题的白字直接隐形，
        // 显式设深色文字（uiautomator 实测文字一直在、只是看不见）
        input.setTextColor(0xFF111111);
        input.setHintTextColor(0xFF888888);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("Folia 服务地址")
                .setMessage("输入已部署的 Folia Web 版地址")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("连接", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_SERVER_URL, url).apply();
                    webView.loadUrl(url);
                })
                .setNegativeButton(firstRun ? "退出" : "取消", (d, w) -> {
                    if (firstRun) finish();
                })
                .show();
    }

    // 长按检测不能靠 onKeyLongPress：dispatchKeyEvent 全量消费 MENU 后，
    // KeyEvent.dispatch/DispatcherState 走不到，onKeyLongPress 实际从不触发。
    // 也不能用标志位：按住超过内核 autorepeat 间隔（坚果上约 1.5s）后，
    // 重复的 ACTION_DOWN 会把标志重置，松手误判成短按（实测面板上叠出设置框）。
    // 可靠做法：记录按下时刻，松手时用实际按压时长判定。
    private long menuDownTime = 0;
    private static final long MENU_LONG_PRESS_MS = 450;
    private final android.os.Handler menuHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable menuLongPressRunnable = this::openCommandPalette;

    private void openCommandPalette() {
        // 长按菜单键 → 打开 Folia 命令面板（synthetic Ctrl+K，
        // Folia 命令面板监听 window keydown，不区分 trusted/synthetic）
        webView.evaluateJavascript(
                "window.dispatchEvent(new KeyboardEvent('keydown',{" +
                "key:'k',code:'KeyK',keyCode:75,which:75,ctrlKey:true," +
                "bubbles:true,cancelable:true}));", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 方向键/确认键不拦截：透传给 WebView，由 Chromium 生成 trusted Arrow*/Enter
        // 键盘事件，驱动 Folia 页面自带的键盘导航（React 对 synthetic 事件不响应）。
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                // 重复的 DOWN（autorepeat）repeatCount>0，落到末尾统一忽略，
                // 不会重置按下时刻
                menuDownTime = android.os.SystemClock.uptimeMillis();
                menuHandler.postDelayed(menuLongPressRunnable, MENU_LONG_PRESS_MS);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                menuHandler.removeCallbacks(menuLongPressRunnable);
                boolean longPress = menuDownTime > 0
                        && android.os.SystemClock.uptimeMillis() - menuDownTime
                                >= MENU_LONG_PRESS_MS;
                if (!longPress && !event.isCanceled()) {
                    // 短按菜单键（未触发长按）→ 服务地址设置
                    showServerSetup(false);
                }
                menuDownTime = 0;
                return true;
            }
            return true;
        }
        // 媒体键（播放/暂停/上一首/下一首）由 MediaSession.Callback 接收，
        // 不在 dispatchKeyEvent 里拦截——系统会把媒体键路由给活跃的 MediaSession。
        return super.dispatchKeyEvent(event);
    }

    // 菜单键 UP 已在 dispatchKeyEvent 处理，不再走 onKeyUp

    @Override
    public void onBackPressed() {
        // 返回键 → trusted Escape 透传给页面：Folia 自己的键盘桥会处理
        // 关弹层/命令面板/退出全屏播放视图（React 只认 trusted 事件，
        // 此前 tvNav.escape() 派发的 synthetic 事件到不了业务层，实测无效）。
        //
        // 但「页面收到 ESC」≠「页面处理了 ESC」：顶层主页也会收到但不做任何事。
        // 所以先用 MutationObserver 观察 ESC 之后 450ms 内有没有节点增删：
        //   - 有 → 页面真的关了某个层/切了视图 → 留在 App
        //   - 无 → 页面无事可做 → 后退或退桌面
        webView.evaluateJavascript(
                "window.__tvEscDirty=false;" +
                "if(window.__tvEscObs)window.__tvEscObs.disconnect();" +
                "window.__tvEscObs=new MutationObserver(function(ms){" +
                "for(var i=0;i<ms.length;i++){var m=ms[i];" +
                "if((m.type==='childList'&&(m.addedNodes.length+m.removedNodes.length)>0)" +
                "||m.type==='attributes'){" +
                "window.__tvEscDirty=true;break;}}});" +
                "window.__tvEscObs.observe(document.documentElement," +
                "{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','aria-hidden','hidden']});'armed'",
                null);

        long now = android.os.SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));

        webView.postDelayed(() -> webView.evaluateJavascript(
                "window.__tvEscObs&&window.__tvEscObs.disconnect();String(!!window.__tvEscDirty)",
                value -> {
                    boolean pageConsumed = value != null && value.contains("true");
                    if (pageConsumed) return;
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        moveTaskToBack(true);
                    }
                }), 450);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        // App 后台时暂停轮询；MediaSession 保持活跃以接收锁屏媒体键
        pollHandler.removeCallbacks(pollRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        if (mediaSession != null) mediaSession.setActive(true);
        if (pollRunnable != null) pollHandler.postDelayed(pollRunnable, 1000);
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacks(pollRunnable);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
