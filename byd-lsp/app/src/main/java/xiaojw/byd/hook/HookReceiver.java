package xiaojw.byd.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xiaojw.byd.core.BydConfig;
import xiaojw.byd.core.HookStore;
import xiaojw.byd.core.Ipc;
import xiaojw.byd.core.LogStore;
import xiaojw.byd.core.ModuleState;
import xiaojw.byd.core.Notifier;
import xiaojw.byd.core.SignTask;

/**
 * 微信进程内的控制面入口。
 *
 * 原方案里控制台（WebUI）通过 HTTP 打 127.0.0.1:8088 来触发签到、看状态；
 * 这里改成显式广播：不占端口、不需要 root，也不会被别的 App 蹭到。
 */
public final class HookReceiver {

    private static boolean registered = false;

    private HookReceiver() {}

    public static synchronized void register(final Context wechatCtx,
                                            final HookStore store,
                                            final WxCodeHook hook) {
        if (registered) return;
        registered = true;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    dispatch(context.getApplicationContext(), store, hook, intent);
                } catch (Throwable t) {
                    LogStore.err("指令处理异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Ipc.ACTION_CMD);
        filter.addAction(Ipc.ACTION_CONFIG);

        // API 33 起必须显式声明接收动态广播的导出性
        if (Build.VERSION.SDK_INT >= 33) {
            wechatCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            wechatCtx.registerReceiver(receiver, filter);
        }
        LogStore.info("控制指令通道已就绪");
    }

    public static void dispatch(Context ctx, HookStore store, WxCodeHook hook, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String json = intent.getStringExtra(Ipc.EXTRA_JSON);

        if (Ipc.ACTION_CONFIG.equals(action)) {
            applyConfig(ctx, store, hook, json);
            return;
        }
        if (!Ipc.ACTION_CMD.equals(action)) return;

        String cmd = "", arg = "";
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            cmd = o.optString("cmd", "");
            arg = o.optString("arg", "");
        } catch (Exception e) {
            LogStore.warn("指令载荷解析失败: " + e.getMessage());
            return;
        }
        handle(ctx, store, hook, cmd, arg);
    }

