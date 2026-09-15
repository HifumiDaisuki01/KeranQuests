package com.keran.quests.player;

import com.keran.quests.config.model.enums.QuestState;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个任务的运行时进度。
 */
public class QuestProgress {

    private final String fullId;
    private QuestState state = QuestState.LOCKED;
    /** 当前阶段序号（0-based） */
    private int stageIndex = 0;
    private long startedAt = 0L;
    private long finishedAt = 0L;
    private String failReason = null;

    /** 阶段序号 -> 要求序号 -> 进度值 */
    private final Map<Integer, Map<Integer, Integer>> requirementProgress = new HashMap<>();

    /** 自定义 trigger key -> 已触发次数 */
    private final Map<String, Integer> triggerCounts = new HashMap<>();

    /** 已完成的阶段 id 集合（用于 stage_visibility: FULL 时的勾选显示） */
    private final java.util.Set<Integer> completedStages = new java.util.LinkedHashSet<>();

    /**
     * 已经处理过（已播报 / 已弹抉择 GUI）的阶段序号集合。
     *
     * <p>用于防止 {@code checkStageCompletion} 被定时器反复调用时重复播报、重复弹窗。
     * 与 {@link #completedStages} 的区别：抉择节点会停留在原地等待玩家选择，
     * 此时阶段已完成但未推进，需要独立标记来避免重复处理。
     */
    private final java.util.Set<Integer> handledStages = new java.util.LinkedHashSet<>();

    public QuestProgress(String fullId) {
        this.fullId = fullId;
    }

    // ---------------- 要求进度 ----------------

    public int getRequirementProgress(int stage, int req) {
        Map<Integer, Integer> m = requirementProgress.get(stage);
        if (m == null) return 0;
        return m.getOrDefault(req, 0);
    }

    public void setRequirementProgress(int stage, int req, int value) {
        requirementProgress.computeIfAbsent(stage, k -> new HashMap<>()).put(req, value);
    }

    public int addRequirementProgress(int stage, int req, int delta) {
        int cur = getRequirementProgress(stage, req);
        int nv = cur + delta;
        setRequirementProgress(stage, req, nv);
        return nv;
    }

    public Map<Integer, Map<Integer, Integer>> getAllRequirementProgress() {
        return requirementProgress;
    }

    /** 清空某阶段及之后的要求进度（失败重置 / 阶段重来时用）。 */
    public void clearFromStage(int fromStage) {
        requirementProgress.keySet().removeIf(k -> k >= fromStage);
        completedStages.removeIf(k -> k >= fromStage);
        handledStages.removeIf(k -> k >= fromStage);
    }

    public void clearAllProgress() {
        requirementProgress.clear();
        completedStages.clear();
        handledStages.clear();
        triggerCounts.clear();
        stageIndex = 0;
    }

    // ---------------- trigger ----------------

    public int getTriggerCount(String key) {
        return triggerCounts.getOrDefault(key, 0);
    }

    public int addTriggerCount(String key) {
        int nv = getTriggerCount(key) + 1;
        triggerCounts.put(key, nv);
        return nv;
    }

    public Map<String, Integer> getAllTriggerCounts() {
        return triggerCounts;
    }

    // ---------------- 阶段 ----------------

    public boolean isStageCompleted(int stage) {
        return completedStages.contains(stage);
    }

    public void markStageCompleted(int stage) {
        completedStages.add(stage);
    }

    public java.util.Set<Integer> getCompletedStages() {
        return completedStages;
    }

    /** 该阶段是否已经处理过（已播报完成 / 已弹出抉择界面）。 */
    public boolean isStageHandled(int stage) {
        return handledStages.contains(stage);
    }

    /** 标记该阶段已经处理过，避免定时轮询重复触发。 */
    public void markStageHandled(int stage) {
        handledStages.add(stage);
    }

    public java.util.Set<Integer> getHandledStages() {
        return handledStages;
    }

    // ---------------- 基本字段 ----------------

    public String getFullId() {
        return fullId;
    }

    public QuestState getState() {
        return state;
    }

    public void setState(QuestState state) {
        this.state = state;
    }

    public int getStageIndex() {
        return stageIndex;
    }

    public void setStageIndex(int stageIndex) {
        this.stageIndex = Math.max(0, stageIndex);
    }

    public void advanceStage() {
        this.stageIndex++;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }
}
