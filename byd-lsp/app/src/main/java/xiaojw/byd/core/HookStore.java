package xiaojw.byd.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块侧持久化（存在微信自己的 SharedPreferences 里）。
 *
 * 两个进程的私有目录互不可见，所以：
 *   配置 —— 控制台 App 存自己那份并推给模块；模块缓存一份在这里，微信重启后仍可用
 *   状态 —— 签到日期、连续天数、已触发的定时点、待处理指令，都落在这里
 *
 * 微信多开/分身各自是独立进程与独立 data 目录，因此天然「一账号一份状态」，
 * 不需要原方案里那套 8088+userId 的端口分配和实例注册。
 */
public final class HookStore {

    private static final String SP = "byd_lsp_cache";

    private static final String K_CONFIG = "config_json";
    private static final String K_CONFIG_SEEN = "config_seen";
    private static final String K_SIGNED_DATE = "signed_date";
    private static final String K_STREAK = "streak";
    private static final String K_LAST_RESULT = "last_result";
    private static final String K_LAST_TIME = "last_time";
    private static final String K_PENDING = "pending_cmd";
    /** 定时点台账：每个定时点当天的状态、尝试次数、下次重试时间 */
    private static final String K_SLOT_LEDGER = "slot_ledger";
    /** 已为哪个微信版本做过 dex 探测，以及那次的结果 —— 避免每次启动都重新扫全包 dex */
    private static final String K_PROBE_VERSION = "probe_version";
    private static final String K_PROBE_RESULT = "probe_result";
    /** 那次探测是哪一版模块做的：模块升级后允许重探一次 */
    private static final String K_PROBE_MODULE = "probe_module_version";
    /** 探测出来的版本配置片段（当前版本仍未收录时用它兜底） */
    private static final String K_PROBE_TABLE = "probe_table";
    /** 上一次打过「生效配置」日志的指纹，用于去掉重复日志 */
    private static final String K_LAST_FINGERPRINT = "last_fingerprint";

    private final SharedPreferences sp;

    public HookStore(Context wechatCtx) {
        this.sp = wechatCtx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* ---------------- 配置 ---------------- */

    public BydConfig loadConfig() {
        return BydConfig.fromJson(sp.getString(K_CONFIG, null));
    }

    public void saveConfig(BydConfig c) {
        sp.edit().putString(K_CONFIG, c.toJson()).putBoolean(K_CONFIG_SEEN, true).apply();
    }

    /** 冷启动时若从未收到过配置，需要主动向控制台索要一份 */
    public boolean hasConfig() {
        return sp.getBoolean(K_CONFIG_SEEN, false);
    }

    /* ---------------- dex 探测记账 ---------------- */

    /**
     * 是否已经为这个微信版本尝试过探测。
     *
     * 探测要扫全包 dex（微信几百 MB），代价很高；而结果无论成功失败都值得记住 ——
     * 之前没有任何持久标记，于是每次微信启动 + 每次打开 App 都会重扫一遍，
     * 表现为微信卡顿、日志里反复出现「正在解析 dex」。
     *
     * 但记账里同时存了「是哪一版模块探的」：**模块升级后探测逻辑可能已经改好**，
     * 这时要允许重探一次 —— 否则上一版探测失败记下的账，会把新逻辑一直挡在门外
     * （实机上就出现过：改了探测规则、装上新版，却因为「已探测过」而不再重试）。
     */
    public boolean probeDoneFor(String version) {
        if (version == null || version.isEmpty()) return false;
        if (!version.equals(sp.getString(K_PROBE_VERSION, ""))) return false;
        return ModuleState.moduleVersion != null
                && ModuleState.moduleVersion.equals(sp.getString(K_PROBE_MODULE, ""));
    }

    public void markProbeDone(String version, boolean ok) {
        sp.edit()
          .putString(K_PROBE_VERSION, version == null ? "" : version)
          .putString(K_PROBE_RESULT, ok ? "ok" : "failed")
          .putString(K_PROBE_MODULE, ModuleState.moduleVersion == null ? "" : ModuleState.moduleVersion)
          .apply();
    }

    public String probeResult() { return sp.getString(K_PROBE_RESULT, ""); }

    /**
     * 本机探测出来的版本配置（片段），单独存一份。
     *
     * 控制台推来的配置里如果**没有当前微信版本**的条目（用户没保存过、或客户端把
     * versionTable 传成了 null），就用这一份补上 —— 否则一次配置推送就会把刚探测出来的
     * 配置冲回内置表的「就近匹配」。实机上出现过：探测成功生成 j1=pp0.i1/c=ko0.c，
     * 紧接着一次配置推送又退回 8.0.78 的 gp0.j1/bo0.c，取码直接 ClassNotFound。
     */
    public void saveProbeTable(String fragmentJson) {
        if (fragmentJson == null || fragmentJson.isEmpty()) return;
        sp.edit().putString(K_PROBE_TABLE, fragmentJson).apply();
    }

    public String probeTable() { return sp.getString(K_PROBE_TABLE, ""); }

    /** 记账里记录的版本号（没记过则为空） */
    public String probedVersion() { return sp.getString(K_PROBE_VERSION, ""); }

    /** 版本变化时清掉探测记账，让新版本重新探测一次 */
    public void clearProbeRecord() {
        sp.edit().remove(K_PROBE_VERSION).remove(K_PROBE_RESULT)
          .remove(K_PROBE_MODULE).remove(K_PROBE_TABLE).apply();
    }

    /* ---------------- 配置原文与日志指纹 ---------------- */

    /** 配置原文，用于「没变就不重复处理」的比较 */
    public String rawConfig() { return sp.getString(K_CONFIG, ""); }

    public String lastFingerprint() { return sp.getString(K_LAST_FINGERPRINT, ""); }

    public void saveFingerprint(String fp) {
        sp.edit().putString(K_LAST_FINGERPRINT, fp == null ? "" : fp).apply();
    }

    /* ---------------- 日期与状态 ---------------- */

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public boolean signedToday() {
        return today().equals(sp.getString(K_SIGNED_DATE, ""));
    }

    public void markSigned() {
        sp.edit().putString(K_SIGNED_DATE, today()).apply();
    }

    public int streak() { return sp.getInt(K_STREAK, 0); }

    public void setStreak(int v) { sp.edit().putInt(K_STREAK, Math.max(0, v)).apply(); }

    public String lastResult() { return sp.getString(K_LAST_RESULT, "—"); }

    public String lastTime() { return sp.getString(K_LAST_TIME, "—"); }

    public void recordResult(String result) {
        sp.edit()
          .putString(K_LAST_RESULT, result)
          .putString(K_LAST_TIME, new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()))
          .apply();
    }

