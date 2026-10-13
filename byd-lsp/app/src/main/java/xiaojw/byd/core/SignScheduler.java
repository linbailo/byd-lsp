package xiaojw.byd.core;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Calendar;

import xiaojw.byd.hook.WxCodeHook;

/**
 * 定时触发，对应 byd_sign 的 crontab：{@code 1 0,7,13,22 * * *}。
 *
 * 原方案靠 KernelSU 的 crond 拉起 shell 脚本——那需要 root 且依赖 busybox。
 * 现在模块本身就常驻在微信进程里，改为进程内 60 秒心跳：
 *
 *   1. 到点（每小时 01 分之后）触发一次自动签到；
 *   2. 进程启动时立即做一轮检查，等于「错过即补签」——
 *      比如 13:05 微信才被打开，13:01 的那次会立刻补上；
 *   3. **失败不再等于今天结束**：按退避表补签，直到成功或这个定时点的重试窗口用尽。
 *
 * 第 3 条是实机日志逼出来的：那天 00:01 自动签到准点触发、code 也拿到了，
 * 但两次 HTTPS 请求都是「连接建得起来、响应 45 秒不回」，然后当天的定时点
 * 就被记成已触发，再也不会补 —— 一次网络抖动就丢掉一整天的签到。
 *
 * 代价：微信被强行停止或系统长期冻结该进程时，模块没有机会跑。
 * 这是无 root、纯进程内方案无法绕开的限制，详见 README 的「已知限制」。
 */
public final class SignScheduler {

    private static final long TICK_MS = 60_000L;
    private static final long FIRST_TICK_DELAY_MS = 8_000L;
    private static final int[] SLOTS = {0, 7, 13, 22};

    /** 补签退避：失败 1 次后 1 分钟、2 次后 3 分钟、3 次后 10 分钟……之后固定 60 分钟 */
    private static final long[] RETRY_MINUTES = {1, 3, 10, 30, 60};
    /** 单个定时点的重试窗口：超过就今天不再试（下一个定时点自然会再来一次） */
    private static final long RETRY_WINDOW_MS = 60 * 60_000L;
    /** 网络预检不通过时的等待：没必要每 60 秒问一次 */
    private static final long NET_WAIT_MS = 5 * 60_000L;
    /** 兜底上限，防止退避表被绕过后无限重试 */
    private static final int MAX_TRIES = 12;
    /** 网络预检连续未通过的次数上限：到点就强制试一次，别让预检变成「永远不试」 */
    private static final int MAX_NET_DEFERS = 3;
    /** App 侧闹钟的让位窗口：这段时间内由 App 进程负责，过后模块自己接管 */
    private static final long CLAIM_GRACE_MS = 10 * 60_000L;

    private static volatile boolean started = false;
    /** 上一次打过的「网络不可用」现场，用来避免每次心跳都刷同一行日志 */
    private static String lastNetSkip = "";
    /** 网络预检连续未通过的次数（进程内计数即可） */
    private static int netDefers = 0;

    private SignScheduler() {}

