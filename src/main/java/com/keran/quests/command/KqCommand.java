package com.keran.quests.command;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestTree;
import com.keran.quests.config.model.enums.QuestType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /kq —— 玩家菜单 + 管理命令。
 */
public class KqCommand implements CommandExecutor, TabCompleter {

    private final KeranQuests plugin;

    public KqCommand(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // 权限兜底：plugin.yml 已声明 permission: kq.use，
        // 但若是通过其他插件 dispatchCommand 绕进来，这里再拦一道。
        if (!sender.hasPermission("kq.use") && !sender.hasPermission("kq.admin")) {
            Text.send(sender, "&c你没有权限。");
            return true;
        }

        if (args.length == 0) {
            // 默认：打开 GUI
            if (!(sender instanceof Player p)) {
                sendHelp(sender, label);
                return true;
            }
            plugin.getGuiManager().openTreeList(p, 0);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list", "gui", "menu" -> {
                if (sub.equals("gui") && args.length >= 2) {
                    // /kq gui <player>
                    if (!checkAdmin(sender)) return true;
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        Text.send(sender, "&c玩家不在线：" + args[1]);
                        return true;
                    }
                    plugin.getGuiManager().openTreeList(target, 0);
                    Text.send(sender, "&a已为 " + target.getName() + " 打开任务菜单。");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Text.send(sender, "&c该命令只能由玩家执行。");
                    return true;
                }
                plugin.getGuiManager().openTreeList(p, 0);
            }
            case "track" -> doTrack(sender, args);
            case "abandon" -> doAbandon(sender, args);
            case "reload" -> doReload(sender);
            case "selftest" -> doSelftest(sender);
            case "debug" -> doDebug(sender, args);
            case "help" -> sendHelp(sender, label);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------

    private void doTrack(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Text.send(sender, "&c该命令只能由玩家执行。");
            return;
        }
        PlayerData data = plugin.getPlayerData(p);

        if (args.length < 2) {
            // 列出进行中任务供选择
            List<Quest> actives = plugin.getQuestManager().getActiveQuests(p);
            if (actives.isEmpty()) {
                Text.send(p, "&7当前没有进行中的任务。");
                return;
            }
            Text.sendRaw(p, "&8&m--------&r &e追踪任务 &8&m--------");
            for (Quest q : actives) {
                Text.sendRaw(p, " &7- &f" + q.getFullId() + " &7| &f"
                        + Text.strip(q.getName()) + " &7(权重 " + q.getWeight() + ")");
            }
            Text.sendRaw(p, "&7使用 &f/kq track <任务ID> &7追踪");
            return;
        }

