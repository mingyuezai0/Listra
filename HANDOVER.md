# 听雨 / Listra —— 交接文档与优化建议

> 由代码通读生成（v0.1.8，commit `55f7d56`）。本文件不是给用户看的，是给下一个改这份代码的人（也就是你）看的。
> 文中的行号都按**编辑器里的行号**（`index.html` 共 11156 行），会随改动漂移，按内容搜更稳。
> 想做外部发布的话把这份挪进 `docs/` 或加进 `.gitignore`。

---

## 零、v0.1.9 处理情况（2026-11-15）

下面这份清单里的项目**已经全部落地**，v0.1.9 一次提交里改完并出了三端产物。改动要点：

- P0 全部 11 条：`renderMain()` 拆出 `paintMain()` 并补 `placeTopActions()`；`closeTopLayer()` 补齐四层浮层并删掉走不到的 `case "Escape"`；媒体元素补 `error` 通道（`mediaError()`，连坏三首就停）；`addFiles` 批内 `stableId` 去重；台词编辑记 `lyricEditTarget`；`lyricCache` 当场作废；帧预览三重兜底；细滚动条脱离 `@supports`；软件锁改 `pendingMode`（取消不再写坏存档）。
- P1 全部：`saveLibrary()` 改 200ms 合并 + `writeLibrary()`，设置走 `saveSettings()`，进度走 `meta:positions`；开屏批量读（`DB.batch`）；`rollCardTags` 两段式；搜索缓存 + 防抖；波形 8kHz 解码上下文 + 尺寸缓存 + 两趟着色；`rekeyItem` 补历史/lastId/进度；`forgetItem` 式清理（peaks + 封面 URL）；`syncNowPlaying` 取代换曲时的整页重画；片段 B 点按帧判 + `clipList` 缓存。
- P2 全部（含 Java）：状态一致性（`markTempDone` 挪到真开播、A/B 不跨曲、事件认 `currentTarget`、`done` 随 ids 收缩、history 按天封顶、音量落盘、ID3 v2.2/扩展头、`emptyHTML` 参数约定、封面缩图、`qRowHTML` 删除）；无障碍（弹窗 `role/aria-modal/inert` + 焦点归还、音量滑杆 `focus-within`、标签下拉键盘可达、搜索下拉 `listbox` + `activedescendant`、卡片 roving tabindex + `listitem`、设置页签方向键、标记载荷行不再劫持按钮）；CSS（补 `--ink-1` / `--mint-400`、去常驻 `will-change`、`--chrome-plate`、`--on-mint` 对比度）；Java（`MediaService.post()` 兜底 + `onTaskRemoved`、`MediaPlugin.handleOnDestroy` 发 A_OFF、`DirPlugin` 后台线程 + 2 万条上限、`LyricService` 夹边界 + 转屏重算、`SharePlugin` 按 TTL 清理）；工程（版本号三处一致 + `scripts/check.js`）。

**这一轮刻意没做的**（都不影响使用，写在这儿免得下次重复发现）：

1. `measureFrame()` 的 `requestVideoFrameCallback` 链仍然随播放一直续订（一帧一次回调，成本极低）；VFR 片源的帧长取的是最后两个样本，换成中位数更稳。
2. 随机播放下「上一首」仍是"再抽一张"的语义（按钮行为，想改成真·上一首得再存一个已播栈）。
3. MP4 的内嵌封面/歌词只在 `moov` 落在前 1MB 时读得到（README 已明说）。
4. ID3 的 `0x80`（非同步）标志没处理 —— 绝大多数写入器不用它。

另外：`scripts/check.js` 现在是出包前的守门人，跑一次就知道版本号、www 漂移、重复 id、未定义令牌有没有出问题。

---

## 一、三十秒版本

一个**单文件**的本地音视频播放器：`index.html`（约 508 KB / 11156 行）里塞下全部 CSS、HTML、JS，零依赖、零构建、离线可用。数据全在本机 IndexedDB（库名 `tingyu`），文件本体也真存进去。同一份 `index.html` 被三个外壳复用：

