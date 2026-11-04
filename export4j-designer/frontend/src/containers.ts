// SPDX-License-Identifier: Apache-2.0
import {at,serialize,xml,type Snapshot} from './types';

export type ContainerKind='table'|'rows'|'list'|'each'|'if'|'then'|'else'|'columns'|'grid'|'item';
export interface ContainerChild {path:string;tag:string;label:string}
export interface ContainerTable {
  rows:number;bodyRows:number;columns:number;row?:number;column?:number;cellPath?:string;hasSpans:boolean;
  canAddRow:boolean;canRemoveRow:boolean;canAddColumn:boolean;canRemoveColumn:boolean;
}
export interface ContainerContext {
  path:string;kind:ContainerKind;label:string;children:ContainerChild[];textPaths:string[];activePath?:string;
  editable:boolean;reason?:string;table?:ContainerTable;
}
export type ContainerOperation={type:'add-row'|'remove-row'|'add-column'|'remove-column'|'add-list-item'|'remove-list-item';index?:number;after?:boolean};
export interface ContainerEditResult {source:string;ids:string[];paths:string[]}

const labels:Record<ContainerKind,string>={table:'表格',rows:'数据表格',list:'数据列表',each:'数据循环',if:'条件',then:'满足条件分支',else:'不满足条件分支',columns:'正文分栏',grid:'独立图文栅格',item:'栅格项'};
const textTags=new Set(['p','heading','bullet','numbered','caption','cell','link']);
const pathOf=(node:Element):string=>node.parentElement?pathOf(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node):'';
const ownRows=(table:Element)=>[...table.children].filter(node=>['header','row'].includes(node.tagName));
const bodyColumns=(node:Element)=>node.tagName==='columns'&&!['table','rows'].includes(node.parentElement?.tagName||'');
function sourceDocument(source:string):Document {
  if(source.length>1_000_000||/<!\s*(DOCTYPE|ENTITY)\b/i.test(source))throw new Error('容器操作不支持外部声明或超出预算的 XML。');
  const document=xml(source),pending:{node:Element;depth:number}[]=[{node:document.documentElement,depth:0}],ids=new Set<string>();let count=0;
  if(document.documentElement.tagName!=='document')throw new Error('请选择有效文档中的容器。');
  while(pending.length){
    const {node,depth}=pending.pop()!;
    if(++count>20_000||depth>48)throw new Error('XML 结构超出容器编辑预算。');
    const id=node.getAttribute('id');if(id){if(ids.has(id))throw new Error('元素 ID 重复，请先修正 XML。');ids.add(id);}
    for(const child of node.children)pending.push({node:child,depth:depth+1});
  }
  return document;
}
function selectedAt(document:Document,path:string):Element {
  if(!/^(?:\/(?:0|[1-9][0-9]*))*$/.test(path))throw new Error('元素位置无效，请重新选择。');
  return at(document,path);
}
interface Cell {node:Element;row:number;column:number;width:number;height:number}
interface TableGrid {rows:Element[];columns:Element[];cells:Cell[];anchors:Cell[][];slots:(Cell|undefined)[][];hasSpans:boolean}
function positive(node:Element,name:string):number {
  const literal=node.getAttribute(name);if(literal===null)return 1;
  if(!/^[1-9][0-9]*$/.test(literal))throw new Error('单元格跨度必须是静态正整数。');
  const value=Number(literal);if(!Number.isSafeInteger(value)||value>20_000)throw new Error('单元格跨度超出预算。');return value;
}
function tableGrid(table:Element):TableGrid {
  const rows=ownRows(table),declaration=[...table.children].find(node=>node.tagName==='columns'),columns=declaration?[...declaration.children]:[];
  if(!columns.length||columns.some(node=>node.tagName!=='column')||columns.length*rows.length>100_000)throw new Error('表格列声明无效或编辑网格超过预算。');
  if(!rows.length||!rows.some(row=>row.tagName==='row')||[...table.children].some(node=>!['columns','header','row'].includes(node.tagName)))throw new Error('此表包含动态结构，请在 XML 中调整行列；内容保持原样。');
  if(table.tagName==='rows'&&rows.filter(row=>row.tagName==='row').length!==1)throw new Error('数据表格必须保留一个正文行模板。');
  const slots=rows.map(()=>Array<Cell|undefined>(columns.length)),cells:Cell[]=[],anchors=rows.map(()=>[] as Cell[]);let body=false;
  rows.forEach((row,y)=>{
    if(row.tagName==='row')body=true;else if(body)throw new Error('表头必须位于所有正文行之前。');
    let x=0;
    for(const node of row.children){
      if(node.tagName!=='cell')throw new Error('此表包含动态单元格，请在 XML 中调整。');
      while(slots[y][x])x++;
      const width=positive(node,'column-span'),height=positive(node,'row-span'),cell={node,row:y,column:x,width,height};
      if(x+width>columns.length||y+height>rows.length)throw new Error('单元格跨度超出网格。');
      for(let r=y;r<y+height;r++)for(let c=x;c<x+width;c++){
        if(slots[r][c]||rows[r].tagName!==row.tagName)throw new Error('单元格重叠或跨越表头和正文边界。');slots[r][c]=cell;
      }
      cells.push(cell);anchors[y].push(cell);x+=width;
    }
    if(Array.from({length:columns.length},(_,column)=>slots[y][column]).some(value=>!value))throw new Error('表格存在未覆盖的物理列。');
  });
  return {rows,columns,cells,anchors,slots,hasSpans:cells.some(cell=>cell.width!==1||cell.height!==1)};
}
function nearestContainer(selected:Element):Element|null {
  for(let node:Element|null=selected;node;node=node.parentElement){
    if(['table','rows','list','each','if','then','else','grid','item'].includes(node.tagName)||bodyColumns(node))return node;
  }
  return null;
}
function isText(node:Element):boolean {return textTags.has(node.tagName)&&!(node.tagName==='cell'&&[...node.children].some(child=>['p','table'].includes(child.tagName)));}
function contextFor(document:Document,path:string):{owner:Element;selected:Element;context:ContainerContext}|null {
  const selected=selectedAt(document,path),owner=nearestContainer(selected);if(!owner)return null;
  const kind=owner.tagName as ContainerKind,context:ContainerContext={path:pathOf(owner),kind,label:labels[kind],children:[],textPaths:[],editable:true};
  const candidates=['table','rows'].includes(kind)?ownRows(owner).flatMap(row=>[...row.children]):[...owner.children];
  context.children=candidates.map((node,index)=>({path:pathOf(node),tag:node.tagName,label:(node.textContent||'').trim().replace(/\s+/g,' ').slice(0,40)||({item:`第 ${index+1} 格`,bullet:'空列表项',numbered:'空编号项',cell:'空单元格',p:'空段落'}[node.tagName]||node.tagName)}));
  const texts=[owner,...owner.querySelectorAll('p,heading,bullet,numbered,caption,cell,link')].filter(isText);
  context.textPaths=texts.map(pathOf);
  const child=candidates.find(node=>node===selected||node.contains(selected));if(child)context.activePath=pathOf(child);
  if(['table','rows'].includes(kind)){
    try{
      const grid=tableGrid(owner),active=grid.cells.find(cell=>cell.node===selected||cell.node.contains(selected)),bodyRows=grid.rows.filter(row=>row.tagName==='row').length;
      context.table={rows:grid.rows.length,bodyRows,columns:grid.columns.length,hasSpans:grid.hasSpans,
        ...(active?{row:active.row,column:active.column,cellPath:pathOf(active.node)}:{}),
        canAddRow:kind==='table',canRemoveRow:kind==='table'&&bodyRows>1&&(!active||grid.rows[active.row].tagName==='row'),
        canAddColumn:true,canRemoveColumn:grid.columns.length>1};
    }catch(error){context.editable=false;context.reason=error instanceof Error?error.message:String(error);}
  }
  return {owner,selected,context};
}

