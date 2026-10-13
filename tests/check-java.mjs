// 逐字符扫描 Java 源码，正确跳过字符串/字符/注释后再统计括号配对
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".java")) files.push(p);
  }
})(path.join(root, "byd-lsp/app/src/main/java"));

let bad = 0;
for (const f of files.sort()) {
  const src = fs.readFileSync(f, "utf8");
  const stack = [];
  let i = 0, line = 1;
  const errs = [];
  const push = (c, ln) => stack.push({ c, ln });
  const pop = (c, ln) => {
    const t = stack.pop();
    if (!t) errs.push(`第 ${ln} 行 多余的 '${c}'`);
    else if (t.c !== c) errs.push(`第 ${ln} 行 '${c}' 与第 ${t.ln} 行 '${t.c}' 不匹配`);
  };
  while (i < src.length) {
    const c = src[i], n = src[i + 1];
    if (c === "\n") { line++; i++; continue; }
    if (c === "/" && n === "/") { while (i < src.length && src[i] !== "\n") i++; continue; }
    if (c === "/" && n === "*") { i += 2; while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) { if (src[i] === "\n") line++; i++; } i += 2; continue; }
    if (c === '"') {
      i++;
      while (i < src.length && src[i] !== '"') { if (src[i] === "\\") i++; if (src[i] === "\n") { line++; errs.push(`第 ${line} 行 字符串未闭合`); break; } i++; }
      i++; continue;
    }
    if (c === "'") {
      i++;
      while (i < src.length && src[i] !== "'") { if (src[i] === "\\") i++; i++; }
      i++; continue;
    }
    if (c === "{" || c === "(" || c === "[") push(c, line);
    else if (c === "}") pop("{", line);
    else if (c === ")") pop("(", line);
    else if (c === "]") pop("[", line);
    i++;
  }
  for (const t of stack) errs.push(`第 ${t.ln} 行 '${t.c}' 未闭合`);
  const name = path.relative(root, f).replace(/\\/g, "/");
  if (errs.length) { bad++; console.log(`BAD   ${name}`); errs.forEach(e => console.log("        " + e)); }
  else console.log(`OK    ${name}`);
}
console.log(bad === 0 ? "\n全部配对正确 ✅" : `\n${bad} 个文件有问题 ❌`);
process.exit(bad ? 1 : 0);
