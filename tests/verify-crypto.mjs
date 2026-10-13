// 校验 index.html 内嵌的 AES-128-CBC / SHA-256 实现是否符合标准向量
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const script = html.slice(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"));

// 只取加密内核 + 编码工具（第 1 节），避免 DOM/localStorage 依赖
const start = script.indexOf("const PROTO");
const end = script.indexOf("2. 状态存储");
if (start < 0 || end < 0) { console.error("切片失败"); process.exit(2); }
const kernel = script.slice(start, script.lastIndexOf("/*", end));

const mod = new Function(
  kernel + "\nreturn {aesCbcEncrypt,sha256,b64,utf8,randomStr,clientTraceId,checksum,PROTO};"
);
const K = mod();

const bytes = h => Uint8Array.from(h.replace(/\s+/g, "").match(/../g).map(x => parseInt(x, 16)));
const tohex = b => [...b].map(x => x.toString(16).padStart(2, "0")).join("");

let fail = 0;
const check = (name, got, want) => {
  const ok = got === want;
  if (!ok) fail++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}`);
  if (!ok) console.log(`      got  ${got}\n      want ${want}`);
};

/* ---- AES-128-CBC，NIST SP 800-38A F.2.1 ----
   pt 恰好是整数块时 PKCS#7 会补满一整块，所以密文比明文多 16 字节，
   这里逐块比对 NIST 给出的密文前缀 */
const nKey = bytes("2b7e151628aed2a6abf7158809cf4f3c");
const nIv  = bytes("000102030405060708090a0b0c0d0e0f");
const NIST_CT =
  "7649abac8119b246cee98e9b12e9197d" + "5086cb9b507219ee95db113a917678b2" +
  "73bed6b8e3c1743b7116e69e22229516" + "3ff1caa1681fac09120eca307586e1a7";
const NIST_PT =
  "6bc1bee22e409f96e93d7e117393172a" + "ae2d8a571e03ac9c9eb76fac45af8e51" +
  "30c81c46a35ce411e5fbc1191a0a52ef" + "f69f2445df4f9b17ad2b417be66c3710";

for (const blocks of [1, 2, 3, 4]) {
  const pt = bytes(NIST_PT.slice(0, blocks * 32));
  const ct = K.aesCbcEncrypt(pt, nKey, nIv);
  check(`AES-128-CBC NIST F.2.1 前 ${blocks} 块 ${pt.length}B→${ct.length}B`,
        tohex(ct).slice(0, blocks * 32), NIST_CT.slice(0, blocks * 32));
}

/* ---- PKCS#7 填充规则 ---- */
const k64 = K.utf8("0123456789abcdef".repeat(4));   // 64B 基准，避免 slice 被截断
for (const [len, want] of [[1, 16], [15, 16], [16, 32], [17, 32], [31, 32], [32, 48], [48, 64], [64, 80]]) {
  check(`PKCS#7 填充 ${len}B → ${want}B`,
        K.aesCbcEncrypt(k64.slice(0, len), nKey, nIv).length, want);
}

/* ---- SHA-256 ---- */
check("SHA-256('abc')", K.sha256(K.utf8("abc")),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
check("SHA-256('')", K.sha256(new Uint8Array(0)),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
check("SHA-256(448bit 跨块)", K.sha256(K.utf8("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");

/* ---- 真实业务常量：回归固定密文 ---- */
const key = K.utf8(K.PROTO.KEY), iv = K.utf8(K.PROTO.IV);
check("KEY/IV 均为 16 字节", `${key.length}/${iv.length}`, "16/16");
const realCt = K.aesCbcEncrypt(K.utf8('{"code":"test"}'), key, iv);
check("业务密文 HEX（回归基线）", tohex(realCt), "b1663f626fc18c39f8cacbdab0420f0e");
check("业务密文 Base64（回归基线）", K.b64(realCt), "sWY/Ym/BjDn4ysvasEIPDg==");

/* ---- 协议工具 ---- */
const n = K.randomStr(16);
check("randomStr 长度", n.length, 16);
check("randomStr 字符集", [...n].every(c => "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678".includes(c)), true);
const tid = K.clientTraceId();
const body = tid.replace("mina-", "");
check("clienttraceid 前缀", tid.slice(0, 5), "mina-");
check("clienttraceid 结构 8-4-4-4-12", /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(body), true);
// 原始 hex 第 14 位固定为 '4'（版本位），格式化后因 2 个连字符前移落在下标 16
check("clienttraceid 版本位=h[14]='4'", body[16], "4");
check("clienttraceid 变体位=h[19]|=8", parseInt(body[22], 16) >= 8, true);
check("checksum 长度 64", K.checksum(K.PROTO.APPSECRET, "aaaaaaaaaaaaaaaa", "1700000000").length, 64);

console.log(fail === 0 ? "\n全部通过 ✅" : `\n${fail} 项失败 ❌`);
process.exit(fail === 0 ? 0 : 1);
