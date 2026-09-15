package com.keran.quests.listener;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.RequirementType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家击杀监听 —— 处理 {@code player_kill} 类型要求。
 *
 * <p>监听的坑点：Bukkit 里 {@code EntityDeathEvent.getKiller()} 只对
 * {@code LivingEntity} 生效，玩家被击杀走的是 {@link PlayerDeathEvent}（它的父类正是
 * EntityDeathEvent），所以这里监听 PlayerDeathEvent 即可拿到 killer。
 *
 * <h3>防刷</h3>
 * {@code anti_farm.same_victim_cooldown} 声明"同一对手多少秒内只计一次"。
 * 用「击杀者 UUID -> 受害者 UUID -> 上次计入时间」的二级表实现，
 * 冷却为 0 时不做限制（直接累加）。
 */
public class PlayerKillListener implements Listener {

    private final KeranQuests plugin;

    /** 击杀者 -> (受害者 -> 上次计入时间戳 ms) */
    private final Map<UUID, Map<UUID, Long>> lastKillAt = new HashMap<>();

    public PlayerKillListener(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        if (!killer.isOnline()) return;

        boolean changed = plugin.getQuestManager().advanceRequirement(
                killer, RequirementType.PLAYER_KILL, 1, new com.keran.quests.runtime.QuestManager.RequirementFilter() {
                    @Override
                    public boolean testRequirement(com.keran.quests.config.model.Quest quest,
                                                   com.keran.quests.config.model.QuestStage stage,
                                                   Requirement req) {
                        return allowCount(killer, victim, req);
                    }
                });

        if (changed) {
            plugin.getPlayerDataStore().save(plugin.getPlayerData(killer));
        }
    }

    /**
     * 防刷判定：同一对手在冷却期内不重复计数。
     *
     * @return true 表示本次击杀应该计数
     */
    private boolean allowCount(Player killer, Player victim, Requirement req) {
        int cooldown = req.getSameVictimCooldown();
        if (cooldown <= 0) return true;

        long now = System.currentTimeMillis();
        Map<UUID, Long> victims = lastKillAt.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
        Long last = victims.get(victim.getUniqueId());
        if (last != null && now - last < cooldown * 1000L) {
            return false;   // 冷却中，不计
        }
        victims.put(victim.getUniqueId(), now);
        return true;
    }

    /** 玩家退出时清理其作为击杀者的记录，避免内存堆积。 */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastKillAt.remove(event.getPlayer().getUniqueId());
    }
}