    public static synchronized void start(final Context wechatCtx,
                                          final HookStore store,
                                          final WxCodeHook hook) {
        if (started) return;
        started = true;

        HandlerThread t = new HandlerThread("byd-lsp-sched");
        t.start();
        final Handler h = new Handler(t.getLooper());

        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    tick(wechatCtx, store, hook);
                } catch (Throwable e) {
                    LogStore.err("定时检查异常: " + e.getMessage());
                } finally {
                    h.postDelayed(this, TICK_MS);
                }
            }
        }, FIRST_TICK_DELAY_MS);

        LogStore.info("定时器已启动：每天 " + slotText()
                + "（进程内心跳，含启动补签与失败补签）");
    }

    private static void tick(Context ctx, HookStore store, WxCodeHook hook) {
        BydConfig cfg = store.loadConfig();
        if (!cfg.enabled) return;

        Calendar now = Calendar.getInstance();
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        int due = -1;
        for (int slot : SLOTS) {
            if (nowMinute >= slot * 60 + 1) due = slot;   // 取「已经过的最晚一个」定时点
        }
        if (due < 0) return;

        String state = store.slotState(due);
        // 先看这个定时点今天是否已经有结论 —— 已经成功/已跳过就不再管，
        // 否则下面那条「今日已签到就跳过」会把它覆盖成「跳过」并打一行误导日志
        if (HookStore.SLOT_DONE.equals(state) || HookStore.SLOT_SKIP.equals(state)) return;

        if (cfg.dedupe && store.signedToday()) {
            // 已签到：今天不再执行。只记一行 —— 之前是静默跳过，
            // 日志里看不出「到底跳过了还是没触发」。
            store.finishSlot(due, HookStore.SLOT_SKIP, "今日已签到");
            LogStore.info("定时点 " + hhmm(due) + " 已到，但今日已签到 —— 跳过，明天再执行");
            return;
        }

        /* App 侧闹钟已经接手这个定时点：让位。
           它的闹钟在 hh:01:00 触发，比本心跳更早，而且刚被闹钟唤醒的那个进程
           会拿到系统给的临时联网白名单 —— 出网交给它，取码仍然由本进程提供。 */
        long claimAt = store.claimAt(due);
        if (claimAt > 0 && System.currentTimeMillis() - claimAt < CLAIM_GRACE_MS) {
            LogStore.info("定时点 " + hhmm(due) + " 已由 App 侧闹钟接手，模块侧让位");
            return;
        }

        if (SignTask.isRunning()) return;

        long nowMs = System.currentTimeMillis();
        if (HookStore.SLOT_RETRY.equals(state)) {
            long nextAt = store.slotNextRetryAt(due);
            if (nextAt > 0 && nowMs < nextAt) return;
        }

        /* 重试窗口：这个定时点折腾超过一小时就收手，下一个定时点会重新来 */
        long firstAt = store.slotFirstAt(due);
        if (firstAt > 0 && nowMs - firstAt > RETRY_WINDOW_MS) {
            String note = store.slotNote(due);
            store.finishSlot(due, HookStore.SLOT_DONE, note);
            LogStore.err("定时点 " + hhmm(due) + " 补签窗口（1 小时）已过，今天不再重试"
                    + (note.isEmpty() ? "" : "（" + note + "）")
                    + " —— 下一个定时点会重新尝试");
            return;
        }

        /* 出网前先体检：网络本身不通就别白等一个读超时 */
        if (!NetProbe.hasUsableNetwork(ctx)) {
            if (netDefers < MAX_NET_DEFERS) {
                netDefers++;
                String note = NetProbe.snapshot(ctx);
                if (!note.equals(lastNetSkip)) {
                    lastNetSkip = note;
                    LogStore.warn("网络当前不可用，暂缓自动签到（" + note + "）");
                }
                store.scheduleSlotRetry(due, nowMs + NET_WAIT_MS, "网络不可用");
                return;
            }
            // 预检只是省一次白等，不能变成「永远不试」：
            // 有些 ROM 会关掉连通性校验，VALIDATED 永远为假，那就必须硬试一次
            LogStore.warn("连续 " + MAX_NET_DEFERS + " 次网络预检未通过，仍强制尝试一次");
        }
        netDefers = 0;
        lastNetSkip = "";

        int prevTries = store.slotTries(due);
        int tries = store.beginSlotAttempt(due, nowMs + retryDelayAfter(prevTries));

        if (tries == 1) {
            LogStore.info("定时点 " + hhmm(due) + " 已到，开始自动签到");
        } else {
            LogStore.info("补签 " + hhmm(due) + " 定时点（第 " + tries + " 次尝试）");
        }
        LogStore.info("出网环境：" + NetProbe.snapshot(ctx));

        SignTask.submit(ctx, store, hook, "auto-" + due, new AutoListener(store, due));
    }

    /** 失败 1 次后 1 分钟、2 次后 3 分钟…… 之后固定一小时 */
    private static long retryDelayAfter(int tries) {
        int i = Math.min(Math.max(tries, 1), RETRY_MINUTES.length) - 1;
        return RETRY_MINUTES[i] * 60_000L;
    }

    /**
     * 自动签到的收尾：决定「这个定时点今天还试不试」。
     *
     * 返回值告诉 SignTask 要不要先压住失败通知 —— 还会重试就不该打扰用户，
     * 否则一次网络抖动会连着弹好几条「签到失败」。
     */
    private static final class AutoListener implements SignTask.Listener {

        private final HookStore store;
        private final int slot;

        AutoListener(HookStore store, int slot) {
            this.store = store;
            this.slot = slot;
        }

        @Override
        public boolean onFinished(String trigger, boolean success, boolean retryable, String reason) {
            int tries = store.slotTries(slot);
            String prevNote = store.slotNote(slot);

            if (success) {
                store.finishSlot(slot, HookStore.SLOT_DONE, "成功");
                if (tries > 1) {
                    LogStore.ok("定时点 " + hhmm(slot) + " 前 " + (tries - 1) + " 次没成功（"
                            + (prevNote.isEmpty() ? "网络异常" : prevNote) + "），已补签成功");
                }
                return false;
            }

            if (!retryable) {
                store.finishSlot(slot, HookStore.SLOT_DONE, reason);
                LogStore.err("定时点 " + hhmm(slot) + " 失败且重试无意义（" + reason + "），今天不再尝试");
                return false;
            }

            if (tries >= MAX_TRIES) {
                store.finishSlot(slot, HookStore.SLOT_DONE, reason);
                LogStore.err("定时点 " + hhmm(slot) + " 已尝试 " + tries + " 次仍未成功（"
                        + reason + "），今天不再重试");
                return false;
            }

            long delay = retryDelayAfter(tries);
            store.scheduleSlotRetry(slot, System.currentTimeMillis() + delay, reason);
            LogStore.warn("定时点 " + hhmm(slot) + " 未成功（" + reason + "），"
                    + (delay / 60_000L) + " 分钟后补签（第 " + tries + " 次尝试）");
            return true;   // 已安排重试：先别发失败通知
        }
    }

    private static String hhmm(int slot) {
        return String.format("%02d:01", slot);
    }

    private static String slotText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(hhmm(SLOTS[i]));
        }
        return sb.toString();
    }
}
