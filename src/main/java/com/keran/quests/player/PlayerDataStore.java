package com.keran.quests.player;

import com.keran.quests.KeranQuests;
import com.keran.quests.config.model.enums.QuestState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家档案持久化。
 *
 * <p>存储方式：每个玩家一个 YAML 文件，位于 {@code plugins/KeranQuests/data/<uuid>.yml}。
 *
 * <h3>为什么不用 SQLite</h3>
 * 服务端虽自带 sqlite-jdbc，但用 YAML 可以不依赖任何驱动、便于人工排查与手工修复。
 * 本插件的数据量（每人几十个任务）远达不到需要 SQL 的程度。
 * 若将来玩家量大，再替换本类为 SQL 实现即可（接口保持不变）。
 *
 * <h3>保存策略</h3>
 * 内存缓存 + 每 60 秒批量 flush 脏档案 + 玩家退出/状态变更时立即写。
 */
public class PlayerDataStore {

    private final KeranQuests plugin;
    private final File dataDir;
    /** uuid -> 内存档案 */
    private final Map<UUID, PlayerData> cache = new HashMap<>();

    public PlayerDataStore(KeranQuests plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            plugin.getLogger().warning("无法创建数据目录：" + dataDir.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------
    //  加载 / 卸载
    // ------------------------------------------------------------------

    /** 载入玩家档案（优先命中缓存）。 */
    public PlayerData load(UUID uuid, String name) {
        PlayerData cached = cache.get(uuid);
        if (cached != null) return cached;

        PlayerData data = new PlayerData(uuid, name);
        File f = fileOf(uuid);
        if (f.exists()) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                deserialize(data, yml);
            } catch (Throwable t) {
                plugin.getLogger().warning("读取玩家档案失败 " + uuid + "：" + t.getMessage());
            }
        }
        cache.put(uuid, data);
        return data;
    }

    /** 玩家退出：写盘并移出缓存。 */
    public void unload(UUID uuid) {
        PlayerData data = cache.remove(uuid);
        if (data != null) {
            save(data);
        }
    }

    public PlayerData get(UUID uuid) {
        return cache.get(uuid);
    }

