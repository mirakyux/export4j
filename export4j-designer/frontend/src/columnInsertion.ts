// SPDX-License-Identifier: Apache-2.0
import {at,serialize,xml} from './types';

export interface ColumnInsertionTarget {path:string;columnsPath:string;editable:boolean;paragraph:boolean;reason?:string}
export interface ColumnCaret {offset:number;size:number;empty:boolean}
export interface ColumnInsertionBoundary {source:string;path:string;after:boolean}
export interface ColumnInsertionResult {source:string;ids:string[];paths:string[]}

const bodyColumns=(node:Element)=>node.tagName==='columns'&&!['table','rows'].includes(node.parentElement?.tagName||'');
const pathOf=(node:Element):string=>node.parentElement?pathOf(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node):'';
function sourceDocument(source:string,fragment=false):Document {
  if(source.length>1_000_000||/<!\s*(DOCTYPE|ENTITY)\b/i.test(source))throw new Error('通栏插入不支持外部声明或超出预算的 XML。');
  const document=xml(source),pending:{node:Element;depth:number}[]=[{node:document.documentElement,depth:0}],ids=new Set<string>();let count=0;
  if(!fragment&&document.documentElement.tagName!=='document')throw new Error('请选择有效文档中的正文分栏。');
  while(pending.length){
    const {node,depth}=pending.pop()!;
    if(++count>20_000||depth>48)throw new Error('XML 结构超出通栏插入预算。');
    const id=node.getAttribute('id');if(id){if(ids.has(id))throw new Error('元素 ID 重复，请先修正 XML。');ids.add(id);}
    for(const child of node.children)pending.push({node:child,depth:depth+1});
  }
  return document;
}
function selectedAt(document:Document,path:string):Element {
  if(!/^(?:\/(?:0|[1-9][0-9]*))*$/.test(path))throw new Error('元素位置无效，请重新选择。');
  return at(document,path);
}
function targetFor(document:Document,path:string):{node:Element;columns:Element;context:ColumnInsertionTarget}|null {
  let node=selectedAt(document,path);
  // Inline selection still belongs to its paragraph; container descendants keep their actual scope.
  while(['span','link','image','page-number','page-count','page-reference','number-reference','note-marker'].includes(node.tagName)&&node.parentElement)node=node.parentElement;
  let columns:Element|null=node;
  while(columns&&!bodyColumns(columns))columns=columns.parentElement;
  if(!columns)return null;
  const direct=node.parentElement===columns,owner=columns.parentElement;
  const editable=direct&&!!owner&&['document','section'].includes(owner.tagName);
  const reason=editable?undefined:node===columns?'请先选择分栏中的段落，在其后插入通栏内容。':'循环、条件或表格内部不能直接拆出通栏内容；请选中分栏的直属段落或整个动态容器。';
  return {node,columns,context:{path:pathOf(node),columnsPath:pathOf(columns),editable,paragraph:node.tagName==='p',...(reason?{reason}:{})}};
}

/** Reads a source boundary without moving content out of a template scope. */
export function columnInsertionTarget(source:string,path:string):ColumnInsertionTarget|null {
  return targetFor(sourceDocument(source),path)?.context??null;
}
function allocate(document:Document,prefix:string,extra?:Element):string {
  const taken=new Set([...document.querySelectorAll('[id]'),...(extra?[extra,...extra.querySelectorAll('[id]')]:[])].map(node=>node.getAttribute('id')!));let serial=1;
  while(taken.has(prefix+'-'+serial))serial++;
  return prefix+'-'+serial;
}
function textSize(text:string):number {
  let size=text.length;for(const match of text.matchAll(/\$\{[^}]+\}/g))size-=match[0].length-1;return size;
}
function inlineSize(node:Node):number {
  if(node.nodeType===3||node.nodeType===4)return textSize(node.nodeValue||'');
  if(node.nodeType===1){
    const element=node as Element;
    if(element.tagName==='style-if')return 0;
    if(['span','link'].includes(element.tagName))return [...node.childNodes].reduce((sum,child)=>sum+inlineSize(child),0);
  }
  // ProseMirror represents fields, images and preserved XML as one atomic inline node.
  return 1;
}
function textOffset(text:string,offset:number):number {
  let raw=0,visible=0;
  for(const match of text.matchAll(/\$\{[^}]+\}/g)){
    const plain=match.index!-raw;
    if(offset<=visible+plain)return raw+offset-visible;
    visible+=plain;
    if(offset===visible+1)return match.index!+match[0].length;
    visible++;raw=match.index!+match[0].length;
  }
  return raw+offset-visible;
}
function splitInline(node:Node,offset:number):[Node,Node] {
  if(node.nodeType===3||node.nodeType===4){
    const text=node.nodeValue||'',cut=textOffset(text,offset);
    if(cut>0&&cut<text.length&&/[\uD800-\uDBFF]/.test(text[cut-1])&&/[\uDC00-\uDFFF]/.test(text[cut]))throw new Error('光标不能位于 Unicode 字符中间，请重新放置光标。');
    const head=node.cloneNode(false),tail=node.cloneNode(false);head.nodeValue=text.slice(0,cut);tail.nodeValue=text.slice(cut);return [head,tail];
  }
  const head=node.cloneNode(false),tail=node.cloneNode(false);let consumed=0;
  for(const child of node.childNodes){
    const size=inlineSize(child);
    if(child.nodeType===1&&(child as Element).tagName==='style-if'){
      head.appendChild(child.cloneNode(true));tail.appendChild(child.cloneNode(true));
    }else if(offset>=consumed+size)head.appendChild(child.cloneNode(true));
    else if(offset<=consumed)tail.appendChild(child.cloneNode(true));
    else {const [before,after]=splitInline(child,offset-consumed);head.appendChild(before);tail.appendChild(after);}
    consumed+=size;
  }
  return [head,tail];
}

