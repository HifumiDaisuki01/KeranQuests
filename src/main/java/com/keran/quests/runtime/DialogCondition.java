package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话条件 —— 决定「同一个 NPC 这次该说哪套话」。
 *
 * <h3>为什么需要它</h3>
 * 旧版 {@code dialogue.yml} 一个 NPC 只能配一条对话，且无条件播放：
 * 玩家第 1 次来和第 10 次来，台词一模一样。本类让一个 NPC 可以挂多条对话，
 * 从上往下取<b>第一条条件满足</b>的，从而实现「按任务进度说不同的话」。
 *
 * <h3>支持的字段（写在 {@code when:} 下）</h3>
 * <pre>
 * when:
 *   quest_active:        [ 'zhulong:main03' ]       # 正在进行中
 *   quest_completed:     [ 'zhulong:main02' ]       # 已完成
 *   quest_not_started:   [ 'zhulong:main03' ]       # 未完成且未进行
 *   choice_made:         { 'zhulong_final': 'A' }   # 某抉择组已选了指定值
 *   choice_any:          [ 'zhulong_final' ]        # 某抉择组已做过选择（不论选了啥）
 *   has_item:                                       # 持有 Oraxen 物品
 *     - { item: 'dsu_supply_crate', count: 1 }
 *   not_has_item:
 *     - { item: 'dsu_supply_crate' }
 *   permission:          'kq.vip'
 *   stage_at_least:      { 'zhulong:main03': 2 }    # 阶段序号 >= N
 *   mode: ALL            # ALL（默认，全部满足）/ ANY（满足 need 个）
 *   need: 1              # 配合 mode: ANY
 * </pre>
 *
 * <p><b>空条件 = 兜底恒真</b>：不写 {@code when} 或写空的 {@code when} 时，
 * 该条对话永远命中。建议每个 NPC 都配一条空条件的兜底对话。
 *
 * <p><b>兼容</b>：旧的「一个 NPC 一条对话、无 when」的写法照常工作 ——
 * 会被当成一条兜底对话，行为与旧版完全一致。
 */
public class DialogCondition {

    /**
     * 一个判定单元。把「类型 + 参数」打包成对象，避免用共享游标遍历
     * 多个并行列表（那样在并发或多次调用时会错位）。
     */
    private static final class Test {
        final Kind kind;
        final String a;       // 任务全名 / 抉择组 / 权限节点
        final String b;       // 期望值（choice_made 用）
        final String itemId;  // has_item / not_has_item
        final int num;        // count / 阶段序号

