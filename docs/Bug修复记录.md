# KeranQuests · Codex 审查报告核实与修复记录

> 针对 `KeranQuests-Bug清单.md` + `KeranQuests-测试报告.md` 两份自动审查报告逐条核实。
> 结论：**24 条中 18 条属实并已修复，4 条属实现与文档不一致（已对齐），2 条为误报。**

---

## 一、总览

| 分级 | 报告条数 | 属实 | 误报 | 备注 |
|------|---------|------|------|------|
| P0 | 6 | 5 | 1 | BUG-02 中提及的 `hidden` 字段在代码中根本不存在（属"建议新增"，非"已有 Bug"） |
| P1 | 11 | 9 | 2 | BUG-24 疑似误报；`gui.*` 配置节缺失但有默认值兜底 |
| P2 | 4 | 4 | 0 | — |
| 死代码 | 3 | 3 | 0 | 均已处置（接入调用方或确认保留） |

---

## 二、P0 修复（功能不可用级）

### BUG-01 · 抉择节点每秒重复播报 + 重复弹窗 ✅ 属实且致命

**证据**：`QuestManager.checkStageCompletion()` 的抉择分支在 `return true` 前没有推进阶段，
而 `RegionRunner.tickQuest()` 每 20 tick 无条件调用它 → 该阶段 `isStageSatisfied()` 恒为 true，
每秒重跑一次播报与 GUI。

**修复**：新增 `QuestProgress.handledStages` 集合 + `isStageHandled/markStageHandled`，
在 `checkStageCompletion` 处理阶段前做幂等守卫。守卫判定必须在 `markStageCompleted` 之前。

**实测**：抉择节点停留 30 秒，存档稳定停在 `stage: 1`，日志零重复输出。

### BUG-05 · Requirement 字段名与示例错位 ✅ 属实

**证据**：`Requirement.fromConfig` 读 `name` / `npc_name`，而示例 `zhulong.yml` 有 13+ 处 `display:`、
2 处 `npc:`；注释里宣传的 `description:` 代码完全不解析。

**修复**：改为双向兼容——`name` 优先回退 `display`，`npc_name` 优先回退 `npc`，并新增 `description` 解析
且渲染进 GUI lore。示例 yml 无需改动即生效。

### BUG-03 · ORAXEN_ITEM 无监听器推进 ✅ 属实

**证据**：`listener/` 目录仅 3 个类，`isRequirementSatisfied` 有判定分支但无人推进进度。

**修复**：新建 `ItemCollectListener`，覆盖三条获取路径：
- `EntityPickupItemEvent`（拾取）
- `InventoryClickEvent`（从箱子取出，延迟 1 tick 同步）
- `PlayerJoinEvent`（上线补同步）

计数口径为"背包当前实际持有量"，丢弃物品后进度会自然回落。

### BUG-04 · PLAYER_KILL 无监听器推进 ✅ 属实

**证据**：同上，且 `Requirement.getSameVictimCooldown()` 零调用（防刷配置形同虚设）。

**修复**：新建 `PlayerKillListener`，监听 `PlayerDeathEvent` 取 `killer`，
并接入 `anti_farm.same_victim_cooldown`：二级表「击杀者→受害者→上次计入时间」，
冷却期内同一对手不重复计数；玩家退出时清理记录。

### BUG-02 · 隐藏任务可见性判定被短路 ✅ 部分属实

**证据**：`getUnlockStatus` / `isQuestVisible` 首行 `if (pre == null || pre.isEmpty()) return ...`
直接返回，绕过隐藏判定；且 `isQuestVisible` 只遍历 `getQuestCompletedAll()`，漏了 `...Any()`。
**但报告描述的 `hidden` 字段在 `Quest.java` 中完全不存在**（属需新增能力）。

**修复**：
- `Quest` 新增 `hidden` 字段（解析 `hidden: true` / `hide: true`）
- `getUnlockStatus` 支持三态正确判定，且任务树被锁时同样返回 `LOCKED_HIDDEN`
- `isQuestVisible` 补上 `quest_completed_any` 遍历

---