| 外壳 | 位置 | 怎么出包 |
| --- | --- | --- |
| 网页版 | 仓库根 `index.html` | 双击打开，无产物 |
| Electron 桌面版 | `D:\electron-tingyu`（不随仓库） | `npm run dist` → Setup / Portable 两个 exe |
| Capacitor 安卓版 | `mobile/` | 双击 `D:\Capacitor\build-apk-listra.bat` → debug APK |

**设计的硬约束**（改之前先想清楚要不要破）：单文件、无构建、无第三方运行时依赖、离线、绝不改用户磁盘上的原件。仓库里唯一的构建动作是"把根目录的 `index.html` 拷进 `mobile/www/`"。

---

## 二、代码地图

### 2.1 `index.html` 内部分段

| 行号 | 内容 | 备注 |
| --- | --- | --- |
| 9–30 | `<head>` 里抢先跑的主题脚本 | 只为第一帧不闪白，普通 `<script>`，不能改 defer/module |
| 31–2002 | CSS | 设计令牌 + 三套主题（`data-theme` / `data-lights`）+ 各面板 |
| 2003–2888 | HTML 骨架 | 开屏、锁屏、顶栏、侧栏、播放台、11 个弹窗、教程页 |
| 2889–3060 | 0. 工具、1. IndexedDB 封装 | `DB.get/set/del`，每个操作一个事务 |
| 3059–3323 | 2. 标签解析 | ID3v2 / Vorbis / MP4 `moov`，只读前若干 KB |
| 3324–3551 | 3. 状态模型 | `store` / 收藏夹 / 待播 / 两种顺序（存放 vs 播放） |
| 3552–4235 | 4. 导入、去重、元数据补全、**持久化** | `addFiles`→`hydrateMetas`→`saveLibrary`/`restoreLibrary` |
| 4236–4417 | 4c. 个人资料、播放历史、目录扫描 | 历史按天分桶 |
| 4418–4988 | 5. 渲染（侧栏 / 卡片 / 列表 / 路由） | 全部 `innerHTML` 模板 + 事件委托 |
| 4989–5680 | 6. 播放核心 | `load` / `advance` / 片段循环 / 逐帧 / 统计 |
| 5681–6160 | 6b–6d. 播放设置面板、标记面板 | |
| 6157–6490 | 6f–6h. 进度保存、波形、帧预览、音量 | |
| 6491–6800 | 6i. 歌词、唱片页、悬浮歌词 | 悬浮歌词走原生 / Electron 桥 |
| 6900–7270 | 6m–6q. 全屏、PiP、Media Session、原生桥 | `Cap*` 一律在 16.5 节才求值（避开 TDZ） |
| 7270–7900 | 8–10. 收藏夹 / 待播 / 编辑详情 / 移除 / 设置项 | |
| 7900–8420 | 10b. 软件锁（密码 / 九宫格 / 等待期） | |
| 8420–11020 | 11–15. 挑选器、随机待播、菜单、事件接线 | |
| 10554–10611 | `closeTopLayer()` + 全局键盘 | Esc 与安卓返回键的**唯一总收口** |
| 11020–11156 | 启动：`restoreLibrary` → 摆回上次那条 → 老数据搬家 | |

### 2.2 数据模型（IndexedDB `tingyu`，版本 2）

| store | 键 | 值 |
| --- | --- | --- |
| `meta` | `library` | **全库投影数组**（每条 20+ 字段，含 `lyrics`/`marks`/`clips`/`pos`） |
| `meta` | `collections` / `queues` / `activeQueue` / `playlist` | 列与播放列表 |
| `meta` | `settings` / `profile` / `history` / `dirHandle` | 设置、资料、按天历史、目录句柄 |
| `files` | 条目 id | 文件本体 Blob（电脑端不存，只记 `path`） |
| `files` | `frameKey(id)` | 封面 / 视频帧 JPEG |
| `peaks` | 条目 id | 720 个波形采样 |

条目 id 是 `stableId = name|size|lastModified`——**跨会话稳定**，收藏夹/待播因此存得住；代价是同名同大小同 mtime 的不同文件会撞 id（见 P0-6）。

