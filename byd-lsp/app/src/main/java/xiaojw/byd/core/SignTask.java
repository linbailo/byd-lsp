package xiaojw.byd.core;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import xiaojw.byd.hook.WxCodeHook;
import xiaojw.byd.sign.BydHttp;
import xiaojw.byd.sign.BydProto;
import xiaojw.byd.sign.BydSignEngine;

/**
 * 签到编排，对应 byd_sign.sh 的 main()。
 *
 * 与原脚本的差异：
 *   1. 原脚本在外层进程里先向本地端口要 code，再发两个 HTTPS 请求；
 *      这里三步都在微信进程内串起来，没有跨进程/跨端口的中间状态。
 *   2. 重试策略保持一致：只有可恢复的「网络类」错误才重试，
 *      解密失败、字段缺失、服务端明确拒绝都立即失败，避免无意义等待。
 *   3. 不再有 success/fail/skip 的多端口汇总，一个进程就是一个账号。
 *   4. 自动签到由 {@link SignScheduler} 传 Listener 进来：一次性失败
 *      （实机里最常见的是「连接建得起来、响应不回」的读超时）不该当天就结束，
 *      由调度侧安排补签，失败通知也压到真正放弃的时候再发。
 */
public final class SignTask {

    private static final long STOP_POLL_MS = 300L;

    private static Handler exec;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean stopRequested = false;

    private SignTask() {}

    /**
     * 收尾回调。
     *
     * 返回 true 表示「还会再试」——SignTask 据此压住失败通知，
     * 否则一次网络抖动会连着弹好几条「签到失败」。
     */
    public interface Listener {
        boolean onFinished(String trigger, boolean success, boolean retryable, String reason);
    }

    public static boolean isRunning() { return RUNNING.get(); }

    public static synchronized void init() {
        if (exec != null) return;
        HandlerThread t = new HandlerThread("byd-lsp-task");
        t.start();
        exec = new Handler(t.getLooper());
    }

    public static void requestStop() {
        stopRequested = true;
        LogStore.warn("已请求停止当前签到任务");
    }

    public static void submit(Context wechatCtx, HookStore store, WxCodeHook hook, String trigger) {
        submit(wechatCtx, store, hook, trigger, null);
    }

    public static void submit(Context wechatCtx, HookStore store, WxCodeHook hook,
                              String trigger, Listener listener) {
        init();
        exec.post(() -> execute(wechatCtx, store, hook, trigger, listener));
    }

    /* ==================== 主流程 ==================== */

