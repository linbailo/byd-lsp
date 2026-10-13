package xiaojw.byd.hook;

import android.content.Context;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xiaojw.byd.core.LogStore;

/**
 * 按 xiaojinwen/wxcode-hook-config 的判定依据，从 dex 里定位 j1 / c / a1 / a7。
 *
 * 那个项目的做法是「反编译 {@code JsApiLogin$LoginTask}，在它自己的代码里找四个调用点」：
 *
 * <pre>
 *   a1 : new b2(this)          单参构造，实参是 this（LoginTask）
 *   a7 : new f2(this, b2Var)   双参构造，实参是 this 和 a1
 *   c  : new o60.c(...)        参数 ≥6、首参 String、次参 LinkedList 的构造
 *   j1 : u70.k1.d().f(cVar)    静态无参方法返回的实例上，再调一个接收 c 的方法
 * </pre>
 *
 * 早先 {@link DexProbe} 是「按形状在全体类里搜」，可能挑到形状相同但不是登录链的类。
 * 这里改成反汇编 LoginTask 自身的字节码，只看它真正 new 了谁、调了谁 —— 与那个项目一致。
 *
 * 只解析单个类的 class_data / code_item，不做整包反编译，内存占用可控。
 * 任何一步越界或解析失败都只是「没找到」，不会影响调用方。
 */
