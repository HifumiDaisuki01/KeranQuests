package com.keran.quests;

import com.keran.quests.command.KaCommand;
import com.keran.quests.command.KqCommand;
import com.keran.quests.command.KquestsCommand;
import com.keran.quests.config.QuestTreeLoader;
import com.keran.quests.gui.GuiManager;
import com.keran.quests.hook.OraxenHook;
import com.keran.quests.hook.VaultHook;
import com.keran.quests.hook.WorldGuardHook;
import com.keran.quests.listener.ItemCollectListener;
import com.keran.quests.listener.MythicKillListener;
import com.keran.quests.listener.NpcClickListener;
import com.keran.quests.listener.PlayerKillListener;
import com.keran.quests.listener.PlayerLifecycleListener;
import com.keran.quests.placeholder.KqExpansion;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.PlayerDataStore;
import com.keran.quests.runtime.ChoiceManager;
import com.keran.quests.runtime.DialogueRunner;
import com.keran.quests.runtime.FreezeManager;
import com.keran.quests.runtime.QuestManager;
import com.keran.quests.runtime.RegionRunner;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * KeranQuests —— 任务系统主类。
 *
 * <p>负责装配全部子系统与生命周期管理。
 */
public class KeranQuests extends JavaPlugin {

    private QuestTreeLoader treeLoader;
    private PlayerDataStore playerDataStore;
    private QuestManager questManager;
    private ChoiceManager choiceManager;
    private RegionRunner regionRunner;
    private DialogueRunner dialogueRunner;
    private FreezeManager freezeManager;
    private GuiManager guiManager;

    private OraxenHook oraxenHook;
    private WorldGuardHook worldGuardHook;
    private VaultHook vaultHook;

    private KqExpansion expansion;
    private NamespacedKey actionKey;
    private BukkitTask flushTask;

    @Override
    public void onEnable() {
        // 1. 配置
        saveDefaultConfig();
        this.actionKey = new NamespacedKey(this, "gui_action");

        // 2. 挂钩第三方
        oraxenHook = new OraxenHook(this);
        worldGuardHook = new WorldGuardHook(this);
        vaultHook = new VaultHook(this);
        oraxenHook.hook();
        worldGuardHook.hook();
        vaultHook.hook();

        // 3. 配置加载
        treeLoader = new QuestTreeLoader(this);
        int trees = treeLoader.load();
        getLogger().info("已加载任务树 " + trees + " 棵，任务 "
                + treeLoader.getQuestCount() + " 个，阶段 " + treeLoader.getStageCount() + " 个。");

        // 4. 玩家档案
        playerDataStore = new PlayerDataStore(this);

        // 5. 运行时
        freezeManager = new FreezeManager(this);
        questManager = new QuestManager(this);
        choiceManager = new ChoiceManager(this);
        guiManager = new GuiManager(this);
        dialogueRunner = new DialogueRunner(this);
        dialogueRunner.load();
        regionRunner = new RegionRunner(this);
        regionRunner.start();

        // 6. 事件
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);
        getServer().getPluginManager().registerEvents(new NpcClickListener(this), this);
        // Oraxen 物品收集（拾取 / 从容器取出）
        getServer().getPluginManager().registerEvents(new ItemCollectListener(this), this);
        // 玩家击杀（PvP）
        getServer().getPluginManager().registerEvents(new PlayerKillListener(this), this);
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
            getServer().getPluginManager().registerEvents(new MythicKillListener(this), this);
            getLogger().info("已注册 MythicMobs 击杀监听。");
        }

        // 7. 命令
        registerCommands();

        // 8. PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            expansion = new KqExpansion(this);
            if (expansion.register()) {
                getLogger().info("已注册 PlaceholderAPI 扩展：kq");
            } else {
                getLogger().warning("PlaceholderAPI 扩展注册失败。");
            }
        }

        // 9. 定时批量保存
        int interval = getConfig().getInt("storage.flush_interval", 60) * 20;
        flushTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            playerDataStore.flushAll();
        }, interval, interval);

        // 10. 为已在线玩家载入档案（/reload 场景）
        for (Player p : Bukkit.getOnlinePlayers()) {
            playerDataStore.load(p.getUniqueId(), p.getName());
        }

        getLogger().info("KeranQuests 已启用。");
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (regionRunner != null) regionRunner.stop();
        // 取消未播完的对话任务并解冻玩家，避免残留任务在卸载后刷屏
        if (dialogueRunner != null) dialogueRunner.shutdown();
        if (playerDataStore != null) playerDataStore.flushAll();
        if (expansion != null && expansion.isRegistered()) {
            expansion.unregister();
        }
        if (freezeManager != null) freezeManager.clear();
        getLogger().info("KeranQuests 已卸载。");
    }

    private void registerCommands() {
        PluginCommand kq = getCommand("kq");
        if (kq != null) {
            KqCommand exec = new KqCommand(this);
            kq.setExecutor(exec);
            kq.setTabCompleter(exec);
        }
        PluginCommand ka = getCommand("ka");
        if (ka != null) {
            KaCommand exec = new KaCommand(this);
            ka.setExecutor(exec);
        }
        PluginCommand kquests = getCommand("kquests");
        if (kquests != null) {
            KquestsCommand exec = new KquestsCommand(this);
            kquests.setExecutor(exec);
            kquests.setTabCompleter(exec);
        }
    }

    /** 重载全部配置与任务树。 */
    public void reloadAll() {
        reloadConfig();
        int trees = treeLoader.load();
        dialogueRunner.load();
        getLogger().info("配置已重载：任务树 " + trees + " 棵。");
    }

    // ------------------------------------------------------------------
    //  便捷访问
    // ------------------------------------------------------------------

    /** 取玩家档案（不存在时自动载入）。 */
    public PlayerData getPlayerData(Player player) {
        PlayerData d = playerDataStore.get(player.getUniqueId());
        if (d == null) {
            d = playerDataStore.load(player.getUniqueId(), player.getName());
        }
        return d;
    }

    /** 取带前缀的消息模板并返回值（已做颜色转换）。 */
    public String prefixed(String key, Object... pairs) {
        return prefixed(key, null, pairs);
    }

    /**
     * 取带前缀的消息模板并返回值（已做颜色转换）。
     *
     * @param fallback 配置里没有该 key 时使用的兜底文案（可为 null）。
     *                 用途：服务器上的 config.yml 是首次安装时释放的旧副本，
     *                 插件升级后新增的消息键不会自动补进去；没有兜底就会出现
     *                 "玩家收到一条空消息、控制台日志空白"的问题。
     */
    public String prefixed(String key, String fallback, Object... pairs) {
        String raw = getConfig().getString("messages." + key, null);
        if (raw == null || raw.isBlank()) {
            raw = fallback == null ? "" : fallback;
        }
        return com.keran.quests.util.Text.color(com.keran.quests.util.Text.replace(raw, pairs));
    }

    // ------------------------------------------------------------------
    //  getters
    // ------------------------------------------------------------------

    public QuestTreeLoader getTreeLoader() {
        return treeLoader;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }

    public QuestManager getQuestManager() {
        return questManager;
    }

    public ChoiceManager getChoiceManager() {
        return choiceManager;
    }

    public RegionRunner getRegionRunner() {
        return regionRunner;
    }

    public DialogueRunner getDialogueRunner() {
        return dialogueRunner;
    }

    public FreezeManager getFreezeManager() {
        return freezeManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public OraxenHook getOraxenHook() {
        return oraxenHook;
    }

    public WorldGuardHook getWorldGuardHook() {
        return worldGuardHook;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public NamespacedKey getActionKey() {
        return actionKey;
    }
}
