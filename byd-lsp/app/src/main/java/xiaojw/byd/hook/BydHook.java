package xiaojw.byd.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import xiaojw.byd.core.BydConfig;
import xiaojw.byd.core.HookStore;
import xiaojw.byd.core.Ipc;
import xiaojw.byd.core.LogStore;
import xiaojw.byd.core.ModuleState;
import xiaojw.byd.core.SignScheduler;
import xiaojw.byd.core.SignTask;

/**
 * 模块唯一入口（assets/xposed_init 指向本类）。
 *
 * 作用域：com.tencent.mm。系统分身/多开与主应用包名相同，因此勾选一项即可覆盖；
 * 每个实例都是独立进程 + 独立 data 目录，各自持有一份配置与签到状态。
 *
 * 版本适配流程（对应需求「未收录版本自动查 dex 生成字典」）：
 *   1. 用内置 assets/wxcode_versions.json + 控制台配置里的覆盖表建表
 *   2. 当前微信版本精确命中 → 直接可用
 *   3. 未命中 → 解析安装包与运行时 dex 的类名，按 wxcode-hook-config 的规则
 *      反射校验出 j1 / c / a1 / a7，热应用并把结果推给控制台写进配置文件
 *   4. 探测失败 → 状态回传控制台，首页显示红色「请手动打开配置更新」
 */
public final class BydHook implements IXposedHookLoadPackage {

    private static final String TAG = "[byd-lsp]";
    private static final String WECHAT = "com.tencent.mm";

