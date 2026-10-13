package xiaojw.byd.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

import xiaojw.byd.core.BydConfig;
import xiaojw.byd.core.Ipc;

/**
 * 控制台侧持久化。
 *
 * 微信进程里的模块只推送增量日志与状态快照，落盘、按天清理、
 * 多实例（主应用 + 分身）合并展示都由 App 侧负责——
 * 这正好接替了原 byd_sign 里 clean_old_logs / WebUI 读日志文件的职责。
 */
public final class AppStore {

    private static final String SP = "byd_lsp_app";
    private static final String K_CONFIG = "config_json";
    private static final String K_STATE = "state_map";
    private static final String K_PROBE = "probe_status";
    /** 最近一次听到模块回话的时间（STATE 或 PONG 都算） */
    private static final String K_LAST_SEEN = "last_seen_at";
    /** 最近一次握手应答原文 */
    private static final String K_LAST_PONG = "last_pong";
    /** 本机累计收到的日志批数，用来跟模块侧的推送计数对账 */
    private static final String K_LOG_RECEIPTS = "log_receipts";
    /** 模块侧当前实际生效的配置 */
    private static final String K_MODCFG = "module_config";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "byd.log";
    private static final int MAX_BYTES = 512 * 1024;
    private static final int KEEP_LINES = 300;

    private AppStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* ==================== 配置 ==================== */

    public static String loadConfigJson(Context c) {
        return sp(c).getString(K_CONFIG, null);
    }

    public static void saveConfigJson(Context c, String json) {
        sp(c).edit().putString(K_CONFIG, json).apply();
        writeConfigFile(c, json);
    }

    /* ==================== 配置文件（一个能看见的文件） ==================== */

    private static final String CONFIG_FILE = "config.json";

    /**
     * 配置同时写一份到「外部私有目录」，让人能看见、能备份、能用文件管理器查看。
     *
     * 说明：模块跑在微信进程里，读不到我们这个目录，所以它走的还是广播通道；
     * 这个文件是**同一份配置的可读副本**，不是模块的读取源。
     * 路径：/sdcard/Android/data/io.github.linbailo.byd/files/config.json
     */
    public static File configFile(Context c) {
        File dir = c.getApplicationContext().getExternalFilesDir(null);
        if (dir == null) dir = c.getApplicationContext().getFilesDir();
        return new File(dir, CONFIG_FILE);
    }

    public static String configFilePath(Context c) {
        return configFile(c).getAbsolutePath();
    }

