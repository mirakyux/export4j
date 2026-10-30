// SPDX-License-Identifier: Apache-2.0
'use strict';
const BodyEditor=(()=>{
    let source, originals=new Map(), nextKey=0, selection, host, area, toolbar;
    const make=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
    function stamp(element,node){const key=String(++nextKey);originals.set(key,node.cloneNode(true));element.dataset.inlineKey=key;}
    function display(node){
        if(node.nodeType===3||node.nodeType===4)return document.createTextNode(node.nodeValue);
        const element=make('span');stamp(element,node);
        if(node.nodeType!==1||!['span','link'].includes(node.tagName)){
            element.contentEditable='false';element.className='body-token';
            element.textContent=node.nodeType!==1?'XML':node.tagName==='image'?'▧ '+(node.getAttribute('alt')||node.getAttribute('ref')):({ 'page-number':'页码','page-count':'总页数','page-reference':'目标页码','number-reference':'目标编号','note-marker':'注释' }[node.tagName]||node.tagName);
            element.title='保留的行内对象；详细属性见高级行内片段。';return element;
        }
        if(node.tagName==='link'){element.className='body-link';element.title=node.getAttribute('href');}
        if(node.getAttribute('role')==='bold')element.style.fontWeight='bold';
        if(/^#[0-9a-f]{6}$/i.test(node.getAttribute('color')||''))element.style.color=node.getAttribute('color');
        if(/^\d+(\.\d+)?$/.test(node.getAttribute('size')||''))element.style.fontSize=node.getAttribute('size')+'pt';
        element.style.textDecoration=[node.getAttribute('underline')==='true'?'underline':'',node.getAttribute('strike')==='true'?'line-through':''].join(' ');
        [...node.childNodes].forEach(child=>element.append(display(child)));return element;
    }
    function color(value){if(/^#[0-9a-f]{6}$/i.test(value))return value;const match=value.match(/^rgb\((\d+),\s*(\d+),\s*(\d+)\)$/);return match?'#'+match.slice(1).map(n=>Number(n).toString(16).padStart(2,'0')).join(''):undefined;}
    function read(){
        const result=source.cloneNode(false),doc=result.ownerDocument;
        function convert(node,parent,style={}){
            if(node.nodeType===3){if(!Object.keys(style).length){parent.appendChild(doc.createTextNode(node.nodeValue));return;}const span=doc.createElement('span');Object.entries(style).forEach(([name,value])=>span.setAttribute(name,value));span.textContent=node.nodeValue;parent.appendChild(span);return;}
            if(node.nodeType!==1)return;
            const original=originals.get(node.dataset.inlineKey);
            if(original&&(original.nodeType!==1||!['span','link'].includes(original.tagName))){parent.appendChild(doc.importNode(original,true));return;}
            if(node.tagName==='BR'){parent.appendChild(doc.createTextNode('\n'));return;}
            let target=parent,next={...style};
            if(original?.tagName==='link'){target=doc.importNode(original.cloneNode(false),true);parent.appendChild(target);}
            if(original?.tagName==='span')[...original.attributes].forEach(attribute=>{next[attribute.name]=attribute.value;});
            if(['B','STRONG'].includes(node.tagName)||node.style.fontWeight==='bold'||Number(node.style.fontWeight)>=600)next.role='bold';
            if(node.style.fontWeight==='normal')next.role='';
            if(['U'].includes(node.tagName)||node.style.textDecoration.includes('underline'))next.underline='true';
            if(['S','STRIKE','DEL'].includes(node.tagName)||node.style.textDecoration.includes('line-through'))next.strike='true';
            const rgb=color(node.style.color||node.getAttribute('color')||'');if(rgb)next.color=rgb;
            if(/^\d+(\.\d+)?pt$/.test(node.style.fontSize))next.size=node.style.fontSize.slice(0,-2);
            if(/^\d+(\.\d+)?px$/.test(node.style.fontSize))next.size=String(parseFloat(node.style.fontSize)*.75);
            [...node.childNodes].forEach(child=>convert(child,target,next));
            if(['DIV','P'].includes(node.tagName)&&node.nextSibling)parent.appendChild(doc.createTextNode('\n'));
        }
        [...area.childNodes].forEach(node=>convert(node,result));
        // Rules belong to the cell, not the editable text selection.
        [...source.children].filter(node=>node.tagName==='style-if').forEach(node=>result.appendChild(doc.importNode(node,true)));
        return result;
    }
    function modified(){if(!source||busy||nodeXmlDirty)return;try{Editor.updateBody(read());}catch(error){status(error.message,true);}}
    function restore(){area.focus();if(selection&&area.contains(selection.commonAncestorContainer)){const current=window.getSelection();current.removeAllRanges();current.addRange(selection);}}
    function command(name,value){if(!source||busy||nodeXmlDirty)return;restore();document.execCommand(name,false,value);modified();}
    function render(node){
        if(!host)return;source=node?.cloneNode(true);host.hidden=!source;originals.clear();nextKey=0;selection=undefined;area.replaceChildren();
        if(source)[...source.childNodes].filter(child=>child.nodeType!==1||child.tagName!=='style-if').forEach(child=>area.append(display(child)));
        $('rich-fragments').hidden=!source;lock(busy||nodeXmlDirty);
    }
    function lock(value){if(area)area.contentEditable=String(!!source&&!value);}
    function focus(){if(source&&!busy){document.querySelector('[data-inspect="properties"]').click();area.focus();area.scrollIntoView({block:'nearest'});}else status('请选择段落、标题、列表项或行内单元格。',true);}
    function start(){
        host=make('div');host.id='direct-body';toolbar=make('div');toolbar.className='body-toolbar';toolbar.setAttribute('aria-label','正文格式');
        for(const [label,title,name] of [['B','粗体','bold'],['U','下划线','underline'],['S','删除线','strikeThrough']]){const button=make('button',label);button.type='button';button.title=title;button.setAttribute('aria-label',title);button.onpointerdown=event=>event.preventDefault();button.onclick=()=>command(name);toolbar.append(button);}
        const ink=make('input');ink.type='color';ink.value='#224488';ink.setAttribute('aria-label','正文选区颜色');ink.title='给选中文字设置颜色';ink.oninput=()=>command('foreColor',ink.value);toolbar.append(ink);
        const sizes=make('select');sizes.setAttribute('aria-label','正文选区字号');const placeholder=make('option','字号');placeholder.value='';sizes.append(placeholder);[8,9,10,11,12,14,16,18,20,24,28,32].forEach(size=>{const option=make('option',size+' pt');option.value=size;sizes.append(option);});
        sizes.onchange=()=>{if(!sizes.value)return;restore();document.execCommand('fontSize',false,'7');area.querySelectorAll('font[size="7"]').forEach(font=>{font.style.fontSize=sizes.value+'pt';font.removeAttribute('size');});modified();sizes.value='';};toolbar.append(sizes);
        area=make('div');area.id='body-content';area.className='body-content';area.setAttribute('role','textbox');area.setAttribute('aria-label','正文富文本');area.setAttribute('aria-multiline','true');area.spellcheck=false;
        area.oninput=modified;area.onpaste=event=>{event.preventDefault();command('insertText',event.clipboardData.getData('text/plain'));};area.ondrop=event=>event.preventDefault();
        area.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();command('insertText','\n');}};
        document.addEventListener('selectionchange',()=>{const value=window.getSelection();if(value.rangeCount&&area.contains(value.anchorNode)&&area.contains(value.focusNode))selection=value.getRangeAt(0).cloneRange();});
        const hint=make('p','变量及行内对象保持原文。');hint.className='hint';
        host.append(toolbar,area,hint);$('rich-fragments').before(host);
    }
    return {start,render,lock,focus};
})();

const DesignerActions=(()=>{
    let menu;
    const editing=target=>!!target.closest('input,textarea,select,[contenteditable="true"]');
    async function editBody(){BodyEditor.focus();}
    async function duplicate(){
        if(!selected?.path)throw new Error('请选择要复制的元素。');await drafts();
        const node=nodeAt(selected.path).cloneNode(true),ids=new Map(),taken=new Set(entries.map(entry=>entry.id));let number=1;
        for(const element of [node,...node.querySelectorAll('[id]')]){
            if(!element.hasAttribute('id'))continue;const old=element.getAttribute('id');if(old.includes('${'))throw new Error('动态 ID 的模板请在节点 XML 中显式复制和调整。');
            let fresh;do{fresh='design-'+number++;}while(taken.has(fresh));taken.add(fresh);ids.set(old,fresh);element.setAttribute('id',fresh);
        }
        node.querySelectorAll('[target]').forEach(element=>{const value=ids.get(element.getAttribute('target'));if(value)element.setAttribute('target',value);});
        const path=selected.path,parent=path.slice(0,path.lastIndexOf('/')),index=Number(path.split('/').at(-1))+1;
        await edit({operation:'insert-beside',path,after:true,xml:new XMLSerializer().serializeToString(node)});
        const fresh=entries.find(entry=>entry.path===parent+'/'+index);if(fresh){await choose(fresh);DesignerCanvas.reveal(fresh);}
    }
    function close(){if(menu){menu.remove();menu=undefined;}}
    function showMenu(event,choices,label){
        close();menu=document.createElement('div');menu.className='context-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label',label);
        choices.forEach(([label,key,action,disabled])=>{const item=document.createElement('button');item.setAttribute('role','menuitem');item.disabled=disabled;item.textContent=label;const shortcut=document.createElement('kbd');shortcut.textContent=key;item.append(shortcut);item.onclick=()=>{close();if(action===editBody)action();else run(action);};menu.append(item);});
        document.body.append(menu);const rect=menu.getBoundingClientRect();menu.style.left=Math.max(4,Math.min(event.clientX,innerWidth-rect.width-6))+'px';menu.style.top=Math.max(4,Math.min(event.clientY,innerHeight-rect.height-6))+'px';menu.querySelector('button:not(:disabled)')?.focus();
        menu.onkeydown=key=>{const buttons=[...menu.querySelectorAll('button:not(:disabled)')],index=buttons.indexOf(document.activeElement);if(['ArrowUp','ArrowDown'].includes(key.key)){key.preventDefault();buttons[(index+(key.key==='ArrowDown'?1:buttons.length-1))%buttons.length].focus();}if(key.key==='Escape'){key.preventDefault();close();}};
    }
    function canStartSection(entry){
        const parent=entries.find(item=>item.path===entry?.path.slice(0,entry.path.lastIndexOf('/')));
        if(!entry?.path||!['document','section'].includes(parent?.tag)||['page','header','footer','background','foreground','navigation','notes','section'].includes(entry.tag))return false;
        return parent.tag==='document'||parent.children.filter(item=>!['page','header','footer','background','foreground'].includes(item.tag))[0]?.path!==entry.path;
    }
    async function context(event,entry){
        event.preventDefault();event.stopPropagation();if(busy)return;
        if(!selectedPaths.includes(entry.path))await choose(entry);
        if(propertyDirty||!selectedPaths.includes(entry.path))return;close();
        const multi=selectedPaths.length>1;
        const choices=[['编辑正文','Enter',editBody,multi],['在前面插入段落','',()=>insert('p',{path:selected.path,after:false}),multi||!selected.path],['在后面插入段落','',()=>insert('p',{path:selected.path,after:true}),multi||!selected.path],['从此处新建分节','',()=>insert('section',{path:selected.path}),multi||!canStartSection(selected)],['复制元素','Ctrl+D',duplicate,multi||!selected.path],['上移','Alt+↑',()=>multi?moveSelection(-1):edit({operation:'move',direction:-1}),!selected.path],['下移','Alt+↓',()=>multi?moveSelection(1):edit({operation:'move',direction:1}),!selected.path],['删除所选元素','Delete',()=>edit(multi?{operation:'batch-remove',paths:selectedPaths}:{operation:'remove'}),!selected.path]];
        showMenu(event,choices,'元素操作');
    }
    function blankContext(event,placement){
        event.preventDefault();event.stopPropagation();if(busy)return;
        const choices=[['插入段落','',()=>insert('p',placement)],['插入标题','',()=>insert('heading',placement)],['插入列表项','',()=>insert('bullet',placement)],['插入数据列表','',()=>insert('list',placement)],['插入编号','',()=>insert('numbered',placement)],['插入表格','',()=>insert('table',placement)],['插入图片','',()=>insert('image',placement)],['插入题注','',()=>insert('caption',placement)],['插入引用','',()=>insert('cross-reference',placement)],['插入换页','',()=>insert('page-break',placement)],['从此处新建分节','',()=>insert('section',placement),!canStartSection(entries.find(item=>item.path===placement?.path))]];
        showMenu(event,choices,'在空白区域插入');
    }
    function resources(){
        document.querySelector('[data-inspect="resources"]').remove();const dock=$('resources');dock.className='resource-dock';dock.setAttribute('aria-label','项目资源');
        $('resources-view').append(dock);
        const used=document.createElement('div');used.id='used-resources';used.className='used-resources';$('structured-help').before(used);
    }
    function usedResources(node){
        const host=$('used-resources');if(!host)return;host.replaceChildren();
        const refs=[...new Set([...(node.tagName==='image'?[node]:[]),...node.querySelectorAll('image')].map(image=>image.getAttribute('ref')))];
        if(refs.length&&node.tagName!=='image'){const label=document.createElement('strong');label.textContent='此元素使用的图片';host.append(label);refs.forEach(name=>{const card=document.createElement('div');card.className='used-image';const picture=document.createElement('div'),caption=document.createElement('span');caption.textContent=name;DesignerAssets.preview(name,picture);card.append(picture,caption);host.append(card);});}
        if(['p','heading','bullet','numbered','cell','span','link'].includes(node.tagName)){
            const roles=new Set();
            function collect(part,bold){
                if(part.nodeType===3&&part.nodeValue.trim())roles.add(bold?'Bold.ttf':'Regular.ttf');
                else if(part.nodeType===1){const active=bold||part.getAttribute('role')==='bold';part.childNodes.forEach(child=>collect(child,active));}
            }
            collect(node,false);
            if(roles.size){
                const font=document.createElement('small');
                font.textContent='字体资源：'+[...roles].map(name=>name+(state.fonts.some(item=>item.name===name)?'':'（未导入）')).join(' · ')
                    +(state.fonts.some(item=>item.name==='Chinese.ttf')?'；中文回退：Chinese.ttf（按实际文字使用）':'');
                host.append(font);
            }
        }
        host.hidden=!host.childNodes.length;
    }
    function start(){
        BodyEditor.start();resources();
        $('tree').addEventListener('contextmenu',event=>{
            if(event.target.closest('.tree-item'))return;
            const entry=canStartSection(selected)?selected:entries.find(item=>item.tag==='section')||entries[0];
            blankContext(event,{path:entry?.path??'',after:true,container:!canStartSection(entry)});
        });
        document.querySelector('.file-menu-content').addEventListener('click',event=>{if(event.target.closest('button'))document.querySelector('.file-menu').open=false;});
        document.addEventListener('pointerdown',event=>{if(menu&&!menu.contains(event.target))close();});
        document.addEventListener('keydown',event=>{
            if(busy||document.querySelector('dialog[open]')||editing(event.target)||menu)return;
            const key=event.key.toLowerCase(),modifier=event.ctrlKey||event.metaKey;
            if(modifier&&key==='s'){event.preventDefault();$('save').click();return;}
            if(modifier&&(key==='z'||key==='y')){event.preventDefault();$(key==='y'||event.shiftKey?'redo':'undo').click();return;}
            if(!event.target.closest('#tree,#pages,.canvas-scroll'))return;
            if((key==='delete'||key==='backspace')&&selected?.path){event.preventDefault();run(()=>edit(selectedPaths.length>1?{operation:'batch-remove',paths:selectedPaths}:{operation:'remove'}));}
            else if(modifier&&key==='d'&&selectedPaths.length===1){event.preventDefault();run(duplicate);}
            else if(event.altKey&&['arrowup','arrowdown'].includes(key)){event.preventDefault();run(()=>selectedPaths.length>1?moveSelection(key==='arrowup'?-1:1):edit({operation:'move',direction:key==='arrowup'?-1:1}));}
            else if(['enter','f2'].includes(key)&&selectedPaths.length===1){event.preventDefault();editBody();}
            else if(event.shiftKey&&key==='f10'&&selected){event.preventDefault();const rect=event.target.getBoundingClientRect();context({preventDefault(){},stopPropagation(){},clientX:rect.x+20,clientY:rect.y+20},selected);}
        });
    }
    return {start,context,blankContext,editBody,usedResources};
})();
