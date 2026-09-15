# KeranQuests

Minecraft 任务系统插件 —— 基于 Paper 1.20.1，支持任务树、阶段推进、抉择分支、循环任务，并深度兼容 MythicMobs / WorldGuard / Oraxen / Citizens / LuckPerms / PlaceholderAPI。

> **设计目标**：让服主/策划**只改 YAML 就能写任务**，不需要碰代码、不需要重新编译。

## 特性

- **任务树** —— 一个 YAML 文件一棵树，通过 `/kq reload` 热重载
- **阶段系统** —— 一个任务多个阶段，支持"全部满足"或"满足 N 条"
- **丰富的要求类型** —— 进入/停留区域、击杀 MM 怪、击杀玩家、收集 Oraxen 物品、NPC 对话、等待第三方触发、权限检查
- **前置条件** —— 完成指定任务、任选 N 个、通关整棵树、持有物品、拥有权限
- **抉择分支** —— 二选一，选了就锁定另一个
- **互斥与终结** —— 任务级互斥组；`terminates_tree` 可锁定整棵树
- **循环任务** —— 配好冷却即可做每日/每周任务
- **失败机制** —— 死亡失败、限时、禁入区域、超额击杀；可选失败冷却
- **生命周期命令** —— 开始/阶段完成/完成/失败时自动执行命令，支持延迟
- **GUI** —— Oraxen 材质图标，任务树列表 → 任务详情，类 TrMenu 交互
- **PlaceholderAPI** —— 20+ 占位符，identifier `kq`
- **第三方联动** —— 提供 `/kquests trigger` 等 API 命令，容错设计绝不报错

## 环境要求

| 项目 | 版本 |
|---|---|
| 服务端 | Paper 1.20.1（Spigot 亦可） |
| Java | 17+ |

**可选依赖**（不装也能跑，装了自动启用对应功能）：

MythicMobs 5.x · WorldGuard 7.x · Oraxen 1.x · Citizens 2.x · Vault · PlaceholderAPI · LuckPerms

所有第三方挂钩均通过**反射**实现，版本不匹配时自动降级而非崩溃。

## 快速开始

1. 把 `KeranQuests-1.0.0.jar` 丢进 `plugins/`
2. 启动服务器，插件会自动生成配置目录：

```
plugins/KeranQuests/
    ├── config.yml        主配置
    ├── quests/           任务树（一个 yml 一棵树）
    ├── npc/              Citizens 对话
    └── data/             玩家进度（自动生成）
```

3. 进游戏执行 `/kq selftest` 检查环境（12 项自检）
4. `/kq` 打开任务菜单

## 命令

### 玩家

| 命令 | 作用 |
|---|---|
| `/kq` 或 `/kq list` | 打开任务菜单 |
| `/kq track [任务ID]` | 追踪任务 |
| `/kq abandon [任务ID]` | 放弃任务 |
| `/ka` | 快速查看当前任务 |

### 管理

| 命令 | 作用 |
|---|---|
| `/kq reload` | 热重载任务树与对话 |
| `/kq selftest` | 环境自检 |
| `/kq debug <玩家>` | 查看玩家档案 |

### 第三方 API

```bash
/kquests trigger <玩家> <key>      # 最常用：等第三方发信号
/kquests accept <玩家> <任务ID>     # 接任务（尊重前置与上限）
/kquests force <玩家> <任务ID>      # 无视前置与上限强制接
/kquests complete <玩家> <任务ID>
/kquests stage <玩家> <任务ID> <序号>
/kquests fail <玩家> <任务ID> [原因]
/kquests reset <玩家> <任务ID>
/kquests talk <玩家> <NPC名>
/kquests query <玩家> <任务ID>
```

除 `query` 外全部**静默容错**：玩家没接任务、key 不存在等情况一律不报错，可安全写入任何插件的 `console-commands`。

## 写一个任务

```yaml
id: mytree
name: '&a我的任务树'
order: 10

quests:
  hello:
    name: '&f第一次打招呼'
    type: MAIN                # MAIN=主线 / SIDE=支线
    weight: 10
    stages:
      - requirements:
          - type: region_enter
            region: my_region
            world: world
            display: '前往集合点'
```

改完 `/kq reload` 即刻生效。

完整写法见 [`src/main/resources/quests/zhulong.yml`](src/main/resources/quests/zhulong.yml) —— 这是一份**注释齐全的教学模板**，覆盖了全部功能，想找某种写法直接按关键字搜（如 `[抉择二选一]`、`[循环任务]`）。

详细文档：[docs/使用说明.md](docs/使用说明.md)

## 构建

```bash
# 需要 JDK 17+
gradle build
```

产物在 `out/KeranQuests-1.0.0.jar`。

> 编译依赖 Paper API、WorldGuard、PlaceholderAPI（由 Gradle 自动拉取）。
> Citizens / MythicMobs / Oraxen / Vault 以 `compileOnly` + 本地 `libs/` jar 方式引用，**这些 jar 不随本仓库分发**，如需重新编译请自行放入 `libs/` 目录。

## 占位符

identifier = `kq`，主要占位符：

| 占位符 | 含义 |
|---|---|
| `%kq_current%` | 当前任务名 |
| `%kq_current_stage%` | 当前阶段名 |
| `%kq_current_req_1_progress%` | 第 1 条要求进度（`3/10`） |
| `%kq_current_time_left%` | 限时任务剩余时间 |
| `%kq_active_main%` / `%kq_active_side%` | 进行中主线/支线数 |
| `%kq_tree_<树ID>_main%` | 某树主线进度 |
| `%kq_completed_total%` | 已完成任务总数 |
| `%kq_cooldown_<树ID>:<任务ID>%` | 循环任务剩余冷却 |
| `%kq_can_accept_<树ID>:<任务ID>%` | 是否可接取 |

完整清单见文档。

## 第三方联动示例

**撤离点插件：**

```yaml
console-commands:
  - 'kquests trigger {player} sl_zhulong_extracted'
```

**MythicMobs 技能：**

```yaml
Skills:
  - command{c="kquests trigger <caster.var.p> boss_down"} @self
```

## 许可证

本项目采用 [MIT License](LICENSE)。

Copyright (c) 2026 **Keran Technology**
