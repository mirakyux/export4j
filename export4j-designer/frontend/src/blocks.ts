// SPDX-License-Identifier: Apache-2.0
import {at,fixedBox,flowPath,serialize,xml,type Snapshot} from './types';

export const blockKinds=[['p','段落'],['heading','标题'],['bullet','列表项'],['table','表格'],['page-break','分页符']] as const;
export type BlockKind=typeof blockKinds[number][0];
const idTags=new Set(['p','heading','bullet','numbered','caption','cross-reference','page-break','table','rows']);
export const pathOf=(node:Element):string=>node.parentElement?pathOf(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node):'';

export function insertBlock(state:Snapshot,path:string|undefined,after:boolean,kind:BlockKind){
  const document=xml(state.source),flow=path?flowPath(document,path):null;
  const anchor=flow?at(document,flow):null;
  let parent:Element=anchor?.parentElement??document.documentElement;
  if(!anchor&&[...document.documentElement.children].some(node=>node.tagName==='section')){
    const candidates=[...state.selectedPaths,...(state.pages[state.currentPage]?.hits??[]).map(hit=>hit.path)];
    const section=candidates.map(path=>{try{return at(document,path).closest('section');}catch{return null;}}).find(Boolean);
    const sections=[...document.documentElement.children].filter(node=>node.tagName==='section');
    if(!section&&sections.length!==1)throw new Error('请先选择当前分节中的组件，再插入内容。');
    parent=section??sections[0];
  }
  if(kind==='page-break'&&['header','footer'].includes(parent.tagName))throw new Error('页眉、页脚中不能插入分页符。');
  const node=document.createElement(kind),id='block-'+crypto.randomUUID();node.setAttribute('id',id);
  if(kind==='heading'){node.setAttribute('level','2');node.textContent='新标题';}
  if(kind==='p')node.textContent='新段落';
  if(kind==='bullet')node.textContent='新列表项';
  if(kind==='table'){
    const columns=document.createElement('columns');
    for(let i=0;i<2;i++){const column=document.createElement('column');column.setAttribute('weight','1');columns.append(column);}
    node.append(columns);
    for(let r=0;r<2;r++){const row=document.createElement('row');for(let c=0;c<2;c++){const cell=document.createElement('cell');cell.textContent=r?'内容':'表头';row.append(cell);}node.append(row);}
  }
  if(kind==='page-break')node.removeAttribute('id');
  parent.insertBefore(node,anchor?(after?anchor.nextSibling:anchor):null);
  return {source:serialize(document),ids:kind==='page-break'?[]:[id],paths:[pathOf(node)]};
}
export function duplicateBlocks(state:Snapshot){
  const document=xml(state.source),ids:string[]=[],paths:string[]=[];
  const roots=state.selectedPaths.filter(Boolean).map(path=>{const node=at(document,path),flow=flowPath(document,path);return fixedBox(node)??(flow?at(document,flow):node);});
  const originals=[...new Set(roots)].filter(node=>!roots.some(other=>other!==node&&other.contains(node))),copies:Element[]=[],mapping=new Map<string,string>();
  for(const original of originals){
    const clone=original.cloneNode(true) as Element;
    for(const node of [clone,...clone.querySelectorAll('[id]')])if(node.hasAttribute('id')){
      const previous=node.getAttribute('id')!,expressions=[...previous.matchAll(/\$\{[^}]+\}/g)].map(match=>match[0]).join('-');
      const next='copy-'+crypto.randomUUID()+(expressions?'-'+expressions:'');mapping.set(previous,next);node.setAttribute('id',next);
    }
    if(idTags.has(clone.tagName)&&!clone.hasAttribute('id'))clone.setAttribute('id','copy-'+crypto.randomUUID());
    original.parentElement!.insertBefore(clone,original.nextSibling);
    copies.push(clone);
    const selected=clone.tagName==='box'?clone.firstElementChild!:clone;
    if(idTags.has(selected.tagName)&&!selected.hasAttribute('id'))selected.setAttribute('id','copy-'+crypto.randomUUID());
    if(selected.hasAttribute('id'))ids.push(selected.getAttribute('id')!);paths.push(pathOf(selected));
  }
  for(const copy of copies)for(const reference of [copy,...copy.querySelectorAll('page-reference[target],number-reference[target],cross-reference[target]')]){
    if(!['page-reference','number-reference','cross-reference'].includes(reference.tagName))continue;
    const target=mapping.get(reference.getAttribute('target')!);if(target)reference.setAttribute('target',target);
  }
  return {source:serialize(document),ids,paths};
}
export interface SnapGuide {axis:'x'|'y';position:number}
export function snapMove(rect:{x:number;y:number;width:number;height:number},dx:number,dy:number,targets:{x:number;y:number;width:number;height:number}[],threshold:number){
  const guides:SnapGuide[]=[];let x=dx,y=dy;
  for(const axis of ['x','y'] as const){
    const size=axis==='x'?'width':'height',delta=axis==='x'?dx:dy;
    let nearest=threshold,correction=0,position:number|undefined;
    for(const target of targets)for(const stop of [target[axis],target[axis]+target[size]/2,target[axis]+target[size]])for(const edge of [rect[axis]+delta,rect[axis]+delta+rect[size]/2,rect[axis]+delta+rect[size]]){
      const distance=Math.abs(stop-edge);if(distance<nearest){nearest=distance;correction=stop-edge;position=stop;}
    }
    if(position!==undefined){if(axis==='x')x+=correction;else y+=correction;guides.push({axis,position});}
  }
  return {x,y,guides};
}
export function contiguousText(state:Snapshot,path:string,page:number):{paths:string[];nodes:Element[]}{
  const document=xml(state.source),node=at(document,path),eligible=(value:Element)=>['p','heading'].includes(value.tagName)&&!fixedBox(value)&&!value.hasAttribute('rotation')&&!value.querySelector('image,table,p')&&['document','section','header','footer'].includes(value.parentElement?.tagName||'');
  if(!eligible(node))return {paths:[path],nodes:[node]};
  const visible=(value:Element)=>{
    if(!eligible(value))return false;
    const path=pathOf(value),visiblePages=state.pages.flatMap((model,index)=>model.hits.some(hit=>hit.path===path)?[index]:[]);
    return visiblePages.length===1&&visiblePages[0]===page;
  };
  if(!visible(node))return {paths:[path],nodes:[node]};
  const nodes=[node];let before=node.previousElementSibling,after=node.nextElementSibling;
  while(before&&visible(before)){nodes.unshift(before);before=before.previousElementSibling;}
  while(after&&visible(after)){nodes.push(after);after=after.nextElementSibling;}
  return {paths:nodes.map(pathOf),nodes};
}
