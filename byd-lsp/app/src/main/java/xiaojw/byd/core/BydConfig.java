package xiaojw.byd.core;

import org.json.JSONObject;

import xiaojw.byd.sign.BydProto;

/**
 * 模块配置。
 *
 * 原项目把配置拆在 email.conf（shell 键值对）与 code_api.conf 两个文件里，
 * 由 KernelSU 安装脚本逐条 sed 追加字段做「升级兼容」。这里统一成一份 JSON：
 * 控制台 App 用 SharedPreferences 存，通过 {@link Ipc#ACTION_CONFIG} 推给微信进程，
 * 模块侧缓存进微信自己的 SharedPreferences（见 ConfigCache）。
 */
public class BydConfig {

    public boolean enabled = true;      // 自动签到总开关
    public boolean dedupe = true;       // 当日已签到则跳过（手动签到同样受它约束）

    public int retry = 2;               // 网络失败重试次数
    public int interval = 10;           // 重试间隔（秒）
    public int keep = 3;                // 日志保留天数
    /** 取码等待上限（秒）。原 wxcode 用 30s；换码要过网络，太短会把「慢」误判成失败 */
    public int codeTimeout = 30;
    /**
     * 是否自动解析本机 dex 生成版本配置。
     * 默认 **true**：版本表没收录的微信版本会自动分析本机 dex 生成配置。
     * 关闭则完全按版本表走（与原 wxcode 行为一致）。
     */
    public boolean autoProbe = true;

    public String brand = "hy";         // belong_brand
    public String appid = BydProto.defaultAppId();

    public boolean mailEnabled = false;
    public String smtp = "";
    public String mailFrom = "";
    public String mailTo = "";
    public String mailAuth = "";

    public boolean hookEnabled = false;
    public String hookType = "";        // wechat / dingtalk / telegram / pushplus / custom
    public String hookUrl = "";
    /**
     * 按类型复用同一个附加字段，避免为每种 Webhook 都开一个输入框：
     *   dingtalk  → 加签密钥
     *   telegram  → chat_id
     *   pushplus  → token
     */
    public String hookExtra = "";
    /** pushplus 专用：渠道标识，留空走默认渠道 */
    public String hookChannel = "";
    /**
     * 通知策略。两个通道（邮件 / Webhook）共用：
     * 之前是「成功也发、失败也发」，用户没法只收其中一种。
     */
    public boolean notifySuccess = true;
    public boolean notifyFailure = true;
    /**
     * 微信版本适配表的覆盖层（JSON 文本，结构 {"common":{...},"versions":{...}}）。
     * 由控制台写入，也由运行时 dex 探测自动追加新版本。
     * 以嵌套对象形式落在配置 JSON 里，方便在「配置」页直接阅读和编辑。
     */
    public String versionTable = "";

    public static BydConfig defaults() { return new BydConfig(); }

