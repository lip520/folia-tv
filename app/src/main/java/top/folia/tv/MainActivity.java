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

public class MainActivity extends Activity {

    private static final String PREFS = "folia_tv";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_URL = "http://192.168.1.17:8080";

    private WebView webView;
    private String navScript;

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
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 方向键/确认键不拦截：透传给 WebView，由 Chromium 生成 trusted Arrow*/Enter
        // 键盘事件，驱动 Folia 页面自带的键盘导航（React 对 synthetic 事件不响应）。
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            showServerSetup(false);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // 优先让页面关闭弹层；无弹层则后退；再无可后退才退到桌面
        webView.evaluateJavascript(
                "window.tvNav ? tvNav.escape() : 'nav-missing'", value -> {
                    boolean handled = value != null && value.contains("\"handled\"");
                    if (handled) return;
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        moveTaskToBack(true);
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
