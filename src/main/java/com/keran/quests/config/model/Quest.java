package com.keran.quests.config.model;

import com.keran.quests.config.model.enums.QuestType;
import com.keran.quests.config.model.enums.ResetMode;
import com.keran.quests.config.model.enums.StageVisibility;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个任务。不可变模型。
 */
public class Quest {

    private final String treeId;
    private final String id;
    private final String name;
    private final QuestType type;
    private final int weight;
    private final String icon;
    private final StageVisibility stageVisibility;
    private final Prerequisite prerequisites;
    private final List<QuestStage> stages;

    // 生命周期命令
    private final List<String> onStart;
    private final List<String> onStageComplete;
    private final List<String> onComplete;
    private final List<String> onFail;

    // 奖励
    private final double money;
    private final int exp;
    private final List<RewardItem> rewardItems;
    private final List<String> rewardCommands;

    // 循环
    private final boolean repeatable;
    private final int cooldown;
    private final boolean resetProgressOnComplete;

    // 互斥
    private final String exclusiveGroup;
    private final List<String> exclusiveWith;
    private final boolean terminatesTree;

    // 失败
    private final boolean failOnDeath;
    private final int timeLimit;
    private final int failCooldown;
    private final List<ForbiddenRegion> forbiddenRegions;
    private final int maxKills;
    private final List<String> maxKillTypes;
    private final ResetMode resetOnFail;

    /** 完成后解锁的任务树（daily01 的"解锁新任务线"） */
    private final String unlockTree;

    /**
     * 是否为隐藏任务。
     *
     * <p>隐藏任务在未满足其前置条件时，GUI 中显示为「？？？」而不是任务名，
     * 玩家接到第三方提示后才知道它的存在。前置一旦满足即正常显示。
     */
    private final boolean hidden;

    // ---- 放弃任务的限制与惩罚 ----

    /**
     * 是否允许放弃该任务。
     *
     * <p>默认 {@code true}（可放弃）。设为 {@code false} 时玩家无法放弃，
     * 用于防止「接了不做反复刷」——典型场景是需要消耗稀有道具或占用并发名额的任务。
     */
    private final boolean abandonAllowed;

    /** 放弃时是否扣血。默认 {@code true}，配合 {@link #abandonHealthCost} 使用。 */
    private final boolean abandonHealthEnabled;

    /** 放弃时扣除的生命值（点）。默认 5，即 2.5 颗心。 */
    private final double abandonHealthCost;

    /** 放弃时额外执行的命令（支持 %player% 占位符，可配 delay 语法）。 */
    private final List<String> abandonCommands;

    private Quest(String treeId, String id, String name, QuestType type, int weight, String icon,
                  StageVisibility stageVisibility, Prerequisite prerequisites, List<QuestStage> stages,
                  List<String> onStart, List<String> onStageComplete, List<String> onComplete,
                  List<String> onFail, double money, int exp, List<RewardItem> rewardItems,
                  List<String> rewardCommands, boolean repeatable, int cooldown,
                  boolean resetProgressOnComplete, String exclusiveGroup, List<String> exclusiveWith,
                  boolean terminatesTree, boolean failOnDeath, int timeLimit, int failCooldown,
                  List<ForbiddenRegion> forbiddenRegions, int maxKills, List<String> maxKillTypes,
                  ResetMode resetOnFail, String unlockTree, boolean hidden,
                  boolean abandonAllowed, boolean abandonHealthEnabled, double abandonHealthCost,
                  List<String> abandonCommands) {
        this.treeId = treeId;
        this.id = id;
        this.name = name;
        this.type = type;
        this.weight = weight;
        this.icon = icon;
        this.stageVisibility = stageVisibility;
        this.prerequisites = prerequisites;
        this.stages = stages;
        this.onStart = onStart;
        this.onStageComplete = onStageComplete;
        this.onComplete = onComplete;
        this.onFail = onFail;
        this.money = money;
        this.exp = exp;
        this.rewardItems = rewardItems;
        this.rewardCommands = rewardCommands;
        this.repeatable = repeatable;
        this.cooldown = cooldown;
        this.resetProgressOnComplete = resetProgressOnComplete;
        this.exclusiveGroup = exclusiveGroup;
        this.exclusiveWith = exclusiveWith;
        this.terminatesTree = terminatesTree;
        this.failOnDeath = failOnDeath;
        this.timeLimit = timeLimit;
        this.failCooldown = failCooldown;
        this.forbiddenRegions = forbiddenRegions;
        this.maxKills = maxKills;
        this.maxKillTypes = maxKillTypes;
        this.resetOnFail = resetOnFail;
        this.unlockTree = unlockTree;
        this.hidden = hidden;
        this.abandonAllowed = abandonAllowed;
        this.abandonHealthEnabled = abandonHealthEnabled;
        this.abandonHealthCost = abandonHealthCost;
        this.abandonCommands = abandonCommands;
    }

