package xiaojw.byd.core;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import xiaojw.byd.sign.BydCrypto;
import xiaojw.byd.sign.BydHttp;

/**
 * 对应 byd_sign 的 send_email / send_webhook / send_notification。
 *
 * 原实现靠内置 curl 发 SMTP 与 HTTP；这里 SMTP 直接走 SSLSocket（仅隐式 SSL，465），
 * Webhook 复用 {@link BydHttp}。多开/分身场景下两个进程各自发通知，
 * 与原 HTTP 端口方案「每个端口各发一份」的行为一致。
 */
public final class Notifier {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Webhook 通知的读超时。
     * 通知发不出去是小事，让它占住收尾阶段几十秒才是真的影响签到 ——
     * 实机日志里就出现过「签到已经失败，又因为 webhook 超时多等了 45 秒」。
     */
    private static final int WEBHOOK_TIMEOUT_MS = 8_000;

    private Notifier() {}

    /**
     * 发通知。
     *
     * 放到独立线程：通知是收尾动作，邮件或 webhook 卡住不应该拖住
     * 「签到结束」的日志和状态回写。各通道自带短超时，最坏也就几秒。
     *
     * @param success 本次结果，用来套用「成功时通知 / 失败时通知」两个开关
     */
    public static void notifyAll(final BydConfig c, final boolean success,
                                 final String title, final String message) {
        if (!c.shouldNotify(success)) {
            LogStore.info((success ? "成功通知" : "失败通知") + "已在设置里关闭，跳过发送");
            return;
        }
        Thread t = new Thread(() -> sendAll(c, title, message), "byd-lsp-notify");
        t.setDaemon(true);
        t.start();
    }

    private static void sendAll(BydConfig c, String title, String message) {
        if (c.mailUsable()) {
            try {
                sendMail(c, title, message);
                LogStore.ok("邮件通知已发送");
            } catch (Exception e) {
                LogStore.warn("邮件通知发送失败: " + e.getMessage());
            }
        } else if (c.mailEnabled) {
            LogStore.warn("邮件通知已开启但配置不完整，跳过");
        }

        if (c.webhookUsable()) {
            try {
                sendWebhook(c, title, message);
                LogStore.ok("Webhook 通知已发送");
            } catch (Exception e) {
                LogStore.warn("Webhook 通知发送失败: " + e.getMessage());
            }
        } else if (c.hookEnabled) {
            LogStore.warn("Webhook 通知已开启但配置不完整，跳过");
        }
    }

    /* ==================== 邮件 ==================== */

