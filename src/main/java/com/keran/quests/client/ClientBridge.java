package com.keran.quests.client;

import com.google.gson.*;
import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.*;
import com.keran.quests.config.model.enums.*;
import com.keran.quests.player.*;
import com.keran.quests.runtime.QuestManager;
import com.keran.quests.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned companion protocol. Clients request actions; the server decides every mutation. */
public final class ClientBridge implements PluginMessageListener {
    public static final String CHANNEL = "keranquests:ui";
    public static final int PROTOCOL = 1;
    private static final Gson GSON = new Gson();
    private final KeranQuests plugin;
    private final Map<UUID, String> clients = new HashMap<>();
    private final Set<UUID> open = new HashSet<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Map<UUID, String> lastSnapshot = new HashMap<>();
    private BukkitTask task;
    public ClientBridge(KeranQuests plugin) { this.plugin = plugin; }
    public void start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : new ArrayList<>(clients.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !plugin.getDialogueRunner().isTalking(id)) snapshot(p, false, null, false);
            }
        }, 20L, 20L);
    }
    public void stop() {
        if (task != null) task.cancel();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        clients.clear(); open.clear(); lastSnapshot.clear(); lastAction.clear();
    }
    public boolean hasClient(Player p) {
        return clients.containsKey(p.getUniqueId()) && !"legacy".equalsIgnoreCase(plugin.getConfig().getString("ui.mode", "auto"));
    }
    public boolean open(Player p) { return open(p, null); }
    public boolean open(Player p, String focus) {
        if (!hasClient(p)) {
            if (plugin.getConfig().getBoolean("ui.require_client", false)) {
                Text.send(p, "&e请安装 KeranQuests Client 1.20.1 模组后打开任务界面。"); return true;
            }
            return false;
        }
        open.add(p.getUniqueId()); snapshot(p, true, focus, true); return true;
    }
    public void forget(UUID id) { clients.remove(id); open.remove(id); lastAction.remove(id); lastSnapshot.remove(id); }
    public void refreshAll() { lastSnapshot.clear(); }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!CHANNEL.equals(channel) || bytes.length == 0 || bytes.length > 4096 || !player.hasPermission("kq.use")) return;
        try {
            JsonObject msg = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String op = text(msg, "op");
            if ("hello".equals(op)) {
                long at = System.currentTimeMillis();
                if (at - lastAction.getOrDefault(player.getUniqueId(), 0L) < 1000) return;
                lastAction.put(player.getUniqueId(), at);
                if (msg.get("protocol").getAsInt() != PROTOCOL) { send(player, Map.of("op", "error", "text", "客户端协议不匹配，请更新模组。")); return; }
                clients.put(player.getUniqueId(), UUID.randomUUID().toString());
                send(player, Map.of("op", "ready", "protocol", PROTOCOL, "token", clients.get(player.getUniqueId())));
                snapshot(player, true, null, false);
                return;
            }
            if (!hasClient(player) || !Objects.equals(clients.get(player.getUniqueId()), text(msg, "token"))) return;
            if ("close".equals(op)) { open.remove(player.getUniqueId()); return; }
            long now = System.currentTimeMillis();
            if (now - lastAction.getOrDefault(player.getUniqueId(), 0L) < 100L) return;
            lastAction.put(player.getUniqueId(), now);
            if ("open".equals(op)) { open(player); return; }
            if (op.startsWith("dialog")) {
                if (!Objects.equals(plugin.getDialogueRunner().sessionToken(player), text(msg, "session"))) return;
                String error = null;
                if ("dialogEnd".equals(op)) plugin.getDialogueRunner().interrupt(player, false);
                else if ("dialogPick".equals(op)) error = plugin.getDialogueRunner().onPick(player, text(msg, "dialog"), integer(msg, "node"), integer(msg, "index"));
                else if ("dialogChoose".equals(op)) error = plugin.getDialogueRunner().onChoose(player, text(msg, "dialog"), integer(msg, "node"), integer(msg, "index"));
                if (error != null) send(player, Map.of("op", "error", "text", Text.strip(Text.color(error))));
                return;
            }
            Quest quest = plugin.getTreeLoader().resolveQuest(text(msg, "quest"));
            if (quest == null) return;
            PlayerData data = plugin.getPlayerData(player);
            QuestProgress progress = data.getProgress(quest.getFullId());
            String error = null;
            switch (op) {
                case "accept" -> error = plugin.getQuestManager().getUnlockStatus(player, quest) == QuestManager.UnlockStatus.LOCKED_HIDDEN
                        ? "该任务尚未解锁。" : plugin.getQuestManager().accept(player, quest);
                case "abandon" -> error = plugin.getQuestManager().abandon(player, quest);
                case "track" -> {
                    if (progress == null || progress.getState() != QuestState.ACTIVE) return;
                    data.setTrackedQuest(quest.getFullId()); plugin.getPlayerDataStore().save(data);
                }
                case "choose" -> {
                    if (progress == null || progress.getState() != QuestState.ACTIVE || progress.getStageIndex() >= quest.getStageCount()) return;
                    QuestStage stage = quest.getStages().get(progress.getStageIndex());
                    if (!stage.getId().equals(text(msg, "stage"))) return;
                    error = plugin.getChoiceManager().choose(player, quest, stage, integer(msg, "index"));
                }
                default -> { return; }
            }
            if (error != null) send(player, Map.of("op", "error", "text", Text.strip(Text.color(error))));
            snapshot(player, true, quest.getFullId(), false);
        } catch (RuntimeException ex) {
            // Malformed/untrusted client packets never reach command dispatch or configuration loaders.
            plugin.getLogger().fine("忽略无效 UI 请求：" + ex.getClass().getSimpleName());
        }
    }
    private void snapshot(Player p, boolean force, String focus, boolean show) {
        List<Map<String, Object>> packets = new ArrayList<>();
        PlayerData data = plugin.getPlayerData(p);
        for (QuestTree tree : plugin.getTreeLoader().getTrees()) {
            if (!tree.isEnabled()) continue;
            packets.add(packet("tree", "id", tree.getId(), "name", plain(tree.getName()), "description", plain(tree.getDescription())));
            for (Quest q : tree.getQuestsByWeight()) {
                QuestProgress progress = data.getProgress(q.getFullId());
                QuestManager.UnlockStatus unlock = plugin.getQuestManager().getUnlockStatus(p, q);
                boolean hidden = unlock == QuestManager.UnlockStatus.LOCKED_HIDDEN;
                QuestState state = progress == null ? QuestState.LOCKED : progress.getState();
                String error = plugin.getQuestManager().canAccept(p, q);
                List<String> parents = new ArrayList<>(q.getPrerequisites().getQuestCompletedAll());
                parents.addAll(q.getPrerequisites().getQuestCompletedAny());
                List<String> rewards = new ArrayList<>();
                if (!hidden) {
                    if (q.getMoney() > 0) rewards.add("金币 " + q.getMoney());
                    if (q.getExp() > 0) rewards.add("经验 " + q.getExp());
                    for (Quest.RewardItem item : q.getRewardItems()) rewards.add(item.getRawId() + " ×" + item.getAmount());
                    rewards.addAll(q.getRewardDisplay());
                    if (rewards.isEmpty() && !q.getRewardCommands().isEmpty()) rewards.add("完成后发放任务奖励");
                }
                int stageIndex = progress == null ? 0 : progress.getStageIndex();
                List<Map<String, Object>> choices = new ArrayList<>();
                String choiceStage = "";
                if (!hidden && state == QuestState.ACTIVE && stageIndex < q.getStageCount()) {
                    QuestStage stage = q.getStages().get(stageIndex);
                    if (stage.isChoice() && progress.isStageHandled(stageIndex)) {
                        choiceStage = stage.getId();
                        for (int i = 0; i < stage.getChoices().size(); i++) choices.add(packet("option", "index", i,
                                "label", plain(stage.getChoices().get(i).getLabel()), "description", plain(stage.getChoices().get(i).getDescription())));
                    }
                }
                packets.add(packet("quest", "id", q.getFullId(), "tree", tree.getId(), "name", hidden ? "？？？" : plain(q.getName()),
                        "description", hidden ? "完成前置剧情以解锁" : plain(q.getDescription()), "state", error == null && !hidden ? "AVAILABLE" : unlock == QuestManager.UnlockStatus.EXCLUDED ? "EXCLUDED" : state.name(),
                        "type", q.getType().name(), "hidden", hidden, "parents", parents, "stageIndex", stageIndex, "stageTotal", hidden ? 0 : q.getStageCount(),
                        "canAccept", error == null && !hidden, "canAbandon", state == QuestState.ACTIVE && q.isAbandonAllowed(), "tracked", q.getFullId().equals(data.getTrackedQuest()),
                        "error", hidden ? "尚未解锁" : plain(error), "rewards", rewards, "choices", choices, "choiceStage", choiceStage));
                if (hidden) continue;
                for (int i = 0; i < q.getStageCount(); i++) {
                    if (q.getStageVisibility() == StageVisibility.HIDDEN && i != Math.min(stageIndex, q.getStageCount()-1)) continue;
                    if (q.getStageVisibility() == StageVisibility.SEQUENTIAL && i > stageIndex) continue;
                    QuestStage stage = q.getStages().get(i);
                    List<String> requirements = new ArrayList<>();
                    for (int ri = 0; ri < stage.getRequirements().size(); ri++) {
                        Requirement req = stage.getRequirements().get(ri);
                        int count = progress == null ? 0 : progress.getRequirementProgress(i, ri);
                        if (req.getType() == RequirementType.ORAXEN_ITEM && i == stageIndex && state == QuestState.ACTIVE)
                            count = plugin.getOraxenHook().countItem(p, req.getItem());
                        if (req.getType() == RequirementType.WAIT && progress != null && i == stageIndex)
                            count = (int) ((System.currentTimeMillis() - progress.getStageStartedAt()) / 1000L);
                        requirements.add(plain(req.describe()) + "  " + Math.min(count, req.target()) + "/" + req.target());
                    }
                    packets.add(packet("stage", "quest", q.getFullId(), "id", stage.getId(), "name", plain(stage.getName()),
                            "description", plain(stage.getDescription()), "done", progress != null && progress.isStageCompleted(i),
                            "active", state == QuestState.ACTIVE && i == stageIndex, "requirements", requirements));
                }
            }
        }
        String digest = GSON.toJson(packets);
        if (!force && digest.equals(lastSnapshot.get(p.getUniqueId()))) return;
        lastSnapshot.put(p.getUniqueId(), digest);
        send(p, Map.of("op", "begin"));
        for (Map<String, Object> packet : packets) send(p, packet);
        send(p, packet("done", "open", show, "focus", focus == null ? "" : focus));
    }
    public boolean dialogue(Player player, String dialog, String session, int node, String text, List<Map<String, Object>> buttons, String mode) {
        if (!hasClient(player)) return false;
        send(player, packet("dialog", "dialog", dialog, "session", session, "node", node, "text", plain(text), "buttons", buttons, "mode", mode));
        return true;
    }
    public void endDialogue(Player player) { if (hasClient(player)) send(player, Map.of("op", "dialogEnd")); }
    public static Map<String, Object> packet(String op, Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>(); map.put("op", op);
        for (int i = 0; i < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return map;
    }
    private void send(Player player, Map<String, ?> packet) {
        byte[] bytes = GSON.toJson(packet).getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 32700) player.sendPluginMessage(plugin, CHANNEL, bytes);
    }
    private static String plain(String s) { if (s == null) return ""; String out = Text.strip(Text.color(s)); return out.length() > 1024 ? out.substring(0, 1024) : out; }
    private static String text(JsonObject json, String key) { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : ""; }
    private static int integer(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsInt() : -1; }
}
