// 用最小 DOM 桩执行 index.html 的 init()，并调用配置页逻辑，捕获运行期异常与缺失元素
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2] || ".";
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const script = html.slice(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"));

/* --- 从真实 HTML 里收集 id 与 data-k，桩只暴露真实存在的元素 --- */
const ids = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]));
const dataKs = [...html.matchAll(/data-k="([^"]+)"/g)].map(m => m[1]);
const missing = [];

function makeEl(id, ds) {
  const cls = new Set();
  return {
    id, dataset: ds || {}, value: "", textContent: "", _html: "",
    style: { setProperty() {}, display: "" },
    classList: {
      add: c => cls.add(c), remove: c => cls.delete(c), contains: c => cls.has(c),
      toggle: (c, f) => { const on = f === undefined ? !cls.has(c) : f; on ? cls.add(c) : cls.delete(c); return on; }
    },
    get innerHTML() { return this._html; },
    set innerHTML(v) { this._html = v; },
    addEventListener() {}, insertAdjacentHTML() {}, select() {}, focus() {},
    scrollTop: 0, scrollHeight: 0, disabled: false, className: ""
  };
}
const registry = {};
for (const id of ids) registry[id] = makeEl(id);

const doc = {
  getElementById(id) { if (!registry[id]) missing.push(id); return registry[id] || null; },
  querySelectorAll(sel) {
    if (sel.includes("data-k")) return dataKs.map(k => makeEl("toggle-" + k, { k }));
    return [];
  },
  addEventListener() {}
};
const store = new Map();
globalThis.document = doc;
globalThis.window = { scrollTo() {} };
globalThis.localStorage = { getItem: k => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, String(v)) };
Object.defineProperty(globalThis, "navigator", {
  value: { clipboard: { writeText: async () => {} } }, writable: true, configurable: true
});
globalThis.setInterval = () => 0;

let fail = 0;
const t = (name, fn) => {
  try { fn(); console.log("PASS  " + name); }
  catch (e) { fail++; console.log("FAIL  " + name + "\n      " + e.message); }
};

/* 暴露内部函数做行为断言。
   注意 CFG / VER 是 let 绑定、会被整体重新赋值，必须用 getter 取当前值，
   直接导出变量只会拿到初始那个对象。 */
new Function(script + `
;init();
globalThis.__api = { init, buildRequest, aesCbcEncrypt, utf8, b64, sha256, esc, slotState,
  loadConfigText, saveConfigText, refreshVersion, renderVersion, VER_FAIL_TEXT,
  detectInjection, pollPong, renderEngine, renderInstances, renderAll,
  runSign, pushCommand, pushToModule, renderBuiltinList, cmpVer, fmtDelta,
  wakeWechatThenLog, nextRunAt, renderSlots, signedTodayAll,
  requestModuleConfig, pollModuleConfig, renderModuleConfig,
  injectText, injectClass, seenText, injectHintHtml,
  maybeCatchUpSign, renderAlarm, renderLogDelivery, dedupeOn, skipRestOfDay,
  setProbeAt: (t) => { probeAt = t; },
  setVerTablePullState: (at, ver) => { verTablePullAt = at; verTablePulledFor = ver; },
  getCFG: () => CFG, getVER: () => VER, getMOD: () => MOD, getENGINE: () => ENGINE,
  DEFAULT_CFG, LOGS, PROTO, INJECT_STEPS };
`)();

const A = globalThis.__api;
const $ = id => registry[id];

t("脚本语法可解析", () => new Function(script));
t("init() 无异常", () => { if (!A.LOGS.length) throw new Error("init 未产生日志"); });
t("未访问不存在的 id", () => {
  const uniq = [...new Set(missing)];
  if (uniq.length) throw new Error("缺失 id: " + uniq.join(", "));
});

/* ---- 引擎 ---- */
t("buildRequest(session) 结构完整", () => {
  const r = A.buildRequest("session", { code: "abc" });
  for (const k of ["nonce", "curtime", "checksum", "plain", "body", "cipherHex", "traceId", "url"])
    if (!r[k]) throw new Error("缺字段 " + k);
  if (r.nonce.length !== 16) throw new Error("nonce 长度 " + r.nonce.length);
  if (r.checksum.length !== 64) throw new Error("checksum 长度 " + r.checksum.length);
  if (r.url !== A.PROTO.BASE + "/?service=mina.decryptCode") throw new Error("url=" + r.url);
});
t("buildRequest(sign) 明文与地址正确", () => {
  const r = A.buildRequest("sign", { date: "", belong_brand: A.getCFG().brand, session_id: "S1", app_version: "460", app_client: "mina" });
  if (!r.plain.includes('"session_id":"S1"')) throw new Error(r.plain);
  if (!r.url.includes("serviceDir=activity/sign/signIn")) throw new Error(r.url);
});
t("请求体是真实 AES 密文（长度符合 PKCS#7）", () => {
  const r = A.buildRequest("session", { code: "code-1234567890" });
  const raw = A.b64(A.aesCbcEncrypt(A.utf8(r.plain), A.utf8(A.PROTO.KEY), A.utf8(A.PROTO.IV)));
  if (r.body !== raw) throw new Error("密文与直接计算不一致");
  if (atob(r.body).length % 16 !== 0) throw new Error("密文不是 16 的整数倍");
});

