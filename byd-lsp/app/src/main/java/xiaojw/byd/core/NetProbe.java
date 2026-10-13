package xiaojw.byd.core;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 出网前的状态体检。
 *
 * 起因：实机日志里自动签到卡在「TCP + TLS 都能握手、响应的字节一直不回」，
 * 45 秒读超时后重试同样失败。这种情况下光看一句
 * {@code SocketTimeoutException} 无法判断到底是
 *   ① 这个进程当时根本不让联网（Doze / 后台受限 / ROM 省电策略）
 *   ② 还是只有某个域名那条路径不通（IPv6、MTU 黑洞）
 *   ③ 还是手机当时的网络确实很差
 * 所以这里做两件事：给出可读的电源/网络状态，以及对若干目标各做一次纯 TCP 连接，
 * 用「别的域名通不通」把 ① 和 ② 分开。
 *
 * 全部是廉价的本地调用；只有 {@link #probe} 会真的建连，所以它只在失败现场调用一次。
 */
public final class NetProbe {

    /** 每个地址的连接超时。宁可早失败，也不要在这里耗掉几十秒 */
    private static final int ADDRESS_TIMEOUT_MS = 4_000;
    /** 每个域名最多探几个地址，避免双栈 + anycast 拖长诊断 */
    private static final int MAX_ADDRESSES = 3;

    private NetProbe() {}

    /* ==================== 可用性判断 ==================== */

    /**
     * 现在是否值得发请求。
     *
     * 要求同时具备 INTERNET 和 VALIDATED：只有 VALIDATED 才说明系统真的探通了外网。
     * 「连着 WiFi 但 WiFi 没有外网」这种最常见的情况就靠这一条挡掉 ——
     * 否则每次都会白等一个连接超时。
     */
    public static boolean hasUsableNetwork(Context ctx) {
        NetworkCapabilities c = activeCaps(ctx);
        return c != null
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /* ==================== 状态摘要 ==================== */

    /**
     * 一行现场摘要，例如：
     * {@code 网络=WiFi·已校验 · 屏幕灭 · 非Doze · 省电模式开 · 微信免电池优化=否 · 微信后台受限=是}
     *
     * 注意「微信后台受限」是在微信进程里调用的，返回的就是**微信这个应用**的受限状态；
     * 这正是需要知道的：受限 = 系统会拦它的后台流量。
     */
    public static String snapshot(Context ctx) {
        StringBuilder sb = new StringBuilder();

        try {
            ConnectivityManager cm = connectivity(ctx);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities c = (n == null || cm == null) ? null : cm.getNetworkCapabilities(n);
            if (c == null) {
                sb.append("网络=无");
            } else {
                sb.append("网络=").append(transportOf(c));
                sb.append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "·已校验" : "·未校验");
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) sb.append("·无INTERNET");
            }
        } catch (Throwable t) {
            sb.append("网络=读取失败");
        }

        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                sb.append(" · 屏幕").append(pm.isInteractive() ? "亮" : "灭");
                sb.append(pm.isDeviceIdleMode() ? " · Doze中" : " · 非Doze");
                sb.append(pm.isPowerSaveMode() ? " · 省电模式开" : " · 省电模式关");
                sb.append(" · 微信免电池优化=")
                  .append(pm.isIgnoringBatteryOptimizations(Ipc.PKG_WECHAT) ? "是" : "否");
            }
        } catch (Throwable ignored) {
            // 状态读不到不影响签到本身
        }

        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    // isBackgroundRestricted() 说的是「调用它的那个应用」，
                    // 而这段代码两个进程都会跑（微信进程内的模块 / App 进程），
                    // 所以标签要按当前进程来写，别一律叫「微信」
                    sb.append(" · ").append(selfLabel(ctx)).append("后台受限=")
                      .append(am.isBackgroundRestricted() ? "是" : "否");
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }

        return sb.toString();
    }

    /** 当前进程是哪个应用：日志里别把自己和微信搞混 */
    private static String selfLabel(Context ctx) {
        try {
            return Ipc.PKG_WECHAT.equals(ctx.getPackageName()) ? "微信" : "本App";
        } catch (Throwable t) {
            return "本进程";
        }
    }

    /* ==================== 连通性探测 ==================== */

    /**
     * 对每个域名做一次纯 TCP 连接（443，不做 TLS），逐个地址报告结果。
     *
     * 用法是「一个必须通的域名 + 一个对照组」：如果对照组也不通，说明当时是
     * 整个进程出不了网；如果只有目标域名不通，问题就在那条路径上（IPv6 / MTU / 对端）。
     */
    public static String probe(String... hosts) {
        StringBuilder sb = new StringBuilder();
        for (String host : hosts) {
            if (host == null || host.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(host).append(" → ");

            InetAddress[] addrs;
            try {
                addrs = InetAddress.getAllByName(host);
            } catch (Throwable t) {
                sb.append("解析失败(").append(t.getClass().getSimpleName()).append(")");
                continue;
            }
            if (addrs == null || addrs.length == 0) {
                sb.append("无可用地址");
                continue;
            }

            int n = 0;
            for (InetAddress a : addrs) {
                if (n >= MAX_ADDRESSES) break;
                if (n > 0) sb.append(", ");
                n++;
                sb.append(a instanceof Inet6Address ? "v6/" : "v4/").append(a.getHostAddress()).append(" ");
                long t0 = System.currentTimeMillis();
                Socket s = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(a, 443), ADDRESS_TIMEOUT_MS);
                    sb.append("通 ").append(System.currentTimeMillis() - t0).append("ms");
                } catch (Throwable t) {
                    sb.append("不通 ").append(t.getClass().getSimpleName());
                } finally {
                    if (s != null) {
                        try { s.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 从 URL 里取主机名，取不到返回 null */
    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            String s = url.trim();
            int i = s.indexOf("://");
            if (i >= 0) s = s.substring(i + 3);
            int slash = s.indexOf('/');
            if (slash >= 0) s = s.substring(0, slash);
            int at = s.indexOf('@');
            if (at >= 0) s = s.substring(at + 1);
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(0, colon);
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ==================== 内部 ==================== */

    private static ConnectivityManager connectivity(Context ctx) {
        try {
            return (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static NetworkCapabilities activeCaps(Context ctx) {
        try {
            ConnectivityManager cm = connectivity(ctx);
            if (cm == null) return null;
            Network n = cm.getActiveNetwork();
            return n == null ? null : cm.getNetworkCapabilities(n);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String transportOf(NetworkCapabilities c) {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "蜂窝";
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "以太网";
        return "其他";
    }
}