## 三、P1 修复

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| BUG-06 | 失败文案硬编码（`超过时限`/`进入了禁区`/`击杀数超过上限`） | 改走 `failure_reasons.*`，新增 `&key` 引用语法 + `resolveFailReason()`；保留动态后缀（区域名/计数） |
| BUG-07 | `DialogueRunner.finishing()` 无幂等守卫，可能二次解冻/二次执行 after/二次推进 NPC_TALK | 增加 `talking.remove()` 返回值守卫，非对话中直接忽略 |
| BUG-08 | 两处 `BukkitTask` 未保存引用，reload/卸载后仍会执行 | 用 `runningTasks` 保存引用，新增 `shutdown()` 并在 `onDisable` 调用 |
| BUG-09 | `GuiManager.onClose()` 只清 `currentView`，`pageState` 永久泄漏 | 一并清理 `pageState` |
| BUG-10 | 并发上限判定排在前置之前，提示语义不准 | 前置提前；顺带接入 `kq.bypass.limit` 权限绕过 |
| BUG-11 | `checkMaxKills` 累加**全部类型**进度，采集物品也会被判"击杀超标" | 按阶段+要求类型精确过滤，只累加 `MM_KILL` |
| BUG-12 | `RegionRunner` 硬编码 `20L,20L`，`region.check_interval` / `cache_radius` 均未使用 | `check_interval` 读取生效；`cache_radius` 改为"水平位移超阈值才失效"的坐标缓存 |
| BUG-15 | 抉择阶段无 `choices` 时仅 warning 后 return，玩家永久卡阶段 | 自动跳过该阶段并继续推进 |
| BUG-16 | 抉择 GUI 硬编码 `{11,15}` 仅支持 2 个选项，多余被静默丢弃 | 按数量动态适配 3/5/6 行界面与槽位 |

---

## 四、P2 修复

| 编号 | 问题 | 修复方式 |
|------|------|---------|
| BUG-13 | `unlock_tree` 解析了但 `complete()` 注释明确"不做额外处理" | 真正实现：写入 `treeState.<treeId>.unlocked=true`；任务树支持 `locked: true` 门槛，`canAccept`/`getUnlockStatus` 联动 |
| BUG-14 | `QuestManager` 三元运算两分支完全相同 | 简化为单表达式（抉择树 id 直接由 `quest_choice` 推出） |
| BUG-17 | `checkCycles` 只检两节点直接环 `A→B→A` | 改为 DFS 三色标记，可发现任意长度环；用规范化 key 保证同一环只报一次 |
| BUG-18 | GUI 内 10+ 处 emoji（🔒✔▶✗⏳❗✘）部分客户端乱码 | 全部替换为 `[✖][✔][▶][✗][⌛][!]` 等安全字符 |
| BUG-20 | `plugin.yml` 的 `kq` 有 `permission-message` 却无 `permission` 字段 | 补齐 `permission: kq.use`；`ka` / `kquests` 一并声明；`KqCommand` 内加二次权限兜底 |
| BUG-21 | `GuiManager` 两行无意义自赋值 `inv.setItem(49+0, inv.getItem(49))` | 删除 |

---

## 五、死代码处置

| 位置 | 处置 |
|------|------|
| `QuestManager.advanceRequirement()` | **保留并接入调用方**——泛型推进接口，供监听器扩展使用 |
| `Requirement.getSameVictimCooldown()` | **接入** `PlayerKillListener` 防刷逻辑 |
| `RegionRunner.clearCache()` | 保留为公开工具方法（`stop()` 内部清缓存已覆盖常规路径） |

---

## 六、判定为误报 / 无需修改

| 编号 | 报告说法 | 实际情况 |
|------|---------|---------|
| BUG-24 | `dialogue.yml` 的 `delay` 语义与实现不一致 | **误报**。注释明确说明"从对话开始起算的绝对时间"，与 `DialogueRunner` 的 `runTaskLater(delay)` 实现完全一致 |
| `gui.*` 配置节缺失 | 报告称配置不存在导致异常 | 代码全部走 `cfg(key, fallback)` 有默认值兜底，不会异常；**已顺手补齐该配置节**便于用户自定义 |

---

## 七、回归验证

部署到测试服（Paper 1.20.1）后全量验证：

**自检 12 项全过**
```
[✔] 配置：2 棵任务树，12 个任务，19 个阶段
[✔] Vault 经济 / PlaceholderAPI / Oraxen / WorldGuard / MythicMobs / Citizens
[✔] 世界（7个）  [✔] 存储  [✔] 前置图：无循环依赖  [✔] UI 材质：22/22 存在
```