/* ---- 配置页 ---- */
t("配置页状态常量存在", () => {
  if (!A.VER_FAIL_TEXT.includes("请手动打开配置更新")) throw new Error(A.VER_FAIL_TEXT);
});
t("loadConfigText() 可渲染出 JSON", () => {
  A.loadConfigText();
  const v = $("cfgText").value;
  JSON.parse(v);                                   // 必须是合法 JSON
});
t("saveConfigText() 保存合法配置", () => {
  $("cfgText").value = JSON.stringify({ enabled: false, retry: 4, brand: "hy", versionTable: { versions: { "9.9.9": { j1: "x" } } } });
  A.saveConfigText();
  if (A.getCFG().enabled !== false) throw new Error("enabled 未更新");
  if (A.getCFG().retry !== 4) throw new Error("retry 未更新");
  const saved = JSON.parse(localStorage.getItem("byd_lsp_cfg_v1"));
  if (saved.enabled !== false) throw new Error("未写入 localStorage");
});
t("saveConfigText() 拒绝非法 JSON", () => {
  $("cfgText").value = "{ 这不是 JSON";
  const before = JSON.stringify(A.getCFG());
  A.saveConfigText();
  if (JSON.stringify(A.getCFG()) !== before) throw new Error("非法 JSON 却改动了配置");
  if (!$("cfgSaveHint").textContent.includes("JSON")) throw new Error("未提示格式错误");
});
t("refreshVersion() 演示模式不抛异常", () => {
  A.refreshVersion();
  if (!$("cfgVer").textContent) throw new Error("版本号未渲染");
});
t("renderVersion() 失败态显示红字提示", () => {
  A.renderVersion({ version: "8.0.99", exact: false, state: "failed", reason: "未找到 a1" });
  if ($("cfgVerState").textContent !== "获取失败") throw new Error($("cfgVerState").textContent);
  if ($("engVerWarn").textContent !== A.VER_FAIL_TEXT) throw new Error("首页未显示红字提示");
  if ($("engVerWarn").style.display === "none") throw new Error("红字提示被隐藏");
});
t("renderVersion() 已收录态显示绿色", () => {
  A.renderVersion({ version: "8.0.78", exact: true, state: "ok", note: "已收录" });
  if ($("cfgVerState").textContent !== "已收录") throw new Error($("cfgVerState").textContent);
  if ($("engVerWarn").style.display !== "none") throw new Error("红字提示未隐藏");
});

/* ---- 其它 ---- */
t("slotState 至多一个 isNext", () => {
  const s = A.slotState();
  if (s.length !== 4) throw new Error("槽位数量 " + s.length);
  if (s.filter(x => x.isNext).length > 1) throw new Error("多个 isNext");
});

t("今日全部签到后，下次执行顺延到明天", () => {
  const eng = A.getENGINE();
  const before = eng.instances.slice();
  eng.instances.length = 0;
  eng.instances.push({ id: 0, name: "主应用", signed: true, streak: 1 });
  const n = A.nextRunAt();
  if (n.reason !== "signed") throw new Error("reason=" + n.reason);
  if (!n.tomorrow) throw new Error("应顺延到明天");
  if (n.at.getHours() !== 0 || n.at.getMinutes() !== 1) throw new Error("应为明天 00:01: " + n.at.toTimeString());
  const left = n.at.getTime() - Date.now();
  if (left <= 0 || left > 24 * 3600 * 1000) throw new Error("剩余时间不合理: " + left);
  A.getCFG().enabled = true;                 // 前面的用例保存过 enabled:false
  A.renderSlots();
  const hint = $("nextRunHint").textContent;
  if (hint.indexOf("今日已签到") < 0) throw new Error("提示未说明已签到: " + hint);
  if (hint.indexOf("明天") < 0) throw new Error("提示未指明明天: " + hint);
  eng.instances.length = 0;
  before.forEach(x => eng.instances.push(x));
});

t("只签了部分实例时，今天剩下的定时点仍是下一次", () => {
  const eng = A.getENGINE();
  const before = eng.instances.slice();
  eng.instances.length = 0;
  eng.instances.push({ id: 0, name: "主应用", signed: true, streak: 1 });
  eng.instances.push({ id: 10, name: "分身", signed: false, streak: 0 });
  const n = A.nextRunAt();
  if (n.reason === "signed" || n.tomorrow) throw new Error("部分未签不应顺延到明天：" + n.reason);
  if (n.at.getTime() <= Date.now()) throw new Error("下一次应在未来");
  A.renderSlots();
  const slotHtml = $("slots").innerHTML;
  const cnt = (slotHtml.match(/下一次/g) || []).length;
  if (cnt !== 1) throw new Error("应恰好一个槽位标为下一次，实际 " + cnt);
  eng.instances.length = 0;
  before.forEach(x => eng.instances.push(x));
});

