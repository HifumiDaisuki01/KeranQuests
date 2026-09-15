package com.keran.quests.config;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.Quest;
import com.keran.quests.config.model.QuestTree;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 quests/ 目录下的全部任务树。
 */
public class QuestTreeLoader {

    private final KeranQuests plugin;
    /** 保序：treeId -> QuestTree */
    private final Map<String, QuestTree> trees = new LinkedHashMap<>();
    /** 全局任务索引：fullId("tree:quest") -> Quest */
    private final Map<String, Quest> questIndex = new LinkedHashMap<>();

    public QuestTreeLoader(KeranQuests plugin) {
        this.plugin = plugin;
    }

    /** 加载（或重载）全部任务树。返回成功加载的树数量。 */
    public int load() {
        trees.clear();
        questIndex.clear();

        File dir = new File(plugin.getDataFolder(), "quests");
        if (!dir.exists()) {
            // 首次运行：把 jar 内自带的示例任务树释放出来
            dir.mkdirs();
            plugin.saveResource("quests/zhulong.yml", false);
            plugin.saveResource("quests/tutorial.yml", false);
        }

        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("quests/ 目录下没有任何 .yml 文件。");
            return 0;
        }

        for (File f : files) {
            String fileId = f.getName().substring(0, f.getName().length() - 4);
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                QuestTree tree = QuestTree.fromConfig(fileId, yml);
                if (tree == null) {
                    plugin.getLogger().warning("任务树解析失败（返回 null）：" + f.getName());
                    continue;
                }
                trees.put(tree.getId(), tree);
                for (Quest q : tree.getQuests()) {
                    questIndex.put(q.getFullId(), q);
                }
                if (plugin.getConfig().getBoolean("debug.verbose", false)) {
                    plugin.getLogger().info("已加载任务树 " + tree.getId()
                            + "（" + tree.getQuests().size() + " 个任务）：" + f.getName());
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("加载任务树失败 " + f.getName() + "：" + t.getMessage());
            }
        }

        // 按 order 排序
        List<QuestTree> sorted = new ArrayList<>(trees.values());
        sorted.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        trees.clear();
        for (QuestTree t : sorted) trees.put(t.getId(), t);

        return trees.size();
    }

    /**
     * 解析任务引用。支持三种写法：
     * <ul>
     *   <li>{@code "zhulong:ch01"}（完整 ID）</li>
     *   <li>{@code "ch01"}（树内唯一时可用）</li>
     * </ul>
     *
     * @return 找不到返回 null
     */
    public Quest resolveQuest(String ref) {
        if (ref == null || ref.isBlank()) return null;
        if (ref.contains(":")) {
            return questIndex.get(ref);
        }
        // 短名：全局搜索唯一匹配
        Quest found = null;
        for (Quest q : questIndex.values()) {
            if (q.getId().equalsIgnoreCase(ref)) {
                if (found != null) {
                    // 歧义：优先取权重高的，但不报错
                    return found.getWeight() >= q.getWeight() ? found : q;
                }
                found = q;
            }
        }
        return found;
    }

    public QuestTree getTree(String treeId) {
        return trees.get(treeId);
    }

    public Collection<QuestTree> getTrees() {
        return trees.values();
    }

    public Collection<Quest> getAllQuests() {
        return questIndex.values();
    }

    public int getTreeCount() {
        return trees.size();
    }

    public int getQuestCount() {
        return questIndex.size();
    }

    /** 全部阶段数（供 selftest 显示）。 */
    public int getStageCount() {
        int n = 0;
        for (Quest q : questIndex.values()) n += q.getStageCount();
        return n;
    }

    public boolean isEmpty() {
        return trees.isEmpty();
    }
}
