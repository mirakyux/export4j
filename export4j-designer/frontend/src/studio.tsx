// SPDX-License-Identifier: Apache-2.0
import React,{useEffect,useLayoutEffect,useMemo,useRef,useState,useSyncExternalStore} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {createPortal} from 'react-dom';
import type {EditorView} from 'prosemirror-view';
import {TextSelection} from 'prosemirror-state';
import {closeHistory} from 'prosemirror-history';
import {DOMSerializer} from 'prosemirror-model';
import {at,xml,serialize,fixedBox,positionable,hitFor,textTags,flowPath,fixedPath,parentPath,reorder,type Bridge,type Snapshot,type Change,type Hit} from './types';
import {geometry} from './geometry';
import {mountRich,toXml,mark,caret,editorDocument,schema,createRichSession,sessionToXml,activeMarks,syncNativeSelection} from './rich';
import {blockKinds,blockGroups,insertBlock,duplicateBlocks,contentBlockTarget,removeContentBlock,contiguousText,snapMove,pathOf,type BlockKind,type SnapGuide} from './blocks';
import {GridCanvas} from './gridCanvas';
import {containerContext} from './containers';
import {TableCanvas,TableTools} from './tableCanvas';
import {columnInsertionTarget} from './columnInsertion';
export {sortGridItems} from './types';
export {gridContext,gridEdit,gridFromSelection,gridSelectionReason} from './grid';
export {bindingContext} from './bindings';
export {bindingValue,editBindingValue} from './bindingValues';
export {containerContext,containerEdit} from './containers';
export {tableMarkup} from './blocks';
export {columnInsertionTarget,prepareColumnInsertion,insertColumnFullWidth} from './columnInsertion';

declare global {interface Window {DesignerBindings?:{pick(path:string):Promise<string|null>}}}

