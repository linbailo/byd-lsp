package xiaojw.byd.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import xiaojw.byd.core.BydConfig;
import xiaojw.byd.core.Ipc;

/**
 * 接收微信进程内模块回写的状态、增量日志、版本表与探测结果。
 *
 * 【重要】这里所有落盘动作都是**同步**完成的，不走后台线程。
 * 原因：系统启动一个"只为处理广播"的进程后，onReceive 一返回就可能立刻回收它。
 * 之前把写文件丢给 ExecutorService，导致自动签到（App 多半不在前台）时
 * 日志广播到达了、文件却没写下来 —— 这正是「自动签到日志丢失」的根因。
 * 日志批量最大 300 行，同步追加只有毫秒级，放主线程完全可以接受。
 */
public final class StateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        final String json = intent.getStringExtra(Ipc.EXTRA_JSON);
        final Context app = context.getApplicationContext();

        try {
            if (Ipc.ACTION_STATE.equals(action)) {
                AppStore.mergeState(app, json);
                AppStore.markSeen(app);

            } else if (Ipc.ACTION_PONG.equals(action)) {
                // 握手应答：模块确实注入到微信进程了
                AppStore.applyPong(app, json);

            } else if (Ipc.ACTION_SELF_TEST.equals(action)) {
                // 自检结果只在日志里露出（控制台没有自检界面，这个入口留给 adb 调试）
                AppStore.appendDiagnostic(app, "INFO", "取码链路自检：\n" + json);

            } else if (Ipc.ACTION_TRY_CODE.equals(action)) {
                AppStore.appendDiagnostic(app, "INFO", "尝试取码结果：\n" + json);

            } else if (Ipc.ACTION_MODULE_CFG.equals(action)) {
                AppStore.saveModuleConfig(app, json);

            } else if (Ipc.ACTION_LOG.equals(action)) {
                AppStore.countLogReceipt(app);
                AppStore.appendLogs(app, json);

            } else if (Ipc.ACTION_LOG_SNAPSHOT.equals(action)) {
                AppStore.countLogReceipt(app);
                // 只补不删：覆盖写会把模块缓冲里没有的本地历史全部抹掉
                AppStore.mergeLogs(app, json);

            } else if (Ipc.ACTION_VERSION_TABLE.equals(action)) {
                // 模块里 dex 探测生成的版本表：写进配置文件，下次启动直接命中
                AppStore.setVersionTable(app, json);

            } else if (Ipc.ACTION_PROBE.equals(action)) {
                AppStore.saveProbeStatus(app, json);

            } else if (Ipc.ACTION_REQ_CONFIG.equals(action)) {
                // 模块冷启动发现没有配置，把 App 侧的配置回推过去
                String cfg = AppStore.loadConfigJson(app);
                if (cfg == null || cfg.isEmpty()) cfg = BydConfig.defaults().toJson();
                Ipc.toHook(app, Ipc.ACTION_CONFIG, cfg);

            } else if (Ipc.ACTION_CLAIM_RESULT.equals(action)) {
                // App 侧闹钟抢占定时点的应答（模块同意才由 App 发出网请求）
                AppSigner.onClaimResult(json);

            } else if (Ipc.ACTION_CODE.equals(action)) {
                // App 侧闹钟要的 code
                AppSigner.onCodeResult(json);
            }
        } catch (Throwable ignored) {
            // 展示层数据，失败不影响模块运行
        }
    }
}
