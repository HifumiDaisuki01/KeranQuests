package com.keran.quests.hook;

import com.keran.quests.KeranQuests;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 第三方插件挂钩的基类。
 *
 * <h3>为什么用反射</h3>
 * Oraxen / MythicMobs / WorldGuard 等插件的 API 在不同版本间有差异，
 * 用反射调用可以把"编译期依赖"降到最低 —— 即使某个插件未安装或版本不符，
 * 本插件也能正常启动，只是对应功能降级。
 */
public abstract class Hook {

    protected final KeranQuests plugin;
    private final String pluginName;
    protected boolean available = false;

    protected Hook(KeranQuests plugin, String pluginName) {
        this.plugin = plugin;
        this.pluginName = pluginName;
    }

    /** 尝试挂钩。子类实现具体逻辑，失败不应抛异常。 */
    public abstract void hook();

    public boolean isAvailable() {
        return available;
    }

    public String getPluginName() {
        return pluginName;
    }

    protected boolean pluginPresent() {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null;
    }

    /** 反射调用静态方法。 */
    protected Object callStatic(String className, String methodName, Class<?>[] types, Object... args) {
        try {
            Class<?> cls = Class.forName(className);
            Method m = cls.getMethod(methodName, types);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射调用实例方法。 */
    protected Object call(Object target, String methodName, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName, types);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
