package xiaojw.byd.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import xiaojw.byd.core.LogStore;

/**
 * 在运行中的微信进程里直接读 dex，为当前微信版本生成 Hook 适配配置。
 *
 * 复刻 xiaojinwen/wxcode-hook-config 的提取规则，但不需要 jadx：
 * 那个项目是「反编译源码 + 正则匹配」，这里换成「枚举 dex 类名 + 反射校验签名」，
 * 得到的是同一组 j1 / c / a1 / a7。
 *
 * 四条规则与源码形态一一对应：
 *
 *   a1  ←  Cls v = new Cls(this)           单参构造，参数可接收 LoginTask
 *   a7  ←  Cls v = new Cls(this, cb)       双参构造 (LoginTask, a1)
 *   c   ←  new pkg.cls(str, LinkedList…)   参数 ≥6、首参 String、次参 LinkedList、末参 a7
 *   j1  ←  pkg.cls.d().g(cObj)             静态无参方法返回的实例，再有一个接收 c 的方法
 *
 * 类名来源同时覆盖「安装包 dex」与「运行时实际加载的 dex」（Tinker 热修后两者可能不同），
 * 后者通过反射 BaseDexClassLoader.pathList 拿到。
 */
public final class DexProbe {

    /* 国内版与 Google Play 版登录链在不同包下，两个都试 */
    private static final String LOGIN_TASK_DOMESTIC =
            "com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin$LoginTask";
    private static final String LOGIN_TASK_PLAY =
            "com.tencent.mm.plugin.type.jsapi.auth.JsApiLogin$LoginTask";

    private static final String AUTH_PKG_DOMESTIC = "com.tencent.mm.plugin.appbrand.jsapi.auth.";
    private static final String AUTH_PKG_PLAY = "com.tencent.mm.plugin.type.jsapi.auth.";

    /** 反射加载类可能触发大量类解析，做上限保护，避免拖慢微信启动 */
    private static final int MAX_LOAD_PER_GROUP = 800;

    /** 提交器类的常见混淆名（参考项目的 DEX 兜底用的就是这一类清单） */
    private static final Set<String> PREFERRED_SUBMITTER =
            new HashSet<>(Arrays.asList("j1", "k1", "i1", "h1"));

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Outcome {
        public final boolean ok;
        public final String version;
        /** 形如 {"8.0.79":{"j1":...}}；失败为 null */
        public final String fragmentJson;
        public final String reason;
        public final List<String> steps;
        public int classCount;

        Outcome(boolean ok, String version, String fragmentJson, String reason, List<String> steps) {
            this.ok = ok;
            this.version = version;
            this.fragmentJson = fragmentJson;
            this.reason = reason;
            this.steps = steps;
        }

        public String stepsText() {
            return String.join("\n", steps);
        }
    }

    private DexProbe() {}

    /* ==================== 入口 ==================== */