        Quest q = plugin.getTreeLoader().resolveQuest(args[1]);
        if (q == null) {
            Text.send(p, "&c任务不存在：" + args[1]);
            return;
        }
        QuestProgress prog = data.getProgress(q.getFullId());
        if (prog == null || prog.getState() != com.keran.quests.config.model.enums.QuestState.ACTIVE) {
            Text.send(p, "&c该任务不在进行中，无法追踪。");
            return;
        }
        data.setTrackedQuest(q.getFullId());
        plugin.getPlayerDataStore().save(data);
        Text.send(p, "&a已追踪：&f" + Text.strip(q.getName()));
    }

    private void doAbandon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Text.send(sender, "&c该命令只能由玩家执行。");
            return;
        }
        Quest q;
        if (args.length >= 2) {
            q = plugin.getTreeLoader().resolveQuest(args[1]);
        } else {
            q = plugin.getQuestManager().getCurrentQuest(p);
        }
        if (q == null) {
            Text.send(p, "&c没有可放弃的任务。");
            return;
        }
        String err = plugin.getQuestManager().abandon(p, q);
        if (err != null) Text.send(p, err);
    }

    private void doReload(CommandSender sender) {
        if (!checkAdmin(sender)) return;
        plugin.reloadAll();
        Text.send(sender, "&a配置已重载。");
    }

    // ------------------------------------------------------------------
    //  selftest
    // ------------------------------------------------------------------

    private void doSelftest(CommandSender sender) {
        if (!checkAdmin(sender)) return;

        Text.sendRaw(sender, "&8&m========&r &eKeranQuests 自检 &8&m========");

        // 1. 配置
        int trees = plugin.getTreeLoader().getTreeCount();
        int quests = plugin.getTreeLoader().getQuestCount();
        int stages = plugin.getTreeLoader().getStageCount();
        line(sender, trees > 0, "配置：&f" + trees + " &7棵任务树，&f" + quests + " &7个任务，&f"
                + stages + " &7个阶段");

        // 2. Vault
        line(sender, plugin.getVaultHook().isAvailable(), "Vault 经济：&f"
                + (plugin.getVaultHook().isAvailable() ? "已挂钩" : "&c不可用（奖励将跳过）"));

        // 3. PAPI
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        line(sender, papi, "PlaceholderAPI：&f" + (papi ? "已注册 kq 扩展" : "&c未安装"));

        // 4. Oraxen
        line(sender, plugin.getOraxenHook().isAvailable(), "Oraxen：&f"
                + (plugin.getOraxenHook().isAvailable() ? "已挂钩（物品识别可用）" : "&c不可用（UI 将用原版材质）"));

        // 5. WorldGuard
        line(sender, plugin.getWorldGuardHook().isAvailable(), "WorldGuard：&f"
                + (plugin.getWorldGuardHook().isAvailable() ? "已挂钩（区域判定可用）" : "&c不可用（区域要求失效）"));

        // 6. MythicMobs
        boolean mm = Bukkit.getPluginManager().getPlugin("MythicMobs") != null;
        line(sender, mm, "MythicMobs：&f" + (mm ? "已安装（击杀判定可用）" : "&c未安装"));

        // 7. Citizens
        boolean cit = Bukkit.getPluginManager().getPlugin("Citizens") != null;
        String diaInfo = cit ? "&f已安装，" + plugin.getDialogueRunner().getDialogueCount() + " 条对话"
                : "&c未安装";
        line(sender, cit, "Citizens：&f" + diaInfo);

        // 8. 世界
        List<String> worlds = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) worlds.add(w.getName());
        line(sender, true, "世界（&f" + worlds.size() + "&7个）：&f" + String.join(", ", worlds));

        // 9. 存储
        int cached = plugin.getPlayerDataStore().getCachedCount();
        line(sender, true, "存储：&f" + plugin.getPlayerDataStore().getDataDir().getName()
                + " &7（已缓存 &f" + cached + " &7份档案）");

        // 10. 前置环依赖检查
        List<String> cycles = checkCycles();
        line(sender, cycles.isEmpty(), "前置图：&f" + (cycles.isEmpty() ? "无循环依赖" : "&c发现循环："
                + String.join(", ", cycles)));

        // 11. UI 材质
        if (plugin.getOraxenHook().isAvailable()) {
            String[] icons = {"quest_ui_done", "quest_ui_locked", "quest_ui_failed",
                    "quest_ui_cooldown", "quest_ui_active", "quest_ui_available",
                    "quest_ui_main", "quest_ui_side", "quest_ui_unknown", "quest_ui_choice",
                    "quest_ui_stage_done", "quest_ui_stage_active", "quest_ui_bar_full",
                    "quest_ui_bar_empty", "quest_ui_bg", "quest_ui_border", "quest_ui_divider",
                    "quest_ui_back", "quest_ui_close", "quest_ui_prev", "quest_ui_next",
                    "quest_ui_lock_overlay"};
            int ok = 0;
            List<String> missing = new ArrayList<>();
            for (String id : icons) {
                if (plugin.getOraxenHook().exists(id)) ok++;
                else missing.add(id);
            }
            boolean allOk = missing.isEmpty();
            line(sender, allOk, "UI 材质：&f" + ok + "/" + icons.length + " 存在"
                    + (allOk ? "" : " &7（缺：" + String.join(", ", missing) + "）"));
        }

        // 12. 在线玩家
        int online = Bukkit.getOnlinePlayers().size();
        Text.sendRaw(sender, " &e⚠ &7在线玩家：&f" + online
                + (online == 0 ? " &7—— 涉及玩家的功能未实测" : ""));

        Text.sendRaw(sender, "&8&m========================================");
    }

    private void line(CommandSender sender, boolean ok, String msg) {
        Text.sendRaw(sender, (ok ? " &a[✔] " : " &c[✘] ") + msg);
    }

    /**
     * 检查前置环依赖。
     *
     * <p>用 DFS 三色标记找出**任意长度**的环（原实现只能发现 A→B→A 这种两步环，
     * 三节点以上的 A→B→C→A 会被漏掉）。每个环只报告一次，避免重复刷屏。
     */
    private List<String> checkCycles() {
        List<String> out = new ArrayList<>();
        // 0 = 未访问，1 = 访问中（在当前 DFS 栈上），2 = 已完成
        Map<String, Integer> state = new HashMap<>();
        Set<String> reported = new HashSet<>();

        for (Quest q : plugin.getTreeLoader().getAllQuests()) {
            if (state.getOrDefault(q.getFullId(), 0) == 0) {
                dfsCycle(q, new ArrayList<>(), state, reported, out);
            }
        }
        return out;
    }

    /** DFS 前置链，遇到"访问中"的节点即发现环。 */
    private void dfsCycle(Quest current, List<String> path, Map<String, Integer> state,
                          Set<String> reported, List<String> out) {
        String id = current.getFullId();
        int st = state.getOrDefault(id, 0);
        if (st == 1) {
            // 找环：截取 path 中从该节点开始的部分
            int at = path.indexOf(id);
            if (at < 0) at = 0;
            List<String> cycle = new ArrayList<>(path.subList(at, path.size()));
            cycle.add(id);
            String key = canonicalCycleKey(cycle);
            if (reported.add(key)) {
                out.add(String.join(" → ", cycle));
            }
            return;
        }
        if (st == 2) return;   // 已完成，无需重复遍历

        state.put(id, 1);
        path.add(id);
        for (String ref : current.getPrerequisites().allReferencedQuests()) {
            Quest parent = plugin.getTreeLoader().resolveQuest(ref);
            if (parent == null) continue;
            dfsCycle(parent, path, state, reported, out);
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
    }

    /** 把环旋转到最小元素开头，使同一环的不同起点得到相同 key（只报告一次）。 */
    private String canonicalCycleKey(List<String> cycle) {
        if (cycle.isEmpty()) return "";
        List<String> body = new ArrayList<>(cycle.subList(0, cycle.size() - 1));
        int minIdx = 0;
        for (int i = 1; i < body.size(); i++) {
            if (body.get(i).compareTo(body.get(minIdx)) < 0) minIdx = i;
        }
        List<String> rotated = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            rotated.add(body.get((minIdx + i) % body.size()));
        }
        return String.join(">", rotated);
    }

    // ------------------------------------------------------------------

    private void doDebug(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) return;
        if (args.length < 2) {
            Text.send(sender, "&c用法：/kq debug <玩家> [任务ID]");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || (target.getName() == null)) {
            Text.send(sender, "&c找不到玩家：" + args[1]);
            return;
        }
        PlayerData data = plugin.getPlayerDataStore().get(target.getUniqueId());
        if (data == null) {
            Text.send(sender, "&c该玩家档案未载入（需在线）。");
            return;
        }
        Text.sendRaw(sender, "&8&m----&r &e档案：" + target.getName() + " &8&m----");
        Text.sendRaw(sender, " &7追踪：&f" + data.getTrackedQuest());
        Text.sendRaw(sender, " &7已载入进度：&f" + data.getAllProgress().size() + " 条");
        for (var e : data.getAllProgress().entrySet()) {
            QuestProgress p = e.getValue();
            Text.sendRaw(sender, "  &7- &f" + e.getKey() + " &8| &f" + p.getState()
                    + " &8| 阶段 &f" + p.getStageIndex());
        }
        if (!data.getAllTreeState().isEmpty()) {
            Text.sendRaw(sender, " &7树状态：");
            for (var e : data.getAllTreeState().entrySet()) {
                Text.sendRaw(sender, "  &7- &f" + e.getKey() + " = " + e.getValue());
            }
        }
        if (!data.getAllCooldowns().isEmpty()) {
            Text.sendRaw(sender, " &7冷却：");
            for (var e : data.getAllCooldowns().entrySet()) {
                long left = e.getValue() - System.currentTimeMillis();
                Text.sendRaw(sender, "  &7- &f" + e.getKey() + " &7剩余 &f"
                        + com.keran.quests.util.TimeUtil.format((int) Math.max(0, left / 1000)));
            }
        }
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("kq.admin") || sender.isOp()
                || !(sender instanceof Player)) {
            return true;
        }
        Text.send(sender, "&c你没有权限执行该命令。");
        return false;
    }

    private void sendHelp(CommandSender sender, String label) {
        Text.sendRaw(sender, "&8&m--------&r &eKeranQuests &8&m--------");
        Text.sendRaw(sender, " &f/" + label + " &7- 打开任务菜单");
        Text.sendRaw(sender, " &f/" + label + " list &7- 同上（显式）");
        Text.sendRaw(sender, " &f/" + label + " track [任务ID] &7- 追踪任务（影响 %kq_current%）");
        Text.sendRaw(sender, " &f/" + label + " abandon [任务ID] &7- 放弃任务");
        Text.sendRaw(sender, " &f/" + label + " reload &7- 重载配置");
        Text.sendRaw(sender, " &f/" + label + " selftest &7- 环境自检");
        Text.sendRaw(sender, " &f/" + label + " debug <玩家> &7- 查看档案");
        Text.sendRaw(sender, " &f/ka &7- 快速查看当前任务");
    }

    // ------------------------------------------------------------------

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("list", "track", "abandon", "reload", "selftest", "debug", "help"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("track") || sub.equals("abandon")) {
                if (sender instanceof Player p) {
                    for (Quest q : plugin.getQuestManager().getActiveQuests(p)) {
                        out.add(q.getFullId());
                    }
                }
                for (QuestTree t : plugin.getTreeLoader().getTrees()) {
                    for (Quest q : t.getQuests()) out.add(q.getFullId());
                }
            } else if (sub.equals("debug") || sub.equals("gui")) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
        }
        String last = args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.toLowerCase().startsWith(last))
                .sorted().distinct().collect(Collectors.toList());
    }
}
