package xiaojw.byd.ui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * App 侧的定时唤醒。
 *
 * 为什么要有它：实机日志显示自动签到卡在出网这一步 —— 微信进程在后台时，
 * 它的 HTTPS 请求「连接建得起来、响应的字节一直不回」，而同一时刻手机是有网的。
 * 所以问题不在「有没有网」，而在**这个进程在那一刻不被允许出网**。
 *
 * {@code setExactAndAllowWhileIdle} 正是为这种情况准备的：系统会在 Doze 里按时唤醒
 * 设置这个闹钟的应用，并给它一段（约 10 分钟）的临时放行 —— 期间可以联网、可以持锁。
 * 关键在这个放行是给**设置闹钟的那个 uid**的，所以出网动作必须落在 App 进程：
 * 取码仍由微信进程内的模块完成（本地反射，不需要网络），两次 HTTPS 由 App 发出。
 *
 * 代价：每个定时点唤醒一次 App 进程，干完就退。没有常驻服务、没有前台通知、
 * 没有常驻 WakeLock。
 */
public final class AppAlarm {

    /** 闹钟广播（显式组件，只有 App 自己收得到） */
    public static final String ACTION_SLOT = "xiaojw.byd.action.APP_SLOT";
    public static final String EXTRA_SLOT = "slot";

    private static final int REQ_CODE = 2001;
    /** 与模块侧心跳用同一组定时点 */
    private static final int[] SLOTS = {0, 7, 13, 22};

    private static final String SP = "byd_lsp_alarm";
    private static final String K_NEXT_AT = "next_at";
    private static final String K_EXACT = "exact_ok";
    private static final String K_LAST_STATE = "last_state";

    private AppAlarm() {}

    /* ==================== 排下一次 ==================== */

    /** 排下一次定时点：开机、打开 App、每次闹钟响过之后都调一次 */
    public static void reschedule(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            long at = nextSlotAt(now);
            schedule(ctx, at, slotOf(at));
        } catch (Throwable ignored) {
            // 排闹钟失败不影响 App 其它功能
        }
    }

    private static void schedule(Context ctx, long atMs, int slot) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx, slot);
        boolean exact = canExact(am);

        long prevAt = sp(ctx).getLong(K_NEXT_AT, 0L);
        boolean prevExact = sp(ctx).getBoolean(K_EXACT, false);

        try {
            // 闹钟每次回到前台、每次响过都要重排（系统可能清掉过它），所以这里必须幂等：
            // 重排本身很便宜，但不能每次都往日志里写一行 —— 打开 App 会刷屏
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
            } else {
                // 没有精确闹钟权限时的退路：这个 API 在 Doze 里同样能触发、同样给临时放行，
                // 只是不保证准点（可能晚十几分钟）。对「每天签到一次」完全可以接受，
                // 而且它不需要用户授权。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
            }
            sp(ctx).edit().putLong(K_NEXT_AT, atMs).putBoolean(K_EXACT, exact).apply();

            if (atMs != prevAt || exact != prevExact) {
                AppStore.appSideLog(ctx, "INFO", "已排下一次闹钟 " + fmt(atMs) + "（定时点 " + hhmm(slot)
                        + "，" + (exact ? "精确" : "非精确：未授权精确闹钟，可能晚十几分钟") + "）");
            }
        } catch (Throwable t) {
            AppStore.appSideLog(ctx, "WARN", "排闹钟失败：" + t.getMessage());
        }
    }

    /* ==================== 时间计算 ==================== */

    /** 下一个定时点（本地时间 hh:01）；已经过了就取下一个 */
    public static long nextSlotAt(long fromMs) {
        for (int day = 0; day <= 1; day++) {
            for (int slot : SLOTS) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(fromMs);
                c.add(Calendar.DAY_OF_YEAR, day);
                c.set(Calendar.HOUR_OF_DAY, slot);
                c.set(Calendar.MINUTE, 1);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() > fromMs + 10_000L) return c.getTimeInMillis();
            }
        }
        return fromMs + 6 * 3600_000L;   // 理论到不了这里（day=1 必然命中）
    }

    private static int slotOf(long atMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(atMs);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /* ==================== 状态展示 ==================== */

    /** 设置页显示：下一次什么时候、有没有精确闹钟授权 */
    public static String describe(Context ctx) {
        long at = sp(ctx).getLong(K_NEXT_AT, 0L);
        if (at <= 0) return "未安排";
        boolean exact = sp(ctx).getBoolean(K_EXACT, false);
        String when = (at > System.currentTimeMillis())
                ? fmt(at) + "（还有 " + delta(at - System.currentTimeMillis()) + "）"
                : fmt(at) + "（已过，打开 App 会重排）";
        return when + " · " + (exact ? "精确闹钟已授权" : "未授权精确闹钟，只能近似触发");
    }

    /** 上一次闹钟执行的结果 */
    public static String lastState(Context ctx) {
        return sp(ctx).getString(K_LAST_STATE, "还没有执行过");
    }

    /**
     * 记一条执行结果，给设置页看。
     *
     * 为什么不只写日志：App 打开时会向模块拉一次日志整体快照（覆盖写入），
     * 只落在日志文件里的 App 侧记录会被冲掉，而设置页这份是留得住的。
     */
    public static void recordState(Context ctx, String text) {
        try {
            sp(ctx).edit().putString(K_LAST_STATE,
                    new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()) + " " + text)
              .apply();
        } catch (Throwable ignored) {
            // 记不上不影响执行
        }
    }

    public static boolean canExact(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && canExact(am);
    }

    private static boolean canExact(AlarmManager am) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 内部 ==================== */

    private static PendingIntent pending(Context ctx, int slot) {
        Intent i = new Intent(ctx, AppSignReceiver.class);
        i.setAction(ACTION_SLOT);
        i.putExtra(EXTRA_SLOT, slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags);
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static String hhmm(int slot) { return String.format("%02d:01", slot); }

    private static String fmt(long atMs) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(atMs));
    }

    private static String delta(long ms) {
        long m = Math.max(0, ms / 60_000L);
        if (m < 60) return m + " 分";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }
}
