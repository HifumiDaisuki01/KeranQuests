package com.keran.quests.runtime;

import com.keran.quests.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 聊天栏可点击文本构建器 —— 对话系统的交互基石。
 *
 * <h3>原理</h3>
 * 服务端把一条消息拆成若干 {@link Component}，每个组件挂一个
 * {@link ClickEvent#runCommand(String)}。玩家鼠标点上去，客户端就替玩家
 * 执行那条命令 —— 于是「点文字」= 「执行一条隐藏命令」。
 *
 * <p>不需要开任何 GUI 窗口，纯聊天栏交互。实测 Paper 1.20.1 原生支持。
 *
 * <h3>为什么用 runCommand 而不是 suggestCommand</h3>
 * {@code suggestCommand} 会把命令填进输入框，玩家还得自己按回车。
 * {@code runCommand} 点了直接执行，才是一步到位的「点击」。
 *
 * <p><b>安全</b>：命令由本类统一拼装，玩家无法伪造。回调路径统一走
 * {@code /kqdlg <动作> <参数...>}，且该命令在 plugin.yml 里设为不可从
 * 聊天框直接调用（见 {@code KqdlgCommand}），避免玩家手打绕过对话状态机。
 */
public final class DialogText {

    private DialogText() {
    }

    /** 回调命令根节点。 */
    public static final String CMD = "/kqdlg";

    /**
     * 构建一个可点击的按钮。
     *
     * @param label    按钮上显示的文字（支持 &amp; 颜色码）
     * @param command  要执行的命令（不含前导斜杠也可）
     * @param hover    悬停说明（支持 &amp; 颜色码与 \n 换行），可为 null
     * @return 可点击组件
     */
    public static Component button(String label, String command, String hover) {
        Component c = legacy(label);
        String cmd = command == null ? "" : command.trim();
        if (!cmd.startsWith("/")) cmd = "/" + cmd;
        c = c.clickEvent(ClickEvent.runCommand(cmd));
        if (hover != null && !hover.isBlank()) {
            c = c.hoverEvent(HoverEvent.showText(legacy(hover.replace("\\n", "\n"))));
        }
        return c;
    }

    /**
     * 构建一行「可点击按钮组」。
     *
     * @param buttons     按钮定义（label / command / hover / 是否可用）
     * @param disabledTip 不可用时显示的悬停提示
     */
    public static Component buttonRow(java.util.List<Btn> buttons, String disabledTip) {
        Component row = Component.empty();
        for (Btn b : buttons) {
            if (!row.equals(Component.empty())) {
                row = row.append(legacy("  "));
            }
            if (b.enabled) {
                row = row.append(button(b.label, b.command, b.hover));
            } else {
                // 灰掉：不可点击，悬停说明原因
                Component d = legacy(b.label).color(NamedTextColor.DARK_GRAY);
                if (disabledTip != null && !disabledTip.isBlank()) {
                    d = d.hoverEvent(HoverEvent.showText(
                            legacy("&8" + disabledTip.replace("\\n", "\n"))));
                }
                row = row.append(d);
            }
        }
        return row;
    }

    /** 一行普通文字（不可点击）。 */
    public static Component line(String text) {
        return legacy(text);
    }

    /**
     * 把含 &amp; 颜色码的字符串转成 Component。
     *
     * <p>实现说明：{@link Text#color(String)} 已经把 {@code &a} 转成了
     * {@code §a}，所以这里必须用 {@code legacySection()}（认 {@code §}）
     * 而不是 {@code legacyAmpersand()}（认 {@code &}）。若误用后者，
     * {@code §} 会被当成普通字符原样显示，玩家看到一堆乱码。
     */
    public static Component legacy(String s) {
        if (s == null) return Component.empty();
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection()
                .deserialize(Text.color(s));
    }

    /** 按钮定义。 */
    public static final class Btn {
        final String label;
        final String command;
        final String hover;
        final boolean enabled;

        public Btn(String label, String command, String hover) {
            this(label, command, hover, true);
        }

        public Btn(String label, String command, String hover, boolean enabled) {
            this.label = label;
            this.command = command;
            this.hover = hover;
            this.enabled = enabled;
        }

        public static Btn of(String label, String command, String hover) {
            return new Btn(label, command, hover);
        }
    }
}