    /* ---------------- 定时点台账 ---------------- */

    /** 今日不再尝试（成功、被服务端拒绝、或重试用尽） */
    public static final String SLOT_DONE = "done";
    /** 今日已签到，这个定时点主动跳过 */
    public static final String SLOT_SKIP = "skip";
    /** 试过但没成，等 nextAt 到点再试 */
    public static final String SLOT_RETRY = "retry";

    /**
     * 读今日台账。
     *
     * 之前只存「最后一次触发的定时点」一个整数，于是签到一失败，
     * 那个定时点就被当成已完成 —— 当天再也不会补，只能等第二天。
     * 现在每个定时点都记状态：没记过 = 还没试过，retry = 等重试，done/skip = 今天到此为止。
     */
    private JSONObject ledger() {
        try {
            String raw = sp.getString(K_SLOT_LEDGER, null);
            JSONObject o = (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
            // 跨天整本作废
            if (!today().equals(o.optString("date", ""))) return new JSONObject();
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveLedger(JSONObject o) {
        try {
            o.put("date", today());
            sp.edit().putString(K_SLOT_LEDGER, o.toString()).apply();
        } catch (Exception ignored) {
            // 记不上账不影响本次执行
        }
    }

    private JSONObject slotEntry(int slot) {
        JSONObject slots = ledger().optJSONObject("slots");
        JSONObject e = slots == null ? null : slots.optJSONObject(String.valueOf(slot));
        return e == null ? new JSONObject() : e;
    }

    private void updateSlot(int slot, String state, long nextAt, String note) {
        try {
            JSONObject l = ledger();
            JSONObject slots = l.optJSONObject("slots");
            if (slots == null) slots = new JSONObject();
            JSONObject e = slots.optJSONObject(String.valueOf(slot));
            if (e == null) e = new JSONObject();
            if (state != null) e.put("state", state);
            if (nextAt >= 0) e.put("nextAt", nextAt);
            if (note != null) e.put("note", note);
            slots.put(String.valueOf(slot), e);
            l.put("slots", slots);
            saveLedger(l);
        } catch (Exception ignored) {
            // 同上
        }
    }

    /**
     * 记一次尝试开始。返回这是第几次尝试（1 = 首次）。
     *
     * 这里就把 nextAt 先按退避表填上：万一微信进程在本次尝试中途被杀，
     * 下次启动时台账里仍有「什么时候再试」，不会永远卡在这一轮。
     */
    public int beginSlotAttempt(int slot, long nextAt) {
        JSONObject e = slotEntry(slot);
        int tries = e.optInt("tries", 0) + 1;
        try {
            JSONObject l = ledger();
            JSONObject slots = l.optJSONObject("slots");
            if (slots == null) slots = new JSONObject();
            JSONObject cur = slots.optJSONObject(String.valueOf(slot));
            if (cur == null) cur = new JSONObject();
            cur.put("tries", tries);
            cur.put("lastAt", System.currentTimeMillis());
            if (!cur.has("firstAt")) cur.put("firstAt", System.currentTimeMillis());
            cur.put("state", SLOT_RETRY);
            cur.put("nextAt", nextAt);
            slots.put(String.valueOf(slot), cur);
            l.put("slots", slots);
            saveLedger(l);
        } catch (Exception ignored) {
            // 同上
        }
        return tries;
    }

    /** 失败但要重试：更新原因和下一次的时间 */
    public void scheduleSlotRetry(int slot, long nextAt, String note) {
        updateSlot(slot, SLOT_RETRY, nextAt, note);
    }

    /** 终态：成功 / 今日不再重试 / 主动跳过 */
    public void finishSlot(int slot, String state, String note) {
        updateSlot(slot, state, 0L, note);
    }

    public String slotState(int slot) { return slotEntry(slot).optString("state", ""); }

    public int slotTries(int slot) { return slotEntry(slot).optInt("tries", 0); }

    public long slotNextRetryAt(int slot) { return slotEntry(slot).optLong("nextAt", 0L); }

    public long slotFirstAt(int slot) { return slotEntry(slot).optLong("firstAt", 0L); }

    public String slotNote(int slot) { return slotEntry(slot).optString("note", ""); }

    /* ---------------- App 侧闹钟的抢占 ---------------- */

    /**
     * App 侧闹钟要接这个定时点。
     *
     * 为什么需要抢占：App 的闹钟到点（hh:01:00）比模块心跳发现到点（最晚 hh:01:59）
     * 更早，但两边都可能动手签。写入抢占后，模块的心跳在这个窗口内让位，
     * 出网交给 App 进程 —— 那个进程刚被闹钟唤醒，系统会临时给它联网白名单。
     */
    public void markClaim(int slot) {
        updateSlotExtra(slot, "claimAt", System.currentTimeMillis());
    }

    /** App 侧那次已经收尾（成功或失败），把抢占放开，模块可以接管补签 */
    public void clearClaim(int slot) {
        updateSlotExtra(slot, "claimAt", 0L);
    }

    public long claimAt(int slot) { return slotEntry(slot).optLong("claimAt", 0L); }

    private void updateSlotExtra(int slot, String key, Object value) {
        try {
            JSONObject l = ledger();
            JSONObject slots = l.optJSONObject("slots");
            if (slots == null) slots = new JSONObject();
            JSONObject e = slots.optJSONObject(String.valueOf(slot));
            if (e == null) e = new JSONObject();
            e.put(key, value);
            slots.put(String.valueOf(slot), e);
            l.put("slots", slots);
            saveLedger(l);
        } catch (Exception ignored) {
            // 记不上账不影响本次执行
        }
    }

    /* ---------------- 指令暂存 ---------------- */

    /** 指令先落盘再执行：微信进程被杀时，下次启动可以补做 */
    public void putPending(String cmd, String arg) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", cmd);
            o.put("arg", arg == null ? "" : arg);
            o.put("at", System.currentTimeMillis());
            sp.edit().putString(K_PENDING, o.toString()).apply();
        } catch (Exception ignored) {
            // 落盘失败不影响本次直接执行
        }
    }

    public JSONObject takePending() {
        String raw = sp.getString(K_PENDING, null);
        if (raw == null) return null;
        sp.edit().remove(K_PENDING).apply();
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 状态快照 ---------------- */

    /**
     * 每个微信进程只代表一个账号，所以实例数组里永远只有自己这一条。
     * 控制台把收到的所有实例快照按 userId 合并展示。
     */
    public String stateJson(boolean running, String version, int userId, String name) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", version);
            o.put("running", running);
            o.put("streak", streak());
            o.put("signedToday", signedToday());
            o.put("last", lastResult());
            o.put("lastTime", lastTime());
            // 版本适配状态：控制台首页据此显示绿色/红色
            o.put("versionExact", ModuleState.versionExact);
            o.put("versionNote", ModuleState.versionNote);
            o.put("probeState", ModuleState.probeState);
            o.put("probeReason", ModuleState.probeReason);

            JSONArray arr = new JSONArray();
            JSONObject inst = new JSONObject();
            inst.put("id", userId);
            inst.put("userId", userId);
            inst.put("name", name);
            inst.put("streak", streak());
            inst.put("signedToday", signedToday());
            inst.put("lastTime", lastTime());
            arr.put(inst);
            o.put("instances", arr);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