t("下次执行时间严格在未来（倒计时不会卡在 0）", () => {
  for (let i = 0; i < 3; i++) {
    const n = A.nextRunAt();
    const left = n.at.getTime() - Date.now();
    if (left <= 0) throw new Error("下次执行时间不在未来，剩余 " + left + "ms");
    if (left > 24 * 3600 * 1000) throw new Error("下次执行时间超过 24 小时：" + left);
    if (n.at.getMinutes() !== 1 || n.at.getSeconds() !== 0) {
      throw new Error("定时点应为 h:01:00，实际 " + n.at.toTimeString());
    }
  }
});

t("倒计时包含秒，肉眼能看到在走", () => {
  const a = A.fmtDelta(3 * 3600000 + 5 * 60000 + 9000);
  if (a.indexOf("秒") < 0) throw new Error("小时级倒计时没有秒：" + a);
  const b = A.fmtDelta(5 * 60000 + 9000);
  if (b.indexOf("秒") < 0) throw new Error("分钟级倒计时没有秒：" + b);
});
t("esc() 转义 HTML", () => {
  if (A.esc("<img src=x onerror=1>") !== "&lt;img src=x onerror=1&gt;") throw new Error(A.esc("<a>"));
});
t("配置模板含版本表字段", () => {
  if (!("versionTable" in A.DEFAULT_CFG)) throw new Error("DEFAULT_CFG 缺少 versionTable");
});

/* ---- 模块注入握手 ---- */
t("未在 APK 内时判定为演示模式", () => {
  A.detectInjection();
  const m = A.getMOD();
  if (m.insideApp) throw new Error("不应判定为 insideApp");
  if (m.state !== "demo") throw new Error("state=" + m.state);
  if ($("engInject").textContent !== "未在 APK 内") throw new Error($("engInject").textContent);
  if ($("engineTxt").textContent !== "演示模式") throw new Error($("engineTxt").textContent);
});

t("模拟微信进程应答 → 判定为已注入", () => {
  let seenNonce = null;
  globalThis.window.BYDModule = {
    pingModule(n) { seenNonce = n; return true; },
    getState() { return JSON.stringify({ version: "8.0.78", performance: true,
      instances: [{ id: 0, userId: 0, name: "主应用", streak: 7, signedToday: true, lastTime: "07:01" }] }); },
    getLogs() { return "[]"; },
    moduleStatus() {
      return JSON.stringify({
        everSeen: true, lastSeenAgo: 0,
        pong: seenNonce ? { nonce: seenNonce, moduleVersion: "1.1.0", version: "8.0.78",
                            instance: "主应用", userId: 0, pid: 4242, streak: 7, signedToday: true } : null
      });
    }
  };
  A.detectInjection();
  if (A.getMOD().state !== "checking") throw new Error("握手未进入 checking: " + A.getMOD().state);
  A.pollPong();                                   // 手动推进轮询（定时器在测试里是空实现）
  const m = A.getMOD();
  if (m.state !== "injected") throw new Error("state=" + m.state);
  if (!m.insideApp) throw new Error("insideApp 应为 true");
  if ($("engInject").textContent.indexOf("已注入") !== 0) throw new Error($("engInject").textContent);
  if ($("engineTxt").textContent !== "已注入") throw new Error($("engineTxt").textContent);
  if ($("instModVer").textContent !== "v1.1.0") throw new Error($("instModVer").textContent);
  if ($("instProc").textContent.indexOf("4242") < 0) throw new Error($("instProc").textContent);
  if ($("instSeen").textContent !== "刚刚") throw new Error($("instSeen").textContent);
  if (A.getENGINE().instances.length !== 1) throw new Error("握手后未立即拉取实例: " + A.getENGINE().instances.length);
  delete globalThis.window.BYDModule;
});

t("未响应时显示诊断清单（含 4 条步骤）", () => {
  // 直接设定状态，避免依赖定时器
  const m = A.getMOD();
  m.insideApp = true;
  m.pong = null;
  m.state = "noresponse";
  const orig = globalThis.window.BYDModule;
  globalThis.window.BYDModule = { pingModule: () => true, moduleStatus: () => "{}" };
  A.getENGINE().instances.length = 0;      // APK 内模块未应答时 instances 就是空的
  A.renderEngine();
  A.renderInstances();
  globalThis.window.BYDModule = orig;

  if ($("engInject").textContent !== "未响应") throw new Error($("engInject").textContent);
  if ($("engInject").className.indexOf("err") < 0) throw new Error("未标红: " + $("engInject").className);
  if ($("engInjectHint").style.display === "none") throw new Error("首页诊断未展开");
  for (const kw of ["勾选", "作用域", "强行停止", "release"]) {
    if ($("instHint").innerHTML.indexOf(kw) < 0) throw new Error("诊断清单缺关键词: " + kw);
  }
  if (A.INJECT_STEPS.length !== 4) throw new Error("步骤数=" + A.INJECT_STEPS.length);
  const html = $("instList").innerHTML;
  if (html.indexOf("没有检测到微信实例") < 0) throw new Error("实例页未提示未注入");
});

