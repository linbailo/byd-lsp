// 静态检查：所有 "自有类.成员" 形式的引用，成员是否真的声明过
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const srcRoot = path.join(root, "byd-lsp/app/src/main/java");

function strip(src) {
  let out = "", i = 0;
  while (i < src.length) {
    const c = src[i], n = src[i + 1];
    if (c === "/" && n === "/") { while (i < src.length && src[i] !== "\n") i++; continue; }
    if (c === "/" && n === "*") { i += 2; while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) i++; i += 2; continue; }
    if (c === '"') { i++; while (i < src.length && src[i] !== '"') { if (src[i] === "\\") i++; i++; } i++; out += '""'; continue; }
    if (c === "'") { i++; while (i < src.length && src[i] !== "'") { if (src[i] === "\\") i++; i++; } i++; out += "''"; continue; }
    out += c; i++;
  }
  return out;
}

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".java")) files.push(p);
  }
})(srcRoot);

// ---- 收集自有类的成员 ----
const cls = new Map();   // name -> {methods:Set, fields:Set, nested:Set, own:[]}
for (const f of files) {
  const raw = fs.readFileSync(f, "utf8");
  const src = strip(raw);
  const m = src.match(/\b(?:class|interface|enum)\s+(\w+)/);
  if (!m) continue;
  const name = m[1];
  const entry = cls.get(name) || { methods: new Set(), fields: new Set(), nested: new Set(), own: [f] };
  entry.own.push(f);
  for (const mm of src.matchAll(/\b(?:public|private|protected|static|final|abstract|synchronized|native)\b[^;{}()=]*?\b(\w+)\s*\(/g)) {
    entry.methods.add(mm[1]);
  }
  for (const mm of src.matchAll(/\b(?:public|private|protected|static|final)\b[^;={}()]*?\b([A-Za-z_]\w*)\s*=/g)) {
    entry.fields.add(mm[1]);
  }
  for (const mm of src.matchAll(/\b(?:class|interface|enum)\s+(\w+)/g)) entry.nested.add(mm[1]);
  cls.set(name, entry);
}
// 构造器调用也算方法
for (const [n, e] of cls) e.methods.add(n);

// 有继承关系的类，把 Exception/IOException 的常用成员放行
const PASSTHROUGH = new Set(["getMessage", "getClass", "getCause", "getStackTrace", "toString", "equals", "hashCode"]);
const JAVAISH = /^(String|Integer|Long|Boolean|Double|Float|Math|System|Thread|Object|Exception|IllegalStateException|RuntimeException|IOException|Character|Character\$|StringBuilder|java|android|javax|org|Xposed|Build|Context|Intent|Bundle|File|JSON|Pattern|Matcher|Base64|Arrays|Collections|Log|List|Map|Set|Calendar|Locale|TimeUnit|SystemClock|Handler|CountDownLatch|AtomicBoolean|SecureRandom|Cipher|Mac|MessageDigest|SecretKeySpec|IvParameterSpec|SSLSocket|SSLSocketFactory|HttpURLConnection|URL|URLEncoder|InetSocketAddress|Charset|SimpleDateFormat|Date|SharedPreferences|PackageInfo|UserHandle|Process|WebView|WebSettings|Color|Debug|RequiresApi|NonNull|SuppressLint|Override|Deprecated)$/;

let issues = 0;
for (const f of files) {
  const src = strip(fs.readFileSync(f, "utf8"));
  const rel = path.relative(root, f).replace(/\\/g, "/");
  for (const mm of src.matchAll(/\b([A-Z][A-Za-z0-9_]*)\s*\.\s*([A-Za-z_]\w*)/g)) {
    const [, C, member] = mm;
    if (member === "class") continue;          // Foo.class 字面量
    if (!cls.has(C)) continue;                 // 非自有类，跳过
    const e = cls.get(C);
    if (e.nested.has(member)) continue;        // 内嵌类型引用
    if (e.methods.has(member) || e.fields.has(member) || PASSTHROUGH.has(member)) continue;
    const line = src.slice(0, mm.index).split("\n").length;
    console.log(`缺失引用  ${rel}:${line}  ${C}.${member}`);
    issues++;
  }
}
console.log(issues === 0 ? "所有自有类成员引用均可解析 ✅" : `${issues} 处可疑引用 ❌`);
console.log(`\n已索引自有类 ${cls.size} 个: ${[...cls.keys()].sort().join(", ")}`);
