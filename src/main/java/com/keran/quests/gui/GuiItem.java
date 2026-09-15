package com.keran.quests.gui;

import com.keran.quests.KeranQuests;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI 物品构建工具。
 *
 * <p>统一的构建入口：优先用 Oraxen 材质，缺失时回退到原版材质，保证 GUI 永远能打开。
 */
public class GuiItem {

    private final KeranQuests plugin;
    private final String oraxenId;
    private final Material fallback;
    private String name = " ";
    private final List<String> lore = new ArrayList<>();
    private int amount = 1;
    /** 点击动作标识（写在 PDC 里，供 InventoryClickEvent 识别） */
    private String action = null;
    private String data = null;

    public GuiItem(KeranQuests plugin, String oraxenId, Material fallback) {
        this.plugin = plugin;
        this.oraxenId = oraxenId;
        this.fallback = fallback;
    }

    public GuiItem name(String name) {
        this.name = name;
        return this;
    }

    public GuiItem lore(String... lines) {
        for (String l : lines) lore.add(l);
        return this;
    }

    public GuiItem lore(List<String> lines) {
        if (lines != null) lore.addAll(lines);
        return this;
    }

    public GuiItem amount(int amount) {
        this.amount = Math.max(1, Math.min(64, amount));
        return this;
    }

    /** 设置点击动作。 */
    public GuiItem action(String action, String data) {
        this.action = action;
        this.data = data;
        return this;
    }

    public String getAction() {
        return action;
    }

    public String getData() {
        return data;
    }

    /** 构建 ItemStack。 */
    public ItemStack build() {
        ItemStack stack = null;
        // 优先 Oraxen
        if (oraxenId != null && !oraxenId.isBlank()) {
            stack = plugin.getOraxenHook().build(stripPrefix(oraxenId), amount);
        }
        // 回退
        if (stack == null) {
            stack = new ItemStack(fallback == null ? Material.PAPER : fallback, amount);
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(com.keran.quests.util.Text.color(name));
            }
            if (!lore.isEmpty()) {
                List<String> colored = new ArrayList<>();
                for (String l : lore) colored.add(com.keran.quests.util.Text.color(l));
                meta.setLore(colored);
            }
            // 把 action 写进 PDC
            if (action != null) {
                meta.getPersistentDataContainer().set(
                        plugin.getActionKey(),
                        PersistentDataType.STRING,
                        action + (data == null ? "" : "|" + data));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String stripPrefix(String id) {
        return id.startsWith("oraxen:") ? id.substring("oraxen:".length()) : id;
    }
}
