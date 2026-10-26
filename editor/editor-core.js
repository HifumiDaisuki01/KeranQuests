/* Shared pure authoring rules. Imported configuration is cloned and unknown fields are preserved. */
(function(root,factory){if(typeof module==='object')module.exports=factory();else root.KQ=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
'use strict';
const clone=x=>JSON.parse(JSON.stringify(x));
const list=x=>x==null?[]:Array.isArray(x)?x:[x];
const full=(tree,id)=>String(id).includes(':')?String(id):`${tree}:${id}`;
const types=['region_enter','region_stay','region_forbidden','mm_kill','player_kill','oraxen_item','item','npc_talk','custom_trigger','permission','wait'];
function parents(tree,q){return list(q.after??q.prerequisites?.quest_completed_all).map(x=>full(tree.id,x));}
function stages(q){return list(q.stages??q.steps).map((s,i)=>{
 s=clone(s);s.id??=`stage_${i+1}`;s.name??=s.text??s.id;s.requirements=list(s.requirements);
 for(const [key,type,field]of[['talk','npc_talk','npc'],['trigger','custom_trigger','key'],['wait','wait','seconds']])if(s[key]!=null){s.requirements.push({type,[field]:key==='wait'?seconds(s[key]):s[key]});delete s[key];}
 if(s.enter!=null){const p=String(s.enter).split(':');s.requirements.push(p.length>1?{type:'region_enter',world:p.shift(),region:p.join(':')}:{type:'region_enter',region:p[0]});delete s.enter;}
 if(s.submit){s.requirements.push({type:'item',item:s.submit.item,count:s.submit.count??1,consume:true});delete s.submit;}
 if(s.stay){const p=String(s.stay.region).split(':');s.requirements.push({type:'region_stay',region:p.length>1?p.slice(1).join(':'):p[0],...(p.length>1?{world:p[0]}:{}),seconds:seconds(s.stay.time??'3s')});delete s.stay;}
 return s;
});}
function seconds(x){if(typeof x==='number')return x;const m=String(x).match(/^(\d+)([smh]?)$/);if(!m)throw Error('时间应写为 30s、5m 或 1h');return +m[1]*({s:1,m:60,h:3600,'':1}[m[2]]);}
function applyDefaults(target,defaults){for(const[key,value]of Object.entries(defaults)){if(target[key]===undefined)target[key]=clone(value);else if(value&&typeof value==='object'&&!Array.isArray(value)&&target[key]&&typeof target[key]==='object'&&!Array.isArray(target[key]))applyDefaults(target[key],value);}}
function normalize(input){const t=clone(input);if(!t||typeof t!=='object'||Array.isArray(t)||!t.quests||Array.isArray(t.quests))throw Error('需要包含 id、name、quests 的任务树配置');t.id??='my_story';t.name??=t.id;for(const q of Object.values(t.quests)){applyDefaults(q,t.defaults??{});q.stages=stages(q);delete q.steps;}return t;}
function validate(t,dialogues={}){
 const errors=[],warnings=[];const add=(quest,text)=>errors.push({quest,text});
 if(!/^[A-Za-z0-9_-]+$/.test(t.id??''))add(null,'任务树 ID 仅允许字母、数字、下划线和连字符');
 const qs=t.quests??{};if(!Object.keys(qs).length)add(null,'任务树至少需要一个任务');
 for(const[id,q]of Object.entries(qs)){
  if(!/^[A-Za-z0-9_-]+$/.test(id))add(id,'任务 ID 格式错误');
  let ss;try{ss=stages(q);}catch(e){add(id,e.message);continue;}
  if(!ss.length)add(id,'至少需要一个阶段');const ids=new Set();
  for(const s of ss){if(ids.has(s.id))add(id,`阶段 ID 重复：${s.id}`);ids.add(s.id);
   if(s.mode==='ANY'&&(s.need??1)>s.requirements.length)add(id,`${s.id} 的 need 超过要求数`);
   for(const r of s.requirements){const type=String(r.type??'').toLowerCase();if(!types.includes(type)){add(id,`${s.id} 未知要求类型：${r.type}`);continue;}
    const field=type.startsWith('region_')?'region':['item','oraxen_item'].includes(type)?'item':({npc_talk:'npc',custom_trigger:'key',permission:'permission'})[type];
    if(field&&!(r[field]??r[field+'_name']))add(id,`${s.id} 缺少 ${field}`);
    if(r.count!=null&&!(r.count>0))add(id,`${s.id} 的 count 必须 > 0`);
    if(r.seconds!=null&&!(r.seconds>=0))add(id,`${s.id} 的 seconds 必须 >= 0`);
   }
   if((s.choice||String(s.on_complete).toUpperCase()==='CHOICE')&&!s.choices?.length)add(id,`${s.id} 抉择没有选项`);
   for(const c of s.choices??[])ref(id,c.quest);
  }
  for(const p of parents(t,q))ref(id,p);
 }
 function ref(id,value){if(!value)return add(id,'存在空任务引用');const f=full(t.id,value),prefix=`${t.id}:`;if(f.startsWith(prefix)){if(!qs[f.slice(prefix.length)])add(id,`引用不存在的任务：${f}`);}else warnings.push({quest:id,text:`跨任务树引用需在服务器校验：${f}`});}
 const done=new Set(),path=new Set();function visit(id){if(done.has(id))return;if(path.has(id)){add(id,'任务前置形成循环：'+[...path,id].join(' → '));return;}path.add(id);if(qs[id]?.prerequisites?.mode!=='ANY')for(const p of parents(t,qs[id])){const prefix=`${t.id}:`;if(p.startsWith(prefix)&&qs[p.slice(prefix.length)])visit(p.slice(prefix.length));}path.delete(id);done.add(id);}
 for(const id of Object.keys(qs))visit(id);
 for(const[id,d]of Object.entries(dialogues)){if(!d.npc)errors.push({dialog:id,text:`对话 ${id} 缺少 NPC`});for(const n of d.nodes??[]){if(!n.say&&!n.ask&&!n.choose)errors.push({dialog:id,text:`对话 ${id} 有未知节点`});for(const o of n.choose?.options??[])for(const key of ['quest','complete_quest'])if(o[key])ref(null,o[key]);}}
 return{errors,warnings};
}
function concise(input){const t=clone(input);for(const[id,q]of Object.entries(t.quests)){
 if(q.type===(t.defaults?.type??'SIDE'))delete q.type;if(q.stage_visibility===(t.defaults?.stage_visibility??'SEQUENTIAL'))delete q.stage_visibility;
 if(!t.defaults?.abandon&&q.abandon&&q.abandon.allowed===true&&q.abandon.cooldown===0&&!q.abandon.commands&&!q.abandon.message)delete q.abandon;
 if(q.prerequisites?.quest_completed_all&&Object.keys(q.prerequisites).length===1){q.after=q.prerequisites.quest_completed_all.map(x=>String(x).startsWith(t.id+':')?String(x).slice(t.id.length+1):x);delete q.prerequisites;}
 q.steps=stages(q).map(s=>{if(s.mode==='ALL')delete s.mode;if(!s.description)delete s.description;if(s.name===s.id)delete s.name;
 const rr=s.requirements;if(rr.length===1&&Object.keys(rr[0]).every(k=>['type','region','world','npc','key','seconds'].includes(k))){const r=rr[0];switch(r.type){case'region_enter':s.enter=(r.world?r.world+':':'')+r.region;break;case'npc_talk':s.talk=r.npc;break;case'custom_trigger':s.trigger=r.key;break;case'wait':s.wait=`${r.seconds}s`;break;}if(s.enter!=null||s.talk!=null||s.trigger!=null||s.wait!=null)delete s.requirements;}
 return s;});delete q.stages;
 }return t;}
function layout(t){const nodes=[],memo={},counts={};function depth(id,path=new Set()){if(memo[id]!=null)return memo[id];if(path.has(id))return 0;path.add(id);let n=0;for(const p of parents(t,t.quests[id])){const key=p.slice(t.id.length+1);if(p.startsWith(t.id+':')&&t.quests[key])n=Math.max(n,Math.min(100,depth(key,path)+1));}path.delete(id);return memo[id]=n;}
 for(const[id,q]of Object.entries(t.quests)){const d=depth(id),row=counts[d]??0;counts[d]=row+1;nodes.push({id,q,x:36+d*245,y:35+row*110,parents:parents(t,q)});}return nodes;}
return{clone,list,full,types,parents,stages,seconds,normalize,validate,concise,layout};
});
