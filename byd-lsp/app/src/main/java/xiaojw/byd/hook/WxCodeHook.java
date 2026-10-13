package xiaojw.byd.hook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import xiaojw.byd.core.BydConfig;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import xiaojw.byd.core.LogStore;

/**
 * 从微信进程内部拿到小程序登录 code。
 *
 * 这是原 wxcode 模块 {@code WxLoginHook.doLogin} 的移植，但去掉了 HTTP 服务、
 * 端口计算、实例注册与保活 Service —— 那些都只是为了把 code「运出」微信进程。
 * 现在调用方就是同进程内的签到引擎，直接返回 code 字符串即可。
 *
 * 原理不变：反射实例化 {@code JsApiLogin$LoginTask}，填好字段后沿
 * {@code j1.d().g(c)} 触发微信自己的登录流程，再轮询 LoginTask 字段拿结果。
 *
 * 字段对应关系（原实现里极易读错，这里注明）：
 *   LoginTask.r  —— 状态位，非 null 表示流程已结束
 *   LoginTask.q  —— 真正的 code，客户端拿去换 session_id 的就是它
 *
 * 版本适配数据不再硬编码在本类里，改由 {@link WxVersionTable} 提供
 * （内置 assets/wxcode_versions.json + 控制台覆盖表 + 运行时 dex 探测结果）。
 */
public final class WxCodeHook {

    private static final String TAG = "[byd-lsp]";
    private static final String WECHAT_PREFIX = "com.tencent.mm";
    /** 轮询间隔：太快只是徒增微信进程的唤醒次数 */
    private static final long POLL_INTERVAL_MS = 300L;

    /* 国内版与 Google Play 版的登录代码在完全不同的包下，
       回调类也各在 plugin.appbrand.jsapi.auth / plugin.type.jsapi.auth 里。
       写死其中一个会让另一个发行版直接找不到类，所以运行时探测。 */
    private static final String LOGIN_TASK_DOMESTIC =
            "com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask";
    private static final String LOGIN_TASK_PLAY =
            "com.tencent.mm.plugin.type.jsapi.auth.JsApiLogin$LoginTask";
    private static final String PKG_DOMESTIC = "plugin.appbrand.jsapi.auth";
    private static final String PKG_PLAY = "plugin.type.jsapi.auth";

    /** 取码失败原因；retryable 区分「微信侧偶发无响应」与「版本不适配」 */
    public static final class CodeException extends Exception {
        public final boolean retryable;
        public CodeException(String msg, boolean retryable) {
            super(msg);
            this.retryable = retryable;
        }
    }

    private final Context wechatCtx;
    private final ClassLoader classLoader;
    private final String version;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** 版本表可能在探测完成后被替换，因此整体 volatile */
    private volatile WxVersionTable table;
    private volatile String j1 = "";
    private volatile String c = "";
    private volatile String a1 = "";
    private volatile String a7 = "";
    private volatile String j1StaticMethod = "d";
    private volatile String j1InstanceMethod = "g";
    private volatile WxVersionTable.Entry applied;
    private volatile boolean exactMatch = false;
    private volatile String sourceNote = "";
    // 反射结果缓存：Class / Constructor 解析出来就不再重算。
    // 之前每次取码都要重新 findClass + 遍历所有构造与方法，
    // 而这些都会触发类加载校验，正好落在微信的 UI 线程上就会卡顿。
    private volatile Class<?> cLoginTask, cA1, cA7, cC, cJ1;
    private volatile Constructor<?> ctorA1, ctorA7, ctorC;

    private volatile String loginTaskClassName = LOGIN_TASK_DOMESTIC;
    private volatile String callbackPackage = PKG_DOMESTIC;

    private HandlerThread workerThread;
    private Handler workerHandler;

    public WxCodeHook(Context wechatCtx, ClassLoader classLoader, String versionName, WxVersionTable table) {
        this.wechatCtx = wechatCtx;
        this.classLoader = classLoader;
        this.version = versionName == null ? "" : versionName;
        this.table = table;
        detectEdition();
        resolve();
    }

    /** 探测当前是国内版还是 Play 版：两套登录链的类路径完全不同 */
    private void detectEdition() {
        if (exists(LOGIN_TASK_DOMESTIC)) {
            loginTaskClassName = LOGIN_TASK_DOMESTIC;
            callbackPackage = PKG_DOMESTIC;
        } else if (exists(LOGIN_TASK_PLAY)) {
            loginTaskClassName = LOGIN_TASK_PLAY;
            callbackPackage = PKG_PLAY;
        } else {
            loginTaskClassName = LOGIN_TASK_DOMESTIC;
            callbackPackage = PKG_DOMESTIC;
        }
    }

