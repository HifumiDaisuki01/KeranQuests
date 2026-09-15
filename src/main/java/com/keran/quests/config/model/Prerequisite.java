package com.keran.quests.config.model;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前置条件集合。不可变模型。
 *
 * <p>支持：
 * <ul>
 *   <li>{@code quest_completed_all} / {@code quest_completed_any}（+need）</li>
 *   <li>{@code tree_completed_all} / {@code tree_completed_any}（+need）</li>
 *   <li>{@code oraxen_items}（含 consume）</li>
 *   <li>{@code permission} / {@code permissions_any}</li>
 *   <li>{@code quest_choice}（抉择分支，如 ch03a 要求 choice_value=A）</li>
 * </ul>
 * 以及组合模式 {@code mode: ALL|ANY} + {@code need}。
 */
public class Prerequisite {

    /** 必须完成的任务（完整 ID） */
    private final List<String> questCompletedAll;
    /** 任选 N 个完成的任务 */
    private final List<String> questCompletedAny;
    private final int questCompletedAnyNeed;
    /** 必须完成的整树 */
    private final List<String> treeCompletedAll;
    /** 任选 N 个完成的树 */
    private final List<String> treeCompletedAny;
    private final int treeCompletedAnyNeed;
    /** 需要的 Oraxen 物品 */
    private final List<ItemReq> oraxenItems;
    /** 需要的权限 */
    private final String permission;
    private final List<String> permissionsAny;
    /** 抉择判定 */
    private final String questChoice;
    private final String choiceValue;
    /** 组合模式 */
    private final String mode;         // ALL / ANY
    private final int need;

    /** 是否为空（无任何前置） */
    private final boolean empty;

    private Prerequisite(List<String> questCompletedAll, List<String> questCompletedAny,
                         int questCompletedAnyNeed, List<String> treeCompletedAll,
                         List<String> treeCompletedAny, int treeCompletedAnyNeed,
                         List<ItemReq> oraxenItems, String permission, List<String> permissionsAny,
                         String questChoice, String choiceValue, String mode, int need) {
        this.questCompletedAll = questCompletedAll;
        this.questCompletedAny = questCompletedAny;
        this.questCompletedAnyNeed = questCompletedAnyNeed;
        this.treeCompletedAll = treeCompletedAll;
        this.treeCompletedAny = treeCompletedAny;
        this.treeCompletedAnyNeed = treeCompletedAnyNeed;
        this.oraxenItems = oraxenItems;
        this.permission = permission;
        this.permissionsAny = permissionsAny;
        this.questChoice = questChoice;
        this.choiceValue = choiceValue;
        this.mode = mode == null ? "ALL" : mode;
        this.need = need;
        this.empty = questCompletedAll.isEmpty() && questCompletedAny.isEmpty()
                && treeCompletedAll.isEmpty() && treeCompletedAny.isEmpty()
                && oraxenItems.isEmpty() && (permission == null || permission.isBlank())
                && permissionsAny.isEmpty() && (questChoice == null || questChoice.isBlank());
    }

    public static Prerequisite empty() {
        return new Prerequisite(new ArrayList<>(), new ArrayList<>(), 0,
                new ArrayList<>(), new ArrayList<>(), 0, new ArrayList<>(),
                null, new ArrayList<>(), null, null, "ALL", 1);
    }

    public static Prerequisite fromConfig(ConfigurationSection sec) {
        if (sec == null) return empty();

        List<String> qAll = new ArrayList<>(sec.getStringList("quest_completed_all"));
        List<String> tAll = new ArrayList<>(sec.getStringList("tree_completed_all"));
        String perm = sec.getString("permission", null);
        List<String> permsAny = new ArrayList<>(sec.getStringList("permissions_any"));
        String qChoice = sec.getString("quest_choice", null);
        String cValue = sec.getString("choice_value", null);
        String mode = sec.getString("mode", "ALL");
        int need = sec.getInt("need", 1);

        // quest_completed_any（两种写法：直接列表 或 带 quests/need 的子节）
        List<String> qAny = new ArrayList<>();
        int qAnyNeed = 1;
        ConfigurationSection qAnySec = sec.getConfigurationSection("quest_completed_any");
        if (qAnySec != null) {
            qAny = new ArrayList<>(qAnySec.getStringList("quests"));
            qAnyNeed = qAnySec.getInt("need", 1);
        } else {
            List<?> raw = sec.getList("quest_completed_any");
            if (raw != null) {
                for (Object o : raw) if (o != null) qAny.add(String.valueOf(o));
                qAnyNeed = sec.getInt("quest_completed_any_need", 1);
            }
        }

        // tree_completed_any
        List<String> tAny = new ArrayList<>();
        int tAnyNeed = 1;
        ConfigurationSection tAnySec = sec.getConfigurationSection("tree_completed_any");
        if (tAnySec != null) {
            tAny = new ArrayList<>(tAnySec.getStringList("trees"));
            tAnyNeed = tAnySec.getInt("need", 1);
        }

        // oraxen_items
        List<ItemReq> items = new ArrayList<>();
        List<Map<?, ?>> rawItems = sec.getMapList("oraxen_items");
        for (Map<?, ?> m : rawItems) {
            Object id = m.get("item");
            if (id == null) continue;
            Object cnt = m.get("count");
            Object cons = m.get("consume");
            int c = cnt == null ? 1 : parseInt(cnt, 1);
            boolean cs = cons != null && Boolean.parseBoolean(String.valueOf(cons));
            items.add(new ItemReq(String.valueOf(id), c, cs));
        }

        return new Prerequisite(qAll, qAny, qAnyNeed, tAll, tAny, tAnyNeed, items,
                perm, permsAny, qChoice, cValue, mode, need);
    }

    private static int parseInt(Object o, int def) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------------- getters ----------------

    public boolean isEmpty() {
        return empty;
    }

    public List<String> getQuestCompletedAll() {
        return questCompletedAll;
    }

    public List<String> getQuestCompletedAny() {
        return questCompletedAny;
    }

    public int getQuestCompletedAnyNeed() {
        return questCompletedAnyNeed <= 0 ? 1 : questCompletedAnyNeed;
    }

    public List<String> getTreeCompletedAll() {
        return treeCompletedAll;
    }

    public List<String> getTreeCompletedAny() {
        return treeCompletedAny;
    }

    public int getTreeCompletedAnyNeed() {
        return treeCompletedAnyNeed <= 0 ? 1 : treeCompletedAnyNeed;
    }

    public List<ItemReq> getOraxenItems() {
        return oraxenItems;
    }

    public String getPermission() {
        return permission;
    }

    public List<String> getPermissionsAny() {
        return permissionsAny;
    }

    public String getQuestChoice() {
        return questChoice;
    }

    public String getChoiceValue() {
        return choiceValue;
    }

    public String getMode() {
        return mode;
    }

    public int getNeed() {
        return need;
    }

    /** 收集本前置里提到的所有「直接前置任务 ID」，用于递归判可见性。 */
    public List<String> allReferencedQuests() {
        List<String> out = new ArrayList<>();
        out.addAll(questCompletedAll);
        out.addAll(questCompletedAny);
        return out;
    }

    /** Oraxen 物品需求。 */
    public static class ItemReq {
        private final String item;
        private final int count;
        private final boolean consume;

        public ItemReq(String item, int count, boolean consume) {
            this.item = item;
            this.count = Math.max(1, count);
            this.consume = consume;
        }

        public String getItem() {
            return item;
        }

        public int getCount() {
            return count;
        }

        public boolean isConsume() {
            return consume;
        }
    }
}
