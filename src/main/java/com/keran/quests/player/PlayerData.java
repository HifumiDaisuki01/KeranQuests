package com.keran.quests.player;

import com.keran.quests.config.model.enums.QuestState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家档案。持有该玩家在全部任务树中的进度。
 *
 * <p>内存对象，由 {@link PlayerDataStore} 负责落盘。
 */
public class PlayerData {

    private final UUID uuid;
    private final String name;

    /** "tree:quest" -> 任务进度 */
    private final Map<String, QuestProgress> progress = new HashMap<>();
    /** "treeId:key" -> 任务树级状态（互斥选择、终结标记） */
    private final Map<String, String> treeState = new HashMap<>();
    /** "tree:quest:kind" -> 冷却解禁时间戳(ms) */
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** 玩家主动追踪的任务 fullId（影响 %kq_current%） */
    private String trackedQuest = null;
    /** 用于批量保存的脏标记 */
    private boolean dirty = false;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ------------------------------------------------------------------
    //  进度
    // ------------------------------------------------------------------

    public QuestProgress getProgress(String fullId) {
        return progress.get(fullId);
    }

    public QuestProgress getOrCreateProgress(String fullId) {
        return progress.computeIfAbsent(fullId, k -> new QuestProgress(fullId));
    }

    public Map<String, QuestProgress> getAllProgress() {
        return progress;
    }

    public void removeProgress(String fullId) {
        progress.remove(fullId);
        markDirty();
    }

    // ------------------------------------------------------------------
    //  任务树级状态
    // ------------------------------------------------------------------

    public String getTreeState(String treeId, String key) {
        return treeState.get(treeId + ":" + key);
    }

    public void setTreeState(String treeId, String key, String value) {
        treeState.put(treeId + ":" + key, value);
        markDirty();
    }

    public Map<String, String> getAllTreeState() {
        return treeState;
    }

    /** 整树是否已终结（选过 03A 之类的终止分支）。 */
    public boolean isTreeTerminated(String treeId) {
        return "true".equals(getTreeState(treeId, "terminated"));
    }

    public void terminateTree(String treeId) {
        setTreeState(treeId, "terminated", "true");
    }

    // ------------------------------------------------------------------
    //  冷却
    // ------------------------------------------------------------------

    public long getCooldownUntil(String fullId, String kind) {
        Long v = cooldowns.get(fullId + ":" + kind);
        return v == null ? 0L : v;
    }

    public void setCooldownUntil(String fullId, String kind, long until) {
        cooldowns.put(fullId + ":" + kind, until);
        markDirty();
    }

    public void clearCooldown(String fullId, String kind) {
        cooldowns.remove(fullId + ":" + kind);
        markDirty();
    }

    public Map<String, Long> getAllCooldowns() {
        return cooldowns;
    }

    /** 剩余冷却秒数（0 表示已结束）。 */
    public int getCooldownRemaining(String fullId, String kind) {
        long until = getCooldownUntil(fullId, kind);
        long remain = until - System.currentTimeMillis();
        return remain <= 0 ? 0 : (int) Math.ceil(remain / 1000.0);
    }

    // ------------------------------------------------------------------
    //  追踪
    // ------------------------------------------------------------------

    public String getTrackedQuest() {
        return trackedQuest;
    }

    public void setTrackedQuest(String fullId) {
        this.trackedQuest = fullId;
        markDirty();
    }

    // ------------------------------------------------------------------
    //  杂项
    // ------------------------------------------------------------------

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void markClean() {
        this.dirty = false;
    }

    /** 判断某任务是否已完成。 */
    public boolean isCompleted(String fullId) {
        QuestProgress p = progress.get(fullId);
        return p != null && p.getState() == QuestState.COMPLETED;
    }
}
