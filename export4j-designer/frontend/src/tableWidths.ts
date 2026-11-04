// SPDX-License-Identifier: Apache-2.0
import {at,serialize,xml,type Snapshot} from './types';
import {containerContext} from './containers';

export interface TableGeometry {path:string;left:number;top:number;width:number;height:number;widths:number[]}
const literal=(value:string|null)=>value!==null&&/^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)$/.test(value)&&Number(value)>0;

/** Reads actual cell borders from the engine, never inferring padding from text hit boxes. */
export function tableGeometry(state:Snapshot,path:string,page:number):TableGeometry|null {
  if(state.layoutState&&state.layoutState!=='synced')return null;
  const context=containerContext(state,path);if(!context?.table)return null;
  const document=xml(state.source),owner=at(document,context.path),declaration=[...owner.children].find(node=>node.tagName==='columns');
  if(!declaration||owner.hasAttribute('rotation')&&Number(owner.getAttribute('rotation'))!==0)return null;
  const columns=[...declaration.children];
  if(columns.some(column=>[...column.attributes].some(attribute=>!['id','weight','width','min-width','max-width'].includes(attribute.name)||attribute.name!=='id'&&!literal(attribute.value))))return null;
  const model=state.pages[page];if(!model)return null;
  const tableHits=model.hits.filter(hit=>hit.path===context.path);if(tableHits.length>1)return null;
  const tableHit=tableHits[0];
  const cells=model.hits.filter(hit=>{
    if(!hit.tableCell)return false;
    try{if(at(document,hit.path).closest('table,rows')!==owner)return false;}catch{return false;}
    const box=hit.tableCell;
    return !tableHit||box.x>=tableHit.x-.1&&box.x+box.width<=tableHit.x+tableHit.width+.1&&box.y>=tableHit.y-.1&&box.y+box.height<=tableHit.y+tableHit.height+.1;
  }).map(hit=>hit.tableCell!);
  if(!cells.length)return null;
  const boundaries=new Map<number,number>();
  for(const cell of cells)for(const [index,value] of [[cell.column,cell.x],[cell.column+cell.columnSpan,cell.x+cell.width]]){
    if(index<0||index>columns.length||boundaries.has(index)&&Math.abs(boundaries.get(index)!-value)>.1)return null;
    boundaries.set(index,value);
  }
  const left=boundaries.get(0),right=boundaries.get(columns.length);if(left===undefined||right===undefined)return null;
  if(boundaries.size!==columns.length+1){
    // Fully merged rows may hide a border. Static fixed/weighted declarations remain unambiguous.
    if(columns.some(column=>!column.hasAttribute('width')&&!column.hasAttribute('weight')))return null;
    const fixed=columns.reduce((sum,column)=>sum+Number(column.getAttribute('width')||0),0),weight=columns.reduce((sum,column)=>sum+Number(column.getAttribute('weight')||0),0);
    let position=left;
    for(let index=0;index<columns.length;index++){
      position+=columns[index].hasAttribute('width')?Number(columns[index].getAttribute('width')):(right-left-fixed)*Number(columns[index].getAttribute('weight'))/weight;
      if(!boundaries.has(index+1))boundaries.set(index+1,position);
    }
  }
  const widths=columns.map((_,index)=>boundaries.get(index+1)!-boundaries.get(index)!);
  if(widths.some(width=>!Number.isFinite(width)||width<=0))return null;
  const top=Math.min(...cells.map(cell=>cell.y)),bottom=Math.max(...cells.map(cell=>cell.y+cell.height));
  return {path:context.path,left,top,width:right-left,height:bottom-top,widths};
}

/** Changes one adjacent pair to explicit pt widths while keeping their total within the engine's width. */
export function resizeTableColumns(state:Snapshot,path:string,widths:number[],boundary:number,leftWidth:number):string {
  const context=containerContext(state,path);if(!context?.table)throw new Error('请选择表格或单元格。');
  if(widths.length!==context.table.columns||!Number.isInteger(boundary)||boundary<1||boundary>=widths.length||widths.some(value=>!Number.isFinite(value)||value<=0))throw new Error('列宽布局已变化，请重新选择表格。');
  const total=widths[boundary-1]+widths[boundary],minimum=Math.min(24,total/4);
  if(!Number.isFinite(leftWidth)||leftWidth<minimum||leftWidth>total-minimum)throw new Error(`相邻列至少各保留 ${minimum.toFixed(1)} pt。`);
  const document=xml(state.source),owner=at(document,context.path),columns=[...[...owner.children].find(node=>node.tagName==='columns')!.children];
  for(const [index,value] of [[boundary-1,leftWidth],[boundary,total-leftWidth]]){
    const column=columns[index];for(const name of ['weight','min-width','max-width'])column.removeAttribute(name);
    column.setAttribute('width',String(Math.floor((value+1e-8)*1000)/1000));
  }
  const source=serialize(document);containerContext({...state,source},context.path);return source;
}