/** Reads source metadata only, without expanding arrays or mutating the workspace. */
export function containerContext(snapshot:Snapshot,path:string):ContainerContext|null {return contextFor(sourceDocument(snapshot.source),path)?.context??null;}
function chosenIndex(value:number|undefined,fallback:number,size:number):number {
  const index=value??fallback;if(!Number.isInteger(index)||index<0||index>=size)throw new Error('选中的行或列已变化，请重新选择。');return index;
}
function blankCell(document:Document,sample?:Element):Element {
  const cell=document.createElement('cell');if(sample)for(const attribute of sample.attributes)if(!['id','column-span','row-span'].includes(attribute.name))cell.setAttribute(attribute.name,attribute.value);return cell;
}
function span(node:Element,name:'row-span'|'column-span',value:number):void {if(value===1)node.removeAttribute(name);else node.setAttribute(name,String(value));}
function referencesRemain(document:Document,nodes:Element[]):void {
  const ids=new Set(nodes.flatMap(node=>[node,...node.querySelectorAll('[id]')]).map(node=>node.getAttribute('id')).filter(Boolean));
  for(const reference of document.querySelectorAll('[target]'))if(ids.has(reference.getAttribute('target'))&&!nodes.some(node=>node===reference||node.contains(reference)))throw new Error('待删除内容被其他节点引用，请先调整引用。');
}
function result(document:Document,node:Element):ContainerEditResult {
  const source=serialize(document);sourceDocument(source);
  const id=node.getAttribute('id');return {source,ids:id?[id]:[],paths:[pathOf(node)]};
}
function tableResult(document:Document,owner:Element,row:number,column:number):ContainerEditResult {
  const grid=tableGrid(owner),cell=grid.slots[Math.min(row,grid.rows.length-1)][Math.min(column,grid.columns.length-1)]!;
  return result(document,cell.node);
}

