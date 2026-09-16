package com.keran.quests.runtime;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.player.PlayerData;
import com.keran.quests.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对话执行器 v2 —— 节点式对话，支持聊天栏内点击交互。
 *
 * <h3>v2 相比 v1 的改进</h3>
 * <ol>
 *   <li><b>一个 NPC 多条对话</b>：按 {@code priority} 降序，取第一条
 *       {@link DialogCondition} 满足的。解决「第 1 次和第 10 次见台词一样」。</li>
 *   <li><b>节点式</b>：对话由 {@code say} / {@code ask} / {@code choose} 节点串成，
 *       不再只是「一条条播台词」。</li>
 *   <li><b>可点击</b>：{@code ask} 节点列出可点的问题，点一个答一个，
 *       答完自动回问句菜单，实现塔科夫式「问 a 答 a、问 b 答 b」。</li>
 *   <li><b>对话内抉择</b>：{@code choose} 节点直接推进任务，无需切到 GUI。</li>
 * </ol>
 *
 * <h3>向后兼容</h3>
 * 旧格式（{@code lines:} + {@code after:}）照常可用：加载时会把 lines 自动
 * 转成一串 {@code say} 节点，行为与 v1 一致。老配置无需修改。
 *
 * <h3>配置示例</h3>
 * <pre>
 * dialogues:
 *   kate_meet:
 *     npc: '卡特'
 *     priority: 20
 *     freeze_player: true
 *     when:
 *       quest_active: [ 'zhulong:main01' ]
 *     nodes:
 *       - say:
 *           text: '&amp;7[卡特] &amp;f哦？又一个不怕死的老鼠……'
 *           delay: 0
 *           run: [ '/rad play {player} http://.../main_kate_01.mp3' ]
 *       - ask:
 *           prompt: '&amp;7你想问什么？'
 *           questions:
 *             - label: '烛龙站是？'
 *               answer: '&amp;7[卡特] &amp;f你不需要知道这么多。'
 *               after: 60
 *             - label: '我没有问题了'
 *               exit: true
 *       - say:
 *           text: '&amp;7[卡特] &amp;f就这么定了……'
 * </pre>
 */
public class DialogueRunner {

    private final KeranQuests plugin;

    /** NPC 名（小写） -> 该 NPC 的全部对话（已按 priority 降序） */
    private final Map<String, List<Dialogue>> byNpc = new LinkedHashMap<>();

    /** 正在对话中的玩家 */
    private final Set<UUID> talking = new HashSet<>();

    /** 玩家 -> 本次对话挂起的定时任务（用于取消） */
    private final Map<UUID, List<BukkitTask>> runningTasks = new HashMap<>();

    /**
     * 玩家 -> 当前对话会话。
     *
     * <p>点击回调（{@code /kqdlg pick ...}）需要靠它找到「玩家现在在哪个
     * 对话的哪个节点」，因此必须保存。玩家下线 / 对话结束时清除。
     */
    private final Map<UUID, Session> sessions = new HashMap<>();

