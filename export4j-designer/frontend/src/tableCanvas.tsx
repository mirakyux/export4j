// SPDX-License-Identifier: Apache-2.0
import React,{useEffect,useMemo,useRef,useState} from 'react';
import {containerContext,containerEdit,type ContainerOperation} from './containers';
import {tableGeometry,resizeTableColumns,type TableGeometry} from './tableWidths';
import type {Bridge,Snapshot} from './types';

const styles=`
.studio-table-divider{position:absolute;z-index:44;width:14px;padding:0;transform:translateX(-50%);border:0;border-radius:0;background:transparent;pointer-events:auto;cursor:col-resize;touch-action:none}
.studio-table-divider::before{content:'';position:absolute;left:6px;top:0;bottom:0;width:2px;background:var(--accent,#2969b1);opacity:.45}
.studio-table-divider:hover::before,.studio-table-divider:focus-visible::before{width:3px;opacity:1}
.studio-table-divider:focus-visible{outline:2px solid var(--accent,#2969b1);outline-offset:2px}
.studio-table-drag-label{position:absolute;left:50%;top:0;transform:translate(-50%,-100%);padding:4px 7px;background:var(--panel,#fff);border:1px solid var(--accent,#2969b1);color:var(--text,#263f5d);white-space:nowrap;font-size:11px;pointer-events:none}
.studio-table-actions{position:relative;max-width:100%;font-size:11px}.studio-table-actions>summary{padding:6px 9px;border:1px solid var(--line,#d6dce6);border-radius:4px;cursor:pointer;list-style:none;background:var(--panel,#fff)}
.studio-table-actions>div{position:absolute;top:100%;right:0;z-index:250;display:grid;gap:3px;min-width:180px;max-width:calc(100vw - 64px);padding:7px;background:var(--panel,#fff);border:1px solid var(--line,#d6dce6);box-shadow:0 3px 12px #182e4926;border-radius:5px}
.studio-table-actions button{text-align:left;width:100%}.studio-table-actions small{padding:4px;color:var(--muted,#647087)}
.studio-table-width-form{display:flex;align-items:center;gap:4px;font-size:11px}.studio-table-width-form input{width:68px;min-width:0;height:30px}.studio-table-width-form label{white-space:nowrap}
`;

