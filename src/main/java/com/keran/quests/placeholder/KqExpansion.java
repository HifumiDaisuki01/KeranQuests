package com.keran.quests.placeholder;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.QuestTree;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.QuestType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import com.keran.quests.util.TimeUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PlaceholderAPI 扩展，identifier = {@code kq}。
 *
 * <h3>占位符一览</h3>
 * <table border="1">
 *   <tr><th>占位符</th><th>含义</th></tr>
 *   <tr><td>{@code %kq_current%}</td><td>当前任务名（追踪优先，否则权重最高）</td></tr>
 *   <tr><td>{@code %kq_current_id%}</td><td>当前任务 ID</td></tr>
 *   <tr><td>{@code %kq_current_type%}</td><td>主线 / 支线</td></tr>
 *   <tr><td>{@code %kq_current_stage%}</td><td>当前阶段名</td></tr>
 *   <tr><td>{@code %kq_current_stage_index%}</td><td>当前阶段序号（1 起）</td></tr>
 *   <tr><td>{@code %kq_current_stage_total%}</td><td>总阶段数</td></tr>
 *   <tr><td>{@code %kq_current_req_1%}</td><td>第 1 条要求的描述</td></tr>
 *   <tr><td>{@code %kq_current_req_1_progress%}</td><td>第 1 条要求的进度（形如 3/10）</td></tr>
 *   <tr><td>{@code %kq_current_req_1_bar%}</td><td>第 1 条要求的进度条</td></tr>
 *   <tr><td>{@code %kq_current_time_left%}</td><td>剩余时间（限时任务）</td></tr>
 *   <tr><td>{@code %kq_current_weight%}</td><td>当前任务权重</td></tr>
 *   <tr><td>{@code %kq_active_count%}</td><td>进行中任务数</td></tr>
 *   <tr><td>{@code %kq_active_main%}</td><td>进行中主线数</td></tr>
 *   <tr><td>{@code %kq_active_side%}</td><td>进行中支线数</td></tr>
 *   <tr><td>{@code %kq_tree_&lt;treeId&gt;_main%}</td><td>某树主线进度 3/8</td></tr>
 *   <tr><td>{@code %kq_tree_&lt;treeId&gt;_side%}</td><td>某树支线进度</td></tr>
 *   <tr><td>{@code %kq_tree_&lt;treeId&gt;_state%}</td><td>某树状态文本</td></tr>
 *   <tr><td>{@code %kq_completed_total%}</td><td>全服内该玩家已完成任务总数</td></tr>
 *   <tr><td>{@code %kq_cooldown_&lt;questId&gt;%}</td><td>某循环任务剩余冷却</td></tr>
 *   <tr><td>{@code %kq_has_quest_&lt;questId&gt;%}</td><td>是否已完成该任务 true/false</td></tr>
 *   <tr><td>{@code %kq_can_accept_&lt;questId&gt;%}</td><td>是否可接取 true/false</td></tr>
 * </table>
 *
 * <p>未接任务时，全部个人占位符返回 {@code config.yml} 里的 {@code placeholders.none}（默认 "无"）。
 */
public class KqExpansion extends PlaceholderExpansion {

    private final KeranQuests plugin;

    public KqExpansion(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "kq";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Keran";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 重载插件后不该丢失扩展。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Nullable
    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        String none = plugin.getConfig().getString("placeholders.none", "无");
        if (offlinePlayer == null) return none;
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) return none;

        String p = params.toLowerCase();

        // ---------------- 任务树进度 ----------------
        if (p.startsWith("tree_")) {
            return treePlaceholder(player, p.substring(5), none);
        }
        // ---------------- 循环冷却 ----------------
        if (p.startsWith("cooldown_")) {
            Quest q = plugin.getTreeLoader().resolveQuest(params.substring(9));
            if (q == null) return none;
            int cd = plugin.getPlayerData(player).getCooldownRemaining(q.getFullId(), "COOLDOWN");
            return cd <= 0 ? "可接取" : TimeUtil.format(cd);
        }
        // ---------------- 是否完成 ----------------
        if (p.startsWith("has_quest_")) {
            Quest q = plugin.getTreeLoader().resolveQuest(params.substring(10));
            if (q == null) return "false";
            return String.valueOf(plugin.getPlayerData(player).isCompleted(q.getFullId()));
        }
        // ---------------- 是否可接 ----------------
        if (p.startsWith("can_accept_")) {
            Quest q = plugin.getTreeLoader().resolveQuest(params.substring(11));
            if (q == null) return "false";
            return String.valueOf(plugin.getQuestManager().canAccept(player, q) == null);
        }

        // ---------------- 其余需要"当前任务" ----------------
        Quest quest = plugin.getQuestManager().getCurrentQuest(player);
        QuestProgress prog = quest == null ? null
                : plugin.getPlayerData(player).getProgress(quest.getFullId());