/** Builds an uncommitted boundary at a collapsed ProseMirror caret, preserving literal inline XML. */
export function prepareColumnInsertion(source:string,path:string,caret?:ColumnCaret):ColumnInsertionBoundary {
  const document=sourceDocument(source),target=targetFor(document,path);
  if(!target?.context.editable)throw new Error(target?.context.reason||'请选择正文分栏中的直属内容。');
  const {node}=target,pathValue=target.context.path;
  if(!caret)return {source,path:pathValue,after:true};
  if(!caret.empty)throw new Error('请先收起文字选区，再在光标处插入通栏内容；已选文字会完整保留。');
  if(node.tagName!=='p')throw new Error('光标处通栏插入仅支持正文分栏的直属段落。');
  const size=[...node.childNodes].reduce((sum,child)=>sum+inlineSize(child),0);
  if(!Number.isSafeInteger(caret.offset)||!Number.isSafeInteger(caret.size)||caret.size!==size||caret.offset<0||caret.offset>size)throw new Error('编辑内容或光标已变化，请重新放置光标。');
  if(caret.offset===0)return {source,path:pathValue,after:false};
  if(caret.offset===size)return {source,path:pathValue,after:true};
  const [head,tail]=splitInline(node,caret.offset) as [Element,Element];
  tail.setAttribute('id',allocate(document,'column-text'));
  node.replaceWith(head,tail);
  const candidate=serialize(document);sourceDocument(candidate);
  return {source:candidate,path:pathOf(head),after:true};
}

/** Inserts one full-width object between two column regions as one candidate source change. */
export function insertColumnFullWidth(source:string,path:string,fragment:string,after=true):ColumnInsertionResult {
  const document=sourceDocument(source),target=targetFor(document,path);
  if(!target?.context.editable)throw new Error(target?.context.reason||'请选择正文分栏中的直属内容。');
  const inserted=sourceDocument(fragment,true).documentElement;
  const image=inserted.tagName==='p'&&!(inserted.textContent||'').trim()&&inserted.children.length===1&&inserted.firstElementChild?.tagName==='image';
  if(inserted.tagName!=='table'&&!image)throw new Error('通栏插入仅支持表格或独立图片段落。');
  const fresh=document.importNode(inserted,true),{node,columns}=target,tail=columns.cloneNode(false) as Element;
  for(let current:Node|null=after?node.nextSibling:node;current;){const next:Node|null=current.nextSibling;tail.appendChild(current);current=next;}
  const owner=columns.parentElement!,anchor=columns.nextSibling;
  owner.insertBefore(fresh,anchor);
  const emptyHead=!columns.children.length,emptyTail=!tail.children.length;
  if(emptyTail)while(tail.firstChild)columns.appendChild(tail.firstChild);
  else {
    if(emptyHead)while(columns.lastChild)tail.insertBefore(columns.lastChild,tail.firstChild);
    else tail.setAttribute('id',allocate(document,'column-flow',tail));
    owner.insertBefore(tail,anchor);
  }
  if(emptyHead)columns.remove();
  const candidate=serialize(document);sourceDocument(candidate);
  const id=fresh.getAttribute('id');
  return {source:candidate,ids:id?[id]:[],paths:[pathOf(fresh)]};
}