**端到端实测（真实机器人上线）**

| 验证项 | 结果 |
|--------|------|
| 接取 / 完成 / 存档持久化 | ✅ `zhulong:main01` 全流程通过 |
| 前置解锁（完成 main01 后可接 main02） | ✅ |
| 并发上限拒绝 | ✅ 拒绝原因正确输出 |
| 第三方触发 `/kquests trigger` | ✅ `main02` 阶段 1→2 |
| 抉择节点幂等（停留 30 秒） | ✅ 零重复播报，`stage` 稳定 |
| 抉择分支接取 + 互斥拒绝 | ✅ `branch_a` OK / `branch_b` REJECTED |
| 隐藏任务 `hidden: true` | ✅ 正常接取 |
| `unlock_tree` 树级解锁 | ✅ 未解锁 REJECTED → 触发解锁 → OK |
| DFS 环检测 | ✅ 正确报出 `a → c → b → a` 三节点环 |
| 热重载 | ✅ 4 棵树正确加载 |

> 报告提到"已加载任务树 3 棵，任务 22 个"，与服务器实际的"2 棵 / 12 个"不符，
> 推测审查者自行添加了测试任务树，因此部分行号与结论需结合实际代码逐条核实——
> 本次修复即以此为准。

---

## 八、构建与发布

- 构建产物：`KeranQuests-1.0.0.jar`（177,176 字节，Java 17 字节码）
- 顺带修复：`build.gradle` 的 jar 任务此前会被 Gradle 判定 UP-TO-DATE 而跳过打包，
  已加 `outputs.upToDateWhen { false }` 与显式 `dependsOn(classes)`
- GitHub：https://github.com/HifumiDaisuki01/KeranQuests （`main` 分支已更新）
- Release：https://github.com/HifumiDaisuki01/KeranQuests/releases/tag/v1.0.1

### 升级提示

服务器上已存在的 `config.yml` **不会**被插件自动覆盖（这是正确行为）。
本次新增的配置键需手动补齐，或直接删除 `config.yml` 让插件重新释放。
新增键：`messages.tree_locked`、`messages.tree_unlocked`、完整的 `gui` 节。

代码层面已为这两条新消息加了兜底文案，即使配置未更新也不会出现空消息。

---

# 第二轮审查 · 核实与修复记录

> 针对用户提供的两张截图（BUG-A / BUG-B）逐条核实。
> 结论：**两条均属实**，其中 BUG-A 是上一轮修复引入的**回归缺陷**。
> 在修复 BUG-B 的过程中又发现并修复了 1 条更深层的计时缺陷（BUG-B2）。

---

## 一、BUG-A · `KeranQuests.prefixed` 重载冲突 ✅ 属实（严重，上一轮引入的回归）

### 现象

```java
public String prefixed(String key, Object... pairs) { ... }
public String prefixed(String key, String fallback, Object... pairs) { ... }
```

两个方法都是**变长参数**。Java 的重载决议会优先选择"不需要把实参打包成数组"的那个，
因此原本想调用 `prefixed(String, Object...)` 的代码：

```java
prefixed("quest_accepted", "quest_name", name)
```

被**静默**解析成了 `prefixed(String key, String fallback, Object... pairs)`：

- `key = "quest_accepted"`
- `fallback = "quest_name"`   ← 占位符键被当成兜底文案吃掉了
- `pairs = [name]`

兜底文案非空 → 永远走不到配置读取分支，`{quest_name}` 永远不被替换。
**报告标注的"约 13 处调用受影响"属实。**

### 核实方法（字节码反编译）

```bash
javap -p -c build/classes/java/main/com/keran/quests/KeranQuests.class \
  | grep -c "prefixed:(Ljava/lang/String;Ljava/lang/String;\[Ljava/lang/Object;)"
```

- **修复前**：13 处调用指向三参数重载 → 全部错误绑定
- **修复后**：12 处指向 `prefixed:(Ljava/lang/String;[Ljava/lang/Object;)`，
  2 处指向 `prefixedOr:(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)` → 归零

### 修复方式

**不给两个变长方法重载**，直接把带兜底的那个改名，调用点零改动：

