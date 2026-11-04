// SPDX-License-Identifier: Apache-2.0
import {at,xml,type Snapshot} from './types';

export interface BindingScope {
  kind:'root'|'list'|'rows'|'each';
  /** Source XML path, not a binding expression. */
  path:string;
  items?:string;
  alias?:string;
}
export interface BindingField {path:string;type:string}
export interface BindingContext {scopes:BindingScope[];fields:BindingField[];hint:string}

const IDENTIFIER=/^[A-Za-z_][A-Za-z_0-9]*$/;
const PATH=/^[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*$/;
const MAX_TEXT=4_000_000,MAX_WORK=20_000,MAX_FIELDS=1000,MAX_SAMPLES=200,MAX_DATA_DEPTH=16;
type Frame=Map<string,unknown[]>;
interface Budget {remaining:number;truncated:boolean;unsupported:boolean;unknown:boolean}

function visit(budget:Budget):boolean {
  if(budget.remaining<=0){budget.truncated=true;return false;}
  budget.remaining--;return true;
}
function validPath(path:string):boolean {return path.length<=256&&PATH.test(path);}
function pathOf(node:Element):string {
  const result:number[]=[];
  while(node.parentElement){result.unshift([...node.parentElement.children].indexOf(node));node=node.parentElement;}
  return result.length?'/'+result.join('/'):'';
}
function sourceNode(source:string,path:string):{root:Element;selected:Element} {
  if(source.length>MAX_TEXT||/<!\s*(DOCTYPE|ENTITY)\b/i.test(source))throw new Error('变量选择不支持外部声明或超出预算的 XML。');
  if(!/^(?:\/(?:0|[1-9][0-9]*))*$/.test(path))throw new Error('元素位置无效，请重新选择。');
  const document=xml(source),root=document.documentElement;
  if(root.tagName!=='document')throw new Error('请选择有效文档中的正文节点。');
  const pending:{node:Element;depth:number}[]=[{node:root,depth:0}];let count=0;
  while(pending.length){
    const {node,depth}=pending.pop()!;
    if(++count>MAX_WORK||depth>48)throw new Error('XML 结构超出变量选择预算。');
    for(const child of node.children){
      if(count+pending.length>=MAX_WORK)throw new Error('XML 结构超出变量选择预算。');
      pending.push({node:child,depth:depth+1});
    }
  }
  return {root,selected:at(document,path)};
}
function keys(value:Record<string,unknown>,budget:Budget,work:(key:string,value:unknown)=>void):void {
  for(const key in value){
    if(!Object.hasOwn(value,key))continue;
    if(!visit(budget))break;
    if(key.length<=256&&IDENTIFIER.test(key))work(key,value[key]);
  }
}
function resolve(path:string,frames:Frame[],budget:Budget):unknown[] {
  if(!validPath(path)){budget.unknown=true;return [];}
  const [first,...tail]=path.split('.');let values:unknown[]=[];
  for(let index=frames.length-1;index>=0;index--){
    if(frames[index].has(first)){values=frames[index].get(first)!;break;}
  }
  for(const key of tail){
    const next:unknown[]=[];
    for(const value of values){
      if(!visit(budget))break;
      if(value&&typeof value==='object'&&!Array.isArray(value)&&Object.hasOwn(value,key))next.push((value as Record<string,unknown>)[key]);
    }
    values=next;
  }
  if(!values.length)budget.unknown=true;
  return values;
}
function samples(values:unknown[],budget:Budget):unknown[] {
  const result:unknown[]=[];
  for(const value of values){
    if(!Array.isArray(value)){budget.unknown=true;continue;}
    for(let index=0;index<value.length;index++){
      if(result.length===MAX_SAMPLES){budget.truncated=true;return result;}
      if(!visit(budget))return result;
      result.push(value[index]);
    }
  }
  if(!result.length)budget.unknown=true;
  return result;
}
function valueType(value:unknown,budget:Budget):string {
  if(value===null){budget.unsupported=true;return 'null';}
  if(Array.isArray(value))return 'array';
  if(typeof value==='number'&&!Number.isFinite(value)){budget.unsupported=true;return 'unsupported';}
  return typeof value;
}
function fieldsFrom(frames:Frame[],budget:Budget):BindingField[] {
  // Inner aliases replace the whole outer declaration, matching DocumentTemplate.Scope.
  const visible=new Map<string,unknown[]>();
  for(const frame of frames)for(const [key,values] of frame)visible.set(key,values);
  const types=new Map<string,Set<string>>(),pending:{path:string;value:unknown;depth:number}[]=[];
  const record=(path:string,type:string)=>{
    if(!types.has(path)){
      if(types.size===MAX_FIELDS){budget.truncated=true;return;}
      types.set(path,new Set());
    }
    types.get(path)!.add(type);
  };
  for(const [path,values] of visible){
    if(!values.length)record(path,'unknown');
    for(const value of values)pending.push({path,value,depth:0});
  }
  while(pending.length){
    if(!visit(budget))break;
    const {path,value,depth}=pending.pop()!,type=valueType(value,budget);
    record(path,type);
    if(value===null||typeof value!=='object'||Array.isArray(value))continue;
    if(depth===MAX_DATA_DEPTH){budget.truncated=true;continue;}
    keys(value as Record<string,unknown>,budget,(key,child)=>{
      const childPath=path+'.'+key;
      if(childPath.length>256){budget.truncated=true;return;}
      pending.push({path:childPath,value:child,depth:depth+1});
    });
  }
  return [...types].map(([path,values])=>({path,type:values.size===1?[...values][0]:'mixed'}))
    .sort((left,right)=>left.path<right.path?-1:left.path>right.path?1:0);
}

/** Reads bounded metadata only. Returned fields never contain test-data values. */
export function bindingContext(snapshot:Snapshot & {data?:string},path:string):BindingContext {
  const {root,selected}=sourceNode(snapshot.source,path),scopes:BindingScope[]=[{kind:'root',path:''}];
  const ancestors:Element[]=[];
  for(let parent=selected.parentElement;parent;parent=parent.parentElement)ancestors.unshift(parent);
  const budget:Budget={remaining:MAX_WORK,truncated:false,unsupported:false,unknown:false},frames:Frame[]=[new Map()];
  let invalidData=false,invalidScope=false,rowsHeader=false;
  try{
    if(typeof snapshot.data!=='string'||snapshot.data.length>MAX_TEXT)throw new Error();
    const data:unknown=JSON.parse(snapshot.data);
    if(!data||typeof data!=='object'||Array.isArray(data))throw new Error();
    keys(data as Record<string,unknown>,budget,(key,value)=>frames[0].set(key,[value]));
  }catch{invalidData=true;}
  for(const ancestor of ancestors){
    if(!['list','each','rows'].includes(ancestor.tagName))continue;
    if(ancestor.tagName==='rows'){
      let child=selected;
      while(child.parentElement!==ancestor&&child.parentElement)child=child.parentElement;
      if(child.tagName!=='row'){if(child.tagName==='header')rowsHeader=true;continue;}
    }
    const kind=ancestor.tagName as 'list'|'rows'|'each',items=(ancestor.getAttribute('items')||'').slice(0,257),alias=(ancestor.getAttribute('as')||'').slice(0,257);
    scopes.push({kind,path:pathOf(ancestor),items,alias});
    if(!validPath(items)||alias.length>256||!IDENTIFIER.test(alias)){invalidScope=true;continue;}
    frames.push(new Map([[alias,samples(resolve(items,frames,budget),budget)]]));
  }
  const fields=fieldsFrom(frames,budget),hints:string[]=[];
  if(root.getAttribute('literal-text')==='true')hints.push('当前文档启用原文模式，正文中的 ${…} 不会绑定；循环数据路径仍可使用。');
  if(invalidData)hints.push('测试数据不是有效的 JSON 对象，仍可手写变量路径。');
  if(invalidScope)hints.push('当前循环的数据路径或别名无效，请先修正 XML；仍可手写路径。');
  if(rowsHeader)hints.push('动态表格的表头使用外部数据，不能引用当前行别名。');
  if(budget.unknown)hints.push('部分循环数据未知或为空，可手写数组项中的字段路径。');
  if(budget.unsupported)hints.push('测试数据包含不受支持的空值或数值，请修正数据后预览。');
  if(budget.truncated)hints.push('字段列表已达到扫描预算；未列出的路径仍可手写。');
  if(!hints.length)hints.push('请选择当前作用域中的字段，或手写点路径；数组与对象用于数据源，正文插入其具体字段。');
  return {scopes,fields,hint:hints.join(' ')};
}