/** Returns one undoable source candidate. Data records are never created or deleted here. */
export function containerEdit(snapshot:Snapshot,path:string,operation:ContainerOperation):ContainerEditResult {
  const document=sourceDocument(snapshot.source),found=contextFor(document,path);if(!found)throw new Error('请先选择一个布局容器。');
  const {owner,context}=found;
  if(operation.type==='add-list-item'||operation.type==='remove-list-item'){
    if(context.kind==='list')throw new Error('数据列表只有一个项目模板；请编辑项目正文，实际项目数量由数组决定。');
    throw new Error('此操作只用于列表；请选择列表项或数据列表。');
  }
  if(!['table','rows'].includes(context.kind))throw new Error('请先选择表格或表格中的单元格。');
  if(!context.editable)throw new Error(context.reason||'当前表格不能修改行列。');
  const grid=tableGrid(owner),declaration=grid.columns[0].parentElement!;
  let candidateLength=snapshot.source.length,candidateNodes=document.getElementsByTagName('*').length;
  const added=(node:Element)=>{
    candidateLength+=serialize(node).length;
    if(candidateLength>1_000_000||++candidateNodes>20_000)throw new Error('新增内容超出容器编辑预算。');
    return node;
  };
  if(operation.type==='add-row'||operation.type==='remove-row'){
    if(context.kind==='rows')throw new Error('数据表格保留一个正文行模板；实际记录数量由绑定数组决定。');
    const index=chosenIndex(operation.index,context.table?.row??grid.rows.length-1,grid.rows.length),target=grid.rows[index];
    if(operation.type==='add-row'){
      // A new body row cannot split the declaration's ordered header region.
      const insertIndex=target.tagName==='header'?grid.rows.findIndex(row=>row.tagName==='row'):index+(operation.after===false?0:1),row=added(document.createElement('row'));
      for(const cell of grid.cells)if(cell.row<insertIndex&&cell.row+cell.height>insertIndex)span(cell.node,'row-span',cell.height+1);
      for(let column=0;column<grid.columns.length;column++){
        const above=insertIndex?grid.slots[insertIndex-1][column]:undefined;
        if(!above||above.row+above.height<=insertIndex)row.append(added(blankCell(document,grid.slots[grid.rows.length-1][column]!.node)));
      }
      owner.insertBefore(row,grid.rows[insertIndex]??null);return tableResult(document,owner,insertIndex,0);
    }
    if(target.tagName!=='row')throw new Error('请先选择正文行；此操作不会删除表头。');
    if(grid.rows.filter(row=>row.tagName==='row').length===1)throw new Error('表格至少保留一行正文。');
    const nextAnchors=grid.anchors[index+1]??[];let next=0;
    for(const cell of grid.cells)if(cell.row<=index&&cell.row+cell.height>index&&cell.height>1){
      span(cell.node,'row-span',cell.height-1);
      if(cell.row===index){
        while(next<nextAnchors.length&&nextAnchors[next].column<cell.column)next++;
        grid.rows[index+1].insertBefore(cell.node,nextAnchors[next]?.node??null);
      }
    }
    // Retained span anchors have moved out, so only genuinely removed IDs are protected here.
    referencesRemain(document,[target]);target.remove();return tableResult(document,owner,index,0);
  }
  if(operation.type==='add-column'||operation.type==='remove-column'){
    const index=chosenIndex(operation.index,context.table?.column??grid.columns.length-1,grid.columns.length);
    if(operation.type==='add-column'){
      const insertIndex=index+(operation.after===false?0:1),column=grid.columns[index].cloneNode(false) as Element;
      column.removeAttribute('id');added(column);
      declaration.insertBefore(column,grid.columns[insertIndex]??null);
      for(const cell of grid.cells)if(cell.column<insertIndex&&cell.column+cell.width>insertIndex)span(cell.node,'column-span',cell.width+1);
      grid.rows.forEach((row,y)=>{
        const left=insertIndex?grid.slots[y][insertIndex-1]:undefined,right=grid.slots[y][insertIndex];
        if(left&&left===right)return;
        row.insertBefore(added(blankCell(document,grid.slots[y][index]!.node)),grid.anchors[y].find(cell=>cell.column>=insertIndex)?.node??null);
      });
      return tableResult(document,owner,context.table?.row??0,insertIndex);
    }
    if(grid.columns.length===1)throw new Error('表格至少保留一列。');
    const affected=grid.cells.filter(cell=>cell.column<=index&&cell.column+cell.width>index),removed=affected.filter(cell=>cell.width===1).map(cell=>cell.node);
    referencesRemain(document,[...removed,grid.columns[index]]);
    for(const cell of affected)if(cell.width>1)span(cell.node,'column-span',cell.width-1);else cell.node.remove();
    grid.columns[index].remove();return tableResult(document,owner,context.table?.row??0,index);
  }
  throw new Error('未知容器操作。');
}
