package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Prerequisite;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.QuestTree;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.config.model.enums.QuestType;
import com.keran.quests.config.model.enums.RequirementType;
import com.keran.quests.config.model.enums.StageMode;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import com.keran.quests.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 任务管理器 —— 整个插件的核心。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>可接性判定（前置 / 并发上限 / 冷却 / 互斥 / 整树终结）</li>
 *   <li>接取 / 推进阶段 / 完成 / 失败 / 重置</li>
 *   <li>要求进度更新（供各监听器与指令调用）</li>
 *   <li>生命周期命令执行（含延迟）</li>
 *   <li>奖励发放</li>
 * </ul>
 */
public class QuestManager {

    private final KeranQuests plugin;

    public QuestManager(KeranQuests plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    //  可接性判定
    // ==================================================================

    /**
     * 任务对某玩家的解锁状态。三态用于 GUI 区分"显示前置"与"显示？？？"。
     */
    public enum UnlockStatus {
        /** 已解锁，可接取 */
        UNLOCKED,
        /** 未解锁，但前置本身可见 —— 可以告诉玩家缺什么 */
        LOCKED_KNOWN,
        /** 未解锁，且前置本身也是锁的 —— 显示 ？？？ */
        LOCKED_HIDDEN
    }

    /**
     * 判定任务是否可接取（综合前置 / 上限 / 冷却 / 互斥 / 整树终结）。
     *
     * @return null 表示可接；否则返回不可接的原因文本
     */
    public String canAccept(Player player, Quest quest) {
        PlayerData data = plugin.getPlayerData(player);

        // 1. 整树是否已终结
        if (data.isTreeTerminated(quest.getTreeId())) {
            QuestTree tree = plugin.getTreeLoader().getTree(quest.getTreeId());
            String treeName = tree == null ? quest.getTreeId() : tree.getName();
            return Text.color(plugin.prefixed("tree_terminated", "tree_name", treeName));
        }

        // 2. 当前状态
        QuestProgress p = data.getProgress(quest.getFullId());
        if (p != null) {
            if (p.getState() == QuestState.ACTIVE) {
                return Text.color("&c该任务正在进行中。");
            }
            if (p.getState() == QuestState.COMPLETED && !quest.isRepeatable()) {
                return Text.color("&c该任务已完成。");
            }
        }

        // 3. 冷却
        int cd = data.getCooldownRemaining(quest.getFullId(), "COOLDOWN");
        if (cd > 0) {
            return Text.color(plugin.prefixed("cannot_accept_cooldown", "time", TimeUtil.format(cd)));
        }
        int failCd = data.getCooldownRemaining(quest.getFullId(), "FAIL_LOCK");
        if (failCd > 0) {
            return Text.color(plugin.prefixed("cannot_accept_cooldown", "time", TimeUtil.format(failCd)));
        }
        // 放弃锁：刚放弃过本任务，冷却结束前不能再接（防「接了放弃」反复刷命令）
        int abandonCd = data.getCooldownRemaining(quest.getFullId(), "ABANDON_LOCK");
        if (abandonCd > 0) {
            // 用 prefixedOr 带兜底：服务器上的 config.yml 是首次安装时释放的旧副本，
            // 插件升级后新增的消息键不会自动补进去，用 prefixed 会返回空串，
            // 玩家就会收到"一条空白提示"——这个坑在实测中真实踩到了。
            return Text.color(plugin.prefixedOr("cannot_accept_abandon_cooldown",
                    "&c你刚放弃过这个任务，请 &f{time} &c后再来接取。",
                    "time", TimeUtil.format(abandonCd)));
        }

        // 4. 任务树解锁门槛（由某任务的 unlock_tree 解锁；未声明门槛的树默认开放）
        if (!isTreeUnlocked(data, quest)) {
            return Text.color(plugin.prefixedOr("tree_locked", "&c该任务线尚未解锁。"));
        }

        // 5. 前置（放在并发上限之前：前置不满足时优先提示"未解锁"，语义更准确）
        if (!checkPrerequisites(player, quest.getPrerequisites())) {
            return Text.color(plugin.prefixed("cannot_accept_locked"));
        }

        // 6. 互斥
        if (!checkExclusive(player, quest)) {
            return Text.color(plugin.prefixed("cannot_accept_exclusive"));
        }

        // 7. 并发上限（拥有 kq.bypass.limit 权限的玩家不受限）
        if (!player.hasPermission("kq.bypass.limit")) {
            QuestTree tree = plugin.getTreeLoader().getTree(quest.getTreeId());
            int limit = quest.getType() == QuestType.MAIN
                    ? (tree == null ? plugin.getConfig().getInt("limits.main_quest_limit", 1)
                    : tree.getMainQuestLimit())
                    : (tree == null ? plugin.getConfig().getInt("limits.side_quest_limit", 3)
                    : tree.getSideQuestLimit());
            int active = countActive(data, quest.getType());
            if (active >= limit) {
                String typeName = quest.getType() == QuestType.MAIN ? "主线" : "支线";
                return Text.color(plugin.prefixed("cannot_accept_limit",
                        "type", typeName, "limit", String.valueOf(limit)));
            }
        }

        return null;
    }

    /**
     * 任务树是否对玩家开放。
     *
     * <p>规则：任务树 yml 中若声明了 {@code locked: true}，则默认关闭，
     * 需要由其他任务的 {@code unlock_tree: <treeId>} 解锁后才可接取；
     * 未声明该字段的树一律视为开放（向后兼容）。
     */
    public boolean isTreeUnlocked(PlayerData data, Quest quest) {
        String treeId = quest.getTreeId();
        if (treeId == null) return true;
        QuestTree tree = plugin.getTreeLoader().getTree(treeId);
        if (tree == null) return true;

        // ① 显式锁定：树配了 locked: true，必须靠 unlock_tree 解锁
        if (tree.isLockedByDefault()
                && !"true".equalsIgnoreCase(data.getTreeState(treeId, "unlocked"))) {
            return false;
        }

        // ② 前置任务：树配了 prerequisites，必须满足才放行。
        //
        // 【为什么必须在这里判】此前只判了 ①，导致「配了前置但没配 locked: true」
        // 的任务树出现严重漏洞：GUI 的任务树图标按前置正确显示「未解锁」，
        // 但 canAccept / getUnlockStatus 只认 locked 标记、认为树已解锁，
        // 于是玩家能直接接取未解锁任务树里的任务、任务也显示成「可接取」。
        // 两处判定必须一致，否则 UI 与逻辑会各说各话。
        Prerequisite treePre = tree.getPrerequisites();
        if (treePre != null && !treePre.isEmpty()) {
            Player owner = plugin.getServer().getPlayer(data.getUuid());
            // 玩家不在线时无法校验权限/背包类前置，此时保守判定为「未解锁」，
            // 避免因为拿不到 Player 就误放行（该方法的所有调用点都在玩家在线场景）。
            if (owner == null) return false;
            if (!checkPrerequisites(owner, treePre)) return false;
        }
        return true;
    }

    /** 统计玩家某类型的进行中任务数。 */
    public int countActive(PlayerData data, QuestType type) {
        int n = 0;
        for (java.util.Map.Entry<String, QuestProgress> e : data.getAllProgress().entrySet()) {
            if (e.getValue().getState() != QuestState.ACTIVE) continue;
            Quest q = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (q != null && q.getType() == type) n++;
        }
        return n;
    }

    /** 判定互斥。 */
    private boolean checkExclusive(Player player, Quest quest) {
        PlayerData data = plugin.getPlayerData(player);
        String group = quest.getExclusiveGroup();

        // 组内是否已有别的任务被接取/完成
        if (group != null && !group.isBlank()) {
            String taken = data.getTreeState(quest.getTreeId(), "exclusive:" + group);
            if (taken != null && !taken.equalsIgnoreCase(quest.getId())) {
                return false;
            }
        }
        // 显式互斥列表
        for (String other : quest.getExclusiveWith()) {
            QuestProgress op = data.getProgress(normalize(quest.getTreeId(), other));
            if (op != null && (op.getState() == QuestState.ACTIVE
                    || op.getState() == QuestState.COMPLETED)) {
                return false;
            }
        }
        return true;
    }

    /** 把短名补全成 "tree:quest" 形式。 */
    private String normalize(String treeId, String ref) {
        return ref.contains(":") ? ref : treeId + ":" + ref;
    }

    // ==================================================================
    //  前置判定
    // ==================================================================

    /** 判定前置是否全部满足。 */
    public boolean checkPrerequisites(Player player, Prerequisite pre) {
        if (pre == null || pre.isEmpty()) return true;
        PlayerData data = plugin.getPlayerData(player);

        List<Boolean> results = new ArrayList<>();

        // 必须完成的任务
        if (!pre.getQuestCompletedAll().isEmpty()) {
            for (String ref : pre.getQuestCompletedAll()) {
                results.add(data.isCompleted(normalizeRef(ref)));
            }
        }
        // 任选 N 个任务
        if (!pre.getQuestCompletedAny().isEmpty()) {
            int done = 0;
            for (String ref : pre.getQuestCompletedAny()) {
                if (data.isCompleted(normalizeRef(ref))) done++;
            }
            results.add(done >= pre.getQuestCompletedAnyNeed());
        }
        // 整树完成（数据源：树内全部任务完成）
        for (String treeId : pre.getTreeCompletedAll()) {
            results.add(isTreeCompleted(data, treeId));
        }
        if (!pre.getTreeCompletedAny().isEmpty()) {
            int done = 0;
            for (String treeId : pre.getTreeCompletedAny()) {
                if (isTreeCompleted(data, treeId)) done++;
            }
            results.add(done >= pre.getTreeCompletedAnyNeed());
        }
        // Oraxen 物品
        for (Prerequisite.ItemReq req : pre.getOraxenItems()) {
            int have = plugin.getOraxenHook().countItem(player, req.getItem());
            results.add(have >= req.getCount());
        }
        // 权限
        if (pre.getPermission() != null && !pre.getPermission().isBlank()) {
            results.add(player.hasPermission(pre.getPermission()));
        }
        if (!pre.getPermissionsAny().isEmpty()) {
            boolean any = false;
            for (String perm : pre.getPermissionsAny()) {
                if (player.hasPermission(perm)) {
                    any = true;
                    break;
                }
            }
            results.add(any);
        }
        // 抉择
        if (pre.getQuestChoice() != null && !pre.getQuestChoice().isBlank()) {
            // 抉择值存在「抉择所属任务树」下，用 quest_choice 自身推出树 id 即可。
            String treeId = questTreeOf(pre.getQuestChoice());
            String chosen = data.getTreeState(treeId, "choice:" + pre.getQuestChoice());
            results.add(chosen != null && chosen.equalsIgnoreCase(pre.getChoiceValue()));
        }

        if (results.isEmpty()) return true;

        if ("ANY".equalsIgnoreCase(pre.getMode())) {
            int need = Math.max(1, pre.getNeed());
            int ok = 0;
            for (boolean b : results) if (b) ok++;
            return ok >= need;
        }
        // ALL
        for (boolean b : results) if (!b) return false;
        return true;
    }

    /** 从 "zhulong:ch03a" 或 "zhulong_ch03_choice" 推出所属树 id。 */
    private String questTreeOf(String ref) {
        if (ref == null) return "";
        if (ref.contains(":")) return ref.substring(0, ref.indexOf(':'));
        // 约定：树名作为前缀，如 "zhulong_ch03_choice"
        int i = ref.indexOf('_');
        return i < 0 ? ref : ref.substring(0, i);
    }

    /** 判断整棵树是否全部完成。 */
    public boolean isTreeCompleted(PlayerData data, String treeId) {
        QuestTree tree = plugin.getTreeLoader().getTree(treeId);
        if (tree == null) return false;
        // 用去重后的任务列表（互斥组只算一个，避免要求两个互斥任务都完成）
        List<Quest> mains = tree.getDedupedByType(QuestType.MAIN);
        List<Quest> sides = tree.getDedupedByType(QuestType.SIDE);
        if (mains.isEmpty() && sides.isEmpty()) return false;
        for (Quest q : mains) {
            if (!data.isCompleted(q.getFullId())) return false;
        }
        for (Quest q : sides) {
            if (!data.isCompleted(q.getFullId())) return false;
        }
        return true;
    }

    /**
     * 判定任务的解锁状态（三态）。用于 GUI 决定显示任务名还是 ？？？。
     */
    public UnlockStatus getUnlockStatus(Player player, Quest quest) {
        Prerequisite pre = quest.getPrerequisites();
        // 已接取 / 已完成的任务永远可见（不受 hidden 影响）
        PlayerData data = plugin.getPlayerData(player);
        if (data.isCompleted(quest.getFullId())) return UnlockStatus.UNLOCKED;

        // 整个任务树被锁定时，同样显示为未解锁
        if (!isTreeUnlocked(data, quest)) return UnlockStatus.LOCKED_HIDDEN;

        // 无前置时：隐藏任务仍显示为 LOCKED_HIDDEN，普通任务直接 UNLOCKED
        if (pre == null || pre.isEmpty()) {
            return quest.isHidden() ? UnlockStatus.LOCKED_HIDDEN : UnlockStatus.UNLOCKED;
        }
        if (checkPrerequisites(player, pre)) return UnlockStatus.UNLOCKED;

        // 隐藏任务：前置未满足 → 直接 ？？？
        if (quest.isHidden()) return UnlockStatus.LOCKED_HIDDEN;

        // 前置是否"可见"：递归看前置任务本身是否已解锁
        for (String ref : pre.getQuestCompletedAll()) {
            Quest parent = plugin.getTreeLoader().resolveQuest(normalizeRef(ref));
            if (parent == null) continue;
            if (!isQuestVisible(player, parent, 0)) {
                return UnlockStatus.LOCKED_HIDDEN;
            }
        }
        for (String ref : pre.getQuestCompletedAny()) {
            Quest parent = plugin.getTreeLoader().resolveQuest(normalizeRef(ref));
            if (parent == null) continue;
            if (!isQuestVisible(player, parent, 0)) {
                return UnlockStatus.LOCKED_HIDDEN;
            }
        }
        return UnlockStatus.LOCKED_KNOWN;
    }

    /** 任务是否"可见"（自己或自己的前置链上没被隐藏）。递归深度上限防环。 */
    private boolean isQuestVisible(Player player, Quest quest, int depth) {
        if (depth > 8) return true;
        if (!quest.isHidden()) return true;
        Prerequisite pre = quest.getPrerequisites();
        if (pre == null || pre.isEmpty()) return false;   // 隐藏且无前置 → 不可见
        if (checkPrerequisites(player, pre)) return true;
        for (String ref : pre.getQuestCompletedAll()) {
            Quest parent = plugin.getTreeLoader().resolveQuest(normalizeRef(ref));
            if (parent == null) continue;
            if (!isQuestVisible(player, parent, depth + 1)) return false;
        }
        for (String ref : pre.getQuestCompletedAny()) {
            Quest parent = plugin.getTreeLoader().resolveQuest(normalizeRef(ref));
            if (parent == null) continue;
            if (!isQuestVisible(player, parent, depth + 1)) return false;
        }
        return false;
    }

    private String normalizeRef(String ref) {
        if (ref == null) return null;
        if (ref.contains(":")) return ref;
        Quest q = plugin.getTreeLoader().resolveQuest(ref);
        return q == null ? ref : q.getFullId();
    }

    // ==================================================================
    //  接取
    // ==================================================================

    /**
     * 接取任务。
     *
     * @return null 表示成功；否则返回失败原因
     */
    public String accept(Player player, Quest quest) {
        String err = canAccept(player, quest);
        if (err != null) return err;

        PlayerData data = plugin.getPlayerData(player);
        QuestProgress p = data.getOrCreateProgress(quest.getFullId());

        // 循环任务重接：清空旧进度
        if (quest.isRepeatable() && quest.isResetProgressOnComplete()) {
            p.clearAllProgress();
        }
        if (p.getState() == QuestState.FAILED) {
            p.clearAllProgress();
        }

        p.setState(QuestState.ACTIVE);
        p.setStageIndex(0);
        p.setStartedAt(System.currentTimeMillis());
        p.setFinishedAt(0);
        p.setFailReason(null);
        p.clearFromStage(0);
        data.markDirty();

        // 互斥组落档
        if (quest.getExclusiveGroup() != null && !quest.getExclusiveGroup().isBlank()) {
            data.setTreeState(quest.getTreeId(), "exclusive:" + quest.getExclusiveGroup(), quest.getId());
        }

        // 追踪（若玩家还没追踪任何任务，自动追踪这个）
        if (data.getTrackedQuest() == null) {
            data.setTrackedQuest(quest.getFullId());
        }

        // 生命周期命令
        runCommands(player, quest.getOnStart(), quest, null, null, null);

        Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                + plugin.prefixed("quest_accepted", "quest_name", quest.getName()));

        // 立即检查阶段（例如"进入区域"这类可能已经满足）
        checkStageCompletion(player, quest, p, false);

        plugin.getPlayerDataStore().save(data);
        if (plugin.getConfig().getBoolean("debug.log_completions", true)) {
            plugin.getLogger().info("玩家 " + player.getName() + " 接取任务 " + quest.getFullId());
        }
        return null;
    }

