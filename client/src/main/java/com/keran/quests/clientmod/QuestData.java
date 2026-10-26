package com.keran.quests.clientmod;

import com.google.gson.*;
import java.util.*;

/** Snapshot assembly stays on the render thread; only complete snapshots become visible. */
public final class QuestData {
    public record Tree(String id, String name, String description) {}
    public static final class Quest {
        public String id, tree, name, description, state, type, error, choiceStage;
        public boolean hidden, canAccept, canAbandon, tracked;
        public int stageIndex, stageTotal;
        public List<String> parents = new ArrayList<>(), rewards = new ArrayList<>();
        public List<Choice> choices = new ArrayList<>();
        public List<Stage> stages = new ArrayList<>();
    }
    public static final class Choice { public int index; public String label, description; }
    public static final class Stage {
        public String id, name, description;
        public boolean active, done;
        public List<String> requirements = new ArrayList<>();
    }
    public final Map<String, Tree> trees = new LinkedHashMap<>();
    public final Map<String, Quest> quests = new LinkedHashMap<>();
    public long revision;
    private static final Gson GSON = new Gson();
    private QuestData pending;
    public void begin() { pending = new QuestData(); }
    public void add(JsonObject json) {
        if (pending == null) return;
        switch (json.get("op").getAsString()) {
            case "tree" -> { Tree tree = GSON.fromJson(json, Tree.class); pending.trees.put(tree.id(), tree); }
            case "quest" -> { Quest quest = GSON.fromJson(json, Quest.class); pending.quests.put(quest.id, quest); }
            case "stage" -> { Quest q = pending.quests.get(json.get("quest").getAsString()); if (q != null) q.stages.add(GSON.fromJson(json, Stage.class)); }
        }
    }
    public void commit() {
        if (pending == null) return;
        trees.clear(); trees.putAll(pending.trees); quests.clear(); quests.putAll(pending.quests);
        pending = null; revision++;
    }
    public void clear() { trees.clear(); quests.clear(); pending = null; revision++; }
}
