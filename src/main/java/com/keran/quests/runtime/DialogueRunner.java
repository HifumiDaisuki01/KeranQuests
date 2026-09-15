package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.scheduler.BukkitTask;

/**
 * 对话执行器 —— 处理 Citizens NPC 的"机械式逐条延时播报"。
 *
 * <p>对话配置位于 {@code plugins/KeranQuests/npc/dialogue.yml}：
 * <pre>
 * dialogues:
 *   zhulong_liaison_intro:
 *     npc: '深空联合联络员'
 *     lines:
 *       - delay: 0
 *         text: '&7[深空联合联络员] &f你终于来了。'
 *       - delay: 40
 *         text: '&7[深空联合联络员] &f烛龙站的情况比报告里糟糕。'
 *     freeze_player: true
 *     after:
 *       - 'tellraw {player} {"text":"请打开任务菜单","color":"yellow"}'
 * </pre>
 */
public class DialogueRunner {

    private final KeranQuests plugin;
    /** NPC 名（小写） -> 对话定义 */
    private final Map<String, Dialogue> byNpc = new LinkedHashMap<>();
    /** 正在对话中的玩家 */
    private final Set<UUID> talking = new HashSet<>();
    /**
     * 正在对话中的玩家 -> 本次对话已排期/已执行的任务。
     *
     * <p>保存引用有两个作用：
     * <ol>
     *   <li>插件卸载 / reload 时能把未执行完的对话任务全部取消，避免重复刷屏；</li>
     *   <li>配合 {@link #talking} 做幂等保护，防止 finishing 被重复执行。</li>
     * </ol>
     */
    private final Map<UUID, List<BukkitTask>> runningTasks = new HashMap<>();