        Test(Kind kind, String a, String b, String itemId, int num) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.itemId = itemId;
            this.num = num;
        }
    }

    private enum Kind {
        QUEST_ACTIVE, QUEST_COMPLETED, QUEST_NOT_STARTED,
        CHOICE_MADE, CHOICE_ANY, HAS_ITEM, NOT_HAS_ITEM,
        PERMISSION, STAGE_AT_LEAST
    }

    private final List<Test> tests = new ArrayList<>();
    private String mode = "ALL";
    private int need = 1;
    private boolean empty = true;

    /**
     * 插件实例。用于访问玩家数据与 Oraxen 挂钩。
     *
     * <p>条件对象在「配置加载时」创建，那时插件实例已存在，因此可以安全注入。
     */
    private KeranQuests plugin;

    private DialogCondition() {
    }

    /** 从配置节解析。{@code sec} 为 null 或空时返回「恒真」条件。 */
    public static DialogCondition fromConfig(ConfigurationSection sec) {
        return fromConfig(sec, com.keran.quests.KeranQuests.getInstance());
    }

    /** 从配置节解析（显式传入插件实例，便于单元测试）。 */
    public static DialogCondition fromConfig(ConfigurationSection sec, KeranQuests plugin) {
        DialogCondition c = new DialogCondition();
        c.plugin = plugin;
        if (sec == null || sec.getKeys(false).isEmpty()) {
            return c;   // 空 = 恒真
        }

        for (String q : toStringList(sec.get("quest_active"))) {
            c.tests.add(new Test(Kind.QUEST_ACTIVE, q, null, null, 0));
        }
        for (String q : toStringList(sec.get("quest_completed"))) {
            c.tests.add(new Test(Kind.QUEST_COMPLETED, q, null, null, 0));
        }
        for (String q : toStringList(sec.get("quest_not_started"))) {
            c.tests.add(new Test(Kind.QUEST_NOT_STARTED, q, null, null, 0));
        }
        for (String g : toStringList(sec.get("choice_any"))) {
            c.tests.add(new Test(Kind.CHOICE_ANY, g, null, null, 0));
        }
        for (String p : toStringList(sec.get("permission"))) {
            c.tests.add(new Test(Kind.PERMISSION, p, null, null, 0));
        }

        // choice_made: { '组名': 'A', '另一个组': 'B' }
        ConfigurationSection cm = sec.getConfigurationSection("choice_made");
        if (cm != null) {
            for (String group : cm.getKeys(false)) {
                c.tests.add(new Test(Kind.CHOICE_MADE, group,
                        cm.getString(group, ""), null, 0));
            }
        }

        // has_item / not_has_item: [ {item, count} ]
        for (Map<?, ?> m : safeMapList(sec, "has_item")) {
            Object id = m.get("item");
            if (id == null) continue;
            c.tests.add(new Test(Kind.HAS_ITEM, null, null,
                    String.valueOf(id), toInt(m.get("count"), 1)));
        }
        for (Map<?, ?> m : safeMapList(sec, "not_has_item")) {
            Object id = m.get("item");
            if (id == null) continue;
            c.tests.add(new Test(Kind.NOT_HAS_ITEM, null, null,
                    String.valueOf(id), toInt(m.get("count"), 1)));
        }

        // stage_at_least: { '任务全名': 阶段序号 }
        ConfigurationSection sa = sec.getConfigurationSection("stage_at_least");
        if (sa != null) {
            for (String q : sa.getKeys(false)) {
                c.tests.add(new Test(Kind.STAGE_AT_LEAST, q, null, null,
                        sa.getInt(q, 0)));
            }
        }

        c.mode = sec.getString("mode", "ALL");
        c.need = sec.getInt("need", 1);
        c.empty = c.tests.isEmpty();   // 只写了 mode/need 也算空
        return c;
    }

    /** 是否是「恒真」条件（没配任何判定）。 */
    public boolean isEmpty() {
        return empty;
    }

    /** 条件数量（诊断用）。 */
    public int size() {
        return tests.size();
    }

    /**
     * 判定条件是否满足。
     *
     * @param player 玩家（需要在线 —— 物品与权限类判定依赖 Player 实例）
     * @return true = 命中，这条对话可用
     */
    public boolean test(Player player) {
        if (empty) return true;
        if (player == null || !player.isOnline()) return false;

        PlayerData data = plugin.getPlayerData(player);
        int ok = 0;
        for (Test t : tests) {
            if (testOne(t, player, data)) ok++;
        }
        if ("ANY".equalsIgnoreCase(mode)) {
            return ok >= Math.max(1, need);
        }
        return ok == tests.size();   // ALL：全部通过
    }

    private boolean testOne(Test t, Player player, PlayerData data) {
        switch (t.kind) {
            case QUEST_ACTIVE: {
                QuestProgress p = data.getProgress(normalize(t.a));
                return p != null && p.getState() == QuestState.ACTIVE;
            }
            case QUEST_COMPLETED:
                return data.isCompleted(normalize(t.a));

            case QUEST_NOT_STARTED: {
                if (data.isCompleted(normalize(t.a))) return false;
                QuestProgress p = data.getProgress(normalize(t.a));
                return p == null || p.getState() != QuestState.ACTIVE;
            }
            case CHOICE_MADE: {
                String chosen = data.getTreeState(treeOf(t.a), "choice:" + t.a);
                return chosen != null && chosen.equalsIgnoreCase(t.b);
            }
            case CHOICE_ANY:
                return data.getTreeState(treeOf(t.a), "choice:" + t.a) != null;

            case HAS_ITEM:
                return plugin.getOraxenHook()
                        .countItem(player, t.itemId) >= Math.max(1, t.num);

            case NOT_HAS_ITEM:
                return plugin.getOraxenHook()
                        .countItem(player, t.itemId) < Math.max(1, t.num);

            case PERMISSION:
                return player.hasPermission(t.a);

            case STAGE_AT_LEAST: {
                QuestProgress p = data.getProgress(normalize(t.a));
                return p != null && p.getStageIndex() >= t.num;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    //  工具
    // ------------------------------------------------------------------

    /** 任务引用归一化：补全树前缀（"main03" → "zhulong:main03" 由 resolveQuest 兜底）。 */
    private String normalize(String ref) {
        if (ref == null) return "";
        if (ref.contains(":")) return ref;
        // 无树前缀时，交由 TreeLoader 在解析时补全；这里先原样返回。
        return ref;
    }

    /** 从「抉择组名」推出所属树 id（"zhulong_final" → "zhulong"）。 */
    private String treeOf(String group) {
        if (group == null) return "";
        if (group.contains(":")) return group.substring(0, group.indexOf(':'));
        int i = group.indexOf('_');
        return i < 0 ? group : group.substring(0, i);
    }

    /** 把配置值转成字符串列表：兼容单个字符串与字符串列表两种写法。 */
    private static List<String> toStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else {
            String s = String.valueOf(raw).trim();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** 安全读取 map 列表（配置写错类型时不抛异常）。 */
    private static List<Map<?, ?>> safeMapList(ConfigurationSection sec, String key) {
        try {
            List<Map<?, ?>> l = sec.getMapList(key);
            return l == null ? List.of() : l;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
