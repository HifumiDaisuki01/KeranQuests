package com.keran.quests.command;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.enums.QuestType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /kquests —— <b>给第三方插件调用的 API 命令</b>。
 *
 * <h3>设计铁律：容错优先，绝不报错</h3>
 * 这个命令会被 ExtractionPoints / Citizens / MythicMobs 等插件通过
 * {@code console-commands} 或 {@code /npc command} 自动调用。调用方不会检查返回值，
 * 也不该因为我们的报错而刷屏控制台。因此本命令遵守：
 * <ul>
 *   <li>任何一条参数不合法（任务不存在 / 玩家不在线 / trigger key 不存在）
 *       → <b>静默 return true</b>，绝不抛异常、绝不向玩家发红字。</li>
 *   <li>玩家未接取该任务时，trigger 静默忽略。</li>
 *   <li>只对 {@code fail} / {@code reset} 这类"管理员故意为之"的操作才输出回执。</li>
 * </ul>
 *
 * <h3>指令一览</h3>
 * <pre>
 * /kquests accept   &lt;player&gt; &lt;questId&gt;            强制接取
 * /kquests trigger  &lt;player&gt; &lt;key&gt;               触发自定义阶段（核心联动指令）
 * /kquests complete &lt;player&gt; &lt;questId&gt;            强制完成（发奖励）
 * /kquests stage    &lt;player&gt; &lt;questId&gt; &lt;index&gt;    跳阶段
 * /kquests fail     &lt;player&gt; &lt;questId&gt; [reason]   强制失败
 * /kquests reset    &lt;player&gt; &lt;questId&gt;            重置任务
 * /kquests talk     &lt;player&gt; &lt;npcName&gt;            触发 NPC 对话要求
 * /kquests refresh  &lt;player&gt; &lt;questId&gt;            刷新循环任务冷却（清零）
 * /kquests query    &lt;player&gt; &lt;questId&gt;            查询状态（输出到 console，供其他插件读）
 * /kquests tree     &lt;player&gt; &lt;treeId&gt; &lt;state&gt;     置任务树状态（choice/terminated）
 * </pre>
 *
 * <p>全部子命令需要权限 {@code kq.api}（控制台免检）。
 */
public class KquestsCommand implements CommandExecutor, TabCompleter {

    private final KeranQuests plugin;

    public KquestsCommand(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            // 控制台调用无参：打印指令表
            apiHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        // 权限：控制台永远放行；玩家需要 kq.api
        if (sender instanceof Player && !sender.hasPermission("kq.api")
                && !sender.hasPermission("kq.admin") && !sender.isOp()) {
            Text.send(sender, "&c你没有权限执行该命令。");
            return true;
        }

        switch (sub) {
            case "accept" -> doAccept(sender, args);
            case "force" -> doForceAccept(sender, args);
            case "trigger" -> doTrigger(args);
            case "complete" -> doComplete(sender, args);
            case "stage" -> doStage(sender, args);
            case "fail" -> doFail(sender, args);
            case "reset" -> doReset(sender, args);
            case "talk" -> doTalk(args);
            case "refresh" -> doRefresh(sender, args);
            case "query" -> doQuery(sender, args);
            case "tree" -> doTree(sender, args);
            case "help" -> apiHelp(sender, label);
            default -> {
                // 未知子命令：静默（第三方可能拼错，不该刷屏）
            }
        }
        return true;
    }

    // ==================================================================
    //  accept —— 强制接取
    // ==================================================================

