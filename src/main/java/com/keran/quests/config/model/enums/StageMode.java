package com.keran.quests.config.model.enums;

/**
 * 阶段内多条要求的组合方式。
 */
public enum StageMode {
    /** 全部要求都要满足（默认） */
    ALL,
    /** 满足其中 N 条即可 */
    ANY;

    public static StageMode parse(String s) {
        if (s == null) return ALL;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return ALL;
        }
    }
}