    public static Quest fromConfig(String treeId, String id, ConfigurationSection sec) {
        if (sec == null) return null;
        QuestType type = QuestType.parse(sec.getString("type", "SIDE"));
        String name = sec.getString("name", id);
        int weight = sec.getInt("weight", 0);
        String icon = sec.getString("icon", null);
        StageVisibility sv = StageVisibility.parse(sec.getString("stage_visibility", "SEQUENTIAL"));

        Prerequisite pre = Prerequisite.fromConfig(sec.getConfigurationSection("prerequisites"));

        List<QuestStage> stages = new ArrayList<>();
        List<?> rawStages = sec.getList("stages");
        if (rawStages != null) {
            for (Object o : rawStages) {
                ConfigurationSection ss = toSection(o);
                QuestStage st = QuestStage.fromConfig(ss);
                if (st != null) stages.add(st);
            }
        }

        List<String> onStart = new ArrayList<>(sec.getStringList("on_start"));
        List<String> onStageComplete = new ArrayList<>(sec.getStringList("on_stage_complete"));
        List<String> onComplete = new ArrayList<>(sec.getStringList("on_complete"));
        List<String> onFail = new ArrayList<>(sec.getStringList("on_fail"));

        // 奖励
        double money = 0;
        int exp = 0;
        List<RewardItem> rewardItems = new ArrayList<>();
        List<String> rewardCommands = new ArrayList<>();
        ConfigurationSection rw = sec.getConfigurationSection("rewards");
        if (rw != null) {
            money = rw.getDouble("money", 0);
            exp = rw.getInt("exp", 0);
            rewardCommands.addAll(rw.getStringList("commands"));
            List<Map<?, ?>> items = rw.getMapList("items");
            for (Map<?, ?> m : items) {
                Object iid = m.get("id");
                if (iid == null) continue;
                Object amt = m.get("amount");
                int a = 1;
                try {
                    a = amt == null ? 1 : Integer.parseInt(String.valueOf(amt));
                } catch (Exception ignored) {
                }
                rewardItems.add(new RewardItem(String.valueOf(iid), a));
            }
        }

        boolean repeatable = sec.getBoolean("repeatable", false);
        int cooldown = sec.getInt("cooldown", 0);
        boolean resetOnComplete = sec.getBoolean("reset_progress_on_complete", true);

        String exclusiveGroup = sec.getString("exclusive_group", null);
        List<String> exclusiveWith = new ArrayList<>(sec.getStringList("exclusive_with"));
        boolean terminatesTree = sec.getBoolean("terminates_tree", false);

        // 失败配置
        boolean failOnDeath = false;
        int timeLimit = 0;
        int failCooldown = 0;
        int maxKills = -1;
        List<String> maxKillTypes = new ArrayList<>();
        ResetMode resetOnFail = ResetMode.WHOLE_QUEST;
        List<ForbiddenRegion> forbidden = new ArrayList<>();

        ConfigurationSection fl = sec.getConfigurationSection("failure");
        if (fl != null) {
            failOnDeath = fl.getBoolean("on_death", false);
            timeLimit = fl.getInt("time_limit", 0);
            failCooldown = fl.getInt("fail_cooldown", 0);
            resetOnFail = ResetMode.parse(fl.getString("reset_on_fail", "WHOLE_QUEST"));
            List<Map<?, ?>> fr = fl.getMapList("forbidden_regions");
            for (Map<?, ?> m : fr) {
                Object r = m.get("region");
                if (r == null) continue;
                Object w = m.get("world");
                forbidden.add(new ForbiddenRegion(String.valueOf(r),
                        w == null ? null : String.valueOf(w)));
            }
            ConfigurationSection mk = fl.getConfigurationSection("max_kills");
            if (mk != null) {
                maxKills = mk.getInt("count", -1);
                maxKillTypes = new ArrayList<>(mk.getStringList("types"));
            }
        }

        String unlockTree = sec.getString("unlock_tree", null);
        // 隐藏标记：hidden: true 或 hide: true 均可
        boolean hidden = sec.getBoolean("hidden", false) || sec.getBoolean("hide", false);

        // ---- 放弃任务的限制与惩罚 ----
        //
        // 两种写法都支持：
        //   简写  abandon: false                      → 禁止放弃
        //   详写  abandon:
        //           allowed: false
        //           health: 5            （扣血量，0 = 不扣血）
        //           commands: [...]      （额外执行的命令）
        //
        // 默认：allowed = true（可放弃）、health = 5（扣 5 点生命值 = 2.5 颗心）。
        // 用 getConfigurationSection 判类型，避免简写布尔值被当成配置节读取时抛异常。
        boolean abandonAllowed = true;
        boolean abandonHealthEnabled = true;
        double abandonHealthCost = 5.0D;
        List<String> abandonCommands = new ArrayList<>();

        Object abandonRaw = sec.get("abandon");
        if (abandonRaw instanceof Boolean b) {
            // 简写形式：abandon: false 直接禁止放弃
            abandonAllowed = b;
        } else {
            ConfigurationSection as = sec.getConfigurationSection("abandon");
            if (as != null) {
                abandonAllowed = as.getBoolean("allowed", true);
                abandonHealthCost = as.getDouble("health", 5.0D);
                // health: 0 表示不扣血
                abandonHealthEnabled = abandonHealthCost > 0;
                abandonCommands = new ArrayList<>(as.getStringList("commands"));
            }
        }

        return new Quest(treeId, id, name, type, weight, icon, sv, pre, stages,
                onStart, onStageComplete, onComplete, onFail, money, exp, rewardItems,
                rewardCommands, repeatable, cooldown, resetOnComplete, exclusiveGroup,
                exclusiveWith, terminatesTree, failOnDeath, timeLimit, failCooldown,
                forbidden, maxKills, maxKillTypes, resetOnFail, unlockTree, hidden,
                abandonAllowed, abandonHealthEnabled, abandonHealthCost, abandonCommands);
    }