t("seenText() 时间文案", () => {
  const m = A.getMOD();
  m.state = "injected";
  if (A.seenText() !== "刚刚") throw new Error(A.seenText());
  m.state = "noresponse"; m.lastSeenAgo = -1;
  if (A.seenText() !== "从未收到") throw new Error(A.seenText());
  m.lastSeenAgo = 30 * 1000;  if (A.seenText() !== "30 秒前") throw new Error(A.seenText());
  m.lastSeenAgo = 5 * 60000;  if (A.seenText() !== "5 分钟前") throw new Error(A.seenText());
  m.lastSeenAgo = 3 * 3600000; if (A.seenText() !== "3 小时前") throw new Error(A.seenText());
  m.lastSeenAgo = 2 * 86400000; if (A.seenText() !== "2 天前") throw new Error(A.seenText());
});

t("演示模式下的实例列表有明确标注", () => {
  const m = A.getMOD();
  m.insideApp = false;
  m.state = "demo";
  A.getENGINE().instances.push({ id:0, name:"主应用", pkg:"com.tencent.mm", user:"User 0", streak:0, signed:false, last:"—" });
  const orig = globalThis.window.BYDModule;
  delete globalThis.window.BYDModule;
  A.renderInstances();
  globalThis.window.BYDModule = orig;
  if ($("instList").innerHTML.indexOf("演示数据") < 0) throw new Error("未标注演示数据");
  A.getENGINE().instances.length = 0;
});

t("模块已注入时，实例列表为空也能下发签到指令", () => {
  const m = A.getMOD();
  m.insideApp = true;
  m.state = "injected";
  A.getENGINE().instances.length = 0;          // 关键：列表还没刷新出来
  A.getENGINE().running = false;
  // 关掉「先唤醒微信」，否则 runSign 会 await 2 秒，同步断言跑在 pushCommand 之前
  A.getCFG().wakeBeforeCode = false;
  const cmds = [];
  globalThis.window.BYDModule = {
    pingModule: () => true,
    moduleStatus: () => "{}",
    getState: () => "{}",
    getLogs: () => "[]",
    command: (cmd, arg) => { cmds.push(cmd + ":" + arg); }
  };
  A.runSign({ all: true });                     // async 函数在首个 await 前是同步执行的
  globalThis.window.BYDModule = undefined;
  if (!cmds.some(x => x.indexOf("signNow") === 0)) {
    throw new Error("实例列表为空时没有下发 signNow —— 正是「没有可签到实例」那个 bug：" + cmds.join(","));
  }
  A.getENGINE().running = false;                // 先清等待态，避免异常时影响后续用例
  A.getCFG().wakeBeforeCode = true;
  if ($("toast").textContent.indexOf("没有可签到的实例") >= 0) {
    throw new Error("不应提示「没有可签到的实例」");
  }
});

t("模块没应答时不下发指令", () => {
  const m = A.getMOD();
  A.getENGINE().running = false;                // 上一条用例可能留下等待态
  m.insideApp = true;
  m.state = "noresponse";
  const cmds = [];
  globalThis.window.BYDModule = {
    pingModule: () => true,
    moduleStatus: () => "{}",
    getState: () => "{}",
    getLogs: () => "[]",
    command: (cmd, arg) => { cmds.push(cmd + ":" + arg); }
  };
  A.runSign({ all: true });
  globalThis.window.BYDModule = undefined;
  if (cmds.length) throw new Error("模块没应答却下发了指令: " + cmds.join(","));
  // 文案不能写「未注入」：noresponse 多半只是微信进程这一刻没接上话，
  // 写死「未注入」会让人以为模块挂了（这正是实机上被抱怨的误报）
  if ($("toast").textContent.indexOf("没应答") < 0) {
    throw new Error("未提示模块无应答: " + $("toast").textContent);
  }
  if ($("toast").textContent.indexOf("未注入") >= 0) {
    throw new Error("仍在用「未注入」描述无应答: " + $("toast").textContent);
  }
});

/* ============================================================
   第三层：App 侧闹钟 + 「打开 App 顺手补签」
   ============================================================ */

t("没应答时顶栏写「未响应」，不写「未注入」", () => {
  const m = A.getMOD();
  m.insideApp = true; m.state = "noresponse";
  A.renderEngine();
  const txt = $("engineTxt").textContent;
  if (txt !== "未响应") throw new Error("顶栏文案是: " + txt);
  if (A.injectText() !== "未响应") throw new Error("注入状态行文案是: " + A.injectText());
});

