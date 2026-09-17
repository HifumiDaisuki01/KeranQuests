package com.keran.quests.listener;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;

/**
 * 玩家生命周期监听 —— 登录 / 退出 / 死亡 / 移动冻结。
 */
public class PlayerLifecycleListener implements Listener {

    private final KeranQuests plugin;

    public PlayerLifecycleListener(KeranQuests plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  登录 / 退出
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.getPlayerDataStore().load(p.getUniqueId(), p.getName());

        // 延迟 2 秒（等客户端加载完）再提示当前任务
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Quest q = plugin.getQuestManager().getCurrentQuest(p);
            if (q == null) return;
            Text.send(p, "&7[任务] &f当前任务：&e" + Text.strip(q.getName())
                    + " &7（输入 &f/ka &7查看进度）");
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        plugin.getFreezeManager().unfreeze(p.getUniqueId());
        // 清理 GUI 状态，避免 currentView / pageState / suppressClose 随上下线堆积
        plugin.getGuiManager().forget(p.getUniqueId());

        // ★ 打断该玩家的对话会话。
        //
        // 对话状态（talking / sessions / runningTasks）全部活在内存里，不随玩家
        // 下线自动清理。若不在这里打断：
        //   ① talking 残留该 UUID → 重连后右键 NPC，startDialogue() 开头
        //      的 if (talking.contains(uid)) return true; 会把这次交互直接吞掉，
        //      玩家对【所有】配了对话的 NPC 永久失聪，且毫无提示；
        //   ② sessions 一并残留 → schedule() 的守卫条件
        //      if (player.isOnline() && sessions.containsKey(s.uid)) 在重连后
        //      依然成立，于是掉线前已排期的台词会"复活"，出现"右键没反应，
        //      但聊天栏自己往下演"的诡异场面。
        // 直到插件重载或服务器重启才会恢复。
        //
        // 传 false：玩家已经下线，不必再发"对话已中断"。
        plugin.getDialogueRunner().interrupt(p, false);

        plugin.getPlayerDataStore().unload(p.getUniqueId());
    }

    // ------------------------------------------------------------------
    //  死亡 → 失败判定
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        PlayerData data = plugin.getPlayerData(p);

        for (Map.Entry<String, QuestProgress> e : new java.util.ArrayList<>(data.getAllProgress().entrySet())) {
            QuestProgress prog = e.getValue();
            if (prog.getState() != QuestState.ACTIVE) continue;
            Quest q = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (q == null) continue;
            if (!q.isFailOnDeath()) continue;
            plugin.getQuestManager().fail(p, q, plugin.getConfig()
                    .getString("failure_reasons.death", "你死亡了"));
        }
    }

    // ------------------------------------------------------------------
    //  冻结：禁止移动 / 丢弃物品
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getFreezeManager().isFrozen(p.getUniqueId())) return;

        // 允许转视角，只锁位置
        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }
        event.setTo(event.getFrom());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 对话中禁止点背包（避免把 GUI 物品丢出去）。 */
    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (plugin.getFreezeManager().isFrozen(p.getUniqueId())
                && event.getView().getTopInventory().getHolder() == null) {
            // 自己背包被打开时的兜底保护（GUI 由 GuiManager 单独处理）
            event.setCancelled(true);
        }
    }
}
