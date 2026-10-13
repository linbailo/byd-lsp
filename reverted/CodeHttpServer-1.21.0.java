package xiaojw.byd.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import xiaojw.byd.hook.WxCodeHook;

/**
 * 本机 HTTP 取码接口（默认关闭）。
 *
 * 对应原 wxcode 项目的 {@code LoginHttpServer}：模块在微信进程里起一个 HTTP 服务，
 * 外部（脚本 / 另一台设备转发 / 浏览器）打 {@code GET /login?appId=<小程序AppID>} 就能拿到 code。
 * 我们这条链路本来是「进程内直取」，没有任何端口；这个功能是给「想用别的方式取码」的人留的开关，
 * 所以**默认关闭**，需要时在设置页手动打开。
 *
 * 与原项目的差异（都是有意为之）：
 *   · 只监听 {@code 127.0.0.1}（回环），不对局域网开放 —— code 是能换 session 的凭据，
 *     而同一台手机上的任何应用都能访问回环地址，所以默认关闭、且不广播端口；
 *   · 不依赖 NanoHTTPD，只用 JDK 的 ServerSocket：模块只有几百 KB，不值得再塞一个 HTTP 框架；
 *   · 端口可配置（原项目固定 8088+userId），端口被占用时退回 {@code 端口+userId}（与原项目一致）。
 *
 * 支持的路径：
 *   {@code /}          说明页（把当前端口拼好的取码链接直接显示出来）
 *   {@code /login}     取码，必填 {@code appId}；{@code fmt=json} 返回原项目的 JSON 结构
 *   {@code /ping}      连通性检查
 *   {@code /whoami}    当前实例信息（哪个微信号、微信版本、模块版本、端口）
 */