    private static void sendMail(BydConfig c, String subject, String body) throws IOException {
        String host = c.smtp;
        int port = 465;
        int idx = c.smtp.lastIndexOf(':');
        if (idx > 0) {
            host = c.smtp.substring(0, idx).trim();
            try {
                port = Integer.parseInt(c.smtp.substring(idx + 1).trim());
            } catch (NumberFormatException ignored) {
                // 端口写错时退回 465
            }
        }

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket sock = (SSLSocket) factory.createSocket()) {
            sock.connect(new InetSocketAddress(host, port), 15_000);
            sock.setSoTimeout(20_000);
            sock.startHandshake();

            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), UTF8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), UTF8));

            expect(in, 220);
            cmd(out, in, "EHLO byd-lsp", 250);
            cmd(out, in, "AUTH LOGIN", 334);
            cmd(out, in, b64(c.mailFrom), 334);
            cmd(out, in, b64(c.mailAuth), 235);
            cmd(out, in, "MAIL FROM:<" + c.mailFrom + ">", 250);
            cmd(out, in, "RCPT TO:<" + c.mailTo + ">", 250);
            cmd(out, in, "DATA", 354);

            // 主题与正文都按 RFC 2047 / base64 传输，避免中文在部分邮箱变成乱码
            String mail = "From: " + c.mailFrom + "\r\n"
                    + "To: " + c.mailTo + "\r\n"
                    + "Subject: " + encodeSubject(subject) + "\r\n"
                    + "MIME-Version: 1.0\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n"
                    + "\r\n"
                    + wrapBase64(body)
                    + "\r\n.";
            out.write(mail);
            out.write("\r\n");
            out.flush();
            expect(in, 250);
            cmd(out, in, "QUIT", 221);
        }
    }

    private static String encodeSubject(String s) throws IOException {
        byte[] raw = s.getBytes(UTF8);
        StringBuilder sb = new StringBuilder();
        // 编码词上限 75 字符，这里按 45 字节源数据分块保证不超
        for (int i = 0; i < raw.length; i += 45) {
            int len = Math.min(45, raw.length - i);
            byte[] chunk = new byte[len];
            System.arraycopy(raw, i, chunk, 0, len);
            if (sb.length() > 0) sb.append("\r\n ");
            sb.append("=?UTF-8?B?").append(Base64.encodeToString(chunk, Base64.NO_WRAP)).append("?=");
        }
        return sb.toString();
    }

    private static String wrapBase64(String s) {
        String b = Base64.encodeToString(s.getBytes(UTF8), Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length(); i += 76) {
            sb.append(b, i, Math.min(i + 76, b.length())).append("\r\n");
        }
        return sb.toString();
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(UTF8), Base64.NO_WRAP);
    }

    /** SMTP 允许 250-xxx 续行，必须读到「空格分隔」的那一行才算结束 */
    private static int readReply(BufferedReader in) throws IOException {
        int code;
        while (true) {
            String line = in.readLine();
            if (line == null) throw new IOException("SMTP 连接被关闭");
            if (line.length() < 3) throw new IOException("SMTP 响应异常: " + line);
            try {
                code = Integer.parseInt(line.substring(0, 3));
            } catch (NumberFormatException e) {
                throw new IOException("SMTP 响应异常: " + line);
            }
            if (line.length() <= 3 || line.charAt(3) != '-') return code;
        }
    }

    private static void expect(BufferedReader in, int want) throws IOException {
        int code = readReply(in);
        if (code != want) throw new IOException("SMTP 期望 " + want + "，收到 " + code);
    }

    private static void cmd(BufferedWriter out, BufferedReader in, String line, int want) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
        expect(in, want);
    }

    /* ==================== Webhook ==================== */

    private static void sendWebhook(BydConfig c, String title, String message) throws Exception {
        String url = c.hookUrl;
        String json;

        switch (c.hookType) {
            case "wechat":
                json = textCard(title, message);
                break;

            case "dingtalk":
                if (!c.hookExtra.isEmpty()) {
                    String ts = String.valueOf(System.currentTimeMillis());
                    String sign = URLEncoder.encode(
                            BydCrypto.hmacSha256Base64(c.hookExtra, ts + "\n" + c.hookExtra), "UTF-8");
                    url = c.hookUrl + (c.hookUrl.contains("?") ? "&" : "?")
                            + "timestamp=" + ts + "&sign=" + sign;
                }
                json = textCard(title, message);
                break;

            case "telegram":
                if (c.hookExtra.isEmpty()) throw new IOException("Telegram 未配置 chat_id");
                json = "{\"chat_id\":\"" + esc(c.hookExtra) + "\","
                        + "\"text\":\"" + esc(title) + "\\n" + esc(message) + "\","
                        + "\"parse_mode\":\"HTML\"}";
                break;

            case "pushplus": {
                if (c.hookExtra.isEmpty()) throw new IOException("PushPlus 未配置 token");
                if (url == null || url.isEmpty()) url = "https://www.pushplus.plus/send";
                StringBuilder sb = new StringBuilder();
                sb.append("{\"token\":\"").append(esc(c.hookExtra)).append("\"")
                  .append(",\"title\":\"").append(esc(title)).append("\"")
                  .append(",\"content\":\"").append(esc(message)).append("\"");
                if (!c.hookChannel.isEmpty()) sb.append(",\"channel\":\"").append(esc(c.hookChannel)).append("\"");
                sb.append("}");
                json = sb.toString();
                break;
            }

            default:
                json = "{\"title\":\"" + esc(title) + "\",\"message\":\"" + esc(message) + "\","
                        + "\"timestamp\":\"" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\"}";
                break;
        }

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        BydHttp.Response r = BydHttp.post(url, json, h, "application/json; charset=utf-8", WEBHOOK_TIMEOUT_MS);
        if (!r.isHttpOk()) throw new IOException("HTTP " + r.code + " " + r.body);
    }

    private static String textCard(String title, String message) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + esc(title) + "\\n" + esc(message) + "\"}}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
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
}
