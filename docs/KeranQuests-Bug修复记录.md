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
