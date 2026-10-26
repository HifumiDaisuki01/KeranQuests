package com.keran.quests;

import com.keran.quests.client.ClientBridge;
import com.keran.quests.config.*;
import com.keran.quests.config.model.*;
import com.keran.quests.config.model.enums.*;
import com.keran.quests.gui.GuiManager;
import com.keran.quests.hook.*;
import com.keran.quests.player.*;
import com.keran.quests.runtime.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegressionTest {
    @TempDir Path dir;
    KeranQuests plugin;
    Player player;
    PlayerData data;
    PlayerDataStore store;
    QuestTreeLoader loader;
    QuestManager manager;
    OraxenHook items;
    BukkitScheduler scheduler;
    static Server server;
    List<Runnable> queue;
    @BeforeAll static void server() {
        server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getLogger("KQ-test"));
        when(server.getName()).thenReturn("Test"); when(server.getVersion()).thenReturn("1.20.1");
        when(server.getBukkitVersion()).thenReturn("1.20.1"); Bukkit.setServer(server);
    }
    @BeforeEach void setup() throws Exception {
        plugin=mock(KeranQuests.class); player=mock(Player.class); store=mock(PlayerDataStore.class); items=mock(OraxenHook.class);
        scheduler=mock(BukkitScheduler.class); queue=new ArrayList<>();
        when(server.getScheduler()).thenReturn(scheduler); when(server.getOnlinePlayers()).thenAnswer(x->List.of(player));
        when(scheduler.runTaskLater(any(Plugin.class),any(Runnable.class),anyLong())).thenAnswer(x->{queue.add(x.getArgument(1));return mock(BukkitTask.class);});
        when(plugin.getLogger()).thenReturn(Logger.getLogger("KQ-test")); when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getServer()).thenReturn(server); when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getName()).thenReturn("Tester");when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);when(server.getPlayer(id)).thenReturn(player);
        PlayerInventory inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[0]);
        World world=mock(World.class);when(world.getName()).thenReturn("world");when(world.getUID()).thenReturn(UUID.randomUUID());when(player.getWorld()).thenReturn(world);
        data=new PlayerData(id,"Tester");when(plugin.getPlayerData(player)).thenReturn(data);when(plugin.getPlayerDataStore()).thenReturn(store);
        when(plugin.getOraxenHook()).thenReturn(items);when(plugin.getVaultHook()).thenReturn(mock(VaultHook.class));
        when(plugin.getFreezeManager()).thenReturn(new FreezeManager(plugin));when(plugin.getClientBridge()).thenReturn(mock(ClientBridge.class));
        when(plugin.getChoiceManager()).thenReturn(mock(ChoiceManager.class));
        when(plugin.getWorldGuardHook()).thenReturn(mock(WorldGuardHook.class));
        loader=new QuestTreeLoader(plugin);when(plugin.getTreeLoader()).thenReturn(loader);
        manager=new QuestManager(plugin);when(plugin.getQuestManager()).thenReturn(manager);
        when(plugin.getBranchService()).thenReturn(new BranchService(plugin));
        Files.createDirectories(dir.resolve("quests"));Files.createDirectories(dir.resolve("npc"));
    }
    Quest tree(String yaml) throws Exception {
        Files.writeString(dir.resolve("quests/test.yml"),yaml);
        loader.load();return loader.getAllQuests().iterator().next();
    }
    QuestProgress active(Quest q) { QuestProgress p=data.getOrCreateProgress(q.getFullId());p.setState(QuestState.ACTIVE);p.setStartedAt(System.currentTimeMillis());return p; }
    void pump(){int limit=100;while(!queue.isEmpty()&&limit-->0)queue.remove(0).run();assertTrue(limit>0,"scheduler loop");}

    @Test void bundledTreesAndDialogueLoad() throws Exception {
        for(String name:List.of("tutorial","zhulong","zhulong-example")) Files.copy(Path.of("src/main/resources/quests/"+name+".yml"),dir.resolve("quests/"+name+".yml"));
        loader.load();assertEquals(21,loader.getQuestCount());
        Files.copy(Path.of("src/main/resources/npc/dialogue.yml"),dir.resolve("npc/dialogue.yml"));
        DialogueRunner runner=new DialogueRunner(plugin);runner.load();assertTrue(runner.getDialogueCount()>5);
    }
    @Test void minimalReadmeStyleStageReceivesStableId() throws Exception {
        Quest q=tree("id: test\nquests:\n  hello:\n    stages:\n      - requirements:\n          - {type: custom_trigger, key: hello}\n");
        assertEquals("stage_1",q.getStages().get(0).getId());assertEquals(1,q.getStageCount());
    }
    @Test void conciseSyntaxAndDurationsExpand() throws Exception {
        Quest q=tree("id: test\ndefaults: {type: MAIN}\nquests:\n  hello:\n    cooldown: 12h\n    steps:\n      - {id: arrive, enter: 'world:camp'}\n      - {id: pause, wait: 5s}\n      - {id: talk, talk: Guide}\n");
        assertEquals(QuestType.MAIN,q.getType());assertEquals(43200,q.getCooldown());
        assertEquals("world",q.getStages().get(0).getRequirements().get(0).getWorld());
        assertEquals(RequirementType.WAIT,q.getStages().get(1).getRequirements().get(0).getType());
    }
    @Test void badTypeFailsWithoutReplacingRegistry() throws Exception {
        Quest q=tree("id: test\nquests:\n  hello:\n    steps: [{trigger: hello}]\n");
        Files.writeString(dir.resolve("quests/test.yml"),"id: test\nquests:\n  hello:\n    stages:\n      - id: bad\n        requirements: [{type: typo}]\n");
        assertThrows(IllegalArgumentException.class,loader::load);assertSame(q,loader.resolveQuest("test:hello"));
    }
    @Test void malformedYamlFailsWithoutReplacingRegistry() throws Exception {
        Quest q=tree("id: test\nquests:\n  hello:\n    steps: [{trigger: hello}]\n");
        Files.writeString(dir.resolve("quests/test.yml"),"quests: [broken");
        assertThrows(IllegalArgumentException.class,loader::load);assertSame(q,loader.resolveQuest("test:hello"));
    }
    @Test void duplicateTreeAndDependencyCyclesAreRejected() throws Exception {
        String yaml="id: test\nquests:\n  a:\n    after: b\n    steps: [{trigger: a}]\n  b:\n    after: a\n    steps: [{trigger: b}]\n";
        assertThrows(IllegalArgumentException.class,()->tree(yaml));
        Files.writeString(dir.resolve("quests/test.yml"),"id: test\nquests:\n  a:\n    steps: [{trigger: a}]\n");
        Files.copy(dir.resolve("quests/test.yml"),dir.resolve("quests/copy.yml"));assertThrows(IllegalArgumentException.class,loader::load);
    }
    @Test void missingTargetRejected() {
        assertThrows(IllegalArgumentException.class,()->tree("id: test\nquests:\n  a:\n    after: missing\n    steps: [{trigger: a}]\n"));
    }
    @Test void ambiguousShortReferenceIsNotGuessed() throws Exception {
        tree("id: test\nquests:\n  a:\n    steps: [{trigger: a}]\n");
        Files.writeString(dir.resolve("quests/other.yml"),"id: other\nquests:\n  a:\n    steps: [{trigger: a}]\n");loader.load();assertNull(loader.resolveQuest("a"));assertNotNull(loader.resolveQuest("test:a"));
    }
    @Test void stageItemsConsumedOnceAndRecheckedLive() throws Exception {
        Quest q=tree("id: test\nquests:\n  a:\n    stages:\n      - id: give\n        requirements: [{type: item, item: 'minecraft:paper', count: 5, consume: true}]\n");
        QuestProgress progress=active(q);when(items.countItem(player,"minecraft:paper")).thenReturn(5);when(items.removeItem(player,"minecraft:paper",5)).thenReturn(5);
        assertTrue(manager.checkStageCompletion(player,q,progress,true));assertFalse(manager.checkStageCompletion(player,q,progress,true));
        verify(items,times(1)).removeItem(player,"minecraft:paper",5);assertEquals(QuestState.COMPLETED,progress.getState());
    }
    @Test void removedItemsCannotUseStaleProgress() throws Exception {
        Quest q=tree("id: test\nquests:\n  a:\n    steps: [{submit: {item: 'minecraft:paper', count: 2}}]\n");
        QuestProgress p=active(q);p.setRequirementProgress(0,0,2);when(items.countItem(player,"minecraft:paper")).thenReturn(0);
        assertFalse(manager.checkStageCompletion(player,q,p,true));verify(items,never()).removeItem(any(),anyString(),anyInt());
    }
    @Test void completionRetriesDoNotDuplicateExperience() throws Exception {
        Quest q=tree("id: test\nquests:\n  a:\n    steps: [{trigger: a}]\n    rewards: {exp: 25}\n");active(q);
        manager.complete(player,q);manager.complete(player,q);verify(player,times(1)).giveExp(25);
    }
    @Test void reopeningChoiceKeepsBusinessMarker() throws Exception {
        tree("id: test\nquests:\n  a:\n    stages: [{id: pick, choice: true, choices: [{quest: b, label: B}]}]\n  b:\n    steps: [{trigger: b}]\n");
        Quest q=loader.resolveQuest("test:a");QuestProgress p=active(q);p.markStageHandled(0);p.markStageCompleted(0);
        GuiManager gui=new GuiManager(plugin);var method=GuiManager.class.getDeclaredMethod("handleReopenChoice",Player.class,String.class);method.setAccessible(true);method.invoke(gui,player,q.getFullId());assertTrue(p.isStageHandled(0));
    }
    @Test void stageMarkersSurviveSaveLoadAndHyphensRoundTrip() throws Exception {
        PlayerDataStore real=new PlayerDataStore(plugin);PlayerData loaded=real.load(data.getUuid(),"Tester");QuestProgress p=loaded.getOrCreateProgress("test:daily-01");
        p.setState(QuestState.ACTIVE);p.markStageCompleted(0);p.markStageHandled(0);p.markChoiceGuiShown(0);loaded.setTreeState("test","choice:final-group","A");real.save(loaded);real.unload(data.getUuid());
        QuestProgress saved=real.load(data.getUuid(),"Tester").getProgress("test:daily-01");assertNotNull(saved);assertTrue(saved.isStageHandled(0));assertTrue(saved.isChoiceGuiShown(0));assertEquals(p.getExecutionId(),saved.getExecutionId());
    }
    @Test void corruptPlayerDataIsNeverLoadedAsEmptyProfile() throws Exception {
        PlayerDataStore real=new PlayerDataStore(plugin);Files.writeString(dir.resolve("data/"+data.getUuid()+".yml"),"quests: [broken");assertThrows(IllegalStateException.class,()->real.load(data.getUuid(),"Tester"));
    }
    @Test void oldCompletedStageMigratesToHandled() throws Exception {
        Files.createDirectories(dir.resolve("data"));Files.writeString(dir.resolve("data/"+data.getUuid()+".yml"),"quests:\n  test%3Aa:\n    state: ACTIVE\n    doneStages: [0]\n");
        PlayerDataStore real=new PlayerDataStore(plugin);assertTrue(real.load(data.getUuid(),"Tester").getProgress("test:a").isStageHandled(0));
    }
    @Test void declinedHandoffDoesNotCommitChoiceOrConsume() throws Exception {
        tree("id: test\nquests:\n  a:\n    steps: [{trigger: a}]\n  b:\n    prerequisites: {permission: locked}\n    steps: [{trigger: b}]\n");
        Quest a=loader.resolveQuest("test:a"),b=loader.resolveQuest("test:b");QuestProgress p=active(a);when(player.hasPermission("locked")).thenReturn(false);when(items.countItem(player,"core")).thenReturn(1);
        assertNotNull(plugin.getBranchService().select(player,b,a,"test_final","A","core",1,true));
        assertNull(data.getTreeState("test","choice:test_final"));assertEquals(QuestState.ACTIVE,p.getState());verify(items,never()).removeItem(any(),anyString(),anyInt());verify(store,never()).save(any());
    }
    @Test void successfulHandoffReleasesMainSlotAndAcceptsChild() throws Exception {
        tree("id: test\nmain_quest_limit: 1\nquests:\n  a:\n    type: MAIN\n    steps: [{trigger: a}]\n  b:\n    type: MAIN\n    after: a\n    steps: [{trigger: b}]\n");
        Quest a=loader.resolveQuest("test:a"),b=loader.resolveQuest("test:b");active(a);when(player.hasPermission("kq.bypass.limit")).thenReturn(false);
        assertNull(plugin.getBranchService().select(player,b,a,"test_final","A",null,1,false));
        assertEquals(QuestState.COMPLETED,data.getProgress(a.getFullId()).getState());assertEquals(QuestState.ACTIVE,data.getProgress(b.getFullId()).getState());
    }
    @Test void refusingDeliveryDoesNotSignalNpcTalk() throws Exception {
        Quest q=tree("id: test\nquests:\n  a:\n    steps: [{talk: Dealer}]\n");active(q);
        Files.writeString(dir.resolve("npc/dialogue.yml"),"dialogues:\n  deliver:\n    npc: Dealer\n    nodes:\n      - choose:\n          options:\n            - {label: Not yet, reply: Bye, after: 1}\n            - {label: Deliver, require_item: core, consume: true, after: 1}\n");
        DialogueRunner runner=new DialogueRunner(plugin);runner.load();assertTrue(runner.startDialogue(player,"Dealer"));assertNull(runner.onChoose(player,"deliver",0,0));pump();assertEquals(QuestState.ACTIVE,data.getProgress(q.getFullId()).getState());
    }
    @Test void successfulDeliverySignalsOnlyOriginalStage() throws Exception {
        Quest q=tree("id: test\nquests:\n  a:\n    steps: [{talk: Dealer}, {talk: Dealer}]\n");active(q);when(items.countItem(player,"core")).thenReturn(1);when(items.removeItem(player,"core",1)).thenReturn(1);
        Files.writeString(dir.resolve("npc/dialogue.yml"),"dialogues:\n  deliver:\n    npc: Dealer\n    nodes:\n      - choose:\n          options: [{label: Deliver, require_item: core, consume: true, after: 1}]\n");
        DialogueRunner runner=new DialogueRunner(plugin);runner.load();runner.startDialogue(player,"Dealer");assertNull(runner.onChoose(player,"deliver",0,0));assertNotNull(runner.onChoose(player,"deliver",0,0));pump();
        assertEquals(1,data.getProgress(q.getFullId()).getStageIndex());verify(items,times(1)).removeItem(player,"core",1);
    }
    @Test void regionResultsRefreshAcrossBoundaryWithoutEightBlockMovement() throws Exception {
        Quest q=tree("id: test\nquests:\n  a:\n    steps: [{enter: 'world:tiny'}]\n");QuestProgress p=active(q);when(plugin.getWorldGuardHook().isInRegion(player,"tiny")).thenReturn(false,true);
        RegionRunner runner=new RegionRunner(plugin);var tick=RegionRunner.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(runner);assertEquals(0,p.getRequirementProgress(0,0));tick.invoke(runner);assertEquals(QuestState.COMPLETED,p.getState());
    }
    @Test void simultaneousStayTimersHaveDistinctKeysAndQuitClearsThem() throws Exception {
        tree("id: test\nquests:\n  a:\n    steps: [{stay: {region: 'world:camp', time: 30s}}]\n  b:\n    steps: [{stay: {region: 'world:camp', time: 30s}}]\n");active(loader.resolveQuest("test:a"));active(loader.resolveQuest("test:b"));when(plugin.getWorldGuardHook().isInRegion(player,"camp")).thenReturn(true);
        RegionRunner runner=new RegionRunner(plugin);var tick=RegionRunner.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(runner);var field=RegionRunner.class.getDeclaredField("stayEnterAt");field.setAccessible(true);Map<?,?> timers=(Map<?,?>)field.get(runner);assertEquals(2,timers.size());runner.forget(player.getUniqueId());assertTrue(timers.isEmpty());
    }
    @Test void customUiRejectsActionsWithoutHandshake() {
        ClientBridge bridge=new ClientBridge(plugin);bridge.onPluginMessageReceived(ClientBridge.CHANNEL,player,"{\"op\":\"accept\",\"quest\":\"test:a\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));verify(store,never()).save(any());assertTrue(data.getAllProgress().isEmpty());
    }
    @Test void eitherExclusiveBranchCanCompleteTheTree() throws Exception {
        tree("id: test\nquests:\n  a:\n    exclusive_group: route\n    steps: [{trigger: a}]\n  b:\n    exclusive_group: route\n    steps: [{trigger: b}]\n");
        data.getOrCreateProgress("test:b").setState(QuestState.COMPLETED);
        assertTrue(manager.isTreeCompleted(data, "test"));
    }

}