    /** 只初始化一次：attach 与 attachBaseContext 两条路可能都命中 */
    private static final AtomicBoolean BOOTED = new AtomicBoolean(false);
    /** 探测是重活，同一进程内只跑一次（手动重探除外） */
    private static final AtomicBoolean PROBING = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WECHAT.equals(lpparam.packageName)) return;
        // 只处理主进程，:appbrand / :tools 等子进程不参与签到
        if (!lpparam.packageName.equals(lpparam.processName)) return;

        XC_MethodHook onAttach = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof Context)) return;
                bootstrap((Context) param.args[0]);
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, onAttach);
        } catch (Throwable primary) {
            // 少数 ROM 改写了 Application.attach；退回 attachBaseContext。
            // 该方法来自 ContextWrapper，会命中所有 ContextWrapper，
            // 因此 bootstrap 内部用一次性开关兜住。
            XposedBridge.log(TAG + " Application.attach 不可用（" + primary.getMessage()
                    + "），改用 attachBaseContext");
            try {
                XposedHelpers.findAndHookMethod(Application.class, "attachBaseContext",
                        Context.class, onAttach);
            } catch (Throwable fallback) {
                XposedBridge.log(TAG + " 初始化挂载全部失败: " + fallback);
            }
        }
    }

    private void bootstrap(Context context) {
        if (!BOOTED.compareAndSet(false, true)) return;

        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();

        try {
            LogStore.init(app);
            ModuleState.versionName = readVersion(app, WECHAT);
            ModuleState.moduleVersion = readVersion(app, Ipc.PKG_MODULE);
            ModuleState.userId = ModuleState.detectUserId(app);

            LogStore.info("模块已注入 " + ModuleState.instanceName()
                    + "（微信 " + ModuleState.versionName + "，User " + ModuleState.userId
                    + "，模块 v" + ModuleState.moduleVersion + "）");
            LogStore.info("取码方式：进程内直接调用，无本地端口转发");

            final HookStore store = new HookStore(app);
            final BydConfig bootCfg = store.loadConfig();

            // 关闭「自动解析 dex」时，历史探测生成的覆盖层一律清空。
            // 它的优先级高于内置表，早期版本自动探测可能写进过错误的实例方法名
            // （调错方法不抛异常、只是登录永不返回），一直留着就会让取码永远失败。
            if (!bootCfg.autoProbe && bootCfg.versionTable != null && !bootCfg.versionTable.isEmpty()) {
                LogStore.warn("「自动解析 dex」已关闭，清空历史探测生成的覆盖层，改用内置版本表");
                bootCfg.versionTable = "";
                store.saveConfig(bootCfg);
                // 通知 App 也清掉，否则它下次保存配置时又会把旧的推回来
                Ipc.toApp(app, Ipc.ACTION_VERSION_TABLE, "{\"versions\":{}}");
            }

            final WxVersionTable table = WxVersionTable.load(bootCfg.versionTable);
            final WxCodeHook codeHook = new WxCodeHook(app, app.getClassLoader(),
                    ModuleState.versionName, table);
            // 配置里没有当前版本时，用模块自己记的探测结果补一层（详见 applyProbeLayer），
            // 并把补好的完整表回推给控制台，让它写进配置文件
            if (codeHook.applyProbeLayer(store.probeTable())) {
                Ipc.toApp(app, Ipc.ACTION_VERSION_TABLE, codeHook.table().toJson());
                LogStore.info("已把补好的完整版本表回推给控制台（含微信 " + ModuleState.versionName + "）");
            }

            // 版本号变了就把探测记账清掉，让新版本重新探测一次
            if (!ModuleState.versionName.equals(store.probedVersion())) {
                store.clearProbeRecord();
            }

            refreshVersionStatus(codeHook);
            logEffectiveConfigOnce(store, codeHook);
            if (!codeHook.isExactMatch()) {
                LogStore.warn(bootCfg.autoProbe
                        ? "当前微信版本未在适配表中，将自动解析 dex 生成配置"
                        : "当前微信版本未在适配表中，按版本表就近匹配（自动解析 dex 已关闭）");
            }

            HookReceiver.register(app, store, codeHook);
            SignScheduler.start(app, store, codeHook);
            drainPending(app, store, codeHook);

            if (!store.hasConfig()) {
                LogStore.warn("尚未收到控制台配置，先按默认值运行并向控制台索取");
                Ipc.toApp(app, Ipc.ACTION_REQ_CONFIG, null);
            }
            SignTask.pushState(store);

            // 自动探测要同时满足：开了开关、版本表没收录、这个版本还没探测过。
            // 探测要扫全包 dex，代价很高；结果无论成败都记账，
            // 于是「打开 App 就重扫一遍」「每次微信启动都重扫」都不会再发生。
            if (!codeHook.isExactMatch() && bootCfg.autoProbe) {
                if (store.probeDoneFor(ModuleState.versionName)) {
                    LogStore.info("微信 " + ModuleState.versionName + " 已探测过（上次结果："
                            + store.probeResult() + "），不再重复解析 dex");
                } else {
                    probeAsync(app, store, codeHook);
                }
            }
        } catch (Throwable t) {
            // 模块内部的任何异常都不应该影响微信本身
            XposedBridge.log(TAG + " 初始化失败: " + t);
        }
    }

    /** 生效配置只在真正变化时打一行，避免每次微信启动都刷 */
    private static void logEffectiveConfigOnce(HookStore store, WxCodeHook hook) {
        String fp = hook.describeConfig();
        if (fp.equals(store.lastFingerprint())) return;
        store.saveFingerprint(fp);
        LogStore.info("生效配置 → " + fp);
    }

    /* ==================== 版本适配状态 ==================== */

    private static void refreshVersionStatus(WxCodeHook hook) {
        if (hook.isExactMatch()) {
            ModuleState.versionExact = true;
            ModuleState.probeState = "ok";
            ModuleState.versionNote = "已收录，可直接使用";
            ModuleState.probeReason = "";
        } else {
            ModuleState.versionExact = false;
            ModuleState.versionNote = "未收录，按版本表就近匹配";
            if (!"failed".equals(ModuleState.probeState)) ModuleState.probeState = "idle";
        }
    }

    /** 手动重探：控制台「配置」页的按钮走这里 */
    public static void requestProbe(Context app, HookStore store, WxCodeHook hook) {
        if (ModuleState.versionExact) {
            LogStore.info("当前微信版本已收录，无需探测");
            // 必须回一条结果：App 侧在发起请求时就把状态写成了「探测中」，
            // 不回答它就会永远停在「已请求模块探测」——实机上就是这个现象。
            Ipc.toApp(app, Ipc.ACTION_PROBE, probeJson("ok", null, null));
            SignTask.pushState(store);
            return;
        }
        // 手动 / adb 触发是强制重跑：先清掉记账再探
        store.clearProbeRecord();
        probeAsync(app, store, hook);
    }

    private static void probeAsync(final Context app, final HookStore store, final WxCodeHook hook) {
        if (!PROBING.compareAndSet(false, true)) {
            LogStore.info("已有版本探测在进行中，忽略重复触发");
            return;
        }
        ModuleState.probeState = "running";
        ModuleState.probeReason = "";
        ModuleState.versionNote = "未收录，正在解析 dex…";
        Ipc.toApp(app, Ipc.ACTION_PROBE, probeJson("running", null, null));
        SignTask.pushState(store);

        Thread t = new Thread(() -> {
            try {
                DexProbe.Outcome out = DexProbe.probe(app, ModuleState.versionName);
                if (out.ok) {
                    hook.mergeTableJson(out.fragmentJson);
                    // 把这份探测结果单独记一份：配置推送把它冲掉时还能补回来
                    store.saveProbeTable(out.fragmentJson);
                    // 把合并后的完整表落进模块缓存的配置，并推给控制台持久化到配置文件
                    BydConfig cfg = store.loadConfig();
                    cfg.versionTable = hook.table().toJson();
                    store.saveConfig(cfg);

                    ModuleState.versionExact = true;
                    ModuleState.probeState = "ok";
                    ModuleState.versionNote = out.classCount > 0
                            ? "已自动生成（共 " + out.classCount + " 个类中匹配成功）"
                            : "已自动生成（由 JsApiLogin 调用点分析）";
                    ModuleState.probeReason = "";

                    Ipc.toApp(app, Ipc.ACTION_VERSION_TABLE, cfg.versionTable);
                    Ipc.toApp(app, Ipc.ACTION_PROBE, probeJson("ok", null, out.stepsText()));
                    LogStore.ok("已为微信 " + ModuleState.versionName + " 自动生成 Hook 配置并生效");
                } else {
                    ModuleState.versionExact = false;
                    ModuleState.probeState = "failed";
                    ModuleState.versionNote = "获取配置失败";
                    ModuleState.probeReason = out.reason;
                    Ipc.toApp(app, Ipc.ACTION_PROBE, probeJson("failed", out.reason, out.stepsText()));
                    LogStore.err("微信 " + ModuleState.versionName + " 配置探测失败：" + out.reason);
                }
            } catch (Throwable th) {
                ModuleState.probeState = "failed";
                ModuleState.probeReason = th.getClass().getSimpleName() + ": " + th.getMessage();
                ModuleState.versionNote = "获取配置失败";
                LogStore.err("版本探测异常: " + ModuleState.probeReason);
            } finally {
                // 成功失败都记账：失败也记，否则每次启动都会重扫一遍 dex
                store.markProbeDone(ModuleState.versionName, ModuleState.versionExact);
                PROBING.set(false);
                SignTask.pushState(store);
                LogStore.flushNow();
            }
        }, "byd-lsp-probe");
        t.setDaemon(true);
        t.start();
    }

    private static String probeJson(String state, String reason, String steps) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", ModuleState.versionName);
            o.put("state", state);
            o.put("exact", ModuleState.versionExact);
            o.put("note", ModuleState.versionNote);
            if (reason != null) o.put("reason", reason);
            if (steps != null) o.put("steps", steps);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 上次微信进程被杀时没执行完的指令，在这里补做。
     * 典型场景：控制台点了「立即签到」，但微信号刚好被系统回收。
     */
    private void drainPending(Context app, HookStore store, WxCodeHook hook) {
        JSONObject pending = store.takePending();
        if (pending == null) return;
        String cmd = pending.optString("cmd", "");
        long age = System.currentTimeMillis() - pending.optLong("at", 0L);
        if (age > 10 * 60_000L) {
            LogStore.warn("丢弃过期指令（" + (age / 1000) + " 秒前）: " + cmd);
            return;
        }
        LogStore.info("补做上次未完成的指令: " + cmd);
        HookReceiver.handle(app, store, hook, cmd, pending.optString("arg", ""));
    }

    private static String readVersion(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName == null ? "unknown" : pi.versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
