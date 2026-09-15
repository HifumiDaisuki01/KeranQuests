package com.keran.quests.hook;

import com.keran.quests.KeranQuests;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Oraxen 挂钩：物品识别与发放。
 *
 * <p>依赖 API（反编译核实，1.218.0）：
 * <pre>
 *   io.th0rgal.oraxen.api.OraxenItems.getIdByItem(ItemStack)  -> String (非 Oraxen 物品返回 null)
 *   io.th0rgal.oraxen.api.OraxenItems.exists(String)          -> boolean
 *   io.th0rgal.oraxen.api.OraxenItems.getItemById(String)     -> ItemBuilder
 *   ItemBuilder.build()                                       -> ItemStack
 * </pre>
 */
public class OraxenHook extends Hook {

    private static final String CLS_ITEMS = "io.th0rgal.oraxen.api.OraxenItems";

    public OraxenHook(KeranQuests plugin) {
        super(plugin, "Oraxen");
    }

    @Override
    public void hook() {
        if (!pluginPresent()) {
            plugin.getLogger().info("未检测到 Oraxen，物品功能将降级。");
            return;
        }
        try {
            Class.forName(CLS_ITEMS);
            // 自检：确认 exists 方法可用
            available = true;
            plugin.getLogger().info("已挂钩 Oraxen（物品识别 + 发放）。");
        } catch (Throwable t) {
            plugin.getLogger().warning("挂钩 Oraxen 失败：" + t.getMessage());
        }
    }

    /** 取物品的 Oraxen ID，非 Oraxen 物品返回 null。 */
    public String getItemId(ItemStack item) {
        if (!available || item == null) return null;
        try {
            Object r = callStatic(CLS_ITEMS, "getIdByItem",
                    new Class<?>[]{ItemStack.class}, item);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 判断某 Oraxen ID 是否存在（配置校验用）。 */
    public boolean exists(String id) {
        if (!available || id == null) return false;
        try {
            Object r = callStatic(CLS_ITEMS, "exists",
                    new Class<?>[]{String.class}, id);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 构建一个 Oraxen 物品的 ItemStack。失败返回 null。 */
    public ItemStack build(String id, int amount) {
        if (!available || id == null) return null;
        try {
            Object builder = callStatic(CLS_ITEMS, "getItemById",
                    new Class<?>[]{String.class}, id);
            if (builder == null) return null;
            Object stack = call(builder, "build", new Class<?>[]{});
            if (stack instanceof ItemStack is) {
                ItemStack copy = is.clone();
                copy.setAmount(Math.max(1, Math.min(64, amount)));
                return copy;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("构建 Oraxen 物品失败 " + id + "：" + t.getMessage());
        }
        return null;
    }

    /** 统计玩家背包中某 Oraxen 物品的总数。 */
    public int countItem(Player player, String oraxenId) {
        if (!available || player == null || oraxenId == null) return 0;
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            String id = getItemId(it);
            if (oraxenId.equals(id)) total += it.getAmount();
        }
        return total;
    }

    /**
     * 从玩家背包移除指定数量的 Oraxen 物品。
     *
     * @return 实际移除的数量
     */
    public int removeItem(Player player, String oraxenId, int amount) {
        if (!available || player == null || oraxenId == null) return 0;
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            String id = getItemId(it);
            if (!oraxenId.equals(id)) continue;
            int take = Math.min(it.getAmount(), remaining);
            it.setAmount(it.getAmount() - take);
            remaining -= take;
            if (it.getAmount() <= 0) player.getInventory().setItem(i, null);
        }
        player.updateInventory();
        return amount - remaining;
    }

    /** 给玩家发放物品。成功返回 true。 */
    public boolean giveItem(Player player, String oraxenId, int amount) {
        if (!available) return false;
        ItemStack stack = build(oraxenId, amount);
        if (stack == null) return false;
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        return true;
    }
}
