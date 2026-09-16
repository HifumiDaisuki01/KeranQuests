package com.keran.quests.listener;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.config.model.enums.RequirementType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.List;
import java.util.Map;

/**
 * Citizens NPC 右键监听 —— 触发机械式对话。
 *
 * <h3>为什么不用 {@code NPCRightClickEvent}</h3>
 * Citizens 的事件类需要编译期依赖，且不同大版本包名会变。这里改用 Bukkit 原生的
 * {@link PlayerInteractEntityEvent}，再用反射从实体上读 Citizens 的 NPC 名。
 * 好处：Citizens 没装时监听器照样工作（只是永远匹配不上），零崩溃风险。
 *
 * <h3>NPC 名匹配规则</h3>
 * <ol>
 *   <li>实体是 Citizens NPC → 取 {@code NPC#getName()}（即游戏内显示的 NPC 名）</li>
 *   <li>fallback：实体自定义名（NameTag）</li>
 * </ol>
 * 配置里 {@code npc: '深空联合联络员'} 直接写 NPC 的显示名即可。
 */
public class NpcClickListener implements Listener {

    private final KeranQuests plugin;

    public NpcClickListener(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        String npcName = resolveNpcName(event.getRightClicked());
        if (npcName == null) return;

        // 1. NPC 对话（有配置才播）
        boolean talked = false;
        if (plugin.getDialogueRunner().hasDialogue(npcName)) {
            talked = plugin.getDialogueRunner().startDialogue(player, npcName);
        }

        // 2. 推进 NPC_TALK 类的任务要求。
        //
        // 【语义】分两种情况，不能一律在右键瞬间推进：
        //   · 该 NPC 配了对话 → 由 DialogueRunner.finish() 在【对话全部播完】
        //     时推进。理由：剧情还没走完就把任务判成"谈过了"，会出现
        //     「对话刚播第一句，任务奖励已经发了」——实测中真实踩到。
        //   · 该 NPC 没配对话 → 它只是个"报到点"，右键即算完成。
        // 两种情况都只在 talked == false 时在此处推进，避免重复触发。
        boolean questAdvanced = false;
        if (!talked) {
            questAdvanced = plugin.getQuestManager().fireNpcTalk(player, npcName);
        }

        // 3. 对话结束后给一句引导（仅在确实有任务被推进时）
        if (questAdvanced && !talked) {
            hintNext(player);
        }
    }

    /** 触发已聊天，给玩家一句"去菜单接下一步"的引导。 */
    private void hintNext(Player player) {
        PlayerData data = plugin.getPlayerData(player);
        for (Map.Entry<String, QuestProgress> e : data.getAllProgress().entrySet()) {
            QuestProgress prog = e.getValue();
            if (prog.getState() != QuestState.ACTIVE) continue;
            Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (quest == null) continue;
            int si = prog.getStageIndex();
            if (si >= quest.getStages().size()) continue;
            QuestStage stage = quest.getStages().get(si);
            for (Requirement req : stage.getRequirements()) {
                if (req.getType() == RequirementType.NPC_TALK) {
                    Text.send(player, "&7[任务] &f对话已完成，输入 &e/kq &f查看后续任务。");
                    return;
                }
            }
        }
    }

    // ==================================================================
    //  反射：从实体取 Citizens NPC 名
    // ==================================================================

    private String resolveNpcName(org.bukkit.entity.Entity entity) {
        // 路径 1：CitizensAPI.getNPCRegistry().getNPC(entity)
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);
            if (registry != null) {
                Object npc = registry.getClass()
                        .getMethod("getNPC", org.bukkit.entity.Entity.class)
                        .invoke(registry, entity);
                if (npc != null) {
                    Object name = npc.getClass().getMethod("getName").invoke(npc);
                    if (name != null) return String.valueOf(name);
                }
            }
        } catch (Throwable ignored) {
            // Citizens 未装或 API 变动
        }

        // 路径 2：实体自定义名（NameTag）
        if (entity.customName() != null) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(entity.customName());
            if (plain != null && !plain.isBlank()) return plain;
        }
        if (entity.getCustomName() != null && !entity.getCustomName().isBlank()) {
            return Text.strip(entity.getCustomName());
        }
        return null;
    }
}
