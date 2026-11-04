// SPDX-License-Identifier: Apache-2.0
import {Schema,Fragment,Slice,type Node as ModelNode,type Mark} from 'prosemirror-model';
import {EditorState,TextSelection,Plugin,type Transaction} from 'prosemirror-state';
import {EditorView} from 'prosemirror-view';
import {history,undo,redo} from 'prosemirror-history';
import {keymap} from 'prosemirror-keymap';
import {baseKeymap,toggleMark,splitBlock,splitBlockAs,chainCommands} from 'prosemirror-commands';
import {serialize} from './types';

export const schema=new Schema({
  nodes:{
    doc:{content:'paragraph+'},
    paragraph:{content:'inline*',group:'block',attrs:{key:{default:null},template:{default:null},tag:{default:'p'},css:{default:null}},
      parseDOM:[{tag:'p'}],toDOM:node=>['p',{'data-studio-block-key':node.attrs.key,style:node.attrs.css},0]},
    text:{group:'inline'},
    hard_break:{inline:true,group:'inline',selectable:false,parseDOM:[{tag:'br'}],toDOM:()=>['br']},
    token:{inline:true,group:'inline',atom:true,attrs:{xml:{default:''},label:{default:''},kind:{default:'field'}},
      parseDOM:[{tag:'span[data-export4j-token]',getAttrs:element=>{
        try {const value=JSON.parse((element as Element).getAttribute('data-export4j-token')!);
          if(typeof value.xml!=='string'||typeof value.kind!=='string'||typeof value.label!=='string'||value.xml.length>65536)return false;
          if(value.kind==='variable'&&!/^\$\{[^}]+\}$/.test(value.xml))return false;
          if(value.kind!=='variable'&&new DOMParser().parseFromString('<holder>'+value.xml+'</holder>','application/xml').querySelector('parsererror'))return false;
          return value;}catch{return false;}
      }}],
      toDOM:node=>['span',{'class':'studio-token','data-export4j-token':JSON.stringify(node.attrs),'data-kind':node.attrs.kind,contenteditable:'false',title:node.attrs.xml},node.attrs.label]}
  },
  marks:{
    strong:{parseDOM:[{tag:'strong'},{tag:'b'},{style:'font-weight=bold'}],toDOM:()=>['strong',0]},
    underline:{parseDOM:[{tag:'u'},{style:'text-decoration=underline'}],toDOM:()=>['u',0]},
    strike:{parseDOM:[{tag:'s'},{tag:'del'}],toDOM:()=>['s',0]},
    style:{attrs:{attributes:{default:{}}},parseDOM:[{tag:'span[data-export4j-style]',getAttrs:node=>{
      try {const attributes=JSON.parse((node as Element).getAttribute('data-export4j-style')!);
        if(!attributes||typeof attributes!=='object'||Array.isArray(attributes))return false;
        for(const [name,value] of Object.entries(attributes)){
          if(typeof value!=='string'||value.length>256||!['style','role','size','color','baseline-shift','underline','strike'].includes(name))return false;
          if(name==='size'&&(!Number.isFinite(Number(value))||Number(value)<=0||Number(value)>512))return false;
          if(name==='color'&&!/^#[\da-f]{6}$/i.test(value))return false;
        }return {attributes};
      }catch{return false;}
    }},{tag:'span[style]',getAttrs:node=>{
      const element=node as HTMLElement,attributes:Record<string,string>={};
      if(element.style.color){const values=element.style.color.match(/^rgb\((\d+), (\d+), (\d+)\)$/);attributes.color=values?'#'+values.slice(1).map(value=>Number(value).toString(16).padStart(2,'0')).join(''):element.style.color;}
      if(element.style.fontSize.endsWith('pt'))attributes.size=element.style.fontSize.slice(0,-2);
      return {attributes};
    }}],toDOM:mark=>['span',{style:css(mark.attrs.attributes),'data-export4j-style':JSON.stringify(mark.attrs.attributes)},0]},
    link:{attrs:{href:{default:''}},inclusive:false,parseDOM:[{tag:'span[data-export4j-link]',getAttrs:node=>({href:(node as Element).getAttribute('data-export4j-link')})},{tag:'a[href]',getAttrs:node=>{
      const href=(node as Element).getAttribute('href')||'';return /^(https?:|mailto:)/i.test(href)?{href}:false;
    }}],toDOM:mark=>['span',{'data-export4j-link':mark.attrs.href,'class':'studio-link',title:mark.attrs.href},0]}
  }
});
function css(attributes:Record<string,string>):string {
  return [attributes.role==='bold'?'font-weight:bold':'',attributes.color?'color:'+attributes.color:'',
    attributes.size?'font-size:calc('+attributes.size+'px * var(--studio-scale, 1))':'',attributes.underline==='true'?'text-decoration:underline':'',
    attributes.strike==='true'?'text-decoration:line-through':''].filter(Boolean).join(';');
}
function content(node:Node,marks:readonly Mark[]=[]):ModelNode[] {
  if(node.nodeType===3||node.nodeType===4){
    const text=node.nodeValue??'',result:ModelNode[]=[];let offset=0;
    const expression=/\$\{[^}]+\}|\n/g;
    for(const match of text.matchAll(expression)){
      if(match.index!>offset)result.push(schema.text(text.slice(offset,match.index),marks));
      result.push(match[0]==='\n'?schema.nodes.hard_break.create():schema.nodes.token.create({xml:match[0],label:match[0],kind:'variable'},null,marks));
      offset=match.index!+match[0].length;
    }
    if(offset<text.length)result.push(schema.text(text.slice(offset),marks));return result;
  }
  if(node.nodeType!==1)return [schema.nodes.token.create({xml:serialize(node),label:'XML',kind:'xml'})];
  const element=node as Element;
  if(element.tagName==='style-if')return [];
  if(!['span','link'].includes(element.tagName)){
    const label=element.tagName==='image'?'图片：'+(element.getAttribute('alt')||element.getAttribute('ref')):
      ({'page-number':'页码','page-count':'总页数'} as Record<string,string>)[element.tagName]||element.tagName;
    return [schema.nodes.token.create({xml:serialize(element),label,kind:element.tagName},null,marks)];
  }
  const next=[...marks];
  if(element.tagName==='span'){
    const attributes=Object.fromEntries([...element.attributes].map(attr=>[attr.name,attr.value]));
    if(attributes.role==='bold'){next.push(schema.marks.strong.create());delete attributes.role;}
    if(attributes.underline==='true'){next.push(schema.marks.underline.create());delete attributes.underline;}
    if(attributes.strike==='true'){next.push(schema.marks.strike.create());delete attributes.strike;}
    if(Object.keys(attributes).length)next.push(schema.marks.style.create({attributes}));
  }
  else next.push(schema.marks.link.create({href:element.getAttribute('href')}));
  return [...element.childNodes].flatMap(child=>content(child,next));
}
export function editorDocument(node:Element):ModelNode {
  return schema.nodes.doc.create(null,schema.nodes.paragraph.create(null,[...node.childNodes].flatMap(child=>content(child))));
}
export interface RichSession {
  nodes:Element[];
  keys:string[];
  originals:Map<string,Element>;
  formats:Record<string,Record<string,string>>;
}
const newKey=()=> 'edit-'+crypto.randomUUID();
export function createRichSession(nodes:Element[],formats:Record<string,Record<string,string>>={}):RichSession {
  if(!nodes.length||nodes.some(node=>!['p','heading'].includes(node.tagName)))throw new Error('连续编辑只支持同一容器内的正文段落和标题。');
  const parent=nodes[0].parentElement;
  if(!parent||nodes.some((node,index)=>node.parentElement!==parent||index>0&&nodes[index-1].nextElementSibling!==node))throw new Error('连续编辑的段落必须相邻且属于同一容器。');
  const originals=new Map<string,Element>(),resolvedFormats:Record<string,Record<string,string>>={};
  const copies=nodes.map((node,index)=>{
    const copy=node.cloneNode(true) as Element,key=node.getAttribute('id')||newKey();
    if(originals.has(key))throw new Error('连续编辑的段落 ID 必须唯一。');
    copy.setAttribute('id',key);originals.set(key,copy);resolvedFormats[key]=formats[node.getAttribute('id')||String(index)]||{};return copy;
  });
  return {nodes:copies,keys:copies.map(node=>node.getAttribute('id')!),originals,formats:resolvedFormats};
}
function blockCss(node:Element,defaults:Record<string,string>={}):string {
  const css:string[]=[],value=(name:string,fallback?:string)=>node.getAttribute(name)??defaults[name]??fallback;
  const length=(attribute:string,property:string)=>{
    const raw=value(attribute);
    if(raw!==undefined&&Number.isFinite(Number(raw)))css.push(property+':calc('+Number(raw)+'px * var(--studio-scale, 1))');
  };
  const size=Number(value('size','12')),line=Number(value('line-height',String(size*1.5)));
  css.push('font-size:calc('+size+'px * var(--studio-scale, 1))','line-height:calc('+line+'px * var(--studio-scale, 1))');
  length('space-before','margin-top');length('space-after','margin-bottom');
  length('indent','padding-left');length('first-line-indent','text-indent');
  css.push('font-weight:'+(value('role')==='bold'?'bold':'normal'));
  const color=value('color','#000000');if(/^#[\da-f]{6}$/i.test(color!))css.push('color:'+color);
  const align=value('align','left')!.toLowerCase();if(['left','center','right','justify'].includes(align))css.push('text-align:'+align);
  return css.join(';');
}
export function sessionDocument(session:RichSession):ModelNode {
  return schema.nodes.doc.create(null,session.nodes.map(node=>{
    const key=node.getAttribute('id')!;
    return schema.nodes.paragraph.create({key,template:key,tag:node.tagName,css:blockCss(node,session.formats[key])},[...node.childNodes].flatMap(child=>content(child)));
  }));
}
export function sessionToXml(view:EditorView,session:RichSession):Element[] {
  const owner=session.nodes[0].ownerDocument,blocks:Element[]=[],keys=new Set<string>();
  view.state.doc.forEach(paragraph=>{
    const key=paragraph.attrs.key as string;
    if(!key||keys.has(key))throw new Error('连续编辑段落缺少唯一标识，未覆盖原内容。');
    keys.add(key);
    const original=session.originals.get(key)??session.originals.get(paragraph.attrs.template)??session.nodes[0];
    const tag=paragraph.attrs.tag==='heading'?'heading':'p',block=owner.createElement(tag);
    for(const attr of [...original.attributes])if(attr.name!=='id'&&!(original.tagName==='heading'&&tag==='p'&&['level','keep-with-next'].includes(attr.name)))block.setAttribute(attr.name,attr.value);
    block.setAttribute('id',key);appendInline(owner,block,paragraph);
    for(const rule of original.querySelectorAll(':scope > style-if'))block.append(rule.cloneNode(true));
    blocks.push(block);
  });
  return blocks;
}
function appendInline(owner:Document,block:Element,paragraph:ModelNode):void {
  paragraph.forEach(item=>{
    let child:Node;
    if(item.isText)child=owner.createTextNode(item.text!);
    else if(item.type===schema.nodes.hard_break)child=owner.createTextNode('\n');
    else if(item.type===schema.nodes.token){
      if(item.attrs.kind==='variable')child=owner.createTextNode(item.attrs.xml);
      else {const parsed=new DOMParser().parseFromString('<holder>'+item.attrs.xml+'</holder>','application/xml');if(parsed.querySelector('parsererror')||parsed.documentElement.childNodes.length!==1)throw new Error('行内 XML 无法解析，未覆盖原内容。');child=owner.importNode(parsed.documentElement.firstChild!,true);}
    }else throw new Error('不支持的编辑内容。');
    const attributes:Record<string,string>={};let href:string|undefined;
    for(const mark of item.marks){
      if(mark.type===schema.marks.style)Object.assign(attributes,mark.attrs.attributes);
      else if(mark.type===schema.marks.link)href=mark.attrs.href;
    }
    for(const mark of item.marks){
      if(mark.type===schema.marks.strong)attributes.role='bold';
      else if(mark.type===schema.marks.underline)attributes.underline='true';
      else if(mark.type===schema.marks.strike)attributes.strike='true';
    }
    if(Object.keys(attributes).length){
      if(child.nodeType===3){const wrapper=owner.createElement('span');for(const [name,value] of Object.entries(attributes))wrapper.setAttribute(name,value);wrapper.append(child);child=wrapper;}
      else if(child.nodeType===1&&['page-number','page-count','page-reference','number-reference','note-marker'].includes((child as Element).tagName)){
        for(const [name,value] of Object.entries(attributes))if(name!=='style')(child as Element).setAttribute(name,value);
      }
    }
    if(href!==undefined){const wrapper=owner.createElement('link');wrapper.setAttribute('href',href);wrapper.append(child);child=wrapper;}
    block.append(child);
  });
}
export function toXml(view:EditorView,original:Element,ids:string[]):Element[] {
  const owner=original.ownerDocument,blocks:Element[]=[];
  view.state.doc.forEach((paragraph,_offset,index)=>{
    const block=index&&original.tagName==='heading'?owner.createElement('p'):original.cloneNode(false) as Element;
    if(index&&original.tagName==='heading')for(const attr of [...original.attributes])if(!['id','level','keep-with-next'].includes(attr.name))block.setAttribute(attr.name,attr.value);
    ids[index]??=original.tagName==='cell'?'':'edit-'+crypto.randomUUID();
    if(ids[index])block.setAttribute('id',ids[index]);
    appendInline(owner,block,paragraph);
    if(index===0)for(const rule of original.querySelectorAll(':scope > style-if'))block.append(rule.cloneNode(true));
    blocks.push(block);
  });
  return blocks;
}
export interface RichOptions {
  changed(view:EditorView):void;
  paste(file:File):void;
  commit():void;
  merge():boolean;
  inlineOnly:boolean;
  editable?():boolean;
  session?:RichSession;
  selectionChanged?(view:EditorView):void;
  navigate?(direction:'previous'|'next',edge:'start'|'end'):boolean;
  tab?(direction:'previous'|'next',view:EditorView):boolean;
}
function inlineContent(fragment:Fragment):ModelNode[] {
  const result:ModelNode[]=[];let blocks=0;
  fragment.forEach(node=>{
    if(node.isTextblock){
      if(blocks++||result.length)result.push(schema.nodes.hard_break.create());
      node.content.forEach(child=>result.push(child));
    }else if(node.isInline)result.push(node);
    else throw new Error('该内容不能放入单个文本块，请先转换为文字或行内内容。');
  });
  return result;
}
function tableClipboardText(data:DataTransfer,owner:Document):string|null {
  const html=data.getData('text/html');if(!/<table(?:\s|>)/i.test(html))return null;
  const template=owner.createElement('template');template.innerHTML=html;
  const tables=[...template.content.querySelectorAll('table')];if(!tables.length)return null;
  const plain=data.getData('text/plain');if(plain)return plain;
  // HTML-only tables become delimited text; never expand spans or import a table into a text editor.
  for(const node of template.content.querySelectorAll('script,style'))node.remove();
  for(const node of template.content.querySelectorAll('br'))node.replaceWith('\n');
  for(const node of template.content.querySelectorAll('p,div,li'))node.append('\n');
  const edgeWhitespace=/^[ \r\n]+|[ \r\n]+$/g;
  for(const table of tables.reverse()){
    const text=[...table.rows].map(row=>[...row.cells].map(cell=>(cell.textContent||'').replace(edgeWhitespace,'')).join('\t')).join('\n');
    table.replaceWith('\n'+text+'\n');
  }
  return (template.content.textContent||'').replace(edgeWhitespace,'');
}
function inlineTransaction(transaction:Transaction):void {
  if(transaction.doc.childCount<2)return;
  const doc=transaction.doc,selection=transaction.selection;
  const flatten=(position:number)=>{
    let mapped=1;
    for(let index=0,offset=0;index<doc.childCount;index++){
      const child=doc.child(index);
      if(position<=offset+child.nodeSize-1)return mapped+Math.max(0,Math.min(child.content.size,position-offset-1));
      mapped+=child.content.size+1;offset+=child.nodeSize;
    }
    return mapped-1;
  };
  const paragraph=doc.firstChild!.copy(Fragment.fromArray(inlineContent(doc.content)));
  // Keep normalization in the user's transaction so undo inverts both the
  // inserted structure and its inline projection as one action.
  transaction.replaceWith(0,doc.content.size,paragraph);
  transaction.setSelection(TextSelection.create(transaction.doc,flatten(selection.from),flatten(selection.to)));
}
/** Captures native caret movement before a command or focus change can replace it. */
export function syncNativeSelection(view:EditorView):void {
  const native=view.dom.ownerDocument.getSelection();
  if(!(view.state.selection instanceof TextSelection)||!native?.anchorNode||!native.focusNode||!view.dom.contains(native.anchorNode)||!view.dom.contains(native.focusNode))return;
  const anchor=view.posAtDOM(native.anchorNode,native.anchorOffset),head=view.posAtDOM(native.focusNode,native.focusOffset);
  const selection=TextSelection.between(view.state.doc.resolve(anchor),view.state.doc.resolve(head));
  if(selection instanceof TextSelection&&!selection.eq(view.state.selection))view.dispatch(view.state.tr.setSelection(selection).setMeta('addToHistory',false));
}
export function mountRich(host:HTMLElement,node:Element,options:RichOptions):EditorView {
  const session=options.session;
  const enter=session?splitBlockAs(paragraph=>({type:schema.nodes.paragraph,attrs:{...paragraph.attrs,key:newKey(),tag:paragraph.attrs.tag==='heading'?'p':paragraph.attrs.tag}})):splitBlock;
  const navigate=(direction:'previous'|'next')=>(state:EditorState)=>{
    if(!state.selection.empty)return false;
    const boundary=direction==='previous'?state.selection.from===1:state.selection.to===state.doc.content.size-1;
    return boundary?options.navigate?.(direction,direction==='previous'?'end':'start')??false:false;
  };
  const documentEdge=(end:boolean,extend=false)=>(state:EditorState,dispatch?:((tr:Transaction)=>void))=>{
    const position=end?state.doc.content.size-1:1;
    dispatch?.(state.tr.setSelection(TextSelection.create(state.doc,extend?state.selection.anchor:position,position)).scrollIntoView());
    return true;
  };
  const bindings={...baseKeymap,
    'Mod-z':undo,'Mod-y':redo,'Mod-Shift-z':redo,
    'Mod-b':toggleMark(schema.marks.strong),'Mod-u':toggleMark(schema.marks.underline),
    // Keep document navigation and its following extension in the same synchronous
    // selection model; native selectionchange can arrive after the next keydown.
    'Mod-Home':documentEdge(false),'Mod-End':documentEdge(true),
    'Mod-Shift-Home':documentEdge(false,true),'Mod-Shift-End':documentEdge(true,true),
    'Shift-Enter':(state:EditorState,dispatch?:((tr:Transaction)=>void))=>{dispatch?.(state.tr.replaceSelectionWith(schema.nodes.hard_break.create()).scrollIntoView());return true;},
    'Mod-Enter':()=>{options.commit();return true;},
    Backspace:chainCommands((state:EditorState)=>!session&&state.selection.empty&&state.selection.from===1?options.merge():false,baseKeymap.Backspace),
    ArrowLeft:chainCommands(navigate('previous'),baseKeymap.ArrowLeft??(()=>false)),
    ArrowRight:chainCommands(navigate('next'),baseKeymap.ArrowRight??(()=>false)),
    ArrowUp:chainCommands((state:EditorState)=>view.endOfTextblock('up')?navigate('previous')(state):false,baseKeymap.ArrowUp??(()=>false)),
    ArrowDown:chainCommands((state:EditorState)=>view.endOfTextblock('down')?navigate('next')(state):false,baseKeymap.ArrowDown??(()=>false)),
    Enter:options.inlineOnly?(state:EditorState,dispatch?:((tr:Transaction)=>void))=>{dispatch?.(state.tr.replaceSelectionWith(schema.nodes.hard_break.create()));return true;}:enter
  };
  const identities=new Plugin({appendTransaction(transactions,_previous,state){
    if(!session||!transactions.some(transaction=>transaction.docChanged))return null;
    const keys=new Set<string>(),transaction=state.tr;let previous:string=session.keys[0];
    state.doc.forEach((paragraph,position)=>{
      const attrs={...paragraph.attrs};
      if(!attrs.key||keys.has(attrs.key)){
        attrs.key=newKey();attrs.template=attrs.template||previous;
        transaction.setNodeMarkup(position,undefined,attrs);
      }
      keys.add(attrs.key);previous=attrs.template||attrs.key;
    });
    return transaction.docChanged?transaction.setMeta('addToHistory',false):null;
  }});
  const view=new EditorView(host,{
    editable:()=>options.editable?.()!==false,
    state:EditorState.create({doc:session?sessionDocument(session):editorDocument(node),plugins:[history(),keymap(bindings),identities]}),
    attributes:{role:'textbox','aria-label':'画布正文编辑','aria-multiline':'true',spellcheck:'false'},
    handleKeyDown(editor,event){
      if(editor.composing||event.isComposing||event.keyCode===229||!editor.editable||options.editable?.()===false)return false;
      // Native navigation may move the caret before selectionchange is delivered.
      // Commands must use that caret rather than deleting at its previous position.
      try{syncNativeSelection(editor);}catch{if(event.key!=='Tab')return false;} // Cell navigation also works during a transient DOM replacement.
      if(event.key==='Tab'&&!event.ctrlKey&&!event.metaKey&&!event.altKey&&options.tab?.(event.shiftKey?'previous':'next',editor)){event.preventDefault();return true;}
      return false;
    },
    dispatchTransaction(transaction){
      if(transaction.docChanged&&options.editable?.()===false)return;
      if(options.inlineOnly&&transaction.docChanged)inlineTransaction(transaction);
      const applied=view.state.applyTransaction(transaction);view.updateState(applied.state);
      if(applied.transactions.some(change=>change.docChanged))options.changed(view);
      if(transaction.selectionSet||transaction.docChanged||transaction.storedMarksSet)options.selectionChanged?.(view);
    },
    handlePaste(editor,event){
      if(options.editable?.()===false){event.preventDefault();return true;}
      const file=[...(event.clipboardData?.files??[])].find(file=>file.type==='image/png'||file.type==='image/jpeg');
      if(file){event.preventDefault();options.paste(file);return true;}
      const text=event.clipboardData?tableClipboardText(event.clipboardData,host.ownerDocument):null;
      if(text!==null){event.preventDefault();editor.pasteText(text);return true;}return false;
    },
    transformPasted(slice){return options.inlineOnly&&slice.content.childCount>1?new Slice(Fragment.fromArray(inlineContent(slice.content)),0,0):slice;}
  });
  return view;
}
export function mark(view:EditorView,type:string,value?:string){
  view.focus();
  if(type==='color'||type==='size'){
    const selected=view.state.selection,existing=view.state.storedMarks??view.state.selection.$from.marks();
    const previous=existing.find(mark=>mark.type===schema.marks.style)?.attrs.attributes??{};
    const transaction=view.state.tr;
    if(selected.empty)transaction.addStoredMark(schema.marks.style.create({attributes:{...previous,[type]:value}}));
    else view.state.doc.nodesBetween(selected.from,selected.to,(node,pos)=>{
      if(!node.isInline)return;
      const attributes=node.marks.find(mark=>mark.type===schema.marks.style)?.attrs.attributes??{};
      transaction.addMark(Math.max(pos,selected.from),Math.min(pos+node.nodeSize,selected.to),schema.marks.style.create({attributes:{...attributes,[type]:value}}));
    });
    view.dispatch(transaction);
  }else toggleMark(schema.marks[type])(view.state,view.dispatch);
}
export function caret(view:EditorView,x:number,y:number){
  const position=view.posAtCoords({left:x,top:y});view.focus();
  if(position)view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(position.pos))));
}
export function selectPosition(view:EditorView,edge:'start'|'end'|number):void {
  const position=edge==='start'?1:edge==='end'?view.state.doc.content.size-1:Math.max(1,Math.min(edge,view.state.doc.content.size-1));
  view.focus();view.dispatch(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(position))));
}
export function activeMarks(view:EditorView):Record<string,string|boolean> {
  const {selection,storedMarks,doc}=view.state,groups:(readonly Mark[])[]=[];
  if(selection.empty)groups.push(storedMarks??selection.$from.marks());
  else doc.nodesBetween(selection.from,selection.to,(node,position)=>{
    if(node.isInline&&position<selection.to&&position+node.nodeSize>selection.from)groups.push(node.marks);
  });
  const result:Record<string,string|boolean>={};
  for(const name of ['strong','underline','strike'])result[name]=groups.length>0&&groups.every(marks=>marks.some(mark=>mark.type===schema.marks[name]));
  for(const name of ['size','color']){
    const values=groups.map(marks=>marks.find(mark=>mark.type===schema.marks.style)?.attrs.attributes?.[name]??'');
    result[name]=values.length&&values.every(value=>value===values[0])?values[0]:'';
  }
  return result;
}