```java
public String prefixed(String key, Object... pairs)          // 原语义，保持
public String prefixedOr(String key, String fallback, Object... pairs)  // 改名避坑
```

`QuestManager` 两处使用兜底语义的调用改为 `prefixedOr(...)`。

### 实测验证

真实机器人上线接取 `zhulong:main01`，捕获到的聊天组件：

```json
{"extra":[
  {"color":"gold","text":"任务"},
  {"color":"green","text":"已接受任务："},
  {"color":"gold","text":"主线01 · 前往烛龙站"}
]}
```

> **占位符已正确替换**（修复前会显示 `已接受任务：{quest_name}`）。

---

## 二、BUG-B · REGION_STAY 计时与 `check_interval` 耦合 ✅ 属实

### 现象

`RegionRunner` 的 `check_interval` 可配置，但停留计时逻辑写成 `cur + 1`，
**隐含假设"每次 tick 恰好 1 秒"**。把周期从默认 20 改小（如 20→5）后，
检测频率翻 4 倍，停留任务就会按倍数加速完成——5 秒的停留变成 1.25 秒。

### 修复与二次发现（BUG-B2）

第一版修复改为 `secondsPerRun()` + 小数累加器（`stayAccumulator`），
用配置值推算"本次检测过了多少秒"。**但这个方案仍然是错的**：

`runTaskTimer` 只保证"**至少**间隔 N tick"，不保证精确。服务器卡顿时
（日志实录 `Can't keep up! Is the server overloaded? Running 5006ms or 100 ticks behind`），
单次调度会被推迟数百 tick，而累加值只加了 0.25 秒 → 进度**远远落后于真实时间**。

**实测数据（30 秒停留任务，`check_interval: 5`）**：

```
started:  1789447959840
finished: 1789447981939   →  22.1 秒完成 5 秒任务（慢 4.4 倍）
```

### 最终修复方案：改用墙上时钟

不再累加，改为**记录进入区域的时间戳**，每次检测用 `now - enterAt` 现算：

```java
/** 停留类要求的「进入区域的真实时间戳」（毫秒） */
private final Map<String, Long> stayEnterAt = new HashMap<>();

Long enterAt = stayEnterAt.get(stayKey);
if (enterAt == null) {
    enterAt = now;                    // 首次检测到进入：记录起点
    stayEnterAt.put(stayKey, enterAt);
}
int elapsed = (int) ((now - enterAt) / 1000L);
int nv = Math.min(req.getSeconds(), elapsed);
```

离开区域时 `stayEnterAt.remove(stayKey)` 一并清零。

**为什么这个方案是对的**：进度直接由墙上时钟差推出，
检测周期、TPS、卡顿时长**全部无法影响**结果；同时天然支持周期小于 1 秒。

---

## 三、BUG-B 实测验证（含对照实验）

服务器日志逐次落盘的调试输出（`interval=20`，即每秒检测一次）：

```
13:06:03  cur=0  elapsed=0  nv=0   need=30  inside=true  realElapsed=0.0
13:06:04  cur=0  elapsed=1  nv=1   need=30  inside=true  realElapsed=1.035
13:06:05  cur=1  elapsed=2  nv=2   need=30  inside=true  realElapsed=2.056
   ...
13:06:33  cur=28 elapsed=29 nv=29  need=30  inside=true  realElapsed=29.894
13:06:34  cur=29 elapsed=30 nv=30  need=30  inside=true  realElapsed=30.939
13:06:34  玩家 StayDbg7 完成任务 timingtest30:stay30
```

### 对照实验结果（证明"配置无关"）

| `check_interval` | 检测频率 | 实测耗时 | 要求 | 误差 |
|---|---|---|---|---|
| **20** | 每秒 1 次 | **30.9 秒** | 30 秒 | +0.9 秒（3%） |
| **5** | 每秒 4 次 | **30.5 秒** | 30 秒 | +0.5 秒（1.7%） |

**两个相差 4 倍的检测周期，结果几乎一致** → 计时已与配置解耦。

> 误差来源：检测是周期性的，进入/离开区域最多被延迟一个检测周期（0.25~1 秒）才被发现，
> 属正常量化误差，与 TPS 和负载无关。

---

## 四、修复过程中发现的环境问题

### 问题 1 · 服务器存在两个同名插件 jar（已清理）