    public static void handle(Context ctx, HookStore store, WxCodeHook hook, String cmd, String arg) {
        switch (cmd) {
            case Ipc.CMD_SIGN_NOW:
                store.putPending(Ipc.CMD_SIGN_NOW, arg);
                LogStore.info("收到手动签到指令（触发后立即执行，与是否已签到无关）");
                SignTask.submit(ctx, store, hook, "manual");
                break;

            case Ipc.CMD_STOP:
                SignTask.requestStop();
                break;

            case Ipc.CMD_CLEAR_LOGS:
                LogStore.clear();
                LogStore.info("日志已按控制台请求清空");
                break;

            case Ipc.CMD_REQ_CONFIG:
                Ipc.toApp(ctx, Ipc.ACTION_REQ_CONFIG, null);
                LogStore.info("已向控制台索取最新配置");
                break;

            case Ipc.CMD_GET_LOGS:
                // 控制台冷启动或点「刷新」：模块侧缓冲比 App 文件更完整，
                // 重推一遍可以补回 App 进程不在时错过的那几条广播
                LogStore.pushAll();
                break;

            case Ipc.CMD_PROBE_VERSION:
                LogStore.info("控制台请求重新探测本机微信版本配置");
                BydHook.requestProbe(ctx, store, hook);
                break;

            case Ipc.CMD_PING:
                sendPong(ctx, store, arg);
                break;

            case Ipc.CMD_GET_CONFIG:
                // 回一份「当前实际在用」的版本配置，App 侧据此核对配置有没有写进来
                Ipc.toApp(ctx, Ipc.ACTION_MODULE_CFG, hook.effectiveConfigJson(store.loadConfig()));
                break;

            case Ipc.CMD_GET_VERSION_TABLE: {
                // 控制台主动来拉完整版本表（内置 ⊕ 覆盖层 ⊕ 本机探测结果），由它落盘。
                // 比「模块主动推」可靠：模块推的时机是微信刚启动，App 多半不在。
                String table = hook.table().toJson();
                LogStore.info("控制台索取版本表：回一份 "
                        + hook.table().knownVersions().size() + " 个版本的完整表");
                Ipc.toApp(ctx, Ipc.ACTION_VERSION_TABLE, table);
                break;
            }

            case Ipc.CMD_SELF_TEST: {
                // 控制台已不再提供自检界面；这个入口保留给 adb 广播调试用：
                //   adb shell am broadcast -a xiaojw.byd.action.CMD -e json '{"cmd":"selfTest"}'
                //   结果会写进模块日志，在 App 的日志页就能看到
                LogStore.info("收到取码链路自检请求（adb 调试入口）");
                // 自检里含全包 dex 扫描，放到工作线程，别占住广播接收的主线程
                final Context appCtx = ctx;
                final WxCodeHook h = hook;
                new Thread(() -> {
                    String result = h.selfTest();
                    // 结果也写进模块日志：App 的日志页看的就是这条通道，
                    // 免得「跑了自检但哪都看不到」（另一份广播过去的结果 App 侧只在落盘、没人读）
                    LogStore.info("自检结果：" + result);
                    LogStore.flushNow();
                    Ipc.toApp(appCtx, Ipc.ACTION_SELF_TEST, result);
                }, "byd-lsp-selftest").start();
                break;
            }

            case Ipc.CMD_TRY_CODE: {
                BydConfig cfg = store.loadConfig();
                final long timeoutMs = Math.max(5, cfg.codeTimeout) * 1000L;
                LogStore.info("收到尝试取码请求（adb 调试入口，appId=" + cfg.appid
                        + "，上限 " + (timeoutMs / 1000) + "s，会真的发起一次登录）");
                // diagnose 会阻塞到拿到结果或超时，绝不能放在 onReceive 的主线程上
                final Context appCtx = ctx;
                final WxCodeHook h = hook;
                final String appId = cfg.appid;
                new Thread(() -> {
                    String result = h.diagnose(appId, timeoutMs);
                    LogStore.info("取码采样结果：" + result);
                    LogStore.flushNow();
                    Ipc.toApp(appCtx, Ipc.ACTION_TRY_CODE, result);
                }, "byd-lsp-trycode").start();
                break;
            }

            /* ==================== App 侧闹钟协议 ==================== */

            case Ipc.CMD_APP_CLAIM:
                handleAppClaim(ctx, store, arg);
                break;

            case Ipc.CMD_GET_CODE: {
                // App 侧闹钟要自己去发出网请求，code 仍由微信进程内的反射拿
                BydConfig cfg = store.loadConfig();
                final long timeoutMs = Math.max(5, Math.min(cfg.codeTimeout, 20)) * 1000L;
                final Context appCtx = ctx;
                final WxCodeHook h = hook;
                final String appId = cfg.appid;
                final String brand = cfg.brand;
                LogStore.info("App 侧闹钟请求取码（appId=" + appId + "，上限 "
                        + (timeoutMs / 1000) + "s）—— 这一步是本地反射，不需要网络");
                // getCode 会一直等到微信登录流程跑完，必须离开主线程
                new Thread(() -> {
                    String json;
                    try {
                        String code = h.getCode(appId, timeoutMs);
                        JSONObject o = new JSONObject();
                        o.put("ok", true);
                        o.put("code", code == null ? "" : code);
                        o.put("appid", appId);
                        o.put("brand", brand);
                        json = o.toString();
                        LogStore.ok("已把 code 交给 App 侧（长度 " + (code == null ? 0 : code.length()) + "）");
                    } catch (Throwable t) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ok", false);
                            o.put("err", t.getMessage() == null
                                    ? t.getClass().getSimpleName() : t.getMessage());
                            json = o.toString();
                        } catch (Exception e) {
                            json = "{\"ok\":false,\"err\":\"取码失败\"}";
                        }
                        LogStore.err("App 侧闹钟取码失败: " + t.getMessage());
                    }
                    Ipc.toApp(appCtx, Ipc.ACTION_CODE, json);
                    LogStore.flushNow();
                }, "byd-lsp-appcode").start();
                break;
            }

            case Ipc.CMD_SIGN_RESULT:
                handleAppSignResult(ctx, store, arg);
                break;

            case Ipc.CMD_APP_LOG: {
                // App 侧闹钟的进度也记进模块日志：日志页和整体快照都只认这一条通道
                try {
                    JSONObject o = new JSONObject(arg == null ? "{}" : arg);
                    String lv = o.optString("level", "INFO");
                    String msg = o.optString("msg", "");
                    if (!msg.isEmpty()) {
                        if ("WARN".equals(lv)) LogStore.warn(msg);
                        else if ("ERR".equals(lv)) LogStore.err(msg);
                        else if ("OK".equals(lv)) LogStore.ok(msg);
                        else LogStore.info(msg);
                    }
                } catch (Exception e) {
                    LogStore.warn("App 侧日志解析失败: " + e.getMessage());
                }
                break;
            }

