// SPDX-License-Identifier: Apache-2.0
'use strict';
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="designer-token"]').content;
let state, selected, selectedPaths = [], entries = [], pages = [], currentPage = 0, busy = false, propertyDirty = false;
let bundleDirty = true, savedData = '', filename = '未命名模板', exportReport, lastReport = {};
let assetKind, pendingFont, dragging, nodeXmlDirty=false, nodeTextDirty=false;
let previewId = '', previewGeneration = 0, previewTimer, previewQueue = Promise.resolve(), previewFormats = {};
const changedAttributes = new Set();
const extensions = {PDF:'pdf', DOCX:'docx', HTML:'html', MARKDOWN:'md', SVG:'svg.zip', PNG:'png.zip'};
const gridLayouts = [
    {id:'full',label:'通栏',spans:[12]},
    {id:'halves',label:'两等分',spans:[6,6]},
    {id:'thirds',label:'三等分',spans:[4,4,4]},
    {id:'wide-left',label:'左宽右窄',spans:[8,4]},
    {id:'wide-right',label:'左窄右宽',spans:[4,8]}
];

async function request(path, payload, blob = false) {
    const response = await fetch('/api/' + path, {
        method: payload === undefined ? 'GET' : 'POST',
        headers: {'X-Designer-Token': token, ...(payload === undefined ? {} : {'Content-Type':'application/json'})},
        body: payload === undefined ? undefined : JSON.stringify({revision: state?.revision, ...payload})
    });
    if (!response.ok) {
        const failure = await response.json().catch(() => ({error:'本地服务请求失败：' + response.status}));
        throw Object.assign(new Error(failure.error), {report:failure.report});
    }
    return blob ? response.blob() : response.json();
}
function status(text, error = false) {
    $('status').textContent = text;
    document.querySelector('.statusbar').classList.toggle('error', error);
    $('editor-feedback').hidden=!error;
    $('editor-feedback-message').textContent=error?text:'';
}
async function run(work) {
    if (busy) return;
    busy = true; document.body.classList.add('busy');$('cancel').hidden=false;BodyEditor.lock(true);
    document.querySelectorAll('button,select,textarea,input:not([type=file])').forEach(control => {
        if(!control.closest('#studio-container-inspector'))control.disabled = control.id !== 'cancel';
    });
    try { DesignerStudio.sync(); await DesignerStudioBridge.settled(); await work(); }
    catch (error) {
        const report={...(error.report||($('export-dialog').open?exportReport:undefined)),error:error.message};
        status(error.message, true);
        if ($('export-dialog').open) {
            showExportReport({...report,format:$('format').value});
            $('export-summary').textContent='导出未完成，未触发文件下载。';
            $('export-report').querySelector('.diagnostic-overview')?.focus();
        } else if ($('template-export-dialog').open) {
            $('template-export-summary').textContent='模板导出失败：'+error.message;
            $('template-export-summary').classList.add('error');
            $('template-export-summary').focus();
        } else diagnostics(report);
    } finally {
        busy = false; document.body.classList.remove('busy');$('cancel').hidden=true;
        document.querySelectorAll('button,select,textarea,input').forEach(control => {
            if(!control.closest('#studio-container-inspector'))control.disabled = false;
        });
        $('cancel').disabled = true; refreshButtons();
        document.dispatchEvent(new Event('designer-idle'));DesignerStudio.sync();
    }
}
function refreshButtons() {
    const history=DesignerStudioBridge.snapshot();
    $('undo').disabled = busy || !history.canUndo; $('redo').disabled = busy || !history.canRedo;
    $('download-export').disabled = busy;
    $('download-loss-report').disabled = busy || !exportReport;
    $('previous').disabled = busy || currentPage <= 0;
    $('next').disabled = busy || currentPage >= pages.length - 1;
    const owner = selected && insertionOwner(nodeAt(selected.path));
    for(const id of ['up','down'])$(id).disabled=busy||selectedPaths.some(path=>entries.find(entry=>entry.path===path)?.tag==='item');
    document.querySelectorAll('[data-insert]').forEach(button=>{const allowed=canInsertAt(button.dataset.insert,owner);button.disabled=busy||!allowed;button.title=allowed?(button.dataset.insert==='grid'?'并排放置独立图文，各格内容不串流。':'点击插入到当前选择的位置。'):'当前位置不支持此容器；请选择文档或分节正文中的组件。';});
    if ($('apply-rich')) $('apply-rich').disabled = busy || !selected || !['p','heading','bullet','numbered','cell','link'].includes(selected.tag);
    // React owns availability inside its container inspector, including busy and pending states.
    document.querySelectorAll('#properties input,#properties select,#properties textarea,#properties button').forEach(control=>{
        if(!control.closest('#studio-container-inspector'))control.disabled=busy||control.dataset.inlineUnavailable==='true'||control.dataset.requiresColumnChild==='true'||control.dataset.requiresGridTracks==='true'||control.dataset.requiresGridRotation==='true'||control.dataset.gridDisabled==='true'||control.dataset.containerDisabled==='true'||control.dataset.conditionProtected==='true';
    });
    document.querySelectorAll('#node-source textarea,#node-source button').forEach(control=>control.disabled=busy||Editor.richDirty);
    for (const id of ['source','data']) $(id).disabled=busy||propertyDirty;
    document.querySelectorAll('[data-command]').forEach(button=>button.disabled=$(button.dataset.command).disabled);
    BodyEditor.lock(busy||nodeXmlDirty);
}
function inBodyColumns(node) {
    for(let current=node;current;current=current.parentElement){if(current.tagName==='columns'&&!['table','rows'].includes(current.parentElement?.tagName||''))return true;}
    return false;
}
function insertionOwner(target, placement) {
    if(placement&&!placement.container)return target.parentElement;
    const container=['document','section','then','else','each','cell','grid','item'].includes(target.tagName)||target.tagName==='columns'&&!['table','rows'].includes(target.parentElement?.tagName||'');
    return container?target:target.parentElement;
}
function canInsertColumns(owner) {
    return !!owner&&['document','section','then','else','each'].includes(owner.tagName)&&!owner.closest('header,footer,cell,table,rows,columns,grid,item,box');
}
function canInsertAt(tag,owner){
    if(!owner)return true;
    if(owner.closest('grid')&&!owner.closest('item'))return false;
    if((inBodyColumns(owner)||owner.closest('item')||owner.closest('cell'))&&!['p','table','image'].includes(tag))return false;
    return !['columns','grid'].includes(tag)||canInsertColumns(owner);
}
function diagnostics(report) {
    lastReport = report;
    $('diagnostics').textContent = JSON.stringify(report, null, 2);
    DesignerWorkspace.report(report);
}
// Textareas normalize CRLF and CR to LF; a display-only newline change must not create history.
function sameText(left, right) { return left.replace(/\r\n?/g,'\n') === right.replace(/\r\n?/g,'\n'); }
function sourceChanged() { return !sameText($('source').value, state.source); }
function dataChanged() { return !sameText($('data').value, state.data); }
function dirty() { return bundleDirty || !sameText(savedData, $('data').value) || propertyDirty || state && sourceChanged(); }
function markDirty() {
    $('file-state').textContent = filename + (dirty() ? ' · 未保存' : ' · 已保存');
    document.title = (dirty() ? '* ' : '') + 'Export4j Designer · ' + filename;
    DesignerWorkspace.documentState(filename,dirty());
}
function invalidate() {
    $('preview-status').textContent = '预览待刷新'; refreshButtons();
}
function flatten(entry, depth = 0) {
    entries.push({...entry, depth}); entry.children.forEach(child => flatten(child, depth + 1));
}
function install(value, changed = true, options = {}) {
    DesignerActions.close();
    if(options.session){clearPreview();selected=undefined;selectedPaths=[];}
    DesignerStudioBridge.accepted(value,options);
    const oldId = selected?.id, oldPath = selected?.path, oldSelections = selectedPaths.map(path => entries.find(entry => entry.path === path)).filter(Boolean);
    state = value; $('source').value = value.source; $('data').value = value.data;
    propertyDirty = false; nodeXmlDirty=false; nodeTextDirty=false; changedAttributes.clear(); entries = []; flatten(value.outline);
    if (changed) bundleDirty = true;
    selected = entries.find(entry => oldId && entry.id === oldId) ?? entries.find(entry => entry.path === oldPath) ?? entries[0];
    selectedPaths = oldSelections.map(old => entries.find(entry => old.id && entry.id === old.id) ?? entries.find(entry => entry.path === old.path)).filter(Boolean).map(entry => entry.path);
    if (!selectedPaths.length && selected) selectedPaths = [selected.path];
    $('tree').replaceChildren();
    entries.forEach(entry => {
        const item = document.createElement('button'); item.className = 'tree-item'; item.style.setProperty('--depth', entry.depth);
        item.setAttribute('role','treeitem'); item.dataset.path = entry.path; item.title = entry.label;
        item.textContent = entry.tag; const id = document.createElement('code'); id.textContent = entry.id; item.append(id);
        if(!entry.id&&entry.label.trim()!==entry.tag){const text=document.createElement('span');text.className='tree-preview';text.textContent=entry.label.replace(entry.tag,'').trim();item.append(text);}
        item.addEventListener('click', event => choose(entry,event));
        if(entry.depth>0)DesignerCanvas.bindDrag(item,{...entry,tree:true});
        item.addEventListener('contextmenu',event=>DesignerActions.context(event,entry));
        $('tree').append(item);
    });
    DesignerWorkspace.decorateTree();
    $('element-count').textContent = entries.length + ' 个元素';
    list('fonts-list', value.fonts.map(font => font.name + ' · ' + (font.bytes / 1024).toFixed(0) + ' KB'));
    DesignerAssets.install(value.images);
    list('fragments-list', Object.keys(value.fragments)); list('licenses-list', value.licenses);
    inspect(); invalidate(); markDirty(); refreshButtons();
    if (busy) document.querySelectorAll('button,select,textarea,input:not([type=file])').forEach(control=>{
        if(!control.closest('#studio-container-inspector'))control.disabled=control.id!=='cancel';
    });
}
function list(id, values) {
    $(id).replaceChildren();
    (values.length ? values : ['暂无']).forEach(text => { const item = document.createElement('div'); item.className = 'resource-item'; item.textContent = text; $(id).append(item); });
}
function nodeAt(path) {
    let node = new DOMParser().parseFromString(state.source, 'application/xml').documentElement;
    if (path) path.slice(1).split('/').forEach(index => node = node.children[Number(index)]);
    return node;
}
async function choose(entry, event, group) {
    try {
        if(propertyDirty)await applyProperties();
        if(sourceChanged()||dataChanged())await DesignerStudioBridge.apply($('source').value);
        entry=entries.find(item=>entry.id&&item.id===entry.id)??entries.find(item=>item.path===entry.path)??entry;
    }
    catch(error){status('无法切换元素：'+error.message,true);diagnostics({error:error.message});return;}
    let paths = group?.map(item => item.path) ?? [entry.path];
    if (event?.shiftKey && selected?.path && !group) {
        const start = entries.findIndex(item => item.path === selected.path), end = entries.findIndex(item => item.path === entry.path);
        paths = entries.slice(Math.min(start,end), Math.max(start,end)+1).filter(item => item.depth === entry.depth && item.path.slice(0,item.path.lastIndexOf('/')) === entry.path.slice(0,entry.path.lastIndexOf('/'))).map(item => item.path);
    } else if ((event?.ctrlKey || event?.metaKey) && !group) {
        paths = selectedPaths.includes(entry.path) ? selectedPaths.filter(path => path !== entry.path) : [...selectedPaths,entry.path];
    }
    selectedPaths = [...new Set(paths)];
    selected = selectedPaths.includes(entry.path) ? entry : entries.find(item => item.path === selectedPaths.at(-1));
    if (!selected) { selected = entry; selectedPaths = [entry.path]; }
    propertyDirty = false; nodeXmlDirty=false; nodeTextDirty=false; inspect();
    $('properties').scrollTop=0;
}
function inspect() {
    if (!selected) return;
    const node = nodeAt(selected.path), multi = selectedPaths.length > 1;
    $('node-tag').textContent = multi ? selectedPaths.length + ' 个元素' : node.tagName; $('attributes').replaceChildren();
    const nodes = selectedPaths.map(nodeAt);
    const names = multi ? [...new Set(nodes.flatMap(item => [...item.attributes].map(attribute => attribute.name)))].filter(name => name !== 'id') : [...node.attributes].map(attribute => attribute.name);
    names.sort((a,b) => a === 'id' ? -1 : b === 'id' ? 1 : a.localeCompare(b));
    names.forEach(name => { const values=nodes.map(item=>item.getAttribute(name)); attributeRow(name,values.every(value=>value===values[0]) ? values[0] ?? '' : '',values.some(value=>value!==values[0])); });
    $('node-text').value = node.children.length ? '' : node.textContent; $('node-text').readOnly = !!node.children.length;
    if(multi){$('node-text').value='';$('node-text').readOnly=true;}
    $('text-help').textContent = multi ? '多选时只修改共同属性；ID 和正文需单独编辑。' : node.children.length ? '当前节点含子元素，请选择子节点或编辑节点 XML。' : '支持模板变量，例如 ${title}。';
    nodeTextDirty=false;
    $('node-xml').value = new XMLSerializer().serializeToString(node);
    Editor.inspect(node);
    $('node-source').hidden=multi;
    document.querySelectorAll('.tree-item').forEach(item => item.setAttribute('aria-selected', String(selectedPaths.includes(item.dataset.path))));
    DesignerWorkspace.selection();
    highlight(); propertyDirty = false; nodeDirtyLabel();
}
function attributeRow(name = '', value = '', mixed = false) {
    const row = document.createElement('div'); row.className = 'attribute-row';
    const key = document.createElement('input'); key.value = name; key.placeholder = '属性名'; key.setAttribute('aria-label','属性名');
    const input = document.createElement(name === 'value' && selected?.tag === 'if' ? 'textarea' : 'input');
    if(input.tagName==='TEXTAREA')input.rows=2;
    input.value = value; input.placeholder = mixed ? '多个值' : '属性值'; input.setAttribute('aria-label', name ? name + ' 属性值' : '属性值');
    const remove = document.createElement('button'); remove.textContent = '×'; remove.title = '移除此属性';
    remove.addEventListener('click', () => { row.remove(); changedAttributes.add(name); propertyDirty = true; nodeDirtyLabel(); schedulePreview(); });
    for (const field of [key,input]) field.addEventListener('input', () => { changedAttributes.add(name); changedAttributes.add(key.value.trim()); propertyDirty = true; nodeDirtyLabel(); schedulePreview(); });
    row.append(key,input,remove); $('attributes').append(row);
}
function nodeDirtyLabel() {
    $('node-state').textContent = propertyDirty ? '修改已预览 · 导出模板以保存' : '实时预览 · 支持撤销与重做';
    $('node-state').classList.toggle('pending', propertyDirty); markDirty(); refreshButtons();
}
async function drafts() {
    if (propertyDirty) await applyProperties();
    if (sourceChanged() || dataChanged()) {
        await DesignerStudioBridge.apply($('source').value);
    }
    await DesignerStudioBridge.flush();
}
async function edit(operation, render = true, context, layout = false) {
    await DesignerStudioBridge.flush(false);
    if(context){if(layout)DesignerStudio.requireCanvasLayout(context);else DesignerStudio.requireCanvasContext(context);}
    if (propertyDirty && !['properties','replace','batch-properties'].includes(operation.operation)) throw new Error('请先完成当前节点修改。');
    if (sourceChanged() || dataChanged()) throw new Error('请先应用模板源码或测试数据修改。');
    const path = operation.path ?? selected.path;
    const movedPath = operation.operation === 'move' && !selected.id
        ? path.slice(0,path.lastIndexOf('/')) + '/' + (Number(path.split('/').at(-1)) + operation.direction)
        : undefined;
    install(await request('edit', {...operation, path:operation.path ?? selected.path}));
    if(render)await preview();else schedulePreview();
    if (movedPath) {
        const moved = entries.find(entry => entry.path === movedPath);
        if (moved) { await choose(moved); DesignerCanvas.reveal(moved); }
    }
}
function clearPreview(){
    DesignerActions.close();
    clearTimeout(previewTimer);++previewGeneration;
    pages=[];previewId='';previewFormats={};currentPage=0;
    DesignerCanvas.clear();$('container-template-state')?.remove();$('pages').hidden=false;
    $('page-jump').replaceChildren();$('page-number').textContent='— / —';
    $('empty-preview').hidden=false;$('empty-preview').querySelector('p').textContent='正在等待当前模板的引擎预览。';
}
async function preview(generation=++previewGeneration) {
    clearTimeout(previewTimer);
    const source=draftSource(),session=DesignerStudioBridge.snapshot().session;
    const payload={source:Editor.pageDraftSource(source),data:$('data').value};
    const identity={...payload,resourceIdentity:state.resourceIdentity};
    DesignerStudioBridge.previewing(identity);
    status('正在排版文档…');
    let result;
    try{result=await request('preview',payload);}catch(error){
        if(generation!==previewGeneration||session!==DesignerStudioBridge.snapshot().session)return;
        if(generation===previewGeneration){
            if(!DesignerStudioBridge.previewFailed(identity))return;
            if(/\bEMPTY_TABLE\b/.test(error.message)){
                showContainerTemplateState();$('preview-status').textContent='当前数据未生成表格行 · 可继续编辑模板';
                status('当前数据未生成表格正文行。可以编辑行模板，加载记录后再检查分页。');return;
            }
            if(!pages.length){$('empty-preview').hidden=false;$('empty-preview').querySelector('p').textContent='当前模板未生成预览：'+error.message;$('preview-status').textContent='当前模板预览未生成';}
        }
        throw error;
    }
    if(generation!==previewGeneration||session!==DesignerStudioBridge.snapshot().session)return;
    $('container-template-state')?.remove();$('pages').hidden=false;
    if(!DesignerStudioBridge.previewed(source,identity))return;
    DesignerActions.close();
    pages = result.pages; previewId=result.previewId; previewFormats=result.formats||{};
    diagnostics({milliseconds:result.milliseconds, pages:pages.length, diagnostics:result.diagnostics});
    DesignerCanvas.render(); $('preview-status').textContent = result.milliseconds + ' ms · ' + pages.length + ' 页'; status('预览完成 · ' + pages.length + ' 页');
}
function schedulePreview() {
    const generation=++previewGeneration;
    clearTimeout(previewTimer);
    $('preview-status').textContent='正在等待输入…';
    previewTimer=setTimeout(()=>{
        previewQueue=previewQueue.catch(()=>{}).then(async()=>{
            if(generation!==previewGeneration||busy)return;
            try{await preview(generation);}catch(error){if(generation===previewGeneration){status('草稿无法预览：'+error.message,true);diagnostics({error:error.message});$('preview-status').textContent='草稿有错误 · 保留上次预览';}}
        });
    },380);
}
function attributeValues(){
    const values={};
    document.querySelectorAll('.attribute-row').forEach(row=>{const fields=row.querySelectorAll('input,textarea'),name=fields[0].value.trim();if(name){if(Object.hasOwn(values,name))throw new Error('属性名不能重复。');values[name]=fields[1].value;}});
    return values;
}
function draftNode(document,path) {
    const entry=entries.find(entry=>entry.path===path);
    const matches=id=>[...document.querySelectorAll('[id]')].filter(node=>node.getAttribute('id')===id);
    if(entry?.id){const nodes=matches(entry.id);if(nodes.length===1)return nodes[0];if(nodes.length>1)throw new Error('元素 ID 重复，请先修正 XML。');}
    // An edited paragraph can have inserted siblings. Resolve an ID-bearing ancestor before using its relative path.
    let original=nodeAt(path),suffix=[];
    while(original?.parentElement){
        suffix.unshift([...original.parentElement.children].indexOf(original));original=original.parentElement;
        const id=original.getAttribute('id');if(!id)continue;
        const nodes=matches(id);if(nodes.length!==1)continue;
        let node=nodes[0];for(const index of suffix)node=node?.children[index];
        if(node&&(!entry||node.tagName===entry.tag))return node;
        throw new Error('元素位置已变化，请重新选择后修改属性。');
    }
    const node=nodeForPath(document,path);
    if(!node||entry&&node.tagName!==entry.tag)throw new Error('元素位置已变化，请重新选择后修改属性。');
    return node;
}
function draftSource(){
    if(!propertyDirty)return $('source').value;
    const document=new DOMParser().parseFromString($('source').value,'application/xml');
    if(document.querySelector('parsererror'))throw new Error('XML 无法解析，修改已保留，请先修正 XML。');
    if(nodeXmlDirty){const replacement=new DOMParser().parseFromString($('node-xml').value,'application/xml');if(replacement.querySelector('parsererror'))throw new Error('节点 XML 无法解析。');draftNode(document,selected.path).replaceWith(document.importNode(replacement.documentElement,true));}
    else if(Editor.richDirty){const replacement=new DOMParser().parseFromString(Editor.bodyXml(attributeValues()),'application/xml');if(replacement.querySelector('parsererror'))throw new Error('正文 XML 无法解析。');draftNode(document,selected.path).replaceWith(document.importNode(replacement.documentElement,true));}
    else {
        const values=attributeValues();
        selectedPaths.forEach(path=>{
            const node=draftNode(document,path);
            changedAttributes.forEach(name=>{
                if(selectedPaths.length>1&&name==='id')throw new Error('ID 只能逐个修改。');
                if(values[name])node.setAttribute(name,values[name]);else node.removeAttribute(name);
            });
            // The inspector may still display text from before a canvas edit. Only an explicit text edit replaces it.
            if(selectedPaths.length===1&&nodeTextDirty&&!$('node-text').readOnly)node.textContent=$('node-text').value;
        });
    }
    if([...changedAttributes].some(name=>['row','column-start','column-span'].includes(name)))DesignerStudio.sortGridItems(document);
    return new XMLSerializer().serializeToString(document);
}
function nodeForPath(document,path){let node=document.documentElement;if(path)path.slice(1).split('/').forEach(index=>node=node.children[Number(index)]);return node;}
async function page(index) {
    DesignerCanvas.scrollTo(index);
}
function highlight() { DesignerCanvas.highlight(); DesignerStudio.sync(); }
async function selectPageProperties(page,context=DesignerStudio.canvasContext()) {
    DesignerStudio.requireCanvasContext(context);
    await DesignerStudio.finishText();DesignerStudio.requireCanvasContext(context);
    await drafts();DesignerStudio.requireCanvasContext(context);
    const path=page===undefined?'':await DesignerStudioBridge.pageOwner(page);
    DesignerStudio.requireCanvasContext(context);
    const entry=entries.find(item=>item.path===path&&['document','section'].includes(item.tag));
    if(!entry)throw new Error('页面归属已变化，请重新选择。');
    if(page!==undefined)DesignerCanvas.activate(page);
    await choose(entry);DesignerWorkspace.openInspector();document.querySelector('[data-inspect="properties"]').click();
}
document.addEventListener('designer-select-page',event=>{const context=event.detail?.context??DesignerStudio.canvasContext();void run(()=>selectPageProperties(event.detail?.page,context));});
document.querySelector('.canvas-scroll').addEventListener('click',event=>{
    if(!event.target.closest('.canvas-sheet,button,input,select,textarea,dialog')){const context=DesignerStudio.canvasContext();void run(()=>selectPageProperties(undefined,context));}
});
function zoom() { DesignerCanvas.zoom(); }
function download(blob, name) {
    const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function ask(message, initial) {
    return new Promise(resolve => {
        const dialog = $('question-dialog'), input = $('question-answer');
        $('question-title').textContent = message; $('question-field').hidden = initial === undefined;
        input.value = initial ?? ''; input.disabled = false;
        const finish = value => { dialog.close(); resolve(value); };
        $('question-cancel').disabled = false; $('question-ok').disabled = false;
        $('question-cancel').onclick = () => finish(null);
        $('question-ok').onclick = () => finish(initial === undefined ? true : input.value);
        dialog.oncancel = event => { event.preventDefault(); finish(null); };
        input.onkeydown = event => { if (event.key === 'Enter') { event.preventDefault(); $('question-ok').click(); } };
        dialog.showModal(); if (initial !== undefined) { input.focus(); input.select(); }
    });
}
async function discard() { return !dirty() || !!(await ask('有未保存的模板、数据或节点修改，是否放弃？')); }
async function bytes(file) {
    if (file.size > 16 * 1024 * 1024) throw new Error('文件超过设计器的 16 MiB 输入上限。');
    const buffer = new Uint8Array(await file.arrayBuffer()); let text = '';
    for (let offset = 0; offset < buffer.length; offset += 8192) text += String.fromCharCode(...buffer.subarray(offset, offset + 8192));
    return btoa(text);
}
function textFile(file) { if (file.size > 4_000_000) throw new Error('文本文件超过 4,000,000 字节。'); return file.arrayBuffer().then(buffer => new TextDecoder('utf-8',{fatal:true}).decode(buffer)); }
function nextId() { let n = 1; while (state.source.includes('design-' + n)) n++; return 'design-' + n; }
function listBinding() {
    return DesignerBindings.listFallback(state.data);
}
function bindingNodePath(node){const parts=[];while(node?.parentElement){parts.unshift([...node.parentElement.children].indexOf(node));node=node.parentElement;}return parts.length?'/'+parts.join('/'):'';}
function gridLayoutPreview(spans) {
    const preview=document.createElement('span');preview.className='grid-layout-preview';preview.setAttribute('aria-hidden','true');
    for(const span of spans){const part=document.createElement('span');part.style.flex=String(span);preview.append(part);}
    return preview;
}
function pickGridLayout({allowSelection=false}={}) {
    return new Promise(resolve=>{
        const dialog=$('grid-layout-dialog'),choices=$('grid-layout-choices');choices.replaceChildren();
        const offering=allowSelection&&selectedPaths.length>1,selection=$('grid-selection-toggle');$('grid-selection-option').hidden=!offering;
        const reason=offering?DesignerStudio.gridSelectionReason(DesignerStudioBridge.snapshot()):null;
        selection.checked=offering&&!reason;selection.disabled=!!reason;
        $('grid-selection-description').textContent=reason||'保留原内容，按阅读顺序放入各格。';
        const finish=value=>{dialog.close();resolve(value);};
        for(const layout of gridLayouts){
            const button=document.createElement('button');button.type='button';button.dataset.gridLayout=layout.id;
            const label=document.createElement('span');label.textContent=layout.label;button.append(gridLayoutPreview(layout.spans),label);
            button.onclick=()=>finish({layout:layout.id,fromSelection:offering&&selection.checked&&!selection.disabled});choices.append(button);
        }
        $('cancel-grid-layout').disabled=false;$('close-grid-layout').disabled=false;
        $('cancel-grid-layout').onclick=()=>finish(null);$('close-grid-layout').onclick=()=>finish(null);
        dialog.oncancel=event=>{event.preventDefault();finish(null);};dialog.showModal();choices.querySelector('button').focus();
    });
}
async function focusGridContent(itemId) {
    const item=entries.find(entry=>entry.id===itemId);if(!item)return;
    await choose(item);DesignerCanvas.reveal(item);
}
async function editGridContent(path=selected?.path) {
    await drafts();
    const context=DesignerStudio.gridContext(state.source,path),itemPath=context?.itemPath;
    const content=itemPath&&entries.find(entry=>['p','table'].includes(entry.tag)&&entry.path.startsWith(itemPath+'/'));
    if(!content)throw new Error('此格暂未包含可编辑的段落或表格。');
    await choose(content);DesignerCanvas.reveal(content);
    if(content.tag==='p'){
        const focus=()=>{if(selected?.path===content.path)DesignerStudio.focusText(content.path);};
        if(busy)document.addEventListener('designer-idle',focus,{once:true});else focus();
    }
}
async function applyGridOperation(operation,path=selected?.path) {
    await drafts();
    const context=DesignerStudio.gridContext(state.source,path);
    if(!context)throw new Error('请选择独立图文栅格或其中的内容。');
    if(!context.editable)throw new Error(context.reason||'此动态栅格暂不支持可视化布局调整，请编辑 XML。');
    const selectedItemId=context.itemPath?nodeAt(context.itemPath).getAttribute('id'):null;
    const result=DesignerStudio.gridEdit(DesignerStudioBridge.snapshot(),path,operation);
    const selectionIds=operation.type==='add-row'?result.ids.slice(0,1):selectedItemId?[selectedItemId]:[];
    const selectionPaths=operation.type==='boundary'&&context.itemPath?[context.itemPath]:result.paths.slice(0,1);
    await DesignerStudioBridge.apply(result.source,selectionIds,selectionPaths);
    if(operation.type==='add-row'){
        await DesignerStudioBridge.flush();await focusGridContent(result.ids[0]);
        status('已添加空白栅格行，可以直接编写内容。');
    }else if(operation.type==='layout-row'){
        const layout=gridLayouts.find(layout=>layout.id===operation.layout),merged=context.items.length>(layout?.spans.length||context.items.length);
        status('本行已调整为'+(layout?.label||'所选布局')+(merged?'；原有内容已按顺序并入末格，可撤销。':'；支持撤销。'));
    }else status('栅格布局已调整，支持撤销。');
    return result;
}
async function addVisualGridRow(path=selected?.path) {
    const choice=await pickGridLayout();if(choice)await applyGridOperation({type:'add-row',layout:choice.layout},path);
}
async function focusContainerText(path){
    const entry=entries.find(entry=>entry.path===path);if(!entry)return;
    await DesignerStudioBridge.choose(path);DesignerCanvas.reveal(entry);
    const focus=()=>{if(selected?.path===path)DesignerStudio.focusText(path);};
    if(busy)document.addEventListener('designer-idle',focus,{once:true});else focus();
}
function showContainerTemplateState(){
    $('container-template-state')?.remove();$('pages').hidden=true;$('empty-preview').hidden=true;
    const panel=document.createElement('section');panel.id='container-template-state';panel.className='container-template-state';
    const heading=document.createElement('h2');heading.textContent='编辑内容模板';
    const hint=document.createElement('p');hint.textContent='当前数据没有生成表格正文行。这里仍可编辑项目和行模板；加载记录后显示实际分页。';panel.append(heading,hint);
    for(const entry of entries.filter(entry=>['list','rows'].includes(entry.tag))){
        const context=DesignerStudio.containerContext(DesignerStudioBridge.snapshot(),entry.path),card=document.createElement('div'),label=document.createElement('strong');
        label.textContent=context.label+(entry.id?' · '+entry.id:'');card.append(label);
        for(const path of context.textPaths){
            const source=nodeAt(path),button=document.createElement('button');button.type='button';button.textContent=source.textContent.trim()||'编辑空白内容';
            button.onclick=()=>{void run(()=>editContainerContent(path));};card.append(button);
        }
        panel.append(card);
    }
    $('pages').before(panel);
}
async function editContainerContent(path=selected?.path){
    await drafts();
    if(typeof path!=='string')throw new Error('请先选择要编辑的内部内容。');
    // Resolve within the selected subtree, including block cells, instead of its enclosing table.
    await focusContainerText(path);
}
function pickContainerContent(){
    return new Promise(resolve=>{
        const dialog=$('container-content-dialog'),finish=value=>{dialog.close();resolve(value);};
        for(const button of dialog.querySelectorAll('[data-container-kind]')){button.disabled=false;button.onclick=()=>finish(button.dataset.containerKind);}
        $('container-content-cancel').disabled=false;$('container-content-cancel').onclick=()=>finish(null);
        dialog.oncancel=event=>{event.preventDefault();finish(null);};dialog.showModal();dialog.querySelector('[data-container-kind]').focus();
    });
}
async function addContainerContent(path=selected?.path){
    await drafts();
    const context=DesignerStudio.containerContext(DesignerStudioBridge.snapshot(),path);if(!context)throw new Error('请先选择一个容器或内部内容。');
    if(context.kind==='if'){throw new Error('请先选择满足条件或不满足条件分支，再添加内容。');}
    if(context.kind==='list'){await editContainerContent(path);status('正在编辑每一项的内容模板；项目数量由数组决定。');return;}
    let owner=nodeAt(context.path),ownerPath=context.path;
    if(['table','rows'].includes(context.kind))ownerPath=context.table?.cellPath||context.children[0]?.path;
    else if(context.kind==='grid')ownerPath=context.children.find(child=>child.tag==='item')?.path;
    if(!ownerPath)throw new Error('请先选择要添加内容的单元格或栅格项。');
    owner=nodeAt(ownerPath);
    if(!['cell','item','columns','each','then','else'].includes(owner.tagName))throw new Error('请选择单元格、栅格项、条件分支、数据循环或正文分栏。');
    const kind=await pickContainerContent();if(kind)await insert(kind,{path:ownerPath,container:true});
}
async function containerAction(path,action){
    if(action==='edit-source'){BodyEditor.focus();return;}
    if(action==='edit-content'){await editContainerContent(path);return;}
    if(action==='add-content'){await addContainerContent(path);return;}
    await drafts();
    const result=DesignerStudio.containerEdit(DesignerStudioBridge.snapshot(),path,{type:action});
    await DesignerStudioBridge.apply(result.source,result.ids,result.paths);await DesignerStudioBridge.flush();
    status('容器内容已调整，可以继续编辑；支持撤销。');
}
document.addEventListener('designer-container-action',event=>{
    const {path,action}=event.detail||{};
    if(action==='edit-source'){BodyEditor.focus();return;}
    if(typeof path==='string'&&['edit-content','add-content','add-row','remove-row','add-column','remove-column'].includes(action))void run(()=>containerAction(path,action));
});
document.addEventListener('designer-template-select',event=>{
    const path=event.detail?.path;if(typeof path!=='string')return;
    void run(async()=>{await drafts();const entry=entries.find(item=>item.path===path);if(!entry)throw new Error('模板位置已变化，请重新选择。');await choose(entry);DesignerWorkspace.openInspector({focus:true});document.querySelector('[data-inspect="properties"]').click();});
});
document.addEventListener('designer-template-insert',event=>{
    const kind=event.detail?.kind,context=DesignerStudio.canvasContext();if(['if','each'].includes(kind))void run(()=>insert(kind,undefined,undefined,context));
});
async function insert(tag, placement, imageName, context=DesignerStudio.canvasContext(), layout = false) {
    const check=()=>layout?DesignerStudio.requireCanvasLayout(context):DesignerStudio.requireCanvasContext(context);
    check();
    await drafts();
    check();
    const insertionPage=placement?.page??currentPage;
    if(placement?.page!==undefined){
        const {page,...position}=placement;
        placement={...position,path:await DesignerStudioBridge.pageOwner(page),container:true};
        check();
    }
    const target=nodeAt(placement?.path??selected?.path??'');let owner=insertionOwner(target,placement);
    if(tag!=='section'&&owner?.tagName==='document'&&[...owner.children].some(node=>node.tagName==='section')){
        const path=await DesignerStudioBridge.pageOwner(insertionPage);
        check();
        if(!path)throw new Error('此页不属于正文分节，请先选择正文页再插入内容。');
        placement={path,container:true};owner=nodeAt(path);
    }
    if(!canInsertAt(tag,owner))throw new Error('此处不能插入该元素。栅格内请先选择栅格项添加段落、表格或图片；栅格和正文分栏用于独立正文区域，不支持互相嵌套。');
    if (tag === 'section') {
        const path = placement?.path ?? selected?.path;
        const id = nextId();
        await edit({operation:'start-section',path,id,initialId:'section-'+id},true,context,layout);
        const section = entries.find(entry=>entry.tag==='section'&&entry.id===id);
        if(section){await choose(section);DesignerCanvas.reveal(section);}
        return;
    }
    const id = nextId(); let xml, data;
    switch (tag) {
        case 'heading': xml = `<heading id="${id}" level="2" size="18" line-height="27">新标题</heading>`; break;
        case 'bullet': xml = `<bullet id="${id}">列表项</bullet>`; break;
        case 'if': {
            const path=await DesignerBindings.pickCondition(bindingNodePath(owner));if(path===null)return;
            xml=`<if test="${path}"><then><p id="${id}">满足条件时显示的内容</p></then><else><p id="${id}-else">不满足条件时显示的内容</p></else></if>`;break;
        }
        case 'each':
        case 'list':
        case 'rows': {
            const binding=await DesignerBindings.pickSource(tag,bindingNodePath(owner),{fallback:listBinding()});if(!binding)return;
            data=binding.data;
            const variable='${'+binding.expression+'}';
            xml=tag==='each'?`<each items="${binding.items}" as="${binding.as}"><p id="${id}">${variable}</p></each>`:tag==='list'?`<list items="${binding.items}" as="${binding.as}"><bullet id="${id}-item">${variable}</bullet></list>`
                :`<rows id="${id}" items="${binding.items}" as="${binding.as}"><columns><column weight="1"/></columns><header><cell>内容</cell></header><row><cell>${variable}</cell></row></rows>`;
            break;
        }
        case 'numbered': xml = `<numbered id="${id}" list-id="list-${id}">编号项</numbered>`; break;
        case 'table': xml = DesignerStudio.tableMarkup(id); break;
        case 'columns': xml = `<columns id="${id}" count="2" gap="18" balanced="true"><p id="${id}-body">在这里编写正文。内容按阅读顺序流经各栏，填满后自动续页。可在结构树中添加、编辑和调整段落顺序。</p></columns>`; break;
        case 'grid': {
            const choice=await pickGridLayout({allowSelection:true});if(!choice)return;
            check();
            if(choice.fromSelection){
                const result=DesignerStudio.gridFromSelection(DesignerStudioBridge.snapshot(),choice.layout);
                await DesignerStudioBridge.apply(result.source,result.ids,result.paths);await DesignerStudioBridge.flush();
                const grid=entries.find(entry=>entry.id===result.ids[0]),item=grid&&entries.find(entry=>entry.tag==='item'&&entry.path.startsWith(grid.path+'/'));
                if(item)await focusGridContent(item.id);status('所选内容已并排放入栅格；内容和顺序保留，支持撤销。');return;
            }
            const layout=gridLayouts.find(layout=>layout.id===choice.layout);let start=1;
            const items=layout.spans.map((span,index)=>{const item=`<item id="${id}-item-${index+1}" row="1" column-start="${start}" column-span="${span}"><p id="${id}-item-${index+1}-text"/></item>`;start+=span;return item;}).join('');
            xml=`<grid id="${id}" tracks="12" column-gap="12" row-gap="12">${items}</grid>`;break;
        }
        case 'image': {
            const name = imageName ?? await DesignerAssets.pick(); if (name === null) return;
            if (!/^[A-Za-z_][A-Za-z_0-9-]*$/.test(name)) throw new Error('图片名称必须是静态标识符。');
            xml = `<p id="${id}" line-height="58"><image ref="${name}" width="72" height="48" alt="图片说明"/></p>`; break;
        }
        case 'caption': xml = `<caption id="${id}" kind="Figure">新图示</caption>`; break;
        case 'cross-reference': {
            const target = await ask('目标元素 ID', ''); if (target === null) return;
            if (!/^[A-Za-z_][A-Za-z_0-9-]*$/.test(target)) throw new Error('目标 ID 无效。');
            xml = `<cross-reference id="${id}" kind="PAGE" target="${target}">参见第 </cross-reference>`; break;
        }
        case 'page-break': xml = `<page-break id="${id}"/>`; break;
        default: xml = `<p id="${id}" size="12" line-height="20">新段落</p>`;
    }
    check();
    if(owner.tagName==='cell'&&(!placement||placement.container)){
        const document=new DOMParser().parseFromString(state.source,'application/xml'),cell=nodeForPath(document,bindingNodePath(owner));
        const blocks=[...cell.children].some(child=>['p','table'].includes(child.tagName));
        if(!blocks){
            if(cell.querySelector('style-if'))throw new Error('此格包含条件样式，添加块内容前请先移除条件规则；原内容已保留。');
            const previous=document.createElement('p');
            for(const name of ['size','line-height','role','color','align','wrap','break'])if(cell.hasAttribute(name))previous.setAttribute(name,cell.getAttribute(name));
            previous.append(...cell.childNodes);cell.append(previous);
        }
        cell.append(document.importNode(new DOMParser().parseFromString(xml,'application/xml').documentElement,true));
        await DesignerStudioBridge.apply(new XMLSerializer().serializeToString(document),[id]);await DesignerStudioBridge.flush();
    }else await edit({operation:placement&&!placement.container?'insert-beside':'insert', xml,...placement,...(data === undefined ? {} : {data})},true,context,layout);
    if(tag==='grid'){await focusGridContent(id+'-item-1');status('已插入独立图文栅格，可以直接编写第一格。');return;}
    const inserted = entries.find(entry => entry.id === (tag==='list'?id+'-item':id));
    const entry = tag==='list'&&inserted?entries.find(entry=>entry.path===inserted.path.slice(0,inserted.path.lastIndexOf('/'))):inserted;
    if(['if','each'].includes(tag)&&inserted){const ownerPath=inserted.path.slice(0,inserted.path.lastIndexOf('/'));const logicPath=tag==='if'?ownerPath.slice(0,ownerPath.lastIndexOf('/')):ownerPath;const logic=entries.find(entry=>entry.path===logicPath);if(logic){await choose(logic);DesignerWorkspace.openInspector();document.querySelector('[data-inspect="properties"]').click();}return;}
    if (entry) { await choose(entry); DesignerCanvas.reveal(entry); }
    if((['table','rows','list','columns'].includes(tag)||placement?.container&&['p','heading'].includes(tag))&&entry)await editContainerContent(entry.path);
}
async function addGridRow(spans){
    await drafts();
    const context=DesignerStudio.gridContext(state.source,selected.path);if(!context)throw new Error('请选择栅格或其中的栅格项。');
    const grid=nodeAt(context.gridPath),rows=[...grid.querySelectorAll('item')].map(item=>Number(item.getAttribute('row')));
    const layout=spans?gridLayouts.find(layout=>layout.spans.join(',')===spans.join(','))?.id:'full';
    if(!layout)throw new Error('未知的栅格行预设。');
    await applyGridOperation({type:'add-row',layout,afterRow:Math.max(0,...rows)});
}
async function insertFullWidth(kind,placement={}) {
    if(!['table','image'].includes(kind))throw new Error('请选择通栏表格或图片。');
    const requested=DesignerStudioBridge.snapshot();
    if(placement.source!==undefined&&(placement.source!==requested.source||placement.session!==requested.session))throw new Error('编辑内容已变化，请重新选择通栏插入位置。');
    await drafts();
    const source=DesignerStudioBridge.snapshot().source,path=placement.path??selected?.path;
    if(typeof path!=='string')throw new Error('请先选择正文分栏中的段落。');
    if(placement.source!==undefined&&source!==placement.source)throw new Error('编辑内容已变化，请重新选择通栏插入位置。');
    const boundary=DesignerStudio.prepareColumnInsertion(source,path,placement.caret),context=DesignerStudio.columnInsertionTarget(source,path);
    const id=nextId();let xml;
    if(kind==='table')xml=DesignerStudio.tableMarkup(id);
    else {
        const name=await DesignerAssets.pick();if(name===null)return;
        if(!/^[A-Za-z_][A-Za-z_0-9-]*$/.test(name))throw new Error('图片名称必须是静态标识符。');
        const image=state.images.find(image=>image.name===name),hit=pages.flatMap(page=>page.hits).find(hit=>hit.path===context.columnsPath);
        const ratio=(image?.height||1)/(image?.width||2),width=+Math.min(320,hit?.width||320,180/ratio).toFixed(2),height=+(width*ratio).toFixed(2);
        xml=`<p id="${id}" line-height="${height+6}" align="CENTER"><image ref="${name}" width="${width}" height="${height}" alt="通栏图片"/></p>`;
    }
    const current=DesignerStudioBridge.snapshot();if(current.source!==source||current.session!==requested.session)throw new Error('编辑内容已变化，请重新选择通栏插入位置。');
    const result=DesignerStudio.insertColumnFullWidth(boundary.source,boundary.path,xml,boundary.after);
    await DesignerStudioBridge.apply(result.source,result.ids,result.paths);await DesignerStudioBridge.flush();await preview();
    const inserted=entries.find(entry=>entry.id===id);if(inserted){await choose(inserted);DesignerCanvas.reveal(inserted);}
    status('已插入通栏'+(kind==='table'?'表格':'图片')+'，后续正文继续分栏；支持撤销。');
}
document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () =>
    DesignerWorkspace.openView(button.dataset.view,{toggle:button.dataset.tool==='true'})));
