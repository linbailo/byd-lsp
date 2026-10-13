package xiaojw.byd.core;

import android.content.Context;
import android.content.Intent;

/**
 * 控制台 App 与微信进程内模块之间的显式广播通道。
 *
 * 取代原 wxcode 的 127.0.0.1:8088 HTTP 取码接口：
 * 取码与签到都在微信进程内完成，本地只保留「下发指令 / 回写状态」的控制面流量，
 * 不携带业务数据，也不需要 root。
 */
public final class Ipc {

    public static final String PKG_MODULE = "xiaojw.byd";
    public static final String PKG_WECHAT = "com.tencent.mm";

    /** App → 模块进程：下发完整配置 */
    public static final String ACTION_CONFIG     = "xiaojw.byd.action.CONFIG";
    /** 模块进程 → App：请求一份最新配置（模块冷启动时） */
    public static final String ACTION_REQ_CONFIG = "xiaojw.byd.action.REQ_CONFIG";
    /** App → 模块进程：执行指令 */
    public static final String ACTION_CMD        = "xiaojw.byd.action.CMD";
    /** 模块进程 → App：运行状态快照 */
    public static final String ACTION_STATE      = "xiaojw.byd.action.STATE";
    /** 模块进程 → App：增量日志 */
    public static final String ACTION_LOG        = "xiaojw.byd.action.LOG";
    /**
     * 模块进程 → App：**整体快照**日志（覆盖写入而非追加）。
     * App 打开时主动拉一次 —— 自动签到发生在 App 不在前台的时候，
     * 那条增量日志可能没送达（Doze 推迟、进程刚起），而模块内存里一直留着最近若干行。
     */
    public static final String ACTION_LOG_SNAPSHOT = "xiaojw.byd.action.LOGSNAP";
    /** 模块进程 → App：dex 探测生成/更新的版本表 */
    public static final String ACTION_VERSION_TABLE = "xiaojw.byd.action.VERSION_TABLE";
    /** 模块进程 → App：版本探测过程与结果（用于首页红/绿提示） */
    public static final String ACTION_PROBE      = "xiaojw.byd.action.PROBE";
    /**
     * 模块进程 → App：握手应答。
     * 这是判断「模块是否真的注入到微信进程」的唯一可靠信号 ——
     * App 自己的 WebView 里 window.BYDModule 永远存在，说明不了任何事；
     * 只有微信进程里的模块收到广播并回话，才证明 LSPosed 确实生效了。
     */
    public static final String ACTION_PONG       = "xiaojw.byd.action.PONG";
    /** 模块进程 → App：取码链路自检结果 */
    public static final String ACTION_SELF_TEST  = "xiaojw.byd.action.SELFTEST";
    /** 模块进程 → App：尝试取码的完整过程与 LoginTask 字段现场 */
    public static final String ACTION_TRY_CODE   = "xiaojw.byd.action.TRYCODE";
    /** 模块进程 → App：模块侧当前实际生效的配置 */
    public static final String ACTION_MODULE_CFG = "xiaojw.byd.action.MODULECFG";
    /** 模块进程 → App：App 侧闹钟抢占的结果（allow / 已签到不必再签） */
    public static final String ACTION_CLAIM_RESULT = "xiaojw.byd.action.CLAIMRESULT";
    /** 模块进程 → App：App 侧要的 code（进程内反射取到的那个） */
    public static final String ACTION_CODE       = "xiaojw.byd.action.CODE";

    public static final String EXTRA_JSON = "json";

    public static final String CMD_SIGN_NOW   = "signNow";
    public static final String CMD_STOP       = "stop";
    public static final String CMD_CLEAR_LOGS = "clearLogs";
    public static final String CMD_REQ_CONFIG = "reqConfig";
    /** 让模块把当前内存里的全部日志重推一遍（App 冷启动/刷新时补齐丢失的日志） */
    public static final String CMD_GET_LOGS   = "getLogs";
    /** 触发一次本机 dex 探测，为当前微信版本生成适配配置 */
    public static final String CMD_PROBE_VERSION = "probeVersion";
    /** 握手：模块收到后必须回 ACTION_PONG（arg 里带 App 生成的 nonce） */
    public static final String CMD_PING       = "ping";
    /** 取码链路自检：逐步走一遍反射，但不触发真实登录 */
    public static final String CMD_SELF_TEST  = "selfTest";
    /** 尝试取码：真的发起一次登录并采样 status/code，用于定位取不到码的原因 */
    public static final String CMD_TRY_CODE   = "tryCode";
    /** 索取模块侧当前实际生效的配置（核对「配置有没有写进模块」） */
    public static final String CMD_GET_CONFIG = "getConfig";

    /**
     * 索取模块侧的**完整版本表**（内置 ⊕ 覆盖层 ⊕ 本机探测结果）。
     *
     * 为什么要有这个「拉」：模块探测成功后本来会主动把表推给控制台，
     * 但那个时机是**微信进程刚启动**——此时 App 进程往往已经死了，
     * 国产 ROM 又会拦后台启动，广播就丢了。结果模块侧一切正常（取码用的是对的类），
     * 控制台那份配置却一直没有这个版本：配置文件里看不到、配置页也看不到。
     * 所以改成 App 活着的时候主动拉一份，落盘由控制台负责。
     */
    public static final String CMD_GET_VERSION_TABLE = "getVersionTable";

    /**
     * 以下三条是「App 侧闹钟」用的协议（arg 为 JSON）。
     *
     * 起因：自动签到一直卡在出网这一步 —— 微信进程在后台时，
     * 它的请求「连接建得起来、响应不回」。把出网动作搬到 App 进程，
     * 并让 App 用 setExactAndAllowWhileIdle 把自己唤醒，
     * 就能拿到系统给「设置闹钟的应用」的那段临时联网白名单。
     * 取码仍然由微信进程内的模块完成（那一步是本地反射，不需要网络）。
     */
    /** App → 模块：我要接这个定时点（模块同意后让位，避免两边同时签） */
    public static final String CMD_APP_CLAIM  = "appClaim";
    /** App → 模块：把本机 code 给我（模块用自己生效配置里的 appid 去取） */
    public static final String CMD_GET_CODE   = "getCode";
    /** App → 模块：App 侧这次签到的结果（模块据此记账、发通知） */
    public static final String CMD_SIGN_RESULT = "signResult";
    /** App → 模块：把 App 侧闹钟的进度写进模块日志（日志页与整体快照只有这一条通道） */
    public static final String CMD_APP_LOG     = "appLog";

    private Ipc() {}

    /** 发往微信进程内的模块（receiver 由模块动态注册） */
    public static void toHook(Context anyCtx, String action, String json) {
        send(anyCtx, PKG_WECHAT, action, json);
    }

    /** 发往控制台 App（receiver 由清单声明） */
    public static void toApp(Context anyCtx, String action, String json) {
        send(anyCtx, PKG_MODULE, action, json);
    }

    private static void send(Context ctx, String targetPkg, String action, String json) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(action);
            i.setPackage(targetPkg);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if (json != null) i.putExtra(EXTRA_JSON, json);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 广播是尽力而为的控制通道，失败不能影响签到主流程
        }
    }
}
