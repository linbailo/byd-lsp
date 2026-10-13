package xiaojw.byd.sign;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应 byd_sign.sh 的 get_session_id / do_sign / send_request 三段。
 *
 * 流程与脚本逐字对齐：
 *   code → AES( {"code":...} ) → /?service=mina.decryptCode → 解密取 session_id
 *        → AES( {"date":"","belong_brand":...,"session_id":...,"app_version":"460","app_client":"mina"} )
 *        → /?s=ForCommonUcSrv.forward&serviceDir=activity/sign/signIn → 解密取 ret / durationDays
 */
public final class BydSignEngine {

    private static final Pattern P_SESSION = Pattern.compile("\"?session_id\"?\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_RET = Pattern.compile("\"?ret\"?\\s*:\\s*(-?\\d+)");
    private static final Pattern P_DAYS = Pattern.compile("\"?durationDays\"?\\s*:\\s*(\\d+)");
    /**
     * 服务端「今天已经签过了」的提示。
     *
     * 重复签到（关掉「当天成功后不再重复」时会发生）多半返回非 200 的 ret，
     * 正文里却写着已签到 —— 那不算失败，按「今日已完成」处理，
     * 否则用户会收到一封莫名其妙的「签到失败」通知。
     */
    private static final Pattern P_ALREADY = Pattern.compile("已签到|已经签到|重复签到|今日已签");

    /** 业务性失败（解密失败、字段缺失、服务端明确拒绝）：重试没有意义 */
    public static final class BydException extends Exception {
        public BydException(String msg) { super(msg); }
    }

    public static final class Outcome {
        public final boolean ok;
        public final int ret;
        public final int days;
        public final String detail;
        public Outcome(boolean ok, int ret, int days, String detail) {
            this.ok = ok; this.ret = ret; this.days = days; this.detail = detail;
        }
    }

    private BydSignEngine() {}

    /* ---------------- 第一步：code → session_id ---------------- */

    public static String fetchSessionId(String appId, String code)
            throws BydHttp.NetworkException, BydException {
        if (code == null || code.isEmpty()) throw new BydException("code 为空");

        Payload p = prepare(sessionPayload(code), appId);
        BydHttp.Response r = BydHttp.post(BydProto.URL_SESSION, p.body, p.headers, "application/json");
        String plain = decrypt(r, "获取 session_id");

        Matcher m = P_SESSION.matcher(plain);
        if (!m.find() || m.group(1).isEmpty()) {
            throw new BydException("响应中未找到 session_id: " + truncate(plain));
        }
        return m.group(1);
    }

    /* ---------------- 第二步：session_id → 签到 ---------------- */

    public static Outcome signIn(String appId, String brand, String sessionId)
            throws BydHttp.NetworkException, BydException {
        if (sessionId == null || sessionId.isEmpty()) throw new BydException("session_id 为空");

        Payload p = prepare(signPayload(brand, sessionId), appId);
        BydHttp.Response r = BydHttp.post(BydProto.URL_SIGN, p.body, p.headers, "application/json");
        String plain = decrypt(r, "签到");

        Integer ret = null, days = 0;
        try {
            JSONObject o = new JSONObject(plain);
            if (o.has("ret")) ret = o.optInt("ret");
            else if (o.has("code")) ret = o.optInt("code");
            days = o.optInt("durationDays", 0);
        } catch (JSONException ignored) {
            // 服务端偶发返回非严格 JSON，退回正则提取（与原脚本 grep 行为一致）
        }
        if (ret == null) {
            Matcher m = P_RET.matcher(plain);
            if (m.find()) ret = Integer.parseInt(m.group(1));
        }
        if (days == 0) {
            Matcher m = P_DAYS.matcher(plain);
            if (m.find()) days = Integer.parseInt(m.group(1));
        }

        if (ret == null) throw new BydException("响应中未找到 ret: " + truncate(plain));

        // 服务端说今天已经签过了：当作今日已完成，别报失败
        if (ret != 200 && P_ALREADY.matcher(plain).find()) {
            return new Outcome(true, ret, days, plain);
        }
        return new Outcome(ret == 200, ret, days, plain);
    }

    /* ---------------- 内部：请求组装 / 响应解密 ---------------- */

    /**
     * 请求体按脚本原本的字符串拼接方式生成，字段顺序与 byd_sign.sh 完全一致。
     * 用 JSONObject 也能构造，但键顺序不受控，会让密文与脚本产生的不同，
     * 排障时无法与历史日志逐字节对齐。
     */
    static String sessionPayload(String code) {
        return "{\"code\":\"" + jsonEscape(code) + "\"}";
    }

    static String signPayload(String brand, String sessionId) {
        return "{\"date\":\"\""
                + ",\"belong_brand\":\"" + jsonEscape(brand) + "\""
                + ",\"session_id\":\"" + jsonEscape(sessionId) + "\""
                + ",\"app_version\":\"" + BydProto.APP_VERSION + "\""
                + ",\"app_client\":\"" + BydProto.APP_CLIENT + "\"}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static final class Payload {
        final String body;
        final Map<String, String> headers;
        Payload(String body, Map<String, String> headers) { this.body = body; this.headers = headers; }
    }

    private static Payload prepare(String plainPayload, String appId) throws BydException {
        try {
            String nonce = BydCrypto.randomStr(16);
            long curtime = System.currentTimeMillis() / 1000L;
            String checksum = BydCrypto.checksum(nonce, curtime);
            String body = BydCrypto.encryptToBase64(plainPayload);
            String refererAppId = (appId == null || appId.isEmpty()) ? BydProto.defaultAppId() : appId;
            return new Payload(body, headers(refererAppId, nonce, curtime, checksum));
        } catch (Exception e) {
            throw new BydException("本地加密失败: " + e.getMessage());
        }
    }

    private static Map<String, String> headers(String appId, String nonce, long curtime, String checksum) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("X-Clienttraceid", BydCrypto.clientTraceId());
        h.put("Nonce", nonce);
        h.put("User-Agent", BydProto.UA);
        h.put("Curtime", String.valueOf(curtime));
        h.put("Xweb_xhr", "1");
        h.put("Checksum", checksum);
        h.put("Appkey", BydProto.APPKEY);
        h.put("Accept", "*/*");
        h.put("Accept-Encoding", "identity");
        h.put("Sec-Fetch-Site", "cross-site");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Referer", "https://servicewechat.com/" + appId + "/142/page-frame.html");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        h.put("Priority", "u=1, i");
        h.put("Connection", "keep-alive");
        return h;
    }

    private static String decrypt(BydHttp.Response r, String stage) throws BydException {
        if (!r.isHttpOk()) {
            throw new BydException(stage + " 失败：HTTP " + r.code + " " + truncate(r.body));
        }
        try {
            return BydCrypto.decryptFromBase64(r.body);
        } catch (Exception e) {
            // 原脚本同样把这种情况记成「无法解密响应」并把原始响应打进日志
            throw new BydException(stage + " 响应无法解密（原始响应: " + truncate(r.body) + "）");
        }
    }

    static String truncate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }
}