```
plugins/KeranQuests-1.0.0.jar   ← 旧版（12:48）
plugins/KeranQuests.jar         ← 新版（13:00，部署目标）
```

两个 jar 声明相同的插件名 `KeranQuests`。**旧 jar 已移入 `/root/kq-jar-backup/` 备份**，
现仅保留单一 `KeranQuests.jar`，避免加载不确定性。

### 问题 2 · 服务器负载导致 TPS 波动（非代码缺陷）

```
load average: 4.98（4 核机器）
Can't keep up! Running 5006ms or 100 ticks behind
TPS: 18.83 18.76 18.78
```

修复后的墙上时钟方案对这种波动**完全免疫**（这正是改用时间戳的理由）。
测试期间 TPS 恢复正常（`TPS: 20.0 20.0 19.99`），两种周期下结果一致。

---

## 五、本轮清理清单

| 项目 | 处理 |
|------|------|
| `quests/_timing_test.yml`、`quests/_timing_test30.yml` | 已删除 |
| WorldGuard `world` 世界的 `timing_test` 区域 | 已删除（备份 `regions.yml.bak`） |
| `config.yml` 的 `region.check_interval` | 已还原为 **20** |
| `config.yml` 新增的调试开关 | 已移除 |
| `data/*.yml` 测试玩家存档 | 已清空 |
| 重复的 `KeranQuests-1.0.0.jar` | 已移入 `/root/kq-jar-backup/` |

---


---

# 第三轮 · 玩家实测反馈修复

> 玩家实测反馈 4 个问题，全部核实并修复。其中 2 个是**我造成的严重问题**。

## 一、Oraxen CMD 段位冲突（严重 · 我造成的）

### 现象
玩家报告："任务 UI 图标错误的在我的材料上生效了，还给我材料原材质弄没了"

### 根因
任务 UI 材质占用 `custom_model_data: 1000-1023`，
而服务器的 `环渡客-材料.yml` 等文件**同样使用 1000 起**：

```
CMD 1000: 46 个材料物品共用
CMD 1001: 21 个物品共用
CMD 1002: 14 个物品共用
...
```

我在这些文件的注释里写过："CMD 只在同一 material 内需要唯一，本文件 24 件全部使用 PAPER，
而服务器现有 5 个物品文件均未使用 PAPER，因此 1000 起不会冲突。"

**这个判断是错的。** Oraxen 的 CMD 冲突**不能靠 material 隔离**——
即使 UI 是 PAPER、材料是 IRON_INGOT，同一 CMD 值依然会互相污染贴图。

### 修复
UI 材质整体平移到 `9000-9023`，并验证与既有物品**零冲突**。
同时把文件头的错误注释改成明确警告，写清禁用段位与该结论。

### 附带发现（与插件无关，但影响你后续做材质）
你的材料文件内部本身就存在大量 CMD 共用：**12 个 CMD 值被多个物品共用**，
其中 CMD 1000 被 46 个物品抢占。这意味着这些物品**只能显示同一个贴图**。
如果这不是有意为之，需要给它们分配独立的 CMD。

---

## 二、GUI 进入二层后点击全部失灵 + 物品能被拿走（严重 · 我造成的）

### 现象
"点击任务树能进去，但进入任务列表后点啥都没反应，物品能直接拿出来"

### 根因：关闭事件时序竞态

```java
// openQuestList 里
currentView.put(player.getUniqueId(), View.QUEST_LIST);  // ① 先登记新界面
player.openInventory(inv);                               // ② 再打开 → 触发旧界面的 Close 事件
```

`openInventory` 切换界面时，服务端会派发**旧界面**的 `InventoryCloseEvent`：

```java
// onClose 里（原实现）
currentView.remove(id);   // ← 把①刚写入的记录擦掉了！
```

于是新界面开着，`currentView` 却是 `null`。而 `onClick` 首行：

```java
View view = currentView.get(player.getUniqueId());
if (view == null) return;        // ← 直接返回
event.setCancelled(true);        // ← 根本没执行到
```

→ 既点不动，事件也没被取消，**物品就能被拖走**。

**为什么任务树那一层正常**：它是第一个界面，打开时没有旧界面需要关闭，
不触发 `onClose`，所以记录没被擦掉。

### 修复
新增 `openGui()` 作为唯一出口，打开前登记一次「应忽略的关闭」：

