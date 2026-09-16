package com.keran.quests.gui;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestStage;
import com.keran.quests.config.model.QuestTree;
import com.keran.quests.config.model.Requirement;
import com.keran.quests.config.model.enums.QuestState;
import com.keran.quests.config.model.enums.QuestType;
import com.keran.quests.player.PlayerData;
import com.keran.quests.player.QuestProgress;
import com.keran.quests.runtime.QuestManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUI 主控 —— 三个界面 + 抉择界面。
 *
 * <h3>界面层级</h3>
 * <pre>
 *   任务树列表  →  任务树详情  →  任务详情  →  抉择
 * </pre>
 */
public class GuiManager implements Listener {

    private final KeranQuests plugin;

    /** 玩家当前打开的界面类型 */
    private final Map<UUID, View> currentView = new HashMap<>();
    /** 玩家在每个界面的翻页状态 */
    private final Map<UUID, Integer> pageState = new HashMap<>();
    /**
     * 界面跳转时被抑制的关闭事件登记：玩家 UUID -> 登记时间(毫秒)。
     *
     * <p>openInventory 切换界面会附带一次旧界面的 InventoryCloseEvent，
     * 若让 onClose 正常处理会把刚写入的 currentView 擦掉，
     * 导致新界面点击失灵。这里登记一次「应忽略的关闭」。
     */
    private final Map<UUID, Long> suppressClose = new HashMap<>();

    /**
     * 抉择界面的「待确认选择」登记：玩家 UUID -> 第一次点击的选项。
     *
     * <p>抉择采用**两级确认**防误触：第一下点击只是选中（界面 lore 变成"再次点击以确认"），
     * 需要在 {@link #CHOICE_CONFIRM_WINDOW_MS} 内再点同一选项才真正生效。
     *
     * <p>为什么不用 Shift：手机端（基岩版 / 触屏启动器）根本没有 Shift 键，
     * "按住 Shift 点击"这个前置条件在手机上永远无法满足，会把抉择彻底锁死。
     */
    private final Map<UUID, PendingChoice> pendingChoice = new HashMap<>();

    /** 两级确认的有效窗口（毫秒）。可由 config 的 {@code gui.choice_confirm_ms} 覆盖。 */
    private long choiceConfirmWindowMs() {
        return Math.max(500L, plugin.getConfig().getLong("gui.choice_confirm_ms", 3000L));
    }

    /** 一次"待确认"的抉择记录。 */
    private static final class PendingChoice {
        final String questId;
        final String stageId;
        final int index;
        final long at;
        /** 判定超时用的窗口，创建时从配置固化下来。 */
        final long windowMs;

        PendingChoice(String questId, String stageId, int index, long windowMs) {
            this.questId = questId;
            this.stageId = stageId;
            this.index = index;
            this.at = System.currentTimeMillis();
            this.windowMs = windowMs;
        }

        boolean expired() {
            return System.currentTimeMillis() - at > windowMs;
        }

        /** 是否为同一次待确认（同一个任务 + 同一个阶段 + 同一个选项）。 */
        boolean matches(Quest quest, QuestStage stage, int idx) {
            return !expired()
                    && quest.getFullId().equals(questId)
                    && stage.getId().equals(stageId)
                    && index == idx;
        }
    }

    public GuiManager(KeranQuests plugin) {
        this.plugin = plugin;
    }

    /** 视图类型。 */
    public enum View {
        TREE_LIST,      // 任务树列表
        QUEST_LIST,     // 某树的任务列表
        QUEST_DETAIL,   // 单任务详情
        CHOICE          // 抉择
    }

    // ==================================================================
    //  ① 任务树列表
    // ==================================================================

    public void openTreeList(Player player, int page) {
        List<QuestTree> trees = new ArrayList<>(plugin.getTreeLoader().getTrees());
        trees.removeIf(t -> !t.isEnabled());

        int size = 54;
        String title = plugin.getConfig().getString("gui.title_tree_list", "任务总览");
        Inventory inv = Bukkit.createInventory(null, size, com.keran.quests.util.Text.color(title));

        // 背景
        fillBackground(inv);

        int perPage = 21;
        int totalPages = Math.max(1, (int) Math.ceil(trees.size() / (double) perPage));
        int p = Math.max(0, Math.min(page, totalPages - 1));
        int start = p * perPage;

        // 按钮位置：3 行 x 7 列（跳过边框）
        int[] slots = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34
        };

        PlayerData data = plugin.getPlayerData(player);
        for (int i = 0; i < perPage && start + i < trees.size(); i++) {
            QuestTree tree = trees.get(start + i);
            inv.setItem(slots[i], buildTreeNode(player, data, tree));
        }

        // 翻页
        if (totalPages > 1) {
            if (p > 0) {
                inv.setItem(45, new GuiItem(plugin, cfg("gui.icons.prev", "quest_ui_prev"), Material.ARROW)
                        .name("&e← 上一页")
                        .action("page", String.valueOf(p - 1))
                        .build());
            }
            inv.setItem(49, new GuiItem(plugin, cfg("gui.icons.bg", "quest_ui_bg"), Material.GRAY_STAINED_GLASS_PANE)
                    .name("&7第 &f" + (p + 1) + "&7/&f" + totalPages + " &7页")
                    .build());
            if (p < totalPages - 1) {
                inv.setItem(53, new GuiItem(plugin, cfg("gui.icons.next", "quest_ui_next"), Material.ARROW)
                        .name("&e下一页 →")
                        .action("page", String.valueOf(p + 1))
                        .build());
            }
        }

