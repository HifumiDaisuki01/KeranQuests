package com.keran.quests.hook;

import com.keran.quests.KeranQuests;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Lightweight economy hook — talks to the Vault economy service if present.
 */
public class VaultHook extends Hook {

    private Object economy = null;

    public VaultHook(KeranQuests plugin) {
        super(plugin, "Vault");
    }

    @Override
    public void hook() {
        if (!pluginPresent()) {
            plugin.getLogger().info("未检测到 Vault，经济奖励将跳过。");
            return;
        }
        tryRegister("启动时");

        // 关键：经济插件（LEconomy / Essentials / CMI 等）的 onEnable 顺序
        // 可能晚于本插件，此时 getRegistration 会返回 null。这里做几次延迟重试，
        // 避免误判为"没有经济服务"而永久跳过发钱。
        if (!available) retry(0);
    }

    /** 重试计划：2s / 5s / 10s / 20s / 40s。 */
    private static final int[] RETRY_DELAYS = {40, 100, 200, 400, 800};

    private void retry(int attempt) {
        if (available) return;
        if (attempt >= RETRY_DELAYS.length) {
            plugin.getLogger().info(
                    "Vault 经济服务始终未就绪 —— 经济奖励将跳过（其他奖励不受影响）。");
            return;
        }
        int delay = RETRY_DELAYS[attempt];
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (available) return;
            tryRegister("延迟重试 +" + delay + "t");
            if (!available) retry(attempt + 1);
        }, delay);
    }

    /** 尝试注册一次。 */
    private void tryRegister(String phase) {
        try {
            Object services = Bukkit.getServicesManager();
            Object economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration = null;
            for (java.lang.reflect.Method m : services.getClass().getMethods()) {
                if (!m.getName().equals("getRegistration") || m.getParameterCount() != 1) continue;
                try {
                    registration = m.invoke(services, economyClass);
                    if (registration != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (registration == null) {
                if (phase.equals("启动时")) {
                    plugin.getLogger().info("Vault 已装但经济服务尚未注册，稍后自动重试…");
                }
                return;
            }
            economy = call(registration, "getProvider", new Class<?>[]{});
            if (economy != null) {
                available = true;
                Object name = call(economy, "getName", new Class<?>[]{});
                plugin.getLogger().info("已挂钩经济服务：" + name + "（" + phase + "）");
            }
        } catch (Throwable t) {
            if (phase.equals("启动时")) {
                plugin.getLogger().warning("挂钩 Vault 失败：" + t.getMessage());
            }
        }
    }

    /** 给玩家加钱。返回是否成功。 */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!available || player == null || amount <= 0) return false;
        try {
            Object resp = call(economy, "depositPlayer",
                    new Class<?>[]{OfflinePlayer.class, double.class}, player, amount);
            if (resp == null) return false;
            Object ok = call(resp, "transactionSuccess", new Class<?>[]{});
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            plugin.getLogger().warning("发放金钱失败：" + t.getMessage());
            return false;
        }
    }

    /** 查询余额；不可用时返回 0。 */
    public double getBalance(OfflinePlayer player) {
        if (!available || player == null) return 0;
        try {
            Object r = call(economy, "getBalance",
                    new Class<?>[]{OfflinePlayer.class}, player);
            return r instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 扣钱。 */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!available || player == null || amount <= 0) return false;
        try {
            Object resp = call(economy, "withdrawPlayer",
                    new Class<?>[]{OfflinePlayer.class, double.class}, player, amount);
            if (resp == null) return false;
            Object ok = call(resp, "transactionSuccess", new Class<?>[]{});
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 玩家是否是 Player 实例（便捷判断）。 */
    public boolean isOnline(Object p) {
        return p instanceof Player;
    }
}