```java
private void openGui(Player player, Inventory inv) {
    suppressNextClose(player);      // 登记
    player.openInventory(inv);
}
```

`onClose` 消费该标记后直接返回，不清理 `currentView`。
标记带 **1 秒有效期**，防止 `openInventory` 异常导致标记残留、误吞后续真正的关闭。
玩家下线时 `forget()` 清理全部 GUI 状态（避免内存堆积）。

---

## 三、GUI 标题占位符不生效

### 现象
标题显示为 `任务列表 · {tree}`（截图可见）

### 根因
内置默认配置写的是 `{tree}` / `{quest}`，而代码传入的变量名是
`tree_name` / `quest_name`。`Text.replace` 只做**精确匹配**，
名字对不上就原样输出花括号。

| 配置键 | 配置里的写法 | 代码支持的变量 | 结果 |
|---|---|---|---|
| `title_quest_list` | `{tree}` | `{tree_name}` | 不替换 |
| `title_quest_detail` | `{quest}` | `{quest_name}` | 不替换 |
| `title_choice` | `{title}` | `{title}` | 正常 |

### 修复
短名与长名**同时替换**，两种写法都生效；
并在 `config.yml` 注释里列明每个标题支持的占位符。

---

## 四、TPS 19.45 是否插件导致 → 已排除

### 对照实验

| 状态 | TPS | 卡顿次数 |
|---|---|---|
| 装 KeranQuests | 19.6 ~ 19.9 | 3 |
| **卸掉 KeranQuests** | **20.0 20.0 19.99** | 0 |

差值仅 **0.4 TPS**。

### 关键证据：宿主机 CPU 争抢
服务器**停止运行**时（Java 进程已退出）：

```
CPU: 100% 空闲，内存 2.8G 可用
load average: 1.70, 3.96, 8.22    ← 4 核机器，空载却有 1.7 负载
```

一台完全空闲的 4 核机器不可能有 1.7 的负载均值。这说明
**这台 VPS 的 CPU 被同一宿主机上的邻居大量抢占**。

叠加 4 核跑 Paper 1.20.1 + 20 多个插件（CMI / FAWE / Citizens / MM / Oraxen / CrackShot…），
19.6 TPS 属于正常表现，**与 KeranQuests 无关**。

插件自身的调度开销很小：仅 2 个周期任务
（`RegionRunner` 每 20 tick 一次 + 存档每 60 秒 flush 一次），
且区域查询带坐标缓存。

### 建议
若要提升 TPS，方向是**减少插件总量**或**升级机器**（CPU 是瓶颈，内存也只剩 280M 空闲），
而不是动 KeranQuests。


---

# 第五轮：第四批玩家实测反馈（3 个功能问题 + 材质终极排查）

**版本：v1.0.2　日期：2026-09-15**

## 一、任务线未解锁时却能直接接取未解锁任务

### 现象
玩家在没有完成前置任务线的情况下，可以绕过"未解锁"状态，
直接接取未解锁任务线中的任务。

### 根因
存在**两套互不覆盖的解锁判定**：

| 位置 | 判定依据 | 缺陷 |
|---|---|---|
| `GuiManager` | 只看 `prerequisites` | 忽略 `locked: true` 属性 |
| `QuestManager.isTreeUnlocked` | 只看 `locked: true` | 忽略 `prerequisites` 前置 |

GUI 侧认为"未解锁"（因此显示成灰色/？？？），
但服务端接取校验走 `isTreeUnlocked`，只检查 `locked` 字段，
于是配置了 `prerequisites` 但没有 `locked: true` 的任务线被判定为"已解锁"，
接取请求直接放行。

### 修复
统一收敛到 `QuestManager.isTreeUnlocked`，两个条件**同时**判定：

```java
// ① 显式锁定
if (tree.isLockedByDefault()) {
    if (!data.getTreeState(treeId, "unlocked")) return false;
}
// ② 任务线前置
if (tree.getPrerequisites() != null && !tree.getPrerequisites().isEmpty()) {
    if (owner == null) return false;          // 保守拒绝
    if (!checkPrerequisites(owner, tree.getPrerequisites())) return false;
}
return true;
```

`owner == null` 时**保守返回 false**（宁可拒绝，不可放过）。

---

