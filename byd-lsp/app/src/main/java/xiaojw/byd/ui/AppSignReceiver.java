package xiaojw.byd.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * App 侧闹钟的落点，同时也是开机后重排闹钟的地方。
 *
 * 这个 receiver 是第三层改造的入口：闹钟到点 → 系统在 Doze 里唤醒 App 进程 →
 * 这里用 {@code goAsync()} 把工作丢给线程（取码是一次跨进程往返，可能要几秒），
 * 干完就退 —— 没有常驻服务、没有前台通知。
 *
 * 处理完无论成败都会重排「下一个定时点」，所以即使某一次失败，链条也不会断。
 */
public final class AppSignReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        final Context app = context.getApplicationContext();

        // 开机 / App 更新后系统会清掉所有闹钟，必须重排
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppAlarm.reschedule(app);
            AppStore.appSideLog(app, "INFO", "开机/更新后重排 App 侧闹钟");
            return;
        }

        if (!AppAlarm.ACTION_SLOT.equals(action)) return;

        final int slot = intent.getIntExtra(AppAlarm.EXTRA_SLOT, -1);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            String result;
            try {
                result = AppSigner.run(app, slot);
            } catch (Throwable t) {
                result = "异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
                AppStore.appSideLog(app, "ERR", "App 侧签到异常：" + result);
            }
            AppAlarm.recordState(app, "定时点 " + String.format("%02d:01", slot) + " → " + result);
            AppStore.appSideLog(app, "INFO", "App 侧签到结束：" + result);

            // 顺手把模块的日志整体拉一份过来落盘。
            // 这一条是「日志只在打开 App 时才有更新」的正解：落盘必须由 App 进程做，
            // 而闹钟是系统唯一能可靠把 App 叫起来的机会 —— 借这一下把缓冲补进本地文件，
            // 就算系统不允许广播唤醒本 App，日志也不会只能等用户手动打开才看得到。
            try {
                AppSigner.pullLogs(app);
                Thread.sleep(1500);          // 等模块推快照 + 本进程的接收器落盘
            } catch (Throwable ignored) {
                // 拉不到就算了，下次打开 App 时握手还会拉一次
            }

            // 无论成败都重排下一次：闹钟链条不能因为一次失败就断掉
            AppAlarm.reschedule(app);
            try {
                pending.finish();
            } catch (Throwable ignored) {
                // 已经结束就忽略
            }
        }, "byd-lsp-appsign").start();
    }
}
