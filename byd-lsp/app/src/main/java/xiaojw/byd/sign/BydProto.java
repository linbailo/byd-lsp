package xiaojw.byd.sign;

/** 接口常量。AppID 与 byd_sign 原本一致地分段持有，运行时拼接。 */
public final class BydProto {

    public static final String KEY       = "3993014457161851";   // AES-128 密钥（16 字节）
    public static final String IV        = "PDVcDRWMrBlLHTqh";   // AES-CBC IV（16 字节）
    public static final String APPKEY    = "hyMinaApi";
    public static final String APPSECRET = "Kfl%BOk6C5PwARw8";
    public static final String BASE      = "https://mina.bydoceanauto.com";

    public static final String APP_VERSION = "460";
    public static final String APP_CLIENT  = "mina";

    public static final String URL_SESSION = BASE + "/?service=mina.decryptCode";
    public static final String URL_SIGN    = BASE + "/?s=ForCommonUcSrv.forward&serviceDir=activity/sign/signIn";

    private static final String[] APPID_PARTS = { "wxf62054", "ec313d6f53" };

    /** 与小程序 WebView 一致的 UA，Referer 依赖它判定调用来源 */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI "
          + "MiniProgramEnv/Windows WindowsWechat/WMPF UnifiedPCWindowsWechat(0xf2541a1b) XWEB/19895";

    private BydProto() {}

    public static String defaultAppId() {
        StringBuilder sb = new StringBuilder();
        for (String p : APPID_PARTS) sb.append(p);
        return sb.toString();
    }
}
