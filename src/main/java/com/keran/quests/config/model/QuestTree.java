package com.keran.quests.config.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务树。一个 yml 文件对应一棵树。不可变模型。
 */
public class QuestTree {

    private final String id;
    private final String name;
    private final String description;
    private final String icon;
    private final int order;
    private final boolean enabled;
    private final Prerequisite prerequisites;
    private final int mainQuestLimit;
    private final int sideQuestLimit;
    /**
     * 是否默认锁定。为 true 时，玩家必须先由其他任务的 {@code unlock_tree: <本树 id>}
     * 解锁，才能接取本树的任务。默认 false（开放）。
     */
    private final boolean lockedByDefault;
    /** 保序的任务表：id -> Quest */
    private final Map<String, Quest> quests;

    public QuestTree(String id, String name, String description, String icon, int order,
                     boolean enabled, Prerequisite prerequisites, int mainQuestLimit,
                     int sideQuestLimit, boolean lockedByDefault, Map<String, Quest> quests) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.order = order;
        this.enabled = enabled;
        this.prerequisites = prerequisites;
        this.mainQuestLimit = mainQuestLimit;
        this.sideQuestLimit = sideQuestLimit;
        this.lockedByDefault = lockedByDefault;
        this.quests = quests;
    }

    /**
     * 从 yml 解析整棵树。
     *
     * @param fileId 文件名（不含扩展名），作为树的默认 id
     * @param root   根配置节
     * @return 解析失败返回 null
     */
    public static QuestTree fromConfig(String fileId, ConfigurationSection root) {
        if (root == null) return null;

        String id = root.getString("id", fileId);
        if (id == null || id.isBlank()) id = fileId;
        String name = root.getString("name", id);
        String description = root.getString("description", "");
        String icon = root.getString("icon", null);
        int order = root.getInt("order", 100);
        boolean enabled = root.getBoolean("enabled", true);

        Prerequisite pre = Prerequisite.fromConfig(root.getConfigurationSection("prerequisites"));
        int mainLimit = root.getInt("main_quest_limit", 1);
        int sideLimit = root.getInt("side_quest_limit", 3);
        // 默认锁定：locked: true 时需被其他任务的 unlock_tree 解锁
        boolean lockedByDefault = root.getBoolean("locked", false);

        Map<String, Quest> quests = new LinkedHashMap<>();
        ConfigurationSection qs = root.getConfigurationSection("quests");
        if (qs != null) {
            for (String key : qs.getKeys(false)) {
                ConfigurationSection qsec = qs.getConfigurationSection(key);
                if (qsec == null) continue;
                Quest q = Quest.fromConfig(id, key, qsec);
                if (q != null) quests.put(key, q);
            }
        }

        return new QuestTree(id, name, description, icon, order, enabled, pre,
                mainLimit, sideLimit, lockedByDefault, quests);
    }

    // ---------------- 查询 ----------------

    public Quest getQuest(String questId) {
        return quests.get(questId);
    }

    public Collection<Quest> getQuests() {
        return quests.values();
    }

    /** 按权重降序返回全部任务（GUI 排序用）。 */
    public List<Quest> getQuestsByWeight() {
        List<Quest> list = new ArrayList<>(quests.values());
        list.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));
        return list;
    }

    /** 主线任务总数（互斥组去重）。 */
    public int countMainTotal() {
        return countTotalByType(com.keran.quests.config.model.enums.QuestType.MAIN);
    }

    /** 支线任务总数（互斥组去重）。 */
    public int countSideTotal() {
        return countTotalByType(com.keran.quests.config.model.enums.QuestType.SIDE);
    }

    /**
     * 统计某类型的任务总数，**互斥组只算 1 个**。
     * 这样 03A/03B 互斥时分母不会算成 2。
     */
    private int countTotalByType(com.keran.quests.config.model.enums.QuestType type) {
        int count = 0;
        java.util.Set<String> seenGroups = new java.util.HashSet<>();
        for (Quest q : quests.values()) {
            if (q.getType() != type) continue;
            String g = q.getExclusiveGroup();
            if (g != null && !g.isBlank()) {
                if (seenGroups.contains(g)) continue;   // 同组只计一次
                seenGroups.add(g);
            }
            count++;
        }
        return count;
    }

    /**
     * 统计某类型的任务总数，**互斥组只算 1 个**（按互斥组）。
     * 收集某类型的全部任务，同互斥组只保留权重最高的那个（用于分母计算）。
     */
    public List<Quest> getDedupedByType(com.keran.quests.config.model.enums.QuestType type) {
        List<Quest> out = new ArrayList<>();
        Map<String, Quest> groupBest = new LinkedHashMap<>();
        for (Quest q : getQuestsByWeight()) {
            if (q.getType() != type) continue;
            String g = q.getExclusiveGroup();
            if (g == null || g.isBlank()) {
                out.add(q);
            } else if (!groupBest.containsKey(g)) {
                groupBest.put(g, q);
            }
        }
        out.addAll(groupBest.values());
        return out;
    }

    // ---------------- getters ----------------

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getIcon() {
        return icon;
    }

    public int getOrder() {
        return order;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Prerequisite getPrerequisites() {
        return prerequisites;
    }

    public int getMainQuestLimit() {
        return mainQuestLimit;
    }

    public int getSideQuestLimit() {
        return sideQuestLimit;
    }

    /** 本树是否默认锁定（需靠 unlock_tree 解锁）。 */
    public boolean isLockedByDefault() {
        return lockedByDefault;
    }
}
