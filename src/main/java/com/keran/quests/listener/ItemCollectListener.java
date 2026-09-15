package com.keran.quests.listener;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.RequirementType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Oraxen 物品收集监听 —— 处理 {@code oraxen_item} 类型要求。
 *
 * <p>覆盖两条获取路径：
 * <ol>
 *   <li><b>拾取</b>：{@link EntityPickupItemEvent}（1.12+ 已由 PlayerPickupItemEvent 迁移而来）</li>
 *   <li><b>背包/容器点击</b>：{@link InventoryClickEvent}，覆盖"从箱子里拿"、"交易/合成得到"等
 *       不经过拾取事件的情形</li>
 * </ol>
 *
 * <p>计数口径是「当前背包里持有多少」（而不是"累计捡了几个"）：每次事件后重新扫一遍背包，
 * 把背包内该 Oraxen 物品的总数量直接同步进进度。这样丢弃/上交物品后进度会自然回落，
 * 避免了"捡了又丢也算完成"的漏洞，也与"收集 N 个"的语义一致。
 */
public class ItemCollectListener implements Listener {

    private final KeranQuests plugin;

    /** 正在同步中的玩家，防止 InventoryClickEvent 递归触发。 */
    private final Set<UUID> syncing = new HashSet<>();

    public ItemCollectListener(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        scheduleSync(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // 延迟 1 tick：点击事件触发时物品还没真正落到背包里
        scheduleSync(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 上线时同步一次，覆盖"离线期间物品变化"的情况
        scheduleSync(event.getPlayer());
    }

    private void scheduleSync(Player player) {
        if (syncing.contains(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> syncInventory(player));
    }

    /** 扫描玩家背包，把每个 oraxen_item 要求的进度同步为实际持有量。 */
    private void syncInventory(Player player) {
        UUID id = player.getUniqueId();
        if (!syncing.add(id)) return;
        try {
            if (!player.isOnline()) return;
            if (!plugin.getOraxenHook().isAvailable()) return;
            syncAll(player);
        } finally {
            syncing.remove(id);
        }
    }

    /**
     * 逐个 oraxen_item 要求做"持有量 → 进度"的直接同步。
     *
     * <p>之所以不走 {@code advanceRequirement}：那是增量接口，而这里需要的是
     * 绝对值同步（背包里少了要能掉下来），逐要求处理更直观。
     */
    private void syncAll(Player player) {
        var data = plugin.getPlayerData(player);
        boolean changed = false;

        for (var entry : new java.util.ArrayList<>(data.getAllProgress().entrySet())) {
            var prog = entry.getValue();
            if (prog.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) continue;
            var quest = plugin.getTreeLoader().resolveQuest(entry.getKey());
            if (quest == null) continue;
            int si = prog.getStageIndex();
            if (si >= quest.getStages().size()) continue;
            var stage = quest.getStages().get(si);

            for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
                Requirement req = stage.getRequirements().get(ri);
                if (req.getType() != RequirementType.ORAXEN_ITEM) continue;
                String itemId = req.getItem();
                if (itemId == null || itemId.isBlank()) continue;

                int owned = countItem(player, itemId);
                int target = req.target();
                int nv = Math.min(target, owned);
                if (prog.getRequirementProgress(si, ri) != nv) {
                    prog.setRequirementProgress(si, ri, nv);
                    changed = true;
                }
            }
        }

        if (changed) {
            data.markDirty();
            for (var entry : new java.util.ArrayList<>(data.getAllProgress().entrySet())) {
                if (entry.getValue().getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) continue;
                var quest = plugin.getTreeLoader().resolveQuest(entry.getKey());
                if (quest != null) {
                    plugin.getQuestManager().checkStageCompletion(player, quest, entry.getValue(), false);
                }
            }
            plugin.getPlayerDataStore().save(data);
        }
    }

    /** 统计背包（含副手与护甲槽）中指定 Oraxen 物品的数量。 */
    private int countItem(Player player, String itemId) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            total += countIn(stack, itemId);
        }
        return total;
    }

    private int countIn(ItemStack stack, String itemId) {
        if (stack == null || stack.getType().isAir()) return 0;
        String id = plugin.getOraxenHook().getItemId(stack);
        return itemId.equalsIgnoreCase(id) ? stack.getAmount() : 0;
    }
}