### 2.3 安卓原生层（`mobile/android/.../listra/`）

| 文件 | 职责 |
| --- | --- |
| `MainActivity` | Capacitor 宿主，注册下面这些插件 |
| `MediaPlugin` + `MediaService` | 通知栏 / 锁屏播放控制（前台服务 + MediaSession） |
| `LyricPlugin` + `LyricService` | 悬浮歌词（`SYSTEM_ALERT_WINDOW` 覆盖窗 + `specialUse` 前台服务） |
| `DirPlugin` | SAF 选文件夹并递归列文件 |
| `SharePlugin` | 系统分享面板 / 临时副本清理 |
| `PipPlugin` | 系统级画中画 |
| `TintPlugin` / `AppInfoPlugin` | 状态栏配色、跳「应用信息」页 |

JS 侧全部通过 `window.Capacitor.Plugins.*` 裸对象调用（**不能用** `registerPlugin`，见 10620 的注释）。

---

## 三、必须保留的隐性契约（改代码前先读这段）

这些是作者踩过坑写下来的、但**只写在注释里**的规则，破坏它们往往不会报错，只会静默坏：

1. **顺序就是依赖。** 主脚本是一个大 IIFE 式的顺序执行体，`paintTheme()` 在 2991 行就跑，早于 `wave` / `canvas`（6225）和 `Cap*`（16.5 节）。所以：首屏路径上不能碰后声明的 `const`/`let`（TDZ 会直接抛）。
2. **主题写两次。** `<head>` 抢先脚本（20–29）和 `paintTheme()`（2971）必须写同一套属性，改一处必须改两处。
3. **`m.tags` 是"从文件里解析出来的"，`m.artist` / `m.labels` / `m.renamed` / `m.lyrics` 是"用户覆盖字段"。** `hydrateMetas()` 每次挂回文件都会重写 `m.tags`，所以用户数据绝不能写进 `m.tags`。
4. **顶栏三颗按钮认元素不认 id。** `renderMain()` 会重写 `#viewMain` 的 innerHTML，把 `.view-head` 连同 `TA_ALL` 一起摘出文档；所以每次渲染后都要 `placeTopActions()` 重新认领（现状只做了一半，见 P0-1）。
5. **`saveLibrary()` 是全量重写 9 个键**，没有增量、没有锁、没有失败上报。任何"改一个字段"的地方调它，代价都是整库克隆 + 9 个事务（见 P1-1）。
6. **`mergeById()` 不许换对象引用**——恢复是异步的，期间用户可能已经在新建收藏夹了。
7. **浮层关闭要举 `ateClick()` 旗子**，否则点外面那一下会穿透到底下的卡片。
8. **`stableId` 是唯一身份**：改名、换 id 必须走 `rekeyItem()`，所有引用（collections / queues / done / history / lastId）都要跟着改（现状漏了两处，见 P1-6）。

---

## 四、优化建议

按"用户能不能撞上"排序。每条给出：位置 → 现象 → 改法。`⌛` 是估的工时（小 ≤ 半小时，中 ≤ 半天）。

### P0 · 会真的坏

**1. 桌面端在资料库里一按搜索键，「添加文件 / 从资料库添加 / 播放列表」三颗按钮当场消失**
`renderMain()` 每次都重写 `#viewMain` 的 innerHTML（4773），而把三颗按钮搬回去的 `placeTopActions()` 只挂在 `syncTopActions()`（4865）和 `resize`（9308）上。搜索（9783、9803）、选筛子（9872）、切布局（7735）、榜单切档（4755/4758）、勾选（9555、9702）都是**只调 `renderMain()`**，于是装着按钮的 `viewActions` 整个变成游离节点，直到下次 `renderAll()` 才回来。`4830` 的注释其实已经写明这个契约，只是没落到代码上。
→ 在 `renderMain()` 的每个 `return` 前（或包一层 `renderMain` 后在末尾）调一次 `placeTopActions()`。最省事的写法：把 4689 的 `function renderMain()` 改名 `paintMain()`，再写
`function renderMain(){ paintMain(); placeTopActions(); }`。⌛小

