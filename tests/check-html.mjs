// 校验 index.html 的标签配对（跳过 script/style 内容与自闭合/空元素）
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const file = path.join(root, "index.html");
const html = fs.readFileSync(file, "utf8");

const VOID = new Set(["area","base","br","col","embed","hr","img","input","link","meta",
                      "param","source","track","wbr","!doctype"]);

// 去掉 script / style 的内容，避免里面的模板字符串干扰
const cleaned = html
  .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, "<script></script>")
  .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, "<style></style>");

const stack = [];
const errors = [];
let count = 0;

for (const m of cleaned.matchAll(/<(\/?)([A-Za-z!][A-Za-z0-9-]*)\b([^>]*)>/g)) {
  const closing = m[1] === "/";
  const name = m[2].toLowerCase();
  const attrs = m[3] || "";
  if (VOID.has(name)) continue;
  if (attrs.trimEnd().endsWith("/")) continue;      // 自闭合写法

  const line = cleaned.slice(0, m.index).split("\n").length;
  if (!closing) {
    stack.push({ name, line });
  } else {
    const top = stack.pop();
    count++;
    if (!top) errors.push(`第 ${line} 行 多余的 </${name}>`);
    else if (top.name !== name) errors.push(`第 ${line} 行 </${name}> 与第 ${top.line} 行 <${top.name}> 不匹配`);
  }
}
for (const t of stack) errors.push(`第 ${t.line} 行 <${t.name}> 未闭合`);

// 顺带检查引用的 id 是否唯一
const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
const dup = ids.filter((v, i) => ids.indexOf(v) !== i);

// 以及内联脚本里 getElementById 引用的 id 是否都存在
const script = html.slice(html.indexOf("<script>"), html.lastIndexOf("</script>"));
const used = [...script.matchAll(/getElementById\("([^"]+)"\)/g)].map(m => m[1]);
const idSet = new Set(ids);
const missing = [...new Set(used)].filter(x => !idSet.has(x) && !x.startsWith("toggle-"));

const rel = path.relative(root, file).replace(/\\/g, "/");
console.log(`${errors.length ? "BAD " : "OK  "} ${rel}  标签 ${count} 对, id ${ids.length} 个`);
errors.forEach(e => console.log("        " + e));
if (dup.length) console.log(`        重复 id: ${[...new Set(dup)].join(", ")}`);
if (missing.length) console.log(`        动态引用了不存在的 id: ${missing.join(", ")}`);

const bad = errors.length + dup.length + missing.length;
console.log(bad === 0 ? "\n结构检查通过 ✅" : `\n${bad} 处问题 ❌`);
process.exit(bad ? 1 : 0);
