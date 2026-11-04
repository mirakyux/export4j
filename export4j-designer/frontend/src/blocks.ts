// SPDX-License-Identifier: Apache-2.0
import {at,fixedBox,flowPath,serialize,xml,type Snapshot} from './types';

export const blockKinds=[['p','段落'],['heading','标题'],['bullet','列表项'],['table','表格'],['list','数据列表'],['rows','数据表格'],['columns','正文分栏'],['grid','独立图文栅格'],['page-break','分页符']] as const;
export type BlockKind=typeof blockKinds[number][0];
export const blockGroups=[['文字',['p','heading','bullet']],['布局与容器',['table','list','rows','columns','grid']],['分页',['page-break']]] as const;
const idTags=new Set(['p','heading','bullet','numbered','caption','cross-reference','page-break','table','rows','columns','grid','item']);
export const pathOf=(node:Element):string=>node.parentElement?pathOf(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node):'';
export function tableMarkup(id:string):string {
  const escaped=id.replace(/[&<>"']/g,value=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[value]!));
  return `<table id="${escaped}"><columns><column weight="1"/><column weight="1"/></columns><header><cell>列一</cell><cell>列二</cell></header><row><cell>内容一</cell><cell>内容二</cell></row></table>`;
}

export function insertBlock(state:Snapshot,path:string|undefined,after:boolean,kind:BlockKind){
  if(kind==='list'||kind==='rows')throw new Error('请先选择数组与字段，再插入数据列表或数据表格。');
  const document=xml(state.source),flow=path?flowPath(document,path):null;
  const anchor=flow?at(document,flow):null;
  let parent:Element=anchor?.parentElement??document.documentElement;
  if(!anchor&&path&&at(document,path).tagName==='item')parent=at(document,path);
  if(!anchor&&parent===document.documentElement&&[...document.documentElement.children].some(node=>node.tagName==='section')){
    const candidates=[...state.selectedPaths,...(state.pages[state.currentPage]?.hits??[]).map(hit=>hit.path)];
    const section=candidates.map(path=>{try{return at(document,path).closest('section');}catch{return null;}}).find(Boolean);
    const sections=[...document.documentElement.children].filter(node=>node.tagName==='section');
    if(!section&&sections.length!==1)throw new Error('请先选择当前分节中的组件，再插入内容。');
    parent=section??sections[0];
  }
  if(kind==='page-break'&&['header','footer'].includes(parent.tagName))throw new Error('页眉、页脚中不能插入分页符。');
  const columnOwner=parent.closest('columns');
  if(columnOwner&&!['table','rows'].includes(columnOwner.parentElement?.tagName||'')&&!['p','table'].includes(kind))throw new Error('正文分栏内可插入段落和表格；小标题可使用加粗段落。');
  if(kind==='columns'&&(!['document','section'].includes(parent.tagName)))throw new Error('正文分栏用于文档或分节正文；不能嵌套到页区、表格或其他分栏中。');
  if(parent.closest('item')&&!['p','table'].includes(kind))throw new Error('栅格项内可插入段落和表格；不支持嵌套栅格或正文分栏。');
  if(kind==='grid'&&!['document','section'].includes(parent.tagName))throw new Error('栅格用于文档或分节正文，不能嵌套到页区、表格、正文分栏或其他栅格中。');
  const id='block-'+crypto.randomUUID(),node=kind==='table'?document.importNode(xml(tableMarkup(id)).documentElement,true):document.createElement(kind);node.setAttribute('id',id);
  if(kind==='heading'){node.setAttribute('level','2');node.textContent='新标题';}
  if(kind==='p')node.textContent='新段落';
  if(kind==='bullet')node.textContent='新列表项';
  if(kind==='columns'){
    node.setAttribute('count','2');node.setAttribute('gap','18');node.setAttribute('balanced','true');
    const paragraph=document.createElement('p');paragraph.setAttribute('id',id+'-body');paragraph.textContent='在这里编写正文。内容按阅读顺序流经各栏，填满后自动续页。';node.append(paragraph);
  }
  if(kind==='grid'){
    node.setAttribute('tracks','12');node.setAttribute('column-gap','12');node.setAttribute('row-gap','12');
    for(const [index,start] of [1,7].entries()){
      const item=document.createElement('item'),paragraph=document.createElement('p');
      for(const [name,value] of Object.entries({id:id+'-item-'+index,row:1,'column-start':start,'column-span':6}))item.setAttribute(name,String(value));
      paragraph.setAttribute('id',id+'-text-'+index);item.append(paragraph);node.append(item);
    }
  }
  if(kind==='page-break')node.removeAttribute('id');
  parent.insertBefore(node,anchor?(after?anchor.nextSibling:anchor):null);
  return {source:serialize(document),ids:kind==='page-break'?[]:[id],paths:[pathOf(node)]};
}
export function duplicateBlocks(state:Snapshot,scope:'component'|'content'='component'){
  const document=xml(state.source),ids:string[]=[],paths:string[]=[];
  const roots=state.selectedPaths.filter(Boolean).map(path=>{const node=at(document,path),flow=flowPath(document,path);return scope==='content'?node:fixedBox(node)??(flow?at(document,flow):node);});
  if(scope==='content'&&roots.some(node=>!contentBlock(node)))throw new Error('请选择容器内的独立段落或表格，数据项目需编辑模板。');
  const originals=[...new Set(roots)].filter(node=>!roots.some(other=>other!==node&&other.contains(node))),copies:Element[]=[],mapping=new Map<string,string>();
  if(originals.some(node=>node.tagName==='item'))throw new Error('栅格项使用显式坐标，请在栅格属性中添加新行，避免副本与原项重叠。');
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
function contentBlock(node:Element):boolean {
  const parent=node.parentElement;
  return ['p','table'].includes(node.tagName)&&!!parent&&(['cell','item'].includes(parent.tagName)||parent.tagName==='columns'&&!['table','rows'].includes(parent.parentElement?.tagName||''));
}
/** Explicit content commands target an inner block; component keyboard commands retain their existing scope. */
export function contentBlockTarget(state:Snapshot,path:string):{path:string;label:string}|null {
  const node=at(xml(state.source),path);return contentBlock(node)?{path,label:node.tagName==='p'?'段落':'内部表格'}:null;
}
export function removeContentBlock(state:Snapshot,path:string){
  const document=xml(state.source),node=at(document,path),parent=node.parentElement;
  if(!contentBlock(node)||!parent)throw new Error('请选择容器内的独立段落或表格。');
  if(['columns','item'].includes(parent.tagName)&&parent.children.length===1)throw new Error('正文分栏和栅格项至少保留一个内容块。');
  const ids=new Set([node,...node.querySelectorAll('[id]')].map(value=>value.getAttribute('id')).filter(Boolean));
  if([...document.querySelectorAll('[target]')].some(reference=>ids.has(reference.getAttribute('target'))&&!node.contains(reference)))throw new Error('内容被其他节点引用，请先调整引用。');
  const next=node.nextElementSibling??node.previousElementSibling??parent;node.remove();
  return {source:serialize(document),paths:[pathOf(next)]};
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
  const document=xml(state.source),node=at(document,path),eligible=(value:Element)=>{
    const owner=value.parentElement;
    const textOwner=!!owner&&(['document','section','header','footer','item','cell'].includes(owner.tagName)||owner.tagName==='columns'&&!['table','rows'].includes(owner.parentElement?.tagName||''));
    return ['p','heading'].includes(value.tagName)&&!fixedBox(value)&&!value.hasAttribute('rotation')&&!value.querySelector('image,table,p')&&textOwner;
  };
  if(!eligible(node))return {paths:[path],nodes:[node]};
  const visible=(value:Element)=>{
    if(!eligible(value))return false;
    const path=pathOf(value),visiblePages=state.pages.flatMap((model,index)=>model.hits.some(hit=>hit.path===path)?[index]:[]);
    // A new paragraph already belongs to the cell source before the next engine preview creates its hit.
    if(!visiblePages.length&&value.parentElement?.tagName==='cell')return true;
    return visiblePages.length===1&&visiblePages[0]===page;
  };
  if(!visible(node))return {paths:[path],nodes:[node]};
  const nodes=[node];let before=node.previousElementSibling,after=node.nextElementSibling;
  while(before&&visible(before)){nodes.unshift(before);before=before.previousElementSibling;}
  while(after&&visible(after)){nodes.push(after);after=after.nextElementSibling;}
  return {paths:nodes.map(pathOf),nodes};
}