t("今天没签成功：补签走 App 进程，不走模块网络", () => {
  const m = A.getMOD();
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: false, instance: "主应用" };
  store.delete("byd_catchup_at_v1");        // 清掉十分钟节流
  const calls = [];
  globalThis.window.BYDModule = {
    appSideSign: () => { calls.push("appSideSign"); return true; },
    command: c => { calls.push("cmd:" + c); }
  };
  A.maybeCatchUpSign();
  globalThis.window.BYDModule = undefined;
  if (calls.join(",") !== "appSideSign") {
    throw new Error("应只调用 App 侧补签，实际: " + calls.join(","));
  }
});

t("App 侧补签不可用时才退回模块通道", () => {
  const m = A.getMOD();
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: false };
  store.delete("byd_catchup_at_v1");
  const calls = [];
  globalThis.window.BYDModule = { command: c => { calls.push("cmd:" + c); } };
  A.maybeCatchUpSign();
  globalThis.window.BYDModule = undefined;
  if (calls.join(",") !== "cmd:signNow") throw new Error("退路没走对: " + calls.join(","));
});

t("今天已经签成功就不再补签", () => {
  const m = A.getMOD();
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: true };
  store.delete("byd_catchup_at_v1");
  const calls = [];
  globalThis.window.BYDModule = {
    appSideSign: () => { calls.push("x"); return true; },
    command: c => { calls.push("cmd:" + c); }
  };
  A.maybeCatchUpSign();
  globalThis.window.BYDModule = undefined;
  if (calls.length) throw new Error("已签到还发起了补签: " + calls.join(","));
});

t("App 侧闹钟状态能渲染出来", () => {
  globalThis.window.BYDModule = {
    getAlarmStatus: () => JSON.stringify({ next: "09-24 00:01（还有 3 小时 2 分）", exact: false, last: "09-23 13:01 签到成功" })
  };
  A.renderAlarm();
  globalThis.window.BYDModule = undefined;
  if ($("alarmNext").textContent.indexOf("00:01") < 0) throw new Error("下一次唤醒没渲染: " + $("alarmNext").textContent);
  if ($("alarmExact").textContent.indexOf("未授权") < 0) throw new Error("精确闹钟状态没渲染: " + $("alarmExact").textContent);
  if ($("alarmLast").textContent.indexOf("签到成功") < 0) throw new Error("上次结果没渲染: " + $("alarmLast").textContent);
});

t("内置版本列表列出全部版本（含本机探测生成的）", () => {
  A.renderVersion({
    version: "8.0.78", exact: true, state: "ok",
    builtin: { versions: {
      "8.0.62": { a1: "plugin.appbrand.jsapi.auth.h2", _src: "known_configs.json" },
      "8.0.68": { a1: "plugin.type.jsapi.auth.l2", _src: "KNOWN_EDITION_CONFIGS.play（Play 版）" },
      "8.0.78": { a1: "plugin.appbrand.jsapi.auth.i2", _src: "hook_config_domestic_8.0.78.json" }
    } },
    effective: { versions: {
      "8.0.62": { a1: "plugin.appbrand.jsapi.auth.h2" },
      "8.0.68": { a1: "plugin.type.jsapi.auth.l2" },
      "8.0.78": { a1: "plugin.appbrand.jsapi.auth.i2" },
      "8.0.99": { a1: "foo.bar.x1" }        // 本机探测生成的
    } }
  });
  const html = $("cfgVersions").innerHTML;
  // 只显示版本号：一行 3 个
  for (const v of ["8.0.62", "8.0.68", "8.0.78", "8.0.99"]) {
    if (html.indexOf(v) < 0) throw new Error("列表缺版本 " + v);
  }
  if ($("cfgVersions").className.indexOf("vergrid") < 0) throw new Error("容器不是三列网格");
  if (html.indexOf("verchip probe") < 0) throw new Error("探测生成的版本没有单独标记");
  if (html.indexOf("verchip cur") < 0) throw new Error("当前微信版本没有高亮");
  // 来源挪到 title 悬浮说明里，不再占一行
  if (html.indexOf("known_configs.json") < 0) throw new Error("悬浮说明里没有来源");
  if (html.indexOf("本机探测生成") < 0) throw new Error("探测生成的版本没有标注来源");
  if ($("cfgVersSummary").textContent.indexOf("内置 3 个") < 0) throw new Error($("cfgVersSummary").textContent);
  if ($("cfgVersSummary").textContent.indexOf("本机探测 1 个") < 0) throw new Error($("cfgVersSummary").textContent);
  if ($("cfgVersSummary").textContent.indexOf("共 4 个") < 0) throw new Error($("cfgVersSummary").textContent);
});

t("探测没回结果时会给出原因，而不是永远停在「探测中」", () => {
  const before = A.getPROBE_AT ? A.getPROBE_AT() : null;
  void before;
  // 模拟：缓存里是 running，但已经等了很久
  globalThis.window.BYDModule = {
    getVersionStatus: () => JSON.stringify({
      version: "8.0.99", exact: false, state: "running", note: "已请求模块探测…",
      builtin: { versions: {} }, effective: { versions: {} }
    }),
    getState: () => "{}", getLogs: () => "[]", moduleStatus: () => "{}"
  };
  A.setProbeAt(Date.now() - 60000);
  A.refreshVersion();
  globalThis.window.BYDModule = undefined;
  const hint = $("cfgVerHint").textContent;
  if ($("cfgVerState").textContent !== "获取失败") throw new Error("状态没转成失败：" + $("cfgVerState").textContent);
  if (hint.indexOf("没收到模块的探测结果") < 0) throw new Error("没给出原因：" + hint);
  A.setProbeAt(0);
});











