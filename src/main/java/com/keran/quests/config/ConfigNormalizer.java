package com.keran.quests.config;

import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/** Expands concise v2 configuration into the original runtime model. No player files are rewritten. */
public final class ConfigNormalizer {
    private ConfigNormalizer() {}
    public static void tree(ConfigurationSection root) {
        ConfigurationSection quests = root.getConfigurationSection("quests");
        if (quests == null) throw new IllegalArgumentException("缺少 quests");
        String treeId = root.getString("id", "");
        ConfigurationSection defaults = root.getConfigurationSection("defaults");
        for (String id : quests.getKeys(false)) {
            ConfigurationSection q = quests.getConfigurationSection(id);
            if (q == null) throw new IllegalArgumentException("quests." + id + " 应为对象");
            if (defaults != null) for (String key : defaults.getKeys(true))
                if (!defaults.isConfigurationSection(key) && !q.contains(key)) q.set(key, defaults.get(key));
            if (q.contains("after")) {
                List<String> refs = strings(q.get("after"));
                q.set("prerequisites.quest_completed_all", refs.stream().map(x -> full(treeId, x)).toList());
            }
            normalizePrerequisites(treeId, q.getConfigurationSection("prerequisites"));
            if (q.contains("cooldown")) q.set("cooldown", ConfigSupport.seconds(q.get("cooldown")));
            if (!q.contains("stages") && q.contains("steps")) q.set("stages", q.get("steps"));
            List<?> raw = q.getList("stages");
            if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("quests." + id + ".stages 不能为空");
            List<Object> stages = new ArrayList<>();
            Set<String> stageIds = new HashSet<>();
            for (int i = 0; i < raw.size(); i++) {
                ConfigurationSection s = ConfigSupport.section(raw.get(i));
                if (!s.contains("id")) s.set("id", "stage_" + (i + 1));
                if (!stageIds.add(s.getString("id"))) throw new IllegalArgumentException(id + " 阶段 ID 重复：" + s.getString("id"));
                if (s.contains("text") && !s.contains("name")) s.set("name", s.get("text"));
                List<Object> requirements = new ArrayList<>(s.getList("requirements", List.of()));
                if (s.contains("enter")) requirements.add(region(s.get("enter"), "region_enter"));
                if (s.contains("stay")) {
                    ConfigurationSection stay = ConfigSupport.section(s.get("stay"));
                    Map<String, Object> req = region(stay.get("region"), "region_stay");
                    req.put("seconds", ConfigSupport.seconds(stay.get("time", "3s")));
                    requirements.add(req);
                }
                if (s.contains("talk")) requirements.add(Map.of("type", "npc_talk", "npc", s.getString("talk")));
                if (s.contains("trigger")) requirements.add(Map.of("type", "custom_trigger", "key", s.getString("trigger")));
                if (s.contains("wait")) requirements.add(Map.of("type", "wait", "seconds", ConfigSupport.seconds(s.get("wait"))));
                if (s.contains("submit")) {
                    ConfigurationSection item = ConfigSupport.section(s.get("submit"));
                    requirements.add(Map.of("type", "oraxen_item", "item", item.getString("item", "minecraft:paper"),
                            "count", item.getInt("count", 1), "consume", true));
                }
                List<Object> normalized = new ArrayList<>();
                for (Object item : requirements) {
                    ConfigurationSection req = ConfigSupport.section(item);
                    if ("item".equalsIgnoreCase(req.getString("type"))) req.set("type", "oraxen_item");
                    normalized.add(req.getValues(false));
                }
                s.set("requirements", normalized);
                if (s.isList("choices")) {
                    List<Object> choices = new ArrayList<>();
                    for (Object choice : s.getList("choices")) {
                        ConfigurationSection c = ConfigSupport.section(choice);
                        if (c.contains("quest")) c.set("quest", full(treeId, c.getString("quest")));
                        choices.add(c.getValues(false));
                    }
                    s.set("choices", choices);
                }
                stages.add(s.getValues(false));
            }
            q.set("stages", stages);
        }
    }
    private static Map<String, Object> region(Object value, String type) {
        String ref = String.valueOf(value);
        String[] parts = ref.split(":", 2);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("region", parts.length == 2 ? parts[1] : parts[0]);
        if (parts.length == 2) out.put("world", parts[0]);
        return out;
    }
    private static void normalizePrerequisites(String tree, ConfigurationSection pre) {
        if (pre == null) return;
        for (String key : List.of("quest_completed_all", "quest_completed_any")) {
            if (pre.isList(key)) pre.set(key, strings(pre.get(key)).stream().map(x -> full(tree, x)).toList());
        }
        if (pre.isList("quest_completed_any.quests")) pre.set("quest_completed_any.quests",
                strings(pre.get("quest_completed_any.quests")).stream().map(x -> full(tree, x)).toList());
    }
    public static String full(String tree, String id) { return id.contains(":") ? id : tree + ":" + id; }
    private static List<String> strings(Object raw) {
        if (raw instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return raw == null ? List.of() : List.of(String.valueOf(raw));
    }
}
