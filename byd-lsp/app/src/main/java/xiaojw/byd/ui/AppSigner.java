package xiaojw.byd.ui;

import android.content.Context;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import xiaojw.byd.core.Ipc;
import xiaojw.byd.core.NetProbe;
import xiaojw.byd.sign.BydHttp;
import xiaojw.byd.sign.BydProto;
import xiaojw.byd.sign.BydSignEngine;

/**
 * App 侧的签到流程。
 *
 * 与模块侧那条路唯一的分工差别：**出网的两步 HTTPS 在这里发**。
 * 原因见 {@link AppAlarm} —— 闹钟带来的临时联网放行只对设置闹钟的这个 uid 生效，
 * 而「连接建得起来、响应不回」恰恰是微信进程作为后台应用时受限的表现。
 *
 * 取码仍然找微信进程要：那一步是进程内反射调用微信自己的登录流程，
 * 除了不需要网络，还避免了重新实现一遍 j1/c/a1/a7 那套版本适配。
 *
 * 结果一律回执给模块记账（签到日期、连续天数、定时点台账、通知都只有那一条通道），
 * 所以不会出现「App 说签了、模块以为没签」而重复签的情况。
 */
public final class AppSigner {

    /** 等模块应答的超时。取码那一步模块内部上限 20 秒，这边留点余量 */
    private static final long CLAIM_WAIT_MS = 6_000L;
    private static final long CODE_WAIT_MS = 25_000L;

    private static volatile String claimReply = null;
    private static volatile CountDownLatch claimLatch = null;
    private static volatile String codeReply = null;
    private static volatile CountDownLatch codeLatch = null;

    private AppSigner() {}

    /* ==================== 模块回执的入口（由 StateReceiver 转进来） ==================== */

    public static void onClaimResult(String json) {
        claimReply = json;
        CountDownLatch l = claimLatch;
        if (l != null) l.countDown();
    }

    public static void onCodeResult(String json) {
        codeReply = json;
        CountDownLatch l = codeLatch;
        if (l != null) l.countDown();
    }

    /* ==================== 主流程 ==================== */