t("开启开关时签到前会先把微信切到前台", () => {
  const m = A.getMOD();
  A.getENGINE().running = false;
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: false };        // 今天还没签，手动签到应放行
  const calls = [];
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}", getLogs: () => "[]",
    command: (c) => calls.push("cmd:" + c),
    bringWechatToFront: () => { calls.push("wake"); return true; }
  };
  const cfg = A.getCFG();
  const before = cfg.wakeBeforeCode;
  cfg.wakeBeforeCode = true;
  A.runSign({ all: true });
  globalThis.window.BYDModule = undefined;
  cfg.wakeBeforeCode = before;
  A.getENGINE().running = false;
  if (calls.indexOf("wake") < 0) throw new Error("没有先唤醒微信：" + calls.join(","));
});

t("关闭开关时直接签到、不跳转微信", () => {
  const m = A.getMOD();
  A.getENGINE().running = false;
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: false };
  const calls = [];
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}", getLogs: () => "[]",
    command: (c) => calls.push("cmd:" + c),
    bringWechatToFront: () => { calls.push("wake"); return true; }
  };
  const cfg = A.getCFG();
  const before = cfg.wakeBeforeCode;
  cfg.wakeBeforeCode = false;
  A.runSign({ all: true });
  globalThis.window.BYDModule = undefined;
  cfg.wakeBeforeCode = before;
  A.getENGINE().running = false;
  if (calls.indexOf("wake") >= 0) throw new Error("关闭开关却仍然唤醒了微信");
  if (!calls.some(x => x.indexOf("cmd:signNow") === 0)) throw new Error("没有下发签到指令：" + calls.join(","));
});

/* 「当天成功后不再重复」这个开关过去在 App 侧从来没被读取过：
   首页的「已跳过 / 今日已签到 · 不再执行」只由 signedTodayAll() 决定，
   手动「立即签到」也刻意绕过它 —— 于是开关怎么拨都看不出变化。 */
t("dedupe 开：今天已签到则手动签到被拦住", () => {
  const m = A.getMOD();
  A.getENGINE().running = false;
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: true };
  const calls = [];
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}", getLogs: () => "[]",
    command: (c) => calls.push("cmd:" + c)
  };
  const cfg = A.getCFG();
  const before = cfg.dedupe;
  const beforeWake = cfg.wakeBeforeCode;
  cfg.dedupe = true;
  cfg.wakeBeforeCode = false;      // 不唤醒时才同步下发指令，断言才是即时的
  A.runSign({ all: true });
  cfg.dedupe = before;
  cfg.wakeBeforeCode = beforeWake;
  globalThis.window.BYDModule = undefined;
  A.getENGINE().running = false;
  if (calls.length) throw new Error("已签到却仍然下发了指令：" + calls.join(","));
});

t("dedupe 关：今天已签到也照常手动签到", () => {
  const m = A.getMOD();
  A.getENGINE().running = false;
  m.insideApp = true; m.state = "injected";
  m.pong = { signedToday: true };
  const calls = [];
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}", getLogs: () => "[]",
    command: (c) => calls.push("cmd:" + c)
  };
  const cfg = A.getCFG();
  const before = cfg.dedupe;
  const beforeWake = cfg.wakeBeforeCode;
  cfg.dedupe = false;
  cfg.wakeBeforeCode = false;
  A.runSign({ all: true });
  cfg.dedupe = before;
  cfg.wakeBeforeCode = beforeWake;
  globalThis.window.BYDModule = undefined;
  A.getENGINE().running = false;
  if (!calls.some(x => x.indexOf("cmd:signNow") === 0)) throw new Error("关掉去重后应照常签到：" + calls.join(","));
});

t("dedupe 关：首页槽位不再标「已跳过」、倒计时也不顺延到明天", () => {
  const cfg = A.getCFG();
  const before = cfg.dedupe;
  const m = A.getMOD();
  const inst = A.getENGINE();
  const savedInst = inst.instances;
  inst.instances = [{ id:0, name:"主应用", signed:true, streak:3 }];

  cfg.dedupe = true;
  if (!A.signedTodayAll()) throw new Error("signedTodayAll 应反映实例状态");
  const n1 = A.nextRunAt();
  if (!n1.tomorrow) throw new Error("开关开启时今日已签到应顺延到明天");

  cfg.dedupe = false;
  const n2 = A.nextRunAt();
  const st2 = A.slotState().filter(s => s.signed);
  if (st2.length) throw new Error("关掉去重后不应再有「已跳过」的槽位");
  if (n2.tomorrow && n2.reason === "signed") throw new Error("关掉去重后不应按「已签到»顺延");

  cfg.dedupe = before;
  inst.instances = savedInst;
  void m;
});

