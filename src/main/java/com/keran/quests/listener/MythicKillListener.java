package com.keran.quests.listener;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.config.model.enums.RequirementType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.List;
import java.util.Map;

/**
 * MythicMobs 击杀监听 —— 处理 {@code mm_kill} 类型要求。
 *
 * <h3>为什么要用反射</h3>
 * MythicMobs 只作为 {@code compileOnly} 依赖，运行时若版本不符（或插件被移除），
 * 直接引用 API 类会导致 {@code NoClassDefFoundError} 把整个监听器打崩。
 * 因此这里全部走反射，找不到 API 就静默降级。
 *
 * <h3>为什么监听 {@link EntityDeathEvent} 而不是 {@code MythicMobDeathEvent}</h3>
 * {@code MythicMobDeathEvent} 在部分 MM 版本里被 cancel 掉或时序靠后，且需要
 * 直接 import MM 的类。{@code EntityDeathEvent} 是 Bukkit 原生事件，配合反射
 * 读取实体上的 MM 元数据更稳。判定顺序：
 * <ol>
 *   <li>先按 Bukkit 实体类型名匹配（配置写 {@code ZOMBIE} 这种）</li>
 *   <li>再按 MythicMobs 内部名匹配（配置写 {@code sw92} 这种）</li>
 * </ol>
 */
public class MythicKillListener implements Listener {

    private final KeranQuests plugin;

    public MythicKillListener(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();

        // 击杀者可能是玩家；也可能是被玩家驯服的狼等 —— 这里只认玩家本人
        if (killer == null) return;
        if (!killer.isOnline()) return;

        // 解析怪物的 MM 内部名 + 实体类型名
        String mmName = resolveMythicName(dead);        // 可能是 null（非 MM 怪）
        String entityType = dead.getType().name();

        PlayerData data = plugin.getPlayerData(killer);
        if (data == null) return;

        boolean changed = false;
        // 遍历该玩家全部进行中的任务
        for (Map.Entry<String, QuestProgress> e : new java.util.ArrayList<>(data.getAllProgress().entrySet())) {
            QuestProgress prog = e.getValue();
            if (prog.getState() != QuestState.ACTIVE) continue;
            Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
            if (quest == null) continue;

            int si = prog.getStageIndex();
            List<QuestStage> stages = quest.getStages();
            if (si >= stages.size()) continue;
            QuestStage stage = stages.get(si);

            for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
                Requirement req = stage.getRequirements().get(ri);
                if (req.getType() != RequirementType.MM_KILL) continue;

                // 匹配怪物名：先看 MM 内部名，再看实体类型
                boolean match = matches(req, mmName, entityType);
                if (!match) continue;

                int cur = prog.getRequirementProgress(si, ri);
                if (cur >= req.target()) continue;
                prog.setRequirementProgress(si, ri, Math.min(req.target(), cur + 1));
                changed = true;
            }

            // 失败条件：击杀数超限
            if (quest.getMaxKills() >= 0) {
                checkMaxKills(killer, quest, prog, req0MobName(quest, mmName, entityType));
            }
        }

