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
                // ⚠ 树 ID 冲突防护。
                //
                // trees 以 tree.getId() 为键，而 id 来自【文件内部】的 id: 字段，
                // 不是文件名。因此两份内容不同、但都写着 id: zhulong 的 yml
                // 会互相覆盖 —— 而且是【静默】覆盖，表现为「某棵树的配置怎么改都不生效」
                // 或「layout 报出不存在的任务」（其实报的是另一份文件的 layout）。
                //
                // 实测踩到过：把旧的教学模板复制成 zhulong-example.yml 时没改 id，
                // 结果正式树被覆盖，且日志里完全看不出原因。
                if (tree.getId() != null && trees.containsKey(tree.getId())) {
                    QuestTree old = trees.get(tree.getId());
                    plugin.getLogger().warning("任务树 ID 冲突：文件 " + f.getName()
                            + " 与 " + old.getSourceFile() + " 都声明了 id=" + tree.getId()
                            + "，后者已覆盖前者。请把其中一个文件的 id 改成不同值。");
                }
                trees.put(tree.getId(), tree);
                tree.setSourceFile(f.getName());

                // 文件名与 id 不一致 → 提醒。树 ID 取自文件内 id: 字段，
                // 与文件名无关；不一致时「改文件名」不会影响树 ID，容易造成误解，
                // 也是「复制文件忘了改 id」这类事故的温床。
                if (!fileId.equals(tree.getId())) {
                    plugin.getLogger().warning("任务树文件名与 id 不一致：" + f.getName()
                            + " 内写的是 id=" + tree.getId()
                            + "。树 ID 以文件内 id: 字段为准（此处生效的是 " + tree.getId() + "）。");
                }
                for (Quest q : tree.getQuests()) {
                    questIndex.put(q.getFullId(), q);
                }
                // 自定义 GUI 布局：提前解析一遍，把配置问题在启动时就报出来，
                // 免得玩家点进 GUI 才发现某格是空的。
                if (tree.hasLayout()) {
                    java.util.List<String> problems = new java.util.ArrayList<>();
                    QuestTree.LayoutResult lr = tree.parseLayout(problems);
                    for (String p : problems) {
                        plugin.getLogger().warning("[layout] " + p);
                    }
                    if (plugin.getConfig().getBoolean("debug.verbose", false)) {
                        plugin.getLogger().info("树 " + tree.getId() + " 使用自定义 GUI 布局："
                                + lr.slotToQuest.size() + " 个任务 + "
                                + lr.emptySlots.size() + " 个空格占位");
                    }
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