    // ==================================================================
    //  推进 / 完成
    // ==================================================================

    /**
     * 检查当前阶段是否满足完成条件，满足则推进。
     *
     * @param silent 静默模式（不播报阶段完成）
     * @return 是否有推进
     */
    public boolean checkStageCompletion(Player player, Quest quest, QuestProgress p, boolean silent) {
        if (p.getState() != QuestState.ACTIVE) return false;

        int stageIdx = p.getStageIndex();
        List<QuestStage> stages = quest.getStages();
        if (stageIdx >= stages.size()) {
            // 全部阶段完成 → 完成任务
            complete(player, quest);
            return true;
        }

        QuestStage stage = stages.get(stageIdx);
        if (!isStageSatisfied(player, quest, p, stage)) return false;

        // 防重复：RegionRunner 每秒都会调用本方法，若该阶段已经处理过
        // （尤其是抉择节点会停留在原地），直接返回，避免每秒刷屏 + 每秒弹 GUI。
        if (p.isStageHandled(stageIdx)) return false;

        // 阶段完成
        p.markStageHandled(stageIdx);
        p.markStageCompleted(stageIdx);
        runCommands(player, stage.getCommandsOnComplete(), quest, stage, null, null);
        runCommands(player, quest.getOnStageComplete(), quest, stage, null, null);

        if (!silent) {
            Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                    + plugin.prefixed("stage_completed", "stage_name", stage.getName()));
        }