    private void doAccept(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;                       // 玩家不在线，静默
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;                       // 任务不存在，静默

        // 本命令面向第三方插件 / RCON，因此严格尊重前置、并发上限等校验。
        // 被拒绝时：给玩家本人一条提示，同时向控制台输出一行固定格式便于排查，
        // 但绝不抛错、绝不影响调用方（符合"静默容错"的对外约定）。
        String err = plugin.getQuestManager().accept(p, q);
        if (err != null) {
            Text.send(p, err);
            // 带上拒绝原因，方便第三方插件调用后排查
            plugin.getLogger().info("KQ_ACCEPT " + p.getName() + " " + q.getFullId()
                    + " REJECTED " + Text.strip(err));
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("KQ_ACCEPT " + p.getName() + " " + q.getFullId() + " OK");
        } else if (sender instanceof Player sp && !sp.equals(p)) {
            Text.send(sender, "&a已为 " + p.getName() + " 接取：" + Text.strip(q.getName()));
        }
    }

    /**
     * 绕过前置 / 并发上限的<b>强制接取</b>。
     *
     * <p>仅供地图脚本、剧情演出等确实需要"无视条件直接开始"的场合使用，
     * 与 {@code /kquests accept} 分离，避免误用导致前置与上限形同虚设。
     */
    private void doForceAccept(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;

        QuestProgress prog = plugin.getPlayerData(p).getProgress(q.getFullId());
        if (prog != null && (prog.getState() == com.keran.quests.config.model.enums.QuestState.ACTIVE
                || prog.getState() == com.keran.quests.config.model.enums.QuestState.COMPLETED)) {
            return;                                  // 已在做 / 已完成：静默忽略
        }
        forceAccept(p, q);
        if (!(sender instanceof Player)) {
            sender.sendMessage("KQ_FORCE " + p.getName() + " " + q.getFullId() + " OK");
        }
        if (sender instanceof Player sp && !sp.equals(p)) {
            Text.send(sender, "&e已强制为 " + p.getName() + " 接取：" + Text.strip(q.getName()));
        }
    }

    /** 绕过前置/上限的强制接取（供地图脚本用）。 */
    private void forceAccept(Player p, Quest q) {
        PlayerData data = plugin.getPlayerData(p);
        QuestProgress prog = data.getOrCreateProgress(q.getFullId());
        prog.setState(com.keran.quests.config.model.enums.QuestState.ACTIVE);
        prog.setStageIndex(0);
        prog.setStartedAt(System.currentTimeMillis());
        prog.setFinishedAt(0);
        prog.setFailReason(null);
        prog.clearFromStage(0);
        data.markDirty();
        if (data.getTrackedQuest() == null) data.setTrackedQuest(q.getFullId());

        plugin.getQuestManager().runCommands(p, q.getOnStart(), q, null, null, null);
        Text.send(p, plugin.getConfig().getString("messages.prefix", "")
                + plugin.prefixed("quest_accepted", "quest_name", q.getName()));
        plugin.getQuestManager().checkStageCompletion(p, q, prog, false);
        plugin.getPlayerDataStore().save(data);
    }

    // ==================================================================
    //  trigger —— 核心联动指令
    // ==================================================================

    /**
     * <b>本插件最重要的对外接口。</b>
     *
     * <p>典型用法（ExtractionPoints 撤离成功后）：
     * <pre>
     * switch:
     *   console-commands:
     *     - 'kquests trigger {player} sl_zhulong_extracted'
     * </pre>
     *
     * <p>容错：key 不存在 / 玩家没接任何任务 / 玩家没接相关任务 → 全部静默返回。
     */
    private void doTrigger(String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        String key = args[2];
        if (key.isBlank()) return;
        // 返回值刻意忽略：没有匹配的任务是正常情况
        plugin.getQuestManager().fireTrigger(p, key);
    }

    // ==================================================================
    //  complete —— 强制完成
    // ==================================================================

    private void doComplete(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;

        QuestProgress prog = plugin.getPlayerData(p).getProgress(q.getFullId());
        if (prog == null || prog.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) {
            return;                                   // 不在进行中 → 静默
        }
        plugin.getQuestManager().complete(p, q);
        if (sender instanceof Player sp && !sp.equals(p)) {
            Text.send(sender, "&a已为 " + p.getName() + " 完成任务：" + Text.strip(q.getName()));
        }
    }