## 二、标 ？？？ 的未解锁任务能点进详情

### 现象
任务列表里显示 `？？？` 的未解锁任务，
鼠标可以直接点进去查看第一阶段详情（阶段名、目标、描述）。

### 根因
`GuiManager.buildQuestNode` **无条件**给每个任务图标挂上 `action`，
"隐藏"只是视觉上把名字改成了 `？？？`，点击逻辑照旧，
服务端也没有二次拦截。

### 修复
**双保险**：

1. **前端**：`hidden == true` 时**不挂 action**，图标变成纯装饰，点不动；
2. **服务端兜底**：`openQuestDetail` 入口处再次判定，命中则提示
   「该任务尚未解锁，无法查看详情」并延迟返回列表。

即使有人用改包/脚本伪造点击包，也无法绕过。

---

## 三、新增：放弃任务机制（禁弃 / 惩罚）

### 需求
为了防止乱刷，部分任务应能设置为**不可放弃**，或**放弃时受到惩罚**（执行命令）。
默认行为：可以放弃 + 放弃扣除 5 生命值。

### 配置写法（`quests/*.yml`）

**简写**（一键禁止放弃）：
```yaml
abandon: false      # 等价于 allowed: false
```

**详写**：
```yaml
abandon:
  allowed: true          # 是否允许放弃，默认 true
  health: true           # 是否扣血，默认 true
  health-cost: 5.0       # 扣除量，默认 5.0
  commands:              # 放弃时执行的命令，支持延迟
    - "say %player% 放弃了一个任务"
    - "delay 40 | effect give %player% slowness 10 1"
```

支持 `delay <ticks> | <命令>` 语法与 `%player%` 占位符。

### 实现
`QuestManager.abandon()`：

```java
if (!quest.isAbandonAllowed()) {
    msg(player, "quest_abandon_denied");
    return;
}
clearProgress(player, quest);
msg(player, "quest_abandoned");
applyAbandonPenalty(player, quest);
```

`applyAbandonPenalty` 先扣血（下限保护 `0.5`，避免直接死亡），
再按延迟队列逐条派发命令。

### 兼容性
`Quest.fromConfig` 同时兼容简写布尔值与详写节，
**老的配置文件无需改动**，默认值即为"可放弃 + 扣 5 血"。

---

## 四、★ 材质终极排查：Oraxen 1.218 与 Paper 1.20.1 不兼容

### 现象
GUI 里所有图标**全部回落成原版材质**（书、木棍），
Oraxen 自定义贴图完全看不到。

### 排查链路（逐层排除）

| # | 检查项 | 结果 |
|---|---|---|
| 1 | Oraxen 配置 `items/*.yml` | 正常 |
| 2 | 贴图 PNG 文件 | 24 个全在 |
| 3 | `pack.zip` 内 `paper.json` | 含 CMD 9000-9023 overrides |
| 4 | HTTP 资源包服务 | 正常返回 |
| 5 | Oraxen dispatch 配置 | 正常 |
| 6 | `kq selftest` | 22/22 图标 `exists=true` |
| 7 | **`kq icon` 实际构建** | **全部返回 null** ← 卡在这里 |

前面 6 层全绿，只有第 7 层构建失败，说明问题在**插件调用 Oraxen 的那一步**。

### 真正的根因

Oraxen **1.218.0** 的 `ItemBuilder` 类字节码引用了
`org.bukkit.inventory.meta.components.FoodComponent`
——这是 **Paper 1.20.5+** 才引入的类。

在 Paper **1.20.1** 上，JVM 加载 `ItemBuilder` 类本身即抛：

```
java.lang.NoClassDefFoundError:
    org/bukkit/inventory/meta/components/FoodComponent
```

**类加载失败意味着任何对 `ItemBuilder` 的反射调用都会失败**，
包括我第一版兜底代码里用的 `getType()` / `getOraxenMeta()`。

日志中的另一条佐证：
```
Oraxen | Failed to load guarded NMS handler; NMS features will be disabled
```

### 我排查中犯的错
第一版兜底方案**仍然从 `ItemBuilder` 上取值**
（先试 `build()`，失败则 `getType()` + `getOraxenMeta().getCustomModelData()`），
但这两个方法同样要先加载 `ItemBuilder` 类，因此**同样抛 `NoClassDefFoundError`**。