    private static void execute(Context ctx, HookStore store, WxCodeHook hook,
                                String trigger, Listener listener) {
        if (!RUNNING.compareAndSet(false, true)) {
            LogStore.warn("已有签到任务在执行，忽略本次触发：" + trigger);
            return;
        }
        stopRequested = false;
        BydConfig cfg = store.loadConfig();
        boolean manual = "manual".equals(trigger);

        try {
            if (!manual && !cfg.enabled) {
                LogStore.info("自动签到已关闭，跳过本次触发：" + trigger);
                return;
            }
            // 「当天成功后不再重复」对手动签到同样生效。
            // 早先手动是刻意绕过它的（注释写着「触发后立即执行，与是否已签到无关」），
            // 于是这个开关点上去看不出任何作用 —— 点了照样又签一次。
            // 想重复签到，先把设置里那个开关关掉。
            if (cfg.dedupe && store.signedToday()) {
                LogStore.info("今日（" + HookStore.today() + "）已签到，且「当天成功后不再重复」开启中"
                        + " —— 跳过本次" + (manual ? "手动" : "自动") + "签到"
                        + (manual ? "；要重复签请在设置里关掉该开关" : ""));
                return;
            }

            LogStore.info("== 比亚迪" + (manual ? "手动" : "自动") + "签到开始（触发：" + trigger + "）==");
            LogStore.info("品牌 " + cfg.brand + " · AppID " + cfg.appid
                    + " · 重试上限 " + Math.max(1, cfg.retry) + " 次 / 间隔 " + cfg.interval + "s");

            /* ---- 第一步：进程内取 code ---- */
            String code = acquireCode(ctx, cfg, hook);
            if (code == null) {
                conclude(store, listener, trigger, false, true, "取码失败");
                return;
            }

            /* ---- 第二步：code → session_id ---- */
            String sessionId;
            try {
                sessionId = withRetry(cfg, "换取 session_id",
                        () -> BydSignEngine.fetchSessionId(cfg.appid, code));
            } catch (InterruptedException e) {
                logStopped();
                return;
            } catch (Exception e) {
                LogStore.err("换取 session_id 失败: " + e.getMessage());
                boolean retryable = e instanceof BydHttp.NetworkException;
                if (retryable) logNetworkSpot(ctx, cfg, "换取 session_id 阶段");
                conclude(store, listener, trigger, false, retryable,
                        "换取 session_id 失败: " + e.getMessage());
                return;
            }
            LogStore.ok("取得 session_id: " + mask(sessionId));

            /* ---- 第三步：session_id → 签到 ---- */
            BydSignEngine.Outcome outcome;
            try {
                outcome = withRetry(cfg, "签到",
                        () -> BydSignEngine.signIn(cfg.appid, cfg.brand, sessionId));
            } catch (InterruptedException e) {
                logStopped();
                return;
            } catch (Exception e) {
                LogStore.err("签到请求失败: " + e.getMessage());
                boolean retryable = e instanceof BydHttp.NetworkException;
                if (retryable) logNetworkSpot(ctx, cfg, "签到阶段");
                conclude(store, listener, trigger, false, retryable,
                        "签到请求失败: " + e.getMessage());
                return;
            }

            if (outcome.ok) {
                int days = outcome.days;
                store.markSigned();
                if (days > 0) store.setStreak(days);
                else store.setStreak(store.streak() + 1);
                LogStore.ok("签到成功！已连续签到 " + store.streak() + " 天");
                conclude(store, listener, trigger, true, false, null);
            } else {
                LogStore.err("签到被服务端拒绝，ret=" + outcome.ret + "，响应: " + outcome.detail);
                conclude(store, listener, trigger, false, false, "服务端返回 ret=" + outcome.ret);
            }
        } catch (Throwable t) {
            // 模块抛出的任何未预期异常都不应让微信进程出问题
            LogStore.err("签到流程异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            RUNNING.set(false);
            stopRequested = false;
            pushState(store);
            // 显式冲刷一次：自动签到发生在 App 多半不在前台的时候，
            // 只靠 800ms 合并窗口有可能把最后几行日志留在缓冲区里
            LogStore.flushNow();
        }
    }

    /**
     * 网络类失败时打一份现场：当时的网络类型/Doze/受限状态，
     * 以及对目标域名和对照组各做一次纯 TCP 连接。
     *
     * 这是唯一能把「整个进程出不了网」和「只有某个域名那条路径不通」
     * 分开的证据 —— 光看一句 SocketTimeoutException 分不出来。
     */
    private static void logNetworkSpot(Context ctx, BydConfig cfg, String stage) {
        try {
            String bydHost = NetProbe.hostOf(BydProto.BASE);
            String hookHost = NetProbe.hostOf(cfg.hookUrl);
            // 对照组用一个国内必通的域名：它也不通 = 进程当时根本出不了网；
            // 它通而目标不通 = 问题在那条路径上（IPv6 / MTU / 对端）
            String probe = NetProbe.probe(bydHost, hookHost, "www.baidu.com");
            LogStore.warn(stage + " 出网现场：" + NetProbe.snapshot(ctx));
            LogStore.warn(stage + " 连通性对照：" + probe);
        } catch (Throwable t) {
            LogStore.warn("出网现场诊断失败: " + t.getMessage());
        }
    }

    /** 收尾：先让调度侧决定还要不要重试，再据此决定发不发通知 */
    private static void conclude(HookStore store, Listener listener, String trigger,
                                 boolean success, boolean retryable, String reason) {
        boolean willRetry = false;
        if (listener != null) {
            try {
                willRetry = listener.onFinished(trigger, success, retryable, reason);
            } catch (Throwable t) {
                LogStore.warn("收尾回调异常: " + t.getMessage());
            }
        }
        finish(store, success, store.streak(), reason, willRetry);
    }

    /**
     * withRetry 用 InterruptedException 表示「被停止」。它只会在各阶段的 try 里抛出，
     * 所以必须在那里就地捕获——放到外层 try 会被 javac 判定为不可达的 catch。
     */
    private static void logStopped() {
        LogStore.warn("任务已被用户停止，本轮签到结束");
    }

    /**
     * 收尾：记录结果并按结果决定是否发通知（与原脚本的三分支一致）。
     *
     * willRetry 为真时只记账不打通知：中间失败不是最终结论，
     * 真正放弃的时候（调度侧不再重试）才打扰用户。
     */
    private static void finish(HookStore store, boolean success, int days, String reason, boolean willRetry) {
        BydConfig cfg = store.loadConfig();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        if (success) {
            store.recordResult("成功 · 连续 " + days + " 天");
            Notifier.notifyAll(cfg, true, "比亚迪签到成功",
                    "签到成功！已连续签到 " + days + " 天。\n\n"
                  + "详情：\n  - " + describeAccount() + ": 签到成功（连续 " + days + " 天）\n"
                  + "时间：" + now);
            LogStore.info("== 签到完成 ==");
        } else if (willRetry) {
            store.recordResult("失败 · 待自动重试");
            LogStore.info("== 本轮未成功（" + reason + "），已安排自动补签 ==");
        } else {
            store.recordResult("失败 · " + reason);
            Notifier.notifyAll(cfg, false, "比亚迪签到失败",
                    "签到失败。\n\n详情：\n  - " + describeAccount() + ": " + reason + "\n"
                  + "时间：" + now);
            LogStore.info("== 签到结束（未成功）==");
        }
    }

    private static String describeAccount() {
        return ModuleState.instanceName() + " / User " + ModuleState.userId;
    }

    /* ==================== 取码（带重试与前台兜底） ==================== */

    private static String acquireCode(Context ctx, BydConfig cfg, WxCodeHook hook) {
        int maxAttempts = Math.max(1, cfg.retry);
        WxCodeHook.CodeException last = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (stopRequested) return null;
            try {
                return hook.getCode(cfg.appid, Math.max(5, cfg.codeTimeout) * 1000L);
            } catch (WxCodeHook.CodeException e) {
                last = e;
                LogStore.err("取码失败：" + e.getMessage());
                if (!e.retryable || attempt == maxAttempts - 1) break;

                LogStore.warn("取码重试（第 " + (attempt + 1) + "/" + maxAttempts + " 次）");
                // 唤醒微信现在由 App 侧在做（模块在微信进程内无法后台启动 Activity）；
                // 这里只按配置的间隔等待
                sleep(cfg.interval * 1000L);
            }
        }
        if (last != null) LogStore.err("取码最终失败（已尝试 " + maxAttempts + " 次）");
        return null;
    }