        if (changed) {
            data.markDirty();
            // 逐任务复查是否阶段完成
            for (Map.Entry<String, QuestProgress> e : new java.util.ArrayList<>(data.getAllProgress().entrySet())) {
                if (e.getValue().getState() != QuestState.ACTIVE) continue;
                Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
                if (quest != null) plugin.getQuestManager().checkStageCompletion(killer, quest, e.getValue(), false);
            }
            plugin.getPlayerDataStore().save(data);
        }
    }

    /** 判定配置里的 mobs 列表是否命中该怪物。 */
    private boolean matches(Requirement req, String mmName, String entityType) {
        List<String> mobs = req.getMobs();
        if (mobs == null || mobs.isEmpty()) {
            // 未指定怪物 = 任意 MM 怪
            return mmName != null;
        }
        for (String m : mobs) {
            if (m == null || m.isBlank()) continue;
            // MM 内部名（推荐用法）
            if (mmName != null && m.equalsIgnoreCase(mmName)) return true;
            // 原版实体类型，允许带或不带 minecraft: 前缀
            String clean = m.contains(":") ? m.substring(m.indexOf(':') + 1) : m;
            if (clean.equalsIgnoreCase(entityType)) return true;
        }
        return false;
    }

    /** max_kills 失败判定用：命中时返回怪物名，否则返回 null。 */
    private String req0MobName(Quest quest, String mmName, String entityType) {
        List<String> types = quest.getMaxKillTypes();
        if (types == null || types.isEmpty()) return mmName != null ? mmName : entityType;
        for (String t : types) {
            if (t == null) continue;
            String clean = t.contains(":") ? t.substring(t.indexOf(':') + 1) : t;
            if (clean.equalsIgnoreCase(entityType)) return clean;
            if (mmName != null && t.equalsIgnoreCase(mmName)) return mmName;
        }
        return null;
    }

    /**
     * 统计本任务已击杀的目标怪物总数，超过 {@code max_kills} 则判定失败。
     *
     * <p>统计口径：<b>只累加 {@code mm_kill} 类型要求的进度</b>（含已完成阶段）。
     * 早期版本把所有类型（采集、区域停留等）一并相加，导致收集物品也会被误判为
     * "击杀超标"，这里按阶段 + 要求类型精确过滤。
     */
    private void checkMaxKills(Player killer, Quest quest, QuestProgress prog, String mobName) {
        if (mobName == null) return;
        if (quest.getMaxKills() < 0) return;

        int total = 0;
        List<QuestStage> stages = quest.getStages();
        for (Map.Entry<Integer, Map<Integer, Integer>> se : prog.getAllRequirementProgress().entrySet()) {
            int stageIdx = se.getKey();
            if (stageIdx < 0 || stageIdx >= stages.size()) continue;
            List<Requirement> reqs = stages.get(stageIdx).getRequirements();
            for (Map.Entry<Integer, Integer> re : se.getValue().entrySet()) {
                int reqIdx = re.getKey();
                if (reqIdx < 0 || reqIdx >= reqs.size()) continue;
                // 只统计击杀类要求，避免把物品/区域进度算进来
                if (reqs.get(reqIdx).getType() != RequirementType.MM_KILL) continue;
                total += re.getValue();
            }
        }
        if (total > quest.getMaxKills()) {
            plugin.getQuestManager().fail(killer, quest,
                    "&too_many_kills（" + total + "/" + quest.getMaxKills() + "）");
        }
    }

    // ==================================================================
    //  反射：读取 MythicMobs 内部名
    // ==================================================================

    /**
     * 按优先级尝试多种路径取 MM 内部名：
     * <ol>
     *   <li>{@code MythicBukkit.inst().getMobManager().getActiveMob(uuid)}
     *       → {@code getType().getInternalName()}</li>
     *   <li>{@code MythicBukkit.inst().getMobManager().getMythicMobInstance(entity)}
     *       → {@code getMobType()}</li>
     * </ol>
     *
     * @return MM 内部名；非 MM 怪物或 API 不可用时返回 null
     */
    private String resolveMythicName(LivingEntity entity) {
        try {
            Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mythicBukkit.getMethod("inst").invoke(null);
            if (inst == null) return null;
            Object mobManager = inst.getClass().getMethod("getMobManager").invoke(inst);
            if (mobManager == null) return null;

            // 路径 1：getActiveMob(UUID)
            try {
                Object activeMob = mobManager.getClass()
                        .getMethod("getActiveMob", java.util.UUID.class)
                        .invoke(mobManager, entity.getUniqueId());
                if (activeMob != null) {
                    // ActiveMob#getType() 优先，回退 getMobType()
                    Object type = tryCall(activeMob, "getType");
                    if (type == null) type = tryCall(activeMob, "getMobType");
                    if (type != null) {
                        Object name = tryCall(type, "getInternalName");
                        if (name != null) return String.valueOf(name);
                        if (type instanceof String s) return s;
                    }
                    // 退化：ActiveMob#getMobType() 直接返回 String
                    Object s = tryCall(activeMob, "getMobType");
                    if (s instanceof String str && !str.isBlank()) return str;
                }
            } catch (Throwable ignored) {
                // 换下一条路径
            }

            // 路径 2：getMythicMobInstance(Entity)
            try {
                Object mm = mobManager.getClass()
                        .getMethod("getMythicMobInstance", org.bukkit.entity.Entity.class)
                        .invoke(mobManager, entity);
                if (mm != null) {
                    Object type = tryCall(mm, "getType");
                    if (type != null) {
                        Object name = tryCall(type, "getInternalName");
                        if (name != null) return String.valueOf(name);
                    }
                }
            } catch (Throwable ignored) {
                // 放弃
            }
        } catch (Throwable ignored) {
            // MythicMobs 未安装或版本差异 —— 静默降级
        }
        return null;
    }

    /** 尝试无参调用，失败返回 null。 */
    private Object tryCall(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
