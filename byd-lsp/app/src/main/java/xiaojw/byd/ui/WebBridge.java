package xiaojw.byd.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import xiaojw.byd.core.BydConfig;
import xiaojw.byd.core.Ipc;

/**
 * WebUI 与原生层的桥。
 *
 * 控制台页面（assets/webui/index.html）检测到 window.BYDModule 时走真实通道，
 * 否则退回「演示模式」——同一份页面既能装进 APK 用，也能单独放到浏览器里看。
 */
public final class WebBridge {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context app;

    public WebBridge(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    /* ==================== 状态 ==================== */

    /** 状态快照：版本、实例列表、运行态、版本适配状态，以及当前生效的配置 */
    @JavascriptInterface
    public String getState() {
        return AppStore.loadStateJson(app);
    }

    /** 模块侧是否正在签到（由广播回写的状态快照里带过来） */
    @JavascriptInterface
    public boolean isRunning() {
        try {
            return new JSONObject(AppStore.loadStateJson(app)).optBoolean("running", false);
        } catch (Exception e) {
            return false;
        }
    }

    /* ==================== 配置读写 ==================== */

    /**
     * 读取当前配置文件（已格式化，便于「配置」页直接编辑）。
     *
     * 这里返回的 versionTable 是**内置表与控制台覆盖层合并后的完整表** ——
     * 也就是「一份配置文件装下全部」：设置项 + 完整版本表。
     * 之前只回传覆盖层，用户在配置文件里看不到内置的版本，容易以为没生效。
     */
    @JavascriptInterface
    public String getConfigText() {
        String cfg = AppStore.loadConfigJson(app);
        if (cfg == null || cfg.isEmpty()) cfg = BydConfig.defaults().toJson();
        try {
            JSONObject o = new JSONObject(cfg);
            JSONObject builtin = parseOrEmpty(readAsset());
            JSONObject override = parseOrEmpty(AppStore.loadVersionTable(app));
            JSONObject merged = merge(builtin, override);
            o.put("versionTable", merged);
            return o.toString(2);
        } catch (Exception e) {
            return cfg;
        }
    }

    /** 保存配置文件：范围收紧 → 本地落盘 → 立即推给微信进程 */
    @JavascriptInterface
    public String saveConfigText(String text) {
        if (text == null || text.trim().isEmpty()) return "配置为空";
        try {
            JSONObject in = new JSONObject(text);
            // 编辑器（或旧版客户端）把版本表写成 null / 空对象时，一律保留已有的一份 ——
            // 版本表是探测的成果，不能被一次「保存配置」清空。
            // 真正的清空走 resetVersionTable()，那条路径不过这里。
            if (!in.has("versionTable") || in.isNull("versionTable")
                    || in.optJSONObject("versionTable") == null
                    || in.optJSONObject("versionTable").length() == 0) {
                String cur = AppStore.loadVersionTable(app);
                if (!cur.isEmpty()) in.put("versionTable", new JSONObject(cur));
                else in.remove("versionTable");
            }
            // 只就地收紧已知的数值字段，其余键原样保留 ——
            // 之前走 BydConfig.fromJson(...).toJson() 会把模型不认识的键（例如
            // wakeBeforeCode）悄悄丢掉，于是「改了配置却像没保存」。
            clampInPlace(in);
            String normalized = in.toString();
            AppStore.saveConfigJson(app, normalized);
            Ipc.toHook(app, Ipc.ACTION_CONFIG, normalized);
            return "OK";
        } catch (Exception e) {
            return "JSON 解析失败: " + e.getMessage();
        }
    }

    /** 配置文件的绝对路径，展示给用户看 */
    @JavascriptInterface
    public String getConfigFilePath() {
        return AppStore.configFilePath(app);
    }

    /** 从配置文件重新加载并生效（用户用文件管理器改过之后用） */
    @JavascriptInterface
    public String reloadFromConfigFile() {
        String text = AppStore.readConfigFile(app);
        if (text == null) return "配置文件不存在或读不到";
        return saveConfigText(text);
    }

    /** 兼容旧调用（设置页逐项保存时用） */
    @JavascriptInterface
    public void saveConfig(String json) {
        String r = saveConfigText(json);
        if (!"OK".equals(r)) return;
    }

    /* ==================== 模块注入状态（握手） ==================== */

    /**
     * 主动握手：让微信进程里的模块回话。
     *
     * 这是判断「LSPosed 的勾选是否真的生效」的唯一可靠办法 ——
     * App 自己的 WebView 里始终存在 window.BYDModule（那是 MainActivity 注入的），
     * 只能说明页面跑在 APK 里，和模块有没有被加载毫无关系。
     *
     * @param nonce 由页面生成，模块会在应答里原样带回，用来确认这次应答就是本次请求的
     */
    @JavascriptInterface
    public boolean pingModule(String nonce) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", Ipc.CMD_PING);
            o.put("arg", nonce == null ? "" : nonce);
            Ipc.toHook(app, Ipc.ACTION_CMD, o.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }



    /**
     * 把微信唤醒并切到前台。
     *
     * 取码依赖微信自己的登录流程真正跑起来。微信在后台时这个流程可能长时间不动 ——
     * byd_sign 的 lib.sh 里写得很清楚：「微信进程存在但后台休眠，导致 get_code 服务无响应」，
     * 它的解法是 `am startservice .booter.CoreService` + `am start`，都要 root。
     *
     * 模块跑在微信进程里，从后台启动 Activity 会被系统拦截；但 App 自己在前台，
     * 由 App 来做这两件事是允许的：
     *   1. 先试 startService 微信的 CoreService（对应 am startservice，未导出就失败，忽略）
     *   2. 再 startActivity 微信启动页，保证进程真的到前台并处于可交互状态
     */
    @JavascriptInterface
    public boolean bringWechatToFront() {
        boolean ok = false;
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName(Ipc.PKG_WECHAT, "com.tencent.mm.booter.CoreService"));
            app.startService(svc);
            ok = true;
        } catch (Throwable t) {
            // CoreService 多数版本不对外导出，起不来是正常的
        }
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(Ipc.PKG_WECHAT);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                app.startActivity(launch);
                ok = true;
            }
        } catch (Throwable t) {
            // 保留上一步的结果
        }
        return ok;
    }

    /**
     * 索取模块**当前实际生效**的配置。
     * 用来回答「我改的配置到底有没有写进模块」—— 之前只能靠日志推断。
     */
    @JavascriptInterface
    public boolean requestModuleConfig() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", Ipc.CMD_GET_CONFIG);
            o.put("arg", "");
            Ipc.toHook(app, Ipc.ACTION_CMD, o.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String getModuleConfig() {
        return AppStore.loadModuleConfig(app);
    }

    /** 最近一次模块应答（含时间与实例信息） */
    @JavascriptInterface
    public String moduleStatus() {
        return AppStore.loadModuleStatus(app);
    }

    /**
     * 由 App 进程直接补签一次（不走模块的网络，取码仍找模块要）。
     *
     * 「打开 App 时今天还没签成功」就走这条路：此时人在手机前、屏幕亮着、
     * 网络是通的 —— 正好补上模块后台签到最缺的那个条件。
     * slot 传 -1 表示「不管哪个定时点，先把今天的签掉」。
     */
    @JavascriptInterface
    public boolean appSideSign() {
        if (appSignRunning) return false;
        appSignRunning = true;
        new Thread(() -> {
            try {
                AppSigner.run(app, -1);
            } catch (Throwable t) {
                AppStore.appSideLog(app, "ERR", "App 侧补签异常：" + t.getMessage());
            } finally {
                appSignRunning = false;
            }
        }, "byd-lsp-appsign-now").start();
        return true;
    }

    private volatile boolean appSignRunning = false;

    /* ==================== App 侧闹钟 ==================== */

    /** 设置页显示：下一次闹钟、精确闹钟授权状态、上一次执行结果 */
    @JavascriptInterface
    public String getAlarmStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("next", AppAlarm.describe(app));
            o.put("exact", AppAlarm.canExact(app));
            o.put("last", AppAlarm.lastState(app));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 打开系统设置允许本应用使用精确闹钟。
     * 没有这个授权也不会失效 —— AppAlarm 会自动退化成 setAndAllowWhileIdle。
     */
    @JavascriptInterface
    public boolean requestExactAlarm() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 31) return false;
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(android.net.Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 版本适配 ==================== */

    /**
     * 版本适配状态。
     *
     * builtin  —— 打包进 APK 的版本表（assets/wxcode_versions.json）
     * override —— 配置里保存的覆盖层（含运行时 dex 探测生成的新版本）
     * effective—— 两者合并后的实际生效表
     * versions —— 生效表里所有版本号，供配置页列出
     */
    @JavascriptInterface
    public String getVersionStatus() {
        try {
            JSONObject builtin = parseOrEmpty(readAsset());
            JSONObject override = parseOrEmpty(AppStore.loadVersionTable(app));
            JSONObject effective = merge(builtin, override);

            JSONObject probe = parseOrEmpty(AppStore.loadProbeStatus(app));
            String state = parseOrEmpty(AppStore.loadStateJson(app)).optString("version", "");

            JSONObject out = new JSONObject();
            out.put("version", probe.optString("version", state));
            out.put("exact", probe.optBoolean("exact", parseOrEmpty(AppStore.loadStateJson(app)).optBoolean("versionExact", false)));
            out.put("state", probe.optString("state", "idle"));
            out.put("note", probe.optString("note", ""));
            out.put("reason", probe.optString("reason", ""));
            out.put("steps", probe.optString("steps", ""));
            out.put("builtin", builtin);
            out.put("override", override);
            out.put("effective", effective);
            out.put("versions", versionList(effective));
            return out.toString();
        } catch (Exception e) {
            return "{\"exact\":false,\"state\":\"idle\",\"note\":\"读取失败: " + e.getMessage() + "\"}";
        }
    }

    /** 触发一次本机 dex 探测，为当前微信版本生成适配配置 */
    @JavascriptInterface
    public boolean probeVersion() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", Ipc.CMD_PROBE_VERSION);
            o.put("arg", "");
            Ipc.toHook(app, Ipc.ACTION_CMD, o.toString());
            AppStore.saveProbeStatus(app, "{\"state\":\"running\",\"note\":\"已请求模块探测…\"}");
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 向模块索取**完整版本表**（内置 ⊕ 覆盖层 ⊕ 本机探测结果），收到后由 App 落盘。
     *
     * 模块探测成功后也会主动推一次，但那个时机是微信刚启动、App 通常不在，
     * 广播可能根本起不动 App —— 所以打开 App / 握手成功时主动拉一次更可靠。
     */
    @JavascriptInterface
    public boolean requestVersionTable() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", Ipc.CMD_GET_VERSION_TABLE);
            o.put("arg", "");
            Ipc.toHook(app, Ipc.ACTION_CMD, o.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 清空覆盖层，回到内置版本表 */
    @JavascriptInterface
    public void resetVersionTable() {
        try {
            BydConfig cfg = BydConfig.fromJson(AppStore.loadConfigJson(app));
            cfg.versionTable = "";
            AppStore.saveConfigJson(app, cfg.toJson());
            Ipc.toHook(app, Ipc.ACTION_CONFIG, cfg.toJson());
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 取模块侧缓冲最完整的日志（重推），随后页面轮询即可读到 */
    @JavascriptInterface
    public void requestLogs() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", Ipc.CMD_GET_LOGS);
            o.put("arg", "");
            Ipc.toHook(app, Ipc.ACTION_CMD, o.toString());
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /* ==================== 日志 ==================== */

    @JavascriptInterface
    public String getLogs() {
        BydConfig cfg = BydConfig.fromJson(AppStore.loadConfigJson(app));
        return AppStore.loadLogsJson(app, cfg.keep);
    }

    /**
     * 日志投递对账：模块推了几批 vs 本机收了几批。
     *
     * 差得多，说明系统不允许广播把本 App 拉起来（厂商 ROM 的自启动限制），
     * 日志就只能等打开 App 时用整体快照补 —— 这正是「日志只在打开 App 才更新」的原因。
     */
    @JavascriptInterface
    public String getLogDelivery() {
        try {
            JSONObject o = new JSONObject();
            o.put("pushed", AppStore.modulePushes(app));
            o.put("received", AppStore.receipts(app));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }


    /** 下发指令：signNow / stop / clearLogs / reqConfig / probeVersion / getLogs */
    @JavascriptInterface
    public void command(String cmd, String arg) {
        if (cmd == null || cmd.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", cmd);
            o.put("arg", arg == null ? "" : arg);
            Ipc.toHook(app, Ipc.ACTION_CMD, o.toString());
        } catch (Exception ignored) {
            // 指令构造失败只影响这一次操作
        }
        if (Ipc.CMD_CLEAR_LOGS.equals(cmd)) AppStore.clearLogs(app);
    }



    /**
     * 就地收紧数值字段的取值，并补上必需项；**不删除任何未知键**。
     * 这样 App 侧新增的配置项（例如 wakeBeforeCode）在保存后不会消失。
     */
    private static void clampInPlace(JSONObject o) {
        clampInt(o, "retry", 0, 5, 2);
        clampInt(o, "interval", 3, 60, 10);
        clampInt(o, "keep", 1, 30, 3);
        clampInt(o, "codeTimeout", 5, 120, 30);
        if (!o.has("brand")) put(o, "brand", "hy");
        if (!o.has("appid")) put(o, "appid", xiaojw.byd.sign.BydProto.defaultAppId());
    }

    private static void clampInt(JSONObject o, String key, int min, int max, int def) {
        int v = o.optInt(key, def);
        if (v < min) v = min;
        if (v > max) v = max;
        put(o, key, v);
    }

    private static void put(JSONObject o, String key, Object v) {
        try {
            o.put(key, v);
        } catch (Exception ignored) {
            // 纯字符串/数字写入不会抛
        }
    }
    /* ==================== 内部 ==================== */

    private String readAsset() {
        try (InputStream in = app.getAssets().open("wxcode_versions.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static JSONObject parseOrEmpty(String s) {
        try {
            return new JSONObject(s == null || s.isEmpty() ? "{}" : s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 版本字典合并：override 覆盖 builtin，其余字段取 builtin */
    private static JSONObject merge(JSONObject builtin, JSONObject override) {
        JSONObject out = parseOrEmpty(builtin.toString());
        try {
            JSONObject ov = override.optJSONObject("versions");
            if (ov == null) return out;
            JSONObject vs = out.optJSONObject("versions");
            if (vs == null) vs = new JSONObject();
            for (Iterator<String> it = ov.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject entry = ov.optJSONObject(k);
                if (entry != null) vs.put(k, entry);
            }
            out.put("versions", vs);
        } catch (Exception ignored) {
            // 合并失败就返回内置表
        }
        return out;
    }

    private static List<String> versionList(JSONObject table) {
        List<String> list = new ArrayList<>();
        JSONObject vs = table.optJSONObject("versions");
        if (vs == null) return list;
        for (Iterator<String> it = vs.keys(); it.hasNext(); ) list.add(it.next());
        list.sort((a, b) -> {
            String[] x = a.split("\\."), y = b.split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int xi = i < x.length ? num(x[i]) : 0;
                int yi = i < y.length ? num(y[i]) : 0;
                if (xi != yi) return xi - yi;
            }
            return 0;
        });
        return list;
    }

    private static int num(String s) {
        try {
            String d = s.replaceAll("\\D", "");
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (Exception e) {
            return 0;
        }
    }
}
