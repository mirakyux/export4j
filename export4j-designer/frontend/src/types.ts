// SPDX-License-Identifier: Apache-2.0
export interface Entry {path:string;id:string;tag:string;label:string;depth:number}
export interface Hit {id:string;path:string;x:number;y:number;width:number;height:number;images:{x:number;y:number;width:number;height:number}[];tableCell?:{x:number;y:number;width:number;height:number;column:number;columnSpan:number}}
export interface Page {width:number;height:number;ownerPath?:string|null;hits:Hit[];imageSpaces?:{path:string;width:number;height:number}[]}
export interface Snapshot {source:string;data:string;resourceIdentity?:string;revision:number;session:number;pending:boolean;layoutState:'pending'|'failed'|'stale'|'synced';historyBoundary?:string;canUndo:boolean;canRedo:boolean;selectedPaths:string[];entries:Entry[];pages:Page[];currentPage:number;busy:boolean;formats:Record<string,Record<string,string>>}
export interface Bridge {
  snapshot():Snapshot;
  choose(path:string,event?:{ctrlKey?:boolean;metaKey?:boolean;shiftKey?:boolean},groupLists?:boolean):Promise<void>;
  select(paths:string[]):Promise<void>;
  draft(source:string):void;
  apply(source:string,ids?:string[],paths?:string[]):Promise<void>;
  flush():Promise<void>;
  history(direction:'undo'|'redo'):Promise<void>;
  preview():void;
  paste(file:File,page:number,x:number,y:number,path?:string):Promise<void>;
  font(name:string):Promise<ArrayBuffer>;
  error(message:string):void;
  context(event:MouseEvent,path:string):void;
}
export interface Change {path:string;position?:'FLOW'|'FIXED';page?:number;x?:number;y?:number;width?:number;height?:number;rotation?:number;widthMode?:string;heightMode?:string;bodyFlow?:'OVERLAP'|'AVOID'}
export function xml(source:string):Document {
  const document=new DOMParser().parseFromString(source,'application/xml');
  if(document.querySelector('parsererror'))throw new Error('请先修正 XML 草稿。');
  return document;
}
export function at(document:Document,path:string):Element {
  let node:Element=document.documentElement;
  if(path)for(const part of path.slice(1).split('/')){node=node.children[Number(part)];if(!node)throw new Error('元素位置已变化，请重新选择。');}
  return node;
}
export const serialize=(node:Node)=>new XMLSerializer().serializeToString(node);
export const fixedBox=(node:Element):Element|null=>node.tagName==='box'?node:node.parentElement?.tagName==='box'?node.parentElement:null;
export const positionable=(node:Element):boolean=>!!fixedBox(node)||(['p','table'].includes(node.tagName)&&['document','section'].includes(node.parentElement?.tagName||''));
const flowTags=new Set(['p','heading','table','rows','bullet','numbered','caption','list','each','if','page-break','cross-reference','columns','grid']);
export const parentPath=(path:string)=>path.slice(0,path.lastIndexOf('/'));
export function fixedPath(document:Document,path:string):string|null {
  while(path){if(fixedBox(at(document,path)))return path;path=parentPath(path);}return null;
}
// Drag the semantic template as a whole, never an expanded data row or a table cell.
export function flowPath(document:Document,path:string):string|null {
  let current=path,node=at(document,current);
  // Grid item coordinates define placement. Reordering the whole item without changing them is invalid.
  if(node.tagName==='item'&&node.closest('grid'))return null;
  while(current){
    if(fixedBox(node))return null;
    const parent=node.parentElement;
    if(flowTags.has(node.tagName)&&parent&&(['document','section','header','footer','item'].includes(parent.tagName)||parent.tagName==='columns'&&!['table','rows'].includes(parent.parentElement?.tagName||'')))return current;
    current=parentPath(current);if(!current)return null;node=at(document,current);
  }
  return null;
}
/** Keeps static item coordinates ordered without moving content across conditional or loop boundaries. */
export function sortGridItems(document:Document):void {
  const scope=(parent:Element)=>{
    const children=[...parent.children];
    for(let offset=0;offset<children.length;){
      if(children[offset].tagName!=='item'){
        const child=children[offset++];if(['each','if','then','else'].includes(child.tagName))scope(child);continue;
      }
      let end=offset;while(end<children.length&&children[end].tagName==='item')end++;
      const items=children.slice(offset,end);
      if(items.every(item=>['row','column-start'].every(name=>/^[1-9][0-9]{0,9}$/.test(item.getAttribute(name)||'')))){
        const ordered=[...items].sort((a,b)=>Number(a.getAttribute('row'))-Number(b.getAttribute('row'))||Number(a.getAttribute('column-start'))-Number(b.getAttribute('column-start')));
        const slots=items.map(item=>{const slot=document.createComment('grid item slot');parent.replaceChild(slot,item);return slot;});
        ordered.forEach((item,index)=>parent.replaceChild(item,slots[index]));
      }
      offset=end;
    }
  };
  document.querySelectorAll('grid').forEach(scope);
}
export function reorder(snapshot:Snapshot,paths:string[],target:string,after:boolean):{source:string;paths:string[]} {
  const document=xml(snapshot.source),nodes=paths.map(path=>at(document,path)),destination=at(document,target),parent=destination.parentElement;
  if(!parent||!paths.length||paths.includes(target)||nodes.some(node=>node.parentElement!==parent))throw new Error('请选择同一容器中的组件进行排序。');
  // Source order is authoritative even when Ctrl-selection was made in reverse order.
  const ordered=[...parent.children].filter(node=>nodes.includes(node));
  const anchor=after?destination.nextSibling:destination;
  for(const node of ordered)parent.insertBefore(node,anchor);
  const pathOf=(node:Element):string=>node===document.documentElement?'':pathOf(node.parentElement!)+'/'+[...node.parentElement!.children].indexOf(node);
  return {source:serialize(document),paths:ordered.map(pathOf)};
}
export function hitFor(snapshot:Snapshot,path:string,page?:number):{hit:Hit;page:number}|undefined {
  for(let index=0;index<snapshot.pages.length;index++){
    if(page!==undefined&&page!==index)continue;
    const hits=snapshot.pages[index].hits.filter(hit=>hit.path===path||hit.path.startsWith(path+'/'));
    if(hits.length){const x=Math.min(...hits.map(hit=>hit.x)),y=Math.min(...hits.map(hit=>hit.y));return {hit:{...hits[0],path,x,y,width:Math.max(...hits.map(hit=>hit.x+hit.width))-x,height:Math.max(...hits.map(hit=>hit.y+hit.height))-y},page:index};}
  }
}
export const textTags=new Set(['p','heading','bullet','numbered','caption','cell','link']);
