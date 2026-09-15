package com.keran.quests.hook;

import com.keran.quests.KeranQuests;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

/**
 * Oraxen 挂钩：物品识别与发放。
 *
 * <h3>★ 为什么不能碰 ItemBuilder（血泪教训）</h3>
 * Oraxen 1.218 的 {@code ItemBuilder} 类里，方法签名直接引用了
 * {@code org.bukkit.inventory.meta.components.FoodComponent} ——
 * 那是 <b>Paper 1.20.5+</b> 才有的类。在 1.20.1 服务端上，JVM 在校验
 * {@code ItemBuilder} 类时就会抛 {@link NoClassDefFoundError}。
 * <br>后果：<b>任何</b>对 ItemBuilder 的反射调用（getType / getOraxenMeta /
 * build …）都会失败，而且异常被反射包了好几层，日志里只能看到
 * 「build() 返回 null」这种误导性信息 —— 我为此排查了很久。
 * <br>所以本类【完全不加载 ItemBuilder】，改为：
 * <ol>
 *   <li>物品 <b>识别</b>用 {@code OraxenItems.getIdByItem(ItemStack)}（不碰 ItemBuilder，安全）</li>
 *   <li>物品 <b>构建</b>改为直接读 {@code plugins/Oraxen/items/*.yml}，
 *       取 material + Pack.custom_model_data，自己拼 ItemStack</li>
 * </ol>
 *
 * <p>这样即使 Oraxen 与本服务端版本不匹配，GUI 图标也能正常显示。
 */
public class OraxenHook extends Hook {

    private static final String CLS_ITEMS = "io.th0rgal.oraxen.api.OraxenItems";

    /** 从 Oraxen items 配置里解析出的物品定义：材质 + CMD。 */
    private static final class Def {
        final Material material;
        final int cmd;
        Def(Material material, int cmd) {
            this.material = material;
            this.cmd = cmd;
        }
    }

    /** id -> 定义。首次构建时懒加载，reload 时清空。 */
    private final java.util.Map<String, Def> defs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean defsLoaded = false;

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

    /**
     * 构建一个 Oraxen 物品的 ItemStack。失败返回 null。
     *
     * <p>实现方式见类注释：<b>完全不经过 ItemBuilder</b>，
     * 直接读 Oraxen 的 items 配置 yml 拿 material + custom_model_data，
     * 自己拼 ItemStack。这样在 Oraxen 与服务端版本不匹配时依然可用。
     */
    public ItemStack build(String id, int amount) {
        if (!available || id == null) return null;
        ensureDefsLoaded();

        Def def = defs.get(id);
        if (def == null) {
            lastError = "配置里找不到物品定义 " + id
                    + "（Oraxen items 目录下没有该 id，或格式不含 material）";
            return null;
        }
        if (def.material == null || def.material.isAir()) {
            lastError = id + " 的 material 无效";
            return null;
        }

        ItemStack stack = new ItemStack(def.material, Math.max(1, Math.min(64, amount)));
        if (def.cmd != 0) {
            ItemMeta im = stack.getItemMeta();
            if (im != null) {
                im.setCustomModelData(def.cmd);
                stack.setItemMeta(im);
            }
        }
        lastError = null;
        return stack;
    }

    /** 清空物品定义缓存（Oraxen 重载配置后调用）。 */
    public void invalidateDefs() {
        defs.clear();
        defsLoaded = false;
    }

    /**
     * 扫描 {@code plugins/Oraxen/items/*.yml}，解析每个物品的 material 与 CMD。
     *
     * <p>只关心两件事：用哪个原版材质、CMD 填多少。其余（displayname、
     * lore、模型细节）对 GUI 图标无意义，一律忽略 —— 这样解析逻辑足够简单，
     * 不会因为 Oraxen 配置格式变化而崩。
     */
    private void ensureDefsLoaded() {
        if (defsLoaded) return;
        synchronized (this) {
            if (defsLoaded) return;
            java.io.File itemsDir = new java.io.File(
                    plugin.getDataFolder().getParentFile(), "Oraxen/items");
            if (!itemsDir.isDirectory()) {
                plugin.getLogger().warning("Oraxen items 目录不存在：" + itemsDir.getPath());
                defsLoaded = true;
                return;
            }
            java.io.File[] files = itemsDir.listFiles(
                    (d, n) -> n.toLowerCase().endsWith(".yml") && !n.contains(".bak"));
            if (files == null) files = new java.io.File[0];

            int count = 0;
            for (java.io.File f : files) {
                try (java.io.Reader r = new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                    org.bukkit.configuration.file.YamlConfiguration yml =
                            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(r);
                    for (String key : yml.getKeys(false)) {
                        if (!yml.isConfigurationSection(key)) continue;
                        org.bukkit.configuration.ConfigurationSection sec =
                                yml.getConfigurationSection(key);
                        if (sec == null) continue;

                        String matName = sec.getString("material");
                        if (matName == null || matName.isBlank()) continue;
                        Material mat = Material.matchMaterial(matName.trim().toUpperCase());
                        if (mat == null || mat.isAir()) continue;

                        // CMD 可能在 Pack 节下（常见写法），也可能直接平铺
                        int cmd = 0;
                        org.bukkit.configuration.ConfigurationSection pack =
                                sec.getConfigurationSection("Pack");
                        if (pack != null) {
                            cmd = pack.getInt("custom_model_data", 0);
                        }
                        if (cmd == 0) {
                            cmd = sec.getInt("custom_model_data", 0);
                        }
                        defs.put(key, new Def(mat, cmd));
                        count++;
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("解析 Oraxen 物品文件失败 " + f.getName()
                            + "：" + t.getMessage());
                }
            }
            defsLoaded = true;
            plugin.getLogger().info("已从 Oraxen 配置解析 " + count + " 个物品定义（绕过 ItemBuilder）。");
        }
    }

    /** 最近一次 build 失败的原始原因（供 /kq icon 诊断显示）。 */
    public String getLastError() {
        return lastError;
    }

    private volatile String lastError = null;

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
