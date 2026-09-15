package com.keran.quests.config.model.enums;

/**
 * 要求类型。对应 yml 里 requirements 下每一条的 type 字段。
 */
public enum RequirementType {
    /** 击杀 MythicMobs 怪物 */
    MM_KILL,
    /** 在 WG 区域内停留 N 秒 */
    REGION_STAY,
    /** 进入 WG 区域即完成 */
    REGION_ENTER,
    /** 进入 WG 区域即失败 */
    REGION_FORBIDDEN,
    /** 持有/收集 Oraxen 物品 */
    ORAXEN_ITEM,
    /** 第三方插件通过指令触发 */
    CUSTOM_TRIGGER,
    /** 与 Citizens NPC 对话 */
    NPC_TALK,
    /** 拥有权限 */
    PERMISSION,
    /** 击杀玩家（可限定区域 + 防刷） */
    PLAYER_KILL;

    public static RequirementType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