        // 抉择节点：暂停在这里等玩家选
        if (stage.isChoice()) {
            // 记录已到抉择点，等待 ChoiceGui 回填
            p.setState(QuestState.ACTIVE);
            plugin.getPlayerDataStore().save(plugin.getPlayerData(player));
            plugin.getChoiceManager().openChoice(player, quest, stage);
            return true;
        }

        p.advanceStage();

        // 递归检查下一阶段是否已满足（例如连续两个区域要求）
        checkStageCompletion(player, quest, p, silent);
        plugin.getPlayerDataStore().save(plugin.getPlayerData(player));
        return true;
    }

    /** 判定单个阶段是否满足。 */
    public boolean isStageSatisfied(Player player, Quest quest, QuestProgress p, QuestStage stage) {
        List<Requirement> reqs = stage.getRequirements();
        if (reqs.isEmpty()) return true;   // 无要求 = 直接完成

        if (stage.getMode() == StageMode.ANY) {
            int ok = 0;
            for (int i = 0; i < reqs.size(); i++) {
                if (isRequirementSatisfied(player, quest, p, stage, i)) ok++;
            }
            return ok >= stage.getNeed();
        }
        for (int i = 0; i < reqs.size(); i++) {
            if (!isRequirementSatisfied(player, quest, p, stage, i)) return false;
        }
        return true;
    }

    /** 判定单条要求是否满足。 */
    public boolean isRequirementSatisfied(Player player, Quest quest, QuestProgress p,
                                          QuestStage stage, int reqIndex) {
        Requirement req = stage.getRequirements().get(reqIndex);
        int stageIdx = p.getStageIndex();
        int progress = p.getRequirementProgress(stageIdx, reqIndex);

        switch (req.getType()) {
            case MM_KILL:
            case ORAXEN_ITEM:
            case PLAYER_KILL:
                return progress >= req.target();
            case REGION_STAY:
                // 进度由 RegionRunner 累加秒数
                return progress >= req.getSeconds();
            case REGION_ENTER:
                return progress >= 1;
            case REGION_FORBIDDEN:
                return false;   // 作为失败条件处理，永不"满足"
            case CUSTOM_TRIGGER:
                return progress >= 1;
            case NPC_TALK:
                return progress >= 1;
            case PERMISSION:
                return player.hasPermission(req.getPermission());
            default:
                return false;
        }
    }

    /** 完成整个任务：发奖励 + 执行命令 + 处理循环/终结。 */
    public void complete(Player player, Quest quest) {
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress p = data.getOrCreateProgress(quest.getFullId());
        p.setState(QuestState.COMPLETED);
        p.setStageIndex(quest.getStageCount());
        p.setFinishedAt(System.currentTimeMillis());
        data.markDirty();

        // 生命周期命令
        runCommands(player, quest.getOnComplete(), quest, null, null, null);

        // 奖励
        giveRewards(player, quest);

        Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                + plugin.prefixed("quest_complete_msg", "quest_name", quest.getName()));

        // 循环任务：写冷却
        if (quest.isRepeatable() && quest.getCooldown() > 0) {
            data.setCooldownUntil(quest.getFullId(), "COOLDOWN",
                    System.currentTimeMillis() + quest.getCooldown() * 1000L);
        }

        // 整树终结
        if (quest.isTerminatesTree()) {
            data.terminateTree(quest.getTreeId());
        }

        // 解锁新任务树：把目标树的 unlocked 标记写进玩家数据，
        // 之后 getUnlockStatus / canAccept 会据此放行（见 isTreeUnlocked）。
        String unlockTree = quest.getUnlockTree();
        if (unlockTree != null && !unlockTree.isBlank()) {
            data.setTreeState(unlockTree, "unlocked", "true");
            QuestTree target = plugin.getTreeLoader().getTree(unlockTree);
            String targetName = target == null ? unlockTree : target.getName();
            Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                    + plugin.prefixedOr("tree_unlocked", "&a已解锁新的任务线：&f{tree_name}",
                    "tree_name", targetName));
            plugin.getLogger().info("玩家 " + player.getName() + " 解锁任务树 " + unlockTree);
        }

        if (plugin.getConfig().getBoolean("debug.log_completions", true)) {
            plugin.getLogger().info("玩家 " + player.getName() + " 完成任务 " + quest.getFullId());
        }

        plugin.getPlayerDataStore().save(data);
    }

    /** 发放奖励。 */
    public void giveRewards(Player player, Quest quest) {
        QuestProgress p = plugin.getPlayerData(player).getProgress(quest.getFullId());

        // 金钱
        if (quest.getMoney() > 0) {
            plugin.getVaultHook().deposit(player, quest.getMoney());
        }
        // 经验
        if (quest.getExp() > 0) {
            player.giveExp(quest.getExp());
        }
        // 物品
        for (Quest.RewardItem item : quest.getRewardItems()) {
            boolean ok = plugin.getOraxenHook().giveItem(player, item.getOraxenId(), item.getAmount());
            if (!ok) {
                plugin.getLogger().warning("发放奖励物品失败：" + item.getRawId()
                        + "（玩家 " + player.getName() + "）");
            }
        }
        // 命令
        runCommands(player, quest.getRewardCommands(), quest, null, null, null);

        // 消耗前置里声明要消耗的物品（任务完成时）
        consumePrerequisiteItems(player, quest);
    }

    /** 处理前置里 consume: true 的物品。 */
    private void consumePrerequisiteItems(Player player, Quest quest) {
        Prerequisite pre = quest.getPrerequisites();
        if (pre == null) return;
        for (Prerequisite.ItemReq req : pre.getOraxenItems()) {
            if (!req.isConsume()) continue;
            plugin.getOraxenHook().removeItem(player, req.getItem(), req.getCount());
        }
    }

    /**
     * 解析失败原因文本。
     *
     * <p>支持两种写法：
     * <ul>
     *   <li>{@code "&timeout"} —— 引用 config.yml 的 {@code failure_reasons.timeout}</li>
     *   <li>任意普通文本 —— 原样使用（失败原因里含动态内容时用这种）</li>
     * </ul>
     */
    public String resolveFailReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return plugin.getConfig().getString("failure_reasons.manual", "未知原因");
        }
        if (reason.startsWith("&")) {
            String key = reason.substring(1);
            String configured = plugin.getConfig().getString("failure_reasons." + key, null);
            if (configured != null) return com.keran.quests.util.Text.strip(configured);
        }
        return reason;
    }

    // ==================================================================
    //  失败 / 重置 / 放弃
    // ==================================================================

    /**
     * 任务失败。
     *
     * @param reason 失败原因文本（会写入 {@code {fail_reason}} 占位符）；
     *               也可传 {@code "&failure_reasons.xxx"} 形式引用配置中的标准文案。
     */
    public void fail(Player player, Quest quest, String reason) {
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress p = data.getOrCreateProgress(quest.getFullId());
        if (p.getState() != QuestState.ACTIVE) return;

        String finalReason = resolveFailReason(reason);

        p.setState(QuestState.FAILED);
        p.setFailReason(finalReason);
        p.setFinishedAt(System.currentTimeMillis());

        runCommands(player, quest.getOnFail(), quest, null, null, finalReason);

        Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                + plugin.prefixed("quest_failed", "quest_name", quest.getName(),
                "fail_reason", finalReason));

        // 失败冷却
        if (quest.getFailCooldown() > 0) {
            data.setCooldownUntil(quest.getFullId(), "FAIL_LOCK",
                    System.currentTimeMillis() + quest.getFailCooldown() * 1000L);
        }

        // 重置粒度
        if (quest.getResetOnFail() == com.keran.quests.config.model.enums.ResetMode.WHOLE_QUEST) {
            p.clearAllProgress();
        } else {
            p.clearFromStage(p.getStageIndex());
        }

        // 放弃追踪
        if (quest.getFullId().equals(data.getTrackedQuest())) {
            data.setTrackedQuest(null);
        }

        plugin.getPlayerDataStore().save(data);

        if (plugin.getConfig().getBoolean("debug.log_completions", true)) {
            plugin.getLogger().info("玩家 " + player.getName() + " 任务失败 " + quest.getFullId()
                    + "：" + reason);
        }
    }

    /** 玩家主动放弃任务。 */
    public String abandon(Player player, Quest quest) {
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress p = data.getProgress(quest.getFullId());
        if (p == null || p.getState() != QuestState.ACTIVE) {
            return Text.color("&c该任务不在进行中。");
        }

        // 禁止放弃的任务直接拦下（用于防止接了不做反复刷）
        if (!quest.isAbandonAllowed()) {
            return Text.color(plugin.prefixedOr("quest_abandon_denied",
                    "&c该任务不允许放弃。"));
        }

        p.setState(QuestState.AVAILABLE);
        p.clearAllProgress();
        data.markDirty();
        if (quest.getFullId().equals(data.getTrackedQuest())) {
            data.setTrackedQuest(null);
        }

        // 放弃锁：写入冷却，冷却结束前不能再次接取本任务
        if (quest.getAbandonCooldown() > 0) {
            data.setCooldownUntil(quest.getFullId(), "ABANDON_LOCK",
                    System.currentTimeMillis() + quest.getAbandonCooldown() * 1000L);
        }

        // 放弃提示：任务里自定义了就用自定义的，没写则回退到 config.yml 默认文案
        String selfMsg = quest.getAbandonMessage();
        if (selfMsg != null && !selfMsg.isBlank()) {
            String rendered = com.keran.quests.util.Text.color(
                    com.keran.quests.util.Text.replace(selfMsg,
                            "quest", quest.getName(), "quest_name", quest.getName(),
                            "player", player.getName()));
            rendered = rendered.replace("%quest%", quest.getName())
                    .replace("%player%", player.getName());
            Text.send(player, plugin.getConfig().getString("messages.prefix", "") + rendered);
        } else {
            Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                    + plugin.prefixedOr("quest_abandoned",
                    "&7已放弃任务：&f{quest_name}", "quest_name", quest.getName()));
        }

        // ---- 放弃惩罚（完全由命令决定，插件不预设任何惩罚）----
        applyAbandonPenalty(player, quest);

        plugin.getPlayerDataStore().save(data);
        return null;
    }

    /**
     * 执行放弃任务的惩罚。
     *
     * <p>惩罚方式<b>完全由任务配置里的 {@code abandon.commands} 决定</b>，插件不内置任何惩罚。
     * 扣血只是其中一种可能的写法，例如：
     * <pre>
     * abandon:
     *   commands:
     *     - "effect give %player% minecraft:instant_damage 1 0"   # 扣血
     *     - "eco take %player% 100"                                # 扣钱
     *     - "say %player% 放弃了一个任务"
     * </pre>
     * 也支持 {@code "delay <ticks> | <command>"} 延迟执行语法。
     */
    private void applyAbandonPenalty(Player player, Quest quest) {
        for (String raw : quest.getAbandonCommands()) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = raw.replace("%player%", player.getName())
                    .replace("{player}", player.getName());
            // 支持 "delay <ticks> | <command>" 延迟语法，与其它钩子保持一致
            if (cmd.startsWith("delay ")) {
                int bar = cmd.indexOf('|');
                if (bar > 0) {
                    int ticks;
                    try {
                        ticks = Integer.parseInt(cmd.substring(6, bar).trim());
                    } catch (NumberFormatException e) {
                        ticks = 0;
                    }
                    String finalCmd = cmd.substring(bar + 1).trim();
                    if (ticks > 0) {
                        finalCmd = finalCmd.replace("%player%", player.getName());
                        String fc = finalCmd;
                        plugin.getServer().getScheduler().runTaskLater(plugin,
                                () -> dispatchCommand(fc, player), ticks);
                        continue;
                    }
                    cmd = finalCmd;
                }
            }
            dispatchCommand(cmd, player);
        }
    }

    /** 以控制台身份执行命令（找不到玩家时用玩家身份兜底，保证至少能跑）。 */
    private void dispatchCommand(String cmd, Player player) {
        if (cmd == null || cmd.isBlank()) return;
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), c)) {
            if (player != null && player.isOnline()) player.performCommand(c);
        }
    }

    /** 去掉多余小数位：5.0 -> 5，2.5 -> 2.5。 */
    private String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    /** 重置任务（管理指令）。 */
    public void reset(Player player, Quest quest) {
        PlayerData data = plugin.getPlayerData(player);
        data.removeProgress(quest.getFullId());
        data.clearCooldown(quest.getFullId(), "COOLDOWN");
        data.clearCooldown(quest.getFullId(), "FAIL_LOCK");
        data.clearCooldown(quest.getFullId(), "ABANDON_LOCK");
        if (quest.getFullId().equals(data.getTrackedQuest())) {
            data.setTrackedQuest(null);
        }
        plugin.getPlayerDataStore().save(data);
    }

    // ==================================================================
    //  要求进度更新 API（供监听器与指令调用）
    // ==================================================================

    /**
     * 推进某类要求的进度。
     *
     * @param stageFilter 仅推进满足条件的阶段（返回 true 表示该阶段适用）
     * @param delta       增量
     * @return 是否有任何进度变化
     */
    public boolean advanceRequirement(Player player, RequirementType type, int delta,
                                      RequirementFilter stageFilter) {
        PlayerData data = plugin.getPlayerData(player);
        boolean changed = false;
        for (java.util.Map.Entry<String, QuestProgress> e : data.getAllProgress().entrySet()) {
            QuestProgress p = e.getValue();
            if (p.getState() != QuestState.ACTIVE) continue;
            Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (quest == null) continue;
            List<QuestStage> stages = quest.getStages();
            int si = p.getStageIndex();
            if (si >= stages.size()) continue;
            QuestStage stage = stages.get(si);
            if (stageFilter != null && !stageFilter.test(quest, stage)) continue;

            for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
                Requirement req = stage.getRequirements().get(ri);
                if (req.getType() != type) continue;
                if (stageFilter != null && !stageFilter.testRequirement(quest, stage, req)) continue;
                int cur = p.getRequirementProgress(si, ri);
                if (cur >= req.target()) continue;   // 已满，不重复加
                int nv = Math.min(req.target(), cur + delta);
                p.setRequirementProgress(si, ri, nv);
                changed = true;
            }
        }
        if (changed) {
            data.markDirty();
            // 重新检查阶段完成（可能在下一 tick 更安全，但这里直接调用也可）
            for (java.util.Map.Entry<String, QuestProgress> e : new ArrayList<>(data.getAllProgress().entrySet())) {
                if (e.getValue().getState() != QuestState.ACTIVE) continue;
                Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
                if (quest != null) checkStageCompletion(player, quest, e.getValue(), false);
            }
        }
        return changed;
    }

    /**
     * 触发自定义 key（CUSTOM_TRIGGER）。
     *
     * @return 是否有任何任务因此推进
     */
    public boolean fireTrigger(Player player, String key) {
        if (key == null || key.isBlank()) return false;
        PlayerData data = plugin.getPlayerData(player);
        boolean changed = false;

        for (java.util.Map.Entry<String, QuestProgress> e : new ArrayList<>(data.getAllProgress().entrySet())) {
            QuestProgress p = e.getValue();
            if (p.getState() != QuestState.ACTIVE) continue;
            Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (quest == null) continue;
            List<QuestStage> stages = quest.getStages();
            int si = p.getStageIndex();
            if (si >= stages.size()) continue;
            QuestStage stage = stages.get(si);

            for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
                Requirement req = stage.getRequirements().get(ri);
                if (req.getType() != RequirementType.CUSTOM_TRIGGER) continue;
                if (!key.equalsIgnoreCase(req.getKey())) continue;
                p.setRequirementProgress(si, ri, 1);
                p.addTriggerCount(key);
                changed = true;
            }
        }

        if (changed) {
            data.markDirty();
            for (java.util.Map.Entry<String, QuestProgress> e : new ArrayList<>(data.getAllProgress().entrySet())) {
                if (e.getValue().getState() != QuestState.ACTIVE) continue;
                Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
                if (quest != null) checkStageCompletion(player, quest, e.getValue(), false);
            }
            plugin.getPlayerDataStore().save(data);
        }
        return changed;
    }

    /** 触发 NPC 对话要求。 */
    public boolean fireNpcTalk(Player player, String npcName) {
        if (npcName == null) return false;
        PlayerData data = plugin.getPlayerData(player);
        boolean changed = false;
        for (java.util.Map.Entry<String, QuestProgress> e : new ArrayList<>(data.getAllProgress().entrySet())) {
            QuestProgress p = e.getValue();
            if (p.getState() != QuestState.ACTIVE) continue;
            Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (quest == null) continue;
            int si = p.getStageIndex();
            if (si >= quest.getStages().size()) continue;
            QuestStage stage = quest.getStages().get(si);
            for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
                Requirement req = stage.getRequirements().get(ri);
                if (req.getType() != RequirementType.NPC_TALK) continue;
                if (!npcName.equalsIgnoreCase(req.getNpcName())) continue;
                p.setRequirementProgress(si, ri, 1);
                changed = true;
            }
        }
        if (changed) {
            data.markDirty();
            for (java.util.Map.Entry<String, QuestProgress> e : new ArrayList<>(data.getAllProgress().entrySet())) {
                if (e.getValue().getState() != QuestState.ACTIVE) continue;
                Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
                if (quest != null) checkStageCompletion(player, quest, e.getValue(), false);
            }
            plugin.getPlayerDataStore().save(data);
        }
        return changed;
    }

    /** 要求过滤器。 */
    public interface RequirementFilter {
        /** 阶段级过滤。 */
        default boolean test(Quest quest, QuestStage stage) {
            return true;
        }

        /** 要求级过滤（比阶段级更细）。 */
        default boolean testRequirement(Quest quest, QuestStage stage, Requirement req) {
            return true;
        }
    }

    // ==================================================================
    //  命令执行（含延迟）
    // ==================================================================

    /**
     * 执行一组命令，支持 {@code "delay <ticks> | <command>"} 语法。
     */
    public void runCommands(Player player, List<String> commands, Quest quest,
                            QuestStage stage, String extra, String failReason) {
        if (commands == null || commands.isEmpty()) return;
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String line = raw.trim();

            int delay = 0;
            String body = line;
            if (line.toLowerCase().startsWith("delay ")) {
                int bar = line.indexOf('|');
                if (bar > 0) {
                    String dpart = line.substring(6, bar).trim();
                    try {
                        delay = Integer.parseInt(dpart);
                    } catch (NumberFormatException ignored) {
                    }
                    body = line.substring(bar + 1).trim();
                }
            }
            final String cmd = fill(body, player, quest, stage, failReason);
            if (delay > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> dispatch(cmd), delay);
            } else {
                dispatch(cmd);
            }
        }
    }

    private void dispatch(String cmd) {
        if (cmd == null || cmd.isBlank()) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable t) {
            plugin.getLogger().warning("执行命令失败：" + cmd + " -> " + t.getMessage());
        }
    }

    /** 填充命令里的占位符。 */
    private String fill(String raw, Player player, Quest quest, QuestStage stage, String failReason) {
        String out = raw;
        out = out.replace("{player}", player == null ? "CONSOLE" : player.getName());
        out = out.replace("{uuid}", player == null ? "" : player.getUniqueId().toString());
        if (quest != null) {
            out = out.replace("{quest_id}", quest.getId());
            out = out.replace("{quest_name}", Text.strip(quest.getName()));
            out = out.replace("{tree_id}", quest.getTreeId());
        }
        if (stage != null) {
            out = out.replace("{stage_id}", stage.getId());
            out = out.replace("{stage_name}", Text.strip(stage.getName()));
        }
        if (failReason != null) {
            out = out.replace("{fail_reason}", failReason);
        }
        return out;
    }

    // ==================================================================
    //  查询 API（占位符 / GUI 共用）
    // ==================================================================

    /**
     * 取玩家"当前任务" —— 权重最高的进行中任务。
     *
     * <p>若玩家手动追踪了某任务，优先返回它。
     */
    public Quest getCurrentQuest(Player player) {
        PlayerData data = plugin.getPlayerData(player);

        // 优先追踪
        String tracked = data.getTrackedQuest();
        if (tracked != null) {
            QuestProgress tp = data.getProgress(tracked);
            if (tp != null && tp.getState() == QuestState.ACTIVE) {
                Quest tq = plugin.getTreeLoader().resolveQuest(tracked);
                if (tq != null) return tq;
            }
        }

        // 否则取权重最高的进行中任务
        Quest best = null;
        for (java.util.Map.Entry<String, QuestProgress> e : data.getAllProgress().entrySet()) {
            if (e.getValue().getState() != QuestState.ACTIVE) continue;
            Quest q = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (q == null) continue;
            if (best == null || q.getWeight() > best.getWeight()) best = q;
        }
        return best;
    }

    public QuestProgress getCurrentProgress(Player player) {
        Quest q = getCurrentQuest(player);
        if (q == null) return null;
        return plugin.getPlayerData(player).getProgress(q.getFullId());
    }

    /** 取玩家全部进行中的任务，按权重降序。 */
    public List<Quest> getActiveQuests(Player player) {
        PlayerData data = plugin.getPlayerData(player);
        List<Quest> out = new ArrayList<>();
        for (java.util.Map.Entry<String, QuestProgress> e : data.getAllProgress().entrySet()) {
            if (e.getValue().getState() != QuestState.ACTIVE) continue;
            Quest q = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (q != null) out.add(q);
        }
        out.sort(Comparator.comparingInt(Quest::getWeight).reversed());
        return out;
    }

    /** 统计玩家在某树中某类型已完成的任务数（互斥去重）。 */
    public int countCompleted(PlayerData data, QuestTree tree, QuestType type) {
        int n = 0;
        for (Quest q : tree.getDedupedByType(type)) {
            // 互斥组内任意一个完成即算完成
            if (isGroupSatisfied(data, q)) n++;
        }
        return n;
    }

    /** 互斥组：组内任一完成即视为该"名额"已完成。 */
    private boolean isGroupSatisfied(PlayerData data, Quest q) {
        if (data.isCompleted(q.getFullId())) return true;
        String g = q.getExclusiveGroup();
        if (g == null || g.isBlank()) return false;
        QuestTree tree = plugin.getTreeLoader().getTree(q.getTreeId());
        if (tree == null) return false;
        for (Quest other : tree.getQuests()) {
            if (!g.equals(other.getExclusiveGroup())) continue;
            if (data.isCompleted(other.getFullId())) return true;
        }
        return false;
    }

    /** 树的完成状态文本。 */
    public String getTreeStateText(PlayerData data, QuestTree tree) {
        if (data.isTreeTerminated(tree.getId())) return "已终结";
        if (isTreeCompleted(data, tree.getId())) return "已完成";
        int main = countCompleted(data, tree, QuestType.MAIN);
        if (main > 0 || countActive(data, QuestType.MAIN) > 0) return "进行中";
        return "未开始";
    }
}
