package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Server-thread handoff: validate the whole selection before changing progress or inventory. */
public final class BranchService {
    private final KeranQuests plugin;
    public BranchService(KeranQuests plugin) { this.plugin = plugin; }

    public String select(Player player, Quest target, Quest source, String group, String value,
                         String item, int count, boolean consume) {
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress parent = source == null ? null : data.getProgress(source.getFullId());
        if (source != null && (parent == null || parent.getState() != QuestState.ACTIVE))
            return "&c原任务已不在进行中，请重新打开任务界面。";
        String tree = source != null ? source.getTreeId() : target == null ? null : target.getTreeId();
        if (group != null && tree == null) return "&c抉择缺少所属任务树。";
        String stateKey = group == null ? null : tree + ":choice:" + group;
        String previous = stateKey == null ? null : data.getAllTreeState().get(stateKey);
        if (previous != null && !previous.equals(value)) return "&c该抉择已锁定。";
        if (item != null && plugin.getOraxenHook().countItem(player, item) < count)
            return "&c物品不足：" + item + " ×" + count;

        // Temporarily project the state needed by the target's prerequisites/limit check.
        // This projection is never saved and executes no commands.
        QuestState parentState = parent == null ? null : parent.getState();
        String error;
        try {
            if (stateKey != null) data.getAllTreeState().put(stateKey, value);
            if (parent != null) parent.setState(QuestState.COMPLETED);
            error = target == null ? null : plugin.getQuestManager().canAccept(player, target);
        } finally {
            if (parent != null) parent.setState(parentState);
            if (stateKey != null) {
                if (previous == null) data.getAllTreeState().remove(stateKey);
                else data.getAllTreeState().put(stateKey, previous);
            }
        }
        if (error != null) return error;
        if (item != null && consume) {
            ItemStack[] snapshot = player.getInventory().getContents();
            for (int i = 0; i < snapshot.length; i++) if (snapshot[i] != null) snapshot[i] = snapshot[i].clone();
            if (plugin.getOraxenHook().removeItem(player, item, count) != count) {
                player.getInventory().setContents(snapshot);
                return "&c扣除物品失败，请重试。";
            }
        }
        if (stateKey != null) data.getAllTreeState().put(stateKey, value);
        data.markDirty();
        if (source != null) plugin.getQuestManager().complete(player, source);
        if (target != null) plugin.getQuestManager().acceptPrepared(player, target);
        plugin.getPlayerDataStore().save(data);
        return null;
    }
}
