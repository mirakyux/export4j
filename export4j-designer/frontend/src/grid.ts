// SPDX-License-Identifier: Apache-2.0
import {at,serialize,xml,type Snapshot} from './types';

export type GridLayoutPreset='full'|'halves'|'thirds'|'wide-left'|'wide-right';
export type GridOperation=
  | {type:'add-row';layout:GridLayoutPreset;afterRow?:number}
  | {type:'layout-row';layout:GridLayoutPreset}
  | {type:'split-item'}
  | {type:'merge-next'}
  | {type:'boundary';leftId:string;rightId:string;start:number}
  | {type:'swap';target:string};
export interface GridContextItem {id:string;path:string;start:number;span:number}
export interface GridContext {
  gridPath:string;itemPath?:string;row:number;tracks:number;items:GridContextItem[];
  editable:boolean;reason?:string;
}
export interface GridEditResult {source:string;ids:string[];paths:string[]}

const MAX_INTEGER=2_147_483_647;
const ID=/^[A-Za-z_][A-Za-z_0-9-]{0,127}$/;
const coordinates=['row','column-start','column-span'] as const;

/** Reads source only. No model binding, bridge mutation, resources or network access occurs. */
function sourceDocument(source:string):Document {
  if(source.length>4_000_000||/<!\s*(DOCTYPE|ENTITY)\b/i.test(source))throw new Error('栅格操作不支持外部声明或超出预算的 XML。');
  const document=xml(source),pending:{node:Element;depth:number}[]=[{node:document.documentElement,depth:0}];let count=0;
  const ids=new Set<string>();
  while(pending.length){
    const {node,depth}=pending.pop()!;
    if(++count>20_000||depth>48)throw new Error('XML 结构超出设计器操作预算。');
    const id=node.getAttribute('id');
    if(id){if(ids.has(id))throw new Error('元素 ID 重复，请先修正 XML。');ids.add(id);}
    for(const child of node.children)pending.push({node:child,depth:depth+1});
  }
  return document;
}
function sourceAt(document:Document,path:string):Element {
  if(!/^(?:\/(?:0|[1-9][0-9]*))*$/.test(path))throw new Error('元素位置无效，请重新选择。');
  return at(document,path);
}
function pathOf(node:Element):string {
  const parts:number[]=[];
  while(node.parentElement){parts.unshift([...node.parentElement.children].indexOf(node));node=node.parentElement;}
  return parts.length?'/'+parts.join('/'):'';
}
function positive(node:Element,name:string,fallback?:number):number {
  const literal=node.getAttribute(name);
  if(literal===null&&fallback!==undefined)return fallback;
  if(!literal||!/^\+?[0-9]+$/.test(literal))throw new Error('栅格的行号、起始轨、跨轨数和轨数必须是静态正整数。');
  const value=Number(literal);
  if(!Number.isInteger(value)||value<1||value>MAX_INTEGER)throw new Error('栅格坐标超出有效整数范围。');
  return value;
}
const itemStart=(item:Element)=>positive(item,'column-start');
const itemSpan=(item:Element)=>positive(item,'column-span');
const itemRow=(item:Element)=>positive(item,'row');
function directItems(grid:Element):Element[]{return [...grid.children].filter(node=>node.tagName==='item');}

function contextFor(document:Document,path:string):{grid:Element;item:Element|null;context:GridContext}|null {
  const selected=sourceAt(document,path),grid=selected.tagName==='grid'?selected:selected.closest('grid');
  if(!grid)return null;
  const item=selected.closest('item'),belongs=item?.closest('grid')===grid;
  const context:GridContext={gridPath:pathOf(grid),...(belongs?{itemPath:pathOf(item!)}:{}),row:1,tracks:12,items:[],editable:false};
  try{
    context.tracks=positive(grid,'tracks',12);
    if(context.tracks>12)throw new Error('栅格轨数必须为 1～12。');
    const items=directItems(grid);
    if(belongs)context.row=itemRow(item!);else if(items.length)context.row=itemRow(items[0]);
    context.items=items.filter(value=>itemRow(value)===context.row).map(value=>({
      id:value.getAttribute('id')||pathOf(value),path:pathOf(value),start:itemStart(value),span:itemSpan(value)
    }));
    if([...grid.children].some(value=>value.tagName!=='item')||belongs&&item!.parentElement!==grid){
      throw new Error('此栅格包含循环、条件或片段作用域，请在 XML 中调整；可视操作不会移动或展开动态栅格项。');
    }
    if(!items.length)throw new Error('此栅格没有静态内容项，请先修正 XML。');
    let row=0,end=0;
    for(const value of items){
      const nextRow=itemRow(value),start=itemStart(value),span=itemSpan(value);
      if(start+span-1>context.tracks)throw new Error('栅格项超出轨道范围，请先修正 XML。');
      if(nextRow<row||nextRow===row&&start<=end)throw new Error('栅格项乱序或重叠，请先修正 XML。');
      row=nextRow;end=start+span-1;
    }
    context.editable=true;
  }catch(error){context.reason=error instanceof Error?error.message:String(error);}
  return {grid,item:belongs?item:null,context};
}