    public static void writeConfigFile(Context c, String json) {
        if (json == null || json.isEmpty()) return;
        try {
            File f = configFile(c);
            f.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(f, false), UTF8))) {
                w.write(json);
            }
        } catch (Exception e) {
            // 外部目录不可用时只影响这份可读副本 —— 但要说出来，
            // 否则「文件里看不到」会被误当成「根本没写进去」
            appendDiagnostic(c, "WARN", "写配置文件副本失败（只影响可读副本，App 内部配置仍在）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 从文件读回配置；文件不存在或损坏返回 null */
    public static String readConfigFile(Context c) {
        try {
            File f = configFile(c);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), UTF8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 版本适配表 ==================== */

    /**
     * 模块探测生成的版本表：直接写进配置里的 versionTable 字段。
     * 模块侧已经和内置表做过合并，这里整体替换即可。
     */
    /**
     * 写入探测生成/更新的版本表。
     *
     * 注意**不要**过一遍 BydConfig：它只认识自己声明的字段，
     * 往返一次会把 App 独有的键（wakeBeforeCode、schema）悄悄删掉 ——
     * 之前「改了的设置像没保存」就有这一份责任。这里按 JSON 就地改。
     */
    public static void setVersionTable(Context c, String tableJson) {
        if (tableJson == null || tableJson.isEmpty()) {
            appendDiagnostic(c, "WARN", "收到空的版本表，忽略（不覆盖已有配置）");
            return;
        }
        try {
            JSONObject table = new JSONObject(tableJson);
            if (table.length() == 0) {                     // 空表不覆盖
                appendDiagnostic(c, "WARN", "收到空白的版本表，忽略");
                return;
            }
            String cur = loadConfigJson(c);
            JSONObject cfg;
            try {
                cfg = (cur == null || cur.isEmpty()) ? new JSONObject(BydConfig.defaults().toJson())
                                                     : new JSONObject(cur);
            } catch (Exception e) {
                cfg = new JSONObject(BydConfig.defaults().toJson());
            }
            cfg.put("versionTable", table);
            saveConfigJson(c, cfg.toString());

            // 记一行，方便确认「模块的表到底有没有落进配置文件」
            JSONObject vs = table.optJSONObject("versions");
            int n = vs == null ? 0 : vs.length();
            StringBuilder names = new StringBuilder();
            if (vs != null) {
                java.util.Iterator<String> it = vs.keys();
                while (it.hasNext()) {
                    if (names.length() > 0) names.append("、");
                    names.append(it.next());
                }
            }
            appendDiagnostic(c, "OK", "已把模块的版本表写进配置：共 " + n + " 个版本（"
                    + names + "）→ " + configFilePath(c));
        } catch (Exception e) {
            // 表坏了就不要污染已有配置
            appendDiagnostic(c, "ERR", "写入版本表失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static String loadVersionTable(Context c) {
        BydConfig cfg = BydConfig.fromJson(loadConfigJson(c));
        return cfg.versionTable == null ? "" : cfg.versionTable;
    }

    /** 探测状态：{version,state,exact,note,reason,steps} */
    public static void saveProbeStatus(Context c, String json) {
        if (json == null || json.isEmpty()) return;
        sp(c).edit().putString(K_PROBE, json).apply();
    }

    public static String loadProbeStatus(Context c) {
        return sp(c).getString(K_PROBE, "{}");
    }

    /* ==================== 状态（按实例合并） ==================== */

    public static void mergeState(Context c, String stateJson) {
        if (stateJson == null || stateJson.isEmpty()) return;
        try {
            JSONObject incoming = new JSONObject(stateJson);
            JSONObject map = new JSONObject(sp(c).getString(K_STATE, "{}"));

            // 顶层字段取最新一份（版本/模式/运行态在各实例间是同一套配置）
            for (Iterator<String> it = incoming.keys(); it.hasNext(); ) {
                String k = it.next();
                if ("instances".equals(k)) continue;
                map.put(k, incoming.get(k));
            }

            JSONArray inInstances = incoming.optJSONArray("instances");
            if (inInstances != null) {
                JSONObject merged = map.optJSONObject("instances");
                if (merged == null) merged = new JSONObject();
                for (int i = 0; i < inInstances.length(); i++) {
                    JSONObject inst = inInstances.optJSONObject(i);
                    if (inst == null) continue;
                    merged.put(String.valueOf(inst.optInt("userId", 0)), inst);
                }
                map.put("instances", merged);
            }
            sp(c).edit().putString(K_STATE, map.toString()).apply();
        } catch (Exception ignored) {
            // 状态是展示用的，单次解析失败不影响功能
        }
    }

    /** 输出给 WebUI 的状态：把按 userId 存的实例摊平成数组 */
    public static String loadStateJson(Context c) {
        try {
            JSONObject map = new JSONObject(sp(c).getString(K_STATE, "{}"));
            JSONObject instances = map.optJSONObject("instances");
            JSONArray arr = new JSONArray();
            if (instances != null) {
                for (Iterator<String> it = instances.keys(); it.hasNext(); ) {
                    JSONObject inst = instances.optJSONObject(it.next());
                    if (inst != null) arr.put(inst);
                }
            }
            map.put("instances", arr);
            String cfg = loadConfigJson(c);
            map.put("config", cfg == null ? new JSONObject() : new JSONObject(cfg));
            return map.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /* ==================== 模块注入状态（握手） ==================== */

    /** 收到任何来自模块的回写都算「它还活着」 */
    public static void markSeen(Context c) {
        sp(c).edit().putLong(K_LAST_SEEN, System.currentTimeMillis()).apply();
    }

    /**
     * 处理握手应答。
     *
     * 这一步同时做两件事：
     *   1. 记录「模块确实注入到微信进程了」以及应答时间
     *   2. 把应答里的实例信息并进状态表 —— 于是哪怕一次签到都还没跑过，
     *      实例页也能立刻显示这个微信实例，而不是一片空白
     */
    public static void applyPong(Context c, String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject p = new JSONObject(json);
            long at = p.optLong("at", System.currentTimeMillis());
            sp(c).edit()
              .putString(K_LAST_PONG, json)
              .putLong(K_LAST_SEEN, at)
              .apply();

            JSONObject map = new JSONObject(sp(c).getString(K_STATE, "{}"));
            map.put("version", p.optString("version", map.optString("version", "—")));
            map.put("versionExact", p.optBoolean("versionExact", false));
            map.put("versionNote", p.optString("versionNote", ""));
            map.put("probeState", p.optString("probeState", "idle"));
            map.put("probeReason", p.optString("probeReason", ""));
            map.put("running", p.optBoolean("running", false));
            map.put("moduleVersion", p.optString("moduleVersion", ""));
            map.put("injected", true);
            map.put("seenAt", at);

            JSONObject instances = map.optJSONObject("instances");
            if (instances == null) instances = new JSONObject();
            int uid = p.optInt("userId", 0);
            String key = String.valueOf(uid);
            JSONObject inst = instances.optJSONObject(key);
            if (inst == null) inst = new JSONObject();
            inst.put("id", uid);
            inst.put("userId", uid);
            inst.put("name", p.optString("instance", uid == 0 ? "主应用" : "分身 " + uid));
            inst.put("streak", p.optInt("streak", 0));
            inst.put("signedToday", p.optBoolean("signedToday", false));
            inst.put("lastTime", p.optString("lastTime", "—"));
            inst.put("pid", p.optInt("pid", 0));
            inst.put("version", p.optString("version", ""));
            inst.put("seenAt", at);
            instances.put(key, inst);
            map.put("instances", instances);

            sp(c).edit().putString(K_STATE, map.toString()).apply();
        } catch (Exception ignored) {
            // 握手数据是展示用的，坏了不影响功能
        }
    }

    /**
     * 自检 / 试取码的结果（adb 调试入口）写进同一条日志。
     *
     * 之前它们是存进两个私有 SharedPreferences 键，而对应的读取方法没有任何调用者 ——
     * 等于「跑了自检，哪都看不到」。日志页才是这套调试入口该露出的地方。
     */
    public static void appendDiagnostic(Context c, String level, String msg) {
        if (msg == null || msg.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("ts", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            o.put("level", level == null ? "INFO" : level);
            o.put("msg", msg);
            JSONArray arr = new JSONArray();
            arr.put(o);
            appendLogs(c, arr.toString());
        } catch (Throwable ignored) {
            // 落盘失败不影响主流程
        }
    }

    /* ---------------- 日志投递对账 ---------------- */

    /** 本机累计收到的日志批数（每收到一条 LOG / LOGSNAP 广播 +1） */
    public static void countLogReceipt(Context c) {
        try {
            sp(c).edit().putLong(K_LOG_RECEIPTS, receipts(c) + 1).apply();
        } catch (Throwable ignored) {
            // 计数失败不影响落盘
        }
    }

    public static long receipts(Context c) {
        return sp(c).getLong(K_LOG_RECEIPTS, 0L);
    }

    /** 模块在最近一次握手里上报的「已推送批数」（模块侧计数） */
    public static long modulePushes(Context c) {
        try {
            JSONObject p = new JSONObject(sp(c).getString(K_LAST_PONG, "{}"));
            return p.optLong("logPushes", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void saveModuleConfig(Context c, String json) {
        if (json == null || json.isEmpty()) return;
        sp(c).edit().putString(K_MODCFG, json).apply();
    }

    public static String loadModuleConfig(Context c) {
        return sp(c).getString(K_MODCFG, "{}");
    }

    /** 交给 WebUI 的模块状态：最近应答 + 距离现在多久 */
    public static String loadModuleStatus(Context c) {
        try {
            SharedPreferences p = sp(c);
            long lastSeen = p.getLong(K_LAST_SEEN, 0L);
            JSONObject o = new JSONObject();
            o.put("everSeen", lastSeen > 0);
            o.put("lastSeenAt", lastSeen);
            o.put("lastSeenAgo", lastSeen == 0 ? -1L : System.currentTimeMillis() - lastSeen);
            String pong = p.getString(K_LAST_PONG, null);
            o.put("pong", pong == null ? JSONObject.NULL : new JSONObject(pong));
            return o.toString();
        } catch (Exception e) {
            return "{\"everSeen\":false,\"lastSeenAgo\":-1}";
        }
    }

    /* ==================== 日志 ==================== */

    private static File logFile(Context c) {
        File dir = new File(c.getApplicationContext().getFilesDir(), LOG_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, LOG_FILE);
    }

    public static synchronized void appendLogs(Context c, String jsonArray) {
        if (jsonArray == null || jsonArray.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(jsonArray);
            if (arr.length() == 0) return;
            File f = logFile(c);
            String day = todayStr();
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(f, true), UTF8))) {
                for (int i = 0; i < arr.length(); i++) {
                    String line = formatLine(day, arr.optJSONObject(i));
                    if (line == null) continue;
                    w.write(line);
                    w.write("\n");
                }
            }
            compactIfNeeded(c, f);
        } catch (Exception ignored) {
            // 日志落盘失败不影响签到
        }
    }

    /**
     * 用模块日志的整体快照**补齐**本地日志（只补不删）。
     *
     * 用途：App 打开时主动向模块拉取完整日志缓冲。自动签到发生在 App 不在前台的时候，
     * 那条增量广播可能被 Doze 推迟、或在 App 进程刚起来时丢掉，日志页就看不到它；
     * 而模块内存里一直留着最近 MAX 行。
     *
     * 原实现是 `FileOutputStream(f, false)` **整体覆盖**，会把这批快照里没有的本地行全部抹掉：
     * App 侧自己记的那几行、以及模块环形缓冲已经挤掉的更早历史都没了 ——
     * 于是「日志保留 N 天」形同虚设，每次打开 App 都把历史截成最近 300 行。
     * 现在按 (日期+时间+级别+内容) 去重后**追加**。
     */
    public static synchronized void mergeLogs(Context c, String jsonArray) {
        if (jsonArray == null || jsonArray.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(jsonArray);
            if (arr.length() == 0) return;

            File f = logFile(c);
            String day = todayStr();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String line : readLines(f)) {
                String k = logLineKey(line);
                if (k != null) seen.add(k);
            }

            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(f, true), UTF8))) {
                for (int i = 0; i < arr.length(); i++) {
                    String line = formatLine(day, arr.optJSONObject(i));
                    if (line == null) continue;
                    String k = logLineKey(line);
                    if (k == null || !seen.add(k)) continue;   // 已有就跳过，避免反复叠加
                    w.write(line);
                    w.write("\n");
                }
            }
            compactIfNeeded(c, f);
        } catch (Exception ignored) {
            // 日志落盘失败不影响签到
        }
    }

    /** 日志行的去重键 */
    private static String logLineKey(String line) {
        try {
            JSONObject o = new JSONObject(line);
            return o.optString("d") + "\u0001" + o.optString("ts") + "\u0001"
                 + o.optString("level") + "\u0001" + o.optString("msg");
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== App 侧（闹钟）日志 ==================== */

    /**
     * 把 App 侧闹钟的事件写进同一条日志。
     *
     * 两条路一起走：先请模块记一笔 —— 它才是日志的源头（App 打开时拉的是模块的
     * 整体快照，只有写进模块的缓冲才不会在历史被挤掉后找不到）；
     * 再自己追加一份，微信进程没起来时至少本地留得下。
     */
    public static void appSideLog(Context c, String level, String msg) {
        if (msg == null || msg.isEmpty()) return;
        String lv = (level == null || level.isEmpty()) ? "INFO" : level;
        String text = "App 侧：" + msg;

        try {
            JSONObject o = new JSONObject();
            o.put("ts", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            o.put("level", lv);
            o.put("msg", text);
            JSONArray arr = new JSONArray();
            arr.put(o);
            appendLogs(c, arr.toString());
        } catch (Throwable ignored) {
            // 落盘失败不影响执行
        }

        try {
            JSONObject arg = new JSONObject();
            arg.put("level", lv);
            arg.put("msg", text);
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", Ipc.CMD_APP_LOG);
            cmd.put("arg", arg.toString());
            Ipc.toHook(c, Ipc.ACTION_CMD, cmd.toString());
        } catch (Throwable ignored) {
            // 模块不在时只留本地那份
        }
    }

    private static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String formatLine(String day, JSONObject o) {
        if (o == null) return null;
        try {
            JSONObject line = new JSONObject();
            line.put("d", day);
            line.put("ts", o.optString("ts", ""));
            line.put("level", o.optString("level", "INFO"));
            line.put("msg", o.optString("msg", ""));
            return line.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 超过上限就只保留最后 KEEP_LINES 行，避免文件无限增长 */
    private static void compactIfNeeded(Context c, File f) {
        if (f.length() <= MAX_BYTES) return;
        try {
            java.util.List<String> lines = readLines(f);
            int from = Math.max(0, lines.size() - KEEP_LINES);
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(f, false), UTF8))) {
                for (int i = from; i < lines.size(); i++) {
                    w.write(lines.get(i));
                    w.write("\n");
                }
            }
        } catch (Exception ignored) {
            // 压缩失败时下次再试
        }
    }

    /** 读取保留期内的最后 KEEP_LINES 行，返回 JSON 数组 */
    public static String loadLogsJson(Context c, int keepDays) {
        JSONArray out = new JSONArray();
        File f = logFile(c);
        if (!f.exists()) return out.toString();
        try {
            java.util.Set<String> validDays = new java.util.HashSet<>();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            for (int i = 0; i < Math.max(1, keepDays); i++) {
                validDays.add(sdf.format(cal.getTime()));
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
            }
            java.util.List<String> lines = readLines(f);
            int from = Math.max(0, lines.size() - KEEP_LINES);
            for (int i = from; i < lines.size(); i++) {
                try {
                    JSONObject o = new JSONObject(lines.get(i));
                    if (!validDays.contains(o.optString("d", ""))) continue;
                    JSONObject item = new JSONObject();
                    item.put("ts", o.optString("ts", ""));
                    item.put("level", o.optString("level", "INFO"));
                    item.put("msg", o.optString("msg", ""));
                    out.put(item);
                } catch (Exception ignored) {
                    // 跳过损坏行
                }
            }
        } catch (Exception ignored) {
            // 读取失败时返回已累积的部分
        }
        return out.toString();
    }

    public static synchronized void clearLogs(Context c) {
        File f = logFile(c);
        if (f.exists()) f.delete();
    }

    private static java.util.List<String> readLines(File f) throws Exception {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), UTF8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }
}
