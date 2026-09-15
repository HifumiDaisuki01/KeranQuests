package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家冻结管理 —— 对话期间锁住移动。
 */
public class FreezeManager {

    private final KeranQuests plugin;
    /** uuid -> 解冻时间戳（ms） */
    private final Map<UUID, Long> frozen = new HashMap<>();

    public FreezeManager(KeranQuests plugin) {
        this.plugin = plugin;
    }

    /** 冻结玩家若干 tick。 */
    public void freeze(UUID uuid, int ticks) {
        if (uuid == null) return;
        frozen.put(uuid, System.currentTimeMillis() + ticks * 50L);
    }

    public void unfreeze(UUID uuid) {
        frozen.remove(uuid);
    }

    public boolean isFrozen(UUID uuid) {
        Long until = frozen.get(uuid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            frozen.remove(uuid);
            return false;
        }
        return true;
    }

    public void clear() {
        frozen.clear();
    }
}
