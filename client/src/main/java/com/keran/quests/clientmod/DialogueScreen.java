package com.keran.quests.clientmod;

import com.google.gson.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Dialogue choices are validated again by the server using a per-session nonce. */
public final class DialogueScreen extends Screen {
    private JsonObject message;
    private int scroll;
    public DialogueScreen(JsonObject message){super(Text.literal("剧情对话"));this.message=message;}
    public void update(JsonObject message){this.message=message;scroll=0;if(client!=null)init(client,width,height);}
    public void refresh() { if (client != null) init(client, width, height); }
    @Override public boolean shouldPause(){return false;}
    @Override protected void init(){
        clearChildren();
        int w=Math.min(width-40,460),left=(width-w)/2;
        JsonArray options=message.getAsJsonArray("buttons");
        int y=height-48-Math.min(4,options.size())*26;
        int start=Math.max(0,Math.min(scroll,Math.max(0,options.size()-4)));
        for(int i=start;i<Math.min(options.size(),start+4);i++){
            JsonObject option=options.get(i).getAsJsonObject();
            ButtonWidget button=ButtonWidget.builder(Text.literal(option.get("label").getAsString()),b->{
                KeranQuestsClient.send(message.get("mode").getAsString().equals("ask")?"dialogPick":"dialogChoose",
                    "dialog",message.get("dialog").getAsString(),"session",message.get("session").getAsString(),"node",message.get("node").getAsInt(),"index",option.get("index").getAsInt());
                for(var child:children())if(child instanceof ButtonWidget btn)btn.active=false;
            }).dimensions(left,y,w,22).build();
            button.active=option.get("enabled").getAsBoolean();
            if(option.has("tip")&&!option.get("tip").getAsString().isBlank())button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(option.get("tip").getAsString())));
            addDrawableChild(button);y+=26;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("稍后再说 · Esc"),b->close()).dimensions(left,height-26,w,20).build());
    }
    @Override public void render(DrawContext context,int mx,int my,float delta){
        context.fill(0,0,width,height,0x66101720);
        int w=Math.min(width-40,460),left=(width-w)/2,top=Math.max(18,height-230);
        context.fill(left-12,top-12,left+w+12,height-3,0xF214202D);
        context.fill(left-12,top-12,left-10,height-3,0xFF75DDC2);
        context.drawText(textRenderer,"剧情通讯",left,top,0xFF75DDC2,false);
        int y=top+23;
        int optionTop=height-48-Math.min(4,message.getAsJsonArray("buttons").size())*26;
        context.enableScissor(left,top+20,left+w,optionTop-6);
        for(var line:textRenderer.wrapLines(Text.literal(message.get("text").getAsString()),w)){context.drawText(textRenderer,line,left,y,0xFFE6EEF5,false);y+=13;}
        context.disableScissor();
        super.render(context,mx,my,delta);
    }
    @Override public void close(){KeranQuestsClient.send("dialogEnd","session",message.get("session").getAsString());super.close();}
    @Override public boolean mouseScrolled(double x,double y,double amount){scroll=Math.max(0,scroll-(int)amount);init(client,width,height);return true;}
}
