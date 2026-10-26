package com.keran.quests.config;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

/** Parse and validate a candidate registry before atomically replacing the live one. */
public class QuestTreeLoader {
    private final KeranQuests plugin;
    private Map<String, QuestTree> trees = new LinkedHashMap<>();
    private Map<String, Quest> questIndex = new LinkedHashMap<>();
    public QuestTreeLoader(KeranQuests plugin) { this.plugin = plugin; }
    public record Prepared(Map<String, QuestTree> trees, Map<String, Quest> quests) {}

    public Prepared prepare() {
        File dir = new File(plugin.getDataFolder(), "quests");
        if (!dir.exists()) {
            dir.mkdirs();
            plugin.saveResource("quests/tutorial.yml", false);
            plugin.saveResource("quests/zhulong.yml", false);
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) throw new IllegalArgumentException("无法读取 quests/ 目录");
        Arrays.sort(files, Comparator.comparing(File::getName));
        Map<String, QuestTree> nextTrees = new LinkedHashMap<>();
        Map<String, Quest> nextQuests = new LinkedHashMap<>();
        for (File f : files) {
            try {
                YamlConfiguration yml = new YamlConfiguration();
                yml.load(f);
                if (!yml.getBoolean("enabled", true)) continue;
                String fileId = f.getName().substring(0, f.getName().length() - 4);
                if (!yml.contains("id")) yml.set("id", fileId);
                ConfigNormalizer.tree(yml);
                QuestTree tree = QuestTree.fromConfig(fileId, yml);
                if (!tree.getId().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("非法树 ID：" + tree.getId());
                if (nextTrees.putIfAbsent(tree.getId(), tree) != null) throw new IllegalArgumentException("树 ID 重复：" + tree.getId());
                tree.setSourceFile(f.getName());
                for (Quest q : tree.getQuests()) {
                    if (!q.getId().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("非法任务 ID：" + q.getId());
                    for (QuestStage stage : q.getStages()) {
                        if (stage.getMode() == com.keran.quests.config.model.enums.StageMode.ANY
                                && stage.getNeed() > stage.getRequirements().size())
                            throw new IllegalArgumentException(q.getFullId() + "/" + stage.getId() + " 的 need 超过要求数量");
                        if (stage.isChoice() && stage.getChoices().isEmpty())
                            throw new IllegalArgumentException(q.getFullId() + "/" + stage.getId() + " 抉择没有选项");
                    }
                    nextQuests.put(q.getFullId(), q);
                }
            } catch (Exception ex) {
                throw new IllegalArgumentException(f.getName() + "：" + ex.getMessage(), ex);
            }
        }
        List<QuestTree> sorted = new ArrayList<>(nextTrees.values());
        sorted.sort(Comparator.comparingInt(QuestTree::getOrder));
        nextTrees.clear();
        for (QuestTree tree : sorted) nextTrees.put(tree.getId(), tree);
        validate(nextTrees, nextQuests);
        return new Prepared(nextTrees, nextQuests);
    }

    public void commit(Prepared prepared) { trees = prepared.trees(); questIndex = prepared.quests(); }
    public int load() { commit(prepare()); return trees.size(); }

    private static void validate(Map<String, QuestTree> trees, Map<String, Quest> quests) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (QuestTree tree : trees.values()) validatePre(tree.getPrerequisites(), tree.getId(), trees, quests);
        for (Quest q : quests.values()) {
            validatePre(q.getPrerequisites(), q.getTreeId(), trees, quests);
            if (q.getUnlockTree() != null && !trees.containsKey(q.getUnlockTree()))
                throw new IllegalArgumentException(q.getFullId() + " 引用了不存在的 unlock_tree：" + q.getUnlockTree());
            for (QuestStage stage : q.getStages()) for (QuestStage.Choice choice : stage.getChoices())
                requireQuest(choice.getQuest(), q.getTreeId(), quests);
            List<String> edges = new ArrayList<>();
            if (!"ANY".equalsIgnoreCase(q.getPrerequisites().getMode())) {
                for (String ref : q.getPrerequisites().getQuestCompletedAll()) edges.add(requireQuest(ref, q.getTreeId(), quests));
            }
            dependencies.put(q.getFullId(), edges);
        }
        Set<String> done = new HashSet<>();
        for (String id : dependencies.keySet()) visit(id, dependencies, new LinkedHashSet<>(), done);
    }
    private static void visit(String id, Map<String, List<String>> edges, Set<String> path, Set<String> done) {
        if (done.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("任务前置循环：" + path + " -> " + id);
        for (String parent : edges.getOrDefault(id, List.of())) visit(parent, edges, path, done);
        path.remove(id); done.add(id);
    }
    private static void validatePre(Prerequisite pre, String tree, Map<String, QuestTree> trees, Map<String, Quest> quests) {
        for (String ref : pre.getQuestCompletedAll()) requireQuest(ref, tree, quests);
        for (String ref : pre.getQuestCompletedAny()) requireQuest(ref, tree, quests);
        List<String> refs = new ArrayList<>(pre.getTreeCompletedAll()); refs.addAll(pre.getTreeCompletedAny());
        for (String id : refs) if (!trees.containsKey(id)) throw new IllegalArgumentException("不存在的前置任务树：" + id);
    }
    private static String requireQuest(String ref, String tree, Map<String, Quest> quests) {
        String full = ConfigNormalizer.full(tree, ref);
        if (!quests.containsKey(full)) throw new IllegalArgumentException("不存在的任务引用：" + full);
        return full;
    }
    public Quest resolveQuest(String ref) {
        if (ref == null || ref.isBlank()) return null;
        if (ref.contains(":")) return questIndex.get(ref);
        Quest found = null;
        for (Quest q : questIndex.values()) if (q.getId().equalsIgnoreCase(ref)) {
            if (found != null) return null; // Ambiguous references are never guessed.
            found = q;
        }
        return found;
    }
    public QuestTree getTree(String id) { return trees.get(id); }
    public Collection<QuestTree> getTrees() { return Collections.unmodifiableCollection(trees.values()); }
    public Collection<Quest> getAllQuests() { return Collections.unmodifiableCollection(questIndex.values()); }
    public int getTreeCount() { return trees.size(); }
    public int getQuestCount() { return questIndex.size(); }
    public int getStageCount() { return questIndex.values().stream().mapToInt(Quest::getStageCount).sum(); }
    public boolean isEmpty() { return trees.isEmpty(); }
}