        switch (p) {
            case "current":
                return quest == null ? none : Text.strip(quest.getName());
            case "current_id":
                return quest == null ? none : quest.getFullId();
            case "current_type":
                if (quest == null) return none;
                return quest.getType() == QuestType.MAIN ? "主线" : "支线";
            case "current_weight":
                return quest == null ? none : String.valueOf(quest.getWeight());
            case "current_stage": {
                if (quest == null || prog == null) return none;
                List<QuestStage> stages = quest.getStages();
                int si = prog.getStageIndex();
                if (si >= stages.size()) return "已完成";
                return Text.strip(stages.get(si).getName());
            }
            case "current_stage_index": {
                if (quest == null || prog == null) return none;
                return String.valueOf(Math.min(prog.getStageIndex() + 1, quest.getStageCount()));
            }
            case "current_stage_total":
                return quest == null ? none : String.valueOf(quest.getStageCount());
            case "current_time_left": {
                if (quest == null || prog == null || quest.getTimeLimit() <= 0) return none;
                long elapsed = (System.currentTimeMillis() - prog.getStartedAt()) / 1000L;
                int remain = (int) Math.max(0, quest.getTimeLimit() - elapsed);
                return TimeUtil.format(remain);
            }
            case "current_time_left_seconds": {
                if (quest == null || prog == null || quest.getTimeLimit() <= 0) return "0";
                long elapsed = (System.currentTimeMillis() - prog.getStartedAt()) / 1000L;
                return String.valueOf(Math.max(0, quest.getTimeLimit() - elapsed));
            }
            case "active_count":
                return String.valueOf(plugin.getQuestManager().getActiveQuests(player).size());
            case "active_main": {
                PlayerData d = plugin.getPlayerData(player);
                return String.valueOf(plugin.getQuestManager().countActive(d, QuestType.MAIN));
            }
            case "active_side": {
                PlayerData d = plugin.getPlayerData(player);
                return String.valueOf(plugin.getQuestManager().countActive(d, QuestType.SIDE));
            }
            case "completed_total": {
                PlayerData d = plugin.getPlayerData(player);
                int n = 0;
                for (Quest q : plugin.getTreeLoader().getAllQuests()) {
                    if (d.isCompleted(q.getFullId())) n++;
                }
                return String.valueOf(n);
            }
            default:
                break;
        }

        // ---------------- 要求级：current_req_<n>[_progress|_bar] ----------------
        if (p.startsWith("current_req_")) {
            return reqPlaceholder(player, quest, prog, p.substring(12), none);
        }

        return null;   // 交给其它扩展
    }

    /** {@code current_req_1} / {@code current_req_1_progress} / {@code current_req_1_bar} */
    private String reqPlaceholder(Player player, Quest quest, QuestProgress prog, String rest, String none) {
        if (quest == null || prog == null) return none;

        String kind = "text";
        String numPart = rest;
        int us = rest.indexOf('_');
        if (us > 0) {
            String tail = rest.substring(us + 1);
            if (tail.equals("progress") || tail.equals("bar") || tail.equals("need")
                    || tail.equals("have")) {
                kind = tail;
                numPart = rest.substring(0, us);
            }
        }
        int idx;
        try {
            idx = Integer.parseInt(numPart) - 1;      // 1 起 → 0 起
        } catch (NumberFormatException e) {
            return none;
        }

        List<QuestStage> stages = quest.getStages();
        int si = prog.getStageIndex();
        if (si >= stages.size()) return none;
        QuestStage stage = stages.get(si);
        if (idx < 0 || idx >= stage.getRequirements().size()) return none;

        Requirement req = stage.getRequirements().get(idx);
        int have = prog.getRequirementProgress(si, idx);
        int need = req.target();

        return switch (kind) {
            case "progress" -> Math.min(have, need) + "/" + need;
            case "bar" -> bar(have, need);
            case "need" -> String.valueOf(need);
            case "have" -> String.valueOf(Math.min(have, need));
            default -> req.describe();
        };
    }

    /** {@code tree_<id>_main} / {@code tree_<id>_side} / {@code tree_<id>_state} */
    private String treePlaceholder(Player player, String rest, String none) {
        String kind = null;
        for (String k : new String[]{"main", "side", "state", "name"}) {
            if (rest.toLowerCase().endsWith("_" + k)) {
                kind = k;
                rest = rest.substring(0, rest.length() - k.length() - 1);
                break;
            }
        }
        if (kind == null) return none;
        QuestTree tree = plugin.getTreeLoader().getTree(rest);
        if (tree == null) return none;

        PlayerData data = plugin.getPlayerData(player);
        return switch (kind) {
            case "main" -> plugin.getQuestManager().countCompleted(data, tree, QuestType.MAIN)
                    + "/" + tree.countMainTotal();
            case "side" -> plugin.getQuestManager().countCompleted(data, tree, QuestType.SIDE)
                    + "/" + tree.countSideTotal();
            case "state" -> plugin.getQuestManager().getTreeStateText(data, tree);
            default -> Text.strip(tree.getName());
        };
    }

    /** 生成 10 格进度条。 */
    private String bar(int have, int need) {
        if (need <= 0) return "";
        int filled = (int) Math.round(Math.min(1.0, have / (double) need) * 10);
        return "&a" + "|".repeat(filled) + "&7" + "|".repeat(10 - filled);
    }
}