/** Anonymous source items use their path as the context key; edits return actual source IDs only. */
export function gridContext(source:string,path:string):GridContext|null {
  return contextFor(sourceDocument(source),path)?.context??null;
}
function allocator(document:Document):(prefix:string)=>string {
  const taken=new Set([...document.querySelectorAll('[id]')].map(node=>node.getAttribute('id')!));let serial=1;
  return prefix=>{let id;do{id=prefix+'-'+serial++;}while(taken.has(id));taken.add(id);return id;};
}
function spansFor(layout:GridLayoutPreset,tracks:number):number[]{
  if(layout==='full')return [tracks];
  const count=layout==='thirds'?3:2;
  if(tracks<count)throw new Error('当前轨数不足以使用这个布局，请先增加轨数。');
  if(layout==='halves'||layout==='thirds'){
    const width=Math.floor(tracks/count),remainder=tracks%count;
    return Array.from({length:count},(_,index)=>width+(index<remainder?1:0));
  }
  if(layout==='wide-left'||layout==='wide-right'){
    const wide=Math.min(tracks-1,Math.max(1,Math.round(tracks*2/3))),narrow=tracks-wide;
    return layout==='wide-left'?[wide,narrow]:[narrow,wide];
  }
  throw new Error('未知栅格布局。');
}
function newItem(document:Document,fresh:(prefix:string)=>string,row:number,start:number,span:number,empty=true):Element {
  const item=document.createElement('item');
  item.setAttribute('id',fresh('grid-item'));setPosition(item,row,start,span);
  if(empty){const p=document.createElement('p');p.setAttribute('id',fresh('grid-text'));item.append(p);}
  return item;
}
function setPosition(item:Element,row:number,start:number,span:number):void {
  for(const [name,value] of Object.entries({row,'column-start':start,'column-span':span}))item.setAttribute(name,String(value));
}
/** Reuses existing element slots, preserving comments and text nodes outside the item subtrees. */
function sortItems(grid:Element):void {
  const items=directItems(grid),ordered=[...items].sort((a,b)=>itemRow(a)-itemRow(b)||itemStart(a)-itemStart(b));
  const slots=items.map(item=>{const slot=grid.ownerDocument.createComment('grid-item-slot');grid.replaceChild(slot,item);return slot;});
  ordered.forEach((item,index)=>grid.replaceChild(item,slots[index]));
}
function result(document:Document,nodes:Element[]):GridEditResult {
  return {source:serialize(document),ids:nodes.map(node=>node.getAttribute('id')||'').filter(Boolean),paths:nodes.map(pathOf)};
}
function selectedItem(item:Element|null):Element {
  if(!item)throw new Error('请先选择一个栅格项。');
  return item;
}
function findItem(items:Element[],key:string):Element {
  const item=items.find(value=>value.getAttribute('id')===key||pathOf(value)===key);
  if(!item)throw new Error('目标栅格项已变化，或位于其他栅格中，请重新选择。');
  return item;
}
function adjacent(left:Element,right:Element,items:Element[]):void {
  if(left.parentElement!==right.parentElement||itemRow(left)!==itemRow(right)
      ||items.indexOf(right)!==items.indexOf(left)+1||itemStart(left)+itemSpan(left)!==itemStart(right)){
    throw new Error('请选择同一行、同一作用域且轨道连续的相邻两项。');
  }
}
/** Removed wrappers are not entities in the resulting model. Only explicit, stable leaf targets can replace them. */
function retargetRemoved(document:Document,removed:Element[]):void {
  const references=[...document.querySelectorAll('[target]')],mapping=new Map<string,string>();
  if(removed.some(item=>item.hasAttribute('id'))&&references.some(node=>(node.getAttribute('target')||'').includes('${'))){
    throw new Error('文档含动态引用目标，无法安全重定位被合并的栅格项；请先在 XML 中明确引用。');
  }
  for(const item of removed){
    const id=item.getAttribute('id');
    if(!id||!references.some(node=>node.getAttribute('target')===id))continue;
    const anchor=item.firstElementChild,anchorId=anchor?.getAttribute('id')||'';
    if(!anchor||!['p','table'].includes(anchor.tagName)||!ID.test(anchorId)){
      throw new Error('被合并的栅格项有引用，但没有稳定的首个段落或表格 ID；请先在 XML 中调整引用。');
    }
    mapping.set(id,anchorId);
  }
  for(const reference of references){const target=mapping.get(reference.getAttribute('target')||'');if(target)reference.setAttribute('target',target);}
}
function appendContent(target:Element,source:Element):void {
  while(source.firstChild)target.append(source.firstChild);
  source.remove();
}

