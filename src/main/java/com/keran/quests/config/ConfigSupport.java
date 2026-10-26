package com.keran.quests.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import java.util.Map;

public final class ConfigSupport {
    private ConfigSupport() {}
    public static ConfigurationSection section(Object raw) {
        if (raw instanceof ConfigurationSection sec) return sec;
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("应为配置对象：" + raw);
        MemoryConfiguration out = new MemoryConfiguration();
        fill(out, map);
        return out;
    }
    private static void fill(ConfigurationSection sec, Map<?, ?> map) {
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> nested) fill(sec.createSection(key), nested);
            else sec.set(key, entry.getValue());
        }
    }
    public static int seconds(Object value) {
        if (value instanceof Number n) return n.intValue();
        String text = String.valueOf(value).trim().toLowerCase();
        int multiplier = text.endsWith("h") ? 3600 : text.endsWith("m") ? 60 : 1;
        if (text.matches(".*[smh]$")) text = text.substring(0, text.length() - 1);
        return Math.multiplyExact(Integer.parseInt(text), multiplier);
    }
}
