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
    /**
     * 自定义 GUI 布局（可空）。非空时，任务列表 GUI 按此布局摆放图标，
     * 不再自动排列、不再翻页。
     * <p>
     * 每个元素代表一行，字符串按空白切分成列；每列取值：
     * <ul>
     *   <li>{@code 0} —— 空格占位（背景板，不可点）</li>
     *   <li>任务 ID（短名或全名均可）—— 该格放此任务</li>
     * </ul>
     * 仅作用于第 1~5 排（槽位 0-44），第 6 排始终留给返回/关闭等固定功能键。
     */
    private final List<String> layout;
    /** 自定义空格占位符材质（Oraxen ID 或原版 material 名），null 表示用默认背景板。 */
    private final String layoutEmptyIcon;

    /**
     * 本树来自哪个文件（如 {@code zhulong.yml}）。由 {@code QuestTreeLoader} 回填，
     * 仅用于诊断 —— 树 ID 冲突时能明确告诉运维是哪两个文件撞了
     * （树 ID 取自文件内的 {@code id:} 字段，与文件名无关，两者不一致是常见误配）。
     */
    private String sourceFile;

    /** 回填来源文件名（诊断用）。 */
    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    /** 来源文件名，未回填时返回 {@code "未知"}。 */
    public String getSourceFile() {
        return sourceFile == null ? "未知" : sourceFile;
    }

    public QuestTree(String id, String name, String description, String icon, int order,
                     boolean enabled, Prerequisite prerequisites, int mainQuestLimit,
                     int sideQuestLimit, boolean lockedByDefault, Map<String, Quest> quests) {
        this(id, name, description, icon, order, enabled, prerequisites, mainQuestLimit,
                sideQuestLimit, lockedByDefault, quests, null, null);
    }

    public QuestTree(String id, String name, String description, String icon, int order,
                     boolean enabled, Prerequisite prerequisites, int mainQuestLimit,
                     int sideQuestLimit, boolean lockedByDefault, Map<String, Quest> quests,
                     List<String> layout, String layoutEmptyIcon) {
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
        this.layout = layout;
        this.layoutEmptyIcon = layoutEmptyIcon;
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

        // 自定义 GUI 布局（可选）。支持两种写法：
        //   layout: ["0 0 0", "0 main01 0"]      —— 字符串列表（推荐）
        //   layout:                              —— 也接受单行字符串，按 \n 切分
        //     - "0 0 0"
        List<String> layout = null;
        if (root.isList("layout")) {
            List<String> raw = root.getStringList("layout");
            if (!raw.isEmpty()) layout = new ArrayList<>(raw);
        } else if (root.isString("layout")) {
            String one = root.getString("layout", "");
            if (one != null && !one.isBlank()) {
                layout = new ArrayList<>();
                for (String line : one.split("\\r?\\n")) layout.add(line);
            }
        }
        String layoutEmptyIcon = root.getString("layout_empty_icon", null);

        return new QuestTree(id, name, description, icon, order, enabled, pre,
                mainLimit, sideLimit, lockedByDefault, quests, layout, layoutEmptyIcon);
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

    /** 自定义 GUI 布局原始行（可能为 null，表示未配置）。 */
    public List<String> getLayout() {
        return layout;
    }

    /** 是否配置了自定义布局。 */
    public boolean hasLayout() {
        return layout != null && !layout.isEmpty();
    }

    /** 空格占位符的材质（Oraxen ID 或原版 material 名）；null 表示用默认背景板。 */
    public String getLayoutEmptyIcon() {
        return layoutEmptyIcon;
    }

    /**
     * 把 layout 解析成「槽位 → 任务」的映射，供 GUI 直接使用。
     * <p>
     * 规则：
     * <ul>
     *   <li>只解析第 1~5 排（槽位 0-44）。超出的行直接忽略。</li>
     *   <li>每行按空白切分，超过 9 列的部分忽略。</li>
     *   <li>{@code 0} 或 {@code -} 视为空格占位，记入返回值的 emptySlots。</li>
     *   <li>其余值按任务 ID 解析，支持短名（main01）与全名（zhulong:main01）。</li>
     *   <li>解析不到的任务 ID → 该格当空格处理，并通过 problems 返回原因。</li>
     *   <li>同一个任务被布局写了多次 → 只认第一处，重复处当空格，problems 记录。</li>
     * </ul>
     *
     * @param problems 若非 null，解析过程中的告警会追加到这里（供控制台打印）
     * @return 解析结果
     */
    public LayoutResult parseLayout(List<String> problems) {
        LayoutResult r = new LayoutResult();
        if (!hasLayout()) return r;

        java.util.Set<String> placedQuests = new java.util.HashSet<>();
        int maxRows = 5;                       // 第 6 排留给功能键
        int maxCols = 9;

        for (int row = 0; row < layout.size(); row++) {
            if (row >= maxRows) {
                if (problems != null) {
                    problems.add("树 " + id + " 的 layout 第 " + (row + 1)
                            + " 行被忽略：最多只能写 " + maxRows + " 行（第 6 排是返回/关闭等功能键）");
                }
                continue;
            }
            String line = layout.get(row);
            if (line == null) continue;
            String[] cols = line.trim().split("\\s+");
            if (cols.length > maxCols && problems != null) {
                problems.add("树 " + id + " 的 layout 第 " + (row + 1)
                        + " 行有 " + cols.length + " 列，超出的部分已忽略（每行最多 " + maxCols + " 列）");
            }
            for (int col = 0; col < Math.min(cols.length, maxCols); col++) {
                String token = cols[col];
                if (token.isEmpty()) continue;
                int slot = row * 9 + col;

                // 空格占位
                if (token.equals("0") || token.equals("-")) {
                    r.emptySlots.add(slot);
                    r.declaredSlots.add(slot);
                    continue;
                }

                // 任务：先按原样查，再按短名查
                Quest q = quests.get(token);
                if (q == null && token.contains(":")) {
                    String shortId = token.substring(token.indexOf(':') + 1);
                    q = quests.get(shortId);
                }
                if (q == null) {
                    if (problems != null) {
                        problems.add("树 " + id + " 的 layout 第 " + (row + 1) + " 行第 " + (col + 1)
                                + " 列写的任务 \"" + token + "\" 不存在，已按空格处理");
                    }
                    r.emptySlots.add(slot);
                    r.declaredSlots.add(slot);
                    continue;
                }
                if (placedQuests.contains(q.getId())) {
                    if (problems != null) {
                        problems.add("树 " + id + " 的 layout 里任务 \"" + q.getId()
                                + "\" 出现了多次，只保留第一次（槽位 " + r.questToSlot.get(q.getId()) + "）");
                    }
                    r.emptySlots.add(slot);
                    r.declaredSlots.add(slot);
                    continue;
                }
                placedQuests.add(q.getId());
                r.slotToQuest.put(slot, q);
                r.questToSlot.put(q.getId(), slot);
                r.declaredSlots.add(slot);
            }
        }

        // 未被布局覆盖到的任务：补到「布局完全没写到」的槽位。
        // ★ 不占用用户显式写的 0 / - —— 那是「我要留白」的明确意图，补位不得侵占。
        List<Integer> freeSlots = new ArrayList<>();
        for (int s = 0; s < maxRows * maxCols; s++) {
            if (!r.declaredSlots.contains(s)) freeSlots.add(s);
        }
        int fi = 0;
        for (Quest q : getQuestsByWeight()) {
            if (placedQuests.contains(q.getId())) continue;
            if (fi >= freeSlots.size()) {
                if (problems != null) {
                    problems.add("树 " + id + " 的 layout 空间不足，任务 \"" + q.getId()
                            + "\" 无法显示（前 5 排已被布局占满，建议把它写进 layout）");
                }
                continue;
            }
            int slot = freeSlots.get(fi++);
            r.slotToQuest.put(slot, q);
            r.questToSlot.put(q.getId(), slot);
        }

        return r;
    }

    /** {@link #parseLayout} 的解析结果。 */
    public static class LayoutResult {
        /** 槽位 → 任务 */
        public final Map<Integer, Quest> slotToQuest = new LinkedHashMap<>();
        /** 任务 ID → 槽位 */
        public final Map<String, Integer> questToSlot = new LinkedHashMap<>();
        /** 空格占位（需要画背景板的格子） */
        public final List<Integer> emptySlots = new ArrayList<>();
        /** 布局显式声明过的槽位（含空格占位）。自动补位不得占用这些格子。 */
        public final java.util.Set<Integer> declaredSlots = new java.util.HashSet<>();

        public boolean isEmpty() {
            return slotToQuest.isEmpty() && emptySlots.isEmpty();
        }
    }
}
