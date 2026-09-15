package com.keran.quests.config.model.enums;

/**
 * 任务状态。持久化到 SQLite 的 player_quest.state 字段。
 */
public enum QuestState {
    /** 未解锁（前置未满足，或整树已终结） */
    LOCKED,
    /** 已解锁，可接取 */
    AVAILABLE,
    /** 进行中 */
    ACTIVE,
    /** 已完成 */
    COMPLETED,
    /** 已失败（等待冷却结束后回到 AVAILABLE） */
    FAILED;

    public static QuestState parse(String s) {
        if (s == null) return LOCKED;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return LOCKED;
        }
    }

    public boolean isFinished() {
        return this == COMPLETED;
    }
}
