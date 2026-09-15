package com.keran.quests.hook;

import com.keran.quests.KeranQuests;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * WorldGuard 挂钩：区域判定。
 *
 * <p>依赖 API（反编译核实，7.0.9）：
 * <pre>
 *   com.sk89q.worldguard.WorldGuard.getInstance()
 *       .getPlatform().getRegionContainer()  -> RegionContainer
 *   RegionContainer.createQuery()            -> RegionQuery
 *   RegionQuery.getApplicableRegions(com.sk89q.worldedit.util.Location)
 *                                            -> ApplicableRegionSet
 *   com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(org.bukkit.Location)
 *                                            -> com.sk89q.worldedit.util.Location
 *   遍历 ApplicableRegionSet 得到 ProtectedRegion，region.getId()
 * </pre>
 */
public class WorldGuardHook extends Hook {

    private static final String CLS_WG = "com.sk89q.worldguard.WorldGuard";
    private static final String CLS_ADAPTER = "com.sk89q.worldedit.bukkit.BukkitAdapter";

    public WorldGuardHook(KeranQuests plugin) {
        super(plugin, "WorldGuard");
    }

    @Override
    public void hook() {
        if (!pluginPresent()) {
            plugin.getLogger().info("未检测到 WorldGuard，区域判定功能将降级。");
            return;
        }
        try {
            Class.forName(CLS_WG);
            Class.forName(CLS_ADAPTER);
            available = true;
            plugin.getLogger().info("已挂钩 WorldGuard（区域判定）。");
        } catch (Throwable t) {
            plugin.getLogger().warning("挂钩 WorldGuard 失败：" + t.getMessage());
        }
    }

    /**
     * 取某位置所处的全部区域 id。
     *
     * @return 区域 id 列表；不可用时返回空列表
     */
    public List<String> getRegionIds(Location loc) {
        List<String> out = new ArrayList<>();
        if (!available || loc == null || loc.getWorld() == null) return out;
        try {
            // WorldGuard.getInstance().getPlatform().getRegionContainer()
            Object wg = callStatic(CLS_WG, "getInstance", new Class<?>[]{});
            if (wg == null) return out;
            Object platform = call(wg, "getPlatform", new Class<?>[]{});
            if (platform == null) return out;
            Object container = call(platform, "getRegionContainer", new Class<?>[]{});
            if (container == null) return out;

            // container.createQuery()
            Object query = call(container, "createQuery", new Class<?>[]{});
            if (query == null) return out;

            // BukkitAdapter.adapt(Location)
            Object wgLoc = callStatic(CLS_ADAPTER, "adapt",
                    new Class<?>[]{Location.class}, loc);
            if (wgLoc == null) return out;

            // query.getApplicableRegions(wgLoc)
            Object set = null;
            for (java.lang.reflect.Method m : query.getClass().getMethods()) {
                if (!m.getName().equals("getApplicableRegions")) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    set = m.invoke(query, wgLoc);
                    if (set != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (set == null) return out;

            // 遍历 ApplicableRegionSet（实现 Iterable<ProtectedRegion>）
            if (set instanceof Iterable<?> it) {
                for (Object region : it) {
                    if (region == null) continue;
                    Object id = call(region, "getId", new Class<?>[]{});
                    if (id != null) out.add(String.valueOf(id));
                }
            }
        } catch (Throwable t) {
            if (plugin.getConfig().getBoolean("debug.verbose", false)) {
                plugin.getLogger().warning("区域查询失败：" + t.getMessage());
            }
        }
        return out;
    }

    /** 玩家是否在指定区域内。 */
    public boolean isInRegion(Player player, String regionId) {
        if (player == null || regionId == null) return false;
        for (String id : getRegionIds(player.getLocation())) {
            if (id.equalsIgnoreCase(regionId)) return true;
        }
        return false;
    }

    /** 位置是否在指定区域内（带世界校验，可选）。 */
    public boolean isInRegion(Location loc, String regionId, String worldName) {
        if (loc == null || regionId == null) return false;
        if (worldName != null && loc.getWorld() != null
                && !loc.getWorld().getName().equalsIgnoreCase(worldName)) {
            return false;
        }
        for (String id : getRegionIds(loc)) {
            if (id.equalsIgnoreCase(regionId)) return true;
        }
        return false;
    }

    /** 取区域中心点（用于提醒与显示）。 */
    public Location getRegionCenter(String worldName, String regionId) {
        if (!available) return null;
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        try {
            Object wg = callStatic(CLS_WG, "getInstance", new Class<?>[]{});
            Object platform = call(wg, "getPlatform", new Class<?>[]{});
            Object container = call(platform, "getRegionContainer", new Class<?>[]{});
            if (container == null) return null;

            Object weWorld = callStatic(CLS_ADAPTER, "adapt", new Class<?>[]{World.class}, w);
            if (weWorld == null) return null;

            Object manager = null;
            for (java.lang.reflect.Method m : container.getClass().getMethods()) {
                if (!m.getName().equals("get") || m.getParameterCount() != 1) continue;
                try {
                    manager = m.invoke(container, weWorld);
                    if (manager != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (manager == null) return null;

            Object region = call(manager, "getRegion", new Class<?>[]{String.class}, regionId);
            if (region == null) return null;

            Object min = call(region, "getMinimumPoint", new Class<?>[]{});
            Object max = call(region, "getMaximumPoint", new Class<?>[]{});
            if (min == null || max == null) return null;

            double x1 = toDouble(call(min, "getX", new Class<?>[]{}));
            double y1 = toDouble(call(min, "getY", new Class<?>[]{}));
            double z1 = toDouble(call(min, "getZ", new Class<?>[]{}));
            double x2 = toDouble(call(max, "getX", new Class<?>[]{}));
            double y2 = toDouble(call(max, "getY", new Class<?>[]{}));
            double z2 = toDouble(call(max, "getZ", new Class<?>[]{}));
            return new Location(w, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
        } catch (Throwable t) {
            return null;
        }
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0;
    }
}
