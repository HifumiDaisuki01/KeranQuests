package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.RequirementType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 区域停留计时器 —— 处理 REGION_STAY / REGION_FORBIDDEN / 超时失败。
 *
 * <p>每秒跑一次：
 * <ul>
 *   <li>检查进行中任务里所有 REGION_STAY 要求，玩家在区域内则累加秒数，离开则清零</li>
 *   <li>检查 REGION_FORBIDDEN，进入即判失败</li>
 *   <li>检查 time_limit，超时即判失败</li>
 *   <li>检查 forbidden_regions（任务级），进入即失败</li>
 * </ul>
 */
public class RegionRunner {

    private final KeranQuests plugin;
    private BukkitTask task;

    /** 玩家当前所处的区域缓存，减少 WG 查询频率（玩家 -> "world:region" -> bool） */
    private final Map<UUID, Map<String, Boolean>> regionCache = new HashMap<>();

    /** 玩家上次查询区域时的坐标，位移未超过 cache_radius 时复用缓存结果 */
    private final Map<UUID, org.bukkit.Location> lastQueryLoc = new HashMap<>();

    public RegionRunner(KeranQuests plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        // 检测周期可由 config.yml 的 region.check_interval 配置（单位 tick，默认 20 = 1 秒）
        long interval = Math.max(1L, plugin.getConfig().getLong("region.check_interval", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        regionCache.clear();
        lastQueryLoc.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        // 注意：不再每 tick 清空区域缓存。
        // 缓存失效改为「玩家水平位移超过 region.cache_radius」时触发（见 inRegion），
        // 这样站着不动时不会每秒重复查询 WorldGuard，而走动超过阈值后立刻重查。
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.getPlayerData(player);
            if (data == null) continue;

            boolean anyActive = false;
            for (Map.Entry<String, QuestProgress> e : new java.util.ArrayList<>(data.getAllProgress().entrySet())) {
                QuestProgress p = e.getValue();
                if (p.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) continue;
                anyActive = true;
                Quest quest = plugin.getTreeLoader().resolveQuest(e.getKey());
                if (quest == null) continue;
                tickQuest(player, data, quest, p, now);
            }
            if (!anyActive) {
                regionCache.remove(player.getUniqueId());
                lastQueryLoc.remove(player.getUniqueId());
            }
        }
    }

    private void tickQuest(Player player, PlayerData data, Quest quest, QuestProgress p, long now) {
        // ---- 超时判定 ----
        if (quest.getTimeLimit() > 0 && p.getStartedAt() > 0) {
            long elapsed = (now - p.getStartedAt()) / 1000;
            if (elapsed >= quest.getTimeLimit()) {
                plugin.getQuestManager().fail(player, quest, "&timeout");
                return;
            }
        }

        // ---- 任务级禁止区域 ----
        for (Quest.ForbiddenRegion fr : quest.getForbiddenRegions()) {
            if (inRegion(player, fr.getRegion(), fr.getWorld())) {
                plugin.getQuestManager().fail(player, quest,
                        "&forbidden_region（" + fr.getRegion() + "）");
                return;
            }
        }

        // ---- 阶段级要求 ----
        int si = p.getStageIndex();
        if (si >= quest.getStages().size()) return;
        QuestStage stage = quest.getStages().get(si);

        for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
            Requirement req = stage.getRequirements().get(ri);
            boolean inside = inRegion(player, req.getRegion(), req.getWorld());

            if (req.getType() == RequirementType.REGION_STAY) {
                int cur = p.getRequirementProgress(si, ri);
                if (inside) {
                    int nv = Math.min(req.getSeconds(), cur + 1);
                    if (nv != cur) {
                        p.setRequirementProgress(si, ri, nv);
                        data.markDirty();
                        // 显示进度（每 1/3 进度提示一次，避免刷屏）
                        if (nv % Math.max(1, req.getSeconds() / 3) == 0 || nv >= req.getSeconds()) {
                            Text.send(player, "&7[" + Text.strip(quest.getName()) + "] "
                                    + "&f" + Text.strip(req.describe())
                                    + " &a" + nv + "&7/&f" + req.getSeconds());
                        }
                    }
                } else if (cur > 0) {
                    // 离开区域：清零（你确认的"离开则中断"）
                    p.setRequirementProgress(si, ri, 0);
                    data.markDirty();
                    Text.send(player, "&7[" + Text.strip(quest.getName()) + "] "
                            + "&c离开了区域，计时清零。");
                }
            } else if (req.getType() == RequirementType.REGION_ENTER) {
                if (inside && p.getRequirementProgress(si, ri) < 1) {
                    p.setRequirementProgress(si, ri, 1);
                    data.markDirty();
                }
            } else if (req.getType() == RequirementType.REGION_FORBIDDEN) {
                if (inside) {
                    plugin.getQuestManager().fail(player, quest,
                            "&forbidden_region（" + req.getRegion() + "）");
                    return;
                }
            }
        }

        // 区域类进度变化后检查阶段完成
        plugin.getQuestManager().checkStageCompletion(player, quest, p, false);
    }

    /** 判断玩家是否在某区域（带缓存，每秒刷新）。 */
    private boolean inRegion(Player player, String region, String world) {
        if (region == null || region.isBlank()) return false;
        if (world != null && player.getWorld() != null
                && !player.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        UUID uid = player.getUniqueId();
        Map<String, Boolean> cache = regionCache.computeIfAbsent(uid, k -> new HashMap<>());
        String key = player.getWorld().getName() + ":" + region;

        Boolean cached = cache.get(key);
        if (cached != null) {
            // 缓存未过期（玩家移动距离未超过 cache_radius）→ 直接复用
            if (!cacheExpired(player, uid)) return cached;
            // 过期则重查，避免走出去很远还拿旧结果
            cache.remove(key);
        }

        boolean r = plugin.getWorldGuardHook().isInRegion(player, region);
        cache.put(key, r);
        rememberLoc(player, uid);
        return r;
    }

    /** 玩家自上次区域查询以来移动距离是否已超过 cache_radius 配置。 */
    private boolean cacheExpired(Player player, UUID uid) {
        double radius = plugin.getConfig().getDouble("region.cache_radius", 8.0D);
        if (radius <= 0) return true;   // 配置为 0 = 不使用坐标缓存
        org.bukkit.Location last = lastQueryLoc.get(uid);
        if (last == null) return true;
        if (last.getWorld() == null || player.getWorld() == null) return true;
        if (!last.getWorld().getName().equals(player.getWorld().getName())) return true;
        // 只比较 X/Z 的水平位移，垂直方向（上楼下楼）不影响区域判定
        double dx = last.getX() - player.getLocation().getX();
        double dz = last.getZ() - player.getLocation().getZ();
        return (dx * dx + dz * dz) > radius * radius;
    }

    private void rememberLoc(Player player, UUID uid) {
        lastQueryLoc.put(uid, player.getLocation().clone());
    }

    /** 每个 tick 周期清空区域缓存（在 tick 开头调用会更准确，这里用简单策略）。 */
    public void clearCache() {
        regionCache.clear();
    }
}