final class JsApiLoginAnalyzer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String DESC_LOGIN_TASK_TAIL =
            "com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin$LoginTask";
    private static final String DESC_LOGIN_TASK_TAIL_PLAY =
            "com/tencent/mm/plugin/type/jsapi/auth/JsApiLogin$LoginTask";
    /** 外层类：参考项目在找不到 $LoginTask 时会退到 JsApiLogin.java，这里同样兜一层 */
    private static final String DESC_LOGIN_TAIL =
            "com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin";
    private static final String DESC_LOGIN_TAIL_PLAY =
            "com/tencent/mm/plugin/type/jsapi/auth/JsApiLogin";

    private static final String D_STRING = "Ljava/lang/String;";
    private static final String D_LINKEDLIST = "Ljava/util/LinkedList;";

    /** 参考项目用来确认「登录链就在这个包里」的两个标记串 */
    private static final String MARK_LOGIN = "Call post initialized callbacks now";
    private static final String MARK_CGI = "/cgi-bin/mmbiz-bin/js-login";

    /** 一次分析的结论；字段为 null 表示没找到 */
    static final class Result {
        String a1;
        String a7;
        /** c：**具体类**（源码形态 new X.Y(...) 的那个 Y），Hook 要实例化的就是它 */
        String c;
        String j1;
        String j1StaticMethod;
        String j1InstanceMethod;
        /**
         * 链式调用里 g 的参数类型（dex 内部名）。它常常是**接口/父类**
         * （8.0.79 是 com/tencent/mm/modelbase/n1），而不是被实例化的那个具体类 ——
         * 两者都要留着：c 用具体类，这个只用来核对 g 到底接受什么。
         */
        String cFromChain;
        final List<String> notes = new ArrayList<>();
        /** c 的候选（LoginTask 里 ≥6 参构造的调用目标），按评分从高到低 */
        final List<Cand> cCands = new ArrayList<>();

        boolean complete() {
            return nonEmpty(a1) && nonEmpty(a7) && nonEmpty(c)
                    && nonEmpty(j1) && nonEmpty(j1StaticMethod) && nonEmpty(j1InstanceMethod);
        }

        private static boolean nonEmpty(String s) { return s != null && !s.isEmpty(); }

        String summary() {
            return "a1=" + a1 + " a7=" + a7 + " c=" + c
                    + " j1=" + j1 + "." + j1StaticMethod + "()." + j1InstanceMethod + "()"
                    + (cFromChain == null || cFromChain.equals(c) ? "" : "（g 接受 " + cFromChain + "）");
        }

        /** 缺哪些字段（诊断用） */
        String missing() {
            StringBuilder sb = new StringBuilder();
            if (a1 == null) sb.append("a1 ");
            if (a7 == null) sb.append("a7 ");
            if (c == null) sb.append("c ");
            if (j1 == null) sb.append("j1 ");
            if (j1StaticMethod == null) sb.append("j1静态方法 ");
            if (j1InstanceMethod == null) sb.append("j1实例方法 ");
            return sb.length() == 0 ? "（无）" : sb.toString().trim();
        }

        /** 把另一个结果的空字段补上（四个候选登录类的发现按字段合并） */
        void mergeFrom(Result o) {
            if (o == null) return;
            if (a1 == null) a1 = o.a1;
            if (a7 == null) a7 = o.a7;
            if (c == null) c = o.c;
            if (j1 == null) j1 = o.j1;
            if (j1StaticMethod == null) j1StaticMethod = o.j1StaticMethod;
            if (j1InstanceMethod == null) j1InstanceMethod = o.j1InstanceMethod;
            if (cFromChain == null) cFromChain = o.cFromChain;
            for (Cand c : o.cCands) {
                if (findCand0(cCands, c.cls) == null) cCands.add(c);
            }
        }

        /** 候选清单（诊断用）：哪几个类长得像 c、各多少参、拿了多少分 */
        String candDump(int max) {
            if (cCands.isEmpty()) return "（无 ≥6 参构造的调用点）";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cCands.size() && i < max; i++) {
                if (i > 0) sb.append("、");
                sb.append(cCands.get(i));
            }
            if (cCands.size() > max) sb.append(" 等 ").append(cCands.size()).append(" 个");
            return sb.toString();
        }
    }

    private static Cand findCand0(List<Cand> list, String cls) {
        for (Cand c : list) {
            if (c.cls.equals(cls)) return c;
        }
        return null;
    }

    /**
     * c 的候选及评分。
     *
     * 参考项目（wxcode-hook-config 的 v3 策略）对 c **只打分、不设硬门槛**：
     *   参数 ≥6 → +10；==8 → +5；末参是 a7 → +5；首参 String → +3；次参 LinkedList → +3
     * 之前这里要求「首参 String + 次参 LinkedList + 末参 a7」同时成立 ——
     * 微信一改构造形状就必然抓不到，实机报「未找到参数构造类 c」就是这么来的。
     */
    static final class Cand {
        final String cls;
        final int params;
        final int score;

        Cand(String cls, int params, int score) {
            this.cls = cls;
            this.params = params;
            this.score = score;
        }

        @Override
        public String toString() {
            return cls.replace('/', '.') + "(" + params + " 参/评分 " + score + ")";
        }
    }

    private JsApiLoginAnalyzer() {}

    /**
     * @param play true 时按 Google Play 版包路径找登录类
     */
    static Result analyze(Context ctx, boolean play) {
        // 与参考项目一致：优先 $LoginTask，退一层到外层的 JsApiLogin；
        // 发行版也两边都试（包路径只能说明大概率，不代表一定）
        List<String> candidates = new ArrayList<>();
        candidates.add(play ? DESC_LOGIN_TASK_TAIL_PLAY : DESC_LOGIN_TASK_TAIL);
        candidates.add(play ? DESC_LOGIN_TASK_TAIL : DESC_LOGIN_TASK_TAIL_PLAY);
        candidates.add(play ? DESC_LOGIN_TAIL_PLAY : DESC_LOGIN_TAIL);
        candidates.add(play ? DESC_LOGIN_TAIL : DESC_LOGIN_TAIL_PLAY);

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) results.add(new Result());
        try {
            analyzeAllSubjects(ctx, candidates, results);
        } catch (Throwable t) {
            for (Result r : results) r.notes.add("分析异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // 合并：四个候选登录类各自的发现按字段互补。
        // 8.0.79 就是这种情况 —— 链式调用（j1/静态/实例方法）在 $LoginTask 里，
        // 而 a7 的构造调用点在别处，只有分开看才凑得齐。
        Result merged = new Result();
        for (Result r : results) {
            merged.mergeFrom(r);
            for (String n : r.notes) {
                if (!merged.notes.contains(n)) merged.notes.add(n);
            }
        }
        merged.notes.add(merged.complete()
                ? "四类目标齐了（" + candidates.size() + " 个候选登录类的并集）"
                : "还缺：" + merged.missing());
        Result result = merged;
        LogStore.info("JsApiLogin 调用点分析：" + (result.complete() ? result.summary() : "未完成"));
        for (String n : result.notes) LogStore.info("  · " + n);
        return result;
    }

    /** 一趟扫完所有候选登录类（每个 dex 只读一次；多候选只是一张表查四次） */
    private static void analyzeAllSubjects(Context ctx, final List<String> subjects,
                                           final List<Result> results) {
        DexProbe.forEachDex(ctx, null, dex -> {
            boolean allDone = true;
            for (int i = 0; i < subjects.size(); i++) {
                Result r = results.get(i);
                if (r.complete()) continue;
                analyzeOne(dex, subjects.get(i), r);
                if (!r.complete()) allDone = false;
            }
            return allDone;
        });
    }

    /* ==================== 单个 dex ==================== */

    private static boolean analyzeOne(byte[] data, String loginTaskInternal, Result r) {
        DexView v = new DexView(data);
        if (!v.valid()) return false;

        int cd = v.findClassDef("L" + loginTaskInternal + ";");
        if (cd < 0) return false;
        int classDataOff = v.classDataOff(cd);
        if (classDataOff <= 0) return false;

        // 标记串体检：参考项目就是靠「post initialized」与 js-login 接口路径
        // 确认登录链的；这里顺手在同一个 dex 上查一次（不额外多扫一遍 dex）
        String mark = "登录类所在 dex：post-initialized "
                + (contains(data, MARK_LOGIN) ? "在" : "缺失")
                + "，js-login " + (contains(data, MARK_CGI) ? "在" : "缺失");
        if (!r.notes.contains(mark)) r.notes.add(mark);

        List<Call> ctorOne = new ArrayList<>();    // <init>(LoginTask)
        List<Call> ctorTwo = new ArrayList<>();    // <init>(LoginTask, X)
        List<Call> ctorMany = new ArrayList<>();   // <init>(String, LinkedList, ...)
        List<Call> virtuals = new ArrayList<>();   // 1 参实例方法
        List<Call> statics = new ArrayList<>();    // 无参静态方法
        List<Chain> chains = new ArrayList<>();    // 链式调用 X.Y.e().g(c)

        for (int[] m : v.methods(classDataOff)) {
            if (m[1] <= 0) continue;
            v.scanCode(m[1], ctorOne, ctorTwo, ctorMany, virtuals, statics, chains);
        }
        if (ctorOne.isEmpty() && ctorMany.isEmpty() && chains.isEmpty()) return false;

        String loginDesc = "L" + loginTaskInternal + ";";

        // a1：拿 LoginTask 当唯一参数的构造
        String a1 = null;
        for (Call c : ctorOne) {
            if (c.params.size() == 1 && loginDesc.equals(c.params.get(0))) {
                a1 = c.cls;
                break;
            }
        }

        // a7：(LoginTask, a1) 双参构造 —— 用 a1 交叉验证，避免抓到别的双参构造
        String a7 = null;
        if (a1 != null) {
            for (Call c : ctorTwo) {
                if (c.params.size() == 2 && loginDesc.equals(c.params.get(0))
                        && ("L" + a1 + ";").equals(c.params.get(1))) {
                    a7 = c.cls;
                    break;
                }
            }
        }

        // c：源码形态 new pkg.cls(长参数列表)。按参考策略打分，不设类型硬门槛
        String cc = null;
        String a7Desc = a7 == null ? null : "L" + a7 + ";";
        for (Call cand : ctorMany) {
            if (cand.params.size() < 6) continue;
            int score = 10;                                   // 参数 ≥6
            if (cand.params.size() == 8) score += 5;          // ==8 优先
            if (a7Desc != null && a7Desc.equals(cand.params.get(cand.params.size() - 1))) score += 5;
            if (D_STRING.equals(cand.params.get(0))) score += 3;
            if (D_LINKEDLIST.equals(cand.params.get(1))) score += 3;
            Cand prev = findCand(r.cCands, cand.cls);
            if (prev == null) r.cCands.add(new Cand(cand.cls, cand.params.size(), score));
            else if (score > prev.score) {
                r.cCands.remove(prev);
                r.cCands.add(new Cand(cand.cls, cand.params.size(), score));
            }
        }
        r.cCands.sort((x, y) -> Integer.compare(y.score, x.score));
        cc = r.cCands.isEmpty() ? null : r.cCands.get(0).cls;
        if (!r.cCands.isEmpty()) {
            r.notes.add("c 候选 " + r.cCands.size() + " 个：" + r.candDump(6));
        }

        // ① 首选：链式调用 X.Y.e().g(c) —— 参考项目的判定依据（j1 与静态/实例方法一次确定）
        String j1 = null, staticM = null, instanceM = null;
        if (!chains.isEmpty()) {
            Chain pick = pickChain(chains, cc, true);
            if (pick == null) pick = pickChain(chains, cc, false);
            if (pick != null) {
                j1 = pick.holder;
                staticM = pick.staticM;
                instanceM = pick.instanceM;
                // 注意：**不要**用链上的参数类型覆盖 c。
                // 链上那个类型常常是接口/父类（8.0.79 是 com/tencent/mm/modelbase/n1），
                // 而 Hook 要实例化的是具体类（new X.Y(...) 的那个，8.0.79 是 ko0.c）。
                // 两者都对：实例化具体类，按接口类型传给 g。这里只把它记下来核对。
                r.cFromChain = v.descriptorToInternal(pick.cDesc);
                r.notes.add("链式调用命中：" + pick + "（链上候选 " + chains.size() + " 条，"
                        + (pick.exactType ? "g 与静态方法同类型" : "g 由继承/接口声明") + "）");
            }
        }

        // ② 退一步：按「无参静态方法 + 接收 c 的 1 参方法」配对（老逻辑，保留作兜底）
        if (j1 == null) {
            for (Cand cand : r.cCands) {
                String cDesc = "L" + cand.cls + ";";
                for (Call m : virtuals) {
                    if (m.params.size() != 1) continue;
                    if (!cDesc.equals(m.params.get(0))) continue;
                    if (!singleLower(m.name)) continue;
                    for (Call s : statics) {
                        if (!s.params.isEmpty()) continue;
                        if (!singleLower(s.name)) continue;
                        if (!("L" + m.cls + ";").equals(s.ret)) continue;
                        j1 = s.cls;
                        staticM = s.name;
                        instanceM = m.name;
                        cc = cand.cls;
                        break;
                    }
                    if (j1 != null) break;
                }
                if (j1 != null) break;
            }
        }
        if (j1 == null && !virtNames(virtuals).isEmpty()) {
            r.notes.add("j1 未配上：1 参实例方法 " + virtNames(virtuals)
                    + "，无参静态方法 " + statics.size() + " 个，链式候选 " + chains.size() + " 条");
        }

        if (a1 != null) r.a1 = a1;
        if (a7 != null) r.a7 = a7;
        if (cc != null) r.c = cc;
        if (j1 != null) {
            r.j1 = j1;
            r.j1StaticMethod = staticM;
            r.j1InstanceMethod = instanceM;
        }
        if (r.complete()) {
            r.notes.add("来源：" + loginTaskInternal + " 的调用点");
            return true;
        }
        r.notes.add("在 " + loginTaskInternal + " 里只找到部分调用点："
                + "a1=" + a1 + " a7=" + a7 + " c=" + cc + " j1=" + j1);
        return false;
    }

    /* ==================== 调用点 ==================== */

    /**
     * 从链式候选里挑一条。
     *
     * 优先「参数类型 == 本轮评分选出的 c」，其次「参数类型的名字像 c/d」，
     * 都没有就用第一条 —— 与参考项目的消歧顺序一致（它先看 KNOWN_CONFIGS 的期望名，
     * 再看包内是否存在同名文件，最后取第一个）。
     */
    private static Chain pickChain(List<Chain> chains, String topC, boolean exactOnly) {
        Chain byC = null, byName = null, first = null;
        for (Chain ch : chains) {
            if (exactOnly && !ch.exactType) continue;
            if (first == null) first = ch;
            String cInt = ch.cDesc == null || ch.cDesc.length() < 2
                    ? null : ch.cDesc.substring(1, ch.cDesc.length() - 1);
            if (byC == null && topC != null && topC.equals(cInt)) byC = ch;
            if (byName == null && cInt != null) {
                String simple = simpleInternal(cInt);
                if ("c".equals(simple) || "d".equals(simple)) byName = ch;
            }
        }
        return byC != null ? byC : (byName != null ? byName : first);
    }

    private static Cand findCand(List<Cand> list, String cls) {
        for (Cand c : list) {
            if (c.cls.equals(cls)) return c;
        }
        return null;
    }

    /** 参考策略里的方法名形态：单个小写字母（d / e / f / g …） */
    private static boolean singleLower(String name) {
        return name != null && name.length() == 1
                && name.charAt(0) >= 'a' && name.charAt(0) <= 'z';
    }

    /** dex 字节里有没有这段字符串（朴素查找，只在探测时用一次） */
    private static boolean contains(byte[] hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty()) return false;
        byte[] n = needle.getBytes(UTF8);
        if (hay.length < n.length) return false;
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** dex 内部名 foo/Bar → 取末段 Bar */
    private static String simpleInternal(String internal) {
        int i = internal.lastIndexOf('/');
        return i < 0 ? internal : internal.substring(i + 1);
    }

    /** 诊断用：1 参实例方法的名字清单（截断，避免刷屏） */
    private static String virtNames(List<Call> list) {
        StringBuilder sb = new StringBuilder();
        for (Call c : list) {
            if (!singleLower(c.name)) continue;
            String n = c.cls.replace('/', '.') + "." + c.name + "()";
            if (sb.indexOf(n) >= 0) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(n);
            if (sb.length() > 160) break;
        }
        return sb.toString();
    }

    private static final class Call {
        String cls;                 // 声明类（内部名）
        String name;                // 方法名
        String ret;                 // 返回类型描述符（静态方法用）
        final List<String> params = new ArrayList<>();
    }

    /* ==================== dex 视图 ==================== */

    private static final class DexView {
        final byte[] data;
        final ByteBuffer b;
        final int stringIdsSize, stringIdsOff;
        final int typeIdsSize, typeIdsOff;
        final int protoIdsSize, protoIdsOff;
        final int methodIdsSize, methodIdsOff;
        final int classDefsSize, classDefsOff;

        DexView(byte[] data) {
            this.data = data;
            this.b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            stringIdsSize = b.getInt(56); stringIdsOff = b.getInt(60);
            typeIdsSize = b.getInt(64);   typeIdsOff = b.getInt(68);
            protoIdsSize = b.getInt(72);  protoIdsOff = b.getInt(76);
            methodIdsSize = b.getInt(88); methodIdsOff = b.getInt(92);
            classDefsSize = b.getInt(96); classDefsOff = b.getInt(100);
        }

        boolean valid() {
            return data.length >= 112 && data[0] == 'd' && data[1] == 'e'
                    && data[2] == 'x' && data[3] == '\n'
                    && stringIdsOff > 0 && typeIdsOff > 0 && classDefsOff > 0;
        }

        String string(int idx) {
            if (idx < 0 || idx >= stringIdsSize) return null;
            int idOff = stringIdsOff + idx * 4;
            if (idOff + 4 > data.length) return null;
            int dataOff = b.getInt(idOff);
            if (dataOff <= 0 || dataOff >= data.length) return null;
            int p = dataOff;
            while (p < data.length && (data[p] & 0x80) != 0) p++;
            p++;
            if (p >= data.length) return null;
            int e = p;
            while (e < data.length && data[e] != 0) e++;
            return new String(data, p, e - p, UTF8);
        }

        String typeDesc(int typeIdx) {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return null;
            int off = typeIdsOff + typeIdx * 4;
            if (off + 4 > data.length) return null;
            return string(b.getInt(off));
        }

        /** Lfoo/Bar; → foo/Bar */
        private String descriptorToInternal(String desc) {
            if (desc == null || desc.length() < 2 || desc.charAt(0) != 'L') return null;
            int e = desc.indexOf(';');
            return desc.substring(1, e < 0 ? desc.length() : e);
        }

        List<String> protoParams(int protoIdx) {
            List<String> out = new ArrayList<>();
            if (protoIdx < 0 || protoIdx >= protoIdsSize) return out;
            int off = protoIdsOff + protoIdx * 12;
            if (off + 12 > data.length) return out;
            int paramsOff = b.getInt(off + 8);
            if (paramsOff <= 0 || paramsOff + 4 > data.length) return out;
            int size = b.getInt(paramsOff);
            for (int i = 0; i < size; i++) {
                int t = paramsOff + 4 + i * 2;
                if (t + 2 > data.length) break;
                out.add(typeDesc(b.getShort(t) & 0xffff));
            }
            return out;
        }

        String protoReturn(int protoIdx) {
            if (protoIdx < 0 || protoIdx >= protoIdsSize) return null;
            int off = protoIdsOff + protoIdx * 12;
            if (off + 12 > data.length) return null;
            return typeDesc(b.getInt(off + 4));
        }

        int findClassDef(String classDesc) {
            for (int i = 0; i < classDefsSize; i++) {
                int cd = classDefsOff + i * 32;
                if (cd + 32 > data.length) return -1;
                int classIdx = b.getInt(cd);
                if (classIdx < 0 || classIdx >= typeIdsSize) continue;
                if (classDesc.equals(typeDesc(classIdx))) return i;
            }
            return -1;
        }

        int classDataOff(int classDefIdx) {
            int cd = classDefsOff + classDefIdx * 32;
            if (cd + 32 > data.length) return -1;
            return b.getInt(cd + 24);
        }

        /** 返回 {methodIdx, codeOff} 列表 */
        List<int[]> methods(int classDataOff) {
            List<int[]> out = new ArrayList<>();
            int[] p = { classDataOff };
            int sf = uleb(p), inf = uleb(p), dm = uleb(p), vm = uleb(p);
            if (sf < 0 || inf < 0 || dm < 0 || vm < 0) return out;
            for (int i = 0; i < sf + inf; i++) { uleb(p); uleb(p); }
            int idx = 0;
            for (int i = 0; i < dm + vm; i++) {
                idx += uleb(p);
                uleb(p);                       // access_flags
                int codeOff = uleb(p);
                if (idx >= 0 && idx < methodIdsSize) out.add(new int[]{ idx, codeOff });
                else out.add(new int[]{ -1, -1 });
            }
            return out;
        }

        private int uleb(int[] pos) {
            int result = 0, shift = 0;
            while (pos[0] < data.length) {
                int x = data[pos[0]++] & 0xff;
                result |= (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
                if (shift > 28) return -1;
            }
            return -1;
        }

        /**
         * 反汇编一个 code_item，挑出各类调用点。
         * 只关心 invoke-* / move-result-* / new-instance 的 method/type/寄存器，
         * 其余指令靠长度表跳过。
         */
        void scanCode(int codeOff, List<Call> ctorOne, List<Call> ctorTwo,
                      List<Call> ctorMany, List<Call> virtuals, List<Call> statics,
                      List<Chain> chains) {
            if (codeOff + 16 > data.length) return;
            int insnsSize = b.getInt(codeOff + 12);
            if (insnsSize <= 0 || insnsSize > (1 << 22)) return;
            int base = codeOff + 16;
            int end = base + insnsSize * 2;
            if (end > data.length) end = data.length;

            // 寄存器 → 产生它的静态调用，用来还原源码里的链式写法 X.Y.e().g(c)。
            // 同一个方法内寄存器会复用，所以只保留最近若干条映射。
            Map<Integer, String[]> resultStatics = new HashMap<>();
            Call pending = null;         // 上一条指令是「无参静态调用」时记下来，供 move-result-* 绑定

            int pos = base;
            int guard = 0;
            while (pos + 2 <= end && guard++ < 200000) {
                int unit = (data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8);
                int op = unit & 0xff;

                // switch / fill-array 的 payload 伪指令，必须按公式跳过否则会错位
                if (op == 0x00) {
                    int ident = unit >> 8;
                    int units;
                    if (ident == 0x01) {
                        units = (unit >> 8) * 2 + 4;
                    } else if (ident == 0x02) {
                        units = (unit >> 8) * 4 + 2;
                    } else if (ident == 0x03 && pos + 8 <= end) {
                        int elemWidth = (data[pos + 2] & 0xff) | ((data[pos + 3] & 0xff) << 8);
                        int size = (data[pos + 4] & 0xff) | ((data[pos + 5] & 0xff) << 8)
                                | ((data[pos + 6] & 0xff) << 16) | ((data[pos + 7] & 0xff) << 24);
                        units = (size * elemWidth + 1) / 2 + 4;
                    } else {
                        units = 1;
                    }
                    if (units <= 0) return;
                    pos += units * 2;
                    continue;
                }

                int len = LENGTH[op];
                if (len <= 0) return;
                int off = pos + 2;

                if (isInvoke(op) && off + 2 <= end) {
                    int methodIdx = (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
                    Call call = collect(op, methodIdx, ctorOne, ctorTwo, ctorMany, virtuals, statics);

                    if (call != null && (op == 0x71 || op == 0x77)) {
                        // invoke-static：只有「无参 + 单字母方法名」才可能是提交器入口
                        pending = (call.params.isEmpty() && singleLower(call.name)) ? call : null;
                    } else if (call != null && call.params.size() == 1 && singleLower(call.name)) {
                        // 1 参实例方法：接收者寄存器如果正好来自上面那个静态调用，
                        // 就是源码里的 X.Y.e().g(c) —— j1 与 c 一起确定，不用猜
                        int r0 = firstRegister(op, pos);
                        String[] st = resultStatics.get(r0);
                        if (st != null) {
                            // 参考项目的正则只看「静态调用紧跟着 .g(」这条链，
                            // 不要求 g 必须声明在返回类型上（可能是继承/接口来的）。
                            // 所以这里记下是否严格同类型：严格的优先用，宽松的做兜底。
                            boolean exactType = ("L" + call.cls + ";").equals(st[2]);
                            chains.add(new Chain(st[0], st[1], call.name, call.params.get(0), exactType));
                        }
                        pending = null;
                    } else {
                        pending = null;
                    }
                } else if (op == 0x0a || op == 0x0b || op == 0x0c) {
                    // move-result / move-result-wide / move-result-object 必须紧跟产生它的 invoke，
                    // 正好用来把「静态调用的返回值」和寄存器对上
                    if (pending != null) {
                        int dest = (unit >> 8) & 0xff;
                        if (resultStatics.size() < 32) {
                            resultStatics.put(dest,
                                    new String[]{pending.cls, pending.name, pending.ret});
                        }
                    }
                    pending = null;
                } else {
                    pending = null;
                }
                pos += len * 2;
            }
        }

        /**
         * invoke-* 的第一个寄存器（实例方法即接收者）。
         * 35c 格式在第三单元的低 4 位；带 range 后缀的格式在第三单元的 16 位。
         */
        private int firstRegister(int op, int pos) {
            int off = pos + 4;
            if (off + 2 > data.length) return -1;
            int u3 = (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
            boolean range = (op == 0x74 || op == 0x75 || op == 0x76 || op == 0x77 || op == 0x78);
            return range ? (u3 & 0xffff) : (u3 & 0x0f);
        }

        private boolean isInvoke(int op) {
            return (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78);
        }

        private Call collect(int op, int methodIdx, List<Call> ctorOne, List<Call> ctorTwo,
                             List<Call> ctorMany, List<Call> virtuals, List<Call> statics) {
            if (methodIdx < 0 || methodIdx >= methodIdsSize) return null;
            int idOff = methodIdsOff + methodIdx * 8;
            if (idOff + 8 > data.length) return null;

            String cls = descriptorToInternal(typeDesc(b.getShort(idOff) & 0xffff));
            int protoIdx = b.getShort(idOff + 2) & 0xffff;
            String name = string(b.getInt(idOff + 4));
            if (cls == null || name == null) return null;

            Call call = new Call();
            call.cls = cls;
            call.name = name;
            call.params.addAll(protoParams(protoIdx));
            call.ret = protoReturn(protoIdx);

            switch (op) {
                case 0x70:                                   // invoke-direct
                case 0x76:                                   // invoke-direct/range
                    if (!"<init>".equals(name)) return call;
                    if (call.params.size() == 1) ctorOne.add(call);
                    else if (call.params.size() == 2) ctorTwo.add(call);
                    if (call.params.size() >= 6) ctorMany.add(call);
                    return call;
                case 0x6e:                                   // invoke-virtual
                case 0x72:                                   // invoke-interface
                case 0x74:
                case 0x78:
                    if (call.params.size() == 1) virtuals.add(call);
                    return call;
                case 0x71:                                   // invoke-static
                case 0x77:
                    if (call.params.isEmpty()) statics.add(call);
                    return call;
                default:
                    // invoke-super 与调用点判定无关
                    return call;
            }
        }
    }

    /**
     * 一次链式调用：源码形态 {@code X.Y.e().g(c)}。
     *
     * 参考项目正是用这条正则定位 j1 的（{@code (\w+)\.(\w+)\.([a-z])\(\)\s*\.\s*([a-z])\(}），
     * 它同时确定了提交器类 / 静态方法 / 实例方法 / c 四个信息 ——
     * 也是唯一能区分「同类里另一个同形静态方法」的依据（早先这里取错过 instance=d）。
     *
     * 注意参考项目的正则**不检查 g 声明在哪个类上**（只要链式写法成立即可，
     * 继承/接口来的方法也算），所以 exactType=false 的链同样收录，只是排在后面用。
     */
    static final class Chain {
        final String holder;      // 静态方法所在类（dex 内部名）
        final String staticM;
        final String instanceM;
        final String cDesc;       // 实例方法唯一参数的描述符，如 Lko0/c;
        final boolean exactType;  // g 是否就声明在静态方法的返回类型上

        Chain(String holder, String staticM, String instanceM, String cDesc, boolean exactType) {
            this.holder = holder;
            this.staticM = staticM;
            this.instanceM = instanceM;
            this.cDesc = cDesc;
            this.exactType = exactType;
        }

        @Override
        public String toString() {
            return holder.replace('/', '.') + "." + staticM + "()." + instanceM
                    + "(" + cDesc.replace('/', '.') + ")"
                    + (exactType ? "" : "[继承声明]");
        }
    }

    /**
     * dex 指令长度表（单位为 16 位 code unit），未列出的项为 0 表示未知。
     * 只用于按长度跳过指令，不解释语义。
     */
    private static final int[] LENGTH = buildLengthTable();

    private static int[] buildLengthTable() {
        int[] t = new int[256];
        // 默认 1 unit
        for (int i = 0; i < 256; i++) t[i] = 1;
        // 2 units
        for (int op : new int[]{
                0x02, 0x05, 0x08, 0x12, 0x14, 0x16, 0x18,
                0x1a, 0x1c, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26,
                0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35,
                0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e,
                0x3f, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47,
                0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, 0x4f, 0x50,
                0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
                0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62,
                0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b,
                0x6c, 0x6d, 0x79, 0x7a,
                0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d,
                0x8e, 0x8f, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96,
                0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
                0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8,
                0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf,
                0xd0, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8,
                0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xe0, 0xe1,
                0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea,
                0xeb, 0xec, 0xed, 0xee, 0xef, 0xf0, 0xf1, 0xf2, 0xf3,
                0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc,
                0xfd, 0xfe, 0xff
        }) t[op] = 2;
        // 3 units
        for (int op : new int[]{
                0x03, 0x06, 0x09, 0x13, 0x15, 0x17, 0x19, 0x1b, 0x27, 0x28,
                0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78
        }) t[op] = 3;
        return t;
    }
}