    /** 把脏档案全部写盘。 */
    public void flushAll() {
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) save(data);
        }
    }

    /** 立即保存某玩家。 */
    public void save(PlayerData data) {
        File f = fileOf(data.getUuid());
        YamlConfiguration yml = new YamlConfiguration();
        serialize(data, yml);
        try {
            yml.save(f);
            data.markClean();
        } catch (IOException e) {
            plugin.getLogger().warning("保存玩家档案失败 " + data.getUuid() + "：" + e.getMessage());
        }
    }

    private File fileOf(UUID uuid) {
        return new File(dataDir, uuid.toString() + ".yml");
    }

    // ------------------------------------------------------------------
    //  序列化
    // ------------------------------------------------------------------

    private void serialize(PlayerData data, YamlConfiguration yml) {
        yml.set("name", data.getName());
        yml.set("tracked", data.getTrackedQuest());

        // 任务进度
        String base = "quests";
        for (Map.Entry<String, QuestProgress> e : data.getAllProgress().entrySet()) {
            String key = safeKey(e.getKey());          // "zhulong:ch01" -> "zhulong.ch01"
            QuestProgress p = e.getValue();
            String path = base + "." + key;
            yml.set(path + ".state", p.getState().name());
            yml.set(path + ".stage", p.getStageIndex());
            yml.set(path + ".started", p.getStartedAt());
            yml.set(path + ".finished", p.getFinishedAt());
            if (p.getFailReason() != null) yml.set(path + ".failReason", p.getFailReason());

            // 要求进度
            for (Map.Entry<Integer, Map<Integer, Integer>> se : p.getAllRequirementProgress().entrySet()) {
                for (Map.Entry<Integer, Integer> re : se.getValue().entrySet()) {
                    yml.set(path + ".req." + se.getKey() + "_" + re.getKey(), re.getValue());
                }
            }
            // trigger 计数
            for (Map.Entry<String, Integer> te : p.getAllTriggerCounts().entrySet()) {
                yml.set(path + ".trig." + safeKey(te.getKey()), te.getValue());
            }
            // 已完成阶段
            if (!p.getCompletedStages().isEmpty()) {
                yml.set(path + ".doneStages", new java.util.ArrayList<>(p.getCompletedStages()));
            }
        }

        // 任务树状态
        for (Map.Entry<String, String> e : data.getAllTreeState().entrySet()) {
            yml.set("treeState." + safeKey(e.getKey()), e.getValue());
        }

        // 冷却
        for (Map.Entry<String, Long> e : data.getAllCooldowns().entrySet()) {
            yml.set("cooldowns." + safeKey(e.getKey()), e.getValue());
        }
    }

    private void deserialize(PlayerData data, YamlConfiguration yml) {
        data.setTrackedQuest(yml.getString("tracked", null));

        ConfigurationSection qs = yml.getConfigurationSection("quests");
        if (qs != null) {
            for (String key : qs.getKeys(false)) {
                String fullId = unsafeKey(key);
                QuestProgress p = data.getOrCreateProgress(fullId);
                ConfigurationSection sec = qs.getConfigurationSection(key);
                if (sec == null) continue;
                p.setState(QuestState.parse(sec.getString("state", "LOCKED")));
                p.setStageIndex(sec.getInt("stage", 0));
                p.setStartedAt(sec.getLong("started", 0));
                p.setFinishedAt(sec.getLong("finished", 0));
                p.setFailReason(sec.getString("failReason", null));

                ConfigurationSection req = sec.getConfigurationSection("req");
                if (req != null) {
                    for (String rk : req.getKeys(false)) {
                        String[] parts = rk.split("_");
                        if (parts.length != 2) continue;
                        try {
                            int stage = Integer.parseInt(parts[0]);
                            int idx = Integer.parseInt(parts[1]);
                            p.setRequirementProgress(stage, idx, req.getInt(rk));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                ConfigurationSection trig = sec.getConfigurationSection("trig");
                if (trig != null) {
                    for (String tk : trig.getKeys(false)) {
                        p.getAllTriggerCounts().put(unsafeKey(tk), trig.getInt(tk));
                    }
                }

                for (Object so : sec.getList("doneStages", new java.util.ArrayList<>())) {
                    try {
                        p.markStageCompleted(Integer.parseInt(String.valueOf(so)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        ConfigurationSection ts = yml.getConfigurationSection("treeState");
        if (ts != null) {
            for (String k : ts.getKeys(false)) {
                data.getAllTreeState().put(unsafeKey(k), ts.getString(k));
            }
        }

        ConfigurationSection cd = yml.getConfigurationSection("cooldowns");
        if (cd != null) {
            for (String k : cd.getKeys(false)) {
                data.getAllCooldowns().put(unsafeKey(k), cd.getLong(k));
            }
        }

        data.markClean();
    }

    /**
     * 把内部 key 编码成 YAML 安全的 key。
     *
     * <p>内部 key 可能同时含 <b>冒号</b>（树与任务、任务与冷却类型的分隔）和 <b>连字符</b>
     * （任务 ID 本身，如 {@code daily-01}）。早期的实现是把冒号直接换成连字符，
     * 再靠"只还原第一个连字符"来解码 —— 一旦 key 里出现两个冒号
     * （例如冷却 key {@code zhulong:daily01:COOLDOWN}）或任务 ID 自带连字符，
     * 就会出现不可逆的信息丢失，导致重启后冷却、进度读不回来。
     *
     * <p>因此改为真正可逆的转义：
     * <ul>
     *   <li>{@code %} -> {@code %25}  （必须先转义，避免与下面两条冲突）</li>
     *   <li>{@code :} -> {@code %3A}  （YAML 路径分隔符，必须转义）</li>
     *   <li>{@code .} -> {@code %2E}  （YAML 的层级分隔符，必须转义）</li>
     * </ul>
     * 连字符 {@code -} 本身在 YAML 里是安全的，保持原样，无需转义。
     */
    private String safeKey(String key) {
        return key.replace("%", "%25").replace(":", "%3A").replace(".", "%2E");
    }

    /**
     * {@link #safeKey(String)} 的逆操作。
     *
     * <p>兼容早期版本写下的档案：早期方案是把 {@code :} 直接替换成 {@code -}，
     * 由于 Key 中本来就可能含连字符（如 {@code daily-01}），那种编码不可逆。
     * 这里在按新规则解码失败时，回退到"把第一个连字符当作冒号"的旧规则，
     * 让老档案仍能被读出，玩家进度不会因升级而丢失。
     */
    private String unsafeKey(String key) {
        if (key.contains("%2E") || key.contains("%3A") || key.contains("%25")) {
            return key.replace("%2E", ".").replace("%3A", ":").replace("%25", "%");
        }
        // 旧格式回退：仅当 key 中不含冒号时，把第一个连字符还原为冒号
        int i = key.indexOf('-');
        if (i < 0) return key;
        return key.substring(0, i) + ":" + key.substring(i + 1);
    }

    /** 已缓存的玩家数（供 selftest）。 */
    public int getCachedCount() {
        return cache.size();
    }

    public File getDataDir() {
        return dataDir;
    }
}
