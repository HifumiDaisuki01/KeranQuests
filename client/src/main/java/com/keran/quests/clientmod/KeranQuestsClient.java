package com.keran.quests.clientmod;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;

public final class KeranQuestsClient implements ClientModInitializer {
    public static final Identifier CHANNEL = new Identifier("keranquests", "ui");
    public static final QuestData DATA = new QuestData();
    public static String token = "";
    public static String notice = "";
    public static long noticeAt;
    private static final Gson GSON = new Gson();
    private int handshakeTicks;
    private boolean joined;
    @Override public void onInitializeClient() {
        KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.keranquests.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.keranquests"));
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buf, responder) -> {
            if (buf.readableBytes() > 32700) return;
            byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes);
            client.execute(() -> {
                try { receive(client, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()); }
                catch (RuntimeException ignored) { notice = "任务数据解析失败，请检查客户端与服务端版本。"; noticeAt = System.currentTimeMillis(); }
            });
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { joined = true; handshakeTicks = 0; token = ""; DATA.clear(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { joined = false; token = ""; DATA.clear(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (joined && token.isEmpty() && ++handshakeTicks % 40 == 0 && handshakeTicks <= 240) send("hello", "protocol", 1);
            while (key.wasPressed()) {
                if (client.player == null) continue;
                if (token.isEmpty()) { send("hello", "protocol", 1); client.player.sendMessage(Text.literal("正在连接任务终端，请稍后再按 J。"), true); }
                else send("open");
            }
        });
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden || client.currentScreen != null) return;
            QuestData.Quest q = DATA.quests.values().stream().filter(x -> x.tracked && "ACTIVE".equals(x.state)).findFirst().orElse(null);
            if (q == null) return;
            int x = context.getScaledWindowWidth() - 214;
            context.fill(x - 8, 26, x + 202, 67, 0xB018202B);
            context.fill(x - 8, 26, x - 6, 67, 0xFF74DCC5);
            context.drawText(client.textRenderer, client.textRenderer.trimToWidth(q.name, 198), x, 34, 0xE6EEF6, false);
            QuestData.Stage stage = q.stages.stream().filter(s -> s.active).findFirst().orElse(null);
            context.drawText(client.textRenderer, client.textRenderer.trimToWidth(stage == null ? "J · 查看任务" : stage.name, 198), x, 51, 0x9CAEC2, false);
        });
    }
    private static void receive(MinecraftClient client, JsonObject msg) {
        switch (msg.get("op").getAsString()) {
            case "ready" -> { token = msg.get("token").getAsString(); }
            case "begin" -> DATA.begin();
            case "tree", "quest", "stage" -> DATA.add(msg);
            case "done" -> {
                DATA.commit();
                String focus = msg.has("focus") ? msg.get("focus").getAsString() : "";
                if (msg.get("open").getAsBoolean() && !(client.currentScreen instanceof QuestScreen)) client.setScreen(new QuestScreen(focus));
                else if (client.currentScreen instanceof QuestScreen screen && !focus.isBlank()) screen.focus(focus);
            }
            case "dialog" -> {
                if (client.currentScreen instanceof DialogueScreen screen) screen.update(msg);
                else client.setScreen(new DialogueScreen(msg));
            }
            case "dialogEnd" -> { if (client.currentScreen instanceof DialogueScreen) client.setScreen(null); }
            case "error" -> { if (client.currentScreen instanceof DialogueScreen screen) screen.refresh(); notice = msg.get("text").getAsString(); noticeAt = System.currentTimeMillis(); if (client.player != null) client.player.sendMessage(Text.literal(notice), true); }
        }
    }
    public static void send(String op, Object... values) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) return;
        JsonObject msg = new JsonObject(); msg.addProperty("op", op); msg.addProperty("token", token);
        for (int i = 0; i < values.length; i += 2) msg.add(String.valueOf(values[i]), GSON.toJsonTree(values[i + 1]));
        PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        buf.writeBytes(msg.toString().getBytes(StandardCharsets.UTF_8)); ClientPlayNetworking.send(CHANNEL, buf);
    }
}