    public DialogueRunner(KeranQuests plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  加载
    // ------------------------------------------------------------------

    public void load() {
        byNpc.clear();
        File dir = new File(plugin.getDataFolder(), "npc");
        if (!dir.exists()) {
            dir.mkdirs();
            plugin.saveResource("npc/dialogue.yml", false);
        }
        File f = new File(dir, "dialogue.yml");
        if (!f.exists()) return;

        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection root = yml.getConfigurationSection("dialogues");
            if (root == null) return;
            for (String key : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;
                Dialogue d = Dialogue.fromConfig(key, sec);
                if (d != null) {
                    byNpc.put(d.npc.toLowerCase(), d);
                }
            }
            plugin.getLogger().info("已加载 " + byNpc.size() + " 条 NPC 对话。");
        } catch (Throwable t) {
            plugin.getLogger().warning("加载对话配置失败：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  执行
    // ------------------------------------------------------------------

    /** 玩家右键 NPC 时调用。 */
    public boolean startDialogue(Player player, String npcName) {
        if (npcName == null) return false;
        Dialogue d = byNpc.get(npcName.toLowerCase());
        if (d == null) return false;

        UUID uid = player.getUniqueId();
        if (talking.contains(uid)) return true;   // 已在对话中
        talking.add(uid);

        if (d.freeze) {
            plugin.getFreezeManager().freeze(uid, estimateDuration(d));
        }

        // 每次开始对话前，清掉这个玩家上一次残留的任务引用
        cancelTasks(uid);
        List<BukkitTask> tasks = new ArrayList<>();
        runningTasks.put(uid, tasks);

        for (Line line : d.lines) {
            final String msg = Text.replace(line.text, "player", player.getName());
            if (line.delay > 0) {
                BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) player.sendMessage(Text.color(msg));
                }, line.delay);
                tasks.add(t);
            } else {
                player.sendMessage(Text.color(msg));
            }
        }

        // 结尾命令
        int lastDelay = estimateDuration(d);
        BukkitTask end = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            finishing(player, d);
        }, Math.max(1, lastDelay));
        tasks.add(end);

        return true;
    }

    /**
     * 对话收尾：解冻 + 跑 after 命令 + 触发 NPC_TALK 要求。
     *
     * <p>幂等：只有当该玩家确实处于「对话中」状态时才执行，且执行时立刻移出
     * talking 集合，因此定时任务被重复触发（或 reload 后重入）也不会二次解冻、
     * 二次执行 after 命令、二次推进 NPC_TALK 进度。
     */
    private void finishing(Player player, Dialogue d) {
        UUID uid = player.getUniqueId();
        if (!talking.remove(uid)) {
            return;   // 已被其他路径收尾过，直接忽略
        }
        cancelTasks(uid);
        plugin.getFreezeManager().unfreeze(uid);
        if (d.after.isEmpty()) return;
        for (String raw : d.after) {
            String cmd = Text.replace(raw, "player", player.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("对话后命令执行失败：" + cmd);
            }
        }
        // 触发 NPC_TALK 要求
        plugin.getQuestManager().fireNpcTalk(player, d.npc);
    }

    /** 取消并清理某玩家当前挂起的对话任务。 */
    private void cancelTasks(UUID uid) {
        List<BukkitTask> tasks = runningTasks.remove(uid);
        if (tasks == null) return;
        for (BukkitTask t : tasks) {
            try {
                if (t != null && !t.isCancelled()) t.cancel();
            } catch (Throwable ignored) {
                // 任务可能已执行完毕，忽略
            }
        }
    }

    /** 插件卸载 / 重载时调用：清理全部对话状态与挂起任务。 */
    public void shutdown() {
        for (UUID uid : new ArrayList<>(runningTasks.keySet())) {
            cancelTasks(uid);
        }
        runningTasks.clear();
        for (UUID uid : new ArrayList<>(talking)) {
            plugin.getFreezeManager().unfreeze(uid);
        }
        talking.clear();
    }

    private int estimateDuration(Dialogue d) {
        int max = 0;
        for (Line l : d.lines) {
            if (l.delay > max) max = l.delay;
        }
        return max + 20;   // 最后一条之后留 1 秒
    }

    /** 玩家是否正在对话。 */
    public boolean isTalking(UUID uuid) {
        return talking.contains(uuid);
    }

    /** 某 NPC 是否配置了对话。 */
    public boolean hasDialogue(String npcName) {
        return npcName != null && byNpc.containsKey(npcName.toLowerCase());
    }

    public int getDialogueCount() {
        return byNpc.size();
    }

    /** 全部已配置对话的原始 NPC 名（保留大小写，供 Tab 补全）。 */
    public List<String> getNpcNames() {
        List<String> out = new ArrayList<>();
        for (Dialogue d : byNpc.values()) out.add(d.npc);
        return out;
    }

    // ------------------------------------------------------------------
    //  内部模型
    // ------------------------------------------------------------------

    private static class Dialogue {
        String id;
        String npc;
        List<Line> lines = new ArrayList<>();
        List<String> after = new ArrayList<>();
        boolean freeze = true;

        static Dialogue fromConfig(String id, ConfigurationSection sec) {
            String npc = sec.getString("npc");
            if (npc == null || npc.isBlank()) return null;
            Dialogue d = new Dialogue();
            d.id = id;
            d.npc = npc;
            d.freeze = sec.getBoolean("freeze_player", true);
            d.after = new ArrayList<>(sec.getStringList("after"));

            List<?> rawLines = sec.getList("lines");
            if (rawLines != null) {
                for (Object o : rawLines) {
                    ConfigurationSection ls = toSection(o);
                    if (ls == null) continue;
                    Line l = new Line();
                    l.delay = ls.getInt("delay", 0);
                    l.text = ls.getString("text", "");
                    if (!l.text.isBlank()) d.lines.add(l);
                }
            }
            return d;
        }

        private static ConfigurationSection toSection(Object o) {
            if (o instanceof ConfigurationSection cs) return cs;
            if (o instanceof Map<?, ?> map) {
                org.bukkit.configuration.MemoryConfiguration mem =
                        new org.bukkit.configuration.MemoryConfiguration();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    mem.set(String.valueOf(e.getKey()), e.getValue());
                }
                return mem;
            }
            if (o instanceof String s) {
                org.bukkit.configuration.MemoryConfiguration mem =
                        new org.bukkit.configuration.MemoryConfiguration();
                mem.set("text", s);
                mem.set("delay", 0);
                return mem;
            }
            return null;
        }
    }

    private static class Line {
        int delay;
        String text;
    }
}