document.querySelectorAll('[data-inspect]').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('[data-inspect]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    document.querySelectorAll('.inspect-view').forEach(view => view.classList.toggle('active', view.id === button.dataset.inspect));
}));
document.querySelectorAll('[data-insert]').forEach(button => button.addEventListener('click', () => {const context=DesignerStudio.canvasContext();return run(() => insert(button.dataset.insert,undefined,undefined,context));}));
document.addEventListener('designer-insert-grid',event=>{const {context=DesignerStudio.canvasContext(),...placement}=event.detail||{};void run(()=>insert('grid',placement,undefined,context));});
document.addEventListener('designer-insert-bound',event=>{const {kind,context=DesignerStudio.canvasContext(),...placement}=event.detail||{};if(['list','rows'].includes(kind))void run(()=>insert(kind,placement,undefined,context));});
document.addEventListener('designer-insert-full-width',event=>{const {kind,...placement}=event.detail||{};if(['table','image'].includes(kind))void run(()=>insertFullWidth(kind,placement));});
document.addEventListener('designer-add-grid-row',event=>{void run(()=>addVisualGridRow(event.detail.path));});
document.addEventListener('designer-layout-grid-row',event=>{void run(async()=>{const choice=await pickGridLayout();if(choice)await applyGridOperation({type:'layout-row',layout:choice.layout},event.detail.path);});});
function on(id, work) { $(id).addEventListener('click', () => run(work)); }
on('refresh', preview); on('previous', () => page(currentPage - 1)); on('next', () => page(currentPage + 1)); $('zoom').addEventListener('change', zoom);
on('up', () => selectedPaths.length>1 ? moveSelection(-1) : edit({operation:'move', direction:-1})); on('down', () => selectedPaths.length>1 ? moveSelection(1) : edit({operation:'move', direction:1}));
on('remove', async () => { if (await ask(`删除所选 ${selectedPaths.length} 个元素？此操作可撤销。`)) await edit(selectedPaths.length>1 ? {operation:'batch-remove',paths:selectedPaths} : {operation:'remove'}); });
async function moveSelection(direction){
    const paths=selectedPaths.slice(), parent=paths[0].slice(0,paths[0].lastIndexOf('/'));
    if(paths.some(path=>path.slice(0,path.lastIndexOf('/'))!==parent))throw new Error('多选移动仅支持同一容器内的同级元素。');
    const indexes=paths.map(path=>Number(path.split('/').at(-1))), edge=direction<0?Math.min(...indexes):Math.max(...indexes),target=parent+'/'+(edge+direction);
    if(indexes.includes(edge+direction)||!entries.some(entry=>entry.path===target))throw new Error('所选元素已到容器边界。');
    await edit({operation:'batch-relocate',paths,target,after:direction>0});
}
for(const direction of ['undo','redo'])$(direction).addEventListener('click',()=>DesignerStudioBridge.history(direction).catch(error=>{status(error.message,true);diagnostics({error:error.message});}));
async function newDocument(kind, sample) {
    if (!(await discard())) return;
    clearTimeout(previewTimer);
    const next = await request('new', {kind, ...(sample === undefined ? {} : {sample})});
    selected = undefined; selectedPaths = []; install(next,true,{session:true});
    filename = kind === 'blank' ? '未命名模板' : state.samples.find(item => item.index === sample).name;
    savedData = '';
    markDirty(); $('sample-dialog').close();
    document.querySelector('.file-menu').open = false;
    await preview();
}
on('new-blank', () => newDocument('blank'));
on('new-sample', async () => {
    const list = $('sample-list'); list.replaceChildren();
    state.samples.forEach(sample => {
        const button = document.createElement('button'); button.type = 'button';
        const title = document.createElement('strong'), description = document.createElement('span');
        title.textContent = sample.name; description.textContent = sample.description;
        button.append(title, description); button.addEventListener('click', () => run(() => newDocument('sample', sample.index)));
        list.append(button);
    });
    document.querySelector('.file-menu').open = false;
    $('sample-dialog').showModal();
});
for (const id of ['close-sample','cancel-sample']) $(id).addEventListener('click', () => $('sample-dialog').close());
function templateExportOptions() {
    const zip=$('template-format').value==='ZIP';
    $('template-format-help').textContent=zip?'包含原始 XML、字体、图片、可复用片段及许可，可直接在设计器中重新打开。':'只包含 XML 源码，不包含字体、图片、片段及许可。复用时须另行提供引用资源；完整迁移请选择 ZIP。';
    $('download-template').textContent=zip?'下载模板 ZIP':'下载模板 XML';
    $('template-export-summary').textContent='';$('template-export-summary').classList.remove('error');
}
on('save', () => { templateExportOptions(); $('template-export-dialog').showModal(); });
$('template-format').addEventListener('change',templateExportOptions);
for(const id of ['close-template-export','cancel-template-export'])$(id).addEventListener('click',()=>$('template-export-dialog').close());
on('download-template', async () => {
    templateExportOptions();$('download-template').textContent='正在导出…';
    try {
        await drafts();
        const zip=$('template-format').value==='ZIP';
        const blob=zip?await request('save',{},true):new Blob([state.source],{type:'application/xml;charset=utf-8'});
        download(blob,zip?'template.zip':'template.xml');
        if(zip)bundleDirty=false;
        markDirty();
        const message=zip?'模板 ZIP 已交给浏览器下载，包含源码和全部资源。':'模板 XML 已交给浏览器下载；引用资源须另行提供，建议同时保留完整 ZIP。';
        $('template-export-summary').textContent=message;status(message);
    } finally { $('download-template').textContent=$('template-format').value==='ZIP'?'下载模板 ZIP':'下载模板 XML'; }
});
on('save-data', async () => { await drafts(); download(new Blob([state.data],{type:'application/json'}),'data.json'); savedData = state.data; markDirty(); status('测试数据 JSON 已交给浏览器下载。'); });
on('save-report', async () => download(new Blob([JSON.stringify(lastReport,null,2)],{type:'application/json'}),'export4j-report.json'));
on('open', async () => { if (await discard()) { $('open-file').value = ''; $('open-file').click(); } });
$('open-file').addEventListener('change', () => run(async () => {
    const file = $('open-file').files[0]; if (!file) return;
    const type = file.name.toLowerCase().endsWith('.xml') ? 'xml' : 'zip';
    install(await request('open', {type,bytes:await bytes(file)}), false,{session:true}); bundleDirty = false; filename = file.name; savedData = '{}'; markDirty();
    status('模板已打开；动态模板请加载对应 JSON 数据。');
    await preview();
}));
on('load-data', async () => { $('data-file').value = ''; $('data-file').click(); });
$('data-file').addEventListener('change', () => run(async () => { const file = $('data-file').files[0]; if (file) { $('data').value = await textFile(file); await drafts(); savedData = state.data; markDirty(); await preview(); } }));
on('add-attribute', async () => { attributeRow(); propertyDirty = true; nodeDirtyLabel(); });
async function applyProperties() {
    if(!propertyDirty)return;
    const source=draftSource(),ids=selectedPaths.map(path=>entries.find(entry=>entry.path===path)?.id).filter(Boolean);
    await DesignerStudioBridge.apply(source,ids,selectedPaths);
    await DesignerStudioBridge.flush();
}
on('apply-properties', applyProperties);
on('apply-node', applyProperties);
for (const id of ['node-text','node-xml']) $(id).addEventListener('input', () => { propertyDirty = true;if(id==='node-xml')nodeXmlDirty=true;else nodeTextDirty=true;nodeDirtyLabel();schedulePreview(); });
for (const id of ['source','data']) $(id).addEventListener('input', () => { markDirty();schedulePreview(); });
function clearExportReport() {
    exportReport=undefined;
    $('export-summary').textContent='点击“下载文件”自动检查并导出。';
    $('export-report').replaceChildren();$('export-report').hidden=true;
    refreshButtons();
}
function showExportReport(report) {
    exportReport=report;diagnostics(report);
    $('export-report').hidden=false;DesignerWorkspace.report(report,$('export-report'));
}
on('export', async () => { await drafts(); clearExportReport(); $('export-dialog').showModal(); });
$('close-export').addEventListener('click', () => $('export-dialog').close());
for (const id of ['policy-action','policy-treatment','format']) $(id).addEventListener('change', clearExportReport);
on('download-loss-report', () => download(new Blob([JSON.stringify(exportReport,null,2)],{type:'application/json'}),'export4j-export-report.json'));
on('download-export', async () => {
    clearExportReport();await drafts();clearTimeout(previewTimer);
    const format=$('format').value;
    $('download-export').textContent='正在检查…';$('export-summary').textContent='正在检查所选格式并生成文件…';
    try {
        const result=await request('export/prepare',{format,action:$('policy-action').value,treatment:$('policy-treatment').value});
        const report={...result.report,format};showExportReport(report);
        $('download-export').textContent='正在下载…';
        const blob=await request('export/download',{id:result.id},true);
        download(blob,'document.'+extensions[format]);showExportReport({...report,downloaded:true});
        const message=report.warnings?`文件已交给浏览器下载，有 ${report.warnings} 项警告，请查看下方报告。`:'文件已交给浏览器下载，检查报告已保留。';
        $('export-summary').textContent=message;status(message);
    } finally { $('download-export').textContent='下载文件'; }
});
$('cancel').addEventListener('click', () => request('cancel',{}).then(() => {if(busy)status('已请求取消，等待当前检查点…');}).catch(error => status(error.message,true)));
on('add-image', async () => { await drafts(); assetKind = 'image'; $('asset-file').accept = '.png,.jpg,.jpeg'; $('asset-file').value = ''; $('asset-file').click(); });
on('add-fragment', async () => { await drafts(); assetKind = 'fragment'; $('asset-file').accept = '.xml'; $('asset-file').value = ''; $('asset-file').click(); });
on('add-font', async () => { await drafts(); assetKind = 'font'; $('asset-file').accept = '.ttf'; $('asset-file').value = ''; $('asset-file').click(); });
$('asset-file').addEventListener('change', () => run(async () => {
    const file = $('asset-file').files[0]; if (!file) return;
    if (assetKind === 'font') { pendingFont = file; $('license-file').value = ''; $('license-file').click(); return; }
    const name = await ask(assetKind === 'image' ? '图片引用名称' : '片段引用名称', file.name.replace(/\.[^.]+$/,'')); if (name === null) return;
    install(await request('resource',{kind:assetKind,name,bytes:await bytes(file)})); await preview();
}));
$('license-file').addEventListener('change', () => run(async () => {
    const license = $('license-file').files[0]; if (!license || !pendingFont) return;
    install(await request('resource',{kind:'font',name:$('font-role').value,bytes:await bytes(pendingFont),license:await textFile(license)})); pendingFont = undefined; await preview();
}));
window.addEventListener('beforeunload', event => { if (dirty()) { event.preventDefault(); event.returnValue = ''; } });
Editor.start();
DesignerCanvas.start();
DesignerActions.start();
DesignerWorkspace.start();
DesignerStudio.start(DesignerStudioBridge);
run(async () => { install(await request('state'),false); await preview(); });
