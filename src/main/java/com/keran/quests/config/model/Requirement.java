package com.keran.quests.config.model;

import com.keran.quests.config.model.enums.RequirementType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条阶段要求。不可变模型。
 *
 * <p>用 {@link #fromConfig(ConfigurationSection)} 从 yml 解析。
 * 不同类型会填充不同字段，未涉及的字段保持默认值。
 */
public class Requirement {

    private final RequirementType type;

    /** 显示名（用于 GUI 与占位符）。未配置时由 {@link #describe()} 自动生成。 */
    private final String displayName;

    /** 补充说明（可选，展示在 GUI lore 中）。 */
    private final String description;

    // ---- 通用 ----
    /** 目标数量（击杀数 / 物品数 / 玩家击杀数） */
    private final int count;
    /** 限定区域（WorldGuard region id），null 表示不限 */
    private final String region;
    /** 限定世界，null 表示不限 */
    private final String world;

    // ---- REGION_STAY ----
    /** 需停留秒数 */
    private final int seconds;

    // ---- MM_KILL ----
    /** MM 怪物内部名列表 */
    private final List<String> mobs;
    /** MM_KILL 的匹配模式：ANY_OF_TYPES（任一累计）/ ALL_TYPES（每种都要） */
    private final String killMode;

    // ---- ORAXEN_ITEM ----
    /** Oraxen 物品 ID */
    private final String item;
    /** 完成后是否消耗 */
    private final boolean consume;

    // ---- CUSTOM_TRIGGER ----
    /** 自定义 trigger key */
    private final String key;

    // ---- NPC_TALK ----
    /** NPC 名 */
    private final String npcName;

    // ---- PERMISSION ----
    private final String permission;

    // ---- PLAYER_KILL 防刷 ----
    /** 同一对手多少秒内只计一次，0 = 不防刷 */
    private final int sameVictimCooldown;

    private Requirement(RequirementType type, String displayName, String description, int count,
                        String region, String world, int seconds, List<String> mobs, String killMode,
                        String item, boolean consume, String key, String npcName,
                        String permission, int sameVictimCooldown) {
        this.type = type;
        this.displayName = displayName;
        this.description = description;
        this.count = count;
        this.region = region;
        this.world = world;
        this.seconds = seconds;
        this.mobs = mobs == null ? new ArrayList<>() : mobs;
        this.killMode = killMode == null ? "ANY_OF_TYPES" : killMode;
        this.item = item;
        this.consume = consume;
        this.key = key;
        this.npcName = npcName;
        this.permission = permission;
        this.sameVictimCooldown = sameVictimCooldown;
    }

    public static Requirement fromConfig(ConfigurationSection sec) {
        if (sec == null) return null;
        RequirementType type = RequirementType.parse(sec.getString("type"));
        if (type == null) return null;

        // 展示名：同时兼容 name / display（示例 yml 使用 display，旧配置可能用 name）
        String displayName = firstNonBlank(sec.getString("name", null), sec.getString("display", null));
        // 补充说明（可选，仅用于展示，不参与判定）
        String description = firstNonBlank(sec.getString("description", null), null);
        int count = sec.getInt("count", 1);
        String region = sec.getString("region", null);
        String world = sec.getString("world", null);
        int seconds = sec.getInt("seconds", 3);
        List<String> mobs = sec.getStringList("mobs");
        String killMode = sec.getString("mode", "ANY_OF_TYPES");
        String item = sec.getString("item", null);
        boolean consume = sec.getBoolean("consume", false);
        String key = sec.getString("key", null);
        // NPC 名：同时兼容 npc_name / npc
        String npcName = firstNonBlank(sec.getString("npc_name", null), sec.getString("npc", null));
        String permission = sec.getString("permission", null);

        int sameVictim = 0;
        ConfigurationSection af = sec.getConfigurationSection("anti_farm");
        if (af != null) {
            sameVictim = af.getInt("same_victim_cooldown", 0);
        }

        return new Requirement(type, displayName, description, count, region, world, seconds, mobs,
                killMode, item, consume, key, npcName, permission, sameVictim);
    }

    /** 返回第一个非空白的字符串，全为空时返回 null。 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    // ------------------------------------------------------------------
    //  展示
    // ------------------------------------------------------------------

    /** 生成人类可读的要求描述（GUI lore 与占位符使用）。 */
    public String describe() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        switch (type) {
            case MM_KILL:
                return "击杀 " + String.join("/", mobs) + " " + count + " 只";
            case REGION_STAY:
                return "在 " + region + " 停留 " + seconds + " 秒";
            case REGION_ENTER:
                return "前往 " + region;
            case REGION_FORBIDDEN:
                return "不要进入 " + region;
            case ORAXEN_ITEM:
                return "收集 " + item + " " + count + " 个";
            case CUSTOM_TRIGGER:
                return "完成 " + key;
            case NPC_TALK:
                return "与 " + npcName + " 对话";
            case PERMISSION:
                return "拥有权限 " + permission;
            case PLAYER_KILL:
                return "击杀玩家 " + count + " 次";
            default:
                return type.name();
        }
    }

    /** 该要求的目标进度值（用于 GUI 进度显示）。 */
    public int target() {
        switch (type) {
            case MM_KILL:
            case ORAXEN_ITEM:
            case PLAYER_KILL:
                return Math.max(1, count);
            case REGION_STAY:
                return Math.max(1, seconds);
            default:
                return 1;
        }
    }

    // ---------------- getters ----------------

    public RequirementType getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public int getCount() {
        return count;
    }

    public String getRegion() {
        return region;
    }

    public String getWorld() {
        return world;
    }

    public int getSeconds() {
        return seconds;
    }

    public List<String> getMobs() {
        return mobs;
    }

    public String getKillMode() {
        return killMode;
    }

    public String getItem() {
        return item;
    }

    public boolean isConsume() {
        return consume;
    }

    public String getKey() {
        return key;
    }

    public String getNpcName() {
        return npcName;
    }

    public String getPermission() {
        return permission;
    }

    public int getSameVictimCooldown() {
        return sameVictimCooldown;
    }
}