    // ==================================================================
    //  stage —— 跳到指定阶段
    // ==================================================================

    private void doStage(CommandSender sender, String[] args) {
        if (args.length < 4) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;
        int idx;
        try {
            idx = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            return;
        }
        if (idx < 0 || idx > q.getStageCount()) return;

        PlayerData data = plugin.getPlayerData(p);
        QuestProgress prog = data.getProgress(q.getFullId());
        if (prog == null || prog.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) {
            return;
        }
        int from = prog.getStageIndex();
        prog.clearFromStage(from);
        prog.setStageIndex(idx);
        data.markDirty();

        if (idx >= q.getStageCount()) {
            plugin.getQuestManager().complete(p, q);
        } else {
            plugin.getQuestManager().checkStageCompletion(p, q, prog, true);
            plugin.getPlayerDataStore().save(data);
        }
        if (sender instanceof Player sp && !sp.equals(p)) {
            Text.send(sender, "&a已把 " + p.getName() + " 的 " + Text.strip(q.getName())
                    + " 置入阶段 " + idx + "。");
        }
    }

    // ==================================================================
    //  fail —— 强制失败（第三方判定失败时调用）
    // ==================================================================

    private void doFail(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;

        QuestProgress prog = plugin.getPlayerData(p).getProgress(q.getFullId());
        if (prog == null || prog.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) {
            return;                                   // 没在做这个任务 → 静默
        }
        String reason = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : "任务失败";
        plugin.getQuestManager().fail(p, q, reason);
        if (sender instanceof Player sp && !sp.equals(p)) {
            Text.send(sender, "&e已判定 " + p.getName() + " 的 " + Text.strip(q.getName())
                    + " 失败：" + reason);
        }
    }

    // ==================================================================
    //  reset / refresh
    // ==================================================================

    private void doReset(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;
        plugin.getQuestManager().reset(p, q);
        Text.send(sender, "&a已重置 " + p.getName() + " 的任务：" + Text.strip(q.getName()));
    }

    /** 清掉循环任务冷却（或全部冷却），让玩家立刻能再接。 */
    private void doRefresh(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        String ref = args[2];

        PlayerData data = plugin.getPlayerData(p);
        if (ref.equals("*") || ref.equalsIgnoreCase("all")) {
            List<String> keys = new ArrayList<>(data.getAllCooldowns().keySet());
            for (String k : keys) {
                int i = k.lastIndexOf(':');
                if (i < 0) continue;
                data.clearCooldown(k.substring(0, i), k.substring(i + 1));
            }
            Text.send(sender, "&a已清空 " + p.getName() + " 的全部冷却。");
            plugin.getPlayerDataStore().save(data);
            return;
        }
        Quest q = plugin.getTreeLoader().resolveQuest(ref);
        if (q == null) return;
        data.clearCooldown(q.getFullId(), "COOLDOWN");
        data.clearCooldown(q.getFullId(), "FAIL_LOCK");
        // 循环任务：顺手把状态退回可接
        QuestProgress prog = data.getProgress(q.getFullId());
        if (prog != null && prog.getState() == com.keran.quests.config.model.enums.QuestState.COMPLETED
                && q.isRepeatable()) {
            prog.setState(com.keran.quests.config.model.enums.QuestState.AVAILABLE);
            prog.clearAllProgress();
        }
        plugin.getPlayerDataStore().save(data);
        Text.send(sender, "&a已刷新 " + p.getName() + " 的循环任务：" + Text.strip(q.getName()));
    }

    // ==================================================================
    //  talk / tree / query
    // ==================================================================

    private void doTalk(String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        plugin.getQuestManager().fireNpcTalk(p, args[2]);
    }

