package com.keran.quests.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * 文本处理工具：颜色码转换 + 占位符替换。
 */
public final class Text {

    private Text() {
    }

    /** 传统颜色码（&a）转 §。 */
    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 去掉全部颜色码，用于字符串比较。 */
    public static String strip(String s) {
        return s == null ? "" : ChatColor.stripColor(color(s));
    }

    /**
     * 按键值对替换 {key} 占位符。
     * 参数顺序为 key1, value1, key2, value2, ...
     */
    public static String replace(String raw, Object... pairs) {
        if (raw == null) return "";
        String out = raw;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String k = String.valueOf(pairs[i]);
            String v = String.valueOf(pairs[i + 1]);
            out = out.replace("{" + k + "}", v == null ? "" : v);
        }
        return out;
    }

    /** 发消息（带 & 颜色转换）。 */
    public static void send(CommandSender to, String msg) {
        if (to == null || msg == null || msg.isEmpty()) return;
        to.sendMessage(color(msg));
    }

    /** 发送带前缀的消息，prefix 从 config 取。 */
    public static void sendPrefixed(CommandSender to, String prefix, String msg) {
        if (to == null || msg == null || msg.isEmpty()) return;
        to.sendMessage(color(prefix + msg));
    }

    /** 发送已经含颜色码的原始消息（不再转换）。 */
    public static void sendRaw(CommandSender to, String msg) {
        if (to == null || msg == null || msg.isEmpty()) return;
        to.sendMessage(color(msg));
    }
}
