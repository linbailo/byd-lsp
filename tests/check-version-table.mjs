// 校验内置版本表：每个版本解析出来的 j1/c/a1/a7 与 静态/实例方法名是否符合预期
//
// 这里镜像 WxVersionTable 的解析规则：
//   static  = 版本条目里的 j1_static_method，缺省取 common.j1_static_method
//   instance= 版本条目里的 j1_instance_method，缺省取 common.j1_instance_method
//
// 加这个测试的直接原因：线上出现过 j1_instance_method 被解析成 d（应为 g）的情况。
// 调错方法名既不抛异常、也不会报错，只是登录永不返回 —— 从日志上极难发现，
// 所以把每个版本的正确值钉死在测试里。
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const assetPath = path.join(root, "byd-lsp/app/src/main/assets/wxcode_versions.json");
const table = JSON.parse(fs.readFileSync(assetPath, "utf8"));

const commonStatic = table.common?.j1_static_method;
const commonInstance = table.common?.j1_instance_method;

// 期望值来自 xiaojinwen/wxcode-hook-config（known_configs.json + 单版本文件 + EDITION 常量）
const EXPECT = {
  "8.0.49": { j1: "u70.k1", c: "o60.c", a1: "plugin.appbrand.jsapi.auth.b2", a7: "plugin.appbrand.jsapi.auth.f2", static: "d", instance: "f" },
  "8.0.62": { j1: "of0.j1", c: "he0.c", a1: "plugin.appbrand.jsapi.auth.h2", a7: "plugin.appbrand.jsapi.auth.l2", static: "d", instance: "g" },
  "8.0.68": { j1: "jj0.k1", c: "ei0.c", a1: "plugin.type.jsapi.auth.l2",    a7: "plugin.type.jsapi.auth.m2",    static: "d", instance: "g" },
  "8.0.70": { j1: "yj0.j1", c: "ti0.c", a1: "plugin.appbrand.jsapi.auth.h2", a7: "plugin.appbrand.jsapi.auth.l2", static: "d", instance: "g" },
  "8.0.71": { j1: "tk0.j1", c: "oj0.c", a1: "plugin.appbrand.jsapi.auth.h2", a7: "plugin.appbrand.jsapi.auth.l2", static: "d", instance: "g" },
  "8.0.72": { j1: "dl0.k1", c: "yj0.c", a1: "plugin.appbrand.jsapi.auth.h2", a7: "plugin.appbrand.jsapi.auth.l2", static: "d", instance: "g" },
  "8.0.74": { j1: "gm0.j1", c: "bl0.c", a1: "plugin.appbrand.jsapi.auth.h2", a7: "plugin.appbrand.jsapi.auth.l2", static: "d", instance: "g" },
  "8.0.76": { j1: "hm0.j1", c: "cl0.c", a1: "plugin.appbrand.jsapi.auth.i2", a7: "plugin.appbrand.jsapi.auth.m2", static: "d", instance: "g" },
  "8.0.77": { j1: "ho0.j1", c: "cn0.c", a1: "plugin.appbrand.jsapi.auth.i2", a7: "plugin.appbrand.jsapi.auth.m2", static: "e", instance: "g" },
  "8.0.78": { j1: "gp0.j1", c: "bo0.c", a1: "plugin.appbrand.jsapi.auth.i2", a7: "plugin.appbrand.jsapi.auth.m2", static: "e", instance: "g" }
};

let fail = 0;
const check = (name, got, want) => {
  const ok = got === want;
  if (!ok) fail++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}`);
  if (!ok) console.log(`      got  ${got}\n      want ${want}`);
};

check("common.j1_static_method", commonStatic, "d");
check("common.j1_instance_method", commonInstance, "g");

const names = Object.keys(table.versions).sort((a, b) => {
  const x = a.split(".").map(Number), y = b.split(".").map(Number);
  for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0);
  return 0;
});
check("版本数量", names.length, 10);
check("版本列表", names.join(","), Object.keys(EXPECT).join(","));

for (const [ver, want] of Object.entries(EXPECT)) {
  const e = table.versions[ver];
  if (!e) { fail++; console.log(`FAIL  ${ver} 缺失`); continue; }
  // 镜像 WxVersionTable：条目自带则用条目，否则落回 common
  const resolved = {
    j1: e.j1, c: e.c, a1: e.a1, a7: e.a7,
    static: e.j1_static_method ?? commonStatic,
    instance: e.j1_instance_method ?? commonInstance
  };
  for (const k of ["j1", "c", "a1", "a7", "static", "instance"]) {
    check(`${ver}.${k}`, resolved[k], want[k]);
  }
}

// 顺手确认没有哪个版本把 instance 显式写成 d（历史上出现过的错值）
for (const [ver, e] of Object.entries(table.versions)) {
  if (e.j1_instance_method === "d") { fail++; console.log(`FAIL  ${ver} 的 j1_instance_method 被写成 d`); }
}
// 每个版本都要有来源标注，便于在 App 里核对
for (const [ver, e] of Object.entries(table.versions)) {
  if (!e._src) { fail++; console.log(`FAIL  ${ver} 缺 _src 来源标注`); }
}

// 构建时会把这个 JSON 编译进 WxVersionTableDefault.java（模块侧不能用运行时读 assets）。
// 生成产物存在的话，顺手校验它还原出来和源文件一致 —— 模块侧的内置表就靠它。
const genPath = path.join(root, "byd-lsp/app/build/generated/bydtable/xiaojw/byd/hook/WxVersionTableDefault.java");
if (fs.existsSync(genPath)) {
  const gen = fs.readFileSync(genPath, "utf8");
  const m = gen.match(/static final String JSON = "((?:[^"\\]|\\.)*)";/);
  if (!m) { fail++; console.log("FAIL  生成文件里没找到 JSON 常量"); }
  else {
    const restored = m[1].replace(/\\n/g, "\n").replace(/\\"/g, '"').replace(/\\\\/g, "\\");
    let ok = false;
    try {
      const o = JSON.parse(restored);
      ok = Object.keys(o.versions).length === names.length
        && o.versions["8.0.78"]?.j1 === "gp0.j1"
        && (o.versions["8.0.78"].j1_instance_method ?? o.common.j1_instance_method) === "g";
    } catch (e) { console.log("      还原失败: " + e.message); }
    check("生成的 Java 常量还原后与内置表一致", ok, true);
  }
} else {
  console.log("SKIP  生成常量未找到（先跑一次构建）→ " + path.relative(root, genPath));
}

console.log(fail === 0 ? "\n全部通过 ✅" : `\n${fail} 项失败 ❌`);
process.exit(fail ? 1 : 0);