t("wakeWechatThenLog 无桥时返回 thenable 且不抛异常", () => {
  delete globalThis.window.BYDModule;
  const p = A.wakeWechatThenLog();
  if (!p || typeof p.then !== "function") throw new Error("应返回 Promise");
});



/* 实机上出现过：探测成功生成 j1=pp0.i1/c=ko0.c，紧接着一次配置推送把它冲回 8.0.78。
   原因是本地 CFG.versionTable 是 null，JSON.stringify 把它当成 versionTable:null 发出去，
   两边一起被清空。这两条用例把修法锁住。 */
t("推送配置时会带上原生侧的版本表，而不是本地的 null", () => {
  const saved = [];
  globalThis.window.BYDModule = {
    getVersionStatus: () => JSON.stringify({ override: { "8.0.79": { j1: "pp0.i1", c: "ko0.c" } } }),
    saveConfig: j => saved.push(JSON.parse(j))
  };
  const cfg = A.getCFG();
  const keep = cfg.versionTable;
  cfg.versionTable = null;
  A.pushToModule();
  cfg.versionTable = keep;
  globalThis.window.BYDModule = undefined;
  const last = saved[saved.length - 1];
  if (!last) throw new Error("没有推送配置");
  if (!last.versionTable || !last.versionTable["8.0.79"]) {
    throw new Error("版本表被推成了空：" + JSON.stringify(last.versionTable));
  }
});

t("原生侧也拿不到版本表时不带这个键（而不是送 null）", () => {
  const saved = [];
  globalThis.window.BYDModule = {
    getVersionStatus: () => "{}",
    saveConfig: j => saved.push(JSON.parse(j))
  };
  const cfg = A.getCFG();
  const keep = cfg.versionTable;
  cfg.versionTable = null;
  A.pushToModule();
  cfg.versionTable = keep;
  globalThis.window.BYDModule = undefined;
  const last = saved[saved.length - 1];
  if (last && "versionTable" in last) {
    throw new Error("不该带 versionTable：" + JSON.stringify(last.versionTable));
  }
});

/* 模块探测成功后会主动推版本表，但那个时机是微信刚启动、App 常常不在 ——
   所以控制台要能在「模块有、本机没有」时主动拉一份。 */
t("模块已收录但本机配置里没有该版本 → 主动索取版本表", () => {
  const pulled = [];
  globalThis.window.BYDModule = {
    getVersionStatus: () => JSON.stringify({
      version: "8.0.79", exact: true, state: "ok",
      builtin: { versions: { "8.0.78": { a1: "x" } } },
      override: { versions: { "8.0.62": { a1: "y" } } },   // 有覆盖层，但没有 8.0.79
      effective: { versions: { "8.0.62": { a1: "y" }, "8.0.78": { a1: "x" } } }
    }),
    requestVersionTable: () => { pulled.push("pull"); return true; },
    getState: () => "{}", getLogs: () => "[]", moduleStatus: () => "{}"
  };
  A.setVerTablePullState(0, "");
  A.refreshVersion();
  globalThis.window.BYDModule = undefined;
  if (pulled.length !== 1) throw new Error("没有索取版本表：" + JSON.stringify(pulled));
});

t("本机配置里已经有该版本 → 不再索取", () => {
  const pulled = [];
  globalThis.window.BYDModule = {
    getVersionStatus: () => JSON.stringify({
      version: "8.0.79", exact: true, state: "ok",
      builtin: { versions: { "8.0.78": {} } },
      override: { versions: { "8.0.79": { j1: "pp0.i1" } } },
      effective: { versions: { "8.0.78": {}, "8.0.79": { j1: "pp0.i1" } } }
    }),
    requestVersionTable: () => { pulled.push("pull"); return true; },
    getState: () => "{}", getLogs: () => "[]", moduleStatus: () => "{}"
  };
  A.setVerTablePullState(0, "");
  A.refreshVersion();
  globalThis.window.BYDModule = undefined;
  if (pulled.length) throw new Error("不该再索取：" + JSON.stringify(pulled));
});

t("保存配置不会丢掉 App 独有的键", () => {
  const cfg = A.getCFG();
  cfg.wakeBeforeCode = false;                 // 模拟用户关掉了这个开关
  $("cfgText").value = JSON.stringify({ enabled: true, retry: 3 });   // 编辑器里没写它
  A.saveConfigText();
  if (A.getCFG().wakeBeforeCode !== false) {
    throw new Error("保存后被重置了：" + A.getCFG().wakeBeforeCode);
  }
  cfg.wakeBeforeCode = true;
});