    private void doTree(CommandSender sender, String[] args) {
        if (args.length < 4) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        String treeId = args[2];
        String state = args[3];
        if (plugin.getTreeLoader().getTree(treeId) == null) return;

        PlayerData data = plugin.getPlayerData(p);
        if (state.equalsIgnoreCase("terminated")) {
            data.terminateTree(treeId);
        } else {
            // 形如 choice:zhulong_ch03=A
            int eq = state.indexOf('=');
            if (eq <= 0) return;
            data.setTreeState(treeId, state.substring(0, eq), state.substring(eq + 1));
        }
        plugin.getPlayerDataStore().save(data);
        Text.send(sender, "&a已设置 " + p.getName() + " 的 " + treeId + " 树状态：" + state);
    }

    /**
     * 查询并输出到命令发送者（通常是控制台），供其他插件/RCON 读取。
     * 输出格式固定为一行 {@code KQ_QUERY <player> <questId> <state> <stage> <main_done> <side_done>}。
     */
    private void doQuery(CommandSender sender, String[] args) {
        if (args.length < 3) return;
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) return;
        Quest q = plugin.getTreeLoader().resolveQuest(args[2]);
        if (q == null) return;
        PlayerData data = plugin.getPlayerData(p);
        QuestProgress prog = data.getProgress(q.getFullId());
        String state = prog == null ? "LOCKED" : prog.getState().name();
        int stage = prog == null ? 0 : prog.getStageIndex();

        var tree = plugin.getTreeLoader().getTree(q.getTreeId());
        int mainDone = tree == null ? 0
                : plugin.getQuestManager().countCompleted(data, tree, QuestType.MAIN);
        int sideDone = tree == null ? 0
                : plugin.getQuestManager().countCompleted(data, tree, QuestType.SIDE);

        sender.sendMessage("KQ_QUERY " + p.getName() + " " + q.getFullId() + " "
                + state + " " + stage + " " + mainDone + " " + sideDone);
    }

    // ==================================================================

    private void apiHelp(CommandSender sender, String label) {
        Text.sendRaw(sender, "&8&m----&r &eKeranQuests 第三方 API &8&m----");
        Text.sendRaw(sender, " &f/" + label + " accept <玩家> <任务ID> &7（尊重前置与上限）");
        Text.sendRaw(sender, " &f/" + label + " force <玩家> <任务ID> &7（无视前置与上限）");
        Text.sendRaw(sender, " &f/" + label + " trigger <玩家> <key> &7← 最常用");
        Text.sendRaw(sender, " &f/" + label + " complete <玩家> <任务ID>");
        Text.sendRaw(sender, " &f/" + label + " stage <玩家> <任务ID> <序号>");
        Text.sendRaw(sender, " &f/" + label + " fail <玩家> <任务ID> [原因]");
        Text.sendRaw(sender, " &f/" + label + " reset <玩家> <任务ID>");
        Text.sendRaw(sender, " &f/" + label + " talk <玩家> <NPC名>");
        Text.sendRaw(sender, " &f/" + label + " refresh <玩家> <任务ID|*>");
        Text.sendRaw(sender, " &f/" + label + " query <玩家> <任务ID>");
        Text.sendRaw(sender, " &f/" + label + " tree <玩家> <树ID> <状态>");
        Text.sendRaw(sender, " &7除 query 外全部静默容错，可安全用于 console-commands。");
    }

    // ==================================================================

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("accept", "force", "trigger", "complete", "stage", "fail",
                    "reset", "talk", "refresh", "query", "tree", "help"));
        } else if (args.length == 2) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("talk")) {
                out.addAll(plugin.getDialogueRunner().getNpcNames());
            } else if (sub.equals("tree")) {
                plugin.getTreeLoader().getTrees().forEach(t -> out.add(t.getId()));
            } else {
                plugin.getTreeLoader().getAllQuests().forEach(q -> out.add(q.getFullId()));
                plugin.getTreeLoader().getAllQuests().forEach(q -> out.add(q.getId()));
            }
        }
        String last = args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.toLowerCase().startsWith(last))
                .sorted().distinct().collect(Collectors.toList());
    }
}
