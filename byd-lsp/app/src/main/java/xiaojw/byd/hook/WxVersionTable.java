package xiaojw.byd.hook;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xiaojw.byd.core.LogStore;

/**
 * 微信版本适配表。
 *
 * 取代原先硬编码在 {@link WxCodeHook} 里的 DEFAULT_VERSION_CONFIG 字符串：
 * 默认值来自 assets/wxcode_versions.json（按 xiaojinwen/wxcode-hook-config 的
 * 提取规则与已知版本表生成），并允许被控制台下发的覆盖表叠加/修正。
 *
 * 解析优先级：内置资源 → 控制台覆盖（含运行时 dex 探测生成的新版本条目）
 */
public final class WxVersionTable {


    /**
     * LoginTask 字段名按「角色」配置：键是角色，值是该角色在具体微信版本里的混淆字段名。
     *
     * 注意这些字段名和 j1/c/a1/a7 一样会随微信版本漂移，但
     * xiaojinwen/wxcode-hook-config 只提取那四个类名、并不提取它们 ——
     * 所以一旦微信改了这批字段名，光靠版本表是修不好的，必须能单独覆盖。
     * 默认值取自原 wxcode 模块（8.0.49 ~ 8.0.76 实测可用）。
     */
    public static final Map<String, String> DEFAULT_LOGIN_TASK_FIELDS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("action", "o");   // 写：String，赋 "login"
        m.put("appId",  "p");   // 写：String，赋小程序 AppID
        m.put("s",      "s");   // 写：int，赋 1
        m.put("v",      "v");   // 写：String，赋 ""
        m.put("t",      "t");   // 写：int，赋 0
        m.put("u",      "u");   // 写：int，赋 0
        m.put("A",      "A");   // 写：int，赋 1271
        m.put("state",  "r");   // 读：非 null 表示登录流程结束
        m.put("code",   "q");   // 读：真正的 code
        DEFAULT_LOGIN_TASK_FIELDS = Collections.unmodifiableMap(m);
    }

    /** 单个微信版本的适配配置 */
    public static final class Entry {
        public final String version;
        public final String j1;
        public final String c;
        public final String a1;
        public final String a7;
        public final String j1StaticMethod;
        public final String j1InstanceMethod;
        public final String note;
        /** 只保存与默认值不同的角色映射 */
        public final Map<String, String> loginTask;

        Entry(String version, String j1, String c, String a1, String a7,
              String j1StaticMethod, String j1InstanceMethod, String note) {
            this(version, j1, c, a1, a7, j1StaticMethod, j1InstanceMethod, note, null);
        }

        Entry(String version, String j1, String c, String a1, String a7,
              String j1StaticMethod, String j1InstanceMethod, String note,
              Map<String, String> loginTask) {
            this.version = version;
            this.j1 = j1;
            this.c = c;
            this.a1 = a1;
            this.a7 = a7;
            this.j1StaticMethod = j1StaticMethod;
            this.j1InstanceMethod = j1InstanceMethod;
            this.note = note;
            this.loginTask = loginTask == null ? Collections.<String, String>emptyMap() : loginTask;
        }

        /** 某个角色在本版本里对应的实际字段名 */
        public String loginTaskField(String role) {
            String v = loginTask.get(role);
            return (v != null && !v.isEmpty()) ? v : DEFAULT_LOGIN_TASK_FIELDS.get(role);
        }

        public boolean isComplete() {
            return notEmpty(j1) && notEmpty(c) && notEmpty(a1) && notEmpty(a7);
        }

        private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("j1", j1);
                o.put("c", c);
                o.put("a1", a1);
                o.put("a7", a7);
                if (notEmpty(j1StaticMethod) && !"d".equals(j1StaticMethod)) o.put("j1_static_method", j1StaticMethod);
                if (notEmpty(j1InstanceMethod) && !"g".equals(j1InstanceMethod)) o.put("j1_instance_method", j1InstanceMethod);
                if (!loginTask.isEmpty()) {
                    JSONObject lt = new JSONObject();
                    for (Map.Entry<String, String> e : loginTask.entrySet()) {
                        lt.put(e.getKey(), e.getValue());
                    }
                    o.put("loginTask", lt);
                }
                if (notEmpty(note)) o.put("_note", note);
            } catch (Exception ignored) {
                // 字段都是字符串，实际不会抛
            }
            return o;
        }
    }

    private final Map<String, Entry> versions = new LinkedHashMap<>();
    private String commonStatic = "d";
    private String commonInstance = "g";
    private final String rawAssetJson;

    private WxVersionTable(String rawAssetJson) {
        this.rawAssetJson = rawAssetJson;
    }

    /* ==================== 加载 ==================== */

    /**
     * 构建版本表：内置默认值 + 控制台下发的覆盖层。
     *
     * 内置值来自 {@link WxVersionTableDefault} —— 它是构建时从
     * assets/wxcode_versions.json 编译进来的 Java 常量。
     *
     * **不能在运行时读 assets**：模块跑在微信进程里，那里的 getAssets() 指向宿主 APK，
     * 读不到本模块自己的资源文件（这正是之前「内置表一直是空的、只能靠覆盖层撑着」
     * 的原因，也是覆盖层一旦被清空取码就彻底失败的原因）。
     */
    public static WxVersionTable load(String overrideJson) {
        String builtin = WxVersionTableDefault.JSON;
        WxVersionTable t = new WxVersionTable(builtin);
        t.parseInto(builtin, false);
        if (overrideJson != null && !overrideJson.isEmpty()) {
            t.parseInto(overrideJson, true);
        }
        // 这行不在这里打：版本表会被重建多次（启动、配置推送、探测结果），
        // 每次打一行会把日志刷满。调用方在真正发生变化时才输出摘要。
        if (t.versions.isEmpty()) {
            LogStore.err("版本表为空 —— 内置默认值没编译进来，取码必然失败");
        }
        return t;
    }


    /**
     * 复制一份并把额外片段并进去（片段优先）。
     * 用于运行时探测出新版本后热更新，不重启微信即可生效。
     */
    public WxVersionTable copyWith(String extraJson) {
        WxVersionTable copy = new WxVersionTable(rawAssetJson);
        copy.parseInto(rawAssetJson, false);
        copy.parseInto(this.toJson(), false);
        if (extraJson != null && !extraJson.isEmpty()) {
            copy.parseInto(extraJson, true);
        }
        return copy;
    }


    /** assets 里的文件名；App 侧（控制台）读它做展示，模块侧用的是编译进来的常量 */

    /**
     * @param override true 表示来自控制台的覆盖表，冲突时覆盖内置值
     */
    private void parseInto(String json, boolean override) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);

            JSONObject common = root.optJSONObject("common");
            if (common != null) {
                commonStatic = common.optString("j1_static_method", commonStatic);
                commonInstance = common.optString("j1_instance_method", commonInstance);
            }

            // 形态一：完整表 {"common":{...},"versions":{...}}
            // 形态二：单版本片段 {"8.0.79":{"j1":...}}
            JSONObject vs = root.optJSONObject("versions");
            if (vs == null) vs = root;

            for (java.util.Iterator<String> it = vs.keys(); it.hasNext(); ) {
                String ver = it.next();
                if (ver.startsWith("_")) continue;
                if (!looksLikeVersion(ver)) continue;
                JSONObject o = vs.optJSONObject(ver);
                if (o == null) continue;

                // 片段里可能又包了一层 versions
                JSONObject nested = o.optJSONObject("versions");
                if (nested != null) {
                    JSONObject inner = nested.optJSONObject(ver);
                    if (inner != null) o = inner;
                }

                Entry existing = versions.get(ver);
                if (existing != null && !override) continue;

                // LoginTask 字段名映射：沿用已有的，再用本层的覆盖
                Map<String, String> lt = new LinkedHashMap<>();
                if (existing != null) lt.putAll(existing.loginTask);
                JSONObject ltJson = o.optJSONObject("loginTask");
                if (ltJson != null) {
                    for (java.util.Iterator<String> k = ltJson.keys(); k.hasNext(); ) {
                        String role = k.next();
                        String fname = ltJson.optString(role, "");
                        if (fname.isEmpty()) lt.remove(role);
                        else lt.put(role, fname);
                    }
                }

                Entry merged = new Entry(
                        ver,
                        o.optString("j1", existing != null ? existing.j1 : null),
                        o.optString("c", existing != null ? existing.c : null),
                        o.optString("a1", existing != null ? existing.a1 : null),
                        o.optString("a7", existing != null ? existing.a7 : null),
                        o.optString("j1_static_method",
                                existing != null ? existing.j1StaticMethod : commonStatic),
                        o.optString("j1_instance_method",
                                existing != null ? existing.j1InstanceMethod : commonInstance),
                        o.optString("_note", existing != null ? existing.note : null),
                        lt);
                versions.put(ver, merged);
            }
        } catch (Throwable t) {
            LogStore.warn("版本表解析失败: " + t.getMessage());
        }
    }

    private static boolean looksLikeVersion(String s) {
        return s != null && s.matches("\\d+(\\.\\d+){1,3}");
    }

    /* ==================== 查询 ==================== */


    /** 精确匹配；未收录返回 null */
    public Entry resolve(String version) {
        Entry e = versions.get(version);
        return e != null && e.isComplete() ? e : null;
    }

    /**
     * 兜底：取不高于当前版本的最新一条，都没有则取表里最新的。
     * 只作为「未适配版本」的尽力尝试，适配状态仍按精确匹配判定。
     */
    public Entry nearest(String version) {
        Entry newest = null;
        for (Entry e : versions.values()) {
            if (!e.isComplete()) continue;
            if (newest == null || compare(e.version, newest.version) > 0) newest = e;
        }
        if (version == null || version.isEmpty()) return newest;

        Entry best = null;
        for (Entry e : versions.values()) {
            if (!e.isComplete()) continue;
            if (compare(e.version, version) <= 0
                    && (best == null || compare(e.version, best.version) > 0)) {
                best = e;
            }
        }
        return best != null ? best : newest;
    }

    public List<String> knownVersions() {
        List<String> list = new ArrayList<>(versions.keySet());
        Collections.sort(list, (a, b) -> compare(a, b));
        return list;
    }

    /** "8.0.10" 要排在 "8.0.9" 之后，因此按数字段比较 */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            String digits = s.replaceAll("\\D", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    /* ==================== 输出 ==================== */

    public String toJson() {
        JSONObject root = new JSONObject();
        try {
            JSONObject common = new JSONObject();
            common.put("j1_static_method", commonStatic);
            common.put("j1_instance_method", commonInstance);
            root.put("common", common);

            JSONObject vs = new JSONObject();
            for (Map.Entry<String, Entry> e : versions.entrySet()) {
                vs.put(e.getKey(), e.getValue().toJson());
            }
            root.put("versions", vs);
        } catch (Exception ignored) {
            // 同上，纯字符串写入不会抛
        }
        return root.toString();
    }


}
