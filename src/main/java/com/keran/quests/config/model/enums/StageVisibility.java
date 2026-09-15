package com.keran.quests.config.model.enums;

/**
 * 阶段可见性。
 */
public enum StageVisibility {
    /** 全部阶段一开始就可见 */
    FULL,
    /** 默认：完成前一阶段才显示下一阶段 */
    SEQUENTIAL,
    /** 只显示当前进度，不列阶段 */
    HIDDEN;

    public static StageVisibility parse(String s) {
        if (s == null) return SEQUENTIAL;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return SEQUENTIAL;
        }
    }
}
