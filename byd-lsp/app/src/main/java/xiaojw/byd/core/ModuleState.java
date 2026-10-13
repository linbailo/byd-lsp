package xiaojw.byd.core;

import android.content.Context;
import android.os.Process;
import android.os.UserHandle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块在微信进程内的运行时身份。
 *
 * 原 wxcode 需要算出 userId 来分配 HTTP 端口（8088 + userId）并做多实例注册；
 * 现在没有端口了，userId 只用于在控制台上把「主应用 / 分身」区分开。
 */
public final class ModuleState {

    public static volatile String versionName = "unknown";
    public static volatile int userId = 0;
    /** 本模块自身的版本号，握手里回给 App，用来确认跑的是哪一版 */
    public static volatile String moduleVersion = "unknown";

    /** 当前微信版本是否在适配表里精确命中 */
    public static volatile boolean versionExact = false;
    /** 适配状态说明，直接展示在控制台首页 */
    public static volatile String versionNote = "尚未检测";
    /** 探测状态：idle / running / ok / failed */
    public static volatile String probeState = "idle";
    /** 探测失败原因（用于首页红字提示） */
    public static volatile String probeReason = "";

    private static final Pattern USER_DIR = Pattern.compile("/data/user/(\\d+)/");

    private ModuleState() {}

    /** 当前实例的展示名：主用户是「主应用」，分身/多开是「分身 N」 */
    public static String instanceName() {
        return userId == 0 ? "主应用" : "分身 " + userId;
    }

    /** 三种策略依次兜底，最终退回 0（主用户） */
    public static int detectUserId(Context ctx) {
        Integer u = viaMyUserId();
        if (u != null) return u;
        u = viaProcessUserHandle();
        if (u != null) return u;
        u = viaDataDir(ctx);
        if (u != null) return u;
        XposedBridge.log("[byd-lsp] 无法获取 userId，按主用户 0 处理");
        return 0;
    }

    private static Integer viaMyUserId() {
        try {
            Method m = UserHandle.class.getDeclaredMethod("myUserId");
            m.setAccessible(true);
            Object v = m.invoke(null);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer viaProcessUserHandle() {
        try {
            UserHandle h = Process.myUserHandle();
            Field f = UserHandle.class.getDeclaredField("mHandle");
            f.setAccessible(true);
            Object v = f.get(h);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer viaDataDir(Context ctx) {
        try {
            String path = ctx.getFilesDir().getAbsolutePath();
            Matcher m = USER_DIR.matcher(path);
            if (m.find()) return Integer.parseInt(m.group(1));
            return path.startsWith("/data/data/") ? 0 : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
