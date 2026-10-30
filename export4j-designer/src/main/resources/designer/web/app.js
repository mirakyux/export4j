// SPDX-License-Identifier: Apache-2.0
'use strict';
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="designer-token"]').content;
let state, selected, selectedPaths = [], entries = [], pages = [], currentPage = 0, busy = false, propertyDirty = false;
let bundleDirty = true, savedData = '', filename = '未命名模板', exportReport, lastReport = {};
let assetKind, pendingFont, dragging, nodeXmlDirty=false;
let previewId = '', previewGeneration = 0, previewTimer, previewQueue = Promise.resolve();
const changedAttributes = new Set();
const extensions = {PDF:'pdf', DOCX:'docx', HTML:'html', MARKDOWN:'md', SVG:'svg.zip', PNG:'png.zip'};

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
    document.querySelectorAll('button,select,textarea,input:not([type=file])').forEach(control => control.disabled = control.id !== 'cancel');
    try { await work(); }
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
        document.querySelectorAll('button,select,textarea,input').forEach(control => control.disabled = false);
        $('cancel').disabled = true; refreshButtons();
    }
}
function refreshButtons() {
    $('undo').disabled = busy || !state?.canUndo; $('redo').disabled = busy || !state?.canRedo;
    $('download-export').disabled = busy;
    $('download-loss-report').disabled = busy || !exportReport;
    $('previous').disabled = busy || currentPage <= 0;
    $('next').disabled = busy || currentPage >= pages.length - 1;
    if ($('apply-rich')) $('apply-rich').disabled = busy || !selected || !['p','heading','bullet','numbered','cell','link'].includes(selected.tag);
    document.querySelectorAll('#properties input,#properties select,#properties textarea,#properties button').forEach(control=>control.disabled=busy);
    document.querySelectorAll('#node-source textarea,#node-source button').forEach(control=>control.disabled=busy||Editor.richDirty);
    for (const id of ['source','data']) $(id).disabled=busy||propertyDirty;
    document.querySelectorAll('[data-command]').forEach(button=>button.disabled=$(button.dataset.command).disabled);
    BodyEditor.lock(busy||nodeXmlDirty);
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
function install(value, changed = true) {
    const oldId = selected?.id, oldPath = selected?.path, oldSelections = selectedPaths.map(path => entries.find(entry => entry.path === path)).filter(Boolean);
    state = value; $('source').value = value.source; $('data').value = value.data;
    propertyDirty = false; nodeXmlDirty=false; changedAttributes.clear(); entries = []; flatten(value.outline);
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
    if (busy) document.querySelectorAll('button,select,textarea,input:not([type=file])').forEach(control=>control.disabled=control.id!=='cancel');
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
    try { if (propertyDirty || sourceChanged() || dataChanged()) await drafts(); }
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
    propertyDirty = false; nodeXmlDirty=false; inspect();
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
    const input = document.createElement('input'); input.value = value; input.placeholder = mixed ? '多个值' : '属性值'; input.setAttribute('aria-label', name ? name + ' 属性值' : '属性值');
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
        const changed = sourceChanged();
        install(await request('apply', {source:$('source').value, data:$('data').value}), changed);
        schedulePreview();
    }
}
async function edit(operation, render = true) {
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
async function preview(generation=++previewGeneration) {
    clearTimeout(previewTimer);
    const payload={source:Editor.pageDraftSource(draftSource()),data:$('data').value};
    status('正在排版文档…');
    const result = await request('preview', payload);
    if(generation!==previewGeneration)return;
    pages = result.pages; previewId=result.previewId;
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
    document.querySelectorAll('.attribute-row').forEach(row=>{const fields=row.querySelectorAll('input'),name=fields[0].value.trim();if(name){if(Object.hasOwn(values,name))throw new Error('属性名不能重复。');values[name]=fields[1].value;}});
    return values;
}
function draftSource(){
    if(!propertyDirty)return $('source').value;
    if(sourceChanged())throw new Error('请先完成XML 编辑。');
    const document=new DOMParser().parseFromString(state.source,'application/xml');
    if(document.querySelector('parsererror'))throw new Error('XML 无法解析。');
    if(nodeXmlDirty){const replacement=new DOMParser().parseFromString($('node-xml').value,'application/xml');if(replacement.querySelector('parsererror'))throw new Error('节点 XML 无法解析。');nodeForPath(document,selected.path).replaceWith(document.importNode(replacement.documentElement,true));}
    else if(selectedPaths.length>1){const values=attributeValues();selectedPaths.forEach(path=>{const node=nodeForPath(document,path);changedAttributes.forEach(name=>{if(name==='id')throw new Error('ID 只能逐个修改。');if(values[name])node.setAttribute(name,values[name]);else node.removeAttribute(name);});});}
    else if(Editor.richDirty){const replacement=new DOMParser().parseFromString(Editor.bodyXml(attributeValues()),'application/xml');nodeForPath(document,selected.path).replaceWith(document.importNode(replacement.documentElement,true));}
    else{const node=nodeForPath(document,selected.path),values=attributeValues();[...node.attributes].forEach(attribute=>node.removeAttribute(attribute.name));Object.entries(values).forEach(([name,value])=>node.setAttribute(name,value));if(!$('node-text').readOnly)node.textContent=$('node-text').value;}
    return new XMLSerializer().serializeToString(document);
}
function nodeForPath(document,path){let node=document.documentElement;if(path)path.slice(1).split('/').forEach(index=>node=node.children[Number(index)]);return node;}
async function page(index) {
    DesignerCanvas.scrollTo(index);
}
function highlight() { DesignerCanvas.highlight(); }
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
    const data = JSON.parse(state.data), identifier = /^[A-Za-z_][A-Za-z_0-9]*$/;
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('测试数据必须是 JSON 对象。');
    const arrays = Object.entries(data).filter(([name,value]) => identifier.test(name) && Array.isArray(value));
    const selected = arrays.find(([name]) => name === 'items') ?? arrays[0];
    if (!selected) {
        let name = 'items', suffix = 1;
        while (Object.hasOwn(data,name)) name = 'items' + ++suffix;
        data[name] = [{name:'示例项'}];
        return {path:name, expression:'item.name', data:JSON.stringify(data,null,2)};
    }
    const [path,items] = selected, sample = items.find(item => item !== null);
    const field = sample && typeof sample === 'object' && !Array.isArray(sample)
        ? (Object.keys(sample).find(key => key === 'name') ?? Object.keys(sample).find(key => identifier.test(key))) : undefined;
    return {path, expression:field ? 'item.' + field : 'item'};
}
async function insert(tag, placement, imageName) {
    await drafts();
    if (tag === 'section') {
        const path = placement?.path ?? selected?.path;
        const id = nextId();
        await edit({operation:'start-section',path,id,initialId:'section-'+id});
        const section = entries.find(entry=>entry.tag==='section'&&entry.id===id);
        if(section){await choose(section);DesignerCanvas.reveal(section);}
        return;
    }
    const id = nextId(); let xml, data;
    switch (tag) {
        case 'heading': xml = `<heading id="${id}" level="2" size="18" line-height="27">新标题</heading>`; break;
        case 'bullet': xml = `<bullet id="${id}">列表项</bullet>`; break;
        case 'list': {
            const binding = listBinding(); data = binding.data;
            xml = `<list items="${binding.path}" as="item"><bullet id="${id}">${'${'+binding.expression+'}'}</bullet></list>`;
            break;
        }
        case 'numbered': xml = `<numbered id="${id}" list-id="list-${id}">编号项</numbered>`; break;
        case 'table': xml = `<table id="${id}"><columns><column weight="1"/><column weight="1"/></columns><header><cell>列一</cell><cell>列二</cell></header><row><cell>内容一</cell><cell>内容二</cell></row></table>`; break;
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
    await edit({operation:placement&&!placement.container?'insert-beside':'insert', xml,...placement,...(data === undefined ? {} : {data})}); const entry = entries.find(entry => entry.id === id); if (entry) { await choose(entry); DesignerCanvas.reveal(entry); }
}
document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () =>
    DesignerWorkspace.openView(button.dataset.view,{toggle:button.dataset.tool==='true'})));