**2. `closeTopLayer()` 漏了 4 层：`randModal`、`nameModal`、`addMenu`（更多操作）、`navMenu`（侧栏 ⋯）**
10557–10578 的链上都没有它们。后果：安卓返回键在这些浮层上开的时候不收它们，直接走"回上一页 / 要退出听雨吗"；`addMenu`/`navMenu` 连 Esc 也没人收（10608 那句 `case "Escape"` 永远走不到，因为 10594 已经 `return` 了）。`randModal` / `nameModal` 各自挂了局部 keydown（8561、7280），所以看起来"能用"，只有返回键不行。
→ 按层级把 `closeAddMenu / closeNavMenu / closeRand / closeName` 补进 `closeTopLayer()`（放在 `searchDrop` 那一批之后），并删掉 10608–10609 的死代码。顺手把 10591–10596 那份手写名单换成 `[...document.querySelectorAll('.modal')].some(m => !m.hidden)`。⌛小

**3. 媒体元素一个 `error` 监听都没有：坏文件 / 文件被移走 = 整列播放静默卡死**
两个元素的监听只有 play/pause/ended/timeupdate/loadedmetadata（5313–5317），`play()` 的拒绝一路 `.catch(()=>{})`（5082、5119、5202）。MKV/APE/DSD 这类 `<audio>` 解不了的、Blob 损坏的、索引条目原文件被删的——`ended` 永不触发 → `advance()` 不跑 → 自动接续、循环、定量全部停摆，界面上既不报错也不前进。
→ 给 `audioEl` / `videoEl` 各挂一个 `error` 监听：读 `el.error.code` 给出人话提示，按设置决定是否跳下一首，并加"连续失败 N 次就停"的计数，避免整列坏文件时空转。5082 的 catch 至少区分 `NotAllowedError`（自动播放被拦）。⌛小

**4. 安卓：`MediaService.post()` 有可能永远不调 `startForeground`，系统 5 秒后崩进程**
`post()` 里 `Notification n = build(); if (n == null) return;`（MediaService:234–235），而 `build()` 在 `session == null` 或抛异常时返回 null（255–278）。此时服务已经被 `startForegroundService` 拉起（MediaPlugin:77），5 秒内没 `startForeground` → `ForegroundServiceDidNotStartInTimeException` 直接崩。外层 `catch (Exception)` 又把线索吞了。
→ `n == null` 或 `startForeground` 抛异常时立刻 `stopSelf()` + `stopForeground(STOP_FOREGROUND_REMOVE)`，并 reject 回页面（"通知栏播放控制没能开启"），绝不能让服务带着"欠一次 startForeground"活着。⌛小

**5. 安卓：选文件夹在主线程递归扫整棵 SAF 树，大曲库直接 ANR**
`@ActivityCallback picked()`（DirPlugin:91）里同步调 `collect()`（113），后者递归 `cr.query` 每个目录（126–150），每个文件建一个 JSObject。挑一张塞了几千首的 SD 卡，主线程会卡几秒到十几秒。
→ 把 `collect` 丢到单线程 `Executor`，遍历完 `ui.post` 再 `call.resolve`；顺便加文件数上限与分批。⌛中

**6. 同一批导入里出现两个相同 `stableId`，会真的入库两条同 id 记录**
`addFiles()` 逐条查重只看 `store.byId`（3810），而本批条目要到 3866 才 `byId.set`——批内互相看不见。同一首歌的两个拷贝、或干脆把同一个文件拖两次，就会进两条记录、`byId` 只指后一条；此后删掉其中一条（7704、7693）会让另一条从所有列表里连带失联。
→ 循环里加 `const seen = new Set()`，`if (seen.has(id)){ dupes++; continue; } seen.add(id)`；`findDupes()` 的候选表也把本批已收的算进去。⌛小

