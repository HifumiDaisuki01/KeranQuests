package com.keran.quests.config.model.enums;

/**
 * 失败后的重置粒度。
 */
public enum ResetMode {
    /** 整个任务重置，回到阶段一（默认，也是用户确认的行为） */
    WHOLE_QUEST,
    /** 只重置当前阶段 */
    STAGE;

    public static ResetMode parse(String s) {
        if (s == null) return WHOLE_QUEST;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return WHOLE_QUEST;
        }
    }
}