let bridge:Bridge,snapshot:Snapshot,mode:'convenient'|'precise'='convenient',activeEditors=0;
const listeners=new Set<()=>void>(),roots=new Map<HTMLElement,Root>();
const subscribe=(listener:()=>void)=>{listeners.add(listener);return ()=>{listeners.delete(listener);};};
export const watchCanvasState=subscribe;
const read=()=>snapshot;
const refresh=()=>listeners.forEach(listener=>listener());
export function sync(){if(!bridge)return;snapshot=bridge.snapshot();refresh();}
type CanvasContext=Pick<Snapshot,'source'|'data'|'resourceIdentity'|'session'|'pages'>;
export function canvasContext(current=bridge.snapshot()):CanvasContext{return {source:current.source,data:current.data,resourceIdentity:current.resourceIdentity,session:current.session,pages:current.pages};}
export function canvasContextMatches(context:CanvasContext,current=bridge.snapshot()):boolean {
  return context.source===current.source&&context.data===current.data&&context.resourceIdentity===current.resourceIdentity&&context.session===current.session&&context.pages===current.pages;
}
export function requireCanvasContext(context:CanvasContext):Snapshot {
  const current=bridge.snapshot();
  if(!canvasContextMatches(context,current))throw new Error('页面或内容已变化，请在最新预览中重新选择插入位置。');
  return current;
}
export function requireCanvasLayout(context:CanvasContext):Snapshot {
  const current=requireCanvasContext(context);
  if(current.pending||current.layoutState!=='synced')throw new Error('页面布局尚未同步，请等待最新预览后重试。');
  return current;
}
function canvasLayoutReady(context:CanvasContext,current=bridge.snapshot()):boolean{return !current.busy&&!current.pending&&current.layoutState==='synced'&&canvasContextMatches(context,current);}
let activeContainer:{path:string;session:number}|undefined;
export async function finishText(){
  activeContainer=undefined;stopTextFocus?.();stopTextFocus=undefined;
  const editing=activeEditors>0;window.document.dispatchEvent(new Event('designer-edit-end'));
  if(editing)await bridge.apply(bridge.snapshot().source);
}
let stopTextFocus:(()=>void)|undefined;
function editableContent(node:Element):Element|null {
  if(textTags.has(node.tagName)&&!(node.tagName==='cell'&&[...node.children].some(child=>['p','table'].includes(child.tagName))))return node;
  for(const child of node.children){const target=editableContent(child);if(target)return target;}
  return null;
}
function contentContainer(node:Element):boolean {return ['table','rows','list','each','if','then','else','grid','item'].includes(node.tagName)||node.tagName==='columns'&&!['table','rows'].includes(node.parentElement?.tagName||'');}
function editingContainer(node:Element):Element|null {
  const table=node.closest('table,rows');if(table)return table;
  const owner=node.closest('grid,list,each');if(owner)return owner;
  for(let current:Element|null=node;current;current=current.parentElement)if(current.tagName==='columns'&&!['table','rows'].includes(current.parentElement?.tagName||''))return current;
  return null;
}
interface TextPlacement {hit:Hit;page:number}
function textPlacement(state:Snapshot,path:string,reference?:TextPlacement):TextPlacement|undefined {
  const candidates=state.pages.flatMap((model,page)=>model.hits.filter(hit=>hit.path===path).map(hit=>({hit,page})));
  const instance=(id:string)=>id.includes('@')?id.slice(id.indexOf('@')).split('#')[0]:'',row=(id:string)=>/#[0-9]+:/.exec(id)?.[0];
  const score=({hit,page}:TextPlacement)=>{
    if(!reference)return page===state.currentPage?0:10_000+page;
    return (page===reference.page?0:10_000+Math.abs(page-reference.page)*100)+(instance(reference.hit.id)&&instance(hit.id)!==instance(reference.hit.id)?100_000:0)+(row(reference.hit.id)&&row(hit.id)!==row(reference.hit.id)?1000:0)+Math.abs(hit.y-reference.hit.y)+Math.abs(hit.x-reference.hit.x)*.01;
  };
  return candidates.sort((a,b)=>score(a)-score(b))[0]??hitFor(state,path,state.currentPage)??hitFor(state,path);
}
export function focusText(path:string,point?:{x:number;y:number},replace=false,reference?:TextPlacement){
  stopTextFocus?.();stopTextFocus=undefined;
  try{
    const current=bridge.snapshot(),target=editableContent(at(xml(current.source),path));if(!target)throw new Error('此容器还没有可编辑正文，请先添加内容。');
    const textPath=pathOf(target),placed=textPlacement(current,textPath,reference),owner=editingContainer(target);
    activeContainer=owner?{path:pathOf(owner),session:current.session}:undefined;
    if(current.busy||!placed&&current.pending){
      stopTextFocus=subscribe(()=>{const next=bridge.snapshot();if(next.session!==current.session||next.source!==current.source){stopTextFocus?.();stopTextFocus=undefined;return;}if(next.busy||next.pending)return;stopTextFocus?.();stopTextFocus=undefined;focusText(textPath,point,replace,reference);});return;
    }
    void bridge.choose(textPath,undefined,false).then(()=>{
      if(!placed){window.document.dispatchEvent(new CustomEvent('designer-container-action',{detail:{path:textPath,action:'edit-source'}}));return;}
      if(!point){const object=[...window.document.querySelectorAll<HTMLElement>(`.canvas-sheet[data-page="${placed.page}"] [data-studio-path]`)].find(node=>node.dataset.studioPath===textPath);object?.scrollIntoView({block:'nearest'});}
      window.document.dispatchEvent(new CustomEvent('designer-focus-text',{detail:{path:textPath,page:placed.page,hit:placed.hit,point:textPath===path?point:undefined,replace}}));
    }).catch(fail);
  }catch(error){fail(error);}
}
function useDocument(){return useSyncExternalStore(subscribe,read);}
function fail(error:unknown){bridge.error(error instanceof Error?error.message:String(error));}
async function applyGeometry(changes:Change[],context=canvasContext(),requireSyncedLayout=true){try{const current=requireSyncedLayout?requireCanvasLayout(context):requireCanvasContext(context);if(current.busy)return;const result=geometry(current,changes);await bridge.apply(result.source,result.ids);}catch(error){fail(error);}}
async function sortSelection(paths:string[],target:string,after:boolean,context=canvasContext()){try{const current=requireCanvasContext(context);if(current.busy)return;const result=reorder(current,paths,target,after);if(result.source!==serialize(xml(current.source)))await bridge.apply(result.source,undefined,result.paths);}catch(error){fail(error);}}
function flowSelection(state:Snapshot):string[]{
  let document:Document;try{document=xml(state.source);}catch{return [];}
  const paths=[...new Set(state.selectedPaths.map(path=>{try{return flowPath(document,path);}catch{return null;}}).filter((path):path is string=>path!==null))];
  return paths.filter(path=>!paths.some(parent=>path.startsWith(parent+'/')));
}
function sortStep(state:Snapshot,direction:number){
  const paths=flowSelection(state);if(!paths.length||paths.some(path=>parentPath(path)!==parentPath(paths[0])))return;
  const document=xml(state.source),siblings=state.entries.filter(entry=>parentPath(entry.path)===parentPath(paths[0])&&flowPath(document,entry.path)===entry.path).map(entry=>entry.path);
  const indices=paths.map(path=>siblings.indexOf(path)),edge=direction<0?Math.min(...indices):Math.max(...indices),target=siblings[edge+direction];
  if(target)void sortSelection(paths,target,direction>0,canvasContext(state));
}
function gridInspectorReady(element:HTMLDivElement|null){if(element)window.document.dispatchEvent(new Event('designer-grid-inspector-ready'));}
function ContainerTools({state}:{state:Snapshot}){
  const more=useRef<HTMLDetailsElement>(null);
  useEffect(()=>{
    const key=(event:KeyboardEvent)=>{if(event.key==='Escape'&&more.current?.open){event.preventDefault();more.current.open=false;more.current.querySelector('summary')?.focus();}};
    window.document.addEventListener('keydown',key);
    return ()=>window.document.removeEventListener('keydown',key);
  },[]);
  const path=state.selectedPaths.length===1?state.selectedPaths[0]:undefined;
  useEffect(()=>{if(more.current)more.current.open=false;},[path,state.session]);
  let context:ReturnType<typeof containerContext>=null;
  try{if(path!==undefined)context=containerContext(state,path);}catch{/* Invalid source retains its existing error display. */}
  if(!context)return null;
  const columnTarget=context?.kind==='columns'?columnInsertionTarget(state.source,path??context.path):null;
  let inner:ReturnType<typeof contentBlockTarget>=null;try{if(path!==undefined)inner=contentBlockTarget(state,path);}catch{/* Invalid source is reported by the editor. */}
  const innerAction=async(kind:'copy'|'remove')=>{
    if(!inner)return;try{if(activeEditors)throw new Error('请先完成文字编辑，再选择要复制或删除的内部内容。');await finishText();const current=bridge.snapshot();if(current.source!==state.source||current.session!==state.session)throw new Error('内容已变化，请重新选择内部内容。');const result:{source:string;ids?:string[];paths:string[]}=kind==='copy'?duplicateBlocks({...current,selectedPaths:[inner.path]},'content'):removeContentBlock(current,inner.path);await bridge.apply(result.source,result.ids,result.paths);}catch(error){fail(error);}
  };
  const action=(value:'add-content')=>{if(context)window.document.dispatchEvent(new CustomEvent('designer-container-action',{detail:{path:path??context.path,action:value}}));};
  return <div className="studio-container-tools" role="toolbar" aria-label="容器内容操作">
      <button disabled={state.busy||!context.textPaths.length} onClick={()=>focusText(path??context!.path)}>编辑内部内容</button>
      {!['if','then','else','each'].includes(context.kind)&&<button disabled={state.busy} onClick={()=>action('add-content')}>添加内容</button>}
      {context.table&&<TableTools state={state} path={path??context.path} bridge={bridge} onError={fail} finish={finishText}/>}
      {(inner||columnTarget)&&<details ref={more} className="studio-container-more" onClick={event=>{if((event.target as Element).closest('button'))event.currentTarget.open=false;}}>
        <summary aria-label="更多容器操作" aria-disabled={state.busy} onClick={event=>{if(state.busy)event.preventDefault();}}>更多操作</summary>
        <div className="studio-container-menu">
          {inner&&<><button disabled={state.busy||activeEditors>0} title="完成文字编辑后，只复制当前内部内容，不复制外层容器" onClick={()=>void innerAction('copy')}>复制当前{inner.label}</button><button disabled={state.busy||activeEditors>0} title="完成文字编辑后，只删除当前内部内容，不删除外层容器；支持撤销" onClick={()=>void innerAction('remove')}>删除当前{inner.label}</button></>}
          {columnTarget&&(['table','image'] as const).map(kind=><button key={kind} disabled={state.busy||!columnTarget.editable} title={columnTarget.reason||'在当前段落后插入通栏内容，前后正文继续分栏'} onClick={()=>window.document.dispatchEvent(new CustomEvent('designer-insert-full-width',{detail:{kind,path:path??context!.path}}))}>{kind==='table'?'插入通栏表格':'插入通栏图片'}</button>)}
        </div>
      </details>}
  </div>;
}
function Toolbar(){
  const state=useDocument(),editingMode=mode;
  return <><div className="studio-tools">
    <span className="studio-interaction-hint" title="单击选择；双击或 Enter / F2 编辑。拖柄可直接拖动，正文内长按也可拖动。组件的排版属性决定排序或自由移动。">单击选择 · 双击编辑 · 拖柄移动</span>
    <span className="studio-selection-count">{state.selectedPaths.length>1?state.selectedPaths.length+' 个对象':''}</span>
    <span className={'studio-sync-state'+(state.layoutState==='pending'?' is-pending':'')} role="status">{{pending:'正在校准版式',failed:'版式校验失败',stale:'版式待更新',synced:'版式已同步'}[state.layoutState]}</span>
    {state.historyBoundary&&<span className="studio-history-boundary" role="status">{state.historyBoundary}</span>}
  </div><div className="studio-modes" role="group" aria-label="编辑模式">
    <button aria-pressed={editingMode==='convenient'} title="浏览器即时编辑，直接显示模板变量；版式为近似效果，完成编辑后更新引擎布局" onClick={()=>{mode='convenient';sync();}}>便捷编辑</button>
    <button aria-pressed={editingMode==='precise'} title="Java 引擎实时排版，显示测试数据绑定后的真实换行、分页与输出效果" onClick={()=>{mode='precise';sync();bridge.preview();}}>精确排版</button>
    <span className="studio-mode-note" role="status">{editingMode==='convenient'?'模板内容 · 近似显示':'引擎结果 · 实际分页'}</span>
  </div></>;
}
function ContainerInspector(){
  const state=useDocument();
  return <><ContainerTools state={state}/><div id="studio-grid-context" ref={gridInspectorReady} className="studio-grid-context-slot"/></>;
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
  if(nodes.some(node=>['if','each','then','else'].includes(node.tagName)))return null;
  const boxes=nodes.map(fixedBox),allFixed=boxes.every(Boolean),placed=hitFor(state,state.selectedPaths[0],state.currentPage)??hitFor(state,state.selectedPaths[0]);
  const flow=flowSelection(state),allFlow=nodes.every(node=>!fixedBox(node))&&flow.length>0&&state.selectedPaths.every(path=>flowPath(document,path)===path)&&flow.every(path=>parentPath(path)===parentPath(flow[0])),canFix=nodes.every(positionable);
  if(!placed||(!canFix&&!allFlow))return null;
  const common=(name:string)=>{const values=nodes.map((node,index)=>name==='rotation'?(node.tagName==='box'?node.firstElementChild:node)?.getAttribute(name)??'0':boxes[index]?.getAttribute(name)??String((hitFor(state,state.selectedPaths[index])?.hit as any)?.[name]??''));return values.every(value=>value===values[0])?values[0]??'':'';};
  // Explicit inspector values extend the current source even while engine validation is queued.
  const change=(value:Partial<Change>)=>applyGeometry(state.selectedPaths.map(path=>({path,...value})),canvasContext(state),false);
  const convert=(position:'FLOW'|'FIXED')=>change({position,page:placed.page});
  return <details className="studio-inspector" open><summary>{allFixed||canFix?'位置与尺寸':'组件顺序'}{(allFixed||canFix)&&<small>pt</small>}</summary>
    {(canFix||allFixed)&&<label>排版方式<select aria-label="块排版方式" disabled={state.busy} value={allFixed?'FIXED':allFlow?'FLOW':''} onChange={event=>convert(event.target.value as 'FLOW'|'FIXED')}>
      {!allFixed&&!allFlow&&<option value="">多个值</option>}<option value="FLOW">自动占位</option><option value="FIXED" disabled={!canFix}>固定位置</option></select></label>
    }
    {allFlow&&<>
      <div className="studio-order"><button disabled={state.busy} onClick={()=>sortStep(state,-1)} title="Alt + ↑">上移组件</button><button disabled={state.busy} onClick={()=>sortStep(state,1)} title="Alt + ↓">下移组件</button></div>
    </>}
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
  void applyGeometry(placed.map(item=>({path:item.path,...(mode==='left'?{x:left}:mode==='top'?{y:top}:mode==='center'?{x:(page.width-item.hit.width)/2}:{y:(page.height-item.hit.height)/2})})),canvasContext(state));
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
interface Editing {key:string;path:string;node:Element;paths:string[];nodes:Element[];bounds:Hit;index:number;replace?:boolean;continuation?:boolean;point?:{x:number;y:number}}
function Rich({editing,page,scale,finish}:{editing:Editing;page:number;scale:number;finish:(focus?:boolean)=>void}){
  const host=useRef<HTMLDivElement>(null),view=useRef<EditorView|null>(null),ids=useRef<string[]>([editing.node.tagName==='cell'?'':editing.node.getAttribute('id')||'edit-'+crypto.randomUUID()]);
  const session=useRef(useMemo(()=>['p','heading'].includes(editing.node.tagName)&&!fixedBox(editing.node)?createRichSession(editing.nodes,Object.fromEntries(editing.nodes.map((node,index)=>[node.getAttribute('id')||String(index),snapshot.formats[editing.paths[index]]||{}]))):undefined,[]));
  const currentIds=useRef<string[]|null>(null),[toolbar,setToolbar]=useState<{left:number;top:number}|null>(null),[marks,setMarks]=useState<Record<string,string|boolean>>({});
  const [pickingVariable,setPickingVariable]=useState(false),variableRequest=useRef<symbol|null>(null);
  const columnContext=useMemo(()=>{try{return columnInsertionTarget(serialize(editing.node.ownerDocument),editing.path);}catch{return null;}},[]);
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
    activeContainer=undefined;finish();void bridge.apply(source,active?[key]:undefined,active?undefined:[editing.paths[editing.index]]).catch(fail);
  };
  const insertFullWidthAtCaret=(kind:'table'|'image')=>{
    try{
      const editor=view.current;if(!editor||pickingVariable||bridge.snapshot().busy)return;
      if(editor.composing)throw new Error('请先完成当前输入，再插入通栏内容。');
      syncNativeSelection(editor);
      const selection=editor.state.selection;
      if(!selection.empty)throw new Error('请先收起文字选区，再在光标处插入通栏内容；已选文字会完整保留。');
      const current=bridge.snapshot(),document=xml(current.source),index=selection.$from.index(0);
      const key=selection.$from.parent.attrs.key||currentIds.current?.[index]||ids.current[index];
      const node=(key?[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===key):undefined)??at(document,editing.paths[index]??editing.path);
      const path=pathOf(node),target=columnInsertionTarget(current.source,path);
      if(!target?.editable||!target.paragraph)throw new Error(target?.reason||'请将光标放在正文分栏的直属段落中。');
      window.document.dispatchEvent(new CustomEvent('designer-insert-full-width',{detail:{kind,path,source:current.source,session:current.session,caret:{offset:selection.$from.parentOffset,size:selection.$from.parent.content.size,empty:selection.empty}}}));
    }catch(error){view.current?.focus();fail(error);}
  };
  const navigateCell=(direction:'previous'|'next',editor:EditorView):boolean=>{
    try{
      const document=xml(bridge.snapshot().source),key=editor.state.selection.$from.parent.attrs.key;
      const node=(key?[...document.querySelectorAll('[id]')].find(value=>value.getAttribute('id')===key):undefined)??at(document,editing.paths[editing.index]);
      const cell=node.closest('cell'),row=cell?.parentElement,table=row?.parentElement;
      if(!cell||!row||!['header','row'].includes(row.tagName)||!table||!['table','rows'].includes(table.tagName))return false;
      // Walk source anchors once: merged coverage and expanded data records are not new editable cells.
      const cells=[...table.children].filter(value=>['header','row'].includes(value.tagName)).flatMap(value=>[...value.children].filter(child=>child.tagName==='cell'));
      const next=cells[cells.indexOf(cell)+(direction==='previous'?-1:1)],target=next?pathOf(next):pathOf(table);
      finish(!next);if(!next)activeContainer=undefined;
      void bridge.apply(bridge.snapshot().source,undefined,[target]).then(()=>{if(next)focusText(target,undefined,false,{page,hit:editing.bounds});}).catch(fail);
      return true;
    }catch(error){fail(error);return true;}
  };
  const insertVariable=async()=>{
    const editor=view.current,picker=window.DesignerBindings;if(!editor||pickingVariable||bridge.snapshot().busy)return;
    if(!picker){fail(new Error('变量选择器尚未加载，请稍后重试。'));return;}
    const original=bridge.snapshot(),source=original.source,document=editor.state.doc,bookmark=editor.state.selection.getBookmark(),marks=editor.state.storedMarks??editor.state.selection.$from.marks();
    const key=editor.state.selection.$from.parent.attrs.key,owner=key?[...xml(source).querySelectorAll('[id]')].find(node=>node.getAttribute('id')===key):undefined,path=owner?pathOf(owner):editing.paths[editing.index];
    const request=Symbol();variableRequest.current=request;setPickingVariable(true);
    try{
      const pathValue=await picker.pick(path);
      if(variableRequest.current!==request||view.current!==editor)return;
      if(pathValue===null){editor.focus();return;}
      const current=bridge.snapshot();
      if(current.session!==original.session||current.source!==source||editor.state.doc!==document||current.busy)throw new Error('编辑内容已变化，请重新选择插入变量的位置。');
      if(!pathValue||pathValue.length>1024||/[{}$\r\n]/.test(pathValue))throw new Error('变量路径无效，请重新选择。');
      const expression='${'+pathValue+'}',token=schema.nodes.token.create({xml:expression,label:expression,kind:'variable'},null,marks);
      const transaction=closeHistory(editor.state.tr.setSelection(bookmark.resolve(editor.state.doc)).replaceSelectionWith(token,false).setStoredMarks(marks).scrollIntoView());
      editor.focus();editor.dispatch(transaction);editor.dispatch(closeHistory(editor.state.tr).setMeta('addToHistory',false));
    }catch(error){if(variableRequest.current===request&&view.current===editor){editor.focus();fail(error);}}
    finally{if(variableRequest.current===request){variableRequest.current=null;setPickingVariable(false);}}
  };
  useLayoutEffect(()=>{
    activeEditors++;
    const editor=mountRich(host.current!,editing.node,{changed,editable:()=>!bridge.snapshot().busy,session:session.current,selectionChanged,paste:file=>{
      changed(editor);const key=editor.state.selection.$from.parent.attrs.key,document=xml(bridge.snapshot().source),node=key?[...document.querySelectorAll('[id]')].find(node=>node.getAttribute('id')===key):undefined;
      finish();void bridge.paste(file,page,20,20,node?pathOf(node):editing.path);
    },commit,tab:navigateCell,inlineOnly:editing.node.tagName==='cell'||!!fixedBox(editing.node),merge:()=>false});
    view.current=editor;
    sync();
    if(editing.point)caret(editor,editing.point.x,editing.point.y);else{
      let position=1;for(let index=0;index<editing.index;index++)position+=editor.state.doc.child(index).nodeSize;
      editor.focus();editor.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc,position,editing.replace?position+editor.state.doc.child(editing.index).content.size:position)));
    }
    return ()=>{activeEditors--;variableRequest.current=null;view.current=null;editor.destroy();sync();};
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
    <div className="studio-edit-actions"><span title={editing.node.closest('cell')?'Tab / Shift + Tab 切换单元格；首末格向外提交并退出。块段落 Enter 分段，行内单元格 Enter 换行。':undefined}>{editing.continuation?'跨页段落 · 编辑完整原文':'正在编辑 · Ctrl + Enter 完成'}{editing.node.closest('cell')?' · Tab 换格':''}</span><button disabled={pickingVariable||snapshot.busy} onPointerDown={event=>event.preventDefault()} onClick={()=>{void insertVariable();}}>插入变量</button>{columnContext&&(['table','image'] as const).map(kind=><button key={kind} disabled={pickingVariable||snapshot.busy||!columnContext.editable||!columnContext.paragraph} title={columnContext.reason||'在当前光标处插入通栏内容，后续正文继续分栏'} onPointerDown={event=>event.preventDefault()} onClick={()=>insertFullWidthAtCaret(kind)}>光标处通栏{kind==='table'?'表格':'图片'}</button>)}<button onClick={commit}>完成</button></div>
    {toolbar&&createPortal(<div className="studio-rich-toolbar studio-floating-toolbar" role="toolbar" aria-label="画布文字格式" style={toolbar} onPointerDown={event=>{if((event.target as Element).closest('button'))event.preventDefault();}}>
      <button aria-label="粗体" aria-pressed={!!marks.strong} onClick={()=>view.current&&mark(view.current,'strong')}>B</button><button aria-label="下划线" aria-pressed={!!marks.underline} onClick={()=>view.current&&mark(view.current,'underline')}>U</button><button aria-label="删除线" aria-pressed={!!marks.strike} onClick={()=>view.current&&mark(view.current,'strike')}>S</button>
      <input type="color" aria-label="画布文字颜色" value={String(marks.color||'#000000')} onChange={event=>view.current&&mark(view.current,'color',event.target.value)}/>
      <select aria-label="画布文字字号" value="" onChange={event=>{if(view.current&&event.target.value)mark(view.current,'size',event.target.value);}}><option value="">字号</option>{[8,10,12,14,16,18,20,24,28,36,48].map(size=><option key={size}>{size}</option>)}</select>
    </div>,window.document.body)}<div ref={host}/>
  </div>;
}
interface Gesture {kind:string;startX:number;startY:number;paths:string[];rects:{path:string;hit:Hit;rotation:number}[];pointer:number;target:HTMLElement;context:CanvasContext;scale:number;width:number;height:number}
interface PointerStart {clientX:number;clientY:number;pointerId:number;currentTarget:HTMLElement;context:CanvasContext}
interface Press extends PointerStart {timer:ReturnType<typeof setTimeout>}
interface Marquee {x:number;y:number;endX:number;endY:number;pointer:number;target:HTMLElement;context:CanvasContext;scale:number}
interface Drop {path:string;page:number;after:boolean;x:number;y:number;width:number;label:string;paper:HTMLElement}
interface BlockMenu {kind:'insert'|'actions';path?:string;after:boolean;left:number;top:number;context:CanvasContext}
function PageEditor({page,paper}:{page:number;paper:HTMLElement}){
  const state=useDocument(),model=state.pages[page],surface=useRef<HTMLDivElement>(null);
  const [editing,setEditing]=useState<Editing|null>(null),[gesture,setGesture]=useState<Gesture|null>(null),[delta,setDelta]=useState({x:0,y:0}),[marquee,setMarquee]=useState<Marquee|null>(null);
  const activeGesture=useRef<Gesture|null>(null),activeMarquee=useRef<Marquee|null>(null),blankClick=useRef<PointerStart|null>(null);
  const [drop,setDrop]=useState<Drop|null>(null);
  const [menu,setMenu]=useState<BlockMenu|null>(null),[guides,setGuides]=useState<SnapGuide[]>([]);
  const [blankOrigin,setBlankOrigin]=useState<{x:number;y:number}|null>(null);
  const [hoveredGridItem,setHoveredGridItem]=useState<string|null>(null);
  const editingLayer=useRef<HTMLDivElement>(null),[expansion,setExpansion]=useState(0);
  const pointer=useRef<{x:number;y:number}|null>(null);
  const [paperWidth,setPaperWidth]=useState(()=>paper.getBoundingClientRect().width);
  const ignoreClick=useRef(false),press=useRef<Press|null>(null),scale=paperWidth/(model?.width||1);
  const geometryDisabled=state.busy||state.pending||state.layoutState!=='synced';
  const release=(target:HTMLElement,pointer:number)=>{if(target.hasPointerCapture(pointer))target.releasePointerCapture(pointer);};
  const cancelPress=()=>{const value=press.current;press.current=null;if(value){clearTimeout(value.timer);release(value.currentTarget,value.pointerId);}};
  const cancelGesture=(keepBlank=false)=>{
    const value=activeGesture.current,selection=activeMarquee.current,blank=keepBlank?null:blankClick.current,active=!!value||!!selection||!!blank||!!press.current;
    activeGesture.current=null;activeMarquee.current=null;if(!keepBlank)blankClick.current=null;cancelPress();
    if(value)release(value.target,value.pointer);if(selection)release(selection.target,selection.pointer);
    if(blank)release(blank.currentTarget,blank.pointerId);
    setGesture(null);setDrop(null);setMarquee(null);setGuides([]);if(active)ignoreClick.current=true;
  };
  useLayoutEffect(()=>{
    const measure=()=>{const width=paper.getBoundingClientRect().width;if(width>0)setPaperWidth(previous=>Math.abs(previous-width)>.1?width:previous);};
    const observer=new ResizeObserver(measure);observer.observe(paper);measure();return ()=>observer.disconnect();
  },[paper]);
  useEffect(()=>()=>{const value=activeGesture.current,selection=activeMarquee.current,blank=blankClick.current;activeGesture.current=null;activeMarquee.current=null;blankClick.current=null;cancelPress();if(value)release(value.target,value.pointer);if(selection)release(selection.target,selection.pointer);if(blank)release(blank.currentTarget,blank.pointerId);},[]);
  useEffect(()=>{
    const current=bridge.snapshot();if(activeContainer&&current.selectedPaths.some(path=>path!==activeContainer!.path&&!path.startsWith(activeContainer!.path+'/')))activeContainer=undefined;
    if(editing&&!state.selectedPaths.some(path=>editing.paths.includes(path)))setEditing(null);
  },[state.selectedPaths.join('|')]);
  useEffect(()=>{if(activeContainer?.session!==state.session)activeContainer=undefined;cancelPress();setEditing(null);setMenu(null);ignoreClick.current=false;},[state.session]);
  useLayoutEffect(()=>{setMenu(null);const blank=blankClick.current;cancelGesture(!!blank&&canvasContextMatches(blank.context,state));},[state.source,state.data,state.resourceIdentity,state.session,state.pages,paperWidth]);
  useLayoutEffect(()=>{if(geometryDisabled)cancelGesture(!state.busy);},[state.layoutState,state.pending,state.busy]);
  useEffect(()=>{const finish=()=>{activeContainer=undefined;setEditing(null);setMenu(null);};for(const name of ['designer-history','designer-edit-end'])window.document.addEventListener(name,finish);return ()=>{for(const name of ['designer-history','designer-edit-end'])window.document.removeEventListener(name,finish);};},[]);
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
    const hits=group.paths.map(path=>textPlacement(current,path,fallback?{page,hit:fallback}:undefined)?.hit).filter((hit):hit is Hit=>!!hit),first=hits[0]??fallback;
    if(!first||!textTags.has(node.tagName))return;
    const x=Math.min(...(hits.length?hits:[first]).map(hit=>hit.x)),y=Math.min(...(hits.length?hits:[first]).map(hit=>hit.y));
    const bounds={...first,x,y,width:Math.max(...(hits.length?hits:[first]).map(hit=>hit.x+hit.width))-x,height:Math.max(...(hits.length?hits:[first]).map(hit=>hit.y+hit.height))-y};
    const continuation=['p','heading'].includes(node.tagName)&&current.pages.filter(model=>model.hits.some(hit=>hit.path===path)).length>1;
    window.document.dispatchEvent(new CustomEvent('designer-edit-start',{detail:{page}}));
    setMenu(null);setEditing({key:crypto.randomUUID(),path:group.paths[0],node:group.nodes[0],paths:group.paths,nodes:group.nodes,bounds,index:group.paths.indexOf(path),replace,continuation,point:continuation?undefined:point});
  };
  useEffect(()=>{
    const focus=(event:Event)=>{
      const detail=(event as CustomEvent<{path:string;page:number;hit?:Hit;point?:{x:number;y:number};replace?:boolean}>).detail,current=bridge.snapshot();if(detail.page!==page||current.busy)return;
      try{editPath(detail.path,detail.point,detail.hit??textPlacement(current,detail.path)?.hit,detail.replace);}catch(error){fail(error);}
    };
    window.document.addEventListener('designer-focus-text',focus);return ()=>window.document.removeEventListener('designer-focus-text',focus);
  },[page,state.source,state.busy]);
  const openMenu=(event:React.MouseEvent<HTMLButtonElement>,kind:BlockMenu['kind'],path?:string,after=true)=>{
    event.preventDefault();event.stopPropagation();const bounds=event.currentTarget.getBoundingClientRect();
    setMenu(previous=>previous?.kind===kind&&previous.path===path&&previous.after===after?null:{kind,path,after,context:canvasContext(),left:Math.max(8,Math.min(window.innerWidth-224,bounds.left)),top:Math.max(64,Math.min(window.innerHeight-310,bounds.bottom+5))});
  };
  const insert=(kind:BlockKind)=>{
    if(!menu)return;
    try{
      const current={...requireCanvasContext(menu.context),currentPage:page};
      if(kind==='grid'){
        setMenu(null);window.document.dispatchEvent(new CustomEvent('designer-insert-grid',{detail:{path:menu.path,after:menu.after,page:menu.path?undefined:page,context:menu.context}}));return;
      }
      if(kind==='list'||kind==='rows'){
        setMenu(null);window.document.dispatchEvent(new CustomEvent('designer-insert-bound',{detail:{kind,path:menu.path,after:menu.after,page:menu.path?undefined:page,context:menu.context}}));return;
      }
      const result=insertBlock(current,menu.path,menu.after,kind),placed=menu.path?hitFor(current,menu.path,page)?.hit:undefined;
      setMenu(null);void bridge.apply(result.source,result.ids,result.paths).then(()=>{
        if(kind==='p'||kind==='heading')editPath(result.paths[0],undefined,{id:result.ids[0],path:result.paths[0],x:placed?.x??30,y:placed?(menu.after?placed.y+placed.height+8:placed.y):30,width:placed?.width??model.width-60,height:24,images:[]},true);
        else if(kind==='table'||kind==='columns')focusText(result.paths[0]);
      }).catch(fail);
    }catch(error){setMenu(null);fail(error);}
  };
  const menuAction=(action:(current:Snapshot)=>void)=>{if(!menu)return;setMenu(null);try{action(requireCanvasContext(menu.context));}catch(error){fail(error);}};
  const duplicate=()=>menuAction(current=>{const result=duplicateBlocks(current);void bridge.apply(result.source,result.ids,result.paths).catch(fail);});
  const open=async(hit:Hit,event:React.MouseEvent,edit=false)=>{
    const current=bridge.snapshot(),source=xml(current.source),clickedPath=current.entries.find(entry=>entry.id&&entry.id===hit.id.split('@')[0])?.path??hit.path;
    const inside=activeContainer?.session===current.session&&(clickedPath===activeContainer.path||clickedPath.startsWith(activeContainer.path+'/'));
    const enter=edit||!!inside&&!!editableContent(at(source,clickedPath));
    const rootPath=!enter?(flowPath(source,clickedPath)??fixedPath(source,clickedPath)):null;
    if(event.ctrlKey||event.metaKey||event.shiftKey){await bridge.choose(rootPath??hit.path,event);return;}
    if(editing?.paths.includes(clickedPath))return;
    if(activeEditors)await finishText();else if(!enter)activeContainer=undefined;
    const path=rootPath??clickedPath;
    await bridge.choose(path,undefined,!enter);let node:Element;try{node=at(xml(bridge.snapshot().source),path);}catch{return;}
    if(enter&&(textTags.has(node.tagName)||contentContainer(node)))focusText(path,{x:event.clientX,y:event.clientY},false,{page,hit});
    else surface.current?.focus({preventScroll:true});
  };
  const startGesture=(start:PointerStart,kind:string,hit:Hit)=>{
    const current=bridge.snapshot();if(!canvasLayoutReady(start.context,current)||!start.currentTarget.isConnected)return;
    const document=xml(current.source),flow=flowPath(document,hit.path),selectedFlow=flowSelection(current);
    const paths=flow?(selectedFlow.includes(flow)?selectedFlow:[flow]):current.selectedPaths.includes(hit.path)?current.selectedPaths:[hit.path];
    if(flow&&kind==='move')kind='sort';
    if(kind==='sort'&&paths.some(path=>parentPath(path)!==parentPath(paths[0]))){fail(new Error('跨容器组件不能一起排序，请选择同级组件。'));return;}
    const rects=paths.map(path=>{const value=hitFor(current,path,page);const node=at(document,path);return value?{path,hit:value.hit,rotation:Number((node.tagName==='box'?node.firstElementChild:node)?.getAttribute('rotation')||0)}:null;}).filter(Boolean) as Gesture['rects'];
    if(!rects.length||kind!=='sort'&&!paths.every(path=>!!fixedBox(at(document,path))))return;
    if(paths.join('|')!==current.selectedPaths.join('|'))void bridge.select(paths);
    const next:Gesture={kind,paths,startX:start.clientX,startY:start.clientY,rects,pointer:start.pointerId,target:start.currentTarget,context:start.context,scale:paper.getBoundingClientRect().width/model.width,width:model.width,height:model.height};
    activeGesture.current=next;setEditing(null);setGesture(next);setDelta({x:0,y:0});
    start.currentTarget.setPointerCapture(start.pointerId);
  };
  const begin=(event:React.PointerEvent<HTMLElement>,kind:string,hit:Hit)=>{
    if(event.button!==0||geometryDisabled)return;event.preventDefault();event.stopPropagation();
    cancelPress();ignoreClick.current=false;try{startGesture({clientX:event.clientX,clientY:event.clientY,pointerId:event.pointerId,currentTarget:event.currentTarget,context:canvasContext(state)},kind,hit);}catch(error){fail(error);}
  };
  const hold=(event:React.PointerEvent<HTMLDivElement>,hit:Hit)=>{
    if(event.button!==0||geometryDisabled||editing||event.ctrlKey||event.metaKey||event.shiftKey)return;
    cancelPress();ignoreClick.current=false;
    const current=bridge.snapshot(),context=canvasContext(state);if(!canvasLayoutReady(context,current))return;
    let document:Document;try{document=xml(current.source);}catch(error){fail(error);return;}
    // A cell edits independently; dragging moves the whole semantic table/control.
    const flow=flowPath(document,hit.path);
    const fixed=fixedPath(document,hit.path);
    const target=flow?hitFor(current,flow,page)?.hit:fixed&&hitFor(current,fixed,page)?.hit;
    if(!target)return;
    surface.current?.focus({preventScroll:true});
    const start:PointerStart={clientX:event.clientX,clientY:event.clientY,pointerId:event.pointerId,currentTarget:event.currentTarget,context};
    event.currentTarget.setPointerCapture(event.pointerId);
    press.current={...start,timer:setTimeout(()=>{press.current=null;ignoreClick.current=true;try{startGesture(start,'move',target);}catch(error){fail(error);}if(!activeGesture.current)release(start.currentTarget,start.pointerId);},350)};
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
      if(!activeGesture.current||!canvasLayoutReady(gesture.context)){cancelGesture();return;}
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
    const hovered=(event.target as Element).closest<HTMLElement>('[data-grid-item-path]')?.dataset.gridItemPath??null;
    setHoveredGridItem(previous=>previous===hovered?previous:hovered);
    pointer.current={x:event.clientX,y:event.clientY};
    if(blankClick.current&&blankClick.current.pointerId===event.pointerId&&Math.hypot(event.clientX-blankClick.current.clientX,event.clientY-blankClick.current.clientY)>4){cancelGesture();return;}
    if(press.current&&press.current.pointerId===event.pointerId&&Math.hypot(event.clientX-press.current.clientX,event.clientY-press.current.clientY)>6){cancelPress();ignoreClick.current=true;}
    const value=activeGesture.current,selection=activeMarquee.current;
    if(value&&value.pointer===event.pointerId){
      if(!canvasLayoutReady(value.context)){cancelGesture();return;}
      const raw={x:(event.clientX-value.startX)/value.scale,y:(event.clientY-value.startY)/value.scale};
      if(value.kind==='move'&&!event.altKey){
        const targets=[{x:0,y:0,width:model.width,height:model.height},...model.hits.filter(hit=>!value.rects.some(item=>hit.path===item.path||hit.path.startsWith(item.path+'/')))];
        const snapped=snapMove(value.rects[0].hit,raw.x,raw.y,targets,5/value.scale);setDelta(snapped);setGuides(snapped.guides);
      }else{setDelta(raw);setGuides([]);}
      if(value.kind==='sort')setDrop(findDrop(event.clientX,event.clientY,value));
    }
    if(selection&&selection.pointer===event.pointerId){
      if(!canvasLayoutReady(selection.context)){cancelGesture();return;}
      const bounds=paper.getBoundingClientRect(),next={...selection,endX:(event.clientX-bounds.left)/selection.scale,endY:(event.clientY-bounds.top)/selection.scale};activeMarquee.current=next;setMarquee(next);
    }
  };
  const stop=(event:React.PointerEvent)=>{
    cancelPress();
    const blank=blankClick.current;
    if(blank&&blank.pointerId===event.pointerId){
      blankClick.current=null;release(blank.currentTarget,blank.pointerId);
      if(canvasContextMatches(blank.context)&&!bridge.snapshot().busy)window.document.dispatchEvent(new CustomEvent('designer-select-page',{detail:{page,context:blank.context}}));
      return;
    }
    const value=activeGesture.current,selection=activeMarquee.current;
    if(value&&value.pointer===event.pointerId){
      if(!canvasLayoutReady(value.context)||Math.abs(paper.getBoundingClientRect().width/value.width-value.scale)>.0001){cancelGesture();return;}
      const rawX=(event.clientX-value.startX)/value.scale,rawY=(event.clientY-value.startY)/value.scale;
      const snap=value.kind==='move'&&!event.altKey?snapMove(value.rects[0].hit,rawX,rawY,[{x:0,y:0,width:model.width,height:model.height},...model.hits.filter(hit=>!value.rects.some(item=>hit.path===item.path||hit.path.startsWith(item.path+'/')))],5/value.scale):null;
      const dx=snap?.x??rawX,dy=snap?.y??rawY;activeGesture.current=null;setGesture(null);setDrop(null);setGuides([]);ignoreClick.current=Math.hypot(dx,dy)>.25;release(value.target,value.pointer);
      if(Math.hypot(dx,dy)>.25){if(value.kind==='sort'){const target=findDrop(event.clientX,event.clientY,value);if(target)void sortSelection(value.paths,target.path,target.after,value.context);}else void applyGeometry(value.rects.map(item=>changeFor(value,item,dx,dy)),value.context);}
    }
    if(selection&&selection.pointer===event.pointerId){
      if(!canvasLayoutReady(selection.context)||Math.abs(paper.getBoundingClientRect().width/model.width-selection.scale)>.0001){cancelGesture();return;}
      const left=Math.min(selection.x,selection.endX),right=Math.max(selection.x,selection.endX),top=Math.min(selection.y,selection.endY),bottom=Math.max(selection.y,selection.endY);
      const paths=[...new Set(model.hits.filter(hit=>hit.x<right&&hit.y<bottom&&hit.x+hit.width>left&&hit.y+hit.height>top).map(hit=>hit.path).filter(Boolean))];
      activeMarquee.current=null;setMarquee(null);release(selection.target,selection.pointer);
      if(Math.hypot(right-left,bottom-top)*selection.scale<=4)window.document.dispatchEvent(new CustomEvent('designer-select-page',{detail:{page}}));
      else void bridge.select(paths.filter(path=>!paths.some(other=>other!==path&&path.startsWith(other+'/'))));
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
  const gridSelection=!!document&&state.selectedPaths.some(path=>{try{return !!at(document!,path).closest('item')?.closest('grid');}catch{return false;}});
  const gridRootSelection=!!document&&flowPaths.length===1&&at(document,flowPaths[0]).tagName==='grid';
  const flowBounds=flowRects.length?{x:Math.min(...flowRects.map(hit=>hit.x)),y:Math.min(...flowRects.map(hit=>hit.y)),right:Math.max(...flowRects.map(hit=>hit.x+hit.width)),bottom:Math.max(...flowRects.map(hit=>hit.y+hit.height))}:null;
  const insertWidth=96/scale,insertHeight=25/scale,insertGap=8/scale,origin=blankOrigin??{x:30,y:30};
  const insertCandidates=[
    {x:origin.x+insertGap,y:origin.y+insertGap},
    {x:origin.x+insertGap,y:origin.y-insertHeight-insertGap},
    {x:origin.x-insertWidth-insertGap,y:origin.y+insertGap},
    ...[30,...model.hits.map(hit=>hit.y+hit.height+insertGap)].sort((a,b)=>a-b).map(y=>({x:30,y}))
  ];
  const insertPosition=insertCandidates.find(point=>point.x>=insertGap&&point.y>=insertGap&&point.x+insertWidth<=model.width-insertGap&&point.y+insertHeight<=model.height-insertGap&&!model.hits.some(hit=>point.x<hit.x+hit.width+insertGap&&point.x+insertWidth>hit.x-insertGap&&point.y<hit.y+hit.height+insertGap&&point.y+insertHeight>hit.y-insertGap));
  return <div className="studio-surface" ref={surface} tabIndex={0} data-gesture={gesture?.kind} aria-label={'第 '+(page+1)+' 页编辑层'} onPointerMove={moving} onPointerLeave={()=>setHoveredGridItem(null)} onPointerUp={stop} onPointerCancel={()=>cancelGesture()} onLostPointerCapture={()=>cancelGesture()}
    onKeyDown={event=>{
      if(event.key==='Escape'&&(gesture||press.current||marquee)){event.preventDefault();event.stopPropagation();cancelGesture();return;}
      if((event.target as Element).closest('.studio-rich,input,select,button'))return;
      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();cancelGesture();return;}
      if(['Enter','F2'].includes(event.key)&&state.selectedPaths.length===1){
        try{const path=state.selectedPaths[0],node=at(xml(state.source),path);
          if(textTags.has(node.tagName)||contentContainer(node)){event.preventDefault();event.stopPropagation();focusText(path);}
        }catch(error){fail(error);}
      }
    }}
    onPaste={event=>{const file=[...event.clipboardData.files].find(file=>['image/png','image/jpeg'].includes(file.type));if(file){event.preventDefault();void bridge.paste(file,page,20,20);}}}>
    <div className="studio-background" onPointerDown={event=>{
      if(event.button!==0||state.busy)return;
      activeContainer=undefined;if(activeEditors)void finishText();
      const current=bridge.snapshot();if(current.busy)return;const context=canvasContext(current);
      if(!canvasLayoutReady(canvasContext(state),current)){blankClick.current={clientX:event.clientX,clientY:event.clientY,pointerId:event.pointerId,currentTarget:event.currentTarget,context};event.currentTarget.setPointerCapture(event.pointerId);return;}
      const bounds=paper.getBoundingClientRect(),x=(event.clientX-bounds.left)/scale,y=(event.clientY-bounds.top)/scale;
      setBlankOrigin({x,y});
      const next:Marquee={x,y,endX:x,endY:y,pointer:event.pointerId,target:event.currentTarget,context,scale};activeMarquee.current=next;setMarquee(next);event.currentTarget.setPointerCapture(event.pointerId);
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
      const gridItem=source?.tagName==='item'&&!!source.closest('grid');
      const itemOwner=source?.closest('item'),gridItemPath=itemOwner?.closest('grid')?pathOf(itemOwner):undefined;
      const selected=active||gridItem&&state.selectedPaths.some(path=>path.startsWith(hit.path+'/'));
      return <div key={hit.path+':'+index} className={'studio-object'+(selected&&!['bullet','numbered'].includes(source?.tagName||'')?' is-selected':'')+(gridItem?' studio-grid-item'+(hoveredGridItem===hit.path?' is-grid-hovered':''):'')+(source?.tagName==='grid'?' studio-grid-root':'')+(editingThis?' is-editing':'')} data-studio-path={hit.path} data-grid-item-path={gridItemPath}
        style={{left:x/model.width*100+'%',top:y/model.height*100+'%',width:w/model.width*100+'%',height:h/model.height*100+'%',visibility:reflow>0&&isTail(hit)&&y>=tailLimit?'hidden':undefined,zIndex:editingThis?40:2+hit.path.split('/').length*2+(active?1:0)}}
        onClick={event=>{event.stopPropagation();if(ignoreClick.current){ignoreClick.current=false;return;}if(!(event.target as Element).closest('.studio-rich,button'))void open(hit,event);}}
        onDoubleClick={event=>{event.preventDefault();event.stopPropagation();cancelPress();if(!(event.target as Element).closest('.studio-rich,button'))void open(hit,event,true);}}
        onContextMenu={event=>{cancelPress();event.preventDefault();event.stopPropagation();bridge.context(event.nativeEvent,hit.path);}}
        onPointerDown={event=>hold(event,hit)}>
        {value&&<div className="studio-ghost" style={{backgroundImage:`url("${paper.querySelector<HTMLImageElement>('.page-image')?.src||''}")`,backgroundSize:`${model.width*scale*w/hit.width}px ${model.height*scale*h/hit.height}px`,backgroundPosition:`${-hit.x*scale*w/hit.width}px ${-hit.y*scale*h/hit.height}px`,transform:change?.rotation!==undefined?`rotate(${change.rotation}deg)`:undefined}}/>}
        {mode==='convenient'&&!editingThis&&!value&&source&&textTags.has(source.tagName)&&!source.querySelector('image,p,table')&&!source.hasAttribute('rotation')&&<TextPreview node={source} scale={scale} defaults={state.formats[hit.path]||{}}/>}
      </div>;
    })}
    <GridCanvas state={state} page={page} paper={paper} bridge={bridge} editing={!!editing} hoveredItem={hoveredGridItem} onEdit={focusText} onError={fail}/>
    <TableCanvas state={state} page={page} paper={paper} bridge={bridge} editing={!!editing} onError={fail}/>
    {flowBounds&&!editing&&!gridSelection&&<div className={'studio-flow-controls'+(gridRootSelection?' studio-grid-root-controls':'')} style={{left:flowBounds.x/model.width*100+'%',top:flowBounds.y/model.height*100+'%',width:(flowBounds.right-flowBounds.x)/model.width*100+'%',height:(flowBounds.bottom-flowBounds.y)/model.height*100+'%'}}>
      <button className="studio-drag-handle" aria-label="拖动排序" disabled={geometryDisabled} title="拖动到插入线排序；也可用 Alt + ↑ / ↓" onPointerDown={event=>begin(event,'move',{...flowRects[0],path:flowPaths[0]})} onClick={event=>event.stopPropagation()}>⠿</button>
      <span className="studio-layout-badge">自动占位{flowPaths.length>1?' · '+flowPaths.length+' 项':''}</span>
      {!gesture&&<><button className="studio-block-action studio-block-more" aria-label="组件操作" aria-haspopup="menu" onClick={event=>openMenu(event,'actions',flowPaths[0])}>···</button>
        <button className="studio-block-action studio-insert-before" aria-label="在前面插入" title="在组件前插入内容" aria-haspopup="menu" onClick={event=>openMenu(event,'insert',flowPaths[0],false)}>+</button>
        <button className="studio-block-action studio-insert-after" aria-label="在后面插入" title="在组件后插入内容" aria-haspopup="menu" onClick={event=>openMenu(event,'insert',flowPaths.at(-1),true)}>+</button></>}
    </div>}
    {state.selectedPaths.filter(path=>{try{return editing?.path!==path&&document&&!!fixedBox(at(document,path));}catch{return false;}}).map(path=>hitFor(state,path,page)?.hit).filter((hit):hit is Hit=>!!hit).map((hit,index)=>{
      const value=gesture?.rects.find(item=>item.path===hit.path),change=value&&gesture?changeFor(gesture,value,delta.x,delta.y):null;
      return <div key={hit.path+':'+index} className="studio-controls" style={{left:(change?.x??hit.x)/model.width*100+'%',top:(change?.y??hit.y)/model.height*100+'%',width:(change?.width??hit.width)/model.width*100+'%',height:(change?.height??hit.height)/model.height*100+'%'}}>
        <button className="studio-drag-handle" aria-label="拖动固定组件" disabled={geometryDisabled} title="按组件的固定位置属性自由移动" onPointerDown={event=>begin(event,'move',hit)} onClick={event=>event.stopPropagation()}>⠿</button>
        <span className="studio-layout-badge">固定 · {fixedBox(at(document!,hit.path))?.getAttribute('body-flow')==='AVOID'?'避让':'遮盖'}</span>
        {gesture&&<span className="studio-dimensions">{Math.round(change?.x??hit.x)}, {Math.round(change?.y??hit.y)} · {Math.round(change?.width??hit.width)} × {Math.round(change?.height??hit.height)} pt</span>}
        {['nw','n','ne','e','se','s','sw','w'].map(edge=><button key={edge} className={'studio-resize-edge edge-'+edge} disabled={geometryDisabled} aria-label={'调整对象大小 '+edge} title="拖动边缘调整大小；也可在检查器输入尺寸" onPointerDown={event=>begin(event,edge,hit)} onClick={event=>event.stopPropagation()}/>)}
        <button className="studio-rotate" aria-label="旋转对象" disabled={geometryDisabled} title="拖动旋转，按 5° 吸附" onPointerDown={event=>begin(event,'rotate',hit)} onClick={event=>event.stopPropagation()}>↻</button>
        {!gesture&&<button className="studio-block-action studio-block-more" aria-label="组件操作" aria-haspopup="menu" onClick={event=>openMenu(event,'actions',hit.path)}>···</button>}
      </div>;
    })}
    {editing&&<div ref={editingLayer} className="studio-editing-surface studio-object is-editing" style={{left:editing.bounds.x/model.width*100+'%',top:editing.bounds.y/model.height*100+'%',width:editing.bounds.width/model.width*100+'%',minHeight:editing.bounds.height/model.height*paper.getBoundingClientRect().height}}>
      <Rich key={editing.key} editing={editing} page={page} scale={scale} finish={focus=>{setEditing(null);if(focus)requestAnimationFrame(()=>surface.current?.focus({preventScroll:true}));}}/>
    </div>}
    {!editing&&!flowBounds&&(!state.selectedPaths.length||!model.hits.length||state.selectedPaths.length===1&&state.selectedPaths[0]===model.ownerPath)&&<button className="studio-empty-insert studio-block-action" data-gutter={!insertPosition||undefined} style={{left:insertPosition?insertPosition.x/model.width*100+'%':-28,top:Math.min(insertPosition?.y??origin.y,model.height-insertHeight-insertGap)/model.height*100+'%'}} aria-label="插入内容" title="在当前页正文末尾插入内容" aria-haspopup="menu" onClick={event=>openMenu(event,'insert')}>{insertPosition?'+ 插入内容':'+'}</button>}
    {menu&&createPortal(<div className="studio-block-menu" role="menu" aria-label={menu.kind==='insert'?'插入内容':'组件操作'} style={{left:menu.left,top:menu.top,maxHeight:Math.max(80,window.innerHeight-menu.top-12),overflowY:'auto'}}>
      <div className="studio-menu-title">{menu.kind==='insert'?(menu.after?'在后面插入':'在前面插入'):state.selectedPaths.length>1?state.selectedPaths.length+' 个组件':'组件操作'}</div>
      {menu.kind==='insert'?blockGroups.map(([group,kinds])=><div key={group} role="group" aria-label={group}><div className="studio-menu-title">{group}</div>{kinds.map(kind=><button key={kind} role="menuitem" onClick={()=>insert(kind)}>{blockKinds.find(([value])=>value===kind)![1]}</button>)}</div>):<>
        <button role="menuitem" onClick={duplicate}>创建副本 <kbd>Ctrl D</kbd></button>
        {flowPaths.length>0&&<><button role="menuitem" onClick={()=>menuAction(current=>sortStep(current,-1))}>上移组件 <kbd>Alt ↑</kbd></button><button role="menuitem" onClick={()=>menuAction(current=>sortStep(current,1))}>下移组件 <kbd>Alt ↓</kbd></button></>}
        <button role="menuitem" className="danger" onClick={()=>menuAction(()=>removeSelection())}>删除组件 <kbd>Delete</kbd></button>
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
  const containers=document.getElementById('studio-container-inspector');if(containers)createRoot(containers).render(<ContainerInspector/>);
  document.addEventListener('keydown',event=>{
    if(event.defaultPrevented||snapshot.busy||!event.target||!(event.target as Element).closest('.studio-surface')||(event.target as Element).closest('.ProseMirror,input,select'))return;
    const directions:Record<string,[number,number]>={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]};
    const duplicate=(event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='d';
    if(duplicate||['Delete','Backspace'].includes(event.key)){
      event.preventDefault();event.stopImmediatePropagation();
      try{
        const current=bridge.snapshot(),document=xml(current.source);
        // Page/document properties do not select a component for canvas commands.
        if(!current.selectedPaths.length||current.selectedPaths.some(path=>['document','section'].includes(at(document,path).tagName)))return;
        if(duplicate){const result=duplicateBlocks(current);void bridge.apply(result.source,result.ids,result.paths).catch(fail);}
        else removeSelection();
      }catch(error){fail(error);}return;
    }
    if(directions[event.key]){
      if(event.altKey&&['ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();event.stopImmediatePropagation();sortStep(snapshot,event.key==='ArrowUp'?-1:1);return;}
      const current=bridge.snapshot(),document=xml(current.source);if(!current.selectedPaths.length||!current.selectedPaths.every(path=>!!fixedBox(at(document,path))))return;
      const [dx,dy]=directions[event.key],step=event.shiftKey?10:1;event.preventDefault();event.stopImmediatePropagation();
      if(!canvasLayoutReady(canvasContext(snapshot),current))return;
      void applyGeometry(current.selectedPaths.map(path=>{const placed=hitFor(current,path,current.currentPage)??hitFor(current,path);return {path,x:Math.max(0,(placed?.hit.x??0)+dx*step),y:Math.max(0,(placed?.hit.y??0)+dy*step)};}),canvasContext(current));
    }
  },true);
  for(const [name,family,weight] of [['Regular.ttf','Export4j body','400'],['Bold.ttf','Export4j body','700'],['Chinese.ttf','Export4j Chinese','400']]){
    void bridge.font(name).then(buffer=>new FontFace(family,buffer,{weight}).load()).then(font=>document.fonts.add(font)).catch(error=>{if(name==='Regular.ttf')fail(error);});
  }
}
