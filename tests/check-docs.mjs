// 文档自检：README 的目录锚点是否都对得上；上传前的文档里有没有漏出本机绝对路径。
//
// 为什么需要它：README 现在有 12 条目录链接，章节一改名锚点就会静默失效
// （GitHub 上点过去只是不跳转，不会报错）；而文档里混进 `D:\...` 这类本机路径，
// 对别人来说就是一段无法复现的说明。
//
// 只扫 README / index.html / tests：build-apk.ps1 / .sh 里那个工具链默认目录是有意的
// （README 里说明了怎么用 -Toolchain / BYD_TOOLCHAIN 覆盖），不算泄漏。
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
let fail = 0;
const bad = (msg) => { fail++; console.log("FAIL  " + msg); };
const ok = (msg) => console.log("OK    " + msg);

/* ---------- 1. 目录锚点 ---------- */
const slug = (t) => t.trim().toLowerCase()
  .replace(/[!-/:-@\[-`{-~、。，：；（）「」…·—]/g, "")
  .replace(/\s+/g, "-");

const readme = fs.readFileSync(path.join(root, "README.md"), "utf8");
const headings = [...readme.matchAll(/^## (.+)$/gm)].map(m => slug(m[1]));
const links = [...readme.matchAll(/\]\(#([^)]+)\)/g)].map(m => m[1]);
const dead = links.filter(l => !headings.includes(l));
if (dead.length) {
  bad("README 目录锚点对不上：" + dead.join(" "));
  console.log("      实际可用：" + headings.join(" "));
} else {
  ok("README 目录锚点 " + links.length + " 条全部有效（章节 " + headings.length + " 个）");
}

/* ---------- 2. 相对链接指向的文件存在 ---------- */
const missing = [];
for (const m of readme.matchAll(/\]\((?!https?:|#)([^)]+)\)/g)) {
  const target = m[1].split("#")[0];
  if (!target) continue;
  if (!fs.existsSync(path.join(root, target))) missing.push(target);
}
if (missing.length) bad("README 里指向的文件不存在：" + [...new Set(missing)].join(" "));
else ok("README 里的相对链接都指向真实文件");

/* ---------- 3. 本机绝对路径 ---------- */
const docs = ["README.md", "CHANGELOG.md", "index.html"];
const hits = [];
for (const f of docs) {
  const p = path.join(root, f);
  if (!fs.existsSync(p)) continue;
  const text = fs.readFileSync(p, "utf8");
  for (const m of text.matchAll(/[A-Za-z]:\\{1,2}[^\s)`"'|]+/g)) hits.push(f + ": " + m[0]);
}
if (hits.length) bad("文档里出现本机绝对路径：" + [...new Set(hits)].join(" ｜ "));
else ok("文档里没有本机绝对路径");

console.log(fail ? "\n" + fail + " 项失败 ❌" : "\n文档检查通过 ✅");
process.exit(fail ? 1 : 0);
