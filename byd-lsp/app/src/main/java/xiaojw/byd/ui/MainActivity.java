package xiaojw.byd.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

import xiaojw.byd.core.BydConfig;
import xiaojw.byd.core.Ipc;

/**
 * 控制台入口：把 assets/webui/index.html 直接跑在 WebView 里。
 *
 * 页面本身是自包含的单文件（内联 CSS/JS，不请求任何远端资源），
 * 因此关掉跨域访问也不会影响功能。
 */
public final class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0E13"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);                     // 需要读取 file:///android_asset/
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);   // 页面不应访问任何远端
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new WebBridge(this), "BYDModule");
        setContentView(web);
        web.loadUrl("file:///android_asset/webui/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台：把当前配置推给微信进程。
        // 模块可能刚随微信重启，此时它手上还没有配置。
        String cfg = AppStore.loadConfigJson(this);
        if (cfg == null || cfg.isEmpty()) cfg = BydConfig.defaults().toJson();
        AppStore.saveConfigJson(this, cfg);
        Ipc.toHook(this, Ipc.ACTION_CONFIG, cfg);

        // 通知页面「App 回到前台了」：握手误报的自愈、以及今天没签成功时的顺手补签
        // 都挂在这个钩子上。冷启动时页面还没加载完，这一次调用会落空，
        // 由页面自己的 init() 完成首次握手。
        if (web != null) {
            web.evaluateJavascript("window.BYD_onResume && window.BYD_onResume();", null);
        }

        // App 侧闹钟是「每个定时点唤醒一次 App 发出网请求」这条路的入口，
        // 打开 App 是重排它最自然、最可靠的时机（用户删过数据、系统清过闹钟都能纠正回来）
        AppAlarm.reschedule(this);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("BYDModule");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
