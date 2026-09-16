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