    public DialogueRunner(KeranQuests plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    //  加载
    // ==================================================================

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

            int total = 0;
            for (String key : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;
                Dialogue d = Dialogue.fromConfig(key, sec);
                if (d == null) continue;
                byNpc.computeIfAbsent(d.npc.toLowerCase(), k -> new ArrayList<>()).add(d);
                total++;
            }
            // 每个 NPC 内部按 priority 降序（大的优先匹配）
            for (List<Dialogue> list : byNpc.values()) {
                list.sort((a, b) -> Integer.compare(b.priority, a.priority));
            }
            plugin.getLogger().info("已加载 " + total + " 条 NPC 对话（覆盖 "
                    + byNpc.size() + " 个 NPC）。");
        } catch (Throwable t) {
            plugin.getLogger().warning("加载对话配置失败：" + t.getMessage());
        }
    }

    // ==================================================================
    //  启动对话
    // ==================================================================

    /**
     * 玩家右键 NPC 时调用。
     *
     * <p>先按 priority 顺序找「第一条条件满足」的对话；都没命中则返回 false，
     * 让上层继续走 NPC_TALK 检定。
     *
     * @return true = 确实开播了对话
     */
    public boolean startDialogue(Player player, String npcName) {
        if (npcName == null) return false;
        List<Dialogue> list = byNpc.get(npcName.toLowerCase());
        if (list == null || list.isEmpty()) return false;

        UUID uid = player.getUniqueId();
        if (talking.contains(uid)) return true;   // 已在对话中，忽略重复右键

        // 挑选第一条条件满足的
        Dialogue chosen = null;
        for (Dialogue d : list) {
            if (d.condition.test(player)) {
                chosen = d;
                break;
            }
        }
        if (chosen == null) return false;

        talking.add(uid);
        cancelTasks(uid);

        Session s = new Session();
        s.uid = uid;
        s.dialogue = chosen;
        s.nodeIndex = 0;
        sessions.put(uid, s);

        if (chosen.freeze) {
            plugin.getFreezeManager().freeze(uid, estimateTicks(chosen) + 200);
        }

        runFrom(player, s, 0);
        return true;
    }

    // ==================================================================
    //  节点执行（核心）
    // ==================================================================

    /**
     * 从第 {@code index} 个节点开始执行。
     *
     * <p>执行模型：逐个节点跑；遇到需要等待的节点（有 delay 或需要玩家点击）
     * 就挂定时任务/等待回调，然后 return。回调里再调本方法继续跑下一个节点。
     * 整条对话是「链式」推进的 —— 不会像 v1 那样一次性把所有节点排期。
     */
    private void runFrom(Player player, Session s, int index) {
        if (!player.isOnline()) {
            endSession(s);
            return;
        }
        s.nodeIndex = index;

        if (index >= s.dialogue.nodes.size()) {
            finish(player, s);
            return;
        }

        Node n = s.dialogue.nodes.get(index);
        switch (n.type) {
            case SAY -> doSay(player, s, n, index);
            case ASK -> doAsk(player, s, n, index);
            case CHOOSE -> doChoose(player, s, n, index);
            default -> runFrom(player, s, index + 1);
        }
    }

    /** say 节点：发一句台词（可带 run 命令），delay 后自动进下一节点。 */
    private void doSay(Player player, Session s, Node n, int index) {
        schedule(player, s, n.delay, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(DialogText.line(
                    Text.replace(n.text, "player", player.getName())));
            runCommands(player, n.run);
            schedule(player, s, Math.max(1, n.after),
                    () -> runFrom(player, s, index + 1));
        });
    }

    /**
     * ask 节点：列出可点问题。玩家点一个 → 答一句 → 回到本菜单（循环）。
     * 直到点了 {@code exit: true} 的问题，才进入下一节点。
     *
     * <p>这正是「问 a 答 a、问 b 答 b、问够了点『我没有问题了』」的实现。
     */
    private void doAsk(Player player, Session s, Node n, int index) {
        schedule(player, s, n.delay, () -> {
            if (!player.isOnline()) return;
            if (n.prompt != null && !n.prompt.isBlank()) {
                player.sendMessage(DialogText.line(
                        Text.replace(n.prompt, "player", player.getName())));
            }
            List<DialogText.Btn> btns = new ArrayList<>();
            for (int i = 0; i < n.questions.size(); i++) {
                Question q = n.questions.get(i);
                // 条件不满足的问题直接不显示（用于"随进度解锁新问题"）
                if (!q.condition.test(player)) continue;
                String cmd = DialogText.CMD + " pick " + s.dialogue.id + " " + index + " " + i;
                btns.add(new DialogText.Btn(
                        q.exit ? ("&8[ " + q.label + " ]") : ("&e[ " + q.label + " ]"),
                        cmd, q.hover));
            }
            if (btns.isEmpty()) {
                // 没有问题可显示 → 直接往下走，避免卡死
                runFrom(player, s, index + 1);
                return;
            }
            player.sendMessage(DialogText.buttonRow(btns, null));
        });
    }

    /**
     * choose 节点：亮出抉择选项（聊天栏点击，非 GUI）。
     *
     * <p>支持物品门槛：不够则灰掉并悬停说明原因。
     */
    private void doChoose(Player player, Session s, Node n, int index) {
        schedule(player, s, n.delay, () -> {
            if (!player.isOnline()) return;
            if (n.prompt != null && !n.prompt.isBlank()) {
                player.sendMessage(DialogText.line(
                        Text.replace(n.prompt, "player", player.getName())));
            }
            List<DialogText.Btn> btns = new ArrayList<>();
            for (int i = 0; i < n.options.size(); i++) {
                Option o = n.options.get(i);
                if (!o.condition.test(player)) continue;
                boolean enough = true;
                String tip = o.hover;
                if (o.requireItem != null) {
                    int have = plugin.getOraxenHook().countItem(player, o.requireItem);
                    if (have < o.requireCount) {
                        enough = false;
                        tip = "需要「" + o.requireItem + "」×" + o.requireCount
                                + "（你有 " + have + "）";
                    }
                }
                String cmd = DialogText.CMD + " choose " + s.dialogue.id + " " + index + " " + i;
                btns.add(new DialogText.Btn("&c[ " + o.label + " ]", cmd, tip, enough));
            }
            if (btns.isEmpty()) {
                runFrom(player, s, index + 1);
                return;
            }
            player.sendMessage(DialogText.buttonRow(btns, null));
        });
    }

    // ==================================================================
    //  点击回调（由 /kqdlg 命令调用）
    // ==================================================================

    /**
     * 玩家点了某个问题。
     *
     * @return null 表示成功，否则返回错误提示
     */
    public String onPick(Player player, String dialogId, int nodeIndex, int qIndex) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return "&c对话已结束。";
        if (!s.dialogue.id.equals(dialogId) || s.nodeIndex != nodeIndex) {
            return "&c这个选项已经过期了。";
        }
        if (s.nodeIndex >= s.dialogue.nodes.size()) return "&c对话已结束。";
        Node n = s.dialogue.nodes.get(s.nodeIndex);
        if (n.type != NodeType.ASK || qIndex < 0 || qIndex >= n.questions.size()) {
            return "&c无效的选项。";
        }
        Question q = n.questions.get(qIndex);
        if (!q.condition.test(player)) return "&c这个选项当前不可用。";

        if (q.answer != null && !q.answer.isBlank()) {
            player.sendMessage(DialogText.line(
                    Text.replace(q.answer, "player", player.getName())));
        }
        runCommands(player, q.run);

        if (q.exit) {
            // 跳出问答循环 → 进入下一节点
            schedule(player, s, Math.max(1, q.after),
                    () -> runFrom(player, s, s.nodeIndex + 1));
        } else {
            // 回到本问句菜单（可反复问）
            schedule(player, s, Math.max(1, q.after),
                    () -> runFrom(player, s, s.nodeIndex));
        }
        return null;
    }

    /**
     * 玩家点了某个抉择选项。
     *
     * <p>流程：物品门槛复核 → 扣除（若配置）→ 记录抉择 → 接取分支任务。
     * 若接取失败且已扣物品，则把物品退回去，避免玩家白交东西。
     *
     * @return null 表示成功，否则返回错误提示
     */
    public String onChoose(Player player, String dialogId, int nodeIndex, int oIndex) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return "&c对话已结束。";
        if (!s.dialogue.id.equals(dialogId) || s.nodeIndex != nodeIndex) {
            return "&c这个选项已经过期了。";
        }
        if (s.nodeIndex >= s.dialogue.nodes.size()) return "&c对话已结束。";
        Node n = s.dialogue.nodes.get(s.nodeIndex);
        if (n.type != NodeType.CHOOSE || oIndex < 0 || oIndex >= n.options.size()) {
            return "&c无效的选项。";
        }
        Option o = n.options.get(oIndex);
        if (!o.condition.test(player)) return "&c这个选项当前不可用。";

        // ---- 物品门槛复核 + 扣除（带回滚） ----
        //
        // 【必须拆成两步】门槛校验（require_item 非空就一定要查）与
        // 是否扣除（consume）是两件独立的事。
        // 早期实现把它俩写成一个条件 `itemId != null && o.consume`，
        // 导致「配了 require_item 但 consume: false」的选项门槛被完全跳过，
        // 玩家没物品也能点 —— 这是实测中真实抓到的缺陷。
        boolean consumed = false;
        String itemId = o.requireItem;
        int itemCount = Math.max(1, o.requireCount);
        if (itemId != null) {
            int have = plugin.getOraxenHook().countItem(player, itemId);
            if (have < itemCount) {
                return "&c你没有「" + itemId + "」×" + itemCount + "（当前 " + have + "），无法选择。";
            }
            if (o.consume) {
                plugin.getOraxenHook().removeItem(player, itemId, itemCount);
                consumed = true;
            }
        }

        // ---- 记录抉择（树级状态，持久化） ----
        if (o.quest != null && !o.quest.isBlank()) {
            Quest target = plugin.getTreeLoader().resolveQuest(o.quest);
            if (target == null) {
                rollback(player, itemId, itemCount, consumed);
                return "&c配置错误：目标任务 " + o.quest + " 不存在。";
            }
            PlayerData data = plugin.getPlayerData(player);
            if (o.choiceGroup != null && !o.choiceGroup.isBlank()) {
                data.setTreeState(target.getTreeId(), "choice:" + o.choiceGroup,
                        o.value == null ? valueOf(oIndex) : o.value);
                data.markDirty();
                plugin.getPlayerDataStore().save(data);
            }
            String err = plugin.getQuestManager().accept(player, target);
            if (err != null) {
                rollback(player, itemId, itemCount, consumed);
                return "&e分支任务接取失败：" + err;
            }
            Text.send(player, plugin.getConfig().getString("messages.prefix", "")
                    + "&a你选择了：&f" + o.label);
        }

        // ---- 播后续台词，推进下一节点 ----
        if (o.reply != null && !o.reply.isBlank()) {
            player.sendMessage(DialogText.line(
                    Text.replace(o.reply, "player", player.getName())));
        }
        runCommands(player, o.run);
        schedule(player, s, Math.max(1, o.after),
                () -> runFrom(player, s, s.nodeIndex + 1));
        return null;
    }

    /** 扣除后接取失败 → 把物品退回去。 */
    private void rollback(Player player, String itemId, int count, boolean consumed) {
        if (!consumed || itemId == null) return;
        plugin.getOraxenHook().giveItem(player, itemId, count);
        plugin.getLogger().warning("抉择接取失败，已退还 " + itemId + " ×" + count
                + " 给 " + player.getName());
    }

    /**
     * 玩家主动放弃当前对话（点「结束对话」或走 /kqdlg end）。
     *
     * <p>与正常跑完的区别：<b>不执行 after 命令、不推进 NPC_TALK 检定</b>。
     * 「说了半截就走人」不该算成「和 NPC 谈完了」。
     */
    public void onQuit(Player player, String dialogId) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        if (dialogId != null && !dialogId.isBlank() && !s.dialogue.id.equals(dialogId)) {
            return;   // id 对不上，说明是过期的点击
        }
        endSession(s);
        Text.send(player, "&7你结束了对话。");
    }

    // ==================================================================
    //  收尾
    // ==================================================================

    /** 对话全部节点跑完。 */
    private void finish(Player player, Session s) {
        UUID uid = player.getUniqueId();
        talking.remove(uid);
        sessions.remove(uid);
        cancelTasks(uid);
        plugin.getFreezeManager().unfreeze(uid);

        for (String raw : s.dialogue.after) {
            dispatch(Text.replace(raw, "player", player.getName()));
        }
        // 推进 NPC_TALK 检定（与 v1 行为一致）
        plugin.getQuestManager().fireNpcTalk(player, s.dialogue.npc);
    }

    /** 中途结束（下线 / 异常），不跑 after、不推进任务。 */
    private void endSession(Session s) {
        UUID uid = s.uid;
        talking.remove(uid);
        sessions.remove(uid);
        cancelTasks(uid);
        plugin.getFreezeManager().unfreeze(uid);
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
        int n = 0;
        for (List<Dialogue> l : byNpc.values()) n += l.size();
        return n;
    }

    /** 全部已配置对话的原始 NPC 名（保留大小写，供 Tab 补全）。 */
    public List<String> getNpcNames() {
        List<String> out = new ArrayList<>();
        for (List<Dialogue> l : byNpc.values()) {
            for (Dialogue d : l) {
                if (!out.contains(d.npc)) out.add(d.npc);
            }
        }
        return out;
    }

    /** 插件卸载 / 重载时清理。 */
    public void shutdown() {
        for (UUID uid : new ArrayList<>(runningTasks.keySet())) {
            cancelTasks(uid);
        }
        runningTasks.clear();
        for (UUID uid : new ArrayList<>(talking)) {
            plugin.getFreezeManager().unfreeze(uid);
        }
        talking.clear();
        sessions.clear();
    }

    // ==================================================================
    //  工具
    // ==================================================================

    private void schedule(Player player, Session s, int delayTicks, Runnable r) {
        if (delayTicks <= 0) {
            r.run();
            return;
        }
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 会话已结束（玩家下线 / 对话被打断）时不再继续，避免幽灵台词
            if (player.isOnline() && sessions.containsKey(s.uid)) r.run();
        }, delayTicks);
        runningTasks.computeIfAbsent(s.uid, k -> new ArrayList<>()).add(t);
    }

    private void runCommands(Player player, List<String> cmds) {
        for (String raw : cmds) {
            dispatch(Text.replace(raw, "player", player.getName()));
        }
    }

    private void dispatch(String cmd) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable t) {
            plugin.getLogger().warning("对话命令执行失败：" + cmd);
        }
    }

    private void cancelTasks(UUID uid) {
        List<BukkitTask> tasks = runningTasks.remove(uid);
        if (tasks == null) return;
        for (BukkitTask t : tasks) {
            try {
                if (t != null && !t.isCancelled()) t.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 估算对话总时长（tick），用于冻结到期时间。 */
    private int estimateTicks(Dialogue d) {
        int sum = 0;
        for (Node n : d.nodes) sum += n.delay + n.after + 40;
        return Math.max(200, sum);
    }

    private static String valueOf(int idx) {
        return idx == 0 ? "A" : idx == 1 ? "B" : idx == 2 ? "C" : String.valueOf(idx);
    }

    // ==================================================================
    //  内部模型
    // ==================================================================

    // ------------------------------------------------------------------
    //  测试入口（包级可见，仅供单元测试直接验证解析结果）
    // ------------------------------------------------------------------

    /** 解析一条对话定义。测试用。 */
    static Object testParse(String id, ConfigurationSection sec) {
        Dialogue d = Dialogue.fromConfig(id, sec);
        return d;
    }

    /** 取对话的节点类型名列表。测试用。 */
    static List<String> testNodeTypes(Object dialogue) {
        List<String> out = new ArrayList<>();
        if (!(dialogue instanceof Dialogue d)) return out;
        for (Node n : d.nodes) out.add(n.type.name());
        return out;
    }

    /** 取对话的 priority。测试用。 */
    static int testPriority(Object dialogue) {
        return dialogue instanceof Dialogue d ? d.priority : -1;
    }

    /** 取 ask 节点的问题数。测试用。 */
    static int testQuestionCount(Object dialogue, int nodeIdx) {
        if (!(dialogue instanceof Dialogue d)) return -1;
        if (nodeIdx < 0 || nodeIdx >= d.nodes.size()) return -1;
        return d.nodes.get(nodeIdx).questions.size();
    }

    /** 取 choose 节点的选项数。测试用。 */
    static int testOptionCount(Object dialogue, int nodeIdx) {
        if (!(dialogue instanceof Dialogue d)) return -1;
        if (nodeIdx < 0 || nodeIdx >= d.nodes.size()) return -1;
        return d.nodes.get(nodeIdx).options.size();
    }

    /** 取 say 节点的文本。测试用。 */
    static String testSayText(Object dialogue, int nodeIdx) {
        if (!(dialogue instanceof Dialogue d)) return null;
        if (nodeIdx < 0 || nodeIdx >= d.nodes.size()) return null;
        return d.nodes.get(nodeIdx).text;
    }

    /** 取对话框的 after 命令数。测试用。 */
    static int testAfterCount(Object dialogue) {
        return dialogue instanceof Dialogue d ? d.after.size() : -1;
    }

    private enum NodeType {SAY, ASK, CHOOSE}

    /** 一次对话会话。 */
    private static class Session {
        UUID uid;
        Dialogue dialogue;
        int nodeIndex;
    }

    /** 一个 NPC 的一套对话。 */
    private static class Dialogue {
        String id;
        String npc;
        int priority;
        boolean freeze = true;
        DialogCondition condition = DialogCondition.fromConfig(null);
        List<Node> nodes = new ArrayList<>();
        List<String> after = new ArrayList<>();

        static Dialogue fromConfig(String id, ConfigurationSection sec) {
            // 防御：配置写错位置 / 传空时安全返回，而不是让整个加载流程崩掉
            if (sec == null) return null;
            String npc = sec.getString("npc");
            if (npc == null || npc.isBlank()) return null;
            Dialogue d = new Dialogue();
            d.id = id;
            d.npc = npc;
            d.priority = sec.getInt("priority", 0);
            d.freeze = sec.getBoolean("freeze_player", true);
            d.condition = DialogCondition.fromConfig(
                    sec.getConfigurationSection("when"));
            d.after = new ArrayList<>(sec.getStringList("after"));

            // ---- 新格式：nodes ----
            List<?> rawNodes = sec.getList("nodes");
            if (rawNodes != null) {
                for (Object o : rawNodes) {
                    ConfigurationSection ns = toSection(o);
                    if (ns == null) continue;
                    Node n = Node.fromConfig(ns);
                    if (n != null) d.nodes.add(n);
                }
            }

            // ---- 旧格式：lines（自动转成 say 节点，保证向后兼容） ----
            if (d.nodes.isEmpty()) {
                List<?> rawLines = sec.getList("lines");
                if (rawLines != null) {
                    int prev = 0;
                    for (Object o : rawLines) {
                        ConfigurationSection ls = toSection(o);
                        if (ls == null) continue;
                        String text = ls.getString("text", "");
                        if (text.isBlank()) continue;
                        int delay = ls.getInt("delay", 0);
                        Node n = new Node();
                        n.type = NodeType.SAY;
                        n.text = text;
                        // 旧格式 delay 是「从对话开始算的绝对时间」，
                        // 节点模型里改成「相对上一个节点的间隔」
                        n.delay = Math.max(0, delay - prev);
                        prev = delay;
                        n.after = 0;
                        d.nodes.add(n);
                    }
                }
            }
            if (d.nodes.isEmpty() && d.after.isEmpty()) return null;
            return d;
        }
    }

    /** 一个对话节点。 */
    private static class Node {
        NodeType type;
        int delay;      // 进入本节点前等待
        int after;      // 本节点输出后等待多久进下一节点
        // say
        String text;
        List<String> run = new ArrayList<>();
        // ask
        String prompt;
        List<Question> questions = new ArrayList<>();
        // choose
        List<Option> options = new ArrayList<>();

        static Node fromConfig(ConfigurationSection ns) {
            if (ns == null) return null;
            Node n = new Node();
            if (ns.isConfigurationSection("say")) {
                ConfigurationSection c = ns.getConfigurationSection("say");
                n.type = NodeType.SAY;
                n.text = c.getString("text", "");
                n.delay = c.getInt("delay", 0);
                n.after = c.getInt("after", 0);
                n.run = new ArrayList<>(c.getStringList("run"));
                return n.text.isBlank() ? null : n;
            }
            if (ns.isConfigurationSection("ask")) {
                ConfigurationSection c = ns.getConfigurationSection("ask");
                n.type = NodeType.ASK;
                n.prompt = c.getString("prompt", "");
                n.delay = c.getInt("delay", 0);
                List<?> qs = c.getList("questions");
                if (qs != null) {
                    for (Object o : qs) {
                        ConfigurationSection qs2 = toSection(o);
                        if (qs2 == null) continue;
                        Question q = new Question();
                        q.label = qs2.getString("label", "");
                        q.answer = qs2.getString("answer", "");
                        q.hover = qs2.getString("hover", null);
                        q.exit = qs2.getBoolean("exit", false);
                        q.after = qs2.getInt("after", 0);
                        q.run = new ArrayList<>(qs2.getStringList("run"));
                        q.condition = DialogCondition.fromConfig(
                                qs2.getConfigurationSection("when"));
                        if (!q.label.isBlank()) n.questions.add(q);
                    }
                }
                return n.questions.isEmpty() ? null : n;
            }
            if (ns.isConfigurationSection("choose")) {
                ConfigurationSection c = ns.getConfigurationSection("choose");
                n.type = NodeType.CHOOSE;
                n.prompt = c.getString("prompt", "");
                n.delay = c.getInt("delay", 0);
                List<?> os = c.getList("options");
                if (os != null) {
                    for (Object o : os) {
                        ConfigurationSection os2 = toSection(o);
                        if (os2 == null) continue;
                        Option op = new Option();
                        op.label = os2.getString("label", "");
                        op.quest = os2.getString("quest", null);
                        op.choiceGroup = os2.getString("choice_group", null);
                        op.value = os2.getString("value", null);
                        op.reply = os2.getString("reply", "");
                        op.hover = os2.getString("hover", null);
                        op.after = os2.getInt("after", 0);
                        op.run = new ArrayList<>(os2.getStringList("run"));
                        op.requireItem = os2.getString("require_item", null);
                        op.requireCount = os2.getInt("require_count", 1);
                        op.consume = os2.getBoolean("consume", false);
                        op.condition = DialogCondition.fromConfig(
                                os2.getConfigurationSection("when"));
                        if (!op.label.isBlank()) n.options.add(op);
                    }
                }
                return n.options.isEmpty() ? null : n;
            }
            return null;
        }
    }

    /** ask 节点里的一个问题。 */
    private static class Question {
        String label;
        String answer;
        String hover;
        boolean exit;
        int after;
        List<String> run = new ArrayList<>();
        DialogCondition condition = DialogCondition.fromConfig(null);
    }

    /** choose 节点里的一个选项。 */
    private static class Option {
        String label;
        String quest;
        String choiceGroup;
        String value;
        String reply;
        String hover;
        int after;
        List<String> run = new ArrayList<>();
        String requireItem;
        int requireCount = 1;
        boolean consume;
        DialogCondition condition = DialogCondition.fromConfig(null);
    }

    /**
     * 把 yml list 里的元素转成 {@link ConfigurationSection}。
     *
     * <h3>★ 为什么必须「深转换」</h3>
     * Bukkit 的 {@code getList()} <b>不会</b>把嵌套的 Map 递归转成
     * ConfigurationSection —— 它只做最外层：List 里的元素是
     * {@link java.util.LinkedHashMap}，且其 value 里若还有 Map（例如
     * {@code nodes: [ {ask: {questions: [ {...} ]}} ]}），那些内层 Map
     * 依然是 Map。
     *
     * <p>如果只做浅转换（把 Map 的 key/value 直接 set 进 MemoryConfiguration），
     * 那么 {@code sec.isConfigurationSection("say")} 会返回 <b>false</b>
     * —— 因为它拿到的是一个普通 Map 值，不是 ConfigurationSection。
     * 后果是所有节点被静默跳过、对话变成「右键 NPC 毫无反应」。
     *
     * <p>所以这里递归地把每一层 Map 都转成 MemoryConfiguration，
     * 保证 {@code getConfigurationSection()} 与 {@code getList()} 在任意
     * 深度都能正常工作。
     */
    static ConfigurationSection toSection(Object o) {
        if (o instanceof ConfigurationSection cs) return cs;
        if (o instanceof Map<?, ?> map) {
            org.bukkit.configuration.MemoryConfiguration mem =
                    new org.bukkit.configuration.MemoryConfiguration();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                mem.set(String.valueOf(e.getKey()), deepConvert(e.getValue()));
            }
            return mem;
        }
        return null;
    }

    /**
     * 递归转换：Map → MemoryConfiguration；List → 逐元素转换的新 List；
     * 其余原样返回。
     */
    static Object deepConvert(Object v) {
        if (v instanceof Map<?, ?> m) {
            return toSection(m);
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(deepConvert(e));
            }
            return out;
        }
        return v;
    }
}
