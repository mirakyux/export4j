// SPDX-License-Identifier: Apache-2.0
import {at,fixedBox,hitFor,serialize,xml,flowPath,type Snapshot,type Change} from './types';
const num=(value:number)=>String(Math.round(value*100)/100);
export function geometry(snapshot:Snapshot,changes:Change[]):{source:string;ids:string[]} {
  const document=xml(snapshot.source);
  // Restoring to flow uses the nearest visible sibling at the box's current page/Y.
  // Capture paths before removing layers, which shifts all subsequent source paths.
  const flowEntries=snapshot.entries.filter(entry=>flowPath(document,entry.path)===entry.path).map(entry=>({node:at(document,entry.path),placed:hitFor(snapshot,entry.path)}));
  const selected=changes.map(change=>({change,node:at(document,change.path)}));
  for(const {change,node} of selected){
    if(!node.hasAttribute('id')&&node.tagName!=='box')node.setAttribute('id','studio-'+crypto.randomUUID());
    let box=fixedBox(node);
    if(change.position==='FLOW'&&box){
      const layer=box.parentElement!,owner=layer.parentElement!;
      const placed=hitFor(snapshot,change.path),anchor=placed&&flowEntries.find(entry=>entry.node.parentElement===owner&&entry.placed&&(entry.placed.page>placed.page||entry.placed.page===placed.page&&entry.placed.hit.y>=placed.hit.y));
      for(const child of [...box.children])owner.insertBefore(child,anchor?anchor.node:null);
      box.remove();if(!layer.children.length)layer.remove();box=null;
    }
    if(change.position==='FIXED'&&!box){
      const owner=node.parentElement;
      if(!owner||!['document','section'].includes(owner.tagName)||!['p','table'].includes(node.tagName))
        throw new Error('自由定位用于顶层段落、图片段落和表格；循环内容请先选择其模板容器并使用流式排版。');
      const placed=hitFor(snapshot,change.path,change.page)??hitFor(snapshot,change.path);
      if(!placed)throw new Error('请先让该元素成功预览，再转换为固定位置。');
      if(node.tagName==='p')for(const [name,value] of Object.entries(snapshot.formats[change.path]||{}))if(!node.hasAttribute(name))node.setAttribute(name,value);
      let layer=[...owner.children].find(child=>child.tagName==='foreground');
      if(!layer){layer=document.createElement('foreground');owner.insertBefore(layer,[...owner.children].find(child=>!['page','header','footer','background'].includes(child.tagName))??null);}
      box=document.createElement('box');
      box.setAttribute('body-flow','OVERLAP');
      for(const [name,value] of Object.entries({x:placed.hit.x,y:placed.hit.y,width:placed.hit.width,height:Math.max(placed.hit.height+2,Number(node.getAttribute('line-height')||18),24),page:placed.page+1}))box.setAttribute(name,num(value));
      layer.append(box);box.append(node);
    }
    if(box){
      for(const name of ['x','y','width','height'] as const)if(change[name]!==undefined){
        const value=change[name]!;if(!Number.isFinite(value)||value<(name==='width'||name==='height'?0.1:0))throw new Error('位置必须非负，尺寸必须大于零。');
        box.setAttribute(name,num(value));
      }
      if(change.page!==undefined)box.setAttribute('page',String(change.page+1));
      if(change.widthMode)box.setAttribute('width-mode',change.widthMode);
      if(change.heightMode)box.setAttribute('height-mode',change.heightMode);
      if(change.bodyFlow)box.setAttribute('body-flow',change.bodyFlow);
      const image=node.tagName==='image'?node:node.tagName==='p'&&node.children.length===1&&node.firstElementChild?.tagName==='image'&&!node.textContent?.trim()?node.firstElementChild:null;
      if(image&&(change.width!==undefined||change.height!==undefined)){
        if(change.width!==undefined)image.setAttribute('width',num(change.width));
        if(change.height!==undefined){image.setAttribute('height',num(change.height));if(node!==image)node.setAttribute('line-height',num(Math.max(change.height+4,18)));box.setAttribute('height',num(Math.max(change.height+4,18)));}
      }
    }else if(change.width!==undefined||change.height!==undefined){
      const image=node.tagName==='image'?node:node.children.length===1&&node.firstElementChild?.tagName==='image'?node.firstElementChild:null;
      if(!image)throw new Error('要设置文字或表格的独立框尺寸，请先切换为固定位置。');
      if(change.width!==undefined)image.setAttribute('width',num(change.width));
      if(change.height!==undefined){image.setAttribute('height',num(change.height));if(node!==image)node.setAttribute('line-height',num(change.height+4));}
    }
    if(change.rotation!==undefined){
      const target=node.tagName==='box'?node.firstElementChild:node;
      if(!target)throw new Error('空框没有可旋转内容。');
      if(!Number.isFinite(change.rotation)||Math.abs(change.rotation)>180)throw new Error('角度范围为 -180° 至 180°。');
      target.setAttribute('rotation',num(change.rotation));
    }
  }
  return {source:serialize(document),ids:selected.map(({node})=>node.getAttribute('id')||node.firstElementChild?.getAttribute('id')||'').filter(Boolean)};
}
