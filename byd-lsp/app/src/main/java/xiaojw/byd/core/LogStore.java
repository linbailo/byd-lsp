package xiaojw.byd.core;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块侧日志。
 *
 * 原 byd_sign 把日志写到 /data/adb/byd_sign/logs/，由 WebUI 直接读文件。
 * 现在模块跑在微信进程里、控制台在另一个进程，两个进程的私有目录互相不可见，
 * 所以改为：内存环形缓冲 + 增量广播给控制台 App，由 App 侧负责落盘与按天清理。
 * 同时镜像一份到 LSPosed 日志，方便远程排障。
 */
public final class LogStore {

    private static final int MAX = 600;
    private static final Deque<JSONObject> BUF = new ArrayDeque<>();

    private static Context ctx;
    private static Handler worker;
    private static long seq = 0;
    private static long lastSent = 0;
    private static boolean flushQueued = false;
    /** 已推送给 App 的批次：跟 App 侧收到的批数对账，就能看出后台唤醒有没有被系统拦掉 */
    private static volatile long pushes = 0;

    private LogStore() {}

    public static synchronized void init(Context appCtx) {
        if (worker != null) return;
        ctx = appCtx.getApplicationContext();
        HandlerThread t = new HandlerThread("byd-lsp-log");
        t.start();
        worker = new Handler(t.getLooper());
    }

    public static void log(String level, String msg) {
        XposedBridge.log("[byd-lsp] " + level + " " + msg);
        JSONObject o = new JSONObject();
        try {
            o.put("ts", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            o.put("level", level);
            o.put("msg", msg == null ? "" : msg);
        } catch (Exception ignored) {
            return;
        }
        synchronized (BUF) {
            // seq 必须在锁内分配：日志线程和 flush 线程都要用它当水位，
            // 之前先在锁外自增、再入缓冲，flush 可能把水位抬到一条还没进缓冲的 seq 上，
            // 那条日志之后永远不会被增量推送出去（只有整体快照能补）。
            try {
                o.put("seq", ++seq);
            } catch (Exception ignored) {
                return;
            }
            BUF.addLast(o);
            while (BUF.size() > MAX) BUF.removeFirst();
        }
        scheduleFlush();
    }

    /** 已推送的批次数（给 App 侧对账用） */
    public static long pushes() { return pushes; }

    /** 当前日志序号（整体快照里最后一条的 seq） */
    public static long currentSeq() { return seq; }

    public static void info(String m) { log("INFO", m); }
    public static void ok(String m)   { log("OK", m); }
    public static void warn(String m) { log("WARN", m); }
    public static void err(String m)  { log("ERR", m); }

    /** 状态快照（含实例、日期、结果），与日志分开推送 */
    public static void pushState(String json) {
        Ipc.toApp(ctx, Ipc.ACTION_STATE, json);
    }

    /**
     * 立即把缓冲区里尚未推送的日志发出去。
     * 签到收尾时显式调用一次，避免「最后几行刚好落在合并窗口里」而丢失。
     */
    public static void flushNow() {
        final Handler h = worker;
        if (h == null) { doFlush(); return; }
        h.post(LogStore::doFlush);
    }

    /**
     * 把整个缓冲区重推一遍（忽略 lastSent）。
     * 控制台冷启动或点「刷新」时用：模块侧的缓冲比 App 侧文件更完整，
     * 能补回 App 进程不在时错过的那几条广播。
     */
    public static void pushAll() {
        final Handler h = worker;
        if (h == null) { doFlushAll(); return; }
        h.post(LogStore::doFlushAll);
    }

    private static void doFlush() {
        JSONArray arr = new JSONArray();
        synchronized (BUF) {
            long max = lastSent;
            for (JSONObject o : BUF) {
                long s = o.optLong("seq", 0);
                if (s > lastSent) arr.put(o);
                if (s > max) max = s;
            }
            // 水位只抬到「真的进过缓冲的最大 seq」，绝不越过还没入缓冲的那条
            lastSent = max;
        }
        if (arr.length() > 0) send(arr);
    }

    /** 整体快照：用覆盖动作发出去，App 侧不再追加而是替换，避免同一批日志反复叠加 */
    private static void doFlushAll() {
        JSONArray arr = new JSONArray();
        synchronized (BUF) {
            for (JSONObject o : BUF) arr.put(o);
        }
        if (arr.length() > 0 && ctx != null) {
            pushes++;
            Ipc.toApp(ctx, Ipc.ACTION_LOG_SNAPSHOT, arr.toString());
        }
    }

    private static void send(JSONArray arr) {
        if (ctx != null) {
            pushes++;
            Ipc.toApp(ctx, Ipc.ACTION_LOG, arr.toString());
        }
    }


    public static void clear() {
        synchronized (BUF) { BUF.clear(); }
        lastSent = seq;
    }

    /**
     * 日志推送合并到 1 秒一次：签到流程会连打十几行，
     * 逐条广播会在微信进程里制造没必要的跨进程唤醒。
     */
    private static void scheduleFlush() {
        final Handler h = worker;
        if (h == null) {
            // init 还没跑（极早期日志）：直接同步发一次，别让日志只留在内存里
            doFlush();
            return;
        }
        synchronized (LogStore.class) {
            if (flushQueued) return;
            flushQueued = true;
        }
        h.postDelayed(() -> {
            synchronized (LogStore.class) { flushQueued = false; }
            doFlush();
        }, 800);
    }
}