    public static BydConfig fromJson(String json) {
        BydConfig c = new BydConfig();
        if (json == null || json.isEmpty()) return c;
        try {
            JSONObject o = new JSONObject(json);
            c.enabled = o.optBoolean("enabled", c.enabled);
            c.dedupe = o.optBoolean("dedupe", c.dedupe);
            c.retry = o.optInt("retry", c.retry);
            c.interval = o.optInt("interval", c.interval);
            c.keep = o.optInt("keep", c.keep);
            c.codeTimeout = o.optInt("codeTimeout", c.codeTimeout);
            c.autoProbe = o.optBoolean("autoProbe", c.autoProbe);
            c.brand = o.optString("brand", c.brand);
            c.appid = o.optString("appid", c.appid);
            c.mailEnabled = o.optBoolean("mailEnabled", c.mailEnabled);
            c.smtp = o.optString("smtp", c.smtp);
            c.mailFrom = o.optString("mailFrom", c.mailFrom);
            c.mailTo = o.optString("mailTo", c.mailTo);
            c.mailAuth = o.optString("mailAuth", c.mailAuth);
            c.hookEnabled = o.optBoolean("hookEnabled", c.hookEnabled);
            c.hookType = o.optString("hookType", c.hookType);
            c.hookUrl = o.optString("hookUrl", c.hookUrl);
            c.hookExtra = o.optString("hookExtra", c.hookExtra);
            c.hookChannel = o.optString("hookChannel", c.hookChannel);
            c.notifySuccess = o.optBoolean("notifySuccess", c.notifySuccess);
            c.notifyFailure = o.optBoolean("notifyFailure", c.notifyFailure);
            JSONObject vt = o.optJSONObject("versionTable");
            if (vt != null) c.versionTable = vt.toString();
        } catch (Exception ignored) {
            // 配置损坏时退回默认值，避免整个模块不可用
        }
        return c.clamp();
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("dedupe", dedupe);
            o.put("retry", retry);
            o.put("interval", interval);
            o.put("keep", keep);
            o.put("codeTimeout", codeTimeout);
            o.put("autoProbe", autoProbe);
            o.put("brand", brand);
            o.put("appid", appid);
            o.put("mailEnabled", mailEnabled);
            o.put("smtp", smtp);
            o.put("mailFrom", mailFrom);
            o.put("mailTo", mailTo);
            o.put("mailAuth", mailAuth);
            o.put("hookEnabled", hookEnabled);
            o.put("hookType", hookType);
            o.put("hookUrl", hookUrl);
            o.put("hookExtra", hookExtra);
            o.put("hookChannel", hookChannel);
            o.put("notifySuccess", notifySuccess);
            o.put("notifyFailure", notifyFailure);
            if (versionTable != null && !versionTable.isEmpty()) {
                o.put("versionTable", new JSONObject(versionTable));
            }
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 收紧取值范围，防止 UI 或手改配置文件传入极端值把模块拖死 */
    public BydConfig clamp() {
        if (retry < 0) retry = 0;
        if (retry > 5) retry = 5;
        if (interval < 3) interval = 3;
        if (interval > 60) interval = 60;
        if (keep < 1) keep = 1;
        if (keep > 30) keep = 30;
        if (codeTimeout < 5) codeTimeout = 5;
        if (codeTimeout > 120) codeTimeout = 120;
        if (brand == null || brand.isEmpty()) brand = "hy";
        if (appid == null || appid.isEmpty()) appid = BydProto.defaultAppId();
        if (smtp == null) smtp = "";
        if (mailFrom == null) mailFrom = "";
        if (mailTo == null) mailTo = "";
        if (mailAuth == null) mailAuth = "";
        if (hookType == null) hookType = "";
        if (hookUrl == null) hookUrl = "";
        if (hookExtra == null) hookExtra = "";
        if (hookChannel == null) hookChannel = "";
        if (versionTable == null) versionTable = "";
        return this;
    }

    public boolean mailUsable() {
        return mailEnabled && !smtp.isEmpty() && !mailFrom.isEmpty() && !mailTo.isEmpty() && !mailAuth.isEmpty();
    }

    /**
     * Webhook 是否可用。
     *
     * 之前只要求 hookType + hookUrl 非空，有两个漏洞：
     *   ① pushplus 留空 hookUrl 时会被拦下，而它的代码里本来是有默认 URL 的（那段兜底永远走不到）
     *   ② telegram / pushplus 缺 chat_id / token 时照样会发出去，然后在发送阶段抛错记成「发送失败」
     */
    public boolean webhookUsable() {
        if (!hookEnabled || hookType.isEmpty()) return false;
        if (!hookUrl.isEmpty()) return extraOk();
        // pushplus 有官方默认地址，允许留空
        return "pushplus".equals(hookType) && extraOk();
    }

    private boolean extraOk() {
        if ("telegram".equals(hookType) || "pushplus".equals(hookType)) {
            return hookExtra != null && !hookExtra.isEmpty();
        }
        return true;
    }

    /** 这个结果要不要发通知（两个通道共用同一套策略） */
    public boolean shouldNotify(boolean success) {
        return success ? notifySuccess : notifyFailure;
    }
}
