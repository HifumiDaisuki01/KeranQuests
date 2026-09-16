package com.keran.quests.config.model;

import com.keran.quests.config.model.enums.StageMode;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务阶段。不可变模型。
 */
public class QuestStage {

    private final String id;
    private final String name;
    private final StageMode mode;
    /** mode=ANY 时，需满足的条数 */
    private final int need;
    private final List<Requirement> requirements;
    private final List<String> commandsOnComplete;
    /** 完成后是否为「抉择」节点 */
    private final boolean choice;
    /** 抉择组 id */
    private final String choiceGroup;
    private final String choiceTitle;
    private final List<Choice> choices;
    /**
     * 阶段简介（可选）。支持多行、支持 &amp; 颜色代码。
     *
     * <p>用于在阶段格子的 lore 里补一段叙事 / 说明文字，
     * 例如"你已经来到了营地，一个熟悉的身影出现了……"。
     */
    private final String description;

    private QuestStage(String id, String name, StageMode mode, int need,
                       List<Requirement> requirements, List<String> commandsOnComplete,
                       boolean choice, String choiceGroup, String choiceTitle, List<Choice> choices,
                       String description) {
        this.id = id;
        this.name = name;
        this.mode = mode;
        this.need = need;
        this.description = description == null ? "" : description;
        this.requirements = requirements == null ? new ArrayList<>() : requirements;
        this.commandsOnComplete = commandsOnComplete == null ? new ArrayList<>() : commandsOnComplete;
        this.choice = choice;
        this.choiceGroup = choiceGroup;
        this.choiceTitle = choiceTitle;
        this.choices = choices == null ? new ArrayList<>() : choices;
    }

    public static QuestStage fromConfig(ConfigurationSection sec) {
        if (sec == null) return null;
        String id = sec.getString("id");
        if (id == null || id.isBlank()) return null;
        String name = sec.getString("name", id);
        StageMode mode = StageMode.parse(sec.getString("mode", "ALL"));
        int need = sec.getInt("need", 0);

        List<Requirement> reqs = new ArrayList<>();
        List<?> rawList = sec.getList("requirements");
        if (rawList != null) {
            for (Object obj : rawList) {
                ConfigurationSection rs = toSection(obj);
                Requirement r = Requirement.fromConfig(rs);
                if (r != null) reqs.add(r);
            }
        }

        List<String> cmds = new ArrayList<>(sec.getStringList("commands_on_complete"));

        // 抉择节点：三种等价写法，任一命中即可
        //   1) on_complete: CHOICE     （早期写法）
        //   2) choice: true            （示例 yml 与文档使用）
        //   3) 配了 choices 列表         （最直观，隐含表示这是抉择节点）
        String onComplete = sec.getString("on_complete", "");
        List<Choice> choices = new ArrayList<>();
        List<?> rawChoices = sec.getList("choices");
        if (rawChoices != null) {
            for (Object obj : rawChoices) {
                ConfigurationSection cs = toSection(obj);
                if (cs == null) continue;
                String quest = cs.getString("quest");
                if (quest == null) continue;
                choices.add(new Choice(
                        quest,
                        cs.getString("label", quest),
                        cs.getString("description", ""),
                        cs.getString("icon", null)
                ));
            }
        }
        boolean choice = "CHOICE".equalsIgnoreCase(onComplete)
                || sec.getBoolean("choice", false)
                || !choices.isEmpty();
        String choiceGroup = sec.getString("choice_group", null);
        String choiceTitle = sec.getString("choice_title", "抉择");
        // 阶段简介（可选）
        String description = sec.getString("description", "");

        return new QuestStage(id, name, mode, need, reqs, cmds, choice, choiceGroup, choiceTitle, choices,
                description);
    }

    /** 把 yml 里 list 内的 Map 元素转成 ConfigurationSection。 */
    private static ConfigurationSection toSection(Object obj) {
        if (obj instanceof ConfigurationSection cs) return cs;
        if (obj instanceof java.util.Map<?, ?> map) {
            org.bukkit.configuration.MemoryConfiguration mem =
                    new org.bukkit.configuration.MemoryConfiguration();
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                mem.set(String.valueOf(e.getKey()), e.getValue());
            }
            return mem;
        }
        return null;
    }

    // ---------------- getters ----------------

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public StageMode getMode() {
        return mode;
    }

    public int getNeed() {
        if (mode == StageMode.ANY && need <= 0) return 1;
        return need;
    }

    public List<Requirement> getRequirements() {
        return requirements;
    }

    public List<String> getCommandsOnComplete() {
        return commandsOnComplete;
    }

    public boolean isChoice() {
        return choice;
    }

    public String getChoiceGroup() {
        return choiceGroup;
    }

    public String getChoiceTitle() {
        return choiceTitle;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    /** 阶段简介（可选，可能为空串）。 */
    public String getDescription() {
        return description;
    }

    /** 抉择项。 */
    public static class Choice {
        private final String quest;      // 完整 ID，如 "zhulong:ch03a"
        private final String label;
        private final String description;
        private final String icon;

        public Choice(String quest, String label, String description, String icon) {
            this.quest = quest;
            this.label = label;
            this.description = description;
            this.icon = icon;
        }

        public String getQuest() {
            return quest;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }

        public String getIcon() {
            return icon;
        }

        /** 取 "zhulong:ch03a" 里的任务部分。 */
        public String getQuestIdOnly() {
            int i = quest.indexOf(':');
            return i < 0 ? quest : quest.substring(i + 1);
        }

        /** 取 "zhulong:ch03a" 里的树部分。 */
        public String getTreeId() {
            int i = quest.indexOf(':');
            return i < 0 ? "" : quest.substring(0, i);
        }
    }
}