/** Returns one complete replacement source or throws; the snapshot, bridge and saved source stay untouched. */
export function gridEdit(snapshot:Snapshot,path:string,op:GridOperation):GridEditResult {
  const document=sourceDocument(snapshot.source),resolved=contextFor(document,path);
  if(!resolved)throw new Error('请先选择栅格或其中的内容。');
  const {grid,item,context}=resolved;
  if(!context.editable)throw new Error(context.reason||'此栅格不能使用可视操作。');
  const items=directItems(grid),fresh=allocator(document),rowItems=items.filter(value=>itemRow(value)===context.row);
  let changed:Element[];
  switch(op.type){
    case 'add-row':{
      const after=op.afterRow??context.row;
      if(!Number.isInteger(after)||!items.some(value=>itemRow(value)===after)||after>=MAX_INTEGER)throw new Error('请选择有效的现有行后添加新行。');
      const widths=spansFor(op.layout,context.tracks),row=after+1,occupied=new Set(items.map(itemRow));let free=row;
      while(occupied.has(free)){if(free===MAX_INTEGER)throw new Error('行号已到上限，无法插入新行。');free++;}
      for(const value of items){const r=itemRow(value);if(r>=row&&r<free)value.setAttribute('row',String(r+1));}
      let start=1;changed=widths.map(span=>{const value=newItem(document,fresh,row,start,span);start+=span;grid.append(value);return value;});
      break;
    }
    case 'layout-row':{
      const widths=spansFor(op.layout,context.tracks),removed=rowItems.slice(widths.length);
      retargetRemoved(document,removed);
      changed=rowItems.slice(0,widths.length);
      while(changed.length<widths.length){const value=newItem(document,fresh,context.row,1,1);changed.push(value);grid.append(value);}
      for(const value of removed)appendContent(changed[changed.length-1],value);
      let start=1;changed.forEach((value,index)=>{setPosition(value,context.row,start,widths[index]);start+=widths[index];});
      break;
    }
    case 'split-item':{
      const left=selectedItem(item),span=itemSpan(left);
      if(span<2)throw new Error('此项只有一条轨道，无法继续拆分。');
      const leftSpan=Math.ceil(span/2),right=newItem(document,fresh,itemRow(left),itemStart(left)+leftSpan,span-leftSpan);
      left.setAttribute('column-span',String(leftSpan));grid.insertBefore(right,left.nextSibling);changed=[left,right];
      break;
    }
    case 'merge-next':{
      const left=selectedItem(item),right=rowItems[rowItems.indexOf(left)+1];
      if(!right)throw new Error('此项右侧没有可合并的相邻项。');
      adjacent(left,right,rowItems);retargetRemoved(document,[right]);
      left.setAttribute('column-span',String(itemSpan(left)+itemSpan(right)));appendContent(left,right);changed=[left];
      break;
    }
    case 'boundary':{
      const left=findItem(items,op.leftId),right=findItem(items,op.rightId);
      adjacent(left,right,items.filter(value=>itemRow(value)===itemRow(left)));
      const end=itemStart(right)+itemSpan(right);
      if(!Number.isInteger(op.start)||op.start<=itemStart(left)||op.start>=end)throw new Error('边界必须为轨道整数，且两侧各保留至少一条轨道。');
      left.setAttribute('column-span',String(op.start-itemStart(left)));
      right.setAttribute('column-start',String(op.start));right.setAttribute('column-span',String(end-op.start));changed=[left,right];
      break;
    }
    case 'swap':{
      const first=selectedItem(item),second=findItem(items,op.target);
      if(first===second)throw new Error('请选择另一个栅格项交换位置。');
      const firstPosition=coordinates.map(name=>first.getAttribute(name)!),secondPosition=coordinates.map(name=>second.getAttribute(name)!);
      coordinates.forEach((name,index)=>{first.setAttribute(name,secondPosition[index]);second.setAttribute(name,firstPosition[index]);});changed=[first,second];
      break;
    }
    default:throw new Error('未知栅格操作。');
  }
  sortItems(grid);
  const checked=contextFor(document,pathOf(grid));
  if(!checked?.context.editable)throw new Error(checked?.context.reason||'操作后栅格布局无效。');
  return result(document,changed);
}