**7. 歌词编辑没记住"在编辑哪一首"，切歌后保存会写进别的歌**
`openLyricEdit()` 只置 `lyricEditing = true`（6729），`saveLyricEdit()` 到 6743 才 `const m = player.current` 取目标。编辑中歌播完自动下一首、或点了播放台下一首（唱片页 z-index 160，播放台仍可点），保存就把旧歌的歌词覆盖到新歌上，**无法撤销**。
→ `openLyricEdit` 里记下 `lyricEditTarget = m`，保存写回它；若 `player.current !== 目标`，提示"已换曲，保存到哪一首？"。`load()` 换歌时若在编辑中，先确认再清。⌛小

**8. 软件锁：换解锁方式后取消弹窗，会把"新方式 + 旧摘要"写盘，之后永久解不开**
`setLockMode()` 在确认回调里先改内存 `lock.mode = next`（8373），`closeLockSet()`（8296）不还原；随后任何一次 `lockSave()`（比如旁边改一下等待时长，8390）就把新 mode 配旧 hash 写进 localStorage。之后解锁永远失败，只剩"等 `waitH` 小时自动关"这一条路（默认 24 小时）。
→ 引入 `pendingMode`：确认换方式只改 pending，`commitLock` 成功后才写 `lock.mode`；`closeLockSet` 清空 pending。⌛小

**9. 悬浮歌词的 `lyricCache` 永不过期：改完台词，浮窗还在念旧词**
缓存键只有 `id + 时长`（6512），全文件再没有第二处给它赋值（6507/6513/6534）。`saveLyricEdit()` 把唱片页刷新了，浮窗仍推旧词，用户会以为没保存。
→ `saveLyricEdit()` 里补 `lyricCache = { key:" ", lines:[] }; lyricSentKey = " "; syncLyricFloat(true)`；或把歌词长度/首行并进缓存键。⌛小

**10. 帧预览的 seek 状态没有兜底：一次 `seeked` 回不来，预览整场失效**
`peekBusy` 只有 `seeked` 一个复位点（6373–6387）。换到内核解不了的片子、或 src 还没 `loadedmetadata` 就 seek，预览从此只剩一个停住的旧画面，直到换片。
→ 给 `peekEl` 挂 `error`/`stalled`/`loadeddata` 统一复位 + 400 ms `setTimeout` 兜底。⌛小

**11. 细滚动条在 Chromium 上整条失效（第 22 批第 3 项等于没生效）**
203 行 `*:not(textarea){scrollbar-width:none}` 让 Chromium 121+ 彻底忽略 `::-webkit-scrollbar`，而 1357–1358 的 `thin` 写在 `@supports not selector(::-webkit-scrollbar)` 里——Chromium 里恒为假。于是设置页、从资料库添加、播放列表三处一根滚动条都画不出来。
→ 把 `scrollbar-width:thin; scrollbar-color:var(--mint-300) transparent` 移出 `@supports`，作用域限定到 `.set-scroll,.pick-list,#plPanel`。⌛小

### P1 · 性能：现在能用，但库一大就钝

**1. `saveLibrary()` 是唯一落盘通道：全量投影 + 9 个事务，而播放时每 5 秒跑一次**
`savePos()` 每 5 秒调 `saveLibrary()`（6186），换一首歌打 3 次（5174、5022、5052、5360），每个设置开关也打一次（7735…7875，共 9 处）。每次都是 `items` 全数组克隆（含 `lyrics`/`marks`/`clips`）+ 9 个独立事务。2000 条库、带歌词时单次就是 MB 级写。
→ ① `pos` / `plays` / `played` 单独存一个扁平小键（`meta:positions`），别再拖整库；② `saveLibrary` 加"脏标记 + 单飞 + 200 ms 合并"；③ 设置类改走只写 `meta:settings` 的 `saveSettings()`。⌛中

**2. `restoreLibrary()` 用 2N 个串行事务接回本体和封面**
4124–4130 每条一次 `DB.get("files", id)`，4136–4140 再每条一次封面。1000 条库 = 2000 次事务往返，开屏后封面要慢慢"冒"好几秒。
→ 两趟各合成一个只读事务，在里面把 N 个 `get` 一次性发出去（`files` 里混着大 Blob，别用 `getAll`）。⌛小