    private static ConfigurationSection toSection(Object obj) {
        if (obj instanceof ConfigurationSection cs) return cs;
        if (obj instanceof Map<?, ?> map) {
            org.bukkit.configuration.MemoryConfiguration mem =
                    new org.bukkit.configuration.MemoryConfiguration();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                mem.set(String.valueOf(e.getKey()), e.getValue());
            }
            return mem;
        }
        return null;
    }

    /** 完整 ID，形如 "zhulong:ch01"。 */
    public String getFullId() {
        return treeId + ":" + id;
    }

    // ---------------- getters ----------------

    public String getTreeId() {
        return treeId;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public QuestType getType() {
        return type;
    }

    public int getWeight() {
        return weight;
    }

    public String getIcon() {
        return icon;
    }

    public StageVisibility getStageVisibility() {
        return stageVisibility;
    }

    public Prerequisite getPrerequisites() {
        return prerequisites;
    }

    public List<QuestStage> getStages() {
        return stages;
    }

    public int getStageCount() {
        return stages.size();
    }

    public List<String> getOnStart() {
        return onStart;
    }

    public List<String> getOnStageComplete() {
        return onStageComplete;
    }

    public List<String> getOnComplete() {
        return onComplete;
    }

    public List<String> getOnFail() {
        return onFail;
    }

    public double getMoney() {
        return money;
    }

    public int getExp() {
        return exp;
    }

    public List<RewardItem> getRewardItems() {
        return rewardItems;
    }

    public List<String> getRewardCommands() {
        return rewardCommands;
    }

    public boolean isRepeatable() {
        return repeatable;
    }

    public int getCooldown() {
        return cooldown;
    }

    public boolean isResetProgressOnComplete() {
        return resetProgressOnComplete;
    }

    public String getExclusiveGroup() {
        return exclusiveGroup;
    }

    public List<String> getExclusiveWith() {
        return exclusiveWith;
    }

    public boolean isTerminatesTree() {
        return terminatesTree;
    }

    public boolean isFailOnDeath() {
        return failOnDeath;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public int getFailCooldown() {
        return failCooldown;
    }

    public List<ForbiddenRegion> getForbiddenRegions() {
        return forbiddenRegions;
    }

    public int getMaxKills() {
        return maxKills;
    }

    public List<String> getMaxKillTypes() {
        return maxKillTypes;
    }

    public ResetMode getResetOnFail() {
        return resetOnFail;
    }

    public String getUnlockTree() {
        return unlockTree;
    }

    /** 是否为隐藏任务（前置未满足时显示为 ？？？）。 */
    public boolean isHidden() {
        return hidden;
    }

    /** 是否允许玩家放弃该任务（默认 true）。 */
    public boolean isAbandonAllowed() {
        return abandonAllowed;
    }

    /** 放弃时是否扣血（由 abandon.health > 0 决定）。 */
    public boolean isAbandonHealthEnabled() {
        return abandonHealthEnabled;
    }

    /** 放弃时扣除的生命值点数（默认 5，即 2.5 颗心）。 */
    public double getAbandonHealthCost() {
        return abandonHealthCost;
    }

    /** 放弃时额外执行的命令列表。 */
    public List<String> getAbandonCommands() {
        return abandonCommands;
    }

    /** 奖励物品。 */
    public static class RewardItem {
        private final String id;
        private final int amount;

        public RewardItem(String id, int amount) {
            this.id = id;
            this.amount = Math.max(1, amount);
        }

        /** 去掉可能存在的 "oraxen:" 前缀。 */
        public String getOraxenId() {
            return id.startsWith("oraxen:") ? id.substring("oraxen:".length()) : id;
        }

        public String getRawId() {
            return id;
        }

        public int getAmount() {
            return amount;
        }
    }

    /** 禁止进入的区域。 */
    public static class ForbiddenRegion {
        private final String region;
        private final String world;

        public ForbiddenRegion(String region, String world) {
            this.region = region;
            this.world = world;
        }

        public String getRegion() {
            return region;
        }

        public String getWorld() {
            return world;
        }
    }
}