document.querySelectorAll('[data-inspect]').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('[data-inspect]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    document.querySelectorAll('.inspect-view').forEach(view => view.classList.toggle('active', view.id === button.dataset.inspect));
}));
document.querySelectorAll('[data-insert]').forEach(button => button.addEventListener('click', () => run(() => insert(button.dataset.insert))));
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
on('undo', async () => { if (propertyDirty || sourceChanged() || dataChanged()) { if (!(await ask('放弃未应用修改并撤销？'))) return; } install(await request('undo', {})); await preview(); });
on('redo', async () => { if (propertyDirty || sourceChanged() || dataChanged()) { if (!(await ask('放弃未应用修改并重做？'))) return; } install(await request('redo', {})); await preview(); });
async function newDocument(kind, sample) {
    if (!(await discard())) return;
    clearTimeout(previewTimer);
    const next = await request('new', {kind, ...(sample === undefined ? {} : {sample})});
    selected = undefined; selectedPaths = []; install(next);
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
    install(await request('open', {type,bytes:await bytes(file)}), false); bundleDirty = false; filename = file.name; savedData = '{}'; markDirty();
    status('模板已打开；动态模板请加载对应 JSON 数据。');
}));
on('load-data', async () => { $('data-file').value = ''; $('data-file').click(); });
$('data-file').addEventListener('change', () => run(async () => { const file = $('data-file').files[0]; if (file) { $('data').value = await textFile(file); await drafts(); savedData = state.data; markDirty(); await preview(); } }));
on('add-attribute', async () => { attributeRow(); propertyDirty = true; nodeDirtyLabel(); });
async function applyProperties() {
    if(!propertyDirty)return;
    const attributes=attributeValues();
    if(nodeXmlDirty)await edit({operation:'replace',xml:$('node-xml').value},false);
    else if(selectedPaths.length>1){
        const changes={};changedAttributes.forEach(name=>{if(name==='id')throw new Error('ID 只能逐个修改。');changes[name]=attributes[name]??'';});
        await edit({operation:'batch-properties',paths:selectedPaths,attributes:changes},false);
    }
    else if(Editor.richDirty)await edit({operation:'replace',xml:Editor.bodyXml(attributes)},false);
    else await edit({operation:'properties',attributes,plain:!$('node-text').readOnly,text:$('node-text').value},false);
}
on('apply-properties', applyProperties);
on('apply-node', applyProperties);
for (const id of ['node-text','node-xml']) $(id).addEventListener('input', () => { propertyDirty = true;if(id==='node-xml')nodeXmlDirty=true;nodeDirtyLabel();schedulePreview(); });
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
$('cancel').addEventListener('click', () => request('cancel',{}).then(() => status('已请求取消，等待当前检查点…')).catch(error => status(error.message,true)));
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
run(async () => { install(await request('state'),false); await preview(); });