t("模块侧生效配置能渲染出类名与字段映射", () => {
  A.renderModuleConfig({
    version: "8.0.78", edition: "国内版", exact: true, at: Date.now(),
    sourceNote: "版本表命中（国内版）",
    j1: "gp0.j1", c: "bo0.c",
    a1: "plugin.appbrand.jsapi.auth.i2", a7: "plugin.appbrand.jsapi.auth.m2",
    j1StaticMethod: "e", j1InstanceMethod: "g",
    tableVersions: ["8.0.49", "8.0.78"],
    loginTask: { action:"o", appId:"p", s:"s", v:"v", t:"t", u:"u", A:"A", state:"r", code:"q" }
  });
  const body = $("mcBody").textContent;
  for (const kw of ["gp0.j1", "bo0.c", "i2", "m2", "e()", "g(c)", "state=r", "code=q"]) {
    if (body.indexOf(kw) < 0) throw new Error("缺 " + kw + "：\n" + body);
  }
  if ($("mcHint").textContent.indexOf("精确命中") < 0) throw new Error($("mcHint").textContent);
  if ($("mcHint").textContent.indexOf("版本表 2 个版本") < 0) throw new Error($("mcHint").textContent);
});

t("默认不跳转微信", () => {
  if (A.DEFAULT_CFG.wakeBeforeCode !== false) throw new Error("wakeBeforeCode 默认应为 false");
});

t("配置文件页显示文件路径", () => {
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}", getLogs: () => "[]",
    getConfigText: () => '{"enabled":true}',
    getConfigFilePath: () => "/sdcard/Android/data/xiaojw.byd/files/config.json"
  };
  A.loadConfigText();
  const txt = $("cfgFilePath").textContent;
  delete globalThis.window.BYDModule;
  if (txt.indexOf("config.json") < 0) throw new Error("未显示配置文件路径：" + txt);
});

t("探测请求按版本记账，不会每次打开都发", () => {
  const m = A.getMOD();
  m.insideApp = true; m.state = "injected";
  const calls = [];
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}", getLogs: () => "[]",
    getVersionStatus: () => JSON.stringify({ version: "8.9.9", exact: false, state: "idle", versions: [] }),
    probeVersion: () => calls.push("probe")
  };
  const cfg = A.getCFG();
  const before = cfg.autoProbe;
  cfg.autoProbe = true;
  try { localStorage.removeItem("byd_probe_requested_v1"); } catch (e) {}
  A.refreshVersion();
  const first = calls.length;
  A.refreshVersion();
  A.refreshVersion();
  globalThis.window.BYDModule = undefined;
  cfg.autoProbe = before;
  if (first !== 1) throw new Error("首次应请求一次，实际 " + first);
  if (calls.length !== 1) throw new Error("重复打开不该再请求，实际共 " + calls.length + " 次");
});

t("默认自动解析 dex（autoProbe 开启）", () => {
  if (A.DEFAULT_CFG.autoProbe !== true) throw new Error("autoProbe 默认应为 true");
  if (A.DEFAULT_CFG.schema !== 2) throw new Error("配置 schema 应为 2");
});

t("autoProbe 关闭时不自动请求 dex 探测", () => {
  const m = A.getMOD();
  m.insideApp = true; m.state = "injected";
  const calls = [];
  globalThis.window.BYDModule = {
    pingModule: () => true, moduleStatus: () => "{}", getState: () => "{}",
    getLogs: () => "[]",
    getVersionStatus: () => JSON.stringify({ version: "8.0.90", exact: false, state: "idle", versions: [] }),
    probeVersion: () => calls.push("probe")
  };
  const cfg = A.getCFG();
  const before = cfg.autoProbe;
  cfg.autoProbe = false;
  A.refreshVersion();
  globalThis.window.BYDModule = undefined;
  cfg.autoProbe = before;
  if (calls.length) throw new Error("autoProbe 关闭却请求了探测：" + calls.join(","));
  if ($("cfgVerState").textContent !== "未收录") throw new Error($("cfgVerState").textContent);
  if ($("cfgVerHint").textContent.indexOf("版本表") < 0) throw new Error($("cfgVerHint").textContent);
});

t("版本号比较按数字段排序", () => {
  if (A.cmpVer("8.0.9", "8.0.10") >= 0) throw new Error("8.0.9 应小于 8.0.10");
  if (A.cmpVer("8.0.78", "8.0.78") !== 0) throw new Error("相等判断错");
  if (A.cmpVer("8.1.0", "8.0.99") <= 0) throw new Error("8.1.0 应大于 8.0.99");
});

t("注入状态类名映射正确", () => {
  const m = A.getMOD();
  m.insideApp = true;
  m.state = "injected";   if (A.injectClass() !== "ok") throw new Error(A.injectClass());
  m.state = "noresponse"; if (A.injectClass() !== "err") throw new Error(A.injectClass());
  m.state = "checking";   if (A.injectClass() !== "warn") throw new Error(A.injectClass());
  m.insideApp = false;    if (A.injectClass() !== "warn") throw new Error(A.injectClass());
});

console.log(fail === 0 ? "\n全部通过 ✅" : `\n${fail} 项失败 ❌`);
process.exit(fail ? 1 : 0);