**3. 每次重画都是"整页 innerHTML + 逐卡强制布局"**
`renderMain()` 重建整个 `#viewMain`（4690/4773），`rollCardTags()` 由 MutationObserver 触发（5910）后对每张卡"写 class → 读 clientWidth → 读 rect"交替（5886–5904）。后台补标签期间每 250 ms 一次 `renderAll`（3897–3899），1000 条库 ≈ 每 250 ms 一千次强制重排，滚动掉帧，滚动位置/焦点/hover 全丢。
→ ① `rollCardTags` 两段式：先一次遍历只读收集，再统一写；② 卡片按 `data-id` 做 keyed patch，不要重建容器；③ 用户正在滚动时暂缓重画。⌛中

**4. 搜索每个按键重建整页，且每条现拼 7 段字符串 + `toLowerCase`**
9781–9785 / 9801–9805 + `matches()`（4401–4408）。3000 条库时每个按键 = 3000 次拼接 + 3000 张卡重建（还叠加 P0-1 的按钮消失）。
→ 条目上加惰性 `m._hay`（小写搜索串，导入/改名/改标签时置 null 失效），`matches` 只做 `includes`；输入框 150 ms debounce，回车/失焦立刻 flush。⌛小

**5. 波形：按 48 kHz 全量 PCM 估内存 + 常驻 AudioContext → 超过约 11 分 23 秒的文件永远没有波形，而且不告诉用户**
`MAX_PCM = 250MB`，判据 `(duration) * 48000 * 4 * 2 > MAX_PCM`（6230/6235），约 683 秒就放弃；而这是录音/播客向的工具，正好砍在主力文件上。另外 `onTime` 每 250 ms 全量重画 720 根柱（每根改一次 `fillStyle` + 一次 `getBoundingClientRect`），与圆点的 60 fps rAF 不同拍。
→ 用 `OfflineAudioContext`（8 kHz 单声道）做解码上下文，PCM 直接降到 1/6，阈值顺势放到几小时档；波形做成离屏位图，播放时只 `drawImage` + 改 clip 宽度；给"没有波形"一条 hover 说明。⌛中

**6. 换 id 时漏改 `history` 和 `settings.lastId`；删条目时漏删 `peaks` 和封面 Blob URL**
`rekeyItem()`（3715–3732）只改 collections/queues/done，于是"覆盖导入"之后榜单次数挂在旧 id 上、`lastId` 指向不存在的记录（重启后播放台空着）。删条目（7704、11134）只删 `files`，`peaks` 和 `m.coverUrl` 都留着；而 `restoreLibrary` 起步就给全库封面建了 objectURL（4136–4139），基数本来就大。
→ `rekeyItem` 补 history 键迁移与 `lastId` 重写；抽一个 `forgetItem(id)` 把 `files`/`frameKey`/`peaks`/`revokeObjectURL(coverUrl)` 四件事收成一处。⌛小

**7. `load()` 末尾无条件 `renderAll()`，只为把"正在播"那一行点亮**
5094 → 整页重建（视频还多一次：3612）。行内那点状态本来只按 `player.current.id` 算。
→ 加 `syncNowPlaying(prevId, nextId)`，只 toggle 那两条的类名。⌛小

**8. 片段循环精度只有 250 ms × 倍速**（5249–5252）
3× 时最多越过 B 点 750 ms，`0.06` 的前置量只对得上 1×。另外 `clipList()` 每个 tick 都 filter+sort 重建。
→ 播放中用 rAF 判 B 点，或进 A 后按 `(b-a)/rate` 定时校准；`clipList` 按 `m.id + clips 版本` 缓存。⌛中

### P2 · 一致性、无障碍、工程