export function TableTools({state,path,bridge,onError,finish}:{state:Snapshot;path:string;bridge:Bridge;onError:(error:unknown)=>void;finish:()=>Promise<void>}){
  let context:ReturnType<typeof containerContext>=null,geometry:TableGeometry|null=null;
  try{context=containerContext(state,path);geometry=tableGeometry(state,path,state.currentPage);}catch{/* Invalid drafts keep their existing error display. */}
  const details=useRef<HTMLDetailsElement>(null),[width,setWidth]=useState('');
  const column=context?.table?.column??0,activeWidth=geometry?.widths[column];
  useEffect(()=>setWidth(activeWidth===undefined?'':activeWidth.toFixed(3)),[activeWidth,column,state.source]);
  useEffect(()=>{if(details.current)details.current.open=false;},[path,state.session]);
  useEffect(()=>{
    const key=(event:KeyboardEvent)=>{if(event.key==='Escape'&&details.current?.open){event.preventDefault();event.stopPropagation();details.current.open=false;details.current.querySelector('summary')?.focus();}};
    window.document.addEventListener('keydown',key,true);
    return ()=>window.document.removeEventListener('keydown',key,true);
  },[]);
  if(!context?.table)return null;
  const disabled=state.busy||state.pending;
  const apply=async(operation:ContainerOperation)=>{
    try{if(details.current)details.current.open=false;await finish();const result=containerEdit(bridge.snapshot(),path,operation);await bridge.apply(result.source,result.ids,result.paths);}catch(error){onError(error);}
  };
  return <><style>{styles}</style><details ref={details} className="studio-table-actions">
    <summary aria-label="当前单元格的行列与合并">行列与合并</summary><div>
      <small>{context.table.row===undefined?'请选择单元格':`第 ${context.table.row+1} 行 · 第 ${column+1} 列`}</small>
      {context.kind==='table'&&<><button disabled={disabled||!context.table.canAddRow} onClick={()=>void apply({type:'add-row',after:false})}>在上方插入行</button><button disabled={disabled||!context.table.canAddRow} onClick={()=>void apply({type:'add-row'})}>在下方插入行</button></>}
      <button disabled={disabled||!context.table.canAddColumn} onClick={()=>void apply({type:'add-column',after:false})}>在左侧插入列</button>
      <button disabled={disabled||!context.table.canAddColumn} onClick={()=>void apply({type:'add-column'})}>在右侧插入列</button>
      <button disabled={disabled} onClick={()=>{if(details.current)details.current.open=false;void finish().then(()=>window.document.dispatchEvent(new CustomEvent('designer-table-layout',{detail:{path}}))).catch(onError);}}>选格与合并／拆分</button>
      {context.kind==='table'&&<button disabled={disabled||!context.table.canRemoveRow} onClick={()=>void apply({type:'remove-row'})}>删除当前行</button>}
      <button disabled={disabled||!context.table.canRemoveColumn} onClick={()=>void apply({type:'remove-column'})}>删除当前列</button>
      {context.reason&&<small>{context.reason}</small>}
    </div></details>
    {geometry&&geometry.widths.length>1&&<details className="studio-table-actions"><summary aria-label="精确列宽">第 {column+1} 列 · {activeWidth?.toFixed(1)} pt</summary><div><form className="studio-table-width-form" onSubmit={event=>{
      event.preventDefault();void (async()=>{try{await finish();const current=bridge.snapshot(),layout=tableGeometry(current,path,current.currentPage);if(!layout)throw new Error('列宽布局已变化，请重新选择。');
        const boundary=column===layout.widths.length-1?column:column+1,total=layout.widths[boundary-1]+layout.widths[boundary];
        await bridge.apply(resizeTableColumns(current,path,layout.widths,boundary,column===boundary?total-Number(width):Number(width)),undefined,[path]);
      }catch(error){onError(error);}})();
    }}><label>列宽 pt <input aria-label="当前列宽（pt）" type="number" step="any" min="1" value={width} disabled={disabled} onChange={event=>setWidth(event.target.value)}/></label><button disabled={disabled} title="调整当前列与相邻列，保持两列总宽；以固定 pt 保存">应用列宽</button></form></div></details>}
  </>;
}

