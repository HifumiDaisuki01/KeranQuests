package com.keran.quests.util;

/**
 * 时间格式化工具。
 */
public final class TimeUtil {

    private TimeUtil() {
    }

    /**
     * 把秒数格式化为 "HH:mm:ss" 或 "mm:ss"。
     * 1 小时以内输出 mm:ss，超过则输出 HH:mm:ss。
     * 负数输出 "--:--"。
     */
    public static String format(int seconds) {
        if (seconds < 0) return "--:--";
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    /**
     * 把毫秒时间戳差值格式化为剩余时间。
     */
    public static String formatRemaining(long untilMillis) {
        long remain = untilMillis - System.currentTimeMillis();
        if (remain <= 0) return "--:--";
        return format((int) Math.ceil(remain / 1000.0));
    }

    /** 输出形如 "1天2小时3分" 的中文时长。 */
    public static String formatChinese(int seconds) {
        if (seconds <= 0) return "0秒";
        int d = seconds / 86400;
        int h = (seconds % 86400) / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("天");
        if (h > 0) sb.append(h).append("小时");
        if (m > 0) sb.append(m).append("分");
        if (s > 0 && d == 0) sb.append(s).append("秒");
        return sb.length() == 0 ? "0秒" : sb.toString();
    }

    /** 把毫秒时间戳格式化。 */
    public static String formatMillis(long millis) {
        return format((int) (millis / 1000));
    }
}
