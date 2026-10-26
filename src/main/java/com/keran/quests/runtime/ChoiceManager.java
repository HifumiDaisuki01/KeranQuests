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
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress progress = data.getProgress(quest.getFullId());
        if (progress == null || progress.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE
                || progress.getStageIndex() >= quest.getStageCount()
                || quest.getStages().get(progress.getStageIndex()) != stage || !stage.isChoice()
                || !progress.isStageHandled(progress.getStageIndex())) return "&c这个抉择已过期。";
        QuestStage.Choice choice = stage.getChoices().get(choiceIndex);
        Quest target = plugin.getTreeLoader().resolveQuest(choice.getQuest());
        if (target == null || target == quest) return "&c无效的分支任务。";
        String group = stage.getChoiceGroup() == null ? quest.getTreeId() + "_" + quest.getId() + "_choice" : stage.getChoiceGroup();
        String value = choiceIndex == 0 ? "A" : choiceIndex == 1 ? "B" : String.valueOf(choiceIndex);
        boolean finalStage = progress.getStageIndex() == quest.getStageCount() - 1;
        String error = plugin.getBranchService().select(player, target, finalStage ? quest : null,
                group, value, null, 0, false);
        if (error != null) return error;
        if (!finalStage) {
            progress.advanceStage();
            data.markDirty();
            plugin.getQuestManager().checkStageCompletion(player, quest, progress, true);
            plugin.getPlayerDataStore().save(data);
        }
        Text.send(player, "&a你选择了：&f" + choice.getLabel());
        return null;
    }
}