interface Drag {pointer:number;source:string;session:number;target:HTMLElement;startX:number;boundary:number;left:number;layout:TableGeometry}
export function TableCanvas({state,page,paper,bridge,editing,onError}:{state:Snapshot;page:number;paper:HTMLElement;bridge:Bridge;editing:boolean;onError:(error:unknown)=>void}){
  const [drag,setDrag]=useState<Drag|null>(null),active=useRef<Drag|null>(null),selected=state.selectedPaths.length===1?state.selectedPaths[0]:undefined;
  const layout=useMemo(()=>{try{return selected===undefined?null:tableGeometry(state,selected,page);}catch{return null;}},[state.source,state.pages,state.layoutState,selected,page]);
  const scale=paper.getBoundingClientRect().width/(state.pages[page]?.width||1);
  const cancel=()=>{const current=active.current;active.current=null;setDrag(null);if(current?.target.hasPointerCapture(current.pointer))current.target.releasePointerCapture(current.pointer);};
  useEffect(()=>{cancel();},[state.source,state.session,state.pages,state.layoutState,state.busy,state.pending,state.currentPage,selected,editing]);
  useEffect(()=>()=>{const current=active.current;active.current=null;if(current?.target.hasPointerCapture(current.pointer))current.target.releasePointerCapture(current.pointer);},[]);
  useEffect(()=>{
    if(!drag)return;
    const next=(event:PointerEvent)=>{const current=active.current;if(!current||current.pointer!==event.pointerId)return null;
      const total=current.layout.widths[current.boundary-1]+current.layout.widths[current.boundary],minimum=Math.min(24,total/4);
      return {...current,left:Math.max(minimum,Math.min(total-minimum,current.layout.widths[current.boundary-1]+(event.clientX-current.startX)/scale))};};
    const move=(event:PointerEvent)=>{const value=next(event);if(value){event.preventDefault();active.current=value;setDrag(value);}};
    const stop=(event:PointerEvent)=>{const value=next(event);if(!value)return;event.preventDefault();event.stopPropagation();cancel();
      const current=bridge.snapshot();if(current.source!==value.source||current.session!==value.session||current.busy||current.pending||current.layoutState!=='synced')return;
      if(Math.abs(value.left-value.layout.widths[value.boundary-1])<.01)return;
      try{void bridge.apply(resizeTableColumns(current,value.layout.path,value.layout.widths,value.boundary,value.left),undefined,current.selectedPaths).catch(onError);}catch(error){onError(error);}
    };
    const abort=(event:PointerEvent)=>{if(event.pointerId===active.current?.pointer)cancel();};
    const key=(event:KeyboardEvent)=>{if(event.key==='Escape'){event.preventDefault();event.stopImmediatePropagation();cancel();}};
    window.document.addEventListener('pointermove',move,{passive:false});window.document.addEventListener('pointerup',stop,true);window.document.addEventListener('pointercancel',abort,true);window.document.addEventListener('keydown',key,true);
    return ()=>{window.document.removeEventListener('pointermove',move);window.document.removeEventListener('pointerup',stop,true);window.document.removeEventListener('pointercancel',abort,true);window.document.removeEventListener('keydown',key,true);};
  },[!!drag,scale]);
  if(!layout||editing||state.currentPage!==page||state.busy||state.pending||state.layoutState!=='synced')return null;
  let offset=layout.left;
  return <><style>{styles}</style>{layout.widths.slice(0,-1).map((width,index)=>{
    offset+=width;const boundary=index+1,moving=drag?.boundary===boundary,x=offset+(moving?drag.left-width:0),total=width+layout.widths[boundary],minimum=Math.min(24,total/4);
    return <button key={layout.path+':'+boundary} className="studio-table-divider" role="separator" aria-orientation="vertical" aria-label={`调整表格第 ${boundary}、${boundary+1} 列宽`} aria-valuemin={minimum} aria-valuemax={total-minimum} aria-valuenow={moving?drag.left:width} aria-valuetext={`${(moving?drag.left:width).toFixed(1)} pt`} title="拖动调整相邻列宽；方向键微调，Esc 取消。调整后保存为固定 pt。" style={{left:x*scale,top:layout.top*scale,height:Math.max(12,layout.height*scale)}}
      onClick={event=>event.stopPropagation()} onPointerDown={event=>{
        const current=bridge.snapshot();if(event.button!==0||current.source!==state.source||current.session!==state.session||current.busy||current.pending||current.layoutState!=='synced')return;event.preventDefault();event.stopPropagation();event.currentTarget.focus({preventScroll:true});
        const value:Drag={pointer:event.pointerId,source:state.source,session:state.session,target:event.currentTarget,startX:event.clientX,boundary,left:width,layout};
        event.currentTarget.setPointerCapture(event.pointerId);active.current=value;setDrag(value);
      }} onLostPointerCapture={event=>{if(active.current?.pointer===event.pointerId)cancel();}} onKeyDown={event=>{
        if(!['ArrowLeft','ArrowRight'].includes(event.key))return;event.preventDefault();event.stopPropagation();
        const current=bridge.snapshot();if(current.source!==state.source||current.session!==state.session||current.busy||current.pending||current.layoutState!=='synced')return;
        const value=Math.max(minimum,Math.min(total-minimum,width+(event.key==='ArrowLeft'?-1:1)*(event.shiftKey?10:1)));
        try{void bridge.apply(resizeTableColumns(current,layout.path,layout.widths,boundary,value),undefined,state.selectedPaths).catch(onError);}catch(error){onError(error);}
      }}>{moving&&<span className="studio-table-drag-label" role="status">{drag.left.toFixed(1)} / {(total-drag.left).toFixed(1)} pt</span>}</button>;
  })}</>;
}
