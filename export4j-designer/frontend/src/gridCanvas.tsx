// SPDX-License-Identifier: Apache-2.0
import React,{useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {createPortal} from 'react-dom';
import {gridContext,gridEdit,type GridContext,type GridOperation} from './grid';
import {at,xml,type Bridge,type Hit,type Snapshot} from './types';
import {pathOf} from './blocks';

export function gridWidthLabel(span:number,tracks:number):string {
  if(!Number.isSafeInteger(span)||span<1||!Number.isSafeInteger(tracks)||tracks<1)return '动态宽度';
  if(span===tracks)return '整行';
  if(span*2===tracks)return '半宽';
  if(span*3===tracks)return '三分之一';
  if(span*4===tracks)return '四分之一';
  if(span*3===tracks*2)return '三分之二';
  if(span*4===tracks*3)return '四分之三';
  const gcd=(a:number,b:number):number=>b?gcd(b,a%b):a,divisor=gcd(span,tracks);
  return `${span/divisor}/${tracks/divisor} 宽`;
}

interface Cell {path:string;id:string;gridPath:string;row:number;start:number;span:number;tracks:number;editable:boolean;empty:boolean;reason?:string}
interface PlacedCell extends Cell {hit:Hit;page:number}
interface Boundary {left:PlacedCell;right:PlacedCell;pitch:number;gap:number}
type Drag = {pointer:number;source:string;target:HTMLElement;startX:number;startY:number} &
  ({kind:'boundary';boundary:Boundary;start:number}|{kind:'swap';cell:PlacedCell;over:PlacedCell|null;x:number;y:number});

function cellsFor(source:string):Cell[] {
  const document=xml(source),cells:Cell[]=[];
  for(const grid of document.querySelectorAll('grid')){
    const gridPath=pathOf(grid),context=gridContext(source,gridPath);if(!context)continue;
    for(const item of grid.querySelectorAll('item')){
      if(item.closest('grid')!==grid)continue;
      const path=pathOf(item),row=Number(item.getAttribute('row')),start=Number(item.getAttribute('column-start')),span=Number(item.getAttribute('column-span'));
      if(![row,start,span].every(value=>Number.isSafeInteger(value)&&value>0))continue;
      const empty=[...item.children].every(child=>child.tagName==='p'&&!child.children.length&&!child.textContent?.trim());
      cells.push({path,id:item.getAttribute('id')||path,gridPath,row,start,span,tracks:context.tracks,editable:context.editable,empty,reason:context.reason});
    }
  }
  return cells.sort((a,b)=>a.gridPath.localeCompare(b.gridPath)||a.row-b.row||a.start-b.start);
}

function neighboringBoundary(context:GridContext,path:string,direction:number):GridOperation|null {
  const index=context.items.findIndex(item=>item.path===path),item=context.items[index];if(!item||!context.editable)return null;
  const right=context.items[index+1],left=context.items[index-1];
  if(right&&item.start+item.span===right.start&&item.span+direction>=1&&right.span-direction>=1)
    return {type:'boundary',leftId:item.id,rightId:right.id,start:right.start+direction};
  if(left&&left.start+left.span===item.start&&item.span+direction>=1&&left.span-direction>=1)
    return {type:'boundary',leftId:left.id,rightId:item.id,start:item.start-direction};
  return null;
}

const styles=`
.studio-grid-canvas{position:absolute;inset:0;pointer-events:none;z-index:52}
.studio-grid-canvas button{font:inherit}
.studio-grid-empty-hint{position:absolute;display:flex;align-items:center;padding-left:9px;color:#8b96a6;font-size:11px;pointer-events:none}
.studio-grid-canvas button:focus-visible,.studio-grid-divider:focus-visible,.studio-grid-quickbar button:focus-visible{outline:3px solid var(--accent,#2969b1);outline-offset:3px}
.studio-grid-context-slot{flex-basis:100%;width:100%;min-width:0}
.studio-grid-context-slot:empty{display:none}
.studio-grid-divider{position:absolute;display:flex;align-items:center;justify-content:center;width:30px;padding:0;transform:translate(-50%,-50%);border:0;background:transparent;pointer-events:auto;cursor:col-resize;touch-action:none;z-index:2}
.studio-grid-divider::before{content:'';position:absolute;top:0;bottom:0;width:3px;border-radius:3px;background:var(--accent,#2969b1);opacity:.6}
.studio-grid-divider>span{position:relative;display:grid;place-items:center;width:22px;height:min(28px,100%);min-height:0;border:1px solid var(--accent,#2969b1);border-radius:7px;background:var(--surface,#fff);color:var(--accent,#2969b1);font-size:15px;box-shadow:0 1px 3px #0002}
.studio-grid-divider:hover>span,.studio-grid-divider:focus-visible>span{background:#edf4ff}
.studio-grid-item-grip{position:absolute;display:flex;align-items:center;justify-content:center;min-height:0;padding:0;border:1px solid #92a4bb;border-radius:4px;background:var(--surface,#fff);color:#34516f;pointer-events:auto;cursor:grab;touch-action:none}
.studio-grid-item-grip:active{cursor:grabbing}
.studio-grid-item-grip[disabled]{display:none}
.studio-grid-quickbar{display:flex;align-items:center;flex-wrap:wrap;gap:3px;width:100%;min-width:0;padding:6px 0 2px;border-top:1px solid var(--line,#e1e5ed);background:var(--panel,#fff);pointer-events:auto}
.studio-grid-context-label{max-width:100%;padding:4px 8px 4px 0;color:var(--muted,#52647b);font-size:11px;overflow-wrap:anywhere}
.studio-grid-quickbar>button{min-height:32px;padding:4px 8px;border:0;border-radius:5px;background:transparent;color:#263f5d;font-size:12px}
.studio-grid-quickbar button:hover:not(:disabled){background:#edf4ff}
.studio-grid-quickbar button:disabled{opacity:.4;cursor:default}
.studio-grid-more{display:grid;flex-basis:100%;grid-template-columns:repeat(auto-fit,minmax(min(110px,100%),1fr));gap:4px;width:100%;min-width:0;padding:6px;border-radius:5px;background:var(--subtle,#f2f5fa)}
.studio-grid-more button{min-height:36px;padding:6px;border:0;border-radius:5px;background:#f2f5fa;color:#263f5d;font-size:12px}
.studio-grid-more small{grid-column:1/-1;white-space:normal;padding:4px;color:#52647b;line-height:1.5}
.studio-grid-preview-cell{position:absolute;border:2px solid var(--accent,#2969b1);background:#3c82d51a;box-sizing:border-box;pointer-events:none}
.studio-grid-preview-cell>span{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);padding:3px 7px;border-radius:5px;background:#fff;color:#23476d;font-size:12px;white-space:nowrap}
.studio-grid-swap-source{position:absolute;box-sizing:border-box;border:2px dashed var(--accent,#2969b1);background:#3c82d51a;pointer-events:none}
.studio-grid-swap-target{position:absolute;z-index:65;box-sizing:border-box;border:3px solid var(--accent,#2969b1);background:#3c82d524;pointer-events:none}
.studio-grid-swap-target>span{display:inline-block;padding:3px 7px;background:var(--accent,#2969b1);color:#fff;font-size:12px}
.studio-grid-drag-status{position:fixed;z-index:250;pointer-events:none;padding:9px 12px;border:1px solid #9bb0c8;border-radius:8px;background:#fff;color:#263f5d;box-shadow:0 3px 12px #182e4926;font:12px/1.5 sans-serif}
.studio-grid-drag-status small{display:block;color:#52647b}
.studio-object.studio-grid-item{outline:1px dashed #8095af40;box-shadow:none;background:transparent}
.studio-object.studio-grid-item.is-grid-hovered{outline:1px solid #8095af;background:transparent}
.studio-object.studio-grid-item.is-selected{outline:2px solid var(--accent,#2969b1);box-shadow:none;background:transparent}
.studio-object.studio-grid-root.is-selected,.studio-flow-controls.studio-grid-root-controls{outline:1px solid #8095af;box-shadow:none;background:transparent}
.studio-grid-readonly{flex-basis:100%;color:#73531d;font-size:12px;line-height:1.5}
@media(max-width:700px){.studio-grid-quickbar>button{min-height:36px}.studio-grid-context-label{flex-basis:100%}}
`;

export function GridCanvas({state,page,paper,bridge,editing,hoveredItem,onEdit,onError}:{state:Snapshot;page:number;paper:HTMLElement;bridge:Bridge;editing:boolean;hoveredItem:string|null;onEdit:(path:string)=>void;onError:(error:unknown)=>void}){
  const model=state.pages[page],scale=paper.getBoundingClientRect().width/model.width;
  const cells=useMemo(()=>{try{return cellsFor(state.source);}catch{return [];}},[state.source]);
  const placed=useMemo(()=>cells.flatMap(cell=>state.pages.flatMap((model,page)=>model.hits.filter(hit=>hit.path===cell.path).map(hit=>({...cell,hit,page})))),[cells,state.pages]);
  const here=placed.filter(cell=>cell.page===page),[drag,setDrag]=useState<Drag|null>(null),activeDrag=useRef<Drag|null>(null),[more,setMore]=useState(false),moreToggle=useRef<HTMLButtonElement>(null);
  const selectedPath=state.selectedPaths.length===1?state.selectedPaths[0]:undefined;
  const selectedCell=selectedPath?(placed.find(cell=>cell.page===state.currentPage&&(selectedPath===cell.path||selectedPath.startsWith(cell.path+'/')))??placed.find(cell=>selectedPath===cell.path||selectedPath.startsWith(cell.path+'/'))):undefined;
  const active=selectedCell?.page===page?selectedCell:undefined,[inspectorHost,setInspectorHost]=useState<HTMLElement|null>(null);
  useLayoutEffect(()=>{
    const locate=()=>setInspectorHost(window.document.getElementById('studio-grid-context'));locate();
    window.document.addEventListener('designer-grid-inspector-ready',locate);return ()=>window.document.removeEventListener('designer-grid-inspector-ready',locate);
  },[]);
  const disabled=state.busy||state.pending;
  const controlsDisabled=disabled||editing||!!drag;
  const boundaries:Boundary[]=here.flatMap(left=>{
    const right=here.find(right=>right.gridPath===left.gridPath&&right.row===left.row&&right.start===left.start+left.span);
    if(!active||active.gridPath!==left.gridPath||active.row!==left.row||!right||!left.editable||!right.editable||Math.abs(left.hit.y-right.hit.y)>.5)return [];
    const gap=right.hit.x-left.hit.x-left.hit.width,pitch=(right.hit.x+right.hit.width-left.hit.x+gap)/(left.span+right.span);
    return gap>=0&&pitch>0?[{left,right,gap,pitch}]:[];
  });
  const apply=(path:string,operation:GridOperation)=>{
    try{
      const current=bridge.snapshot();if(current.busy)return;
      const context=gridContext(current.source,path),itemId=context?.itemPath?at(xml(current.source),context.itemPath).getAttribute('id'):null;
      const result=gridEdit(current,path,operation),selectionId=itemId&&result.ids.includes(itemId)?itemId:undefined;
      if(more)moreToggle.current?.focus();setMore(false);
      void bridge.apply(result.source,selectionId?[selectionId]:undefined,selectionId?undefined:result.paths.slice(0,1)).catch(onError);
    }catch(error){onError(error);}
  };
  const cancel=()=>{
    const value=activeDrag.current;activeDrag.current=null;setDrag(null);
    if(value?.target.hasPointerCapture(value.pointer))value.target.releasePointerCapture(value.pointer);
  };
  useEffect(()=>{cancel();setMore(false);},[state.session,state.source]);
  useEffect(()=>{setMore(false);},[active?.path]);
  useEffect(()=>{
    if(!more)return;
    const key=(event:KeyboardEvent)=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setMore(false);moreToggle.current?.focus();}};
    window.document.addEventListener('keydown',key,true);
    return ()=>window.document.removeEventListener('keydown',key,true);
  },[more]);
  const findTarget=(x:number,y:number,cell:PlacedCell):PlacedCell|null=>{
    for(const targetPaper of window.document.querySelectorAll<HTMLElement>('#pages .page')){
      const index=Number(targetPaper.closest<HTMLElement>('.canvas-sheet')?.dataset.page),bounds=targetPaper.getBoundingClientRect(),model=state.pages[index];
      if(!model||x<bounds.left||x>bounds.right||y<bounds.top||y>bounds.bottom)continue;
      const localX=(x-bounds.left)*model.width/bounds.width,localY=(y-bounds.top)*model.height/bounds.height;
      return placed.find(target=>target.page===index&&target.gridPath===cell.gridPath&&target.path!==cell.path&&target.editable&&localX>=target.hit.x&&localX<=target.hit.x+target.hit.width&&localY>=target.hit.y&&localY<=target.hit.y+target.hit.height)??null;
    }
    return null;
  };
  const update=(event:PointerEvent):Drag|null=>{
    const current=activeDrag.current;if(!current||current.pointer!==event.pointerId)return null;
    if(current.kind==='boundary'){
      const {left,right,pitch}=current.boundary,start=Math.max(left.start+1,Math.min(right.start+right.span-1,right.start+Math.round((event.clientX-current.startX)/(pitch*scale))));
      return {...current,start};
    }
    return {...current,x:event.clientX,y:event.clientY,over:findTarget(event.clientX,event.clientY,current.cell)};
  };
  useEffect(()=>{
    if(!drag)return;
    const move=(event:PointerEvent)=>{const next=update(event);if(next){event.preventDefault();activeDrag.current=next;setDrag(next);}};
    const stop=(event:PointerEvent)=>{
      const next=update(event);if(!next)return;event.preventDefault();event.stopPropagation();cancel();
      if(bridge.snapshot().source!==next.source)return;
      if(next.kind==='boundary'&&next.start!==next.boundary.right.start)apply(next.boundary.left.path,{type:'boundary',leftId:next.boundary.left.id,rightId:next.boundary.right.id,start:next.start});
      if(next.kind==='swap'&&next.over&&Math.hypot(event.clientX-next.startX,event.clientY-next.startY)>4)apply(next.cell.path,{type:'swap',target:next.over.path});
    };
    const cancelled=(event:PointerEvent)=>{if(activeDrag.current?.pointer===event.pointerId)cancel();};
    const key=(event:KeyboardEvent)=>{if(event.key==='Escape'){event.preventDefault();event.stopImmediatePropagation();cancel();}};
    window.document.addEventListener('pointermove',move,{passive:false});window.document.addEventListener('pointerup',stop,true);window.document.addEventListener('pointercancel',cancelled,true);window.document.addEventListener('keydown',key,true);
    return ()=>{window.document.removeEventListener('pointermove',move);window.document.removeEventListener('pointerup',stop,true);window.document.removeEventListener('pointercancel',cancelled,true);window.document.removeEventListener('keydown',key,true);};
  },[!!drag,state.source,state.pages,scale]);
  useEffect(()=>()=>{const current=activeDrag.current;if(current?.target.hasPointerCapture(current.pointer))current.target.releasePointerCapture(current.pointer);activeDrag.current=null;},[]);
  const begin=(event:React.PointerEvent<HTMLButtonElement>,value:{kind:'boundary';boundary:Boundary}|{kind:'swap';cell:PlacedCell})=>{
    event.preventDefault();event.stopPropagation();if(event.button!==0||disabled||editing)return;
    const common={pointer:event.pointerId,target:event.currentTarget,source:state.source,startX:event.clientX,startY:event.clientY};
    const next:Drag=value.kind==='boundary'?{...common,...value,start:value.boundary.right.start}:{...common,...value,over:null,x:event.clientX,y:event.clientY};
    activeDrag.current=next;setDrag(next);setMore(false);event.currentTarget.setPointerCapture(event.pointerId);
    void bridge.choose(value.kind==='boundary'?value.boundary.left.path:value.cell.path);
  };
  const rect=(hit:Hit):React.CSSProperties=>({left:hit.x/model.width*100+'%',top:hit.y/model.height*100+'%',width:hit.width/model.width*100+'%',height:hit.height/model.height*100+'%'});
  const context=active?gridContext(state.source,active.path):null,ordered=active?cells.filter(cell=>cell.gridPath===active.gridPath):[],index=ordered.findIndex(cell=>cell.path===active?.path);
  const narrow=context&&active?neighboringBoundary(context,active.path,-1):null,widen=context&&active?neighboringBoundary(context,active.path,1):null;
  const next=context?.items[(context.items.findIndex(item=>item.path===active?.path)??-1)+1],canMerge=!!active&&!!next&&active.start+active.span===next.start;
  const gripStyle=(cell:PlacedCell):React.CSSProperties|null=>{
    const previous=here.filter(other=>other.gridPath===cell.gridPath&&other.row===cell.row&&other.hit.x+other.hit.width<=cell.hit.x+.01);
    const leftSpace=(cell.hit.x-Math.max(0,...previous.map(other=>other.hit.x+other.hit.width)))*scale;
    if(leftSpace>=22)return {left:cell.hit.x*scale-22,top:cell.hit.y*scale,width:20,height:Math.min(20,cell.hit.height*scale)};
    const previousBottom=Math.max(0,...model.hits.filter(hit=>hit.height>0&&hit.y+hit.height<=cell.hit.y+.01).map(hit=>hit.y+hit.height));
    const height=Math.min(18,(cell.hit.y-previousBottom)*scale-2);
    return height>=5?{left:cell.hit.x*scale+2,top:cell.hit.y*scale-height-1,width:20,height}:null;
  };
  let targetPaper:HTMLElement|undefined;if(drag?.kind==='swap'&&drag.over)targetPaper=[...window.document.querySelectorAll<HTMLElement>('#pages .page')].find(node=>Number(node.closest<HTMLElement>('.canvas-sheet')?.dataset.page)===drag.over!.page);
  return <div className="studio-grid-canvas" data-grid-drag={drag?.kind} onLostPointerCapture={event=>{if(activeDrag.current?.pointer===event.pointerId)cancel();}}>
    <style>{styles}</style>
    {!editing&&here.filter(cell=>cell.empty).map(cell=><span key={'empty-'+cell.path} className="studio-grid-empty-hint" aria-hidden="true" style={rect(cell.hit)}>双击编写</span>)}
    {!editing&&boundaries.map(boundary=>{
      const {left,right}=boundary,moving=drag?.kind==='boundary'&&drag.boundary.left.path===left.path,newStart=moving?drag.start:right.start,x=left.hit.x+(newStart-left.start)*boundary.pitch-boundary.gap/2;
      return <button key={left.path+'-'+right.path} className="studio-grid-divider" role="separator" aria-orientation="vertical" aria-label={'调整两格宽度 '+left.id+' '+right.id} aria-valuemin={left.start+1} aria-valuemax={right.start+right.span-1} aria-valuenow={newStart} aria-valuetext={gridWidthLabel(newStart-left.start,left.tracks)+' / '+gridWidthLabel(right.start+right.span-newStart,left.tracks)} data-grid-left={left.id} data-grid-right={right.id} disabled={disabled} title="拖动分隔线调整两格宽度；方向键也可逐格调整" style={{left:x/model.width*100+'%',top:(left.hit.y+Math.max(left.hit.height,right.hit.height)/2)/model.height*100+'%',height:Math.min(60,Math.max(left.hit.height,right.hit.height)*scale)}} onPointerDown={event=>begin(event,{kind:'boundary',boundary})} onClick={event=>event.stopPropagation()} onKeyDown={event=>{
        if(event.key==='ArrowLeft'||event.key==='ArrowRight'){event.preventDefault();event.stopPropagation();const start=right.start+(event.key==='ArrowLeft'?-1:1);if(start>left.start&&start<right.start+right.span)apply(left.path,{type:'boundary',leftId:left.id,rightId:right.id,start});}
      }}><span aria-hidden="true">↔</span></button>;
    })}
    {!editing&&here.filter(cell=>cell.editable&&(cell.path===active?.path||cell.path===hoveredItem)).map(cell=>{
      const style=gripStyle(cell);return style&&<button key={cell.path} className="studio-grid-item-grip" data-grid-item-path={cell.path} aria-label={'拖动栅格项 '+cell.id} title="拖到另一格交换位置；双击编辑此格；检查器内也可交换顺序" disabled={disabled} style={style} onPointerDown={event=>begin(event,{kind:'swap',cell})} onClick={event=>{event.stopPropagation();void bridge.choose(cell.path);}} onDoubleClick={event=>{event.preventDefault();event.stopPropagation();cancel();onEdit(cell.path);}}><svg aria-hidden="true" viewBox="0 0 12 12" style={{width:12,height:'100%'}} fill="currentColor">{[3,6,9].flatMap(y=>[4,8].map(x=><circle key={x+'-'+y} cx={x} cy={y} r="1"/>))}</svg></button>;
    })}
    {active&&inspectorHost&&createPortal(<div className="studio-grid-quickbar" role="toolbar" aria-label="栅格项操作" onPointerDown={event=>event.stopPropagation()} onClick={event=>event.stopPropagation()}>
      <span className="studio-grid-context-label" title={`起始轨 ${active.start}，跨 ${active.span} 轨`}>{`第 ${active.row} 行 · ${gridWidthLabel(active.span,active.tracks)}`}</span>
      <button disabled={controlsDisabled||!active.editable} onClick={()=>window.document.dispatchEvent(new CustomEvent('designer-layout-grid-row',{detail:{path:active.path}}))}>本行布局</button>
      <button disabled={controlsDisabled||!active.editable||active.span<2} onClick={()=>apply(active.path,{type:'split-item'})}>拆分</button>
      <button disabled={controlsDisabled||!active.editable||!canMerge} onClick={()=>apply(active.path,{type:'merge-next'})}>与右格合并</button>
      <button disabled={controlsDisabled||!active.editable} onClick={()=>window.document.dispatchEvent(new CustomEvent('designer-add-grid-row',{detail:{path:active.path}}))}>添加行</button>
      <button ref={moreToggle} disabled={controlsDisabled} aria-label="栅格宽度与顺序" aria-expanded={more} onClick={()=>setMore(!more)}>更多</button>
      {more&&<div className="studio-grid-more" role="group" aria-label="栅格宽度与顺序操作">
        <button disabled={controlsDisabled||!narrow} onClick={()=>narrow&&apply(active.path,narrow)}>更窄</button><button disabled={controlsDisabled||!widen} onClick={()=>widen&&apply(active.path,widen)}>更宽</button>
        <button disabled={controlsDisabled||!active.editable||index<=0} onClick={()=>apply(active.path,{type:'swap',target:ordered[index-1].path})}>向前交换</button><button disabled={controlsDisabled||!active.editable||index<0||index>=ordered.length-1} onClick={()=>apply(active.path,{type:'swap',target:ordered[index+1].path})}>向后交换</button>
        <small>{active.editable?'拖分隔线调整宽度，拖格子左上角的柄交换位置。':active.reason}</small>
      </div>}
      {!active.editable&&<span className="studio-grid-readonly" role="status">{active.reason}</span>}
    </div>,inspectorHost)}
    {drag?.kind==='boundary'&&(()=>{const {left,right,gap,pitch}=drag.boundary,leftSpan=drag.start-left.start,rightSpan=right.start+right.span-drag.start;return <><div className="studio-grid-preview-cell" style={rect({...left.hit,width:leftSpan*pitch-gap})}><span>{gridWidthLabel(leftSpan,left.tracks)}</span></div><div className="studio-grid-preview-cell" style={rect({...right.hit,x:left.hit.x+leftSpan*pitch,width:rightSpan*pitch-gap})}><span>{gridWidthLabel(rightSpan,right.tracks)}</span></div></>;})()}
    {drag?.kind==='swap'&&<><div className="studio-grid-swap-source" style={rect(drag.cell.hit)}/>{createPortal(<div className="studio-grid-drag-status" role="status" style={{left:drag.x+16,top:drag.y+16}}>交换格子位置<small>{drag.over?'释放，与第 '+drag.over.row+' 行的格子交换':'拖到同一栅格的另一格 · Esc 取消'}</small></div>,window.document.body)}{drag.over&&targetPaper&&createPortal(<div className="studio-grid-swap-target" style={{left:drag.over.hit.x/state.pages[drag.over.page].width*100+'%',top:drag.over.hit.y/state.pages[drag.over.page].height*100+'%',width:drag.over.hit.width/state.pages[drag.over.page].width*100+'%',height:drag.over.hit.height/state.pages[drag.over.page].height*100+'%'}}><span>释放以交换</span></div>,targetPaper)}</>}
  </div>;
}
