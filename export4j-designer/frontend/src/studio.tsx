// SPDX-License-Identifier: Apache-2.0
import React,{useEffect,useLayoutEffect,useMemo,useRef,useState,useSyncExternalStore} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {createPortal} from 'react-dom';
import type {EditorView} from 'prosemirror-view';
import {TextSelection} from 'prosemirror-state';
import {DOMSerializer} from 'prosemirror-model';
import {at,xml,serialize,fixedBox,positionable,hitFor,textTags,flowPath,fixedPath,parentPath,reorder,type Bridge,type Snapshot,type Change,type Hit} from './types';
import {geometry} from './geometry';
import {mountRich,toXml,mark,caret,editorDocument,schema,createRichSession,sessionToXml,activeMarks} from './rich';
import {blockKinds,insertBlock,duplicateBlocks,contiguousText,snapMove,pathOf,type BlockKind,type SnapGuide} from './blocks';

let bridge:Bridge,snapshot:Snapshot,mode:'convenient'|'precise'='convenient',activeEditors=0;
const listeners=new Set<()=>void>(),roots=new Map<HTMLElement,Root>();
const subscribe=(listener:()=>void)=>{listeners.add(listener);return ()=>{listeners.delete(listener);};};
const read=()=>snapshot;
const refresh=()=>listeners.forEach(listener=>listener());
export function sync(){if(!bridge)return;snapshot=bridge.snapshot();refresh();}
function useDocument(){return useSyncExternalStore(subscribe,read);}
function fail(error:unknown){bridge.error(error instanceof Error?error.message:String(error));}
async function applyGeometry(changes:Change[]){try{const result=geometry(bridge.snapshot(),changes);await bridge.apply(result.source,result.ids);}catch(error){fail(error);}}
async function sortSelection(paths:string[],target:string,after:boolean){try{const current=bridge.snapshot(),result=reorder(current,paths,target,after);if(result.source!==serialize(xml(current.source)))await bridge.apply(result.source,undefined,result.paths);}catch(error){fail(error);}}
function flowSelection(state:Snapshot):string[]{
  let document:Document;try{document=xml(state.source);}catch{return [];}
  const paths=[...new Set(state.selectedPaths.map(path=>{try{return flowPath(document,path);}catch{return null;}}).filter((path):path is string=>path!==null))];
  return paths.filter(path=>!paths.some(parent=>path.startsWith(parent+'/')));
}
function sortStep(state:Snapshot,direction:number){
  const paths=flowSelection(state);if(!paths.length||paths.some(path=>parentPath(path)!==parentPath(paths[0])))return;
  const document=xml(state.source),siblings=state.entries.filter(entry=>parentPath(entry.path)===parentPath(paths[0])&&flowPath(document,entry.path)===entry.path).map(entry=>entry.path);
  const indices=paths.map(path=>siblings.indexOf(path)),edge=direction<0?Math.min(...indices):Math.max(...indices),target=siblings[edge+direction];
  if(target)void sortSelection(paths,target,direction>0);
}
function Toolbar(){
  const state=useDocument(),editingMode=mode;
  return <><div className="studio-tools">
    <span className="studio-interaction-hint" title="单击选择；双击或 Enter / F2 编辑。拖柄可直接拖动，正文内长按也可拖动。组件的排版属性决定排序或自由移动。">单击选择 · 双击编辑 · 拖柄移动</span>
    <span className="studio-selection-count">{state.selectedPaths.length>1?state.selectedPaths.length+' 个对象':''}</span>
    <span className={'studio-sync-state'+(state.pending?' is-pending':'')} role="status">{state.pending?'正在校准版式':'版式已同步'}</span>
  </div><div className="studio-modes" role="group" aria-label="编辑模式">
    <button aria-pressed={editingMode==='convenient'} title="浏览器即时编辑，直接显示模板变量；版式为近似效果，完成编辑后更新引擎布局" onClick={()=>{mode='convenient';sync();}}>便捷编辑</button>
    <button aria-pressed={editingMode==='precise'} title="Java 引擎实时排版，显示测试数据绑定后的真实换行、分页与输出效果" onClick={()=>{mode='precise';sync();bridge.preview();}}>精确排版</button>
    <span className="studio-mode-note" role="status">{editingMode==='convenient'?'模板内容 · 近似显示':'引擎结果 · 实际分页'}</span>
  </div></>;
}
export function shouldPreview(){return mode==='precise'||activeEditors===0;}
function paragraphStyle(node:Element,defaults:Record<string,string>,scale:number):React.CSSProperties {
  const value=(name:string,fallback:string)=>node.getAttribute(name)??defaults[name]??fallback;
  return {'--studio-scale':scale,fontSize:Number(value('size','12'))*scale,lineHeight:Number(value('line-height','18'))*scale+'px',
    color:value('color','#000'),fontWeight:value('role','')==='bold'?'bold':'normal',textAlign:value('align','left').toLowerCase()} as React.CSSProperties;
}
function TextPreview({node,scale,defaults}:{node:Element;scale:number;defaults:Record<string,string>}){
  const host=useRef<HTMLDivElement>(null),source=serialize(node);
  useLayoutEffect(()=>{host.current!.replaceChildren(DOMSerializer.fromSchema(schema).serializeFragment(editorDocument(node).content));},[source]);
  return <div ref={host} aria-hidden="true" className="studio-text-preview" style={paragraphStyle(node,defaults,scale)}/>;
}
function Numeric({label,value,onChange,disabled=false}:{label:string;value:string;onChange:(value:number)=>void;disabled?:boolean}){
  const [draft,setDraft]=useState(value);useEffect(()=>setDraft(value),[value]);
  return <label>{label}<input type="number" step="1" value={draft} disabled={disabled}
    onChange={event=>setDraft(event.target.value)} onBlur={()=>{if(draft!==value&&draft.trim()!==''&&Number.isFinite(Number(draft)))onChange(Number(draft));}}
    onKeyDown={event=>{if(event.key==='Enter')event.currentTarget.blur();if(event.key==='Escape')setDraft(value);}}/></label>;
}
function Inspector(){
  const state=useDocument();
  if(!state.selectedPaths.length)return null;
  let nodes:Element[],document:Document;try{document=xml(state.source);nodes=state.selectedPaths.map(path=>at(document,path));}catch{return null;}
  const boxes=nodes.map(fixedBox),allFixed=boxes.every(Boolean),placed=hitFor(state,state.selectedPaths[0],state.currentPage)??hitFor(state,state.selectedPaths[0]);
  const flow=flowSelection(state),allFlow=nodes.every(node=>!fixedBox(node))&&flow.length>0,canFix=nodes.every(positionable);
  if(!placed||(!canFix&&!allFlow))return null;
  const common=(name:string)=>{const values=nodes.map((node,index)=>name==='rotation'?(node.tagName==='box'?node.firstElementChild:node)?.getAttribute(name)??'0':boxes[index]?.getAttribute(name)??String((hitFor(state,state.selectedPaths[index])?.hit as any)?.[name]??''));return values.every(value=>value===values[0])?values[0]??'':'';};
  const change=(value:Partial<Change>)=>applyGeometry(state.selectedPaths.map(path=>({path,...value})));
  const convert=(position:'FLOW'|'FIXED')=>change({position,page:placed.page});
  return <details className="studio-inspector" open><summary>位置与尺寸 <small>pt</small></summary>
    <label>排版方式<select aria-label="块排版方式" disabled={state.busy} value={allFixed?'FIXED':allFlow?'FLOW':''} onChange={event=>convert(event.target.value as 'FLOW'|'FIXED')}>
      {!allFixed&&!allFlow&&<option value="">多个值</option>}<option value="FLOW">自动占位</option><option value="FIXED" disabled={!canFix}>固定位置</option></select></label>
    {allFlow&&<><p className="studio-property-hint">按正文顺序占位，前后内容自动补齐。拖动显示插入线，不改变排版属性。</p>
      <div className="studio-order"><button disabled={state.busy} onClick={()=>sortStep(state,-1)} title="Alt + ↑">上移组件</button><button disabled={state.busy} onClick={()=>sortStep(state,1)} title="Alt + ↓">下移组件</button></div>
      {!canFix&&<p className="studio-property-hint">动态表格、列表和循环按整体排序，保留数据绑定。</p>}</>}
    {allFixed&&<><div className="studio-number-grid">{(['x','y','width','height'] as const).map((name,index)=><Numeric key={name} label={['X','Y','宽度','高度'][index]} value={common(name)} disabled={state.busy} onChange={value=>change({[name]:value,...(name==='width'?{widthMode:'FIXED'}:name==='height'?{heightMode:'FIXED'}:{})})}/>)}</div>
      <label>正文相遇时<select aria-label="正文相遇时" disabled={state.busy} value={boxes.every(box=>(box?.getAttribute('body-flow')||'OVERLAP')===(boxes[0]?.getAttribute('body-flow')||'OVERLAP'))?boxes[0]?.getAttribute('body-flow')||'OVERLAP':''} onChange={event=>change({bodyFlow:event.target.value as 'OVERLAP'|'AVOID'})}><option value="" disabled>多个值</option><option value="OVERLAP">遮盖正文</option><option value="AVOID">上下避让</option></select></label>
      <p className="studio-property-hint">{boxes[0]?.getAttribute('body-flow')==='AVOID'?'正文保留对象上方空间，遇到对象时移至其下方继续排版。':'对象不占正文空间，允许重叠；层级决定谁显示在前。'}</p>
      <label>宽度规则<select aria-label="宽度规则" value={boxes[0]?.getAttribute('width-mode')||'FIXED'} onChange={event=>change({widthMode:event.target.value})}><option value="FIXED">固定宽度</option><option value="FILL">填满右侧剩余空间</option></select></label>
      <label>高度规则<select aria-label="高度规则" value={boxes[0]?.getAttribute('height-mode')||'FIXED'} onChange={event=>change({heightMode:event.target.value})}><option value="FIXED">固定高度</option><option value="AUTO">随内容自动增高</option><option value="FILL">填满下方剩余空间</option></select></label>
      <Numeric label="物理页（0 为每页）" value={boxes[0]?.getAttribute('page')||'0'} onChange={value=>change({page:value-1})}/>
      <div className="studio-order"><button onClick={()=>layerOrder(state,false)}>置于底层</button><button onClick={()=>layerOrder(state,true)}>置于顶层</button></div>
    </>}
    {canFix&&<Numeric label="旋转角度（°）" value={common('rotation')} disabled={state.busy} onChange={value=>change({rotation:value})}/>}
    {allFixed&&<div className="studio-align" role="group" aria-label="对齐对象">{[['left','左对齐'],['top','顶对齐'],['center','水平居中'],['middle','垂直居中']].map(([mode,label])=><button key={mode} disabled={state.busy} onClick={()=>align(state,mode)}>{label}</button>)}</div>}
  </details>;
}
function align(state:Snapshot,mode:string){
  const placed=state.selectedPaths.map(path=>({path,...hitFor(state,path)})).filter(item=>item.hit) as {path:string;hit:Hit;page:number}[];
  if(!placed.length)return;
  const page=state.pages[placed[0].page],left=Math.min(...placed.map(item=>item.hit.x)),top=Math.min(...placed.map(item=>item.hit.y));
  void applyGeometry(placed.map(item=>({path:item.path,...(mode==='left'?{x:left}:mode==='top'?{y:top}:mode==='center'?{x:(page.width-item.hit.width)/2}:{y:(page.height-item.hit.height)/2})})));
}
function layerOrder(state:Snapshot,front:boolean){
  try{const document=xml(state.source);for(const path of state.selectedPaths){const box=fixedBox(at(document,path));if(box){if(front)box.parentElement!.append(box);else box.parentElement!.prepend(box);}}void bridge.apply(serialize(document));}catch(error){fail(error);}
}
function removeSelection(){
  try{const state=bridge.snapshot(),document=xml(state.source),roots=state.selectedPaths.filter(Boolean).map(path=>{const node=at(document,path),flow=flowPath(document,path);return fixedBox(node)??(flow?at(document,flow):node);});
    const nodes=[...new Set(roots)].filter(node=>!roots.some(other=>other!==node&&other.contains(node)));
    for(const node of nodes){const parent=node.parentElement;node.remove();if(parent&&['foreground','background'].includes(parent.tagName)&&!parent.children.length)parent.remove();}
    void bridge.apply(serialize(document));
  }catch(error){fail(error);}
}
interface Editing {path:string;node:Element;paths:string[];nodes:Element[];bounds:Hit;index:number;replace?:boolean;continuation?:boolean;point?:{x:number;y:number}}
function Rich({editing,page,scale,finish}:{editing:Editing;page:number;scale:number;finish:()=>void}){
  const host=useRef<HTMLDivElement>(null),view=useRef<EditorView|null>(null),ids=useRef<string[]>([editing.node.tagName==='cell'?'':editing.node.getAttribute('id')||'edit-'+crypto.randomUUID()]);
  const session=useRef(useMemo(()=>['p','heading'].includes(editing.node.tagName)&&!fixedBox(editing.node)?createRichSession(editing.nodes,Object.fromEntries(editing.nodes.map((node,index)=>[node.getAttribute('id')||String(index),snapshot.formats[editing.paths[index]]||{}]))):undefined,[]));
  const currentIds=useRef<string[]|null>(null),[toolbar,setToolbar]=useState<{left:number;top:number}|null>(null),[marks,setMarks]=useState<Record<string,string|boolean>>({});
  const authored=useRef(bridge.snapshot().source);
  const changed=(editor:EditorView)=>{
    try{
      const document=xml(bridge.snapshot().source);
      const targets=session.current?(currentIds.current?currentIds.current.map(id=>[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===id)).filter((node):node is Element=>!!node):editing.paths.map(path=>at(document,path))):[(ids.current[0]?[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===ids.current[0]):undefined)??at(document,editing.path)];
      if(!targets.length)throw new Error('编辑的内容已变化，请完成本次编辑后重新选择。');
      if(session.current)for(const target of targets){const key=target.getAttribute('id');if(key)session.current.originals.set(key,target.cloneNode(true) as Element);}
      const blocks=session.current?sessionToXml(editor,session.current):toXml(editor,editing.node,ids.current);
      for(const block of blocks){
        const size=Math.max(Number(block.getAttribute('size')||12),...[...block.querySelectorAll('span[size]')].map(span=>Number(span.getAttribute('size'))));
        if(size>Number(block.getAttribute('line-height')||18))block.setAttribute('line-height',String(size*1.5));
      }
      if(!session.current)for(const sibling of [...targets[0].parentElement!.children])if(sibling!==targets[0]&&ids.current.slice(1).includes(sibling.getAttribute('id')||''))sibling.remove();
      targets[0].replaceWith(...blocks.map(block=>document.importNode(block,true)));for(const target of targets.slice(1))target.remove();
      currentIds.current=blocks.map(block=>block.getAttribute('id')||'').filter(Boolean);authored.current=serialize(document);bridge.draft(authored.current);
    }catch(error){fail(error);}
  };
  const selectionChanged=(editor:EditorView)=>{
    setMarks(activeMarks(editor));
    if(editor.state.selection.empty){setToolbar(null);return;}
    try{
      const start=editor.coordsAtPos(editor.state.selection.from),end=editor.coordsAtPos(editor.state.selection.to);
      setToolbar({left:Math.max(8,Math.min(window.innerWidth-340,(start.left+end.left)/2-150)),top:Math.max(72,Math.min(start.top,end.top)-48)});
    }catch{setToolbar(null);}
  };
  const commit=()=>{
    const source=bridge.snapshot().source,document=xml(source),original=editing.nodes[editing.index],key=currentIds.current?view.current?.state.selection.$from.parent.attrs.key:original.getAttribute('id');
    const active=key?[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===key):undefined;
    finish();void bridge.apply(source,active?[key]:undefined,active?undefined:[editing.paths[editing.index]]).catch(fail);
  };
  useLayoutEffect(()=>{
    activeEditors++;
    const editor=mountRich(host.current!,editing.node,{changed,editable:()=>!bridge.snapshot().busy,session:session.current,selectionChanged,paste:file=>{
      changed(editor);const key=editor.state.selection.$from.parent.attrs.key,document=xml(bridge.snapshot().source),node=key?[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===key):undefined;
      finish();void bridge.paste(file,page,20,20,node?pathOf(node):editing.path);
    },commit,inlineOnly:editing.node.tagName==='cell'||!!fixedBox(editing.node),merge:()=>false});
    view.current=editor;
    if(editing.point)caret(editor,editing.point.x,editing.point.y);else{
      let position=1;for(let index=0;index<editing.index;index++)position+=editor.state.doc.child(index).nodeSize;
      editor.focus();editor.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc,position,editing.replace?position+editor.state.doc.child(editing.index).content.size:position)));
    }
    return ()=>{activeEditors--;view.current=null;editor.destroy();};
  },[]);
  useLayoutEffect(()=>{view.current?.setProps({editable:()=>!bridge.snapshot().busy});},[snapshot.busy]);
  useEffect(()=>{
    let frame=0;
    const reposition=()=>{cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{if(view.current)selectionChanged(view.current);});};
    window.addEventListener('resize',reposition);window.addEventListener('scroll',reposition,true);
    return ()=>{cancelAnimationFrame(frame);window.removeEventListener('resize',reposition);window.removeEventListener('scroll',reposition,true);};
  },[]);
  useLayoutEffect(()=>{if(bridge.snapshot().source!==authored.current)finish();},[snapshot.source]);
  return <div className="studio-rich" onPointerDown={event=>event.stopPropagation()} style={session.current?{'--studio-scale':scale,fontSize:12*scale,lineHeight:18*scale+'px'} as React.CSSProperties:paragraphStyle(editing.node,snapshot.formats[editing.path]||{},scale)}>
    <div className="studio-edit-actions"><span>{editing.continuation?'跨页段落 · 编辑完整原文':'正在编辑 · Ctrl + Enter 完成'}</span><button onClick={commit}>完成</button></div>
    {toolbar&&createPortal(<div className="studio-rich-toolbar studio-floating-toolbar" role="toolbar" aria-label="画布文字格式" style={toolbar} onPointerDown={event=>{if((event.target as Element).closest('button'))event.preventDefault();}}>
      <button aria-label="粗体" aria-pressed={!!marks.strong} onClick={()=>view.current&&mark(view.current,'strong')}>B</button><button aria-label="下划线" aria-pressed={!!marks.underline} onClick={()=>view.current&&mark(view.current,'underline')}>U</button><button aria-label="删除线" aria-pressed={!!marks.strike} onClick={()=>view.current&&mark(view.current,'strike')}>S</button>
      <input type="color" aria-label="画布文字颜色" value={String(marks.color||'#000000')} onChange={event=>view.current&&mark(view.current,'color',event.target.value)}/>
      <select aria-label="画布文字字号" value="" onChange={event=>{if(view.current&&event.target.value)mark(view.current,'size',event.target.value);}}><option value="">字号</option>{[8,10,12,14,16,18,20,24,28,36,48].map(size=><option key={size}>{size}</option>)}</select>
    </div>,window.document.body)}<div ref={host}/>
  </div>;
}
interface Gesture {kind:string;startX:number;startY:number;paths:string[];rects:{path:string;hit:Hit;rotation:number}[];pointer:number;scale:number;width:number;height:number}
interface PointerStart {clientX:number;clientY:number;pointerId:number;currentTarget:HTMLElement}
interface Press extends PointerStart {timer:ReturnType<typeof setTimeout>}
interface Drop {path:string;page:number;after:boolean;x:number;y:number;width:number;label:string;paper:HTMLElement}
interface BlockMenu {kind:'insert'|'actions';path?:string;after:boolean;left:number;top:number}
function PageEditor({page,paper}:{page:number;paper:HTMLElement}){
  const state=useDocument(),model=state.pages[page],surface=useRef<HTMLDivElement>(null);
  const [editing,setEditing]=useState<Editing|null>(null),[gesture,setGesture]=useState<Gesture|null>(null),[delta,setDelta]=useState({x:0,y:0}),[marquee,setMarquee]=useState<{x:number;y:number;endX:number;endY:number}|null>(null);
  const [drop,setDrop]=useState<Drop|null>(null);
  const [menu,setMenu]=useState<BlockMenu|null>(null),[guides,setGuides]=useState<SnapGuide[]>([]);
  const [blankOrigin,setBlankOrigin]=useState<{x:number;y:number}|null>(null);
  const editingLayer=useRef<HTMLDivElement>(null),[expansion,setExpansion]=useState(0);
  const pointer=useRef<{x:number;y:number}|null>(null);
  const ignoreClick=useRef(false),press=useRef<Press|null>(null),scale=paper.getBoundingClientRect().width/(model?.width||1);
  const cancelPress=()=>{if(press.current){clearTimeout(press.current.timer);press.current=null;}};
  const cancelGesture=()=>{cancelPress();setGesture(null);setDrop(null);setMarquee(null);setGuides([]);ignoreClick.current=true;};
  useEffect(()=>cancelPress,[]);
  useEffect(()=>{if(editing&&!state.selectedPaths.some(path=>editing.paths.includes(path))){setEditing(null);}},[state.selectedPaths.join('|')]);
  useEffect(()=>{cancelPress();setEditing(null);setMenu(null);ignoreClick.current=false;},[state.session]);
  useEffect(()=>{const finish=()=>{setEditing(null);setMenu(null);};window.document.addEventListener('designer-history',finish);return ()=>window.document.removeEventListener('designer-history',finish);},[]);
  useEffect(()=>{const other=(event:Event)=>{if((event as CustomEvent).detail.page!==page)setEditing(null);};window.document.addEventListener('designer-edit-start',other);return ()=>window.document.removeEventListener('designer-edit-start',other);},[page]);
  useLayoutEffect(()=>{
    const element=editingLayer.current;
    if(!editing||!element){setExpansion(0);return;}
    const measure=()=>{const extra=Math.max(0,element.getBoundingClientRect().height/scale-editing.bounds.height);setExpansion(previous=>Math.abs(previous-extra)>.25?extra:previous);};
    const observer=new ResizeObserver(measure);observer.observe(element);measure();return ()=>observer.disconnect();
  },[editing,scale]);
  useEffect(()=>{
    if(!menu)return;
    window.document.querySelector<HTMLButtonElement>('.studio-block-menu [role="menuitem"]')?.focus({preventScroll:true});
    const close=(event:Event)=>{if(!(event.target as Element).closest('.studio-block-menu,.studio-block-action'))setMenu(null);};
    const key=(event:KeyboardEvent)=>{
      if(event.key==='Escape'){event.preventDefault();setMenu(null);surface.current?.focus();}
      if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){
        const buttons=[...window.document.querySelectorAll<HTMLButtonElement>('.studio-block-menu [role="menuitem"]')],index=buttons.indexOf(window.document.activeElement as HTMLButtonElement);
        if(buttons.length){event.preventDefault();buttons[event.key==='Home'?0:event.key==='End'?buttons.length-1:(index+(event.key==='ArrowUp'?-1:1)+buttons.length)%buttons.length].focus();}
      }
    };
    window.document.addEventListener('pointerdown',close);window.document.addEventListener('keydown',key);
    return ()=>{window.document.removeEventListener('pointerdown',close);window.document.removeEventListener('keydown',key);};
  },[menu]);
  let document:Document|undefined;try{document=xml(state.source);}catch{/* Keep the last engine image for an invalid source draft. */}
  const editPath=(path:string,point?:{x:number;y:number},fallback?:Hit,replace=false)=>{
    const current=bridge.snapshot(),group=contiguousText(current,path,page),node=at(xml(current.source),path);
    const hits=group.paths.map(path=>hitFor(current,path,page)?.hit).filter((hit):hit is Hit=>!!hit),first=hits[0]??fallback;
    if(!first||!textTags.has(node.tagName))return;
    const x=Math.min(...(hits.length?hits:[first]).map(hit=>hit.x)),y=Math.min(...(hits.length?hits:[first]).map(hit=>hit.y));
    const bounds={...first,x,y,width:Math.max(...(hits.length?hits:[first]).map(hit=>hit.x+hit.width))-x,height:Math.max(...(hits.length?hits:[first]).map(hit=>hit.y+hit.height))-y};
    const continuation=['p','heading'].includes(node.tagName)&&current.pages.filter(model=>model.hits.some(hit=>hit.path===path)).length>1;
    window.document.dispatchEvent(new CustomEvent('designer-edit-start',{detail:{page}}));
    setMenu(null);setEditing({path:group.paths[0],node:group.nodes[0],paths:group.paths,nodes:group.nodes,bounds,index:group.paths.indexOf(path),replace,continuation,point:continuation?undefined:point});
  };
  const openMenu=(event:React.MouseEvent<HTMLButtonElement>,kind:BlockMenu['kind'],path?:string,after=true)=>{
    event.preventDefault();event.stopPropagation();const bounds=event.currentTarget.getBoundingClientRect();
    setMenu(previous=>previous?.kind===kind&&previous.path===path&&previous.after===after?null:{kind,path,after,left:Math.max(8,Math.min(window.innerWidth-224,bounds.left)),top:Math.max(64,Math.min(window.innerHeight-310,bounds.bottom+5))});
  };
  const insert=(kind:BlockKind)=>{
    if(!menu)return;
    try{
      const current={...bridge.snapshot(),currentPage:page},result=insertBlock(current,menu.path,menu.after,kind),placed=menu.path?hitFor(current,menu.path,page)?.hit:undefined;
      setMenu(null);void bridge.apply(result.source,result.ids,result.paths).then(()=>{
        if(kind==='p'||kind==='heading')editPath(result.paths[0],undefined,{id:result.ids[0],path:result.paths[0],x:placed?.x??30,y:placed?(menu.after?placed.y+placed.height+8:placed.y):30,width:placed?.width??model.width-60,height:24,images:[]},true);
      }).catch(fail);
    }catch(error){fail(error);}
  };
  const duplicate=()=>{try{const result=duplicateBlocks(bridge.snapshot());setMenu(null);void bridge.apply(result.source,result.ids,result.paths).catch(fail);}catch(error){fail(error);}};
  const open=async(hit:Hit,event:React.MouseEvent,edit=false)=>{
    const rootPath=document&&!edit?(flowPath(document,hit.path)??fixedPath(document,hit.path)):null;
    if(event.ctrlKey||event.metaKey||event.shiftKey){await bridge.choose(rootPath??hit.path,event);return;}
    if(editing?.path===hit.path)return;
    if(editing){setEditing(null);await bridge.apply(bridge.snapshot().source);}
    const path=rootPath??bridge.snapshot().entries.find(entry=>entry.id&&entry.id===hit.id.split('@')[0])?.path??hit.path;
    await bridge.choose(path,undefined,!edit);let node:Element;try{node=at(xml(bridge.snapshot().source),path);}catch{return;}
    if(textTags.has(node.tagName)&&edit)editPath(path,{x:event.clientX,y:event.clientY},hit);
    else surface.current?.focus({preventScroll:true});
  };
  const startGesture=(start:PointerStart,kind:string,hit:Hit)=>{
    const current=bridge.snapshot();if(current.busy||!start.currentTarget.isConnected)return;
    const document=xml(current.source),flow=flowPath(document,hit.path),selectedFlow=flowSelection(current);
    const paths=flow?(selectedFlow.includes(flow)?selectedFlow:[flow]):current.selectedPaths.includes(hit.path)?current.selectedPaths:[hit.path];
    if(flow&&kind==='move')kind='sort';
    if(kind==='sort'&&paths.some(path=>parentPath(path)!==parentPath(paths[0]))){fail(new Error('跨容器组件不能一起排序，请选择同级组件。'));return;}
    const rects=paths.map(path=>{const value=hitFor(current,path,page);const node=at(document,path);return value?{path,hit:value.hit,rotation:Number((node.tagName==='box'?node.firstElementChild:node)?.getAttribute('rotation')||0)}:null;}).filter(Boolean) as Gesture['rects'];
    if(!rects.length||kind!=='sort'&&!paths.every(path=>!!fixedBox(at(document,path))))return;
    if(paths.join('|')!==current.selectedPaths.join('|'))void bridge.select(paths);
    setEditing(null);setGesture({kind,paths,startX:start.clientX,startY:start.clientY,rects,pointer:start.pointerId,scale,width:model.width,height:model.height});setDelta({x:0,y:0});
    start.currentTarget.setPointerCapture(start.pointerId);
  };
  const begin=(event:React.PointerEvent<HTMLElement>,kind:string,hit:Hit)=>{
    if(event.button!==0||state.busy)return;event.preventDefault();event.stopPropagation();
    cancelPress();ignoreClick.current=false;try{startGesture(event,kind,hit);}catch(error){fail(error);}
  };
  const hold=(event:React.PointerEvent<HTMLDivElement>,hit:Hit)=>{
    if(event.button!==0||state.busy||editing||event.ctrlKey||event.metaKey||event.shiftKey)return;
    cancelPress();ignoreClick.current=false;
    const current=bridge.snapshot();let document:Document;try{document=xml(current.source);}catch(error){fail(error);return;}
    // A cell edits independently; dragging moves the whole semantic table/control.
    const flow=flowPath(document,hit.path);
    const fixed=fixedPath(document,hit.path);
    const target=flow?hitFor(current,flow,page)?.hit:fixed&&hitFor(current,fixed,page)?.hit;
    if(!target)return;
    surface.current?.focus({preventScroll:true});
    const start:PointerStart={clientX:event.clientX,clientY:event.clientY,pointerId:event.pointerId,currentTarget:event.currentTarget};
    event.currentTarget.setPointerCapture(event.pointerId);
    press.current={...start,timer:setTimeout(()=>{press.current=null;ignoreClick.current=true;try{startGesture(start,'move',target);}catch(error){fail(error);}},350)};
  };
  const changeFor=(value:Gesture,item:Gesture['rects'][number],dx:number,dy:number):Change=>{
    const change:Change={path:item.path},hit=item.hit;
    if(value.kind==='move'){change.x=Math.max(0,Math.min(value.width-hit.width,hit.x+dx));change.y=Math.max(0,Math.min(value.height-hit.height,hit.y+dy));}
    else if(value.kind==='rotate'){
      const bounds=paper.getBoundingClientRect(),cx=bounds.left+(hit.x+hit.width/2)*value.scale,cy=bounds.top+(hit.y+hit.height/2)*value.scale;
      const angle=Math.atan2(value.startY+dy*value.scale-cy,value.startX+dx*value.scale-cx)*180/Math.PI+90;
      change.rotation=Math.max(-180,Math.min(180,Math.round(angle/5)*5));
    }else{
      const ratio=hit.width/hit.height;
      let width=hit.width+(value.kind.includes('w')?-dx:value.kind.includes('e')?dx:0),height=hit.height+(value.kind.includes('n')?-dy:value.kind.includes('s')?dy:0);
      if(value.kind.length===2){height=width/ratio;}
      width=Math.max(12,Math.min(width,value.kind.includes('w')?hit.x+hit.width:value.width-hit.x));
      height=Math.max(12,Math.min(height,value.kind.includes('n')?hit.y+hit.height:value.height-hit.y));
      change.width=width;change.height=height;change.widthMode='FIXED';change.heightMode='FIXED';
      if(value.kind.includes('w'))change.x=Math.max(0,hit.x+hit.width-width);if(value.kind.includes('n'))change.y=Math.max(0,hit.y+hit.height-height);
    }return change;
  };
  const findDrop=(clientX:number,clientY:number,value:Gesture):Drop|null=>{
    if(value.kind!=='sort'||!document)return null;
    const selected=value.paths,parent=parentPath(selected[0]),candidates:Drop[]=[];
    const papers=[...window.document.querySelectorAll<HTMLElement>('#pages .page')];
    for(const targetPaper of papers){
      const pageIndex=Number(targetPaper.closest<HTMLElement>('.canvas-sheet')?.dataset.page),bounds=targetPaper.getBoundingClientRect(),model=state.pages[pageIndex];
      if(!model||clientX<bounds.left-32||clientX>bounds.right+32||clientY<bounds.top||clientY>bounds.bottom)continue;
      const y=(clientY-bounds.top)*model.height/bounds.height;
      for(const entry of state.entries){
        if(parentPath(entry.path)!==parent||selected.includes(entry.path)||flowPath(document,entry.path)!==entry.path)continue;
        const hit=hitFor(state,entry.path,pageIndex)?.hit;if(!hit)continue;
        const after=y>hit.y+hit.height/2;
        candidates.push({path:entry.path,page:pageIndex,after,x:hit.x,y:after?hit.y+hit.height:hit.y,width:hit.width,label:entry.label,paper:targetPaper});
      }
      candidates.sort((a,b)=>Math.abs(a.y-y)-Math.abs(b.y-y));
      return candidates[0]??null;
    }return null;
  };
  useEffect(()=>{
    if(gesture?.kind!=='sort')return;
    let frame:number;
    const tick=()=>{
      const scroller=paper.closest<HTMLElement>('.canvas-scroll'),point=pointer.current,bounds=scroller?.getBoundingClientRect();
      if(scroller&&bounds&&point){
        const step=point.y>bounds.bottom-36?12:point.y<bounds.top+36?-12:0,before=scroller.scrollTop;
        if(step){scroller.scrollTop+=step;if(before!==scroller.scrollTop)setDrop(findDrop(point.x,point.y,gesture));}
      }
      frame=requestAnimationFrame(tick);
    };
    frame=requestAnimationFrame(tick);return ()=>cancelAnimationFrame(frame);
  },[gesture]);
  const moving=(event:React.PointerEvent)=>{
    pointer.current={x:event.clientX,y:event.clientY};
    if(press.current&&press.current.pointerId===event.pointerId&&Math.hypot(event.clientX-press.current.clientX,event.clientY-press.current.clientY)>6){cancelPress();ignoreClick.current=true;}
    if(gesture&&gesture.pointer===event.pointerId){
      const raw={x:(event.clientX-gesture.startX)/gesture.scale,y:(event.clientY-gesture.startY)/gesture.scale};
      if(gesture.kind==='move'&&!event.altKey){
        const targets=[{x:0,y:0,width:model.width,height:model.height},...model.hits.filter(hit=>!gesture.rects.some(item=>hit.path===item.path||hit.path.startsWith(item.path+'/')))];
        const snapped=snapMove(gesture.rects[0].hit,raw.x,raw.y,targets,5/gesture.scale);setDelta(snapped);setGuides(snapped.guides);
      }else{setDelta(raw);setGuides([]);}
      if(gesture.kind==='sort')setDrop(findDrop(event.clientX,event.clientY,gesture));
    }
    if(marquee){const bounds=paper.getBoundingClientRect();setMarquee({...marquee,endX:(event.clientX-bounds.left)/scale,endY:(event.clientY-bounds.top)/scale});}
  };
  const stop=(event:React.PointerEvent)=>{
    cancelPress();
    if(gesture&&gesture.pointer===event.pointerId){
      const value=gesture,rawX=(event.clientX-value.startX)/value.scale,rawY=(event.clientY-value.startY)/value.scale;
      const snap=value.kind==='move'&&!event.altKey?snapMove(value.rects[0].hit,rawX,rawY,[{x:0,y:0,width:model.width,height:model.height},...model.hits.filter(hit=>!value.rects.some(item=>hit.path===item.path||hit.path.startsWith(item.path+'/')))],5/value.scale):null;
      const dx=snap?.x??rawX,dy=snap?.y??rawY;setGesture(null);setDrop(null);setGuides([]);ignoreClick.current=Math.hypot(dx,dy)>.25;
      if(Math.hypot(dx,dy)>.25){if(value.kind==='sort'){const target=findDrop(event.clientX,event.clientY,value);if(target)void sortSelection(value.paths,target.path,target.after);}else void applyGeometry(value.rects.map(item=>changeFor(value,item,dx,dy)));}
    }
    if(marquee){
      const left=Math.min(marquee.x,marquee.endX),right=Math.max(marquee.x,marquee.endX),top=Math.min(marquee.y,marquee.endY),bottom=Math.max(marquee.y,marquee.endY);
      const paths=[...new Set(model.hits.filter(hit=>hit.x<right&&hit.y<bottom&&hit.x+hit.width>left&&hit.y+hit.height>top).map(hit=>hit.path).filter(Boolean))];
      void bridge.select(paths.filter(path=>!paths.some(other=>other!==path&&path.startsWith(other+'/'))));setMarquee(null);
    }
  };
  if(!model)return null;
  const isTail=(hit:Hit)=>{
    if(!editing||!document||hit.y<editing.bounds.y+editing.bounds.height-.1||editing.paths.includes(hit.path))return false;
    try{const node=at(document,hit.path);return !node.closest('header,footer,background,foreground');}catch{return false;}
  };
  const reflow=mode==='convenient'&&editing&&['document','section'].includes(editing.node.parentElement?.tagName||'')&&!fixedBox(editing.node)?expansion:0;
  const tailTop=editing?editing.bounds.y+editing.bounds.height:0,tailBottom=Math.max(tailTop,...model.hits.filter(isTail).map(hit=>hit.y+hit.height));
  const tailLimit=Math.min(model.height,...model.hits.filter(hit=>{try{return !!document&&!!at(document,hit.path).closest('footer');}catch{return false;}}).map(hit=>hit.y));
  const flowPaths=document?flowSelection(state):[],flowRects=flowPaths.map(path=>hitFor(state,path,page)?.hit).filter((hit):hit is Hit=>!!hit);
  const flowBounds=flowRects.length?{x:Math.min(...flowRects.map(hit=>hit.x)),y:Math.min(...flowRects.map(hit=>hit.y)),right:Math.max(...flowRects.map(hit=>hit.x+hit.width)),bottom:Math.max(...flowRects.map(hit=>hit.y+hit.height))}:null;
  const insertWidth=96/scale,insertHeight=25/scale,insertGap=8/scale,origin=blankOrigin??{x:30,y:30};
  const insertCandidates=[
    {x:origin.x+insertGap,y:origin.y+insertGap},
    {x:origin.x+insertGap,y:origin.y-insertHeight-insertGap},
    {x:origin.x-insertWidth-insertGap,y:origin.y+insertGap},
    ...[30,...model.hits.map(hit=>hit.y+hit.height+insertGap)].sort((a,b)=>a-b).map(y=>({x:30,y}))
  ];
  const insertPosition=insertCandidates.find(point=>point.x>=insertGap&&point.y>=insertGap&&point.x+insertWidth<=model.width-insertGap&&point.y+insertHeight<=model.height-insertGap&&!model.hits.some(hit=>point.x<hit.x+hit.width+insertGap&&point.x+insertWidth>hit.x-insertGap&&point.y<hit.y+hit.height+insertGap&&point.y+insertHeight>hit.y-insertGap));
  return <div className="studio-surface" ref={surface} tabIndex={0} data-gesture={gesture?.kind} aria-label={'第 '+(page+1)+' 页编辑层'} onPointerMove={moving} onPointerUp={stop} onPointerCancel={cancelGesture} onLostPointerCapture={()=>{cancelPress();setGesture(null);}}
    onKeyDown={event=>{
      if(event.key==='Escape'&&(gesture||press.current||marquee)){event.preventDefault();event.stopPropagation();cancelGesture();return;}
      if((event.target as Element).closest('.studio-rich,input,select,button'))return;
      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();cancelGesture();return;}
      if(['Enter','F2'].includes(event.key)&&state.selectedPaths.length===1){
        try{const path=state.selectedPaths[0],node=at(xml(state.source),path);
          if(textTags.has(node.tagName)){event.preventDefault();event.stopPropagation();editPath(path);}
        }catch(error){fail(error);}
      }
    }}
    onPaste={event=>{const file=[...event.clipboardData.files].find(file=>['image/png','image/jpeg'].includes(file.type));if(file){event.preventDefault();void bridge.paste(file,page,20,20);}}}>
    <div className="studio-background" onPointerDown={event=>{
      if(event.button!==0||state.busy)return;
      if(editing){setEditing(null);void bridge.apply(bridge.snapshot().source);}
      const bounds=paper.getBoundingClientRect(),x=(event.clientX-bounds.left)/scale,y=(event.clientY-bounds.top)/scale;
      setBlankOrigin({x,y});
      setMarquee({x,y,endX:x,endY:y});event.currentTarget.setPointerCapture(event.pointerId);
    }}/>
    {gesture&&gesture.kind!=='sort'&&gesture.rects.map(item=><div key={item.path} className="studio-source-mask" style={{left:item.hit.x/model.width*100+'%',top:item.hit.y/model.height*100+'%',width:item.hit.width/model.width*100+'%',height:item.hit.height/model.height*100+'%'}}/>)}
    {reflow>.25&&tailBottom>tailTop&&<>
      <div className="studio-tail-mask" style={{top:tailTop/model.height*100+'%',height:Math.max(0,Math.min(tailLimit-tailTop,tailBottom-tailTop+reflow))/model.height*100+'%'}}/>
      <div className="studio-flow-tail" data-reflow={reflow} style={{top:(tailTop+reflow)/model.height*100+'%',height:Math.max(0,Math.min(tailBottom-tailTop,tailLimit-tailTop-reflow))/model.height*100+'%',backgroundImage:`url("${paper.querySelector<HTMLImageElement>('.page-image')?.src||''}")`,backgroundSize:`${paper.getBoundingClientRect().width}px ${paper.getBoundingClientRect().height}px`,backgroundPosition:`0 ${-tailTop*scale}px`}}/>
    </>}
    {model.hits.filter(hit=>hit.path).map((hit,index)=>{
      if(editing?.paths.includes(hit.path))return null;
      const active=state.selectedPaths.includes(hit.path),value=gesture?.kind!=='sort'?gesture?.rects.find(item=>item.path===hit.path):undefined,change=value&&gesture?changeFor(gesture,value,delta.x,delta.y):null;
      const editingThis=false,x=change?.x??hit.x,y=change?.y??hit.y+(isTail(hit)?reflow:0),w=change?.width??hit.width,h=change?.height??hit.height;
      let source:Element|undefined;try{if(document)source=[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===hit.id.split('@')[0])??at(document,hit.path);}catch{/* Source may be midway through a paragraph split. */}
      return <div key={hit.path+':'+index} className={'studio-object'+(active&&!['bullet','numbered'].includes(source?.tagName||'')?' is-selected':'')+(editingThis?' is-editing':'')} data-studio-path={hit.path}
        style={{left:x/model.width*100+'%',top:y/model.height*100+'%',width:w/model.width*100+'%',height:h/model.height*100+'%',visibility:reflow>0&&isTail(hit)&&y>=tailLimit?'hidden':undefined,zIndex:editingThis?40:2+hit.path.split('/').length*2+(active?1:0)}}
        onClick={event=>{event.stopPropagation();if(ignoreClick.current){ignoreClick.current=false;return;}if(!(event.target as Element).closest('.studio-rich,button'))void open(hit,event);}}
        onDoubleClick={event=>{event.preventDefault();event.stopPropagation();cancelPress();if(!(event.target as Element).closest('.studio-rich,button'))void open(hit,event,true);}}
        onContextMenu={event=>{cancelPress();event.preventDefault();event.stopPropagation();bridge.context(event.nativeEvent,hit.path);}}
        onPointerDown={event=>hold(event,hit)}>
        {value&&<div className="studio-ghost" style={{backgroundImage:`url("${paper.querySelector<HTMLImageElement>('.page-image')?.src||''}")`,backgroundSize:`${model.width*scale*w/hit.width}px ${model.height*scale*h/hit.height}px`,backgroundPosition:`${-hit.x*scale*w/hit.width}px ${-hit.y*scale*h/hit.height}px`,transform:change?.rotation!==undefined?`rotate(${change.rotation}deg)`:undefined}}/>}
        {mode==='convenient'&&!editingThis&&!value&&source&&textTags.has(source.tagName)&&!source.querySelector('image,p,table')&&!source.hasAttribute('rotation')&&<TextPreview node={source} scale={scale} defaults={state.formats[hit.path]||{}}/>}
      </div>;
    })}
    {flowBounds&&!editing&&<div className="studio-flow-controls" style={{left:flowBounds.x/model.width*100+'%',top:flowBounds.y/model.height*100+'%',width:(flowBounds.right-flowBounds.x)/model.width*100+'%',height:(flowBounds.bottom-flowBounds.y)/model.height*100+'%'}}>
      <button className="studio-drag-handle" aria-label="拖动排序" title="拖动到插入线排序；也可用 Alt + ↑ / ↓" onPointerDown={event=>begin(event,'move',{...flowRects[0],path:flowPaths[0]})} onClick={event=>event.stopPropagation()}>⠿</button>
      <span className="studio-layout-badge">自动占位{flowPaths.length>1?' · '+flowPaths.length+' 项':''}</span>
      {!gesture&&<><button className="studio-block-action studio-block-more" aria-label="组件操作" aria-haspopup="menu" onClick={event=>openMenu(event,'actions',flowPaths[0])}>···</button>
        <button className="studio-block-action studio-insert-before" aria-label="在前面插入" title="在组件前插入内容" aria-haspopup="menu" onClick={event=>openMenu(event,'insert',flowPaths[0],false)}>+</button>
        <button className="studio-block-action studio-insert-after" aria-label="在后面插入" title="在组件后插入内容" aria-haspopup="menu" onClick={event=>openMenu(event,'insert',flowPaths.at(-1),true)}>+</button></>}
    </div>}
    {state.selectedPaths.filter(path=>{try{return editing?.path!==path&&document&&!!fixedBox(at(document,path));}catch{return false;}}).map(path=>hitFor(state,path,page)?.hit).filter((hit):hit is Hit=>!!hit).map((hit,index)=>{
      const value=gesture?.rects.find(item=>item.path===hit.path),change=value&&gesture?changeFor(gesture,value,delta.x,delta.y):null;
      return <div key={hit.path+':'+index} className="studio-controls" style={{left:(change?.x??hit.x)/model.width*100+'%',top:(change?.y??hit.y)/model.height*100+'%',width:(change?.width??hit.width)/model.width*100+'%',height:(change?.height??hit.height)/model.height*100+'%'}}>
        <button className="studio-drag-handle" aria-label="拖动固定组件" title="按组件的固定位置属性自由移动" onPointerDown={event=>begin(event,'move',hit)} onClick={event=>event.stopPropagation()}>⠿</button>
        <span className="studio-layout-badge">固定 · {fixedBox(at(document!,hit.path))?.getAttribute('body-flow')==='AVOID'?'避让':'遮盖'}</span>
        {gesture&&<span className="studio-dimensions">{Math.round(change?.x??hit.x)}, {Math.round(change?.y??hit.y)} · {Math.round(change?.width??hit.width)} × {Math.round(change?.height??hit.height)} pt</span>}
        {['nw','n','ne','e','se','s','sw','w'].map(edge=><button key={edge} className={'studio-resize-edge edge-'+edge} aria-label={'调整对象大小 '+edge} title="拖动边缘调整大小；也可在检查器输入尺寸" onPointerDown={event=>begin(event,edge,hit)} onClick={event=>event.stopPropagation()}/>)}
        <button className="studio-rotate" aria-label="旋转对象" title="拖动旋转，按 5° 吸附" onPointerDown={event=>begin(event,'rotate',hit)} onClick={event=>event.stopPropagation()}>↻</button>
        {!gesture&&<button className="studio-block-action studio-block-more" aria-label="组件操作" aria-haspopup="menu" onClick={event=>openMenu(event,'actions',hit.path)}>···</button>}
      </div>;
    })}
    {editing&&<div ref={editingLayer} className="studio-editing-surface studio-object is-editing" style={{left:editing.bounds.x/model.width*100+'%',top:editing.bounds.y/model.height*100+'%',width:editing.bounds.width/model.width*100+'%',minHeight:editing.bounds.height/model.height*paper.getBoundingClientRect().height}}>
      <Rich editing={editing} page={page} scale={scale} finish={()=>setEditing(null)}/>
    </div>}
    {!editing&&!flowBounds&&(!state.selectedPaths.length||!model.hits.length)&&<button className="studio-empty-insert studio-block-action" data-gutter={!insertPosition||undefined} style={{left:insertPosition?insertPosition.x/model.width*100+'%':-28,top:Math.min(insertPosition?.y??origin.y,model.height-insertHeight-insertGap)/model.height*100+'%'}} aria-label="插入内容" title="在当前页正文末尾插入内容" aria-haspopup="menu" onClick={event=>openMenu(event,'insert')}>{insertPosition?'+ 插入内容':'+'}</button>}
    {menu&&createPortal(<div className="studio-block-menu" role="menu" aria-label={menu.kind==='insert'?'插入内容':'组件操作'} style={{left:menu.left,top:menu.top}}>
      <div className="studio-menu-title">{menu.kind==='insert'?(menu.after?'在后面插入':'在前面插入'):state.selectedPaths.length>1?state.selectedPaths.length+' 个组件':'组件操作'}</div>
      {menu.kind==='insert'?blockKinds.map(([kind,label])=><button key={kind} role="menuitem" onClick={()=>insert(kind)}>{label}</button>):<>
        <button role="menuitem" onClick={duplicate}>创建副本 <kbd>Ctrl D</kbd></button>
        {flowPaths.length>0&&<><button role="menuitem" onClick={()=>{setMenu(null);sortStep(bridge.snapshot(),-1);}}>上移组件 <kbd>Alt ↑</kbd></button><button role="menuitem" onClick={()=>{setMenu(null);sortStep(bridge.snapshot(),1);}}>下移组件 <kbd>Alt ↓</kbd></button></>}
        <button role="menuitem" className="danger" onClick={()=>{setMenu(null);removeSelection();}}>删除组件 <kbd>Delete</kbd></button>
      </>}
    </div>,window.document.body)}
    {gesture?.kind==='sort'&&createPortal(<div className="studio-drag-preview" style={{left:(pointer.current?.x??gesture.startX)+16,top:(pointer.current?.y??gesture.startY)+16}} role="status">{gesture.paths.length>1?gesture.paths.length+' 个组件':state.entries.find(entry=>entry.path===gesture.paths[0])?.label??'组件'}<small>{drop?'释放以放置':'移到同一容器的插入位置'}</small></div>,window.document.body)}
    {drop&&gesture?.kind==='sort'&&createPortal(<div className="studio-drop-placeholder" style={{left:drop.x/state.pages[drop.page].width*100+'%',top:drop.y/state.pages[drop.page].height*100+'%',width:drop.width/state.pages[drop.page].width*100+'%'}}/>,drop.paper)}
    {guides.map((guide,index)=><div key={index} className={'studio-snap-guide studio-snap-'+guide.axis} style={guide.axis==='x'?{left:guide.position/model.width*100+'%'}:{top:guide.position/model.height*100+'%'}}/>)}
    {drop&&gesture?.kind==='sort'&&createPortal(<div className="studio-drop-indicator" role="status" style={{left:drop.x/state.pages[drop.page].width*100+'%',top:drop.y/state.pages[drop.page].height*100+'%',width:drop.width/state.pages[drop.page].width*100+'%'}}><span>{drop.after?'插入后面':'插入前面'}</span></div>,drop.paper)}
    {marquee&&<div className="studio-marquee" style={{left:Math.min(marquee.x,marquee.endX)/model.width*100+'%',top:Math.min(marquee.y,marquee.endY)/model.height*100+'%',width:Math.abs(marquee.endX-marquee.x)/model.width*100+'%',height:Math.abs(marquee.endY-marquee.y)/model.height*100+'%'}}/>}
  </div>;
}
export function render(paper:HTMLElement,page:number){
  if(!bridge)return;sync();
  let host=paper.querySelector<HTMLElement>('.studio-host');if(!host){host=document.createElement('div');host.className='studio-host';paper.append(host);roots.set(host,createRoot(host));}
  roots.get(host)!.render(<PageEditor paper={paper} page={page}/>);
}
export function dispose(paper:HTMLElement){const host=paper.querySelector<HTMLElement>('.studio-host');if(host){roots.get(host)?.unmount();roots.delete(host);host.remove();}}
export function start(value:Bridge){
  bridge=value;sync();
  window.addEventListener('resize',sync);
  const toolbar=document.getElementById('studio-toolbar')!,properties=document.getElementById('studio-properties')!;
  createRoot(toolbar).render(<Toolbar/>);createRoot(properties).render(<Inspector/>);
  document.addEventListener('keydown',event=>{
    if(event.defaultPrevented||snapshot.busy||!event.target||!(event.target as Element).closest('.studio-surface')||(event.target as Element).closest('.ProseMirror,input,select'))return;
    const directions:Record<string,[number,number]>={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]};
    if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='d'){
      event.preventDefault();event.stopImmediatePropagation();try{const result=duplicateBlocks(bridge.snapshot());void bridge.apply(result.source,result.ids,result.paths).catch(fail);}catch(error){fail(error);}return;
    }
    if(['Delete','Backspace'].includes(event.key)){event.preventDefault();event.stopImmediatePropagation();removeSelection();return;}
    if(directions[event.key]){
      if(event.altKey&&['ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();event.stopImmediatePropagation();sortStep(snapshot,event.key==='ArrowUp'?-1:1);return;}
      const document=xml(snapshot.source);if(!snapshot.selectedPaths.length||!snapshot.selectedPaths.every(path=>!!fixedBox(at(document,path))))return;
      const [dx,dy]=directions[event.key],step=event.shiftKey?10:1;event.preventDefault();event.stopImmediatePropagation();
      void applyGeometry(snapshot.selectedPaths.map(path=>{const placed=hitFor(snapshot,path,snapshot.currentPage)??hitFor(snapshot,path);return {path,x:Math.max(0,(placed?.hit.x??0)+dx*step),y:Math.max(0,(placed?.hit.y??0)+dy*step)};}));
    }
  },true);
  for(const [name,family,weight] of [['Regular.ttf','Export4j body','400'],['Bold.ttf','Export4j body','700'],['Chinese.ttf','Export4j Chinese','400']]){
    void bridge.font(name).then(buffer=>new FontFace(family,buffer,{weight}).load()).then(font=>document.fonts.add(font)).catch(error=>{if(name==='Regular.ttf')fail(error);});
  }
}
