# KeranQuests 2 安装与配置

## 安装

- 服务端：Paper 1.20.1、Java 17。只安装 `KeranQuests-2.0.0.jar` 到 plugins/。
- 客户端：Minecraft 1.20.1、Fabric Loader 0.15+、Fabric API；把 `KeranQuests-Client-1.20.1-2.0.0.jar` 放入 mods/。
- 安装客户端后，按 **J** 或输入 `/kq` 打开独立任务终端。J 可在按键设置中修改。
- 任务界面、任务树与 HUD 无需 Oraxen、资源包或浏览器。原有 Oraxen 任务物品继续通过可选挂钩工作。移除 Oraxen 前，必须替换任务中的 Oraxen 物品。
- GUI 缩放导致逻辑宽度小于 640 时，详情改为单独面板；点“← 任务树”返回。
- 图中拖动平移，滚轮缩放；右侧详情与章节列表独立滚动；抉择及放弃任务需要再次确认。

## 升级已有服务器

1. 停服并备份整个 plugins/KeranQuests 目录以及玩家物品/经济数据。
2. 移除服务器 plugins/ 中旧版插件 jar，放入新 jar，分发客户端模组并重启。
3. **升级不会覆盖已有任务、对话、主配置或玩家数据。** 新的简化模板位于 Release 包与源码 resources 中。想使用新模板，请先导出原配置再手动替换。
4. 玩家档案增加格式版本、阶段完成处理标记、任务运行标识与阶段计时起点。旧档案会读入，下一次保存时迁移。回滚时恢复备份，避免旧版本忽略新字段。
5. 旧 `stages`、`requirements`、命令、物品 ID 继续支持；不再猜测有歧义的任务短 ID，第三方调用请写 `树:任务`。
6. 旧 main04 配置中的“先 progress、再 choose”手法，会在内存加载时转换为选项上的完整交接，原 YAML 不会被改写。升级前已经在该缺口失去抉择入口的玩家，可由管理员 `/kquests reset <玩家> zhulong:main04` 后重新接取。
7. `consume: true` 的阶段物品现在会在**阶段完成时**扣除。请核对自己是否还写了额外的扣物品命令，避免双扣。
8. 旧对话的 choose 选项，默认不推进 NPC_TALK；仅物品消费且没有目标分支任务的选项默认推进。需要推进的其他选项请明确添加 `advance_talk: true`；稍后再说请用 `end: true`。

可选配置（旧 config.yml 没有这些键也使用下列默认值）：

```yaml
ui:
  mode: auto             # auto 使用客户端；legacy 强制旧菜单
  require_client: false  # true：未安装客户端时提示安装，不打开箱子菜单
```

## 简化任务

```yaml
schema: 2
id: story
name: 新的故事
defaults:
  type: MAIN
quests:
  arrive:
    name: 抵达营地
    steps:
      - id: arrive
        enter: world:camp
  meet:
    name: 与向导交谈
    after: [arrive]
    steps:
      - id: talk
        talk: 向导
      - id: pause
        wait: 5s
    rewards:
      items:
        - {id: 'minecraft:bread', amount: 10}
```

- `steps` 与 `stages` 等价；不写阶段 ID 时自动使用 `stage_1` 等。上线任务建议显式写 ID。
- `after: [任务ID]` 等价于本树的 `prerequisites.quest_completed_all`。跨树写 `other:quest`。
- `enter: 世界:区域`、`talk: NPC名`、`trigger: key`、`wait: 5s` 为单目标简写。
- `stay: {region: 'world:camp', time: 30s}`：连续停留，离开或掉线重新计时。
- `submit: {item: 'minecraft:paper', count: 3}`：持有足量时立即完成该阶段并扣除物品。如果要与 NPC 交货，应使用对话选项的物品门槛与消费，不要把此简写误认为必须手动点击提交。
- 复合目标仍可用 `requirements`、`mode: ALL/ANY` 和 `need`。ALL 多条相同消费物品会合计校验，ANY 按顺序选择足够的满足项并消费其中的物品。
- 时间简写 `s/m/h` 用于 cooldown、wait 与 stay.time；原有 `delay ... | command` 和对话 after 仍是 tick。
- `minecraft:` 物品无需 Oraxen；裸 ID 和 `oraxen:` ID 使用 Oraxen。
- 玩家进度仍按阶段与目标**序号**保存。给正在进行的任务重排/插入阶段前，先备份并安排对应玩家进度迁移；显式 ID 目前不会自动迁移历史序号。

## 交货与分支

```yaml
- choose:
    prompt: 你怎么回答？
    options:
      - label: 稍后再说
        end: true
      - label: 交付数据，进入合作路线
        require_item: data_drive
        require_count: 1
        consume: true
        complete_quest: 'story:report'
        quest: 'story:cooperate'
        choice_group: 'story_final'
        value: A
```

服务器先检查物品、原任务、分支前置及并发限制，再执行扣除与任务交接。检查失败不会保存抉择。`complete_quest` 只应指向这次选择确实要完成的原任务。任意控制台命令和第三方经济不属于可回滚数据库事务，因此不要用命令拼装物品/分支的核心状态操作。

NPC_TALK 完成只作用于**开始本段对话时**的任务与阶段，避免一段对话顺带完成后续同 NPC 的其他阶段。

## 可视化编辑器

直接打开 Release 中 `KeranQuests-Editor-2.0.0.html`，无需网络、安装或服务器端口。支持：

- 导入原有任务树与 npc/dialogue.yml；任务表单、自动关系图、前置连线、平移缩放。
- 添加/删除/调整阶段，编辑各类目标、奖励与对话节点。
- 对话的选项、完整条件与生命周期命令可在对应高级 YAML 中编辑。
- 检查当前章节引用、目标必需字段、阶段重复与前置循环；跨章节引用提示在服务端核实。
- 源码编辑、默认值省略、简写导出、浏览器草稿与最多 50 步撤销。
- 保留导入配置的高级字段。编辑器不连接服务器，导出后需手动放入 quests/ 或 npc/，执行 `/kq reload`。

服务器严格加载完整配置，错误时保留原任务和对话注册表。它不会验证第三方世界中每个 NPC、区域、物品一定存在；发布前仍需在实际服务器确认这些资源。

## 验证范围

发布构建包含 Java 回归测试、编辑器配置测试、客户端编译与离线编辑器浏览器检查。自动测试覆盖交货拒绝、消费幂等、抉择失败回滚、断线清理、区域刷新和存档迁移。未在本次环境中完成真实 Minecraft 客户端联机试玩；上线前先在备份测试服验证 Citizens、WorldGuard、Oraxen、语音与撤离插件的实际组合。