    private boolean exists(String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isPlayEdition() { return PKG_PLAY.equals(callbackPackage); }

    /* ==================== 版本适配 ==================== */

    /**
     * 精确命中则用该版本配置；未收录时退回「不高于当前版本的最新一条」尽力尝试，
     * 并由 {@link #isExactMatch()} 告知调用方需要触发 dex 探测。
     */
    private void resolve() {
        clearRefCache();          // 配置变了，缓存的类/构造要重算
        WxVersionTable.Entry exact = table.resolve(version);
        WxVersionTable.Entry use = exact;
        if (use == null) {
            use = table.nearest(version);
        }

        if (use == null) {
            j1 = c = a1 = a7 = "";
            exactMatch = false;
            sourceNote = "版本表里没有任何可用配置";
            LogStore.err("版本表中没有任何可用配置，取码必然失败");
            return;
        }

        applied = use;
        j1 = nz(use.j1);
        c = nz(use.c);
        a1 = nz(use.a1);
        a7 = nz(use.a7);
        j1StaticMethod = nz(use.j1StaticMethod).isEmpty() ? "d" : use.j1StaticMethod;
        j1InstanceMethod = nz(use.j1InstanceMethod).isEmpty() ? "g" : use.j1InstanceMethod;

        // 回调类必须落在当前发行版的包里，否则取码时报 ClassNotFound 很难查
        boolean editionOk = editionMatches(a1);
        exactMatch = exact != null && exact.version.equals(version) && editionOk;

        if (!editionOk) {
            sourceNote = "配置的回调包（" + shortForm(a1) + "）与当前发行版（"
                    + (isPlayEdition() ? "Play 版 " + PKG_PLAY : "国内版 " + PKG_DOMESTIC)
                    + "）不符，需要重新探测";
        } else if (exact == null) {
            sourceNote = "未收录，暂用 " + use.version + " 的配置尽力尝试";
        } else {
            sourceNote = "版本表命中（" + (isPlayEdition() ? "Play 版" : "国内版") + "）";
        }
    }

    private void clearRefCache() {
        cLoginTask = cA1 = cA7 = cC = cJ1 = null;
        ctorA1 = ctorA7 = ctorC = null;
    }

    private boolean editionMatches(String a1v) {
        if (a1v == null || a1v.isEmpty()) return false;
        return shortForm(a1v).startsWith(callbackPackage);
    }

    private static boolean same(String a, String b) {
        return a != null && a.equals(b);
    }

    /** dex 内部名 com/foo/Bar → com.foo.Bar */
    private static String dotted(String internal) {
        return internal == null ? null : internal.replace('/', '.');
    }

    private static String shortForm(String cls) {
        return cls.startsWith(WECHAT_PREFIX + ".") ? cls.substring(WECHAT_PREFIX.length() + 1) : cls;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * a1 / a7 允许写短名或全限定名。
     * 参考仓库 xiaojinwen/wxcode-hook-config 里两种写法都出现过
     * （国内版写 plugin.appbrand.jsapi.auth.h2，Play 版写 com.tencent.mm.plugin.type.jsapi.auth.l2），
     * 这里统一补成全限定名，避免直接粘贴原值时被拼成
     * com.tencent.mm.com.tencent.mm.plugin… 这种必然找不到的类名。
     */
    private static String qualify(String cls) {
        if (cls == null || cls.isEmpty()) return cls;
        return cls.startsWith(WECHAT_PREFIX + ".") ? cls : WECHAT_PREFIX + "." + cls;
    }

    /**
     * 补一层「本机探测结果」。
     *
     * 控制台推来的配置里如果没有当前微信版本的条目（用户没保存过、或客户端把
     * versionTable 推成了 null），就用模块自己记账里那份探测结果 ——
     * 否则一次配置推送就会把刚探测出来的配置冲回内置表的「就近匹配」，
     * 表现就是「探测成功、却按 8.0.78 的类名取码然后 ClassNotFound」。
     *
     * 调用方在返回 true 时应当把 {@link #table()} 回推给控制台 ——
     * 否则控制台那份永远缺这个版本，每次推配置都要在这里补一次，日志里那行提示会无限重复。
     *
     * @return 是否真的补上了
     */
    public boolean applyProbeLayer(String probeFragmentJson) {
        if (exactMatch) return false;                       // 已经收录，不用补
        if (probeFragmentJson == null || probeFragmentJson.isEmpty()) return false;
        try {
            if (!probeFragmentJson.contains("\"" + version + "\"")) return false;   // 没有本版本的条目
            mergeTableJson(probeFragmentJson);
            LogStore.info("控制台配置里没有微信 " + version + " 的条目，已用本机探测结果补上");
            return isExactMatch();
        } catch (Throwable t) {
            LogStore.warn("补探测结果失败: " + t.getMessage());
            return false;
        }
    }

    /** 当前微信版本是否已收录（决定首页显示绿色还是红色提示） */
    public boolean isExactMatch() { return exactMatch; }


    /**
     * 把版本表**整表重建**：内置表 + 这份覆盖层。
     *
     * 用重建而不是增量合并，是因为增量合并会保留内存里已有的条目 ——
     * 早期版本探测生成过的错误值（例如把 j1_instance_method 写成 d）会一直留在表里，
     * 即使控制台那边已经把它清掉了。
     */
    public void setTableJson(String overrideJson) {
        table = WxVersionTable.load(overrideJson);
        resolve();
        LogStore.info("版本表已按控制台配置重建：" + describeConfig());
    }

    /**
     * 并入一份版本表片段（只用于运行时 dex 探测生成的新条目）。
     * 控制台下发的完整表走 {@link #setTableJson}。
     */
    public void mergeTableJson(String tableJson) {
        if (tableJson == null || tableJson.isEmpty()) return;
        table = table.copyWith(tableJson);
        resolve();
        LogStore.info("已并入探测结果：" + describeConfig());
    }

    public WxVersionTable table() { return table; }

    /**
     * 当前**实际生效**的配置快照，回给控制台核对用。
     * 把它和版本表并排一放，就能判断「配置到底有没有写进模块」。
     */
    public String effectiveConfigJson(BydConfig cfg) {
        JSONObject o = new JSONObject();
        try {
            o.put("version", version);
            o.put("edition", isPlayEdition() ? "Play 版" : "国内版");
            o.put("exact", exactMatch);
            o.put("sourceNote", sourceNote);
            o.put("j1", j1);
            o.put("c", c);
            o.put("a1", a1);
            o.put("a7", a7);
            o.put("j1StaticMethod", j1StaticMethod);
            o.put("j1InstanceMethod", j1InstanceMethod);
            o.put("tableVersions", new JSONArray(table.knownVersions()));
            JSONObject lt = new JSONObject();
            for (String role : new String[]{"action", "appId", "s", "v", "t", "u", "A", "state", "code"}) {
                lt.put(role, fieldName(role));
            }
            o.put("loginTask", lt);
            o.put("runtime", runtimeJson(cfg));
            o.put("at", System.currentTimeMillis());
        } catch (Exception ignored) {
            // 纯字符串写入不会抛
        }
        return o.toString();
    }

    /**
     * 运行时开关的**真实取值**（模块侧读到的，而不是控制台界面上的）。
     *
     * 这一块是为了回答「我拨的开关到底生效了没有」：之前这里只报版本表相关的东西，
     * 开关类设置（自动签到 / 当天不再重复 / 通知策略 / dex 自动解析…）在模块侧
     * 完全看不到，只能靠猜。
     */
    private JSONObject runtimeJson(BydConfig cfg) {
        JSONObject r = new JSONObject();
        if (cfg == null) return r;
        try {
            r.put("enabled", cfg.enabled);
            r.put("dedupe", cfg.dedupe);
            r.put("autoProbe", cfg.autoProbe);
            r.put("retry", cfg.retry);
            r.put("interval", cfg.interval);
            r.put("codeTimeout", cfg.codeTimeout);
            r.put("keep", cfg.keep);
            r.put("mailEnabled", cfg.mailEnabled);
            r.put("mailUsable", cfg.mailUsable());
            r.put("hookEnabled", cfg.hookEnabled);
            r.put("hookType", cfg.hookType);
            r.put("webhookUsable", cfg.webhookUsable());
            r.put("notifySuccess", cfg.notifySuccess);
            r.put("notifyFailure", cfg.notifyFailure);
        } catch (Exception ignored) {
            // 读不到就少报几项，不影响别的
        }
        return r;
    }

    public String describeConfig() {
        return "微信 " + version + "（" + (isPlayEdition() ? "Play 版" : "国内版") + "）"
                + " | j1=" + j1 + " c=" + c
                + " a1=" + a1 + " a7=" + a7
                + " | static=" + j1StaticMethod + " instance=" + j1InstanceMethod
                + " | " + sourceNote;
    }

    /* ==================== 取码主流程 ==================== */

    /**
     * 取码。
     *
     * @param timeoutMs 轮询等待上限。原 wxcode 的 doLogin 用的是 30000ms；
     *                  微信登录要过网络，给短了会把「慢」误判成「失败」。
     */
    public String getCode(String appId, long timeoutMs) throws CodeException {
        validate(appId);
        if (!inFlight.compareAndSet(false, true)) throw new CodeException("已有取码请求正在处理中", true);

        PowerManager.WakeLock lock = acquireWakeLock(timeoutMs * 2);
        try {
            LogStore.info("开始取码，appId=" + appId + "，等待上限 " + (timeoutMs / 1000) + "s");
            try {
                Object loginTask = submitLogin(appId, null, j1InstanceMethod);
                return awaitCode(loginTask, timeoutMs);

            } catch (CodeException first) {
                // 实例方法名调错时的表现是「不抛异常、登录一直不返回」，
                // 光看日志分不出来。所以这里自动换同类上的另一个候选再试一次。
                String alt = alternativeInstanceMethod();
                if (!first.retryable || alt == null) throw first;
                LogStore.warn("按 ." + j1InstanceMethod + "(c) 取码无响应，改用同类上的 ."
                        + alt + "(c) 重试一次");
                Object loginTask = submitLogin(appId, null, alt);
                String code = awaitCode(loginTask, timeoutMs);
                LogStore.ok("改用 ." + alt + "(c) 后取到了 code —— "
                        + "请把 j1_instance_method 改成 " + alt);
                return code;
            }
        } catch (CodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new CodeException("取码失败（" + describeConfig() + "）→ "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
        } finally {
            releaseWakeLock(lock);
            inFlight.set(false);
        }
    }

    private void validate(String appId) throws CodeException {
        if (appId == null || appId.isEmpty()) throw new CodeException("未配置小程序 AppID", false);
        if (j1.isEmpty() || c.isEmpty() || a1.isEmpty() || a7.isEmpty()) {
            throw new CodeException("当前微信 " + version + " 没有可用的 Hook 配置，"
                    + "请在控制台「配置」页更新版本适配表", false);
        }
    }

    /**
     * 构造 LoginTask 并发起登录，返回 loginTask 实例，结果由调用方轮询。
     *
     * @param trace 非 null 时逐步记录过程，供「尝试取码」展示
     */
    private Object submitLogin(String appId, List<String> trace, String instanceMethod) throws Throwable {
        Class<?> loginTaskCls = cLoginTask;
        if (loginTaskCls == null) {
            loginTaskCls = XposedHelpers.findClass(loginTaskClassName, classLoader);
            cLoginTask = loginTaskCls;
        }
        if (cA1 == null) cA1 = XposedHelpers.findClass(qualify(a1), classLoader);
        if (cA7 == null) cA7 = XposedHelpers.findClass(qualify(a7), classLoader);
        // c / j1 的混淆包名本身就是顶层包（形如 he0.c、of0.j1），不加 com.tencent.mm 前缀
        if (cC == null) cC = XposedHelpers.findClass(c, classLoader);
        if (cJ1 == null) cJ1 = XposedHelpers.findClass(j1, classLoader);
        Class<?> a1Cls = cA1, a7Cls = cA7, cCls = cC, j1Cls = cJ1;
        note(trace, "加载类完成：" + loginTaskClassName + " / " + qualify(a1) + " / "
                + qualify(a7) + " / " + c + " / " + j1);

        // 字段名按角色映射，默认取自原 wxcode（o/p/s/v/t/u/A）；
        // 微信改过名字时可在「配置」页覆盖，不必重新编译
        Object loginTask = XposedHelpers.newInstance(loginTaskCls);
        setRoleField(loginTask, "action", "login");
        setRoleField(loginTask, "appId", appId);
        setRoleField(loginTask, "s", Integer.valueOf(1));
        setRoleField(loginTask, "v", "");
        setRoleField(loginTask, "t", Integer.valueOf(0));
        setRoleField(loginTask, "u", Integer.valueOf(0));
        setRoleField(loginTask, "A", Integer.valueOf(1271));
        note(trace, "LoginTask 字段已写入：" + describeLoginTaskFields());

        if (ctorA1 == null) ctorA1 = findSingleArgConstructor(a1Cls, loginTaskCls);
        if (ctorA7 == null) ctorA7 = findTwoArgCtor(a7Cls, loginTaskCls, a1Cls);
        if (ctorC == null) ctorC = findEightArgConstructor(cCls);
        Object a1Obj = ctorA1.newInstance(loginTask);
        Object a7Obj = ctorA7.newInstance(loginTask, a1Obj);
        Object cObj = ctorC.newInstance(appId, new LinkedList<>(), 1, "", "", 0, 1271, a7Obj);
        note(trace, "a1 / a7 / c 构造成功；c 对象字段：" + fieldValuesOf(cObj));

        Object submitter = XposedHelpers.callStaticMethod(j1Cls, j1StaticMethod);
        note(trace, "j1." + j1StaticMethod + "() → "
                + (submitter == null ? "null（下一步必然失败）" : submitter.getClass().getName()));
        Object ret = XposedHelpers.callMethod(submitter, instanceMethod, cObj);
        note(trace, "调用 ." + instanceMethod + "(c) 返回：" + asString(ret)
                + "（false 通常表示被拒绝入队）");
        // 只在诊断模式下才枚举候选：这行会多扫一遍 j1 单例的方法，正常取码不该付这个代价
        if (trace != null) {
            note(trace, "同类上接收 c 的候选方法：" + String.join(", ", submittersOf(cCls)));
        }
        return loginTask;
    }

    private static void note(List<String> trace, String msg) {
        if (trace != null) trace.add(msg);
    }

    /* ---------- 取码期间的短时唤醒锁 ---------- */

    /**
     * 原 wxcode 在发起登录前会先把进程优先级提上去（KeepAliveService），
     * 避免后台被限流导致登录请求卡住。我们没法往微信的清单里加 Service，
     * 但可以在等待期间持一个 PARTIAL_WAKE_LOCK，至少保证 CPU 不被挂起。
     */
    private PowerManager.WakeLock acquireWakeLock(long timeoutMs) {
        if (wechatCtx == null) return null;
        try {
            PowerManager pm = (PowerManager) wechatCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "byd-lsp:code");
            lock.setReferenceCounted(false);
            lock.acquire(timeoutMs + 5000);
            return lock;
        } catch (Throwable t) {
            LogStore.warn("获取唤醒锁失败（只影响休眠场景，不影响取码本身）: " + t.getMessage());
            return null;
        }
    }


    /**
     * 当前微信进程的重要性：100=前台，125=前台服务，200=可见，>=400 基本是后台/缓存。
     * 取码要微信自己跑登录流程，它在后台时这个流程可能长时间不动 —— 把数值报出来，
     * 才能区分「取不到码」是配置问题还是微信被系统冻结了。
     */
    int processImportance() {
        if (wechatCtx == null) return -1;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    wechatCtx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            android.app.ActivityManager.RunningAppProcessInfo info =
                    new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(info);
            return info.importance;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void releaseWakeLock(PowerManager.WakeLock lock) {
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
            // 释放失败无害
        }
    }

    /**
     * 等待 status 字段有值，再读 code 字段。
     * 原实现用主线程 Handler + 锁等待；这里用独立 HandlerThread + CountDownLatch。
     */
    private String awaitCode(final Object loginTask, final long timeoutMs) throws CodeException {
        ensureWorker();
        final String stateField = fieldName("state");
        final String codeField = fieldName("code");
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] code = new String[1];
        final Throwable[] failure = new Throwable[1];
        final boolean[] timedOut = new boolean[1];
        final long start = SystemClock.uptimeMillis();

        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object state = getField(loginTask, stateField);
                    if (state == null) {
                        if (SystemClock.uptimeMillis() - start < timeoutMs) {
                            workerHandler.postDelayed(this, POLL_INTERVAL_MS);
                            return;
                        }
                        timedOut[0] = true;
                        latch.countDown();
                        return;
                    }
                    Object raw = getField(loginTask, codeField);
                    code[0] = raw == null ? null : String.valueOf(raw);
                    latch.countDown();
                } catch (Throwable t) {
                    failure[0] = t;
                    latch.countDown();
                }
            }
        });

        boolean done;
        try {
            done = latch.await(timeoutMs + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeException("取码等待被中断", true);
        }

        if (!done || timedOut[0]) {
            long waited = SystemClock.uptimeMillis() - start;
            // 用 ASCII 变量名：中文标识符虽然合法，但一旦漏了空格就会被当成单个标识符
            String scene = fieldValuesOf(loginTask);
            XposedBridge.log(TAG + " 取码超时 " + describeConfig() + " 字段现场: " + scene);
            throw new CodeException("取码超时（等了 " + (waited / 1000) + " 秒，上限 " + (timeoutMs / 1000)
                    + " 秒）。微信进程 importance=" + processImportance()
                    + "（100 才是前台；≥400 说明微信在后台，登录流程可能被冻结）。"
                    + "LoginTask 字段现场：" + scene, true);
        }
        if (failure[0] != null) {
            String extra = (failure[0] instanceof NoSuchFieldException)
                    ? "（本机实际字段：[" + fieldNamesOf(loginTask.getClass()) + "]，"
                      + "可在「配置」页的 loginTask 里改 state/code 的映射）"
                    : "";
            throw new CodeException("读取登录结果字段失败: " + failure[0].getMessage() + extra, false);
        }
        if (code[0] == null || code[0].isEmpty()) {
            throw new CodeException("微信返回的 code 为空。LoginTask 字段现场："
                    + fieldValuesOf(loginTask), false);
        }

        LogStore.ok("取得 code（" + classifyCode(code[0]) + "，长度 " + code[0].length() + "）");
        return code[0];
    }

    /**
     * 同类上其它「接收 c 的 1 参方法」候选。
     *
     * 存在的理由：版本表里的 j1_instance_method 是某个微信构建里实测出来的，
     * 换构建后可能漂移。而**调错方法不会抛异常**，只会让登录一直不返回 ——
     * 从日志上完全看不出来。所以留一条自动换候选的路。
     */
    private String alternativeInstanceMethod() {
        try {
            Class<?> cCls = XposedHelpers.findClass(c, classLoader);
            List<String> all = submittersOf(cCls);
            if (all.size() != 2) return null;      // 候选不唯一就不乱试
            for (String m : all) {
                if (!m.equals(j1InstanceMethod)) return m;
            }
        } catch (Throwable t) {
            LogStore.warn("枚举候选实例方法失败: " + t.getMessage());
        }
        return null;
    }

    /** j1 的静态方法返回的实例上，所有「单参且参数可接收 c」的 1 字母方法名 */
    private List<String> submittersOf(Class<?> cCls) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> j1Cls = XposedHelpers.findClass(j1, classLoader);
            Object holder = XposedHelpers.callStaticMethod(j1Cls, j1StaticMethod);
            if (holder == null) return out;
            for (Method m : holder.getClass().getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                if (!m.getName().matches("[a-z]")) continue;
                if (!p[0].isAssignableFrom(cCls)) continue;
                out.add(m.getName());
            }
        } catch (Throwable ignored) {
            // 枚举失败就当作没有候选
        }
        return out;
    }

    /* ==================== 尝试取码（诊断） ==================== */

    /**
     * 真的跑一次取码，并把过程完整记录下来。
     *
     * 与「自检」的区别：自检只验证反射结构能不能对上，这里会**真的发起登录**、
     * 每 500ms 采样一次 status/code，最后把 LoginTask 所有字段的**实际值**列出来 ——
     * 于是「status 一直没变」「code 落在了别的字段上」这类问题一眼就能看出来。
     */
    public String diagnose(String appId, long timeoutMs) {
        JSONObject out = new JSONObject();
        List<String> trace = new ArrayList<>();
        long start = SystemClock.uptimeMillis();
        boolean locked = false;
        PowerManager.WakeLock lock = acquireWakeLock(timeoutMs);
        try {
            validate(appId);
            if (!inFlight.compareAndSet(false, true)) throw new CodeException("已有取码请求在处理中", true);
            locked = true;

            out.put("appId", appId);
            out.put("timeoutMs", timeoutMs);
            Object loginTask = submitLogin(appId, trace, j1InstanceMethod);

            JSONArray samples = new JSONArray();
            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            String status = null, codeVal = null;
            while (SystemClock.uptimeMillis() < deadline) {
                status = asString(getFieldOrNull(loginTask, fieldName("state")));
                codeVal = asString(getFieldOrNull(loginTask, fieldName("code")));
                samples.put((SystemClock.uptimeMillis() - start) + "ms  importance="
                        + processImportance() + "（100=前台）  status=" + status
                        + "  code=" + shorten(codeVal));
                if (status != null) break;
                Thread.sleep(500);
            }

            boolean hit = status != null && codeVal != null && !"null".equals(codeVal) && !codeVal.isEmpty();
            out.put("status", status);
            out.put("code", hit ? codeVal : "");
            out.put("codeType", hit ? classifyCode(codeVal) : "");
            out.put("codeLength", hit ? codeVal.length() : 0);
            out.put("ok", hit);
            out.put("elapsedMs", SystemClock.uptimeMillis() - start);
            out.put("samples", samples);
            out.put("loginTaskFields", fieldValuesOf(loginTask));
            out.put("trace", new JSONArray(trace));
            out.put("at", System.currentTimeMillis());
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                out.put("trace", new JSONArray(trace));
                out.put("elapsedMs", SystemClock.uptimeMillis() - start);
                out.put("at", System.currentTimeMillis());
            } catch (Exception ignored) {
                // JSONObject 写入字符串不会抛
            }
            LogStore.err("尝试取码失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (locked) inFlight.set(false);
            releaseWakeLock(lock);
        }
        LogStore.info("尝试取码结束：" + (out.optBoolean("ok") ? "拿到 code" : "没拿到 code"));
        return out.toString();
    }

    /* ---------- 值 dump（定位 code 到底落在哪个字段） ---------- */

    private static Object getFieldOrNull(Object obj, String name) {
        try {
            return getField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fieldValuesOf(Object obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(obj);
                } catch (Throwable t) {
                    v = "<读不到>";
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(f.getName()).append('=').append(shorten(asString(v)));
            }
        } catch (Throwable t) {
            return "<枚举字段失败: " + t.getMessage() + ">";
        }
        return sb.toString();
    }

    private static String asString(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + v + "\"";
        if (v.getClass().isArray() || v instanceof java.util.Collection) return v.getClass().getSimpleName();
        return String.valueOf(v);
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() <= 48 ? s : s.substring(0, 48) + "…";
    }

    private void ensureWorker() {
        if (workerThread != null && workerThread.isAlive()) return;
        synchronized (this) {
            if (workerThread != null && workerThread.isAlive()) return;
            workerThread = new HandlerThread("byd-lsp-code");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
        }
    }

    /* ==================== 取码链路自检 ==================== */

    /**
     * 按 getCode 的每一步单独走一遍，但**不触发真正的登录**、也不等待 code，
     * 逐步报告哪一步失败，并把反射看到的真实字段名 / 构造签名打出来。
     *
     * 「签到没反应」时先跑这个：取码要同时凑齐 j1/c/a1/a7 四个类名**和**
     * LoginTask 的一批混淆字段名，而 xiaojinwen/wxcode-hook-config 只提取前四个，
     * 字段名漂移时版本表看起来是对的、实际却必然失败 —— 自检能把这种情况直接指出来。
     */
    public String selfTest() {
        JSONArray steps = new JSONArray();
        boolean[] ok = { true };

        addStep(steps, ok, "版本配置", true, describeConfig());
        addStep(steps, ok, "登录类", true, loginTaskClassName + "（" + (isPlayEdition() ? "Play 版" : "国内版") + "）");
        addStep(steps, ok, "LoginTask 字段映射", true, describeLoginTaskFields());

        Class<?> loginTaskCls = tryLoad(steps, ok, "加载 LoginTask", loginTaskClassName);
        Class<?> a1Cls = tryLoad(steps, ok, "加载 a1", qualify(a1));
        Class<?> a7Cls = tryLoad(steps, ok, "加载 a7", qualify(a7));
        Class<?> cCls = tryLoad(steps, ok, "加载 c", c);
        Class<?> j1Cls = tryLoad(steps, ok, "加载 j1", j1);

        // 用 JsApiLogin 的调用点独立算一遍，与当前生效配置对照 ——
        // 「自检全绿但取不到码」最常见的原因是配置命中了别的同形状类
        try {
            JsApiLoginAnalyzer.Result a = JsApiLoginAnalyzer.analyze(wechatCtx, isPlayEdition());
            if (a.complete()) {
                boolean match = same(a.j1, j1) && same(a.c, c)
                        && same(dotted(a.a1), shortForm(qualify(a1)))
                        && same(dotted(a.a7), shortForm(qualify(a7)))
                        && same(a.j1StaticMethod, j1StaticMethod)
                        && same(a.j1InstanceMethod, j1InstanceMethod);
                addStep(steps, ok, "JsApiLogin 调用点对照", match, match
                        ? "dex 里的调用点与当前配置一致：" + a.summary()
                        : "dex 里的调用点与当前配置不一致！dex 是：" + a.summary()
                          + "；当前配置：" + describeConfig()
                          + "（当前以版本表为准，可手动点「重新探测本机微信」覆盖）");
            } else {
                addStep(steps, ok, "JsApiLogin 调用点对照", false,
                        "没能从 dex 里解析出完整调用点：" + String.join("；", a.notes));
            }
        } catch (Throwable t) {
            addStep(steps, ok, "JsApiLogin 调用点对照", false,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        if (loginTaskCls != null) checkLoginTaskFields(steps, ok, loginTaskCls);

        Constructor<?> a1Ctor = null, a7Ctor = null, cCtor = null;

        if (a1Cls != null && loginTaskCls != null) {
            try {
                a1Ctor = findSingleArgConstructor(a1Cls, loginTaskCls);
                addStep(steps, ok, "a1 单参构造", true, a1Ctor.toString());
            } catch (Throwable t) {
                addStep(steps, ok, "a1 单参构造", false, "没找到接收 LoginTask 的单参构造；本类构造：" + signatures(a1Cls));
            }
        }

        if (a7Cls != null && loginTaskCls != null && a1Cls != null) {
            try {
                for (Constructor<?> ct : a7Cls.getDeclaredConstructors()) {
                    Class<?>[] ps = ct.getParameterTypes();
                    if (ps.length == 2 && ps[0].isAssignableFrom(loginTaskCls) && ps[1].isAssignableFrom(a1Cls)) {
                        a7Ctor = ct;
                        break;
                    }
                }
                if (a7Ctor == null) throw new NoSuchMethodException("没有 (LoginTask, a1) 双参构造");
                addStep(steps, ok, "a7 双参构造", true, a7Ctor.toString());
            } catch (Throwable t) {
                addStep(steps, ok, "a7 双参构造", false, "本类构造：" + signatures(a7Cls));
            }
        }

        if (cCls != null) {
            try {
                cCtor = findEightArgConstructor(cCls);
                addStep(steps, ok, "c 构造", true, cCtor.toString());
            } catch (Throwable t) {
                addStep(steps, ok, "c 构造", false, "本类构造：" + signatures(cCls));
            }
        }

        if (j1Cls != null && cCls != null) {
            List<String> cands = submittersOf(cCls);
            if (cands.size() > 1) {
                addStep(steps, ok, "j1 实例方法候选", true,
                        "同类上接收 c 的候选有 " + cands.size() + " 个：" + String.join(", ", cands)
                        + "；配置用的是 ." + j1InstanceMethod + "(c)"
                        + (cands.contains(j1InstanceMethod) ? "（在候选内）" : "（不在候选内，很可能错）"));
            }
            String[] hit = findSubmitterOn(j1Cls, cCls);
            if (hit == null) {
                addStep(steps, ok, "j1 调用链", false,
                        "没找到「静态无参方法 → 返回实例上有接收 c 的方法」这种形态；本类方法：" + methodsOf(j1Cls));
            } else {
                boolean cfgOk = hit[0].equals(j1StaticMethod) && hit[1].equals(j1InstanceMethod);
                String detail = "本机实际是 ." + hit[0] + "()." + hit[1] + "(c)";
                if (cfgOk) {
                    detail += "，与配置一致";
                } else {
                    detail += "，但配置里写的是 ." + j1StaticMethod + "()." + j1InstanceMethod
                            + "(c) —— 需要在「配置」页把 j1_static_method / j1_instance_method 改过来";
                }
                addStep(steps, ok, "j1 调用链", cfgOk, detail);
            }
        }

        // 试构造对象，验证参数能对上；到这一步为止都不会触发真实登录
        if (loginTaskCls != null && a1Ctor != null && a7Ctor != null && cCtor != null) {
            try {
                Object lt = XposedHelpers.newInstance(loginTaskCls);
                setRoleField(lt, "action", "login");
                setRoleField(lt, "appId", "selftest");
                setRoleField(lt, "s", Integer.valueOf(1));
                setRoleField(lt, "v", "");
                setRoleField(lt, "t", Integer.valueOf(0));
                setRoleField(lt, "u", Integer.valueOf(0));
                setRoleField(lt, "A", Integer.valueOf(1271));
                Object a1o = a1Ctor.newInstance(lt);
                Object a7o = a7Ctor.newInstance(lt, a1o);
                cCtor.newInstance("selftest", new LinkedList<>(), 1, "", "", 0, 1271, a7o);
                addStep(steps, ok, "试构造 LoginTask / a1 / a7 / c", true,
                        "全部成功（未触发登录；自检到此为止，最后一步触发登录由签到流程执行）");
            } catch (Throwable t) {
                addStep(steps, ok, "试构造 LoginTask / a1 / a7 / c", false,
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("ok", ok[0]);
            out.put("version", version);
            out.put("edition", isPlayEdition() ? "Play 版" : "国内版");
            out.put("at", System.currentTimeMillis());
            out.put("steps", steps);
        } catch (Exception ignored) {
            // 纯字符串写入不会抛
        }
        LogStore.info("取码自检完成：" + (ok[0] ? "全部通过" : "有步骤失败"));
        return out.toString();
    }

    private static void addStep(JSONArray arr, boolean[] ok, String name, boolean stepOk, String detail) {
        if (!stepOk) ok[0] = false;
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("ok", stepOk);
            o.put("detail", detail == null ? "" : detail);
        } catch (Exception ignored) {
            // 同上
        }
        arr.put(o);
    }

    private Class<?> tryLoad(JSONArray steps, boolean[] ok, String label, String className) {
        if (className == null || className.isEmpty()) {
            addStep(steps, ok, label, false, "配置里为空");
            return null;
        }
        try {
            Class<?> k = XposedHelpers.findClass(className, classLoader);
            addStep(steps, ok, label, true, className);
            return k;
        } catch (Throwable t) {
            addStep(steps, ok, label, false,
                    className + " → " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** 逐个角色检查字段是否存在、类型是否合理；缺失时把本机真实字段名（含序号）报出来 */
    private void checkLoginTaskFields(JSONArray steps, boolean[] ok, Class<?> k) {
        String[] roles = {"action", "appId", "s", "v", "t", "u", "A", "state", "code"};
        StringBuilder sb = new StringBuilder();
        boolean allFound = true;
        for (String role : roles) {
            String name = fieldName(role);
            Field f = null;
            try {
                f = k.getDeclaredField(name);
            } catch (Throwable ignored) {
                // 缺失
            }
            if (sb.length() > 0) sb.append(' ');
            if (f == null) {
                allFound = false;
                sb.append(role).append('=').append('"').append(name).append("\"(缺失)");
            } else {
                sb.append(role).append('=').append('"').append(name).append("\":").append(simpleType(f.getType()));
            }
        }
        if (!allFound) {
            sb.append("　｜　本机实际字段：[")
              .append(fieldNamesOf(k))
              .append("]，可按序号对应着改 loginTask 映射");
        }
        addStep(steps, ok, "LoginTask 字段逐个校验", allFound, sb.toString());
    }

    private String describeLoginTaskFields() {
        StringBuilder sb = new StringBuilder();
        for (String role : new String[]{"action", "appId", "s", "v", "t", "u", "A", "state", "code"}) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(role).append('=').append(fieldName(role));
        }
        return sb.toString();
    }

    /* ---------- 字段读写 ---------- */

    private String fieldName(String role) {
        WxVersionTable.Entry e = applied;
        String n = e == null ? null : e.loginTaskField(role);
        return (n == null || n.isEmpty()) ? "?" : n;
    }

    /** 按角色写 LoginTask 字段；名字对不上时把真实字段名报出来，便于在配置页改映射 */
    private void setRoleField(Object obj, String role, Object val) throws CodeException {
        String name = fieldName(role);
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, val);
        } catch (NoSuchFieldException e) {
            throw new CodeException("LoginTask 字段名不匹配：角色 " + role + " 映射到 \"" + name
                    + "\"，本机实际字段 [" + fieldNamesOf(obj.getClass()) + "]。"
                    + "可在「配置」页的 versionTable[" + version + "].loginTask 里改成正确名字", false);
        } catch (Throwable t) {
            throw new CodeException("写 LoginTask 字段 " + name + "（角色 " + role + "）失败: " + t, false);
        }
    }

    /* ---------- 诊断辅助 ---------- */

    /** 形如 "#0 o:String, #1 p:String, #2 s:int"；序号用于「只改了名字、顺序没变」时对照 */
    private static String fieldNamesOf(Class<?> k) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Field f : k.getDeclaredFields()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('#').append(i++).append(' ').append(f.getName()).append(':').append(simpleType(f.getType()));
        }
        return sb.toString();
    }

    private static String signatures(Class<?> k) {
        StringBuilder sb = new StringBuilder();
        for (Constructor<?> ct : k.getDeclaredConstructors()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append('(');
            Class<?>[] ps = ct.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleType(ps[i]));
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static String methodsOf(Class<?> k) {
        StringBuilder sb = new StringBuilder();
        for (Method m : k.getDeclaredMethods()) {
            if (sb.length() > 0) sb.append(", ");
            if (Modifier.isStatic(m.getModifiers())) sb.append("static ");
            sb.append(m.getName()).append('/').append(m.getParameterTypes().length);
        }
        return sb.toString();
    }

    private static String simpleType(Class<?> t) {
        if (t == null) return "?";
        if (t.isPrimitive()) return t.getName();
        String n = t.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    /** 在给定 j1 类里找「静态无参方法 → 返回实例上有接收 c 的方法」 */
    private static String[] findSubmitterOn(Class<?> k, Class<?> c) {
        for (Method m : k.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!m.getName().matches("[a-z]")) continue;
            Class<?> holder = m.getReturnType();
            if (holder == null || holder.isPrimitive() || holder == void.class) continue;
            for (Method m2 : holder.getDeclaredMethods()) {
                if (Modifier.isStatic(m2.getModifiers())) continue;
                Class<?>[] p = m2.getParameterTypes();
                if (p.length != 1) continue;
                if (!m2.getName().matches("[a-z]")) continue;
                if (!p[0].isAssignableFrom(c)) continue;
                return new String[]{m.getName(), m2.getName()};
            }
        }
        return null;
    }

    /* ==================== 反射工具 ==================== */

    private static Object getField(Object obj, String name) throws Throwable {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /** a7 的双参构造 (LoginTask, a1) */
    private static Constructor<?> findTwoArgCtor(Class<?> clazz, Class<?> loginTaskCls, Class<?> a1Cls) {
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 2 && p[0].isAssignableFrom(loginTaskCls) && p[1].isAssignableFrom(a1Cls)) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        throw new IllegalStateException("未找到 a7 双参构造函数: " + clazz.getName());
    }

    /** 混淆类名的构造参数个数随版本漂移，优先按 8 参匹配，再退到 ≥6 参 */
    private static Constructor<?> findEightArgConstructor(Class<?> clazz) {
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterTypes().length == 8) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterTypes().length >= 6) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        throw new IllegalStateException("未找到目标构造函数: " + clazz.getName());
    }

    /**
     * a1 的 1 参构造 (LoginTask)。8.0.76 起构造函数签名发生混淆位移，
     * 因此优先按「首参可接收 LoginTask」匹配，匹配不到再退回精确签名。
     */
    private static Constructor<?> findSingleArgConstructor(Class<?> clazz, Class<?> loginTaskCls) {
        Constructor<?> fallback = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1) {
                if (params[0].isAssignableFrom(loginTaskCls)) {
                    ctor.setAccessible(true);
                    return ctor;
                }
                if (fallback == null) fallback = ctor;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            XposedBridge.log(TAG + " [a1] 未找到精确 (LoginTask) 构造，回退单参构造: " + fallback);
            return fallback;
        }
        throw new IllegalStateException("未找到 a1 单参构造函数: " + clazz.getName());
    }

    private static String classifyCode(String s) {
        if (s == null || s.isEmpty()) return "invalid";
        if (s.matches("^[0-9a-fA-F]+$")) return "hex";
        if (s.matches("^[A-Za-z0-9+/=]+$")) return "base64";
        if (s.matches("^[A-Za-z0-9_\\-]+$")) return "base64url";
        if (s.matches("^[A-Za-z0-9]+$")) return "alnum";
        return "other";
    }
}
