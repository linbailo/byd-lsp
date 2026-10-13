package xiaojw.byd.sign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 对应 byd_sign 的 send_request，但只保留通用 POST 能力。
 *
 * 原实现用模块内置的静态 curl，是为了绕开「cron / WebUI / MT 管理器 PATH 不同
 * 导致 TLS、DNS、压缩行为不一致」。这里改用进程内 HttpURLConnection，
 * 该环境差异自然消失；同时显式声明 identity 编码，避免响应被 gzip/br 压缩后
 * 被当成密文去解密（原注释里记录过这个坑）。
 *
 * 比亚迪那套伪装请求头由 {@link BydSignEngine} 负责组装。
 */
public final class BydHttp {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /**
     * 读超时。原来是 45 秒 —— 实机里自动签到卡住时，一次尝试要占掉 45 秒，
     * 两轮重试加上间隔就是 100 秒，而那个时间段里网络恰恰是不通的。
     * 现在收敛到 20 秒：单次尝试更快失败，耐心交给调度侧的补签重试。
     */
    private static final int READ_TIMEOUT_MS = 20_000;

    /** 网络类异常单独标记为可重试，业务/解密失败则快速失败 */
    public static final class NetworkException extends IOException {
        public NetworkException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static final class Response {
        public final int code;
        public final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
        public boolean isHttpOk() { return code >= 200 && code < 300; }
    }

    private BydHttp() {}

    public static Response post(String url, String body, Map<String, String> headers, String contentType)
            throws NetworkException {
        return post(url, body == null ? new byte[0] : body.getBytes(UTF8), headers, contentType, READ_TIMEOUT_MS);
    }

    /**
     * 指定读超时的版本。
     * 通知类请求（webhook）用它压短超时：通知发不出去是小事，
     * 让它在收尾阶段占住几十秒才是真的影响签到。
     */
    public static Response post(String url, String body, Map<String, String> headers,
                               String contentType, int readTimeoutMs)
            throws NetworkException {
        return post(url, body == null ? new byte[0] : body.getBytes(UTF8), headers, contentType, readTimeoutMs);
    }

    public static Response post(String url, byte[] body, Map<String, String> headers,
                               String contentType, int readTimeoutMs)
            throws NetworkException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Math.max(1_000, readTimeoutMs));
            conn.setInstanceFollowRedirects(false);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, readAll(in));
        } catch (IOException e) {
            throw new NetworkException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 例如 URL 非法、协议不支持
            throw new NetworkException("请求构造失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        }
    }
}
