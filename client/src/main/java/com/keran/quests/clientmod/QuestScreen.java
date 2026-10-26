package com.keran.quests.clientmod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import java.util.*;

/** Independent vector UI: no inventory, item slots, Oraxen assets, browser or native library. */
public final class QuestScreen extends Screen {
    private static final int BG = 0xFF101720, PANEL = 0xFF17212D, BORDER = 0xFF2A3B4F;
    private static final int INK = 0xFFE6EEF5, MUTED = 0xFF8DA1B6, ACCENT = 0xFF75DDC2;
    private final List<Hit> hits = new ArrayList<>();
    private String tree, selected, confirm = "";
    private double panX = 24, panY = 26, zoom = 1, detailScroll, treeScroll;
    private int graphX, graphY, graphW, graphH, detailX, detailW, detailHeight;
    private boolean compact, detailOpen, dragging;
    private final Map<String, Box> nodes = new LinkedHashMap<>();
    private record Hit(int x, int y, int w, int h, Runnable action) { boolean has(double px, double py) { return px >= x && px < x+w && py >= y && py < y+h; } }
    private record Box(int x, int y, int w, int h) {}
    public QuestScreen(String focus) { super(Text.literal("任务终端")); focus(focus); }
    public void focus(String id) {
        QuestData.Quest q = KeranQuestsClient.DATA.quests.get(id);
        if (q != null) { selected = id; tree = q.tree; detailOpen = true; detailScroll = 0; confirm = ""; }
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { KeranQuestsClient.send("close"); super.close(); }
    @Override public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear(); nodes.clear();
        c.fill(0, 0, width, height, BG);
        c.fill(0, 0, width, 42, PANEL);
        c.fill(0, 41, width, 42, BORDER);
        text(c, "K", 14, 13, ACCENT); text(c, "KERAN / QUESTS", 31, 13, INK);
        text(c, "任务终端", 150, 13, MUTED);
        button(c, width-45, 9, 32, 23, "关闭", false, this::close, mx, my);
        compact = width < 640;
        int side = compact ? 105 : 132;
        graphX = side + 12; graphY = 54; graphH = Math.max(40, height - 80);
        detailW = compact ? width - graphX - 12 : Math.min(275, Math.max(220, width / 3));
        detailX = compact ? graphX : width - detailW - 12;
        graphW = compact ? width - graphX - 12 : detailX - graphX - 10;
        if (tree == null || !KeranQuestsClient.DATA.trees.containsKey(tree)) tree = KeranQuestsClient.DATA.trees.keySet().stream().findFirst().orElse(null);
        c.fill(0, 42, side, height, PANEL);
        text(c, "任务章节", 12, 55, MUTED);
        c.enableScissor(0, 74, side, height-24);
        int ty = 79 - (int) treeScroll;
        for (QuestData.Tree t : KeranQuestsClient.DATA.trees.values()) {
            boolean chosen = t.id().equals(tree);
            if (chosen) c.fill(5, ty-3, side-5, ty+38, 0xFF243648);
            text(c, textRenderer.trimToWidth(t.name(), side-24), 12, ty+2, chosen ? ACCENT : INK);
            long done = KeranQuestsClient.DATA.quests.values().stream().filter(q -> q.tree.equals(t.id()) && "COMPLETED".equals(q.state)).count();
            long total = KeranQuestsClient.DATA.quests.values().stream().filter(q -> q.tree.equals(t.id()) && !"EXCLUDED".equals(q.state)).count();
            text(c, done + " / " + total + " 完成", 12, ty+19, MUTED);
            if (ty > 65 && ty < height-24) hits.add(new Hit(5,ty-3,side-10,41, () -> { tree=t.id(); selected=null; detailOpen=false; panX=24; panY=26; detailScroll=0; confirm=""; }));
            ty += 46;
        }
        c.disableScissor();
        if (compact && detailOpen && selected != null) renderDetail(c,mx,my,true);
        else {
            renderGraph(c,mx,my);
            if (!compact) renderDetail(c,mx,my,false);
        }
        text(c, compact ? "拖动平移 · 滚轮缩放 · J 打开任务" : "拖动平移 · 滚轮缩放 · 点击节点查看详情", graphX, height-17, MUTED);
        if (System.currentTimeMillis()-KeranQuestsClient.noticeAt < 5000) {
            c.fill(0,height-32,width,height,BG);
            text(c,textRenderer.trimToWidth(KeranQuestsClient.notice,width-24),12,height-20,0xFFFFCC88);
        }
    }
    private void renderGraph(DrawContext c, int mx, int my) {
        c.fill(graphX,graphY,graphX+graphW,graphY+graphH,0xFF121D28);
        QuestData.Tree t = KeranQuestsClient.DATA.trees.get(tree);
        c.enableScissor(graphX,graphY,graphX+graphW,graphY+graphH);
        List<QuestData.Quest> quests = KeranQuestsClient.DATA.quests.values().stream().filter(q->q.tree.equals(tree)).toList();
        Map<Integer,Integer> rows = new HashMap<>();
        Map<String,Integer> depths = new HashMap<>();
        for (QuestData.Quest q : quests) {
            int depth = depth(q,depths,new HashSet<>());
            int row = rows.merge(depth,1,Integer::sum)-1;
            int x = graphX+(int)(panX+depth*185*zoom), y=graphY+(int)(panY+row*78*zoom);
            nodes.put(q.id,new Box(x,y,(int)(155*zoom),Math.max(34,(int)(51*zoom))));
        }
        for (QuestData.Quest q : quests) {
            Box b=nodes.get(q.id);
            for(String parent:q.parents) {
                Box a=nodes.get(parent); if(a==null)continue;
                int x=a.x+a.w, y=a.y+a.h/2, end=b.y+b.h/2, middle=(x+b.x)/2;
                c.fill(Math.min(x,middle),y,Math.max(x,middle)+1,y+1,BORDER);
                c.fill(middle,Math.min(y,end),middle+1,Math.max(y,end)+1,BORDER);
                c.fill(Math.min(middle,b.x),end,Math.max(middle,b.x)+1,end+1,BORDER);
            }
        }
        for(QuestData.Quest q:quests) {
            Box b=nodes.get(q.id);
            if(b.x+b.w<graphX||b.x>graphX+graphW||b.y+b.h<graphY||b.y>graphY+graphH)continue;
            int color=stateColor(q.state);
            boolean hover=mx>=b.x&&mx<b.x+b.w&&my>=b.y&&my<b.y+b.h;
            round(c,b.x,b.y,b.w,b.h,q.id.equals(selected)?0xFF2C4355:hover?0xFF26384B:PANEL);
            c.fill(b.x,b.y+3,b.x+2,b.y+b.h-3,color);
            text(c,textRenderer.trimToWidth(q.name,Math.max(20,b.w-16)),b.x+9,b.y+9,q.hidden?MUTED:INK);
            text(c,status(q.state)+(q.tracked?" · 追踪中":""),b.x+9,b.y+b.h-15,color);
            int hx=Math.max(graphX,b.x),hy=Math.max(graphY,b.y);
            hits.add(new Hit(hx,hy,Math.min(graphX+graphW,b.x+b.w)-hx,Math.min(graphY+graphH,b.y+b.h)-hy,()->focus(q.id)));
        }
        if(quests.isEmpty()) text(c,"此章节暂无任务",graphX+18,graphY+50,MUTED);
        c.disableScissor();
        c.fill(graphX,graphY,graphX+graphW,graphY+19,0xED121D28);
        text(c,t==null?"正在获取任务…":textRenderer.trimToWidth(t.name(),graphW-60),graphX+8,graphY+6,MUTED);
        button(c,graphX+graphW-42,graphY+2,38,16,"复位",false,()->{panX=24;panY=26;zoom=1;},mx,my);
    }
    private int depth(QuestData.Quest q, Map<String,Integer> memo, Set<String> path) {
        if(memo.containsKey(q.id))return memo.get(q.id);
        if(!path.add(q.id))return 0;
        int d=0;
        for(String id:q.parents){QuestData.Quest p=KeranQuestsClient.DATA.quests.get(id);if(p!=null&&p.tree.equals(q.tree))d=Math.max(d,Math.min(100,depth(p,memo,path)+1));}
        path.remove(q.id);memo.put(q.id,d);return d;
    }
    private void renderDetail(DrawContext c,int mx,int my,boolean overlay) {
        c.fill(detailX,graphY,detailX+detailW,graphY+graphH,PANEL);
        QuestData.Quest q=KeranQuestsClient.DATA.quests.get(selected);
        if(q==null){text(c,"选择一个任务节点",detailX+16,graphY+22,MUTED);return;}
        int header=overlay?38:12;
        if(overlay)button(c,detailX+8,graphY+6,78,22,"← 任务树",false,()->detailOpen=false,mx,my);
        int top=graphY+header, bottom=graphY+graphH-8;
        c.enableScissor(detailX,top,detailX+detailW,bottom);
        int x=detailX+13,y=top+8-(int)detailScroll,w=detailW-26;
        text(c,status(q.state)+(q.type.equals("MAIN")?" / 主线":" / 支线"),x,y,stateColor(q.state));y+=19;
        y=wrapped(c,q.name,x,y,w,INK);y+=10;
        y=wrapped(c,q.description,x,y,w,MUTED);y+=14;
        if(!q.hidden){
            text(c,"任务目标  "+Math.min(q.stageIndex+1,q.stageTotal)+" / "+q.stageTotal,x,y,MUTED);y+=20;
            for(QuestData.Stage s:q.stages){
                y=wrapped(c,(s.done?"✓ ":s.active?"▶ ":"· ")+s.name,x,y,w,s.active?ACCENT:s.done?MUTED:INK);
                if(!s.description.isBlank()){y+=3;y=wrapped(c,s.description,x+8,y,w-8,MUTED);}
                if(s.active)for(String req:s.requirements){y+=4;y=wrapped(c,req,x+8,y,w-8,INK);}
                y+=12;
            }
            if(!q.rewards.isEmpty()){text(c,"完成奖励",x,y,MUTED);y+=17;for(String reward:q.rewards){y=wrapped(c,reward,x,y,w,0xFFE7C682);y+=4;}y+=12;}
        }
        if(q.canAccept){final String id=q.id;buttonVisible(c,x,y,w,25,"接受任务",true,()->KeranQuestsClient.send("accept","quest",id),mx,my,top,bottom);y+=33;}
        if("ACTIVE".equals(q.state)){
            buttonVisible(c,x,y,w,25,q.tracked?"正在追踪":"追踪此任务",true,()->KeranQuestsClient.send("track","quest",q.id),mx,my,top,bottom);y+=33;
            for(QuestData.Choice option:q.choices){String key=q.id+":"+option.index;
                buttonVisible(c,x,y,w,28,confirm.equals(key)?"确认选择 · "+option.label:option.label,true,()->{if(confirm.equals(key)){KeranQuestsClient.send("choose","quest",q.id,"stage",q.choiceStage,"index",option.index);confirm="";}else confirm=key;},mx,my,top,bottom);y+=35;
            }
            if(q.canAbandon){String key="abandon:"+q.id;buttonVisible(c,x,y,w,25,confirm.equals(key)?"确认放弃并清空进度":"放弃任务",false,()->{if(confirm.equals(key)){KeranQuestsClient.send("abandon","quest",q.id);confirm="";}else confirm=key;},mx,my,top,bottom);y+=33;}
        } else if(!q.canAccept&&!q.error.isBlank()){y=wrapped(c,q.error,x,y,w,MUTED);}
        detailHeight=Math.max(0,y-top+(int)detailScroll+10);
        c.disableScissor();
        if(detailHeight>bottom-top){int track=bottom-top,thumb=Math.max(16,track*track/detailHeight);int pos=(int)(detailScroll/Math.max(1,detailHeight-track)*(track-thumb));c.fill(detailX+detailW-4,top+pos,detailX+detailW-2,top+pos+thumb,BORDER);}
    }
    private int wrapped(DrawContext c,String text,int x,int y,int w,int color){for(var line:textRenderer.wrapLines(Text.literal(text==null?"":text),Math.max(20,w))){c.drawText(textRenderer,line,x,y,color,false);y+=12;}return y;}
    private void buttonVisible(DrawContext c,int x,int y,int w,int h,String label,boolean primary,Runnable action,int mx,int my,int top,int bottom){
        if(y>=top&&y+h<=bottom)button(c,x,y,w,h,label,primary,action,mx,my);
        else {round(c,x,y,w,h,primary?0xFF254C47:0xFF263446);text(c,textRenderer.trimToWidth(label,w-12),x+6,y+(h-8)/2,INK);}
    }
    private void button(DrawContext c,int x,int y,int w,int h,String label,boolean primary,Runnable action,int mx,int my){
        boolean hover=mx>=x&&mx<x+w&&my>=y&&my<y+h;
        round(c,x,y,w,h,primary?(hover?0xFF356A60:0xFF254C47):(hover?0xFF35485C:0xFF263446));
        text(c,textRenderer.trimToWidth(label,w-12),x+6,y+(h-8)/2,primary?ACCENT:INK);hits.add(new Hit(x,y,w,h,action));
    }
    private void text(DrawContext c,String value,int x,int y,int color){c.drawText(textRenderer,value,x,y,color,false);}
    private static void round(DrawContext c,int x,int y,int w,int h,int color){c.fill(x+3,y,x+w-3,y+h,color);c.fill(x,y+3,x+w,y+h-3,color);c.fill(x+1,y+1,x+w-1,y+h-1,color);}
    static int stateColor(String state){return switch(state){case "ACTIVE"->ACCENT;case "AVAILABLE"->0xFFE8C77E;case "COMPLETED"->0xFF82AADE;case "FAILED"->0xFFE98B8B;default->MUTED;};}
    static String status(String state){return switch(state){case "ACTIVE"->"进行中";case "AVAILABLE"->"可接取";case "COMPLETED"->"已完成";case "FAILED"->"已失败";case "EXCLUDED"->"另一条路线";default->"未解锁";};}
    @Override public boolean mouseClicked(double x,double y,int button){if(button==0){for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).has(x,y)){hits.get(i).action.run();return true;}if(x>=graphX&&x<graphX+graphW&&y>=graphY&&y<graphY+graphH){dragging=true;return true;}}return super.mouseClicked(x,y,button);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(dragging&&button==0){panX+=dx;panY+=dy;return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double amount){
        if(x<graphX){treeScroll=Math.max(0,Math.min(Math.max(0,KeranQuestsClient.DATA.trees.size()*46-(height-100)),treeScroll-amount*24));return true;}
        if((!compact&&x>=detailX)||(compact&&detailOpen)){detailScroll=Math.max(0,Math.min(Math.max(0,detailHeight-(graphH-(compact?46:20))),detailScroll-amount*24));return true;}
        double old=zoom;zoom=Math.max(.65,Math.min(1.5,zoom+amount*.1));panX=(panX-(x-graphX))*zoom/old+(x-graphX);panY=(panY-(y-graphY))*zoom/old+(y-graphY);return true;
    }
}