**状态与数据一致性**
- `markTempDone()` 挂在 `load()`（5033）而不是"真的开播了"（5354–5361 才有 `m._counted` 闸）→ 自动播放被拦时整列被当成"听过"收走。挪到 `setPlaying(true)`。
- `clipA/clipB` 只在 `if (!auto)` 里清（5047）→ 自动连播会把上一首的 A/B 带到下一首，一点"加片段"就按上一首的秒数记。挪出 `!auto`。
- 换源时旧元素的 `pause` 事件迟到会作用到新曲（5313–5317 的 handler 不认 `e.currentTarget`）→ 每个 handler 加 `if (e.currentTarget !== activeEl) return`。
- 临时待播的 `done` 只增不减（7358–7360、7718、8832）→ 一列被删空后永不自收、移出再加回的条目被当成听过。改 ids 的三处都补 `q.done = q.done.filter(id => q.ids.includes(id))`。
- `history` 注释写"天数封顶"（3350–3354），代码没有任何裁剪（4316–4322）→ 要么补裁剪（保留最近 N 天，更老的并进 `_all` 桶），要么改注释。
- 音量/静音不落盘（6804）→ 每次重开都回 70%，与"别的设置都记得"不一致。并进 `store.settings`，滚轮改成按 `deltaY` 量级 + 30 ms 节流。
- ID3 放行了 v2.2 却按 v2.4 帧头解析，且没读 flags 的扩展头/非同步位（3076–3089）→ 带扩展头的文件会整份丢标签、退回文件名。v2.2 直接 `return null` 最省事。
- `emptyHTML()` 的 `desc`/`descCls` 直接进 innerHTML（4643）→ 目前调用点都是字面量，但这个签名没区分"可信 HTML"，是全文件唯一例外。改名为 `descHTML` 并写明约定。
- 内嵌封面按原图入库（3987）→ 2400×2400 的扫描件常见 2–3 MB，一专辑 12 首就是几十 MB 配额。入库前用 canvas 缩到 480 再 `toBlob(jpeg, .82)`（`seedFrameCover` 已有这套代码）。
- 死代码：`qRowHTML()`（4885–4916）早已不用，只剩 9572 的 `[data-del]` 守卫还引用它。

**无障碍（这块做得比多数单文件项目好，缺口集中且好补）**
- 11 个业务弹窗全是裸 `div.modal`：没有 `role="dialog"` / `aria-modal` / `aria-labelledby`，全文件 0 处 `inert`，打开不移焦点、关闭不还焦点；锁屏下面那层 `.app` 也没 inert（键盘仍可 Tab 到背后并回车）。
  → 收一对 `showModal/hideModal`：记 `lastFocus`、给 `.app` 加 `inert`、弹窗根补 `role/aria-modal/aria-labelledby`、关掉时还焦点。⌛中
- 音量滑杆可 Tab 聚焦，但 `.vol-pop` 只在 `:hover` / `.open` 下显形 → 键盘用户"摸黑调音量"。补一条 `.vol:focus-within .vol-pop{...}`。⌛小
- 「已有标签」下拉键盘选不中：输入框 blur 后 `setTimeout(hideTagDrop,0)`（7533）在 Tab 到选项时立刻把盒子藏起来。按 `relatedTarget` 判断，或改 `role="combobox"` + `aria-activedescendant`。⌛中
- 搜索下拉是半套 ARIA：`#searchDrop` 不是 listbox，子项却是 `role="option"`，也没有 `aria-activedescendant`（2603、9734、9777）→ 补 listbox + 每项 id + activedescendant。⌛小
- 每张卡 3 个 Tab 停靠点（卡片把手 / 封面 / ⋯）→ 1000 条库 = 3000 次 Tab。改 roving tabindex（容器一个 tabindex=0，方向键移动）。⌛中
- 书架布局缺 `role="listitem"`（4616 vs 4595），`role="list"` 的语义一半是空的。⌛小
- 设置四个 tab 只切 `aria-selected`，没有 roving tabindex / 方向键（7881–7892）。⌛小
- 标记载荷行里的"重命名/删除"按钮被外层行 keydown 劫持：按空格/回车变成跳转并开始播放（5816–5821、5706–5710）→ keydown 先 `if (e.target.closest('button,a,input')) return`。⌛小

