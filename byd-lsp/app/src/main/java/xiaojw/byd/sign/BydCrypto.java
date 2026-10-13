package xiaojw.byd.sign;

import android.util.Base64;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对应 byd_sign 中的 aes_encrypt / aes_decrypt / calculate_checksum /
 * random_str / generate_clienttraceid。
 *
 * 区别：不再调用模块内置的 openssl 二进制，改用 JCE；也无法再因
 * “裁剪版 openssl / curl 行为不一致”导致解密失败。
 */
public final class BydCrypto {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RNG = new SecureRandom();
    private static final String NONCE_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";

    private BydCrypto() {}

    /* ---------------- AES-128-CBC ---------------- */

    private static byte[] crypt(int mode, byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /** 明文 → Base64 密文 */
    public static String encryptToBase64(String plain) throws Exception {
        byte[] ct = crypt(Cipher.ENCRYPT_MODE, plain.getBytes(UTF8),
                BydProto.KEY.getBytes(UTF8), BydProto.IV.getBytes(UTF8));
        return Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    /** Base64 密文 → 明文 */
    public static String decryptFromBase64(String base64) throws Exception {
        byte[] ct = Base64.decode(base64.replaceAll("[^A-Za-z0-9+/=]", ""), Base64.DEFAULT);
        byte[] pt = crypt(Cipher.DECRYPT_MODE, ct,
                BydProto.KEY.getBytes(UTF8), BydProto.IV.getBytes(UTF8));
        return new String(pt, UTF8);
    }

    /* ---------------- 校验与随机 ---------------- */

    /** SHA256(APPSECRET + nonce + curtime) 的小写十六进制 */
    public static String checksum(String nonce, long curtime) {
        return sha256Hex(BydProto.APPSECRET + nonce + curtime);
    }

    public static String sha256Hex(String s) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 非 16 字节的随机 nonce，字符集与原实现保持一致 */
    public static String randomStr(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(NONCE_CHARS.charAt(RNG.nextInt(NONCE_CHARS.length())));
        return sb.toString();
    }

    /** mina-8-4-4-4-12 形式的请求追踪号（非标准 UUID，保持与原实现逐位一致） */
    public static String clientTraceId() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder h = new StringBuilder(32);
        for (byte x : b) h.append(String.format("%02x", x));
        h.setCharAt(14, '4');
        h.setCharAt(19, Character.forDigit((Character.digit(h.charAt(19), 16) | 8), 16));
        String s = h.toString();
        return "mina-" + s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20, 32);
    }

    /** 钉钉机器人加签用 */
    public static String hmacSha256Base64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(UTF8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(data.getBytes(UTF8)), Base64.NO_WRAP);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
