package com.keran.quests.command;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.StageVisibility;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import com.keran.quests.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /ka —— 快速查看当前任务与进度。
 *
 * <p>比 GUI 更轻，用于聊天栏即时确认。等价于 {@code /kq status}。
 */
public class KaCommand implements CommandExecutor {

    private final KeranQuests plugin;

    public KaCommand(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            Text.send(sender, "&c该命令只能由玩家执行。");
            return true;
        }

        // 支持 /ka <玩家> 供管理员查看
        Player target = p;
        if (args.length >= 1) {
            if (!p.hasPermission("kq.admin") && !p.isOp()) {
                Text.send(p, "&c你没有权限查看他人任务。");
                return true;
            }
            Player t = org.bukkit.Bukkit.getPlayerExact(args[0]);
            if (t == null) {
                Text.send(p, "&c玩家不在线：" + args[0]);
                return true;
            }
            target = t;
        }

        showStatus(target);
        return true;
    }

    /** 输出当前任务详情（也供 GUI 的"聊天栏输出"按钮复用）。 */
    public void showStatus(Player p) {
        Quest quest = plugin.getQuestManager().getCurrentQuest(p);
        if (quest == null) {
            Text.sendRaw(p, "&8&m--------&r &e当前任务 &8&m--------");
            Text.sendRaw(p, " &7你当前没有进行中的任务。");
            Text.sendRaw(p, " &7输入 &f/kq &7打开任务菜单。");
            return;
        }

        PlayerData data = plugin.getPlayerData(p);
        QuestProgress prog = data.getProgress(quest.getFullId());

        String typeTag = quest.getType() == com.keran.quests.config.model.enums.QuestType.MAIN
                ? "&6[主线]" : "&b[支线]";

        Text.sendRaw(p, "&8&m--------&r " + typeTag + " &f" + Text.strip(quest.getName())
                + " &8&m--------");
        Text.sendRaw(p, " &7任务树：&f" + treeName(quest));

        int stageIdx = prog == null ? 0 : prog.getStageIndex();
        List<QuestStage> stages = quest.getStages();
        Text.sendRaw(p, " &7进度：&f阶段 " + Math.min(stageIdx + 1, stages.size())
                + "&7/&f" + stages.size());

        // 剩余时间
        if (quest.getTimeLimit() > 0 && prog != null && prog.getStartedAt() > 0) {
            long elapsed = (System.currentTimeMillis() - prog.getStartedAt()) / 1000L;
            int remain = (int) Math.max(0, quest.getTimeLimit() - elapsed);
            Text.sendRaw(p, " &7剩余时间：&f" + TimeUtil.format(remain)
                    + (remain < 300 ? " &c（紧迫！）" : ""));
        }

        // 当前阶段要求
        if (stageIdx < stages.size()) {
            QuestStage stage = stages.get(stageIdx);
            Text.sendRaw(p, " &7当前阶段：&f" + Text.strip(stage.getName()));
            int ri = 0;
            for (Requirement req : stage.getRequirements()) {
                int cur = prog == null ? 0 : prog.getRequirementProgress(stageIdx, ri);
                int need = req.target();
                String bar = bar(cur, need);
                String status = cur >= need ? " &a[完成]" : "";
                Text.sendRaw(p, "   &8▪ &f" + req.describe()
                        + " &7" + bar + " &f" + Math.min(cur, need) + "&7/&f" + need + status);
                ri++;
            }
        } else {
            Text.sendRaw(p, " &7（所有阶段已完成）");
        }

        // 循环任务冷却
        if (quest.isRepeatable()) {
            int cd = data.getCooldownRemaining(quest.getFullId(), "COOLDOWN");
            if (cd > 0) {
                Text.sendRaw(p, " &7循环冷却：&f" + TimeUtil.format(cd) + " &7后可再挑战");
            }
        }
        Text.sendRaw(p, " &8提示：&7/kq &8查看全部任务树");
    }

    private String bar(int cur, int need) {
        if (need <= 0) return "&8[&f||||||||&8]";
        int filled = (int) Math.round(Math.min(1.0, cur / (double) need) * 8);
        StringBuilder sb = new StringBuilder("&8[");
        sb.append("&a");
        sb.append("|".repeat(filled));
        sb.append("&7");
        sb.append("|".repeat(8 - filled));
        sb.append("&8]");
        return sb.toString();
    }

    private String treeName(Quest quest) {
        var tree = plugin.getTreeLoader().getTree(quest.getTreeId());
        return tree == null ? quest.getTreeId() : Text.strip(tree.getName());
    }
}