public final class CodeHttpServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 读请求头的上限，防止异常客户端把内存撑爆 */
    private static final int MAX_HEADER_LINES = 64;
    /** 单条连接的读取超时：卡住的连接不能一直占着线程 */
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    /** 并发处理上限（取码本身在 WxCodeHook 里已被 CAS 串行化，这里只是别让线程无限增长） */
    private static final int MAX_WORKERS = 4;

    private static ServerSocket server;
    private static Thread acceptThread;
    private static volatile boolean running = false;
    private static volatile int boundPort = 0;
    private static volatile int workers = 0;

    private static volatile WxCodeHook hook;
    private static volatile String instanceName = "";
    private static volatile int userId = 0;
    private static volatile String wechatVersion = "";
    private static volatile String moduleVersion = "";

    private CodeHttpServer() {}

    /* ==================== 生命周期 ==================== */

    /**
     * 按当前配置应用一次：开关关了或端口变了就重启服务。
     * 在模块启动、以及收到控制台配置时各调一次。
     */
    public static synchronized void apply(boolean enabled, int port, WxCodeHook h) {
        hook = h;
        instanceName = ModuleState.instanceName();
        userId = ModuleState.userId;
        wechatVersion = ModuleState.versionName;
        moduleVersion = ModuleState.moduleVersion;

        if (!enabled) {
            stop();
            return;
        }
        if (running && boundPort == port) return;
        stop();
        start(port);
    }

    public static boolean isRunning() { return running; }

    public static int boundPort() { return boundPort; }

    private static void start(int wantPort) {
        // 端口被占用时退回「端口 + userId」——与原项目的多开端口方案一致
        int[] tries = wantPort == 8088
                ? new int[]{wantPort, wantPort + Math.max(0, userId)}
                : new int[]{wantPort};
        for (int p : tries) {
            if (p < 1024 || p > 65535) continue;
            try {
                ServerSocket s = new ServerSocket();
                // 只绑回环：不监听任何网卡，局域网访问不到
                s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 8);
                server = s;
                boundPort = p;
                running = true;
                acceptThread = new Thread(CodeHttpServer::acceptLoop, "byd-lsp-http");
                acceptThread.setDaemon(true);
                acceptThread.start();
                LogStore.info("本机 HTTP 取码接口已开启：http://127.0.0.1:" + p
                        + "/login?appId=<小程序AppID>（只监听回环，默认关闭是刻意的）");
                return;
            } catch (Throwable t) {
                if (p == tries[tries.length - 1]) {
                    LogStore.err("HTTP 取码接口启动失败（端口 " + p + "）：" + t.getMessage());
                }
            }
        }
    }

    private static synchronized void stop() {
        if (server != null) {
            try { server.close(); } catch (Throwable ignored) { }
            server = null;
        }
        running = false;
        if (boundPort != 0) {
            LogStore.info("本机 HTTP 取码接口已关闭");
            boundPort = 0;
        }
    }

    private static void acceptLoop() {
        while (running && server != null && !server.isClosed()) {
            Socket sock = null;
            try {
                sock = server.accept();
            } catch (Throwable t) {
                if (running && server != null && !server.isClosed()) {
                    LogStore.warn("HTTP 取码接口 accept 异常: " + t.getMessage());
                }
                continue;
            }
            final Socket s = sock;
            if (workers >= MAX_WORKERS) {
                // 忙不过来就立刻回 503，别把连接堆起来
                writeAndClose(s, 503, "Service Unavailable", "text/plain", "busy\n");
                continue;
            }
            workers++;
            Thread t = new Thread(() -> {
                try {
                    handle(s);
                } catch (Throwable e) {
                    writeAndClose(s, 500, "Internal Error", "text/plain", "err: " + e.getMessage() + "\n");
                } finally {
                    workers--;
                }
            }, "byd-lsp-http-req");
            t.setDaemon(true);
            t.start();
        }
    }

    /* ==================== 请求处理 ==================== */

    private static void handle(Socket s) throws Exception {
        try {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) { s.close(); return; }
            // 请求头读掉即可（上限保护）
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = in.readLine();
                if (line == null || line.isEmpty()) break;
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                writeAndClose(s, 405, "Method Not Allowed", "text/plain", "只支持 GET\n");
                return;
            }

            String path = target;
            Map<String, String> q = new LinkedHashMap<>();
            int qm = target.indexOf('?');
            if (qm >= 0) {
                path = target.substring(0, qm);
                q = parseQuery(target.substring(qm + 1));
            }
            if (path.isEmpty()) path = "/";

            if ("/ping".equals(path)) {
                writeAndClose(s, 200, "OK", "text/plain", "ok\n");
                return;
            }
            if ("/whoami".equals(path)) {
                String json = "{\"instance\":\"" + esc(instanceName) + "\",\"userId\":" + userId
                        + ",\"wechat\":\"" + esc(wechatVersion) + "\",\"module\":\"" + esc(moduleVersion)
                        + "\",\"port\":" + boundPort + "}";
                writeAndClose(s, 200, "OK", "application/json", json);
                return;
            }
            if ("/".equals(path) && !hasAppId(q)) {
                writeAndClose(s, 200, "OK", "text/plain; charset=utf-8", helpText());
                return;
            }
            if ("/".equals(path) || "/login".equals(path) || "/code".equals(path)
                    || "/get_code".equals(path)) {
                login(s, q);
                return;
            }
            writeAndClose(s, 404, "Not Found", "text/plain",
                    "404 —— 可用：/login?appId=xxx 、/ping 、/whoami\n");
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static void login(Socket s, Map<String, String> q) {
        String appId = q.get("appid");
        if (appId == null || appId.isEmpty()) {
            writeAndClose(s, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"err\":-1,\"msg\":\"缺少 appId 参数，示例：/login?appId=wxxxxxxxxxxxxxxxx\"}");
            return;
        }
        WxCodeHook h = hook;
        if (h == null) {
            writeAndClose(s, 503, "Service Unavailable", "application/json; charset=utf-8",
                    "{\"err\":-2,\"msg\":\"模块还没就绪\"}");
            return;
        }
        int timeout = 30;
        try { timeout = Integer.parseInt(q.getOrDefault("timeout", "30")); } catch (Exception ignored) { }
        timeout = Math.max(5, Math.min(timeout, 120));

        LogStore.info("HTTP 取码请求：appId=" + appId + "（上限 " + timeout + "s）");
        long t0 = System.currentTimeMillis();
        try {
            String code = h.getCode(appId, timeout * 1000L);
            long ms = System.currentTimeMillis() - t0;
            if (code == null || code.isEmpty()) {
                LogStore.warn("HTTP 取码失败：返回空 code（" + ms + "ms）");
                writeAndClose(s, 502, "Bad Gateway", "application/json; charset=utf-8",
                        "{\"err\":-3,\"msg\":\"取码返回为空\"}");
                return;
            }
            LogStore.ok("HTTP 已返回 code（长度 " + code.length() + "，" + ms + "ms）");
            if ("json".equalsIgnoreCase(q.get("fmt"))) {
                // 与原项目 LoginHttpServer 的响应结构保持一致，方便已有脚本直接用
                String json = "{\"err\":0,\"msg\":\"success\",\"appId\":\"" + esc(appId)
                        + "\",\"code\":\"" + esc(code)
                        + "\",\"codeLength\":" + code.length() + "}";
                writeAndClose(s, 200, "OK", "application/json; charset=utf-8", json);
            } else {
                writeAndClose(s, 200, "OK", "text/plain; charset=utf-8", code + "\n");
            }
        } catch (Throwable e) {
            long ms = System.currentTimeMillis() - t0;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LogStore.err("HTTP 取码失败：" + msg + "（" + ms + "ms）");
            boolean timeouted = msg != null && (msg.contains("超时") || msg.contains("timeout"));
            writeAndClose(s, timeouted ? 504 : 502, timeouted ? "Gateway Timeout" : "Bad Gateway",
                    "application/json; charset=utf-8",
                    "{\"err\":" + (timeouted ? -210 : -4) + ",\"msg\":\"" + esc(msg) + "\"}");
        }
    }

    /* ==================== 工具 ==================== */

    private static boolean hasAppId(Map<String, String> q) {
        String v = q.get("appid");
        return v != null && !v.isEmpty();
    }

    /** 极简 query 解析：key=value&…，会做一次 URL 解码，键名统一小写 */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq);
            String v = eq < 0 ? "" : kv.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, "UTF-8").toLowerCase(), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                out.put(k.toLowerCase(), v);
            }
        }
        return out;
    }

    private static String helpText() {
        int p = boundPort == 0 ? 8088 : boundPort;
        return "BYD 自动签到 · 本机 HTTP 取码接口\n"
             + "（只在回环地址上监听，仅本机可访问）\n\n"
             + "取码：http://127.0.0.1:" + p + "/login?appId=把小程序AppID填在这里\n"
             + "     加 &fmt=json 返回 {\"err\":0,\"code\":\"…\"} 结构（与原 wxcode 一致）\n"
             + "     加 &timeout=30 指定等待上限（5~120 秒）\n"
             + "检查：http://127.0.0.1:" + p + "/ping\n"
             + "信息：http://127.0.0.1:" + p + "/whoami\n\n"
             + "当前实例：" + instanceName + " / User " + userId
             + " · 微信 " + wechatVersion + " · 模块 v" + moduleVersion + "\n";
    }

    private static void writeAndClose(Socket s, int status, String reason, String ctype, String body) {
        try {
            byte[] bytes = (body == null ? "" : body).getBytes(UTF8);
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + ctype + "\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(UTF8));
            out.write(bytes);
            out.flush();
        } catch (Throwable ignored) {
            // 客户端提前断开是常事
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