**CSS**
- `var(--ink-1)` 用了 10 处、`var(--mint-400)` 用了 1 处，两个令牌从未定义 → 触发 IACVT，颜色静默退回继承值（949、1547–1603）。补令牌或改回现有名字。
- `.mrow/.q-row/.pl-row` 常驻 `will-change:transform`（516/558/991），而 467–469 自己写过"每张卡挂一层合成层太费" → 只留 `.dragging/.shifting`。
- 黑夜模式下 `.card-more/.card-grip` 仍是硬编码白底（486–498），图标 `--ink-2` 在 dark 里是 `#AFC0BA` → 对比约 1.8:1，而手机端这两颗是唯一入口。抽成令牌。
- 主色 `#16C79A` 上压白字对比约 2.2:1（主按钮、播放键、播放中药丸）→ 实心底上的字改深墨，或底色换 `--mint-600/700`。

**工程与发布**
- **版本号不一致**：根 README 与 `android/app/build.gradle`（`versionCode 9 / versionName "0.1.8"`）是 0.1.8，但 `mobile/package.json` 还写着 `0.1.4`。
- `mobile/www/index.html` 当前与根 `index.html` **哈希一致**（已核对），但它是被 `.gitignore` 掉的产物，一旦忘了 `npm run sync` 就会出现"APK 里是旧版本"且毫无提示 —— 出包前跑一次哈希比对最省心。
- `node_modules` 没有入库（好）；仓库只有 70 个受控文件（干净）。
- `README` 的更新日志里 v0.1.7 与 v0.1.8 **同一天**（2026-09-17），而 v0.1.7 标题下缺少与上一版之间的空行，排版上容易被读成一节。
- **建议加一个零依赖校验脚本** `scripts/check.js`（`node scripts/check.js` 就能跑，不需要 npm install），至少守四件事：
  1. 抽出第二个 `<script>` 块 → `new Function(src)` 做语法检查（不需要写临时文件）；
  2. 体积守卫：`index.html` > 600 KB 就告警（防单文件悄悄膨胀）；
  3. 版本守卫：`README.md` / `mobile/package.json` / `build.gradle` 三处版本号必须一致；
  4. 一致性守卫：`mobile/www/index.html` 与根文件哈希一致（或提示先跑 sync）。
  再加两条"便宜的体检"：重复 `id="..."` 检测、`var(--x)` 中 `x` 是否有定义。这四条正好覆盖了上面最容易复发的那几类错误。⌛小

---

## 五、如果只做三件事

1. **P0-1 + P0-2**（`renderMain` 后补 `placeTopActions`；`closeTopLayer` 补四层）——两处共约 15 行，直接消掉两个"用户一定会撞上"的交互 bug。
2. **P0-3 + P0-4**（媒体 `error` 监听；`MediaService` 的 `startForeground` 兜底）——把"静默卡死"和"进程崩溃"变成"一句人话提示"。
3. **P1-1**（拆 `savePos` / `saveSettings`，`saveLibrary` 加合并）——一次改动同时解掉播放、设置、勾选三条路上的写放大，库越大收益越明显。

## 六、自测清单（改完这些建议顺手验一遍）

- 桌面端：资料库 → 搜索框输入任意字符 → 三颗按钮还在吗？→ 清空搜索 → Esc 关下拉。
- 安卓：开「更多操作」菜单按返回键；开「随机临时待播」按返回键；开「新建收藏夹」按返回键（都应只关那一层）。
- 播放一个 `.mkv` 或把索引条目的原文件删掉，再点播放 → 应有提示而不是没反应。
- 在音频页把一个标签的 × 点掉（确认框出现）→ 按 Enter → 应只关确认框、不动编辑框。
- 编辑台词时点播放台「下一首」→ 保存后检查是否写到了原来那首。
- 设置里把解锁方式从「密码」改成「九宫格」，弹确认框时点取消 → 重启 → 原密码还能解开。
- 一次拖入同一个文件两次 → 资料库里应只有一条。
- 播放一首超过 15 分钟的歌 → 波形是否出现、界面是否卡。