function selection(document:Document,snapshot:Snapshot):{parent:Element;nodes:Element[]} {
  if(snapshot.selectedPaths.length<2||snapshot.selectedPaths.length>200||new Set(snapshot.selectedPaths).size!==snapshot.selectedPaths.length){
    throw new Error('请选择至少两个相邻正文段落或表格，再将所选内容并排。');
  }
  const nodes=snapshot.selectedPaths.map(path=>sourceAt(document,path)),parent=nodes[0].parentElement;
  if(!parent||!['document','section'].includes(parent.tagName)||nodes.some(node=>node.parentElement!==parent||!['p','table'].includes(node.tagName))){
    throw new Error('只能将同一文档或分节中相邻的直属段落、表格并排；不能跨越动态作用域或固定框。');
  }
  const siblings=[...parent.children],ordered=siblings.filter(node=>nodes.includes(node)),first=siblings.indexOf(ordered[0]);
  if(ordered.some((node,index)=>siblings[first+index]!==node))throw new Error('所选内容必须相邻，中间不能跳过其他正文。');
  const noteAnchors=new Set([...document.querySelectorAll('note[paragraph]')].map(note=>note.getAttribute('paragraph')));
  for(const node of ordered){
    const paragraphs=node.tagName==='p'?[node]:[...node.querySelectorAll('p')];
    for(const paragraph of paragraphs){
      if(paragraph.getAttribute('keep-with-next')==='true'||paragraph.getAttribute('page-break-before')==='true')throw new Error('所选段落有保持下一段或显式分页设置，请先关闭再并排。');
    }
    for(const block of [node,...node.querySelectorAll('p,table')]){
      if(block.hasAttribute('rotation')&&Number(block.getAttribute('rotation'))!==0)throw new Error('所选内容包含旋转，暂不能放入栅格。');
    }
    if(node.querySelector('note-marker')||paragraphs.some(paragraph=>noteAnchors.has(paragraph.getAttribute('id')))){
      throw new Error('带脚注或尾注锚点的正文暂不能放入栅格。');
    }
  }
  return {parent,nodes:ordered};
}

/** Null means the current multi-selection can be moved into a grid without guessing content ownership. */
export function gridSelectionReason(snapshot:Snapshot):string|null {
  try{selection(sourceDocument(snapshot.source),snapshot);return null;}catch(error){return error instanceof Error?error.message:String(error);}
}

/** An explicit user action moves original adjacent blocks; it never copies, truncates or inserts sample prose. */
export function gridFromSelection(snapshot:Snapshot,layout:GridLayoutPreset):GridEditResult {
  const document=sourceDocument(snapshot.source),{parent,nodes}=selection(document,snapshot),fresh=allocator(document);
  const grid=document.createElement('grid');grid.setAttribute('id',fresh('grid'));
  grid.setAttribute('tracks','12');grid.setAttribute('column-gap','12');grid.setAttribute('row-gap','12');
  const widths=spansFor(layout,12);let start=1;
  const items=widths.map(span=>{const item=newItem(document,fresh,1,start,span,false);start+=span;grid.append(item);return item;});
  parent.insertBefore(grid,nodes[0]);
  nodes.forEach((node,index)=>items[Math.min(index,items.length-1)].append(node));
  for(const item of items){if(!item.children.length){const p=document.createElement('p');p.setAttribute('id',fresh('grid-text'));item.append(p);}}
  return result(document,[grid]);
}
