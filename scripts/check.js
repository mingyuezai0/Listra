#!/usr/bin/env node
/* 听雨 / Listra —— 零依赖自检。`node scripts/check.js` 就能跑，不需要 npm install。
   守的是"改完了不会静默坏"这一类事：
     1) 两个 <script> 块能编译（语法错的单文件，双击打开是一片白，什么提示都没有）
     2) 体积守卫（单文件是设计前提，膨胀到某个程度就该提醒了）
     3) 版本号三处一致（README / mobile/package.json / build.gradle）
     4) mobile/www/index.html 与根 index.html 一致（不然打出来的 APK 是旧版本）
     5) 重复的 id="..."
     6) var(--x) 里的 x 有没有定义（少了它 CSS 会静默失效，颜色回退成继承值）
   有问题就 exit 1，能直接挂到出包脚本前面。 */
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const crypto = require("crypto");

const root = path.join(__dirname, "..");
const problems = [];
const notes = [];
const fail = (msg) => problems.push(msg);
const note = (msg) => notes.push(msg);
const read = (p) => fs.readFileSync(p, "utf8");

/* ---- 1. 语法 ---- */
const html = read(path.join(root, "index.html"));
const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (!scripts.length) fail("index.html 里没找到 <script> 块");
scripts.forEach((src, i) => {
  try { new vm.Script(src, { filename: `index.html<script #${i + 1}>` }); }
  catch (e) { fail(`第 ${i + 1} 个 <script> 编译失败：${e.message}`); }
});
note(`<script> 块 ${scripts.length} 个，合计 ${scripts.reduce((n, s) => n + s.length, 0)} 字符`);

/* ---- 2. 体积 ---- */
const SIZE_LIMIT = 640 * 1024;
const size = Buffer.byteLength(html, "utf8");
note(`index.html ${(size / 1024).toFixed(1)} KB / ${html.split("\n").length} 行`);
if (size > SIZE_LIMIT) fail(`index.html 已经 ${(size / 1024).toFixed(0)} KB，超过 ${SIZE_LIMIT / 1024} KB 的守卫线`);

/* ---- 3. 版本号 ---- */
const versionOf = [
  ["README.md", () => (read(path.join(root, "README.md")).match(/###\s*v(\d+\.\d+\.\d+)/) || [])[1]],
  ["mobile/package.json", () => JSON.parse(read(path.join(root, "mobile/package.json"))).version],
  ["mobile/android/app/build.gradle", () => (read(path.join(root, "mobile/android/app/build.gradle")).match(/versionName\s+"([\d.]+)"/) || [])[1]],
];
const versions = versionOf.map(([name, get]) => {
  try { return [name, get()]; } catch (e) { fail(`读不到 ${name} 的版本号：${e.message}`); return [name, null]; }
});
const uniq = new Set(versions.map(([, v]) => v));
note("版本号：" + versions.map(([n, v]) => `${n}=${v}`).join(" · "));
if (uniq.size > 1 || uniq.has(undefined)) fail("三处版本号不一致（README 更新日志 / mobile/package.json / build.gradle versionName）");

/* ---- 4. www 与根一致 ---- */
const wwwPath = path.join(root, "mobile/www/index.html");
if (fs.existsSync(wwwPath)) {
  const h = (buf) => crypto.createHash("sha256").update(buf).digest("hex");
  if (h(fs.readFileSync(wwwPath)) !== h(Buffer.from(html, "utf8"))) {
    fail("mobile/www/index.html 与根 index.html 不一致 —— 先跑 cd mobile && npm run sync，否则 APK 里是旧版本");
  } else note("mobile/www/index.html 与根一致");
} else note("mobile/www 还没生成（出包前 npm run sync 会补上）");

/* ---- 5. 重复 id ---- */
const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
const dup = ids.filter((v, i) => ids.indexOf(v) !== i);
if (dup.length) fail("重复的 id：" + [...new Set(dup)].join(", "));
else note(`id ${ids.length} 个，无重复`);

/* ---- 6. CSS 变量 ---- */
const css = [...html.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/g)].map(m => m[1]).join("\n");
const defined = new Set([...css.matchAll(/(--[a-z0-9-]+)\s*:/g)].map(m => m[1]));
const used = new Set([...html.matchAll(/var\(\s*(--[a-z0-9-]+)/g)].map(m => m[1]));
// 这几个是**运行时**才有的：--roll/--roll-dur 由 rollCardTags() 写，--px/--py 是拖拽时写的，
// --safe-area-inset-* 由安卓壳 / Electron 那边注入。静态查不到定义是正常的。
const RUNTIME_VARS = new Set(["--roll", "--roll-dur", "--px", "--py",
  "--safe-area-inset-top", "--safe-area-inset-right", "--safe-area-inset-bottom", "--safe-area-inset-left"]);
const missing = [...used].filter(v => !defined.has(v) && !RUNTIME_VARS.has(v) && !v.startsWith("--dsh"));
if (missing.length) fail("用了没定义的 CSS 令牌：" + missing.join(", "));
else note(`CSS 令牌定义 ${defined.size} 个，引用全部有定义`);

/* ---- 汇总 ---- */
for (const n of notes) console.log("· " + n);
if (problems.length) {
  console.error("\n✗ 有 " + problems.length + " 个问题：");
  for (const p of problems) console.error("  - " + p);
  process.exit(1);
}
console.log("\n✓ 全部通过");
