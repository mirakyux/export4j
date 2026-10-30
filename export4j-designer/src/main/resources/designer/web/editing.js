// SPDX-License-Identifier: Apache-2.0
'use strict';
const Editor = (() => {
    const codeEditors = new Map();
    const serialize = node => new XMLSerializer().serializeToString(node);
    let richNode, richDirty = false, pageDocument, pageBaseline, pageScope, pageVariant='default', fieldId=0;
    const xml = source => {
        if (source.length > 1_000_000 || /<!DOCTYPE/i.test(source)) throw new Error('XML 超过预算或包含禁止的 DOCTYPE。');
        const parsed = new DOMParser().parseFromString(source, 'application/xml');
        if (parsed.querySelector('parsererror')) throw new Error('XML 格式错误，请修正后应用。');
        return parsed;
    };
    const el = (tag, text, className) => {
        const node = document.createElement(tag); if (text !== undefined) node.textContent = text;
        if (className) node.className = className; return node;
    };
    function button(text, action) { const result = el('button',text); result.type = 'button'; result.addEventListener('click',action); return result; }
    function view(name) { DesignerWorkspace.openView(name); }
    const escape = text => text.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    function highlighted(text, json) {
        const pattern = json ? /"(?:\\.|[^"\\])*"\s*(?=:)|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g
            : /<!--[\s\S]*?(?:-->|$)|<!\[CDATA\[[\s\S]*?(?:\]\]>|$)|<\?[\s\S]*?(?:\?>|$)|<\/?[A-Za-z_][\w:.-]*|\/?>|(?<!\S)[A-Za-z_][\w:.-]*(?=\s*=)|"[^"<]*(?:"|(?=<)|$)|'[^'<]*(?:'|(?=<)|$)|\$\{[^}]*\}?/g;
        let end = 0, result = '';
        for (const match of text.matchAll(pattern)) {
            result += escape(text.slice(end,match.index)); const value = match[0];
            const type = json ? value.endsWith('"') && /^"/.test(value) && /^\s*:/.test(text.slice(match.index+value.length)) ? 'key' : /^"/.test(value) ? 'string' : 'value'
                : value.startsWith('<!--') || value.startsWith('<?') || value.startsWith('<!') ? 'comment'
                : value.startsWith('<') || value === '>' || value === '/>' ? 'tag' : /^["']/.test(value) ? 'string' : value.startsWith('${') ? 'variable' : 'key';
            result += `<span class="syntax-${type}">${escape(value)}</span>`; end = match.index + value.length;
        }
        return result + escape(text.slice(end)) + '\n';
    }
    function codeEditor(area, json = false) {
        const wrapper = el('div',undefined,'code-editor'), paint = el('pre'), gutter = el('pre',undefined,'line-numbers');
        paint.setAttribute('aria-hidden','true'); gutter.setAttribute('aria-hidden','true');
        area.parentNode.insertBefore(wrapper,area); wrapper.append(gutter,paint,area); area.wrap = 'off';
        const update = () => {
            paint.innerHTML = highlighted(area.value,json);
            gutter.textContent = Array.from({length:area.value.split('\n').length},(_,n)=>n+1).join('\n');
            sync();
        };
        const caret = () => {
            const line=area.value.slice(0,area.selectionEnd).split('\n').length;
            wrapper.style.setProperty('--caret-top',(14+(line-1)*22-area.scrollTop)+'px');
            if(document.activeElement===area)DesignerWorkspace.editorStatus(area);
        };
        const sync = () => { paint.scrollTop = area.scrollTop; paint.scrollLeft = area.scrollLeft; gutter.scrollTop = area.scrollTop; caret(); };
        area.addEventListener('scroll',sync); area.addEventListener('input',update);
        for(const event of ['focus','keyup','click','select'])area.addEventListener(event,caret);
        area.addEventListener('keydown',event => {
            if (event.key === 'Tab') {
                event.preventDefault(); area.setRangeText('  ',area.selectionStart,area.selectionEnd,'end'); area.dispatchEvent(new Event('input',{bubbles:true}));
            } else if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'f') {
                event.preventDefault(); run(()=>format(area.id,json));
            }
        });
        codeEditors.set(area.id,update); update();
    }
    async function format(id, json = id === 'data') {
        if (propertyDirty && ['source','data'].includes(id)) throw new Error('请先应用当前节点的属性、XML 或富文本修改。');
        const area = $(id); const result = await request('format',{kind:json?'json':'xml',source:area.value});
        area.value = result.formatted; area.dispatchEvent(new Event('input',{bubbles:true}));
        status('格式化完成；应用后写入工作区，可撤销。');
    }

    // Fields map only to attributes accepted by the XML compiler. Blank means inherit/remove.
    const common = [
        ['size','字号','number'], ['line-height','行高','number'], ['align','对齐',['LEFT','CENTER','RIGHT','JUSTIFY']],
        ['role','字体角色',['','bold']], ['color','文字颜色','text'], ['line-break','断行',['STANDARD','CJK_STRICT']],
        ['space-before','段前外间距','number'], ['space-after','段后外间距','number'],
        ['first-indent','首行缩进','number'], ['subsequent-indent','后续行缩进','number'],
        ['keep-together','段落不拆分',['true','false']], ['keep-with-next','与后段保持',['true','false']],
        ['page-break-before','段前换页',['true','false']], ['min-lines-before','页末最少行','number'], ['min-lines-after','页首最少行','number'],
        ['rotation','旋转角度（°）','number']
    ];
    const inline = [['role','字体角色',['','bold']],['color','颜色','text'],['size','字号','number'],
        ['underline','下划线',['true','false']],['strike','删除线',['true','false']],['baseline-shift','基线偏移','number']];
    const schema = {
        document:[['title','标题','text'],['author','作者','text'],['literal-text','文字空白',['true','false']]],
        page:[['size','纸张',['A4','LETTER']],['width','宽度（可带单位）','text'],['height','高度（可带单位）','text'],['margin','统一页边距','text'],
            ['orientation','方向',['PORTRAIT','LANDSCAPE']],...['top','right','bottom','left'].map((side,n)=>['margin-'+side,['上','右','下','左'][n]+'页边距','text'])],
        section:[['id','标识符','text'],['first-page-number','起始页码','number']],
        p:[['style','命名样式','text'],...common], heading:[['level','标题级别','number'],...common],
        bullet:[['level','列表级别','number'],...common], numbered:[['list-id','列表标识','text'],['level','编号级别','number'],['restart','重新编号起始值','number'],...common],
        caption:[['kind','题注类型','text'],...common],
        'cross-reference':[['kind','引用类型',['PAGE','NUMBER']],['target','目标 ID','text'],...common.filter(f=>!['role','color'].includes(f[0]))],
        span:[...inline],
        cell:[...common.filter(f=>['size','line-height','align','role','color','line-break'].includes(f[0])),['column-span','跨列数','number'],['row-span','跨行数','number']],
        list:[['items','数据路径','text'],['as','迭代变量','text']],
        table:[['row-split','跨页拆行',['true','false']],['draw-borders','绘制边框',['true','false']],
            ...['top','right','bottom','left'].map((side,n)=>['padding-'+side,['上','右','下','左'][n]+'内边距','number']),['space-before','表前外间距','number'],['space-after','表后外间距','number'],['rotation','旋转角度（°）','number']],
        column:[['weight','比例列宽','number'],['width','固定列宽','number'],['min-width','自动最小宽','number'],['max-width','自动最大宽','number']],
        image:[['ref','图片资源','text'],['width','宽度','number'],['height','高度','number'],['alt','替代文字','text'],['baseline-shift','基线偏移','number'],['rotation','旋转角度（°）','number']],
        link:[['href','链接地址','text']],
        box:[['x','横坐标','text'],['y','纵坐标','text'],['width','固定宽度','text'],['height','固定高度','text'],['source-id','共享来源名','text'],['source-ref','共享来源引用','text']],
        header:[['height','区域高度','text'],['gap','与正文间距','text'],['edge','距纸张顶边','text'],
            ['left','距纸张左边','text'],['right','距纸张右边','text'],['variant','适用页',['default','first','even']],
            ['source-id','共享来源名','text'],['source-ref','共享来源引用','text']]
    };
    schema.footer = schema.header.map(item=>item[0]==='edge'?[item[0],'距纸张底边',item[2]]:item);
    schema.rows = [...schema.table,['items','数据路径','text'],['as','迭代变量','text']];
    for (const tag of ['page-number','page-count','page-reference','number-reference','note-marker']) {
        schema[tag] = [['width','预留宽度','number'],...(['page-number','page-count'].includes(tag)?[]:[['target','目标 ID','text']]),...inline];
    }
    const optionLabels={true:'启用',false:'关闭',LEFT:'左对齐',CENTER:'居中',RIGHT:'右对齐',JUSTIFY:'两端对齐',
        STANDARD:'标准断行',CJK_STRICT:'中文严格禁则',PORTRAIT:'纵向',LANDSCAPE:'横向',CUSTOM:'自定义尺寸',
        default:'默认页',first:'首页',even:'偶数页',bold:'粗体角色',PAGE:'目标页码',NUMBER:'目标编号',
        TRUE:'为真',FALSE:'为假',EQ:'等于',NE:'不等于',GT:'大于',GE:'大于等于',LT:'小于',LE:'小于等于',CONTAINS:'包含文本'};
    const help={
        size:'字号，单位 pt。固定行高必须能容纳字号及图像。','line-height':'固定基线间距，单位 pt；不会因文字变大而自动增高。',align:'段落或单元格文字的对齐方式。',
        role:'使用资源栏中显式登记的物理字体角色。',color:'颜色格式 #RRGGBB；空值恢复继承。',
        'line-break':'标准断行，或启用中文行首／行尾禁则。','space-before':'本段／本表之前的外间距，单位 pt。','space-after':'本段／本表之后的外间距，单位 pt。',
        'first-indent':'首行水平缩进，单位 pt。','subsequent-indent':'后续行水平缩进，单位 pt。',
        'keep-together':'尽量将整段放在同一页；内容高于整页时明确报错。','keep-with-next':'与后续段落保持，避免标题孤立在页末。','page-break-before':'在此段之前开始新页。',
        'min-lines-before':'分页前至少保留的完整行数。','min-lines-after':'分页后至少保留的完整行数。',
        'row-split':'允许表格内容按受支持的完整行跨页拆分。','draw-borders':'显示当前表格网格边框。',
        width:'显式宽度；图片／字段使用 pt，纸张／固定框可带 mm、cm、in。',height:'显式高度；固定区域须容纳完整内容，不裁切溢出。',
        weight:'按当前可用宽度与其他比例列共同分配，必须为正数。','min-width':'自动列宽的下限，单位 pt。','max-width':'自动列宽的上限，单位 pt。',
        'column-span':'跨越的物理列数，正整数；表格网格仍须完整。','row-span':'跨越的物理行数，正整数。',
        ref:'从已导入的 PNG／JPEG 资源中选择；也可保留原模板动态引用。',alt:'图片的替代文字，说明图片内容。',
        underline:'为此文字片段绘制下划线。',strike:'为此文字片段绘制删除线。','baseline-shift':'基线偏移，单位 pt；固定行高仍须容纳文字。',
        target:'被引用节点的唯一 ID。',href:'显式链接地址或 #内部目标；不自动访问外部内容。',
        x:'相对纸张左边缘的固定横坐标，可带 pt／mm／cm／in。',y:'相对纸张上边缘的固定纵坐标，可带单位。',
        margin:'四边默认页边距；单边设置优先。空值继承，0 表示无边距。',orientation:'横向／纵向在已有宽高基础上决定方向。',
        gap:'页区和正文之间的间距，可带单位。',edge:'页眉距纸张顶边、页脚距纸张底边；留空沿用对应正文页边距。',
        left:'页眉页脚距纸张左边；留空沿用正文左页边距。',right:'页眉页脚距纸张右边；留空沿用正文右页边距。',
        variant:'默认、首页或偶数页的区域选择。',
        'source-id':'定义可供同类页区复用的静态来源名。','source-ref':'复用前面已声明的同类来源，不展开或复制源码。',
        items:'测试数据中数组的路径；列表对每项展开一个列表项。',as:'当前列表项在模板内使用的变量名。',
        level:'标题为 1–6 级；编号／列表级别以现有模板语法为准。',restart:'重新编号的整数起始值；留空继续列表。',
        'first-page-number':'本节的显示起始页码；0 表示接续上一节。',style:'选择模板已声明的命名样式；完整样式替换，不按单项级联。',
        rotation:'顺时针角度，范围 -180° 至 180°。内容会缩放以适配原有排版框；分页和元素位置不改变。',
        'literal-text':'保留文字空白的声明；格式化不会改写混合文字内容。',title:'文档标题，可使用绑定变量。',author:'文档作者元数据。',
        test:'从测试数据 JSON 读取数据的点路径，无需 ${}；动态表格使用其迭代变量，如 record.score。',
        operator:'文本／数值／布尔比较；大于等数值比较只接受数字，为真／为假只接受布尔值。不执行代码。',
        value:'比较值按实际数据类型解释；数字精确比较，文本区分大小写。为真／为假时留空。'
    };
    function fieldHelp(name,label){return name==='size'&&label==='纸张预设'?'选择纸张预设；自定义尺寸由宽度和高度决定，分节留空时继承文档设置。':help[name]||(name.startsWith('padding-')?'单元格内容与表格边框之间的内边距，单位 pt。':name.startsWith('margin-')?'此方向的页边距，覆盖统一边距；可带 pt／mm／cm／in。':`${label}写入现有 XML 属性；留空移除局部覆盖。`);}
    function field(descriptor, value, changed) {
        let [name,label,type] = descriptor; const wrapper = el('label',label,'setting-field');
        if(name==='ref')type=state.images.map(image=>image.name);
        const input = el(Array.isArray(type)?'select':'input');
        if (Array.isArray(type)) {
            const values = [...new Set(['',...type,...(value && !type.includes(value)?[value]:[])])];
            values.forEach(v=>{const option=el('option',v?(optionLabels[v]||v):'继承 / 默认');option.value=v;input.append(option);});
        } else { input.type = type; if (type === 'number') input.step = 'any'; }
        input.autocomplete='off';input.name=name;
        input.value = value; input.dataset.attribute = name; input.setAttribute('aria-label',label);
        if (!Array.isArray(type)) input.placeholder = '继承 / 默认';
        const info=fieldHelp(name,label),hint=el('span','ⓘ','field-help');hint.id='field-help-'+(++fieldId);
        hint.title=info;hint.tabIndex=0;hint.setAttribute('role','note');hint.setAttribute('aria-label',label+'：'+info);
        input.title=info;
        const caption=el('span',label,'field-caption');wrapper.textContent='';caption.append(hint);
        input.addEventListener(Array.isArray(type)?'change':'input',()=>changed(input.value));wrapper.append(caption,input);
        if(type==='number'&&!['first-indent','subsequent-indent','baseline-shift','rotation'].includes(name))input.min='0';
        if(name==='rotation'){input.min='-180';input.max='180';}
        if(['column-span','row-span','level','min-lines-before','min-lines-after','first-page-number'].includes(name))input.step='1';
        if(name==='color'){
            const color=el('input');color.type='color';color.value=/^#[0-9a-f]{6}$/i.test(value)?value:'#000000';color.setAttribute('aria-label',label+'调色板');color.title='选择颜色';color.addEventListener('input',()=>{input.value=color.value;changed(input.value);});wrapper.append(color);
        }
        return wrapper;
    }
    function setAttribute(name,value){let row=[...$('attributes').children].find(row=>row.querySelector('input').value===name);if(!row){attributeRow(name,value);row=$('attributes').lastElementChild;}if(value==='')row.remove();else row.querySelectorAll('input')[1].value=value;changedAttributes.add(name);propertyDirty=true;nodeDirtyLabel();schedulePreview();}
    function inspect(node) {
        richDirty = false; richNode = node.cloneNode(true);
        document.querySelectorAll('[data-image-inspector]').forEach(item=>item.remove());
        // Keep the direct editor outside the property groups while rebuilding controls.
        $('properties').append($('body-section'));
        $('structured-properties').replaceChildren();
        delete $('structured-properties').dataset.grouped;
        const root=new DOMParser().parseFromString(state.source,'application/xml');
        const selectedNodes=selectedPaths.map(path=>nodeForPath(root,path));
        const idField=node.hasAttribute('id')||['section','p','heading','bullet','numbered','table','caption','cross-reference','page-break'].includes(node.tagName) ? [['id','ID','text']] : [];
        const descriptors = selectedPaths.length>1 ? (schema[node.tagName]||[]).filter(descriptor=>descriptor[0]!=='id'&&selectedNodes.every(item=>(schema[item.tagName]||[]).some(field=>field[0]===descriptor[0]))) : [...idField,...(schema[node.tagName]||[]).filter(item=>item[0]!=='id')];
        for (const descriptor of descriptors) {
            const values=selectedNodes.map(item=>item.getAttribute(descriptor[0]));
            const same=values.every(value=>value===values[0]),common=same?values[0]||'':'';
            const control=field(descriptor,common,value=>{
                setAttribute(descriptor[0],value);
            });
            if(!same){
                const input=control.querySelector('input,select');
                if(input.tagName==='SELECT'){const mixed=el('option','多个值');mixed.value='__mixed__';mixed.disabled=true;input.prepend(mixed);input.value=mixed.value;}
                else input.placeholder='多个值';
            }
            $('structured-properties').append(control);
        }
        $('structured-help').title = descriptors.length ? '长度单位 pt；页面和页区也接受 mm / cm / in。留空移除覆盖。' : '此结构的属性使用下方高级属性或节点 XML 编辑。';
        $('node-page-settings').hidden = selectedPaths.length>1 || !['document','section'].includes(node.tagName);
        DesignerTables.inspect(node);
        if(node.tagName==='column')columnMode(node);
        if(node.tagName==='image')imageProperties(node);
        renderRich();if(selectedPaths.length>1){$('body-section').hidden=true;$('node-source').hidden=true;document.querySelector('[data-inspect="properties"]').click();} DesignerActions.usedResources(node);if(selectedPaths.length>1)$('used-resources').hidden=true;syncEditors();
    }
    function columnMode(node){
        const wrapper=field(['column-mode','列宽方式',['FIXED','WEIGHT','AUTO']],node.hasAttribute('width')?'FIXED':node.hasAttribute('weight')?'WEIGHT':'AUTO',value=>{
            const names=value==='FIXED'?['width']:value==='WEIGHT'?['weight']:['min-width','max-width'];
            for(const name of ['width','weight','min-width','max-width']){
                const input=$('structured-properties').querySelector(`[data-attribute="${name}"]`);
                input.closest('.setting-field').hidden=!names.includes(name);
                const next=names.includes(name)?(input.value||(name==='width'?'72':name==='weight'?'1':name==='min-width'?'24':'240')):'';input.value=next;setAttribute(name,next);
            }
        });
        const select=wrapper.querySelector('select');[...select.options].forEach(option=>option.textContent=({FIXED:'固定宽度（pt）',WEIGHT:'按比例分配',AUTO:'有界自动宽度'})[option.value]||'请选择');
        const help=wrapper.querySelector('.field-help');help.title='三种列宽互斥；切换时清除其他方式的属性。';help.setAttribute('aria-label',help.title);$('structured-properties').prepend(wrapper);
        const names=select.value==='FIXED'?['width']:select.value==='WEIGHT'?['weight']:['min-width','max-width'];
        ['width','weight','min-width','max-width'].forEach(name=>$('structured-properties').querySelector(`[data-attribute="${name}"]`).closest('.setting-field').hidden=!names.includes(name));
    }
    function imageProperties(node){
        const host=el('div',undefined,'selected-image-preview'),picture=el('div');host.append(picture);$('structured-properties').before(host);host.dataset.imageInspector='';DesignerAssets.preview(node.getAttribute('ref'),picture);
        const ref=$('structured-properties').querySelector('[data-attribute="ref"]');ref.addEventListener('change',()=>DesignerAssets.preview(ref.value,picture));
        const lock=el('input');lock.type='checkbox';lock.checked=true;lock.setAttribute('aria-label','锁定图片宽高比');const label=el('label','锁定图片宽高比','ratio-lock');label.prepend(lock);$('structured-properties').prepend(label);
        const width=$('structured-properties').querySelector('[data-attribute="width"]'),height=$('structured-properties').querySelector('[data-attribute="height"]');
        let ratio=Number(width.value)/Number(height.value);if(!Number.isFinite(ratio)||ratio<=0)ratio=1;
        for(const [input,other] of [[width,height],[height,width]])input.addEventListener('input',()=>{if(lock.checked&&Number(input.value)>0){other.value=String(+(Number(input.value)*(input===width?1/ratio:ratio)).toFixed(2));setAttribute(other.dataset.attribute,other.value);}});
        lock.addEventListener('change',()=>{const value=Number(width.value)/Number(height.value);if(lock.checked&&Number.isFinite(value)&&value>0)ratio=value;});
        const reset=button('恢复原图比例',()=>{const image=state.images.find(image=>image.name===ref.value);if(image&&Number(width.value)>0){ratio=image.width/image.height;height.value=String(+(Number(width.value)/ratio).toFixed(2));setAttribute('height',height.value);}});host.append(reset);
    }
    function syncEditors() { codeEditors.forEach(update=>update()); }
    function richAllowed() {
        return richNode && ['p','heading','bullet','numbered','cell','link'].includes(richNode.tagName)
            && ![...richNode.children].some(child=>['p','table'].includes(child.tagName));
    }
    function markRich(work,fromBody=false) {
        if (nodeXmlDirty) { status('请先应用节点 XML 修改。',true);return; }
        work(); richDirty=true; propertyDirty=true; prepared=undefined;if(!fromBody)BodyEditor.render(richNode);nodeDirtyLabel();schedulePreview();
    }
    function appendInline(parent, tag) {
        if (tag === 'text') parent.appendChild(parent.ownerDocument.createTextNode('新文字'));
        else {
            const node=parent.ownerDocument.createElement(tag);
            if (tag === 'span') { node.setAttribute('role','bold'); node.textContent='格式文字'; }
            if (tag === 'link') { node.setAttribute('href','https://example.com'); node.textContent='链接文字'; }
            if (tag === 'image') { node.setAttribute('ref',state.images[0]?.name || 'mark'); node.setAttribute('width','12');node.setAttribute('height','12');node.setAttribute('alt','图片'); }
            if (['page-number','page-count','page-reference','number-reference'].includes(tag)) { node.setAttribute('width','36'); if(tag.endsWith('reference'))node.setAttribute('target','target-id'); }
            parent.appendChild(node);
        }
    }
    function inlineToolbar(parent) {
        const toolbar=el('div',undefined,'rich-toolbar');
        const choices=parent.tagName==='span'?[]:parent.tagName==='link'?[['text','文字'],['span','格式文字'],['image','图片']]
            :[['text','文字'],['span','格式文字'],['link','链接'],['image','图片'],['page-number','页码'],['page-count','总页数'],['page-reference','目标页码'],['number-reference','目标编号']];
        choices.forEach(([tag,label])=>toolbar.append(button('＋ '+label,()=>markRich(()=>{appendInline(parent,tag);renderRich();}))));
        return toolbar;
    }
    function renderRuns(parent, host) {
        [...parent.childNodes].filter(node=>node.nodeType!==1||node.tagName!=='style-if').forEach((node,index)=>{
            const card=el('section',undefined,'run-card'), title=el('div',undefined,'run-title');
            title.append(el('strong',`${index+1} · ${node.nodeType===3?'文字':node.nodeName}`));
            title.append(button('上移',()=>markRich(()=>{if(node.previousSibling)parent.insertBefore(node,node.previousSibling);renderRich();})),
                button('下移',()=>markRich(()=>{if(node.nextSibling)parent.insertBefore(node.nextSibling,node);renderRich();})),
                button('移除',()=>markRich(()=>{node.remove();renderRich();})));
            card.append(title);
            if (node.nodeType===3 || node.nodeType===4 || node.nodeType===1 && node.tagName==='span') {
                const area=el('textarea');area.rows=2;area.value=node.nodeType===1?node.textContent:node.nodeValue;
                area.setAttribute('aria-label',`片段 ${index+1} 文字`);
                area.addEventListener('input',()=>markRich(()=>{if(node.nodeType===1)node.textContent=area.value;else node.nodeValue=area.value;}));card.append(area);
            }
            if (node.nodeType===1) {
                const grid=el('div',undefined,'settings-grid');
                (schema[node.tagName]||[]).forEach(d=>grid.append(field(d,node.getAttribute(d[0])||'',value=>markRich(()=>{if(value)node.setAttribute(d[0],value);else node.removeAttribute(d[0]);}))));
                card.append(grid);
                if(node.tagName==='link'){renderRuns(node,card);card.append(inlineToolbar(node));}
                else if(!['span','image','page-number','page-count','page-reference','number-reference','note-marker'].includes(node.tagName))card.append(el('pre',serialize(node)));
            } else if (![3,4].includes(node.nodeType)) card.append(el('pre',serialize(node)));
            host.append(card);
        });
    }
    function renderRich() {
        $('rich-content').replaceChildren();$('rich-actions').replaceChildren();
        $('rich-selection').textContent=selected?`${selected.tag} ${selected.id || selected.path}`:'未选择';
        $('apply-rich').disabled=!richAllowed();
        BodyEditor.render(richAllowed()?richNode:null);
        $('node-text').hidden=richAllowed();$('text-help').hidden=richAllowed();$('body-section').hidden=!richAllowed()&&richNode.tagName!=='span';
        if (!richAllowed()) {$('rich-content').append(el('p','请从结构树选择段落、标题、列表项或行内单元格；块格请先选择其中的段落。','hint'));return;}
        if (richNode.tagName==='span') {
            $('rich-content').append(el('p','当前是单个格式文字。修改正文和样式请使用属性面板，或选中父段落编辑多个片段。','hint')); return;
        }
        $('rich-actions').append(inlineToolbar(richNode));renderRuns(richNode,$('rich-content'));
    }
    function simpleBand(kind) {
        const input=$('band-'+kind+'-text');
        try {
            const node=xml($('band-'+kind+'-xml').value).documentElement,p=node.children[0];
            const simple=!node.hasAttribute('source-ref')&&node.children.length===1&&p.tagName==='p'
                &&[...p.childNodes].every(n=>n.nodeType===3||n.nodeType===1&&['page-number','page-count'].includes(n.tagName)
                    &&n.getAttribute('width')==='24'&&n.attributes.length===1);
            input.readOnly=!simple;
            input.value=simple?[...p.childNodes].map(n=>n.nodeType===3?n.nodeValue:n.tagName==='page-number'?'{page}':'{pages}').join(''):'';
            input.placeholder=simple?'文字；{page} 页码，{pages} 总页数':'复杂区域请编辑 XML 或应用后从结构树编辑';
        } catch { input.readOnly=true; input.value=''; }
    }
    function updateSimpleBand(kind) {
        const area=$('band-'+kind+'-xml'),node=xml(area.value).documentElement,p=node.children[0],value=$('band-'+kind+'-text').value;
        p.replaceChildren();let end=0;
        for(const match of value.matchAll(/(?<!\$)\{(page|pages)\}/g)){
            p.appendChild(node.ownerDocument.createTextNode(value.slice(end,match.index)));
            const field=node.ownerDocument.createElement(match[1]==='page'?'page-number':'page-count');field.setAttribute('width','24');p.appendChild(field);end=match.index+match[0].length;
        }
        p.appendChild(node.ownerDocument.createTextNode(value.slice(end)));area.value=serialize(node);codeEditors.get(area.id)();
    }
    function loadPageScope() {
        pageScope=$('page-scope').value; const root=scopeRoot(pageDocument,pageScope);
        const page=[...root.children].find(n=>n.tagName==='page');
        for(const name of ['size','width','height','orientation','margin','margin-top','margin-right','margin-bottom','margin-left']){
            $('page-'+name).value=page?.getAttribute(name)||'';
        }
        if (page?.hasAttribute('width')) $('page-size').value='CUSTOM';
        const variant=$('page-variant').value;
        pageVariant=variant;
        for(const kind of ['header','footer']) {
            const region=[...root.children].find(n=>n.tagName===kind&&(n.getAttribute('variant')||'default')===variant);
            $('band-'+kind).checked=!!region;
            $('band-'+kind+'-height').value=region?.getAttribute('height')||'';
            $('band-'+kind+'-gap').value=region?.getAttribute('gap')||'';
            for(const name of ['edge','left','right'])$('band-'+kind+'-'+name).value=region?.getAttribute(name)||'';
            $('band-'+kind+'-xml').value=region?serialize(region):`<${kind} height="24" gap="6"><p size="10" line-height="15">${kind==='header'?'页眉内容':'第 <page-number width="24"/> / <page-count width="24"/> 页'}</p></${kind}>`;
            simpleBand(kind);
        }
        syncEditors();
    }
    function scopeRoot(document,id){
        if(!id)return document.documentElement;
        const section=[...document.documentElement.children].find(node=>node.tagName==='section'&&node.getAttribute('id')===id);
        if(!section)throw new Error('设置范围对应的分节不存在。');
        return section;
    }
    async function openPage() {
        await drafts();pageDocument=xml(state.source);pageBaseline=serialize(pageDocument);$('page-scope').replaceChildren();
        const root=el('option','整篇文档（节默认值）');root.value='';$('page-scope').append(root);
        entries.filter(e=>e.tag==='section').forEach(e=>{const option=el('option',e.id);option.value=e.id;$('page-scope').append(option);});
        const scope=entries.filter(e=>e.tag==='section'&&(selected.path===e.path||selected.path.startsWith(e.path+'/'))).at(-1);
        $('page-scope').value=scope?.id||'';$('page-variant').value='default';loadPageScope();$('page-dialog').showModal();
    }
    function pageDraftSource(source) {
        return $('page-dialog')?.open ? serialize(buildPageDocument()) : source;
    }
    function buildPageDocument() {
        const document=pageDocument.cloneNode(true);
        const root=scopeRoot(document,pageScope);
        let page=[...root.children].find(n=>n.tagName==='page');const candidate=page?.cloneNode(true)||document.createElement('page');
        for(const name of ['size','width','height','orientation','margin','margin-top','margin-right','margin-bottom','margin-left']){
            candidate.removeAttribute(name);const value=$('page-'+name).value.trim();if(value&&!(name==='size'&&value==='CUSTOM'))candidate.setAttribute(name,value);
        }
        if(candidate.attributes.length||page){if(page)root.replaceChild(candidate,page);else root.insertBefore(candidate,root.firstChild);}
        const variant=pageVariant;
        for(const kind of ['header','footer']){
            const previous=[...root.children].find(n=>n.tagName===kind&&(n.getAttribute('variant')||'default')===variant);
            if(!$('band-'+kind).checked){if(previous)previous.remove();continue;}
            const next=xml($('band-'+kind+'-xml').value).documentElement;
            if(next.tagName!==kind)throw new Error(`区域源码根必须是 ${kind}。`);
            const region=document.importNode(next,true);if(variant==='default')region.removeAttribute('variant');else region.setAttribute('variant',variant);
            const height=$('band-'+kind+'-height').value.trim(),gap=$('band-'+kind+'-gap').value.trim();
            if(height)region.setAttribute('height',height);if(gap)region.setAttribute('gap',gap);
            for(const name of ['edge','left','right']){
                const value=$('band-'+kind+'-'+name).value.trim();
                if(value)region.setAttribute(name,value);else region.removeAttribute(name);
            }
            if(previous)root.replaceChild(region,previous);else root.insertBefore(region,[...root.children].find(n=>!['page','header','footer'].includes(n.tagName))||null);
        }
        return document;
    }
    async function applyPage() {
        const source=serialize(buildPageDocument());
        if(source!==pageBaseline)install(await request('apply',{source,data:state.data}));
        $('page-dialog').close();await preview();
    }
    function start() {
        const pageButton=button('页面设置',()=>run(openPage));pageButton.id='page-settings';$('refresh').parentElement.before(pageButton);
        const body=el('section');body.id='body-section';
        const bodyLabel=document.querySelector('label[for="node-text"]');bodyLabel.textContent='正文内容';body.append(bodyLabel,$('node-text'),$('text-help'));
        $('properties').prepend(body);
        const richView=el('details');richView.id='rich-fragments';
        richView.innerHTML='<summary>高级行内片段 <span id="rich-selection"></span></summary><div id="rich-actions"></div><div id="rich-content"></div><button id="apply-rich" hidden>保存正文与属性</button>';
        body.append(richView);
        const advanced=el('details');advanced.id='advanced-attributes';advanced.innerHTML='<summary>高级 XML 属性</summary>';
        const attributes=$('attributes');attributes.before(advanced);advanced.append(attributes,$('add-attribute'));
        const properties=el('div');properties.innerHTML='<div class="inspector-quick-actions"><button id="node-page-settings" type="button" hidden>页面、页眉页脚与边距</button><span class="field-help" id="structured-help" role="note" tabindex="0" aria-label="属性说明" title="悬浮查看属性说明">ⓘ</span></div><div class="settings-grid" id="structured-properties"></div><div id="table-tools" hidden></div>';
        advanced.before(properties);
        $('properties').prepend(properties);
        on('node-page-settings',openPage);
        const dialog=el('dialog');dialog.id='page-dialog';
        dialog.innerHTML=`<div class="dialog-heading"><h2>页面与页眉页脚</h2><button id="close-page" aria-label="关闭页面设置">×</button></div>
            <div class="settings-grid"><label class="setting-field">设置范围<select id="page-scope" aria-label="设置范围"></select></label><label class="setting-field">页区变体<select id="page-variant" aria-label="页区变体"><option value="default">默认（其余页）</option><option value="first">首页</option><option value="even">偶数页</option></select></label></div>
            <span class="field-help dialog-help" role="note" tabindex="0" aria-label="页面设置说明：留空继承默认值；四边页边距与页区高度共同预留正文空间。切换范围时保留当前草稿，完成后写入历史。" title="留空继承默认值；四边页边距与页区高度共同预留正文空间。切换范围时保留当前草稿，完成后写入历史。">ⓘ</span>
            <fieldset><legend>纸张与页边距</legend><div class="settings-grid" id="page-fields"></div><button id="page-no-margins" type="button">无页边距</button></fieldset>
            <div id="band-fields"></div><span class="field-help dialog-help" role="note" tabindex="0" aria-label="页区说明：新建变体是完整页眉页脚对，未启用的一边为空。节中移除局部声明恢复根继承；清空继承区域请显式设置高度为零的空区域。页区内容也可从画布点选编辑。" title="新建变体是完整页眉／页脚对，未启用的一边为空。节中移除局部声明恢复根继承；清空继承区域请显式设置 height=&quot;0&quot; 的空区域。页区内容也可从画布点选编辑。">ⓘ</span>
            <div class="dialog-footer"><button id="apply-page" class="primary">完成</button></div>`;
        document.body.append(dialog);
        const pageFields=[['size','纸张预设',['A4','LETTER','A3','A5','LEGAL','CUSTOM']],['orientation','方向',['PORTRAIT','LANDSCAPE']],['width','宽度','text'],['height','高度','text'],['margin','统一页边距','text'],...['top','right','bottom','left'].map((s,n)=>['margin-'+s,['上','右','下','左'][n]+'页边距','text'])];
        pageFields.forEach(d=>{const wrapper=field(d,'',()=>{});const input=wrapper.querySelector('input,select');input.id='page-'+d[0];$('page-fields').append(wrapper);});
        $('page-no-margins').addEventListener('click',()=>{
            $('page-margin').value='0';for(const side of ['top','right','bottom','left'])$('page-margin-'+side).value='';
            schedulePreview();
        });
        $('page-size').addEventListener('change',()=>{
            const sizes={A3:['297mm','420mm'],A5:['148mm','210mm'],LEGAL:['8.5in','14in']};const value=$('page-size').value;
            if(sizes[value]){[$('page-width').value,$('page-height').value]=sizes[value];$('page-size').value='CUSTOM';}
            else if(value!=='CUSTOM'){$('page-width').value='';$('page-height').value='';}
        });
        for (const id of ['page-width','page-height']) $(id).addEventListener('input',()=>{if($(id).value)$('page-size').value='CUSTOM';});
        for(const kind of ['header','footer']){
            const title=kind==='header'?'页眉':'页脚',section=el('fieldset');
            section.innerHTML=`<legend><label><input type="checkbox" id="band-${kind}" aria-label="启用${title}"> ${title}</label></legend><div class="settings-grid"><label class="setting-field">高度<input id="band-${kind}-height" aria-label="${title}高度" placeholder="沿用区域 XML"></label><label class="setting-field">与正文间距<input id="band-${kind}-gap" aria-label="${title}间距" placeholder="沿用区域 XML"></label><label class="setting-field">距纸张${kind==='header'?'顶':'底'}边<input id="band-${kind}-edge" aria-label="${title}距纸边" placeholder="沿用正文页边距"></label><label class="setting-field">距纸张左边<input id="band-${kind}-left" aria-label="${title}距左边" placeholder="沿用正文左边距"></label><label class="setting-field">距纸张右边<input id="band-${kind}-right" aria-label="${title}距右边" placeholder="沿用正文右边距"></label></div><label class="setting-field">简单文字与页码<input id="band-${kind}-text" aria-label="${title}文字"></label><span class="field-help dialog-help" role="note" tabindex="0" aria-label="页码说明：{page} 为显示页码，{pages} 为全篇总页数；混合样式、图片及表格保持 XML 编辑。" title="{page} 为显示页码，{pages} 为全篇总页数；混合样式、图片及表格保持 XML 编辑。">ⓘ</span><button data-format="band-${kind}-xml">格式化${title} XML</button><textarea id="band-${kind}-xml" spellcheck="false" aria-label="${title} XML"></textarea>`;
            $('band-fields').append(section);
            $('band-'+kind+'-text').addEventListener('input',()=>{updateSimpleBand(kind);schedulePreview();});
            $('band-'+kind+'-xml').addEventListener('input',()=>simpleBand(kind));
        }
        for(const id of ['source','data','node-xml','band-header-xml','band-footer-xml'])codeEditor($(id),id==='data');
        document.querySelectorAll('[data-format]').forEach(b=>b.addEventListener('click',()=>run(()=>format(b.dataset.format))));
        $('page-scope').addEventListener('change',()=>{const next=$('page-scope').value;try{pageDocument=buildPageDocument();$('page-scope').value=next;loadPageScope();schedulePreview();}catch(error){$('page-scope').value=pageScope;status(error.message,true);}});
        $('page-variant').addEventListener('change',()=>{const next=$('page-variant').value;try{pageDocument=buildPageDocument();$('page-variant').value=next;loadPageScope();schedulePreview();}catch(error){$('page-variant').value=pageVariant;status(error.message,true);}});
        $('page-dialog').addEventListener('input',event=>{if(event.target.id!=='page-scope'&&event.target.id!=='page-variant')schedulePreview();});
        $('page-dialog').addEventListener('change',event=>{if(event.target.id!=='page-scope'&&event.target.id!=='page-variant')schedulePreview();});
        $('close-page').addEventListener('click',()=>run(applyPage));
        $('page-dialog').addEventListener('cancel',event=>{event.preventDefault();run(applyPage);});
        on('apply-page',applyPage);
        on('apply-rich',applyProperties);
        DesignerTables.start({field,serialize,xml});
    }
    function bodyXml(attributes){const node=richNode.cloneNode(true);[...node.attributes].forEach(attribute=>node.removeAttribute(attribute.name));Object.entries(attributes).forEach(([name,value])=>node.setAttribute(name,value));return serialize(node);}
    return {start,inspect,syncEditors,bodyXml,pageDraftSource,updateBody:node=>markRich(()=>{richNode=node;},true),get richDirty(){return richDirty;},pendingMessage:()=>richDirty?'正文有未应用修改；正文与属性可一起应用。':undefined};
})();