    public static Outcome probe(Context wechatCtx, String version) {
        List<String> steps = new ArrayList<>();
        Outcome out;
        try {
            out = doProbe(wechatCtx, version, steps);
        } catch (Throwable t) {
            steps.add("探测异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            out = new Outcome(false, version, null, "探测异常: " + t.getMessage(), steps);
        }
        LogStore.info("版本探测结果（" + version + "）: "
                + (out.ok ? "生成成功" : "失败 → " + out.reason));
        for (String s : steps) LogStore.info("  · " + s);
        return out;
    }

    private static Outcome doProbe(Context ctx, String version, List<String> steps) throws Exception {
        ClassLoader cl = ctx.getClassLoader();

        /* ---- 1. 定位登录任务类，同时确定是国内版还是 Play 版 ---- */
        Class<?> loginTask = load(cl, LOGIN_TASK_DOMESTIC);
        String authPkg = AUTH_PKG_DOMESTIC;
        boolean play = false;
        if (loginTask == null) {
            loginTask = load(cl, LOGIN_TASK_PLAY);
            authPkg = AUTH_PKG_PLAY;
            play = true;
        }
        if (loginTask == null) {
            return new Outcome(false, version, null,
                    "未找到 JsApiLogin$LoginTask（国内版与 Play 版两条包路径都试过了）", steps);
        }
        final String edition = play ? "Play 版" : "国内版";
        steps.add("定位登录类: JsApiLogin$LoginTask（" + edition + "，回调包 " + authPkg + "）");

        /* ---- 优先：按 xiaojinwen/wxcode-hook-config 的做法，反汇编 LoginTask
               自己的调用点来定四个类。这比按形状在全体类里搜可靠得多。 ---- */
        JsApiLoginAnalyzer.Result analyzed = JsApiLoginAnalyzer.analyze(ctx, play);
        if (analyzed.complete() && analyzable(cl, analyzed)) {
            steps.add("由 JsApiLogin 调用点分析得到：" + analyzed.summary());
            // 分析结果里 j1/c 是 dex 内部名（pp0/i1），必须转成点号再写回配置，
            // 否则 Hook 侧按类名加载会失败（a1/a7 本来就转了，这里漏掉过）
            WxVersionTable.Entry entry = new WxVersionTable.Entry(
                    version,
                    stripWeChatPrefix(dotted(analyzed.j1)),
                    stripWeChatPrefix(dotted(analyzed.c)),
                    stripWeChatPrefix(dotted(analyzed.a1)), stripWeChatPrefix(dotted(analyzed.a7)),
                    analyzed.j1StaticMethod, analyzed.j1InstanceMethod,
                    "由 JsApiLogin 调用点分析生成（" + edition + "）");
            if (!analyzed.cCands.isEmpty()) {
                steps.add("c 候选：" + analyzed.candDump(6));
            }
            JSONObject frag = new JSONObject();
            frag.put(version, entry.toJson());
            return new Outcome(true, version, frag.toString(), null, steps);
        }
        steps.add("调用点分析未完成（缺 " + analyzed.missing() + "），与形状搜索**按字段合并**："
                + String.join("；", analyzed.notes));

        /* ---- 2. 枚举 dex 里的类名 ---- */
        Set<String> all = readClassNames(ctx, steps);
        if (all.isEmpty()) {
            return new Outcome(false, version, null, "无法读取微信 dex 类名（安装包或运行时 dex 均不可读）", steps);
        }

        // a1/a7 必须与登录链同包，不能跨发行版乱抓
        List<String> authClasses = filterAuthPackage(all, authPkg);
        if (authClasses.isEmpty()) {
            authClasses = filterAuthPackage(all, play ? AUTH_PKG_DOMESTIC : AUTH_PKG_PLAY);
            if (!authClasses.isEmpty()) {
                steps.add("注意: " + authPkg + " 下没有候选类，改用另一发行版的包试探");
            }
        }
        if (authClasses.isEmpty()) {
            return new Outcome(false, version, null,
                    "dex 中未找到 " + authPkg + " 下的类，可能微信结构变化或 dex 不完整", steps);
        }
        steps.add("登录包候选类: " + authClasses.size() + " 个");

        /* ---- 3~6. 四类目标：调用点分析的结论优先，缺的用形状搜索补 ----
           参考项目各条规则是**彼此独立**地成立就行了；我们早先把它们做成了
           「要么全用调用点分析、要么全退回形状搜索」，于是 8.0.79 这种
           「链式调用给了 j1，a7 却要在形状搜索里确认」的情况两边都不完整。 */
        boolean a1FromAnalysis = false, a7FromAnalysis = false, cFromAnalysis = false;

        Class<?> a1 = analyzed.a1 == null ? null : load(cl, dotted(analyzed.a1));
        a1FromAnalysis = a1 != null;
        if (a1 == null) a1 = findSingleArgCtor(cl, authClasses, loginTask);
        if (a1 == null) {
            return new Outcome(false, version, null,
                    "未找到单参回调 a1（new Cls(this)）—— 分析结果里 " + analyzed.missing(), steps);
        }
        steps.add("a1 = " + shorten(a1.getName()) + "（单参构造接收 LoginTask"
                + (a1FromAnalysis ? "，来自调用点分析" : "，来自形状搜索") + "）");

        Class<?> a7 = analyzed.a7 == null ? null : load(cl, dotted(analyzed.a7));
        a7FromAnalysis = a7 != null;
        if (a7 == null) a7 = findTwoArgCtor(cl, authClasses, loginTask, a1);
        if (a7 == null) {
            return new Outcome(false, version, null, "未找到双参回调 a7（new Cls(this, cb)）", steps);
        }
        steps.add("a7 = " + shorten(a7.getName()) + "（双参构造 (LoginTask, a1)"
                + (a7FromAnalysis ? "，来自调用点分析" : "，来自形状搜索") + "）");

        // c 用**具体类**：源码里 new X.Y(...) 的那个 Y，Hook 要实例化的就是它
        Class<?> c = analyzed.c == null ? null : load(cl, dotted(analyzed.c));
        cFromAnalysis = c != null;
        if (c == null) c = findParamCtor(cl, all, a7, analyzed.cCands);
        if (c == null) {
            return new Outcome(false, version, null,
                    "未找到参数构造类 c（≥6 参构造）—— 候选：" + analyzed.candDump(6), steps);
        }
        steps.add("c = " + c.getName() + (c != null && cFromAnalysis ? "（来自调用点分析）" : "（来自形状搜索）"));
        if (!analyzed.cCands.isEmpty()) steps.add("c 候选：" + analyzed.candDump(6));

        // j1 优先用链式调用的结论（名字不写死，8.0.79 是 pp0.i1）
        String[] j1 = submitterFromAnalysis(analyzed);
        if (j1 == null) {
            j1 = findSubmitter(cl, all, c, analyzed.j1 == null ? null : dotted(analyzed.j1));
        }
        if (j1 == null) {
            return new Outcome(false, version, null,
                    "未找到任务提交器 j1（pkg.cls.d().g(...) 链式调用）", steps);
        }
        steps.add("j1 = " + j1[0] + "（." + j1[1] + "()." + j1[2] + "()"
                + (j1.length > 3 && j1[3] != null ? "，只收 " + j1[3] : "") + "）");

        // c 与「g 接受的类型」核对：链上那个类型常常是接口/父类（8.0.79 是 modelbase.n1），
        // 这时实例化具体类、按父类型传参是对的；只有连父类都装不下这个具体类时才需要换。
        String chainC = analyzed.cFromChain == null ? null : dotted(analyzed.cFromChain);
        if (chainC != null && !chainC.equals(c.getName())) {
            Class<?> want = load(cl, chainC);
            if (want != null && !want.isAssignableFrom(c)) {
                if (hasLongCtor(want)) {
                    steps.add("c 换成 " + chainC + "：链上 g 只接受它，而原候选 " + c.getName() + " 装不进去");
                    c = want;
                } else {
                    steps.add("注意：链上 g 接受 " + chainC + "，但它没有 ≥6 参构造，保留 " + c.getName());
                }
            } else {
                steps.add("c 与链上参数核对通过：" + c.getName() + " 可以按 " + chainC + " 传给 g");
            }
        }

        /* ---- 7. 输出片段 ---- */
        WxVersionTable.Entry entry = new WxVersionTable.Entry(
                version, j1[0], c.getName(),
                stripWeChatPrefix(a1.getName()), stripWeChatPrefix(a7.getName()),
                j1[1], j1[2], "由本机 dex 探测生成（" + edition + "）");
        JSONObject frag = new JSONObject();
        frag.put(version, entry.toJson());

        Outcome ok = new Outcome(true, version, frag.toString(), null, steps);
        ok.classCount = all.size();
        return ok;
    }

    /* ==================== 四类签名匹配 ==================== */

    /** a1：单参构造，参数可接收 LoginTask（源码形态 new Cls(this)） */
    private static Class<?> findSingleArgCtor(ClassLoader cl, List<String> candidates, Class<?> loginTask) {
        int tried = 0;
        for (String name : candidates) {
            if (tried++ > MAX_LOAD_PER_GROUP) break;
            try {
                Class<?> k = load(cl, name);
                if (k == null || k.isInterface() || Modifier.isAbstract(k.getModifiers())) continue;
                for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 1 && p[0].isAssignableFrom(loginTask)) return k;
                }
            } catch (Throwable ignored) {
                // 该类依赖缺失，跳过
            }
        }
        return null;
    }

    /** a7：双参构造 (LoginTask, a1)（源码形态 new Cls(this, cb)） */
    private static Class<?> findTwoArgCtor(ClassLoader cl, List<String> candidates,
                                           Class<?> loginTask, Class<?> a1) {
        int tried = 0;
        for (String name : candidates) {
            if (tried++ > MAX_LOAD_PER_GROUP) break;
            try {
                Class<?> k = load(cl, name);
                if (k == null || k.isInterface() || Modifier.isAbstract(k.getModifiers())) continue;
                if (k == a1) continue;
                for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 2 && p[0].isAssignableFrom(loginTask) && p[1].isAssignableFrom(a1)) {
                        return k;
                    }
                }
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }

    /**
     * c：源码形态 {@code new pkg.cls(str, LinkedList, …, a7Obj)}。
     *
     * 按参考项目（wxcode-hook-config v3）的策略**打分**，不设类型硬门槛：
     * 参数 ≥6 → +10、==8 → +5、末参可接受 a7 → +5、首参 String → +3、次参 LinkedList → +3；
     * 另外「这个类确实在 LoginTask 里被 new 出来」（分析阶段的调用点候选）与
     * 「混淆后这个类通常叫 c/d」各加 +3。
     *
     * 之前这里把「首参 String + 次参 LinkedList + 末参 a7 + 名字必须是 c/d」当硬门槛，
     * 微信一改构造形状或类名漂移就整轮失败 —— 8.0.79 就是这么挂的。
     *
     * @param hints 分析阶段真正看到的 ≥6 参构造调用目标（dex 内部名），优先试
     */
    private static Class<?> findParamCtor(ClassLoader cl, Set<String> all, Class<?> a7,
                                          List<JsApiLoginAnalyzer.Cand> hints) {
        Map<String, Integer> hinted = new HashMap<>();
        if (hints != null) {
            for (JsApiLoginAnalyzer.Cand h : hints) {
                if (h.cls != null) hinted.put(h.cls.replace('/', '.'), h.score);
            }
        }

        Class<?> best = null;
        int bestScore = -1;
        int tried = 0;

        // 先把「分析阶段看到被 new 出来」的候选试完，再退到按名字猜的那一批
        Set<String> ordered = new LinkedHashSet<>();
        for (String name : all) {
            if (hinted.containsKey(name)) ordered.add(name);
        }
        for (String name : all) {
            String simple = simpleName(name);
            if (!hinted.containsKey(name) && !"c".equals(simple) && !"d".equals(simple)) continue;
            ordered.add(name);
        }

        for (String name : ordered) {
            if (tried++ > MAX_LOAD_PER_GROUP * 2) break;
            try {
                Class<?> k = load(cl, name);
                if (k == null || k.isInterface() || Modifier.isAbstract(k.getModifiers())) continue;
                String simple = simpleName(name);
                for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length < 6 || p.length > 12) continue;
                    int score = 10;
                    if (p.length == 8) score += 5;
                    if (a7 != null && p[p.length - 1].isAssignableFrom(a7)) score += 5;
                    if (p[0].isAssignableFrom(String.class)) score += 3;
                    if (p[1].isAssignableFrom(LinkedList.class)) score += 3;
                    if (hinted.containsKey(name)) score += 3;
                    if ("c".equals(simple) || "d".equals(simple)) score += 3;
                    if (score > bestScore) {
                        bestScore = score;
                        best = k;
                    }
                }
            } catch (Throwable ignored) {
                // 该类依赖缺失，跳过
            }
        }
        return best;
    }

    /**
     * 直接用调用点分析（链式调用）的结论定位 j1。
     *
     * 这是参考项目定位 j1 的原手段（正则 {@code X.Y.e().g(c)}），
     * 因为只看调用形状、不看名字，所以 8.0.79 的 {@code pp0.i1} 也能认出来 ——
     * 而按类名筛的那条路（只认 j1/k1/i1/h1）在 25 万个混淆类里很容易错失目标。
     *
     * @return {类, 静态方法, 实例方法, 实例方法的参数类型}；没有链式结论时返回 null
     */
    private static String[] submitterFromAnalysis(JsApiLoginAnalyzer.Result a) {
        if (a == null || a.j1 == null || a.j1StaticMethod == null || a.j1InstanceMethod == null) {
            return null;
        }
        return new String[]{
                dotted(a.j1), a.j1StaticMethod, a.j1InstanceMethod,
                a.cFromChain == null ? null : dotted(a.cFromChain)
        };
    }

    /**
     * j1：源码形态 {@code pkg.cls.d().g(cObj)} —— 静态无参方法返回的实例上，
     * 再有一个只接收 c 的方法。
     *
     * 参考项目匹配的是**调用形状**而不是名字，所以先试链式调用给出的那个类（hint），
     * 再按常见混淆名（j1/k1/i1/h1）试一批。**不做全量扫描**：
     * 微信有 25 万个类，逐个反射加载既慢又没用（参考项目的 DEX 兜底也是只查一张前缀表）。
     *
     * @return {类名, 静态方法名, 实例方法名, 实例方法参数类}；未找到返回 null
     */
    private static String[] findSubmitter(ClassLoader cl, Set<String> all, Class<?> c, String hint) {
        if (hint != null) {
            String[] hit = matchSubmitter(cl, hint, c);
            if (hit != null) return hit;
        }
        int tried = 0;
        for (String name : all) {
            if (!PREFERRED_SUBMITTER.contains(simpleName(name))) continue;
            if (tried++ > MAX_LOAD_PER_GROUP) break;
            if (name.equals(hint)) continue;
            String[] hit = matchSubmitter(cl, name, c);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 在单个类上按形状找 j1；同一个类里多个候选时取评分最高的那个 */
    private static String[] matchSubmitter(ClassLoader cl, String name, Class<?> c) {
        Class<?> k = load(cl, name);
        if (k == null) return null;

        String[] best = null;
        int bestScore = -1;
        for (Method m : safeMethods(k)) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!m.getName().matches("[a-z]")) continue;
            Class<?> holder = m.getReturnType();
            if (holder == null || holder.isPrimitive() || holder == void.class) continue;

            for (Method m2 : safeMethods(holder)) {
                if (Modifier.isStatic(m2.getModifiers())) continue;
                Class<?>[] p = m2.getParameterTypes();
                if (p.length != 1) continue;
                if (!m2.getName().matches("[a-z]")) continue;

                // 打分而不是「第一个命中就用」：
                //   参数能接受已选的 c → +6；静态方法返回自身（单例访问器）→ +4；
                //   参数类名像 c/d → +3
                int score = 0;
                if (c != null && p[0].isAssignableFrom(c)) score += 6;
                if (holder == k) score += 4;
                String simple = simpleName(p[0].getName());
                if ("c".equals(simple) || "d".equals(simple)) score += 3;
                if (score <= 0) continue;

                if (score > bestScore) {
                    bestScore = score;
                    best = new String[]{name, m.getName(), m2.getName(), p[0].getName()};
                }
            }
        }
        return best;
    }

    /** 这个类有没有 ≥6 参的构造（用来判断某个类型「像不像 c」） */
    private static boolean hasLongCtor(Class<?> k) {
        try {
            if (k == null || k.isInterface() || Modifier.isAbstract(k.getModifiers())) return false;
            for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                if (ctor.getParameterTypes().length >= 6) return true;
            }
        } catch (Throwable ignored) {
            // 参数类型解析不出来就算了
        }
        return false;
    }

    /* ==================== dex 类名读取 ==================== */

    /**
     * 读取全部 dex 的类描述符。
     * 来源一：安装包（sourceDir + splitSourceDirs）。
     * 来源二：运行时真正在用的 dex（BaseDexClassLoader.pathList）——
     *         微信用 Tinker 热修时，磁盘上的包与内存里的代码可能不一致。
     */
    /** dex 字节访问器；返回 true 表示已找到目标，可以停止遍历 */
    interface DexVisitor { boolean visit(byte[] dex); }

    /**
     * 逐个 dex 访问（一次只驻留一个 dex）。
     * 微信的安装包有几十个 dex、合计数百 MB，整包读进来会在微信进程里造成很大压力，
     * 所以这里始终只持有一个 dex 的字节数组。
     */
    static void forEachDex(Context ctx, List<String> steps, DexVisitor visitor) {
        Set<String> sources = new LinkedHashSet<>();

        ApplicationInfo ai = ctx.getApplicationInfo();
        if (ai.sourceDir != null) sources.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) Collections.addAll(sources, ai.splitSourceDirs);

        List<String> runtimeDex = runtimeDexPaths(ctx.getClassLoader());
        if (!runtimeDex.isEmpty()) {
            if (steps != null) steps.add("运行时 dex 条目: " + runtimeDex.size() + " 个");
            sources.addAll(runtimeDex);
        }

        int read = 0;
        for (String path : sources) {
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) continue;

            if (path.endsWith(".dex")) {
                byte[] data = readFile(f);
                if (data == null) continue;
                read++;
                if (visitor.visit(data)) return;
                continue;
            }

            ZipFile zip = null;
            try {
                zip = new ZipFile(f);
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".dex")) continue;
                    if (!n.startsWith("classes") && n.indexOf('/') < 0) continue;
                    byte[] data;
                    try (InputStream in = zip.getInputStream(e)) {
                        data = readAll(in);
                    } catch (Throwable ignored) {
                        continue;      // 单个 dex 损坏不影响其余
                    }
                    read++;
                    if (visitor.visit(data)) return;
                }
            } catch (Throwable t) {
                LogStore.warn("读取 dex 包失败 " + f.getName() + ": " + t.getMessage());
            } finally {
                if (zip != null) {
                    try { zip.close(); } catch (Throwable ignored) { }
                }
            }
        }
        if (steps != null) steps.add("已扫描 dex " + read + " 个");
    }

    private static Set<String> readClassNames(Context ctx, List<String> steps) {
        final Set<String> out = new HashSet<>();
        final int[] count = { 0 };
        forEachDex(ctx, null, dex -> {
            count[0]++;
            parseDex(dex, out);
            return false;
        });
        steps.add("已解析 dex " + count[0] + " 个，类 " + out.size() + " 个");
        return out;
    }

    /** 反射拿运行时加载的 dex 路径；失败就只靠安装包，不影响主流程 */
    private static List<String> runtimeDexPaths(ClassLoader cl) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListField = base.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);
            if (pathList == null) return out;

            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object elements = elementsField.get(pathList);
            if (!(elements instanceof Object[])) return out;

            for (Object el : (Object[]) elements) {
                if (el == null) continue;
                String path = null;
                for (String fname : new String[]{"path", "dexFile"}) {
                    try {
                        Field f = el.getClass().getDeclaredField(fname);
                        f.setAccessible(true);
                        Object v = f.get(el);
                        if (v instanceof File) path = ((File) v).getAbsolutePath();
                        else if (v instanceof String) path = (String) v;
                        else if (v != null) {
                            Method m = v.getClass().getMethod("getName");
                            Object n = m.invoke(v);
                            if (n instanceof String) path = (String) n;
                        }
                        if (path != null) break;
                    } catch (Throwable ignored) {
                        // 换下一个字段名再试
                    }
                }
                if (path != null && !out.contains(path)) out.add(path);
            }
        } catch (Throwable ignored) {
            // 该 ROM 的 DexClassLoader 结构不同，忽略
        }
        return out;
    }

    /* ==================== dex 二进制解析 ==================== */

    private static final int OFF_STRING_IDS_SIZE = 56;
    private static final int OFF_STRING_IDS_OFF = 60;
    private static final int OFF_TYPE_IDS_SIZE = 64;
    private static final int OFF_TYPE_IDS_OFF = 68;
    private static final int OFF_CLASS_DEFS_SIZE = 96;
    private static final int OFF_CLASS_DEFS_OFF = 100;
    private static final int CLASS_DEF_ITEM_SIZE = 32;

    private static void parseDex(byte[] data, Set<String> out) {
        if (data == null || data.length < 112) return;
        if (data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n') return;

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = b.getInt(OFF_STRING_IDS_SIZE);
        int stringIdsOff = b.getInt(OFF_STRING_IDS_OFF);
        int typeIdsSize = b.getInt(OFF_TYPE_IDS_SIZE);
        int typeIdsOff = b.getInt(OFF_TYPE_IDS_OFF);
        int classDefsSize = b.getInt(OFF_CLASS_DEFS_SIZE);
        int classDefsOff = b.getInt(OFF_CLASS_DEFS_OFF);

        if (stringIdsSize <= 0 || stringIdsOff <= 0 || typeIdsSize <= 0
                || typeIdsOff <= 0 || classDefsSize <= 0 || classDefsOff <= 0) return;

        for (int i = 0; i < classDefsSize; i++) {
            int cd = classDefsOff + i * CLASS_DEF_ITEM_SIZE;
            if (cd + CLASS_DEF_ITEM_SIZE > data.length) break;

            int classIdx = b.getInt(cd);
            if (classIdx < 0 || classIdx >= typeIdsSize) continue;

            int typeOff = typeIdsOff + classIdx * 4;
            if (typeOff + 4 > data.length) continue;
            int descIdx = b.getInt(typeOff);

            String desc = readString(b, data, stringIdsSize, stringIdsOff, descIdx);
            if (desc == null || desc.length() < 3) continue;
            if (desc.charAt(0) != 'L') continue;

            int end = desc.indexOf(';');
            if (end < 0) end = desc.length();
            out.add(desc.substring(1, end).replace('/', '.'));
        }
    }

    private static String readString(ByteBuffer b, byte[] data, int size, int off, int idx) {
        if (idx < 0 || idx >= size) return null;
        int stringIdOff = off + idx * 4;
        if (stringIdOff + 4 > data.length) return null;
        int dataOff = b.getInt(stringIdOff);
        if (dataOff <= 0 || dataOff >= data.length) return null;

        int p = dataOff;
        while (p < data.length && (data[p] & 0x80) != 0) p++;   // 跳过 uleb128 长度
        p++;
        if (p >= data.length) return null;

        int start = p;
        int e = start;
        while (e < data.length && data[e] != 0) e++;
        return new String(data, start, e - start, UTF8);
    }

    /* ==================== 工具 ==================== */

    private static Class<?> load(ClassLoader cl, String dotted) {
        try {
            return Class.forName(dotted, false, cl);   // 不初始化，避免触发静态块副作用
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method[] safeMethods(Class<?> k) {
        try {
            return k.getDeclaredMethods();
        } catch (Throwable t) {
            return new Method[0];
        }
    }

    private static List<String> filterAuthPackage(Set<String> all, String pkgPrefix) {
        List<String> out = new ArrayList<>();
        for (String n : all) {
            if (n.indexOf('$') >= 0) continue;
            if (n.startsWith(pkgPrefix)) out.add(n);
        }
        return out;
    }

    private static String simpleName(String dotted) {
        int i = dotted.lastIndexOf('.');
        return i < 0 ? dotted : dotted.substring(i + 1);
    }

    /** Hook 侧拼类名时会补 com.tencent.mm.，所以这里要剥掉前缀存回配置 */
    private static String stripWeChatPrefix(String dotted) {
        return dotted.startsWith("com.tencent.mm.") ? dotted.substring("com.tencent.mm.".length()) : dotted;
    }

    private static String shorten(String dotted) {
        return stripWeChatPrefix(dotted);
    }

    /** dex 内部名 com/foo/Bar → com.foo.Bar */
    private static String dotted(String internal) {
        return internal == null ? null : internal.replace('/', '.');
    }

    /**
     * 分析结果必须真能加载出来才采信 —— 万一解析器出错，
     * 宁可退回形状搜索，也不能把原本可用的配置换成错的。
     */
    private static boolean analyzable(ClassLoader cl, JsApiLoginAnalyzer.Result a) {
        return loadable(cl, a.j1) && loadable(cl, a.c)
                && loadable(cl, a.a1) && loadable(cl, a.a7);
    }

    private static boolean loadable(ClassLoader cl, String internal) {
        if (internal == null || internal.isEmpty()) return false;
        try {
            Class.forName(internal.replace('/', '.'), false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