    /**
     * 走完一次 App 侧签到。调用方必须在工作线程上，并且确保进程活着（goAsync）。
     *
     * @return 一行人类可读的结果，写进设置页
     */
    public static String run(Context ctx, int slot) {
        /* ---- 1. 先跟模块打招呼：它同意才接手，两边不会同时签 ---- */
        JSONObject claim = claim(ctx, slot);
        if (claim == null) {
            // 模块不在，没有什么可回执的：签到根本没开始
            return fail(ctx, slot, "模块没有应答（微信进程可能没在运行）");
        }
        if (!claim.optBoolean("allow", false)) {
            String reason = claim.optString("reason", "模块未同意");
            AppStore.appSideLog(ctx, "INFO", "定时点 " + hhmm(slot) + " 无需 App 侧签：" + reason);
            return "跳过：" + reason;
        }

        /* ---- 2. 网络体检：闹钟的临时放行有没有生效，这里能看出来 ---- */
        if (!NetProbe.hasUsableNetwork(ctx)) {
            String note = NetProbe.snapshot(ctx);
            AppStore.appSideLog(ctx, "WARN", "网络不可用，放弃本次：" + note);
            report(ctx, slot, false, 0, "网络不可用");
            return "网络不可用（" + note + "）";
        }
        AppStore.appSideLog(ctx, "INFO", "出网环境：" + NetProbe.snapshot(ctx));

        /* ---- 3. 向微信进程要 code（本地反射，不需要网络）---- */
        JSONObject code = requestCode(ctx);
        if (code == null) {
            AppStore.appSideLog(ctx, "WARN", "取码无应答（微信进程可能不在）");
            report(ctx, slot, false, 0, "取码无应答");
            return "取码无应答";
        }
        if (!code.optBoolean("ok", false)) {
            String err = code.optString("err", "未知原因");
            AppStore.appSideLog(ctx, "ERR", "取码失败：" + err);
            report(ctx, slot, false, 0, "取码失败：" + err);
            return "取码失败：" + err;
        }
        String appid = code.optString("appid", "");
        String brand = code.optString("brand", "hy");
        String value = code.optString("code", "");
        if (value.isEmpty()) {
            report(ctx, slot, false, 0, "取码为空");
            return "取码为空";
        }
        AppStore.appSideLog(ctx, "OK", "已从微信进程取到 code（长度 " + value.length() + "）");

        /* ---- 4. 出网两步：由被闹钟唤醒的 App 进程发出 ---- */
        try {
            long t0 = System.currentTimeMillis();
            String sessionId = BydSignEngine.fetchSessionId(appid, value);
            AppStore.appSideLog(ctx, "OK", "取得 session_id（耗时 "
                    + (System.currentTimeMillis() - t0) + "ms）");

            BydSignEngine.Outcome outcome = BydSignEngine.signIn(appid, brand, sessionId);
            if (outcome.ok) {
                report(ctx, slot, true, outcome.days, "");
                return "签到成功" + (outcome.days > 0 ? "（连续 " + outcome.days + " 天）" : "");
            }
            String why = "服务端 ret=" + outcome.ret;
            AppStore.appSideLog(ctx, "WARN", why + " · " + outcome.detail);
            report(ctx, slot, false, 0, why);
            return "未成功：" + why;
        } catch (BydHttp.NetworkException e) {
            String spot = NetProbe.snapshot(ctx) + " · 对照 "
                    + NetProbe.probe(NetProbe.hostOf(BydProto.BASE), "www.baidu.com");
            AppStore.appSideLog(ctx, "WARN", "出网失败：" + e.getMessage());
            AppStore.appSideLog(ctx, "WARN", "出网现场：" + spot);
            report(ctx, slot, false, 0, e.getMessage());
            return "出网失败：" + e.getMessage();
        } catch (Throwable t) {
            AppStore.appSideLog(ctx, "ERR", "签到异常：" + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            report(ctx, slot, false, 0, String.valueOf(t.getMessage()));
            return "失败：" + t.getMessage();
        }
    }

    /* ==================== 与模块的两次往返 ==================== */

    /** 让模块把它内存里的日志整体推一份过来（本进程的接收器会落盘） */
    public static void pullLogs(Context ctx) {
        send(ctx, Ipc.CMD_GET_LOGS, "");
    }

    /** 问模块「这个定时点能不能让我接」；拿不到应答返回 null */
    private static JSONObject claim(Context ctx, int slot) {
        CountDownLatch latch = new CountDownLatch(1);
        claimReply = null;
        claimLatch = latch;
        if (!send(ctx, Ipc.CMD_APP_CLAIM, String.valueOf(slot))) return null;
        try {
            latch.await(CLAIM_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            claimLatch = null;
        }
        return parse(claimReply);
    }

    /** 要 code；拿不到应答返回 null */
    private static JSONObject requestCode(Context ctx) {
        CountDownLatch latch = new CountDownLatch(1);
        codeReply = null;
        codeLatch = latch;
        if (!send(ctx, Ipc.CMD_GET_CODE, "")) return null;
        try {
            latch.await(CODE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            codeLatch = null;
        }
        return parse(codeReply);
    }

    /** 把结果回执给模块：记账、通知、状态回写都由它做 */
    private static void report(Context ctx, int slot, boolean ok, int days, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("slot", slot);
            o.put("days", days);
            o.put("reason", reason == null ? "" : reason);
            send(ctx, Ipc.CMD_SIGN_RESULT, o.toString());
        } catch (Throwable ignored) {
            // 回执失败时模块侧会在让位窗口过后自行补签
        }
    }

    private static boolean send(Context ctx, String cmd, String arg) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", cmd);
            o.put("arg", arg == null ? "" : arg);
            Ipc.toHook(ctx, Ipc.ACTION_CMD, o.toString());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static JSONObject parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String fail(Context ctx, int slot, String msg) {
        AppStore.appSideLog(ctx, "WARN", "定时点 " + hhmm(slot) + " " + msg);
        return msg;
    }

    private static String hhmm(int slot) { return String.format("%02d:01", slot); }
}
