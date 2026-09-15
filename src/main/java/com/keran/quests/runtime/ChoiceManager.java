package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import org.bukkit.entity.Player;

/**
 * 抉择管理 —— 处理主线03 这类"二选一"节点。
 */
public class ChoiceManager {

    private final KeranQuests plugin;

    public ChoiceManager(KeranQuests plugin) {
        this.plugin = plugin;
    }

    /**
     * 打开抉择 GUI。
     *
     * <p>容错：若阶段声明为 CHOICE 却没配 choices，直接把该阶段当作普通阶段推进，
     * 而不是把玩家永久卡在这个阶段（历史 bug：只打 warning 然后 return，
     * 玩家既没得选也走不了）。
     */
    public void openChoice(Player player, Quest quest, QuestStage stage) {
        if (stage.getChoices().isEmpty()) {
            plugin.getLogger().warning("任务 " + quest.getFullId() + " 的阶段 " + stage.getId()
                    + " 是抉择节点但没有配置 choices，已自动跳过该阶段。");
            QuestProgress p = plugin.getPlayerData(player).getOrCreateProgress(quest.getFullId());
            p.advanceStage();
            plugin.getPlayerDataStore().save(plugin.getPlayerData(player));
            plugin.getQuestManager().checkStageCompletion(player, quest, p, true);
            return;
        }
        plugin.getGuiManager().openChoice(player, quest, stage);
    }

    /**
     * 玩家做出选择。
     *
     * @param choiceIndex 选项序号（0-based）
     * @return null 表示成功
     */
    public String choose(Player player, Quest quest, QuestStage stage, int choiceIndex) {
        if (choiceIndex < 0 || choiceIndex >= stage.getChoices().size()) {
            return Text.color("&c无效的选项。");
        }
        QuestStage.Choice choice = stage.getChoices().get(choiceIndex);
        PlayerData data = plugin.getPlayerData(player);

        // 记录抉择（树级状态）
        String group = stage.getChoiceGroup() != null && !stage.getChoiceGroup().isBlank()
                ? stage.getChoiceGroup()
                : (quest.getTreeId() + "_" + quest.getId() + "_choice");
        String value = choiceIndex == 0 ? "A" : (choiceIndex == 1 ? "B" : String.valueOf(choiceIndex));
        data.setTreeState(quest.getTreeId(), "choice:" + group, value);
        data.markDirty();

        // 推进原任务的阶段（抉择节点已完成）
        QuestProgress p = data.getOrCreateProgress(quest.getFullId());
        p.markStageCompleted(p.getStageIndex());
        p.advanceStage();
        plugin.getPlayerDataStore().save(data);

        // 解锁目标分支任务（把分支任务设为 AVAILABLE 并接取）
        Quest target = plugin.getTreeLoader().resolveQuest(choice.getQuest());
        if (target == null) {
            plugin.getLogger().warning("抉择目标任务不存在：" + choice.getQuest());
            return null;
        }

        // 检查原任务是否还有后续阶段
        plugin.getQuestManager().checkStageCompletion(player, quest, p, true);

        // 自动接取分支任务
        String err = plugin.getQuestManager().accept(player, target);
        if (err != null) {
            Text.send(player, "&e分支任务接取失败：" + err);
        }

        Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                + "&a你选择了：&f" + choice.getLabel());
        return null;
    }
}