    /* ==================== 通用水印重试 ==================== */

    private interface Stage<T> {
        T run() throws Exception;
    }

    private static <T> T withRetry(BydConfig cfg, String what, Stage<T> stage) throws Exception {
        int maxAttempts = Math.max(1, cfg.retry);
        Exception last = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (stopRequested) throw new InterruptedException("已停止");
            try {
                return stage.run();
            } catch (Exception e) {
                last = e;
                boolean retryable = e instanceof BydHttp.NetworkException;
                if (!retryable) {
                    LogStore.info(what + " 失败（" + e.getMessage() + "），错误不可重试");
                    break;
                }
                if (attempt == maxAttempts - 1) break;
                LogStore.warn(what + " 网络失败（" + e.getMessage() + "），"
                        + cfg.interval + " 秒后重试（第 " + (attempt + 1) + "/" + maxAttempts + " 次）");
                sleep(cfg.interval * 1000L);
            }
        }
        if (last == null) throw new IllegalStateException(what + " 未执行");
        throw last;
    }

    private static void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (stopRequested) return;
            try {
                Thread.sleep(Math.min(STOP_POLL_MS, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /* ==================== 状态推送 ==================== */

    public static void pushState(HookStore store) {
        BydConfig cfg = store.loadConfig();
        LogStore.pushState(store.stateJson(
                isRunning(), ModuleState.versionName,
                ModuleState.userId, ModuleState.instanceName()));
    }

    private static String mask(String s) {
        if (s == null) return "";
        return s.length() <= 10 ? s + "…" : s.substring(0, 10) + "…";
    }
}
