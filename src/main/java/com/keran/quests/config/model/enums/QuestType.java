package com.keran.quests.config.model.enums;

/**
 * 任务类型。
 */
public enum QuestType {
    /** 主线：默认同时只能进行 1 个 */
    MAIN,
    /** 支线：并发上限可配 */
    SIDE;

    public static QuestType parse(String s) {
        if (s == null) return SIDE;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return SIDE;
        }
    }
}