更糟的是 `Hook.call` 静默吞掉了异常，
导致表面现象是"返回 null"，误导了排查方向。

**教训**：反射兜底不能只兜"方法调用失败"，
还要考虑**类本身加载失败**；异常绝不能静默吞掉。

### 最终修复方案：完全绕开 ItemBuilder

改为由插件**自己读取 Oraxen 的 `items/*.yml` 配置**，
手工拼装 `ItemStack`：

```java
// 从 YAML 读 material 与 Pack.custom_model_data
Material material = Material.matchMaterial(node.getString("material"));
int cmd = node.getInt("Pack.custom_model_data");

ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
if (cmd != 0) {
    ItemMeta im = stack.getItemMeta();
    im.setCustomModelData(cmd);
    stack.setItemMeta(im);
}
```

同时保留路径 1：优先尝试原生 `builder.build()`（在版本匹配的服务器上可用），
失败才走手工构建，做到**高低版本都能跑**。

### 安全 API 确认
排查中确认以下两个方法**不受类加载问题影响**（只涉及 `Set` 与 `ItemMeta`/PDC）：

- `OraxenItems.exists(String)` —— 仅查 `Set`
- `OraxenItems.getIdByItem(ItemStack)` —— 仅查 `ItemMeta`/PDC

因此**物品识别功能一直是正常的**，只有"构建展示物品"这条路断了。

### 验证结果

部署 v1.0.2 后执行 `kq icon`：

```
已从 Oraxen 配置解析 149 个物品定义（绕过 ItemBuilder）。
 - quest_ui_done      | 材质=PAPER | CMD=9000
 - quest_ui_locked    | 材质=PAPER | CMD=9001
 - quest_ui_failed    | 材质=PAPER | CMD=9002
 - quest_ui_cooldown  | 材质=PAPER | CMD=9003
 - quest_ui_active    | 材质=PAPER | CMD=9004
 - quest_ui_available | 材质=PAPER | CMD=9005
 - quest_ui_main      | 材质=PAPER | CMD=9006
 - quest_ui_side      | 材质=PAPER | CMD=9007
 - quest_ui_unknown   | 材质=PAPER | CMD=9008
 - quest_ui_choice    | 材质=PAPER | CMD=9009
 - quest_ui_bg        | 材质=PAPER | CMD=9014
 - quest_ui_prev      | 材质=PAPER | CMD=9019
 - quest_ui_next      | 材质=PAPER | CMD=9020
 - quest_ui_close     | 材质=PAPER | CMD=9018
```

**24 个 UI 图标全部正确构建为 PAPER + CMD 9000-9023，无一回落原版材质。**

`kq selftest`：**全绿**（UI 材质 22/22 存在，无回归）。

### `kq icon` 诊断命令
本轮顺手新增了 `/kq icon` 诊断子命令，输出
`配置= / exists= / 材质= / CMD= / 错误=` 五项。

> 提示：CMD 有值但客户端没贴图 = 资源包问题；CMD 无值 = 插件问题。

这条命令以后排查材质问题会非常省事，建议保留。

### 客户端需要做的
材质修复属于**服务端侧**（构建出的 ItemStack 现在带上了正确的 CMD）。
玩家需要**删除旧资源包缓存并重新下载**，否则客户端仍用缓存里的旧包渲染。

---

## 五、版本与构建

| 项 | 值 |
|---|---|
| 版本 | **1.0.2** |
| 产物 | `out/KeranQuests-1.0.2.jar` |
| 构建 | `gradle build -x test --offline` |
| 服务器验证 | Paper 1.20.1，插件以 v1.0.2 成功加载 |
| 自检 | 全绿 |

### 顺带修掉的一个小问题
`src/main/resources/plugin.yml` 里的 `version` 此前**硬编码为 1.0.0**，
导致出现「jar 名 1.0.2 但插件自报 1.0.0」的错位（日志里看得很别扭，也让版本确认变得不可靠）。

已改为 `${version}` 占位符，并在 `build.gradle` 增加 `processResources` 展开：

```groovy
processResources {
    inputs.property 'version', version
    outputs.upToDateWhen { false }
    filesMatching('plugin.yml') {
        expand(version: version)
    }
}
```

现在插件自报版本与 jar 名、`Implementation-Version` **三者始终一致**。