            default:
                LogStore.warn("收到未知指令: " + cmd);
        }
    }

    /**
     * App 侧闹钟抢占定时点。
     *
     * App 的闹钟在 hh:01:00 触发，模块的 60 秒心跳最晚 hh:01:59 才看到到点 ——
     * 不打招呼的话两边都会签一遍。App 先来问一句，模块同意才让它接手；
     * 已签到、已关闭自动签到、或这个定时点今天已有结论，都直接拒掉。
     */
    private static void handleAppClaim(Context ctx, HookStore store, String arg) {
        BydConfig cfg = store.loadConfig();
        int slot = -1;
        try { slot = Integer.parseInt(arg == null ? "" : arg.trim()); } catch (Exception ignored) {}
        if (slot < 0 || slot > 23) slot = -1;

        boolean allow = true;
        String reason = "";
        if (!cfg.enabled) {
            allow = false;
            reason = "自动签到已关闭";
        } else if (cfg.dedupe && store.signedToday()) {
            allow = false;
            reason = "今日已签到";
        } else if (slot >= 0) {
            String state = store.slotState(slot);
            if (HookStore.SLOT_DONE.equals(state) || HookStore.SLOT_SKIP.equals(state)) {
                allow = false;
                reason = "这个定时点今天已有结论";
            }
        }
        if (allow && slot >= 0) {
            store.markClaim(slot);
            LogStore.info("App 侧闹钟接手定时点 " + String.format("%02d:01", slot)
                    + " —— 出网交给 App 进程（刚被闹钟唤醒，系统会临时放行联网）");
        }

        try {
            JSONObject o = new JSONObject();
            o.put("allow", allow);
            o.put("reason", reason);
            o.put("slot", slot);
            o.put("signedToday", store.signedToday());
            o.put("appid", cfg.appid);
            o.put("brand", cfg.brand);
            Ipc.toApp(ctx, Ipc.ACTION_CLAIM_RESULT, o.toString());
        } catch (Exception e) {
            LogStore.warn("抢占应答构造失败: " + e.getMessage());
        }
        SignTask.pushState(store);
    }

    /**
     * App 侧闹钟那次签到的结果。
     *
     * 结果统一回到模块记账：签到日期、连续天数、定时点台账、通知都只有这一条通道，
     * 不会出现「App 说签了、模块以为没签」而重复签的情况。
     */
    private static void handleAppSignResult(Context ctx, HookStore store, String arg) {
        JSONObject o;
        try {
            o = new JSONObject(arg == null ? "{}" : arg);
        } catch (Exception e) {
            LogStore.warn("App 侧结果解析失败: " + e.getMessage());
            return;
        }
        boolean ok = o.optBoolean("ok", false);
        int slot = o.optInt("slot", -1);
        int days = o.optInt("days", 0);
        String reason = o.optString("reason", "");

        if (slot >= 0) store.clearClaim(slot);

        if (ok) {
            store.markSigned();
            if (days > 0) store.setStreak(days);
            else store.setStreak(store.streak() + 1);
            if (slot >= 0) store.finishSlot(slot, HookStore.SLOT_DONE, "App 侧闹钟签到成功");
            LogStore.ok("App 侧闹钟签到成功，已连续签到 " + store.streak() + " 天");
            Notifier.notifyAll(store.loadConfig(), true, "比亚迪签到成功",
                    "签到成功！已连续签到 " + store.streak() + " 天。\n\n"
                  + "详情：\n  - " + ModuleState.instanceName() + " / User " + ModuleState.userId
                  + ": 签到成功（连续 " + store.streak() + " 天）\n"
                  + "时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        } else {
            // App 侧手动补签（slot = -1）没有定时点可退避，这一失败得说出来，
            // 否则它会静默消失 —— 之前正是如此：既不安排补签也不发通知
            if (slot >= 0) {
                store.scheduleSlotRetry(slot, System.currentTimeMillis() + 60_000L,
                        "App 侧：" + reason);
            } else {
                Notifier.notifyAll(store.loadConfig(), false, "比亚迪签到失败",
                        "签到失败（由 App 进程发起）。\n\n详情：\n  - " + ModuleState.instanceName()
                      + " / User " + ModuleState.userId + ": " + reason + "\n"
                      + "时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            }
            LogStore.warn("App 侧闹钟签到未成功（" + reason + "），交回模块侧按退避表补签");
        }
        SignTask.pushState(store);
        LogStore.flushNow();
    }

    /**
     * 握手应答。
     *
     * 这是判断「模块是否真的注入到微信进程」的唯一可靠信号：App 自己的 WebView 里
     * window.BYDModule 永远存在，只能说明页面跑在 APK 里，说明不了模块有没有生效。
     * 只有微信进程里的模块收到广播并回话，才证明 LSPosed 的勾选真的起作用了。
     *
     * 顺带回一份状态快照：即使 PONG 丢包，STATE 到达也能让 App 判活。
     */
    private static void sendPong(Context ctx, HookStore store, String nonce) {
        try {
            JSONObject o = new JSONObject();
            o.put("nonce", nonce == null ? "" : nonce);
            o.put("at", System.currentTimeMillis());
            o.put("moduleVersion", ModuleState.moduleVersion);
            o.put("version", ModuleState.versionName);
            o.put("versionExact", ModuleState.versionExact);
            o.put("versionNote", ModuleState.versionNote);
            o.put("probeState", ModuleState.probeState);
            o.put("probeReason", ModuleState.probeReason);
            o.put("userId", ModuleState.userId);
            o.put("instance", ModuleState.instanceName());
            o.put("pid", android.os.Process.myPid());
            o.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
            o.put("running", SignTask.isRunning());
            o.put("signedToday", store.signedToday());
            o.put("streak", store.streak());
            o.put("lastResult", store.lastResult());
            o.put("lastTime", store.lastTime());
            // 日志投递对账用：模块推了几批 / 日志序号到哪了。
            // App 侧拿这两个数跟自己的接收计数一比，就知道后台唤醒有没有被系统拦掉。
            o.put("logPushes", LogStore.pushes());
            o.put("logSeq", LogStore.currentSeq());
            Ipc.toApp(ctx, Ipc.ACTION_PONG, o.toString());
            SignTask.pushState(store);
            // 这里刻意不打日志：App 每 30 秒复检一次，逐次记录会把日志环形缓冲
            // （只有 300 行）冲掉，真正有价值的签到日志反而看不见了
        } catch (Throwable t) {
            LogStore.warn("握手应答失败: " + t.getMessage());
        }
    }

    private static void applyConfig(Context ctx, HookStore store, WxCodeHook hook, String json) {
        if (json == null || json.isEmpty()) return;
        BydConfig cfg = BydConfig.fromJson(json);

        // 配置没有任何变化就什么都不做：不落盘、不重建版本表、不打日志。
        // App 每次回到前台都会推一遍配置，之前会连带刷出「配置已更新」+
        // 「版本表已按控制台配置重建」等好几行，日志全被这些淹没。
        String normalized = cfg.toJson();
        if (normalized.equals(store.rawConfig())) {
            return;
        }
        store.saveConfig(cfg);
        LogStore.info("配置已更新 → 自动签到=" + cfg.enabled
                + " 去重=" + cfg.dedupe
                + " 品牌=" + cfg.brand
                + " 尝试 " + Math.max(1, cfg.retry) + " 次/" + cfg.interval + "s"
                + " 邮件=" + cfg.mailUsable()
                + " Webhook=" + cfg.webhookUsable());

        // 版本表整表重建（内置 + 这份覆盖层）。空覆盖＝只用内置表，同样要重建，
        // 否则内存里残留的旧探测结果会继续生效。
        hook.setTableJson(cfg.versionTable);
        // 推来的配置里没有当前版本时，用模块自己记的探测结果补上 ——
        // 否则一次配置推送就会把刚探测出来的配置冲掉
        if (hook.applyProbeLayer(store.probeTable())) {
            // 关键：把补好的完整表**回推给控制台**。不回推的话控制台那份永远缺这个版本，
            // 于是每次推配置都要在这里再补一次（日志里那行提示会无限重复），
            // 而且配置页的「当前支持版本 / 生效版本表」也永远看不到它。
            Ipc.toApp(ctx, Ipc.ACTION_VERSION_TABLE, hook.table().toJson());
            LogStore.info("已把补好的完整版本表回推给控制台（含微信 " + ModuleState.versionName + "）");
        }
        SignTask.pushState(store);
    }
}