        currentView.put(player.getUniqueId(), View.TREE_LIST);
        pageState.put(player.getUniqueId(), p);
        openGui(player, inv);
    }

    /** 构建任务树图标。 */
    private ItemStack buildTreeNode(Player player, PlayerData data, QuestTree tree) {
        int mainDone = plugin.getQuestManager().countCompleted(data, tree, QuestType.MAIN);
        int mainTotal = tree.countMainTotal();
        int sideDone = plugin.getQuestManager().countCompleted(data, tree, QuestType.SIDE);
        int sideTotal = tree.countSideTotal();

        QuestManager.UnlockStatus treeStatus = getTreeUnlockStatus(player, tree);
        boolean hasAvailable = hasAvailableQuest(player, tree);
        String stateText = plugin.getQuestManager().getTreeStateText(data, tree);

        // 图标：根据状态选择
        String iconId = tree.getIcon();
        Material fallback = Material.BOOK;
        if (treeStatus == QuestManager.UnlockStatus.LOCKED_HIDDEN) {
            iconId = cfg("gui.icons.unknown", "quest_ui_unknown");
            fallback = Material.GRAY_DYE;
        } else if (treeStatus == QuestManager.UnlockStatus.LOCKED_KNOWN) {
            iconId = cfg("gui.icons.locked", "quest_ui_locked");
            fallback = Material.GRAY_DYE;
        } else if ("已完成".equals(stateText)) {
            iconId = cfg("gui.icons.done", "quest_ui_done");
            fallback = Material.LIME_DYE;
        } else if (hasAvailable) {
            iconId = cfg("gui.icons.available", "quest_ui_available");
            fallback = Material.YELLOW_DYE;
        }

        List<String> lore = new ArrayList<>();
        lore.add("&8──────────────");

        // 未解锁隐藏：不显示描述
        if (treeStatus == QuestManager.UnlockStatus.LOCKED_HIDDEN) {
            lore.add("&8未知的任务线");
            lore.add("");
            lore.add("&7完成更多任务以解锁…");
        } else {
            for (String line : tree.getDescription().split("\n")) {
                lore.add("&7" + line);
            }
            lore.add("");
            lore.add("&7主线进度：&a" + mainDone + "&7/&f" + mainTotal);
            lore.add("&7支线进度：&a" + sideDone + "&7/&f" + sideTotal);
            lore.add("");

            if (treeStatus == QuestManager.UnlockStatus.LOCKED_KNOWN) {
                lore.add("&c[✖] 未解锁");
                lore.addAll(describeMissingPrerequisites(player, tree));
            } else {
                if (hasAvailable) {
                    lore.add("&e[▶] 有任务可以接取");
                }
                if (!"已完成".equals(stateText) && !"未开始".equals(stateText)) {
                    lore.add("&7状态：&f" + stateText);
                }
                lore.add("");
                lore.add("&7点击查看详情");
            }
        }

        String name = treeStatus == QuestManager.UnlockStatus.LOCKED_HIDDEN
                ? "&8？？？"
                : tree.getName();

        return new GuiItem(plugin, iconId, fallback)
                .name(name)
                .lore(lore)
                .action("open_tree", tree.getId())
                .build();
    }

    /** 判定整棵树的解锁状态。 */
    private QuestManager.UnlockStatus getTreeUnlockStatus(Player player, QuestTree tree) {
        // 树无前置 → 解锁
        if (tree.getPrerequisites() == null || tree.getPrerequisites().isEmpty()) {
            return QuestManager.UnlockStatus.UNLOCKED;
        }
        if (plugin.getQuestManager().checkPrerequisites(player, tree.getPrerequisites())) {
            return QuestManager.UnlockStatus.UNLOCKED;
        }
        return QuestManager.UnlockStatus.LOCKED_KNOWN;
    }

    private boolean hasAvailableQuest(Player player, QuestTree tree) {
        for (Quest q : tree.getQuests()) {
            PlayerData data = plugin.getPlayerData(player);
            QuestProgress p = data.getProgress(q.getFullId());
            if (p != null && p.getState() == QuestState.ACTIVE) continue;
            if (plugin.getQuestManager().canAccept(player, q) == null) return true;
        }
        return false;
    }

    /** 描述树缺少的前置（用于 lore）。 */
    private List<String> describeMissingPrerequisites(Player player, QuestTree tree) {
        List<String> out = new ArrayList<>();
        if (tree.getPrerequisites() == null) return out;
        PlayerData data = plugin.getPlayerData(player);

        for (String ref : tree.getPrerequisites().getQuestCompletedAll()) {
            Quest q = plugin.getTreeLoader().resolveQuest(ref);
            String full = q == null ? ref : q.getFullId();
            if (!data.isCompleted(full)) {
                out.add("&7需完成：&f" + (q == null ? ref : com.keran.quests.util.Text.strip(q.getName())));
            }
        }
        for (String ref : tree.getPrerequisites().getTreeCompletedAll()) {
            if (!plugin.getQuestManager().isTreeCompleted(data, ref)) {
                QuestTree t = plugin.getTreeLoader().getTree(ref);
                out.add("&7需完成：&f" + (t == null ? ref : com.keran.quests.util.Text.strip(t.getName())));
            }
        }
        List<String> anyQ = tree.getPrerequisites().getQuestCompletedAny();
        if (!anyQ.isEmpty()) {
            int need = tree.getPrerequisites().getQuestCompletedAnyNeed();
            int done = 0;
            List<String> names = new ArrayList<>();
            for (String ref : anyQ) {
                Quest q = plugin.getTreeLoader().resolveQuest(ref);
                String full = q == null ? ref : q.getFullId();
                String nm = q == null ? ref : com.keran.quests.util.Text.strip(q.getName());
                names.add(nm);
                if (data.isCompleted(full)) done++;
            }
            if (done < need) {
                out.add("&7需完成以下任意 &f" + need + " &7个（当前 &a" + done + "&7）：");
                out.add("&8  " + String.join(" &7/ &8", names));
            }
        }
        return out;
    }

    // ==================================================================
    //  ② 任务树详情
    // ==================================================================

    public void openQuestList(Player player, String treeId, int page) {
        QuestTree tree = plugin.getTreeLoader().getTree(treeId);
        if (tree == null) {
            player.closeInventory();
            return;
        }
        PlayerData data = plugin.getPlayerData(player);
        int mainDone = plugin.getQuestManager().countCompleted(data, tree, QuestType.MAIN);
        int sideDone = plugin.getQuestManager().countCompleted(data, tree, QuestType.SIDE);

        String titleTpl = plugin.getConfig().getString("gui.title_quest_list",
                "{tree_name} · 主线 {main}/{main_total} · 支线 {side}/{side_total}");
        // 同时支持短名 {tree} 与长名 {tree_name}：
        // 默认配置节里写的是 {tree}，文档里写的是 {tree_name}，
        // 只认一个会让另一种写法的标题永远显示成花括号原文，故两者都替换。
        String title = com.keran.quests.util.Text.replace(titleTpl,
                "tree_name", com.keran.quests.util.Text.strip(tree.getName()),
                "tree", com.keran.quests.util.Text.strip(tree.getName()),
                "main", String.valueOf(mainDone),
                "main_total", String.valueOf(tree.countMainTotal()),
                "side", String.valueOf(sideDone),
                "side_total", String.valueOf(tree.countSideTotal()));

        Inventory inv = Bukkit.createInventory(null, 54, com.keran.quests.util.Text.color(title));
        fillBackground(inv);

        // ---- 自定义布局优先 ----
        // 配了 layout 就完全按布局摆，禁用自动排列与翻页；
        // 没配则走原来的「按权重铺 21 格 + 翻页」。
        if (tree.hasLayout()) {
            QuestTree.LayoutResult lr = tree.parseLayout(null);
            // 空格占位：画一层不可点的背景板（覆盖已有背景，保证样式统一）
            String emptyIcon = tree.getLayoutEmptyIcon();
            if (emptyIcon == null || emptyIcon.isBlank()) {
                emptyIcon = cfg("gui.icons.bg", "quest_ui_bg");
            }
            for (int slot : lr.emptySlots) {
                if (slot < 0 || slot >= 45) continue;
                inv.setItem(slot, buildLayoutEmpty(emptyIcon));
            }
            // 任务图标
            for (Map.Entry<Integer, Quest> e : lr.slotToQuest.entrySet()) {
                int slot = e.getKey();
                if (slot < 0 || slot >= 45) continue;
                inv.setItem(slot, buildQuestNode(player, data, e.getValue()));
            }
        } else {
            List<Quest> quests = tree.getQuestsByWeight();
            int perPage = 21;
            int totalPages = Math.max(1, (int) Math.ceil(quests.size() / (double) perPage));
            int p = Math.max(0, Math.min(page, totalPages - 1));
            int start = p * perPage;

            int[] slots = {
                    10, 11, 12, 13, 14, 15, 16,
                    19, 20, 21, 22, 23, 24, 25,
                    28, 29, 30, 31, 32, 33, 34
            };

            for (int i = 0; i < perPage && start + i < quests.size(); i++) {
                Quest q = quests.get(start + i);
                inv.setItem(slots[i], buildQuestNode(player, data, q));
            }

            if (totalPages > 1) {
                if (p > 0) {
                    inv.setItem(48, new GuiItem(plugin, cfg("gui.icons.prev", "quest_ui_prev"), Material.ARROW)
                            .name("&e← 上一页")
                            .action("page_tree", treeId + "|" + (p - 1))
                            .build());
                }
                if (p < totalPages - 1) {
                    inv.setItem(50, new GuiItem(plugin, cfg("gui.icons.next", "quest_ui_next"), Material.ARROW)
                            .name("&e下一页 →")
                            .action("page_tree", treeId + "|" + (p + 1))
                            .build());
                }
            }
            pageState.put(player.getUniqueId(), p);
        }

        // 返回
        inv.setItem(45, new GuiItem(plugin, cfg("gui.icons.back", "quest_ui_back"), Material.ARROW)
                .name("&e← 返回任务总览")
                .action("back_tree_list", "")
                .build());
        // 关闭
        inv.setItem(49, new GuiItem(plugin, cfg("gui.icons.close", "quest_ui_close"), Material.BARRIER)
                .name("&c关闭")
                .action("close", "")
                .build());

        currentView.put(player.getUniqueId(), View.QUEST_LIST);
        openGui(player, inv);
    }

    /**
     * 构建「自定义布局里的空格占位」图标：一层不可点的背景板。
     * 显式抹掉 action 键，防止它被当成可点按钮。
     */
    private ItemStack buildLayoutEmpty(String iconId) {
        ItemStack it = new GuiItem(plugin, iconId, Material.GRAY_STAINED_GLASS_PANE)
                .name("&r")
                .build();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(plugin.getActionKey());
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 构建单个任务图标。 */
    private ItemStack buildQuestNode(Player player, PlayerData data, Quest quest) {
        QuestProgress progress = data.getProgress(quest.getFullId());
        QuestState state = progress == null ? QuestState.LOCKED : progress.getState();
        QuestManager.UnlockStatus unlock = plugin.getQuestManager().getUnlockStatus(player, quest);
        int cd = data.getCooldownRemaining(quest.getFullId(), "COOLDOWN");
        int failCd = data.getCooldownRemaining(quest.getFullId(), "FAIL_LOCK");

        // 决定图标
        String iconId;
        Material fallback;
        String stateLabel;

        if (state == QuestState.COMPLETED) {
            iconId = cfg("gui.icons.done", "quest_ui_done");
            fallback = Material.LIME_DYE;
            stateLabel = "&a[✔] 已完成";
        } else if (state == QuestState.ACTIVE) {
            iconId = cfg("gui.icons.active", "quest_ui_active");
            fallback = Material.YELLOW_DYE;
            stateLabel = "&e[▶] 进行中";
        } else if (state == QuestState.FAILED) {
            iconId = cfg("gui.icons.failed", "quest_ui_failed");
            fallback = Material.RED_DYE;
            stateLabel = "&c[✗] 已失败";
        } else if (cd > 0 || failCd > 0) {
            iconId = cfg("gui.icons.cooldown", "quest_ui_cooldown");
            fallback = Material.CLOCK;
            stateLabel = "&7[⌛] 冷却中 &f" + com.keran.quests.util.TimeUtil.format(Math.max(cd, failCd));
        } else if (unlock == QuestManager.UnlockStatus.LOCKED_HIDDEN) {
            iconId = cfg("gui.icons.unknown", "quest_ui_unknown");
            fallback = Material.GRAY_DYE;
            stateLabel = "&8？？？";
        } else if (unlock == QuestManager.UnlockStatus.EXCLUDED) {
            // 被互斥淘汰（抉择选走了另一条线）：
            // 用「失败」图标 + 灰色，语义上最贴切 —— 这条线确实已经没机会了。
            // 这里**不显示 ？？？**，因为玩家需要知道"是哪条线没了"，
            // 藏着只会让人反复来点。
            iconId = cfg("gui.icons.failed", "quest_ui_failed");
            fallback = Material.GRAY_DYE;
            stateLabel = "&8[✖] 不可接取";
        } else if (unlock == QuestManager.UnlockStatus.LOCKED_KNOWN) {
            iconId = cfg("gui.icons.locked", "quest_ui_locked");
            fallback = Material.GRAY_DYE;
            stateLabel = "&c[✖] 未解锁";
        } else {
            iconId = cfg("gui.icons.available", "quest_ui_available");
            fallback = Material.PAPER;
            stateLabel = "&e[❗] 可接取";
        }

        boolean exclud = unlock == QuestManager.UnlockStatus.EXCLUDED;
        boolean selfActive = state == QuestState.ACTIVE;
        // hidden 只用于「？？？」渲染。被淘汰的任务要显示真名（玩家得知道没的是哪条线），
        // 所以即便它配了 hidden: true，也不能算 hidden。
        boolean hidden = unlock == QuestManager.UnlockStatus.LOCKED_HIDDEN && state == QuestState.LOCKED;

        List<String> lore = new ArrayList<>();
        lore.add("&8──────────────");
        lore.add("&7类型：" + (quest.getType() == QuestType.MAIN ? "&6主线" : "&b支线"));
        if (!hidden) {
            lore.add("&7权重：&f" + quest.getWeight());
        }
        lore.add("");
        lore.add(stateLabel);

        if (!hidden) {
            if (state == QuestState.ACTIVE && progress != null) {
                lore.add("&7阶段：&f" + (progress.getStageIndex() + 1) + "&7/&f" + quest.getStageCount());
                if (quest.getTimeLimit() > 0 && progress.getStartedAt() > 0) {
                    long elapsed = (System.currentTimeMillis() - progress.getStartedAt()) / 1000;
                    int remain = (int) Math.max(0, quest.getTimeLimit() - elapsed);
                    lore.add("&7剩余时间：&c" + com.keran.quests.util.TimeUtil.format(remain));
                }
            }
            if (unlock == QuestManager.UnlockStatus.LOCKED_KNOWN) {
                lore.add("");
                lore.add("&7缺少前置：");
                lore.addAll(describeQuestPrerequisites(player, quest));
            }
            if (exclud && !selfActive) {
                // 被互斥淘汰：说清楚"为什么没了"，别让玩家反复来试。
                // 原因是动态算的（可能来自 exclusive_group，也可能来自 exclusive_with），
                // 所以直接取 getExcludeReason 的文本。
                lore.add("");
                String reason = plugin.getQuestManager().getExcludeReason(player, quest);
                lore.add(reason == null ? "&c这条路线已不可用。" : reason);
            }
            if (!exclud) {
                lore.add("");
                lore.add("&7点击查看详情");
            }
        } else {
            lore.add("");
            lore.add("&8完成前置任务以解锁…");
        }

        String name = hidden ? "&8？？？" : quest.getName();

        GuiItem item = new GuiItem(plugin, iconId, fallback)
                .name(name)
                .lore(lore);
        // 只有「可见」的任务才挂点击动作。
        //
        // hidden 为真时任务显示为「？？？」，此前的实现无条件挂 open_quest，
        // 导致玩家点 ？？？ 就能看到完整的第一阶段详情，可见性形同虚设。
        // 这里不挂 action，onClick 取不到标识就会直接返回，点不动。
        //
        // 被淘汰（EXCLUDED）的任务同样不让点：详情页里有「接取」按钮，
        // 点进去只会撞上 canAccept 的互斥提示，白跑一趟。
        if (!hidden && !exclud) {
            item.action("open_quest", quest.getFullId());
        }
        return item.build();
    }

    /** 描述某任务缺少的前置。 */
    private List<String> describeQuestPrerequisites(Player player, Quest quest) {
        List<String> out = new ArrayList<>();
        if (quest.getPrerequisites() == null) return out;
        PlayerData data = plugin.getPlayerData(player);
        var pre = quest.getPrerequisites();

        for (String ref : pre.getQuestCompletedAll()) {
            Quest q = plugin.getTreeLoader().resolveQuest(ref);
            String full = q == null ? ref : q.getFullId();
            String nm = q == null ? ref : com.keran.quests.util.Text.strip(q.getName());
            out.add((data.isCompleted(full) ? "&a [✔] " : "&c [✗] ") + "&f" + nm);
        }
        for (String ref : pre.getTreeCompletedAll()) {
            QuestTree t = plugin.getTreeLoader().getTree(ref);
            String nm = t == null ? ref : com.keran.quests.util.Text.strip(t.getName());
            out.add((plugin.getQuestManager().isTreeCompleted(data, ref) ? "&a [✔] " : "&c [✗] ")
                    + "&f" + nm);
        }
        if (!pre.getQuestCompletedAny().isEmpty()) {
            int need = pre.getQuestCompletedAnyNeed();
            int done = 0;
            List<String> lines = new ArrayList<>();
            for (String ref : pre.getQuestCompletedAny()) {
                Quest q = plugin.getTreeLoader().resolveQuest(ref);
                String full = q == null ? ref : q.getFullId();
                String nm = q == null ? ref : com.keran.quests.util.Text.strip(q.getName());
                boolean ok = data.isCompleted(full);
                if (ok) done++;
                lines.add((ok ? "&a[✔] " : "&7") + nm);
            }
            out.add("&7任意 &f" + need + " &7个（&a" + done + "&7/&f" + need + "&7）：");
            out.add("&8  " + String.join(" &7/ ", lines));
        }
        for (var item : pre.getOraxenItems()) {
            int have = plugin.getOraxenHook().countItem(player, item.getItem());
            out.add((have >= item.getCount() ? "&a [✔] " : "&c [✗] ")
                    + "&f持有 " + item.getItem() + " x" + item.getCount()
                    + " &7(" + have + "/" + item.getCount() + ")");
        }
        if (pre.getPermission() != null && !pre.getPermission().isBlank()) {
            boolean ok = player.hasPermission(pre.getPermission());
            out.add((ok ? "&a [✔] " : "&c [✗] ") + "&f权限 " + pre.getPermission());
        }
        return out;
    }

    // ==================================================================
    //  ③ 任务详情
    // ==================================================================

    public void openQuestDetail(Player player, String fullId) {
        Quest quest = plugin.getTreeLoader().resolveQuest(fullId);
        if (quest == null) {
            player.closeInventory();
            return;
        }

        // 服务端兜底：显示为「？？？」的任务不允许打开详情。
        //
        // 仅靠「不挂 action」不够——玩家可能通过其它途径构造打开请求
        // （旧界面残留点击、或未来新增入口忘了判）。这里按与 buildQuestNode
        // **完全相同**的判据再拦一次，保证 UI 显示与逻辑行为不分家。
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress p = data.getProgress(quest.getFullId());
        QuestManager.UnlockStatus st = plugin.getQuestManager().getUnlockStatus(player, quest);
        QuestState stt = p == null ? QuestState.LOCKED : p.getState();
        boolean hiddenNow = st == QuestManager.UnlockStatus.LOCKED_HIDDEN && stt == QuestState.LOCKED;
        if (hiddenNow) {
            com.keran.quests.util.Text.send(player, "&c该任务尚未解锁，无法查看详情。");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) openQuestList(player, quest.getTreeId(), 0);
            });
            return;
        }

        String title = com.keran.quests.util.Text.strip(quest.getName());
        if (title.length() > 32) title = title.substring(0, 32);

        // 同时支持 {quest_name} 与短名 {quest}（默认配置节用的是 {quest}）
        Inventory inv = Bukkit.createInventory(null, 54,
                com.keran.quests.util.Text.color(plugin.getConfig()
                        .getString("gui.title_quest_detail", "{quest_name}")
                        .replace("{quest_name}", title)
                        .replace("{quest}", title)));
        fillBackground(inv);

        QuestState state = p == null ? QuestState.LOCKED : p.getState();
        int stageIdx = p == null ? 0 : p.getStageIndex();

        // ---- 中央：任务信息 + 阶段列表 ----
        List<String> infoLore = new ArrayList<>();
        infoLore.add("&8──────────────");
        infoLore.add("&7类型：" + (quest.getType() == QuestType.MAIN ? "&6主线" : "&b支线"));
        infoLore.add("&7状态：" + stateLabel(state, data, quest));
        if (state == QuestState.ACTIVE && quest.getTimeLimit() > 0 && p.getStartedAt() > 0) {
            long elapsed = (System.currentTimeMillis() - p.getStartedAt()) / 1000;
            int remain = (int) Math.max(0, quest.getTimeLimit() - elapsed);
            infoLore.add("&7剩余时间：&c" + com.keran.quests.util.TimeUtil.format(remain));
        }
        infoLore.add("&7阶段：&f" + Math.min(stageIdx + 1, quest.getStageCount())
                + "&7/&f" + quest.getStageCount());

        // ---- 任务简介（可选） ----
        // 放在状态信息之后、奖励之前：玩家先看"我现在该干什么"，
        // 再看这段氛围文字，最后看奖励。多行用换行符分隔，逐行渲染。
        if (quest.getDescription() != null && !quest.getDescription().isBlank()) {
            infoLore.add("");
            infoLore.add("&8──────────────");
            for (String line : quest.getDescription().split("\n")) {
                infoLore.add(com.keran.quests.util.Text.color(line));
            }
        }

        infoLore.add("");
        infoLore.add("&7奖励：");
        if (quest.getMoney() > 0) infoLore.add("&8 · &f金钱 &a" + (int) quest.getMoney());
        if (quest.getExp() > 0) infoLore.add("&8 · &f经验 &a" + quest.getExp());
        for (Quest.RewardItem ri : quest.getRewardItems()) {
            infoLore.add("&8 · &f" + ri.getRawId() + " &7x" + ri.getAmount());
        }
        if (quest.getMoney() <= 0 && quest.getExp() <= 0 && quest.getRewardItems().isEmpty()) {
            infoLore.add("&8 · &7无");
        }
        if (quest.isRepeatable()) {
            infoLore.add("");
            infoLore.add("&7循环任务，冷却 &f" + com.keran.quests.util.TimeUtil.formatChinese(quest.getCooldown()));
        }

        inv.setItem(4, new GuiItem(plugin, quest.getIcon(), Material.BOOK)
                .name(quest.getName())
                .lore(infoLore)
                .build());

        // ---- 阶段列表（按可见性） ----
        List<Integer> visibleStages = getVisibleStages(quest, p);
        int[] stageSlots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < visibleStages.size() && i < stageSlots.length; i++) {
            int si = visibleStages.get(i);
            QuestStage stage = quest.getStages().get(si);
            inv.setItem(stageSlots[i], buildStageNode(player, quest, stage, p, si, state));
        }

        // ---- 失败条件 ----
        if (quest.isFailOnDeath() || quest.getTimeLimit() > 0 || !quest.getForbiddenRegions().isEmpty()) {
            List<String> failLore = new ArrayList<>();
            failLore.add("&8──────────────");
            if (quest.isFailOnDeath()) failLore.add("&c[✗] 不可死亡");
            if (quest.getTimeLimit() > 0) {
                failLore.add("&c[✗] " + com.keran.quests.util.TimeUtil.formatChinese(quest.getTimeLimit()) + "内完成");
            }
            for (Quest.ForbiddenRegion fr : quest.getForbiddenRegions()) {
                failLore.add("&c[✗] 不可进入 " + fr.getRegion());
            }
            inv.setItem(40, new GuiItem(plugin, cfg("gui.icons.failed", "quest_ui_failed"), Material.REDSTONE_BLOCK)
                    .name("&c失败条件")
                    .lore(failLore)
                    .build());
        }

        // ---- 按钮 ----
        inv.setItem(45, new GuiItem(plugin, cfg("gui.icons.back", "quest_ui_back"), Material.ARROW)
                .name("&e← 返回")
                .action("open_tree", quest.getTreeId())
                .build());

        if (state == QuestState.ACTIVE) {
            inv.setItem(48, new GuiItem(plugin, cfg("gui.icons.active", "quest_ui_active"), Material.YELLOW_DYE)
                    .name("&e追踪此任务")
                    .lore("&7让 &f%kq_current% &7显示这个任务")
                    .action("track", quest.getFullId())
                    .build());

            // ---- 「继续抉择」按钮 ----
            //
            // 抉择节点靠"原地停留"工作：阶段索引停在抉择那一格，等玩家点选后才推进。
            // 但玩家一旦关掉抉择界面，就没有任何入口能重新打开它 —— 只能退出重进
            // （因为 handledStages 不落盘，重连后内存标记清空才会重弹）。
            // 这里在任务详情页给一个明确的入口，让"稍后再决定"这个承诺真的成立。
            if (p != null && stageIdx < quest.getStageCount()) {
                QuestStage cur = quest.getStages().get(stageIdx);
                if (cur.isChoice() && !cur.getChoices().isEmpty()) {
                    inv.setItem(47, new GuiItem(plugin, cfg("gui.icons.choice", "quest_ui_choice"),
                            Material.PAPER)
                            .name("&6[!] 继续抉择")
                            .lore("&7你有尚未做出的选择：")
                            .lore("&f" + com.keran.quests.util.Text.strip(cur.getChoiceTitle()))
                            .lore("")
                            .lore("&e点击打开抉择界面")
                            .action("reopen_choice", quest.getFullId())
                            .build());
                }
            }

            if (plugin.getConfig().getBoolean("gui.allow_abandon_in_gui", true)) {
                if (quest.isAbandonAllowed()) {
                    GuiItem ab = new GuiItem(plugin, cfg("gui.icons.failed", "quest_ui_failed"), Material.BARRIER)
                            .name("&c放弃任务")
                            .lore("&7进度将被清空");
                    // 放弃代价由任务里的 abandon.commands 决定（扣血/扣钱/记日志都可能），
                    // 插件无法通用地预知，所以这里不再硬编码任何代价提示；
                    // 想告知玩家代价，请写进任务的 abandon.message。
                    if (quest.getAbandonCooldown() > 0) {
                        ab.lore("&7放弃后 &f" + quest.getAbandonCooldown() + " &7秒内无法再次接取");
                    }
                    ab.action("abandon", quest.getFullId());
                    inv.setItem(50, ab.build());
                } else {
                    // 不允许放弃：按钮置灰且不挂 action（点了没反应）
                    inv.setItem(50, new GuiItem(plugin, cfg("gui.icons.locked", "quest_ui_locked"),
                            Material.GRAY_DYE)
                            .name("&8放弃任务")
                            .lore("&7该任务不允许放弃")
                            .build());
                }
            }
        } else {
            String err = plugin.getQuestManager().canAccept(player, quest);
            if (err == null) {
                inv.setItem(49, new GuiItem(plugin, cfg("gui.icons.available", "quest_ui_available"), Material.LIME_DYE)
                        .name("&a[✔] 接取任务")
                        .lore("&7点击开始此任务")
                        .action("accept", quest.getFullId())
                        .build());
            } else {
                inv.setItem(49, new GuiItem(plugin, cfg("gui.icons.locked", "quest_ui_locked"), Material.GRAY_DYE)
                        .name("&c无法接取")
                        .lore("&7" + err)
                        .build());
            }
        }

        inv.setItem(53, new GuiItem(plugin, cfg("gui.icons.close", "quest_ui_close"), Material.BARRIER)
                .name("&c关闭")
                .action("close", "")
                .build());

        currentView.put(player.getUniqueId(), View.QUEST_DETAIL);
        openGui(player, inv);
    }

    /** 计算可见的阶段序号列表。 */
    private List<Integer> getVisibleStages(Quest quest, QuestProgress p) {
        List<Integer> out = new ArrayList<>();
        int n = quest.getStageCount();
        int cur = p == null ? 0 : p.getStageIndex();

        switch (quest.getStageVisibility()) {
            case FULL:
                for (int i = 0; i < n; i++) out.add(i);
                break;
            case HIDDEN:
                out.add(Math.min(cur, Math.max(0, n - 1)));
                break;
            case SEQUENTIAL:
            default:
                for (int i = 0; i <= cur && i < n; i++) out.add(i);
                break;
        }
        return out;
    }

    /** 构建阶段节点。 */
    private ItemStack buildStageNode(Player player, Quest quest, QuestStage stage,
                                     QuestProgress p, int stageIndex, QuestState state) {
        int cur = p == null ? 0 : p.getStageIndex();
        boolean isDone = p != null && p.isStageCompleted(stageIndex);
        boolean isCurrent = stageIndex == cur && state == QuestState.ACTIVE;

        String iconId;
        Material fallback;
        if (isDone) {
            iconId = cfg("gui.icons.stage_done", "quest_ui_stage_done");
            fallback = Material.LIME_STAINED_GLASS_PANE;
        } else if (isCurrent) {
            iconId = cfg("gui.icons.stage_active", "quest_ui_stage_active");
            fallback = Material.YELLOW_STAINED_GLASS_PANE;
        } else {
            iconId = cfg("gui.icons.locked", "quest_ui_locked");
            fallback = Material.GRAY_STAINED_GLASS_PANE;
        }

        List<String> lore = new ArrayList<>();
        lore.add("&8──────────────");
        if (isDone) {
            lore.add("&a[✔] 已完成");
        } else if (isCurrent) {
            lore.add("&e[▶] 进行中");
        } else {
            lore.add("&7未开始");
        }
        lore.add("");

        for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
            Requirement req = stage.getRequirements().get(ri);
            int prog = p == null ? 0 : p.getRequirementProgress(stageIndex, ri);
            int target = req.target();
            boolean ok = prog >= target;
            String mark = ok ? "&a[✔]" : (isCurrent ? "&e[▶]" : "&7·");
            String progText = "";
            switch (req.getType()) {
                case MM_KILL:
                case ORAXEN_ITEM:
                case PLAYER_KILL:
                    progText = " &7" + prog + "/" + target;
                    break;
                case REGION_STAY:
                    progText = " &7" + prog + "/" + target + "秒";
                    break;
                default:
                    progText = ok ? "" : (isCurrent ? "" : " &8未开始");
                    break;
            }
            lore.add(mark + " &f" + com.keran.quests.util.Text.strip(req.describe()) + progText);
            // requirement 的 description（可选）作为补充说明缩进显示
            if (req.getDescription() != null && !req.getDescription().isBlank()) {
                lore.add("   &8" + com.keran.quests.util.Text.strip(req.getDescription()));
            }
        }

        if (stage.getMode() == com.keran.quests.config.model.enums.StageMode.ANY) {
            lore.add("");
            lore.add("&7需满足其中 &f" + stage.getNeed() + " &7条");
        }

        // ---- 阶段简介（可选） ----
        // 放在要求列表下方：先让玩家看清"要做什么"，再给一段叙事文字收尾。
        if (stage.getDescription() != null && !stage.getDescription().isBlank()) {
            lore.add("");
            lore.add("&8──────────────");
            for (String line : stage.getDescription().split("\n")) {
                lore.add(com.keran.quests.util.Text.color(line));
            }
        }

        return new GuiItem(plugin, iconId, fallback)
                .name("&f阶段 " + (stageIndex + 1) + " &7· &f" + com.keran.quests.util.Text.strip(stage.getName()))
                .lore(lore)
                .build();
    }

    private String stateLabel(QuestState state, PlayerData data, Quest quest) {
        switch (state) {
            case COMPLETED:
                return "&a[✔] 已完成";
            case ACTIVE:
                return "&e[▶] 进行中";
            case FAILED:
                return "&c[✗] 已失败";
            case AVAILABLE:
                return "&e[❗] 可接取";
            default:
                return "&7未解锁";
        }
    }

    // ==================================================================
    //  ④ 抉择界面
    // ==================================================================

    public void openChoice(Player player, Quest quest, QuestStage stage) {
        List<QuestStage.Choice> choices = stage.getChoices();

        // 根据选项数量动态决定界面大小与槽位：
        //   1~2 个 → 3 行（27 格），槽位左右对开
        //   3~4 个 → 5 行（45 格），槽位横向均分
        //   5+ 个  → 6 行（54 格），两行铺开
        // 原实现硬编码 {11, 15}，配置 3 个及以上选项时超出的会被静默丢弃，
        // 玩家看到 2 个选项但实际有 3 个，属于功能性缺陷。
        int[] slots;
        int rows;
        if (choices.size() <= 2) {
            rows = 3;
            slots = new int[]{11, 15};
        } else if (choices.size() <= 4) {
            rows = 5;
            slots = new int[]{20, 22, 24, 31};
        } else {
            rows = 6;
            slots = new int[]{19, 21, 23, 25, 29, 31, 33, 37, 39, 41, 43};
        }
        slots = java.util.Arrays.copyOf(slots, Math.min(slots.length, choices.size()));

        String rawTitle = plugin.getConfig().getString("gui.title_choice", "⚠ 抉择 · {title}");
        String title = rawTitle.replace("{title}", stage.getChoiceTitle());

        Inventory inv = Bukkit.createInventory(null, rows * 9,
                com.keran.quests.util.Text.color(title));
        fillBackground(inv);

        // 提示（放在第一行正中间）
        int infoSlot = rows == 3 ? 4 : 4;
        inv.setItem(infoSlot, new GuiItem(plugin, cfg("gui.icons.choice", "quest_ui_choice"), Material.PAPER)
                .name("&6[!] " + stage.getChoiceTitle())
                .lore("&7此选择 &c不可撤销&7，请谨慎决定。")
                .build());

        for (int i = 0; i < choices.size() && i < slots.length; i++) {
            QuestStage.Choice c = choices.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&8──────────────");
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                lore.add("&7" + c.getDescription());
                lore.add("");
            }
            lore.add("&c[!] 选择后不可更改");
            lore.add("");
            // 两级确认：第一下点击只是"选中"，需要在 3 秒内再点一下才真正生效。
            // 这样既防误触，又不依赖 Shift（手机端根本没有 Shift 键）。
            lore.add(pendingChoice.get(player.getUniqueId()) != null
                    && pendingChoice.get(player.getUniqueId()).matches(quest, stage, i)
                    ? "&a[▶] 再次点击以确认"
                    : "&e点击选择");

            inv.setItem(slots[i], new GuiItem(plugin, c.getIcon(), Material.PAPER)
                    .name("&f" + c.getLabel())
                    .lore(lore)
                    .action("choice",
                            quest.getFullId() + "|" + stage.getId() + "|" + i)
                    .build());
        }

        // 关闭按钮放在最后一行正中间
        int closeSlot = (rows - 1) * 9 + 4;
        inv.setItem(closeSlot, new GuiItem(plugin, cfg("gui.icons.close", "quest_ui_close"), Material.BARRIER)
                .name("&7稍后再决定")
                .lore("&7关掉后可从任务详情页的")
                .lore("&7「继续抉择」按钮重新打开")
                .action("close", "")
                .build());

        currentView.put(player.getUniqueId(), View.CHOICE);
        openGui(player, inv);
    }

    // ==================================================================
    //  点击处理
    // ==================================================================

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        View view = currentView.get(player.getUniqueId());
        if (view == null) return;

        event.setCancelled(true);   // 永远阻止拖拽

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String raw = meta.getPersistentDataContainer().get(
                plugin.getActionKey(), PersistentDataType.STRING);
        if (raw == null) return;

        String[] parts = raw.split("\\|", 2);
        String action = parts[0];
        String data = parts.length > 1 ? parts[1] : "";

        switch (action) {
            case "open_tree" -> openQuestList(player, data, 0);
            case "back_tree_list" -> openTreeList(player, 0);
            case "open_quest" -> openQuestDetail(player, data);
            case "close" -> player.closeInventory();
            case "page" -> openTreeList(player, parseInt(data, 0));
            case "page_tree" -> {
                String[] pd = data.split("\\|");
                if (pd.length == 2) openQuestList(player, pd[0], parseInt(pd[1], 0));
            }
            case "accept" -> handleAccept(player, data);
            case "abandon" -> handleAbandon(player, data);
            case "track" -> handleTrack(player, data);
            case "reopen_choice" -> handleReopenChoice(player, data);
            case "choice" -> handleChoice(player, data);
            default -> {
            }
        }
    }

    private void handleAccept(Player player, String fullId) {
        Quest quest = plugin.getTreeLoader().resolveQuest(fullId);
        if (quest == null) return;
        String err = plugin.getQuestManager().accept(player, quest);
        if (err != null) {
            com.keran.quests.util.Text.send(player, err);
        }
        // 刷新界面
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                if (plugin.getConfig().getBoolean("gui.allow_abandon_in_gui", true)) {
                    openQuestDetail(player, fullId);
                } else {
                    openQuestList(player, quest.getTreeId(), 0);
                }
            }
        });
    }

    /**
     * 重新打开当前任务的抉择界面（任务详情页「继续抉择」按钮）。
     *
     * <p>为什么要清 handled 标记：{@code checkStageCompletion} 开头有防重复守卫
     * {@code if (p.isStageHandled(stageIdx)) return false;}，一旦抉择界面弹过一次
     * 就会打上标记，之后再也不会弹。这里把它清掉，保证点击必定能打开界面。
     *
     * <p>注意只清 handled、<b>不清 completed</b>：completed 表示"阶段条件已满足"，
     * 是真实进度，清掉反而会让阶段状态显示回退。
     */
    private void handleReopenChoice(Player player, String fullId) {
        Quest quest = plugin.getTreeLoader().resolveQuest(fullId);
        if (quest == null) return;
        PlayerData data = plugin.getPlayerData(player);
        QuestProgress p = data.getProgress(quest.getFullId());
        if (p == null || p.getState() != QuestState.ACTIVE) {
            com.keran.quests.util.Text.send(player, "&c该任务不在进行中。");
            return;
        }
        int idx = p.getStageIndex();
        if (idx >= quest.getStageCount()) return;
        QuestStage stage = quest.getStages().get(idx);
        if (!stage.isChoice() || stage.getChoices().isEmpty()) {
            // 配置改了 / 状态错位：给个明确反馈，别让玩家点了没反应
            com.keran.quests.util.Text.send(player, "&c当前阶段不是抉择节点。");
            openQuestDetail(player, fullId);
            return;
        }
        p.unmarkStageHandled(idx);
        data.markDirty();
        plugin.getPlayerDataStore().save(data);
        plugin.getChoiceManager().openChoice(player, quest, stage);
    }

    private void handleAbandon(Player player, String fullId) {
        Quest quest = plugin.getTreeLoader().resolveQuest(fullId);
        if (quest == null) return;
        String err = plugin.getQuestManager().abandon(player, quest);
        if (err != null) com.keran.quests.util.Text.send(player, err);
        openQuestDetail(player, fullId);
    }

    private void handleTrack(Player player, String fullId) {
        plugin.getPlayerData(player).setTrackedQuest(fullId);
        plugin.getPlayerDataStore().save(plugin.getPlayerData(player));
        com.keran.quests.util.Text.send(player, "&a已追踪该任务。");
        player.closeInventory();
    }

    private void handleChoice(Player player, String data) {
        String[] parts = data.split("\\|");
        if (parts.length < 3) return;
        Quest quest = plugin.getTreeLoader().resolveQuest(parts[0]);
        if (quest == null) return;
        QuestStage stage = null;
        for (QuestStage s : quest.getStages()) {
            if (s.getId().equals(parts[1])) {
                stage = s;
                break;
            }
        }
        if (stage == null) return;
        final QuestStage stageRef = stage;   // lambda 里要用，得是 effectively final
        int idx = parseInt(parts[2], -1);
        if (idx < 0) return;

        // ---- 两级确认：第一下点击只是选中，再点一下才生效 ----
        PendingChoice pc = pendingChoice.get(player.getUniqueId());
        if (pc == null || !pc.matches(quest, stage, idx)) {
            // 第一次点击（或点了别的选项 / 上次已超时）→ 记下待确认项并重绘界面
            long window = choiceConfirmWindowMs();
            pendingChoice.put(player.getUniqueId(), new PendingChoice(
                    quest.getFullId(), stage.getId(), idx, window));
            // 重绘当前的抉择界面，让该选项的 lore 变成「再次点击以确认」
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) openChoice(player, quest, stageRef);
            });
            com.keran.quests.util.Text.send(player, "&e已选中 &f"
                    + com.keran.quests.util.Text.strip(stage.getChoices().get(idx).getLabel())
                    + " &e，请在 &f" + (window / 1000)
                    + " &e秒内再点一次以确认。");
            return;
        }

        // 第二次点击同一个选项 → 真正生效
        pendingChoice.remove(player.getUniqueId());
        String err = plugin.getChoiceManager().choose(player, quest, stage, idx);
        if (err != null) com.keran.quests.util.Text.send(player, err);
        player.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        UUID id = p.getUniqueId();

        // 【关键】不能在这里无条件清空 currentView。
        //
        // GUI 内部跳转的实现是「先 currentView.put(新界面) → 再 openInventory(新界面)」，
        // 而 openInventory 切换界面时，服务端会先派发一次 InventoryCloseEvent 关掉旧界面。
        // 如果这里无条件 remove，就会把刚 put 进去的「新界面」记录擦掉，
        // 导致新界面虽然开着、currentView 却是 null ——
        // 于是点击全部无响应、物品还能被拖走（因为事件没被取消）。
        //
        // 解决办法：跳转时把「下一次关闭」标记为可忽略。
        // openXxx() 在 put 之后调用 suppressNextClose(player)，
        // 这样紧跟而来的那次 close 事件不会误删新界面的记录。
        // 登记过「跳转产生的关闭」→ 这次关闭是旧界面被替换，不能清 currentView。
        // 加 1 秒有效期：万一登记后没等来 close 事件（例如 openInventory 抛异常），
        // 超时后自动失效，避免这个标记一直误吞后续真正的关闭事件。
        Long marked = suppressClose.remove(id);
        if (marked != null && System.currentTimeMillis() - marked <= 1000L) {
            return;
        }

        currentView.remove(id);
        pageState.remove(id);
        suppressClose.remove(id);
        // 关掉界面就等于放弃这次待确认，避免下次打开时选项还是"确认态"
        pendingChoice.remove(id);
    }

    /**
     * 登记「该玩家下一次 InventoryCloseEvent 是界面跳转产生的，应忽略」。
     *
     * <p>在每次 openInventory 之前调用。若玩家手动关闭界面，
     * 不会有人清掉这个标记，因此 {@link #onClick} 之外的地方也要注意：
     * 这里用 Long 记录登记时间，超过 1 秒未消费则视为过期自动失效，
     * 避免标记残留导致后续真正的关闭被漏清理。
     */
    private void suppressNextClose(Player player) {
        suppressClose.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * 打开 GUI 的统一出口。
     *
     * <p>必须走这里而不是直接 {@code player.openInventory(inv)}：
     * 切换界面会触发一次旧界面的 {@link InventoryCloseEvent}，
     * 需要先登记 suppress 标记，避免 {@link #onClose} 把刚写入的 currentView 擦掉。
     */
    private void openGui(Player player, Inventory inv) {
        suppressNextClose(player);
        player.openInventory(inv);
    }

    /** 玩家下线时清理其全部 GUI 状态（由 PlayerLifecycleListener 调用）。 */
    public void forget(UUID id) {
        currentView.remove(id);
        pageState.remove(id);
        suppressClose.remove(id);
        pendingChoice.remove(id);
    }

    // ==================================================================
    //  工具
    // ==================================================================

    private void fillBackground(Inventory inv) {
        String bgId = cfg("gui.icons.bg", "quest_ui_bg");
        ItemStack bg = new GuiItem(plugin, bgId, Material.GRAY_STAINED_GLASS_PANE)
                .name("&r")
                .build();
        ItemMeta meta = bg.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(plugin.getActionKey());
            bg.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, bg.clone());
        }
    }

    private String cfg(String path, String def) {
        String v = plugin.getConfig().getString(path, def);
        return v == null ? def : v;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 去掉多余小数位：5.0 -> 5，2.5 -> 2.5。 */
    private static String fmtNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
