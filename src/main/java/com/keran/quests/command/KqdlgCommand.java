package com.keran.quests.command;

import com.keran.quests.KeranQuests;
import com.keran.quests.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 对话回调命令 —— 处理聊天栏里「点击文字」的动作。
 *
 * <h3>设计要点</h3>
 * <ol>
 *   <li><b>只能由玩家执行</b>：控制台调用直接拒绝。</li>
 *   <li><b>不校验参数合法性，只校验会话状态</b>：真正的权限来自
 *       {@link com.keran.quests.runtime.DialogueRunner} 的会话校验 ——
 *       只有当玩家确实处在那个对话的那个节点时，回调才会生效。
 *       因此玩家即使手打这条命令，也无法绕过对话状态机。</li>
 *   <li><b>静默失败</b>：任何异常都吞掉并给玩家一句提示，绝不让命令报错
 *       刷屏（点击事件触发的命令报错会在聊天栏显示红色报错，很丑）。</li>
 * </ol>
 *
 * <h3>用法</h3>
 * <pre>
 * /kqdlg pick   &lt;对话id&gt; &lt;节点序号&gt; &lt;问题序号&gt;    # 点击提问
 * /kqdlg choose &lt;对话id&gt; &lt;节点序号&gt; &lt;选项序号&gt;    # 点击抉择
 * /kqdlg end    &lt;对话id&gt;                            # 放弃对话
 * </pre>
 * 这些参数由 {@link com.keran.quests.runtime.DialogText} 在构建可点击文本时
 * 自动拼装，玩家看不到也不需要知道。
 */
public class KqdlgCommand implements CommandExecutor {

    private final KeranQuests plugin;

    public KqdlgCommand(KeranQuests plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 只允许玩家（点击事件必然来自玩家）
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (args.length < 2) return true;

        String action = args[0].toLowerCase();
        try {
            switch (action) {
                case "pick" -> {
                    if (args.length < 4) return true;
                    String err = plugin.getDialogueRunner().onPick(player, args[1],
                            parseInt(args[2], -1), parseInt(args[3], -1));
                    if (err != null) Text.send(player, err);
                }
                case "choose" -> {
                    if (args.length < 4) return true;
                    String err = plugin.getDialogueRunner().onChoose(player, args[1],
                            parseInt(args[2], -1), parseInt(args[3], -1));
                    if (err != null) Text.send(player, err);
                }
                case "end" -> plugin.getDialogueRunner().onQuit(player, args[1]);
                default -> {
                    // 未知动作：静默忽略
                }
            }
        } catch (Throwable t) {
            // 点击触发的命令报错会在聊天栏刷红色错误，这里统一吞掉
            plugin.getLogger().warning("对话回调异常：" + t.getMessage());
        }
        return true;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
