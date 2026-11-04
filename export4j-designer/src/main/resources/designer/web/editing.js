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
    const marker=[['marker-role','标记字体角色','text'],['marker-size','标记字号','number'],['marker-color','标记颜色','text'],['marker-shift','标记基线偏移','number'],['marker-underline','标记下划线',['true','false']],['marker-strike','标记删除线',['true','false']]];
    const schema = {
        document:[['title','标题','text'],['author','作者','text'],['literal-text','原文模式',['true','false']]],
        page:[['size','纸张',['A4','LETTER']],['width','宽度（可带单位）','text'],['height','高度（可带单位）','text'],['margin','统一页边距','text'],
            ['orientation','方向',['PORTRAIT','LANDSCAPE']],...['top','right','bottom','left'].map((side,n)=>['margin-'+side,['上','右','下','左'][n]+'页边距','text'])],
        section:[['id','标识符','text'],['first-page-number','起始页码','number']],
        columns:[['count','正文栏数',['1','2','3']],['gap','栏距（pt）','number'],['weights','栏宽比例','text'],['balanced','末页平衡',['true','false']]],
        grid:[['tracks','栅格轨数','number'],['column-gap','轨间距（pt）','number'],['row-gap','行间距（pt）','number']],
        item:[['row','栅格行号','number'],['column-start','起始轨','number'],['column-span','跨轨数','number']],
        p:[['style','命名样式','text'],...common], heading:[['level','标题级别','number'],...common],
        bullet:[['marker','列表标记','text'],['level','列表级别','number'],...marker,...common], numbered:[['list-id','列表标识','text'],['level','编号级别','number'],['restart','重新编号起始值','number'],...marker,...common],
        caption:[['kind','题注类型','text'],...common.filter(item=>!['role','color'].includes(item[0]))],
        'cross-reference':[['kind','引用类型',['PAGE','NUMBER']],['target','目标 ID','text'],...common.filter(f=>!['role','color'].includes(f[0]))],
        span:[['style','命名行内样式','text'],...inline],
        cell:[...common.filter(f=>['size','line-height','align','role','color','line-break'].includes(f[0])),['column-span','跨列数','number'],['row-span','跨行数','number']],
        list:[['items','数据路径','text'],['as','迭代变量','text']],
        each:[['items','数组路径','text'],['as','迭代变量','text']],
        if:[['test','条件数据路径','text'],['operator','比较方式',['TRUE','FALSE','EQ','NE','GT','GE','LT','LE','CONTAINS']],['value','比较值','text']],
        include:[['ref','片段名称','text'],['prefix','ID 前缀','text']],
        navigation:[['headings','按标题生成导航',['true','false']],['max-level','最大标题级别','number']],
        bookmark:[['title','书签标题','text'],['target','目标 ID','text'],['level','书签级别','number']],
        contents:[['enabled','生成目录',['true','false']],['title','目录标题','text']],
        typography:[['part','目录文字区域',['TITLE','ENTRY','PAGE_LABEL']],['size','字号','number'],['line-height','行高','number'],['first-indent','首行缩进','number'],['subsequent-indent','后续行缩进','number'],['align','对齐',['LEFT','CENTER','RIGHT','JUSTIFY']],['line-break','断行',['STANDARD','CJK_STRICT']]],
        color:[['part','目录文字区域',['TITLE','ENTRY','PAGE_LABEL']],['value','颜色（#RRGGBB）','text']],
        note:[['id','注释 ID','text'],['paragraph','正文锚点 ID','text'],['kind','注释类型',['FOOTNOTE','ENDNOTE']]],
        'style-if':[['test','条件数据路径','text'],['operator','比较方式',['TRUE','FALSE','EQ','NE','GT','GE','LT','LE','CONTAINS']],['value','比较值','text'],...inline.filter(item=>item[0]!=='baseline-shift'),['line-height','行高','number'],['align','对齐',['LEFT','CENTER','RIGHT','JUSTIFY']],['line-break','断行',['STANDARD','CJK_STRICT']]],
        table:[['row-split','跨页拆行',['true','false']],['draw-borders','绘制边框',['true','false']],
            ...['top','right','bottom','left'].map((side,n)=>['padding-'+side,['上','右','下','左'][n]+'内边距','number']),['space-before','表前外间距','number'],['space-after','表后外间距','number'],['rotation','旋转角度（°）','number']],
        column:[['weight','比例列宽','number'],['width','固定列宽','number'],['min-width','自动最小宽','number'],['max-width','自动最大宽','number']],
        image:[['ref','图片资源','text'],['width','宽度','number'],['height','高度','number'],['alt','替代文字','text'],['baseline-shift','基线偏移','number'],['rotation','旋转角度（°）','number']],
        link:[['href','链接地址','text']],
        box:[['x','横坐标','text'],['y','纵坐标','text'],['width','固定宽度','text'],['height','固定高度','text'],['page','物理页（0 为每页）','number'],['width-mode','宽度模式',['FIXED','FILL']],['height-mode','高度模式',['FIXED','AUTO','FILL']],['body-flow','正文相遇时',['OVERLAP','AVOID']],['source-id','共享来源名','text'],['source-ref','共享来源引用','text']],
        header:[['height','区域高度','text'],['gap','与正文间距','text'],['edge','距纸张顶边','text'],
            ['left','距纸张左边','text'],['right','距纸张右边','text'],['variant','适用页',['default','first','even']],
            ['source-id','共享来源名','text'],['source-ref','共享来源引用','text']]
    };
    schema.footer = schema.header.map(item=>item[0]==='edge'?[item[0],'距纸张底边',item[2]]:item);
    schema.rows = [...schema.table,['items','数据路径','text'],['as','迭代变量','text']];
    for (const tag of ['page-number','page-count','page-reference','number-reference','note-marker']) {
        schema[tag] = [['width','预留宽度','number'],...(['page-number','page-count'].includes(tag)?[]:[['target','目标 ID','text']]),...inline];
    }
    const optionLabels={true:'启用',false:'关闭','1':'单栏','2':'双栏','3':'三栏',LEFT:'左对齐',CENTER:'居中',RIGHT:'右对齐',JUSTIFY:'两端对齐',
        STANDARD:'标准断行',CJK_STRICT:'中文严格禁则',PORTRAIT:'纵向',LANDSCAPE:'横向',CUSTOM:'自定义尺寸',
        default:'默认页',first:'首页',even:'偶数页',bold:'粗体角色',PAGE:'目标页码',NUMBER:'目标编号',
        TRUE:'为真',FALSE:'为假',EQ:'等于',NE:'不等于',GT:'大于',GE:'大于等于',LT:'小于',LE:'小于等于',CONTAINS:'包含文本',FIXED:'固定',FILL:'填满剩余空间',AUTO:'随内容增高',OVERLAP:'遮盖正文',AVOID:'上下避让',TITLE:'目录标题',ENTRY:'目录条目',PAGE_LABEL:'目录页码',FOOTNOTE:'脚注',ENDNOTE:'尾注'};
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
        count:'此容器中的正文按阅读顺序流经 1、2 或 3 栏，填满后自动续页。切换栏数会恢复等宽。',
        weights:'留空为等宽。按从左到右的顺序填写正数，以空格分隔，数量须与栏数一致，例如双栏 1 2 或三栏 1 1 1。',
        balanced:'启用时尽量平衡最后一页各栏的高度；关闭时按顺序填满每栏。',
        tracks:'栅格将正文宽度分为 1～12 条等宽轨道。减少轨数不会自动裁剪或移动已有项，超出范围时须修正。',
        'column-gap':'相邻栅格轨道之间的空白，单位 pt，必须非负。跨轨项包含其内部轨间距。',
        'row-gap':'两行栅格之间的空白，单位 pt，必须非负。行号跳号不会添加空白行。',
        row:'从 1 开始的逻辑行号。同一行高度由最高内容决定，整行一起分页；不支持跨行。',
        'column-start':'从 1 开始的起始轨。允许留空轨道，同一行各项不得重叠。修改后在当前静态作用域内按行号和起始轨排序。',
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
        'literal-text':'启用原文模式后，正文中的 ${…} 按原文显示，不读取绑定数据；循环数组路径仍有效。',title:'文档标题，可使用绑定变量。',author:'文档作者元数据。',
        test:'从测试数据 JSON 读取数据的点路径，无需 ${}；动态表格使用其迭代变量，如 record.score。',
        operator:'文本／数值／布尔比较；大于等数值比较只接受数字，为真／为假只接受布尔值。不执行代码。',
        value:'比较值按实际数据类型解释；数字精确比较，文本区分大小写。为真／为假时留空。'
    };
    function fieldHelp(name,label){return ['ref','prefix'].includes(name)&&richNode?.tagName==='include'?(name==='ref'?'静态片段名称，需先导入对应资源；不接受模板变量。':'必填静态 ID 前缀，用于区分片段的各次引用；同一组合中须唯一，不接受模板变量。'):name==='value'&&richNode?.tagName==='color'?'静态 RGB 颜色，格式为 #RRGGBB。':name==='title'&&['contents','bookmark'].includes(richNode?.tagName)?'静态标题文字；不读取模板变量。':name==='gap'&&label==='栏距（pt）'?'相邻正文栏之间的空白距离，单位 pt，必须非负。':name==='size'&&label==='纸张预设'?'选择纸张预设；自定义尺寸由宽度和高度决定，分节留空时继承文档设置。':help[name]||(name.startsWith('padding-')?'单元格内容与表格边框之间的内边距，单位 pt。':name.startsWith('margin-')?'此方向的页边距，覆盖统一边距；可带 pt／mm／cm／in。':`${label}写入现有 XML 属性；留空移除局部覆盖。`);}
    function field(descriptor, value, changed,kind=richNode?.tagName) {
        let [name,label,type] = descriptor; const wrapper = el(['test','items'].includes(name)?'div':'label',label,'setting-field');
        if(name==='ref'&&kind==='image')type=state.images.map(image=>image.name);
        const multiline=kind==='if'&&name==='value',input = el(multiline?'textarea':Array.isArray(type)?'select':'input');
        if (Array.isArray(type)) {
            const values = [...new Set(['',...type,...(value && !type.includes(value)?[value]:[])])];
            values.forEach(v=>{const option=el('option',v?(optionLabels[v]||v):'继承 / 默认');option.value=v;input.append(option);});
        } else { if(multiline)input.rows=2;else input.type = type; if (type === 'number') input.step = 'any'; }
        input.autocomplete='off';input.name=name;
        input.value = value; input.dataset.attribute = name; input.setAttribute('aria-label',label);
        if (!Array.isArray(type)) input.placeholder = '继承 / 默认';
        const info=fieldHelp(name,label),hint=el('span','ⓘ','field-help');hint.id='field-help-'+(++fieldId);
        hint.title=info;hint.tabIndex=0;hint.setAttribute('role','note');hint.setAttribute('aria-label',label+'：'+info);
        input.title=info;input.setAttribute('aria-describedby',hint.id);
        const caption=el('span',label,'field-caption');wrapper.textContent='';caption.append(hint);
        input.addEventListener(Array.isArray(type)?'change':'input',()=>changed(input.value));wrapper.append(caption,input);
        if(type==='number'&&!['first-indent','subsequent-indent','baseline-shift','marker-shift','rotation'].includes(name))input.min='0';
        if(name==='rotation'){input.min='-180';input.max='180';}
        if(name==='max-level'||name==='level'&&['heading','bookmark'].includes(richNode?.tagName)){input.min='1';input.max='6';input.step='1';}
        if(richNode?.tagName==='typography'&&name.includes('indent'))input.min='0';
        if(['column-span','row-span','level','min-lines-before','min-lines-after','first-page-number'].includes(name))input.step='1';
        if(['tracks','row','column-start','column-span'].includes(name)){input.step='1';input.min='1';if(name==='tracks')input.max='12';}
        if(name==='color'){
            const color=el('input');color.type='color';color.value=/^#[0-9a-f]{6}$/i.test(value)?value:'#000000';color.setAttribute('aria-label',label+'调色板');color.title='选择颜色';color.addEventListener('input',()=>{input.value=color.value;changed(input.value);});wrapper.append(color);
        }
        return wrapper;
    }
    function bindingFields(control,name){
        const input=control.querySelector('[data-attribute]'),picker=el('details',undefined,'binding-field-picker'),browser=el('div');
        const summary=el('summary');picker.append(summary,browser);control.append(picker);let mounted;
        const refresh=()=>{
            const scalar=name==='test'&&richNode?.tagName==='if'&&!['TRUE','FALSE'].includes(richNode.getAttribute('operator')||'TRUE');
            summary.textContent=name==='test'?(scalar?'选择条件字段':'选择布尔变量'):'选择数组字段';mounted?.destroy();
            mounted=DesignerBindings.mountFields(browser,{path:selected.path,types:scalar?['boolean','string','number','mixed','unknown']:[name==='test'?'boolean':'array'],selectedPath:input.value,onSelect:path=>{
                input.value=path;input.dispatchEvent(new Event('input',{bubbles:true}));
            }});
        };
        control.refreshBindingFields=refresh;refresh();
    }
    function conditionChain(root){
        const cases=[];let node=root,other;
        // Retain every existing branch. The creation budget below never truncates loaded source.
        for(let index=0;node?.tagName==='if'&&index<128;index++){
            const yes=[...node.children].find(child=>child.tagName==='then'),no=[...node.children].find(child=>child.tagName==='else');
            cases.push({node,yes,no});
            if(no?.children.length===1&&no.children[0].tagName==='if'){node=no.children[0];continue;}
            other=no;break;
        }
        return {cases,other};
    }
    function conditionSuffix(root,node){
        const parts=[];while(node!==root){const parent=node.parentElement;if(!parent)throw new Error('条件位置已变化，请重新选择。');parts.unshift([...parent.children].indexOf(node));node=parent;}
        return parts.length?'/'+parts.join('/'):'';
    }
    function conditionError(error){
        const host=$('properties').querySelector('[data-condition-editor]');if(!host)return;
        const warning=host.querySelector('.condition-editor-error')||el('p',undefined,'condition-editor-error');warning.textContent=error.message;warning.setAttribute('role','alert');host.append(warning);
    }
    async function conditionMutation(work){
        if(nodeXmlDirty)throw new Error('请先应用节点 XML 修改。');
        if(sourceChanged())throw new Error('源码有未应用修改，请先应用源码后再调整条件分支。');
        if(selectedPaths.length!==1||richNode?.tagName!=='if')throw new Error('请选择要编辑的条件。');
        const ownerPath=selected.path,candidate=richNode.cloneNode(true);
        // Apply only intentional attribute edits. Single-line advanced controls must not
        // normalize untouched XML attribute values while changing a nested branch.
        const attributes=attributeValues();for(const name of changedAttributes){if(attributes[name])candidate.setAttribute(name,attributes[name]);else candidate.removeAttribute(name);}
        const target=work(candidate),suffix=target?conditionSuffix(candidate,target):undefined;
        const document=xml(propertyDirty?draftSource():$('source').value),owner=nodeForPath(document,ownerPath);
        if(owner?.tagName!=='if')throw new Error('条件位置已变化，请重新选择，当前源码保持不变。');
        owner.replaceWith(document.importNode(candidate,true));
        const pending=[{node:document.documentElement,depth:1}];let count=0;
        while(pending.length){const current=pending.pop();if(++count>20_000||current.depth>32)throw new Error('添加后会超过模板的 32 层嵌套或结构预算；请减少条件层级。');for(const child of current.node.children)pending.push({node:child,depth:current.depth+1});}
        if(serialize(document).length>1_000_000)throw new Error('修改后模板超过源码预算。');
        markRich(()=>{richNode=candidate;},true);await applyProperties();
        if(suffix!==undefined){const entry=entries.find(item=>item.path===ownerPath+suffix);if(!entry)throw new Error('新增条件的位置已变化，请重新选择。');await choose(entry);DesignerWorkspace.openInspector({focus:true});}
    }
    function conditionAction(text,work){return button(text,()=>run(async()=>{try{await conditionMutation(work);}catch(error){conditionError(error);throw error;}}));}
    function conditionPredicate(node){
        const result=node.ownerDocument.createElement('if');
        for(const name of ['test','operator','value'])if(node.hasAttribute(name))result.setAttribute(name,node.getAttribute(name));
        result.append(node.ownerDocument.createElement('then'),node.ownerDocument.createElement('else'));return result;
    }
    function conditionPanel(){
        const fields=['test','operator','value'].map(name=>$('structured-properties').querySelector('[data-attribute="'+name+'"]')?.closest('.setting-field'));if(fields.some(field=>!field))return;
        const panel=el('section',undefined,'condition-editor'),input=fields[0].querySelector('[data-attribute="test"]'),operator=fields[1].querySelector('select'),comparison=fields[2].querySelector('input,textarea'),sentence=el('p',undefined,'condition-sentence'),value=el('div');
        panel.dataset.conditionEditor='';panel.setAttribute('aria-label','显示条件');panel.append(el('strong','显示条件'),...fields,sentence,value);
        operator.querySelector('option[value=""]')?.remove();
        operator.value=richNode.getAttribute('operator')||'TRUE';
        for(const control of [input,operator,comparison])control.dataset.conditionField=control.dataset.attribute;
        input.placeholder='例如 approved 或 item.visible';
        const refresh=()=>{
            const boolean=['TRUE','FALSE'].includes(operator.value);fields[2].hidden=boolean;
            sentence.textContent=input.value.trim()?'当 '+input.value.trim()+' '+(optionLabels[operator.value]||operator.value)+(boolean?'':' '+comparison.value)+' 时，显示第一个分支；否则按顺序继续判断。':'填写字段路径和比较方式，决定显示哪个分支。';
            value.replaceChildren();value.dataset.conditionValue='';
            if(!input.value.trim())return;
            try{
                const result=DesignerStudio.bindingValue({...DesignerStudioBridge.snapshot(),data:$('data').value},selected.path,input.value.trim());
                const simple=result.status==='value'&&result.type==='boolean'&&conditionChain(richNode).cases.length===1&&boolean,hint=el('p',undefined,'inspector-inline-note');
                const updateHint=raw=>{hint.textContent=simple?(raw===(operator.value==='FALSE'?'false':'true')?'当前显示：满足条件':'当前显示：其他情况'):'根据各分支的顺序判断；实际结果见预览。';};
                DesignerBindings.mountValue(value,{path:selected.path,fieldPath:input.value.trim(),inputLabel:'条件测试值',onChange:updateHint});
                if(result.status==='value'){updateHint(result.raw);value.append(hint);}
            }catch(error){const warning=el('p',error.message,'inspector-inline-note');warning.setAttribute('role','alert');value.append(warning);}
        };
        for(const control of [input,operator,comparison])control.addEventListener(control===operator?'change':'input',()=>{
            const name=control.dataset.attribute;if(control.value==='')richNode.removeAttribute(name);else richNode.setAttribute(name,control.value);
            if(control===operator){if(['TRUE','FALSE'].includes(operator.value)){comparison.value='';richNode.removeAttribute('value');setAttribute('value','');}fields[0].refreshBindingFields?.();}
            refresh();
        });
        refresh();$('structured-properties').prepend(panel);
    }
    function setAttribute(name,value){let row=[...$('attributes').children].find(row=>row.querySelector('input').value===name);if(!row){attributeRow(name,value);row=$('attributes').lastElementChild;}if(value==='')row.remove();else row.querySelectorAll('input,textarea')[1].value=value;changedAttributes.add(name);propertyDirty=true;nodeDirtyLabel();schedulePreview();}
    function gridPanel() {
        const context=DesignerStudio.gridContext(state.source,selected.path),panel=el('section',undefined,'grid-editor-panel');panel.dataset.gridHelp='';panel.setAttribute('aria-label','栅格布局');
        if(!context)return panel;
        const heading=el('div',undefined,'grid-editor-heading');heading.append(el('strong','独立图文栅格'),el('span','第 '+context.row+' 行'));panel.append(heading);
        panel.title='各格独立排版，内容不串流；整行一起分页。';
        const diagram=el('div',undefined,'grid-row-diagram');diagram.style.gridTemplateColumns=`repeat(${context.tracks},minmax(0,1fr))`;diagram.setAttribute('aria-label','当前行比例，点击选择内容格');
        for(const [index,item] of context.items.entries()){
            const ratio=Math.round(item.span/context.tracks*100),tile=button((index+1)+' · '+ratio+'%',()=>{const entry=entries.find(entry=>entry.path===item.path);if(entry)void choose(entry);});
            tile.style.gridColumn=item.start+' / span '+item.span;tile.setAttribute('aria-label','选择第 '+(index+1)+' 格（'+ratio+'%）');tile.setAttribute('aria-pressed',String(item.path===context.itemPath));diagram.append(tile);
        }
        panel.append(diagram);
        diagram.title='点击选择内容格；拖动画布格间分隔线可调整宽度。';
        if(!context.editable){const restriction=el('p',context.reason||'动态生成的栅格布局请使用 XML 编辑。','grid-layout-restriction');restriction.setAttribute('role','note');panel.append(restriction);}
        return panel;
    }
    function inspect(node) {
        richDirty = false; richNode = node.cloneNode(true);
        document.querySelectorAll('[data-image-inspector],[data-columns-help],[data-grid-help],[data-binding-help],[data-content-navigation]').forEach(item=>item.remove());
        // Keep the direct editor outside the property groups while rebuilding controls.
        $('properties').append($('body-section'));
        $('structured-properties').replaceChildren();
        delete $('structured-properties').dataset.grouped;
        const root=new DOMParser().parseFromString(state.source,'application/xml');
        const selectedNodes=selectedPaths.map(path=>nodeForPath(root,path));
        if(selectedPaths.length===1){
            const current=selectedNodes[0];let scope=current.parentElement;
            while(scope){
                if(['each','list'].includes(scope.tagName))break;
                if(scope.tagName==='rows'){
                    let child=current;while(child.parentElement&&child.parentElement!==scope)child=child.parentElement;
                    if(child.tagName==='row')break;
                }
                scope=scope.parentElement;
            }
            if(scope){
                const panel=el('div',undefined,'binding-context');panel.dataset.bindingHelp='';
                panel.textContent=`数据作用域：${scope.getAttribute('as')} ← ${scope.getAttribute('items')}`;
                panel.title='此处保留所在模板的变量作用域；可通过检查器面包屑返回数据源。动态表格的表头使用外部数据。';
                $('structured-properties').after(panel);
            }
        }
        const bodyColumns=item=>item.tagName==='columns'&&!['table','rows'].includes(item.parentElement?.tagName||'');
        const inColumns=item=>{for(let current=item;current;current=current.parentElement){if(bodyColumns(current))return true;}return false;};
        const fieldsFor=item=>item.tagName==='columns'&&!bodyColumns(item)||item.tagName==='header'&&['table','rows'].includes(item.parentElement?.tagName)?[]:schema[item.tagName]||[...item.attributes].filter(attribute=>attribute.name!=='id').map(attribute=>[attribute.name,attribute.name,'text']);
        const gridSelection=selectedPaths.length===1&&['grid','item'].includes(node.tagName);
        let gridAdvanced,gridFields;
        if(gridSelection){
            gridAdvanced=el('details',undefined,'grid-advanced');gridAdvanced.dataset.gridHelp='';gridAdvanced.append(el('summary','高级轨道设置'));
            gridFields=el('div',undefined,'settings-grid');gridAdvanced.append(gridFields);$('structured-properties').append(gridAdvanced);
        }
        const idField=node.hasAttribute('id')||bodyColumns(node)||['grid','item','section','p','heading','bullet','numbered','table','caption','cross-reference','page-break'].includes(node.tagName) ? [['id','ID','text']] : [];
        const descriptors = selectedPaths.length>1 ? fieldsFor(node).filter(descriptor=>descriptor[0]!=='id'&&selectedNodes.every(item=>fieldsFor(item).some(field=>field[0]===descriptor[0]))) : [...idField,...fieldsFor(node).filter(item=>item[0]!=='id')];
        for (const descriptor of descriptors) {
            const values=selectedNodes.map(item=>item.getAttribute(descriptor[0]));
            const same=values.every(value=>value===values[0]),common=same?values[0]||(bodyColumns(node)?({count:'2',gap:'18',balanced:'true'}[descriptor[0]]||''):node.tagName==='grid'?({tracks:'12','column-gap':'12','row-gap':'12'}[descriptor[0]]||''):''):'';
            const control=field(descriptor,common,value=>{
                setAttribute(descriptor[0],value);
                if(bodyColumns(node)&&descriptor[0]==='count'){
                    const weights=$('structured-properties').querySelector('[data-attribute="weights"]');if(weights)weights.value='';setAttribute('weights','');
                }
            });
            if(selectedNodes.some(item=>inColumns(item)||item.closest('item'))&&['keep-with-next','page-break-before'].includes(descriptor[0])){
                control.querySelector('option[value="true"]').disabled=true;
                const info='连续分栏或栅格项内暂不支持此分页选项；可留空或关闭。',hint=control.querySelector('.field-help');control.querySelector('select').title=info;hint.title=info;hint.setAttribute('aria-label',descriptor[1]+'：'+info);
            }
            if(selectedNodes.some(item=>item.closest('item'))&&descriptor[0]==='rotation'){
                const input=control.querySelector('input');input.disabled=true;input.dataset.requiresGridRotation='true';
                const info='栅格项内暂不支持旋转。',hint=control.querySelector('.field-help');input.title=info;hint.title=info;hint.setAttribute('aria-label','旋转角度：'+info);
            }
            if(node.tagName==='item'&&descriptor[0]==='column-span'){
                const info='此项跨越的轨道数，必须为正整数，且起始轨 + 跨轨数 − 1 不超过栅格轨数。';const hint=control.querySelector('.field-help');hint.title=info;hint.setAttribute('aria-label','跨轨数：'+info);control.querySelector('input').title=info;
            }
            if(!same){
                const input=control.querySelector('input,select,textarea');
                if(input.tagName==='SELECT'){const mixed=el('option','多个值');mixed.value='__mixed__';mixed.disabled=true;input.prepend(mixed);input.value=mixed.value;}
                else input.placeholder='多个值';
            }
            if(gridSelection&&descriptor[0]!=='id')gridFields.append(control);else $('structured-properties').append(control);
            if(selectedPaths.length===1&&['test','items'].includes(descriptor[0])&&!(node.tagName==='style-if'))bindingFields(control,descriptor[0]);
        }
        if(selectedPaths.length===1&&node.tagName==='if')conditionPanel();
        $('structured-help').title = descriptors.length ? '长度单位 pt；页面和页区也接受 mm / cm / in。留空移除覆盖。' : '此结构的属性使用下方高级属性或节点 XML 编辑。';
        if(bodyColumns(node)){
            const note=el('div',undefined,'columns-property-note');note.dataset.columnsHelp='';
            note.append(el('p','正文连续流经各栏，填满后自动续页。'));$('structured-properties').after(note);
        }
        if(gridSelection){
            $('structured-properties').before(gridPanel());
            const legacy=el('div',undefined,'grid-legacy-actions');legacy.append(button('添加通栏新行',()=>run(()=>addGridRow())));
            const grid=node.tagName==='grid'?node:node.closest('grid'),tracks=Number(grid?.getAttribute('tracks')||12);
            for(const [spans,label] of [[[6,6],'添加 6 + 6 行'],[[8,4],'添加 8 + 4 行'],[[4,4,4],'添加 4 + 4 + 4 行']]){
                const action=button(label,()=>run(()=>addGridRow(spans)));action.disabled=tracks!==12;action.dataset.requiresGridTracks=String(tracks!==12);legacy.append(action);
            }
            gridAdvanced.append(legacy);
        }
        $('node-page-settings').hidden = selectedPaths.length>1 || !['document','section'].includes(node.tagName);
        DesignerTables.inspect(node);
        if(node.tagName==='column')columnMode(node);
        if(node.tagName==='image')imageProperties(node);
        contentNavigation(node);
        renderRich();if(selectedPaths.length>1){$('body-section').hidden=true;$('node-source').hidden=true;document.querySelector('[data-inspect="properties"]').click();} DesignerActions.usedResources(node);if(selectedPaths.length>1)$('used-resources').hidden=true;syncEditors();
    }
    function contentNavigation(node){
        if(selectedPaths.length!==1)return;
        if(node.tagName==='if'){conditionNavigation();return;}
        const direct=['document','section','box','header','footer','columns','grid','item','table','rows','row','cell','if','then','else','each','list','include','notes','note','navigation','contents'].includes(node.tagName),hasChildren=node.children.length>0;
        if(!direct&&!hasChildren)return;
        const panel=el(direct?'section':'details',undefined,'content-navigation');panel.dataset.contentNavigation='';panel.setAttribute('aria-label','内部内容');
        const headings={document:'文档内容',section:'分节内容',if:'条件分支',then:'分支内容',else:'分支内容',each:'循环内容',list:'项目模板',table:'表格结构',rows:'表格结构',include:'片段内容'},heading=el(direct?'div':'summary',(headings[node.tagName]||'内部内容')+(hasChildren?' · '+node.children.length:''),'content-navigation-heading');
        const hints={if:'两个分支均可编辑，预览由条件数据决定。',then:'未命中的分支也可编辑。',else:'未命中的分支也可编辑。',each:'每项数据重复使用此模板；空数组仍可编辑。',list:'修改项目模板会应用到每一项。',rows:'修改正文行模板会应用到每一项。',include:'修改资源会同步影响所有引用，支持撤销。'};
        if(hints[node.tagName])heading.title=hints[node.tagName];panel.append(heading);
        if(node.tagName==='include'){
            const name=node.getAttribute('ref');
            if(Object.hasOwn(state.fragments,name))panel.append(button('编辑片段内容',()=>{void editFragment(name).catch(error=>status(error.message,true));}));
            else panel.append(el('p','未找到片段资源，请导入资源或调整引用名称。','inspector-inline-note'));
        }
        if(hasChildren){
            const list=el('div',undefined,'content-navigation-list'),ownerPath=selected.path,ownerId=node.getAttribute('id'),session=DesignerStudioBridge.snapshot().session;
            const originalChildren=[...node.children].map(serialize);
            const contentAction=(child,suffix,label)=>{
                const path=ownerPath+suffix,id=child.getAttribute('id');
                const action=button(label,()=>run(async()=>{
                    await drafts();let entry;
                    if(DesignerStudioBridge.snapshot().session===session){
                        if(id)entry=entries.find(item=>item.id===id);
                        else{
                            const parent=entries.find(item=>ownerId?item.id===ownerId:item.path===ownerPath);
                            if(parent?.tag===node.tagName){
                                const children=[...nodeAt(parent.path).children];
                                if(children.length===originalChildren.length&&children.every((item,index)=>serialize(item)===originalChildren[index]))entry=entries.find(item=>item.path===parent.path+suffix);
                            }
                        }
                    }
                    if(!entry||entry.tag!==child.tagName){
                        const message='内容位置已变化，请重新选择。',current=$('properties').querySelector('[data-content-navigation]');
                        if(current){
                            const warning=current.querySelector('.content-navigation-error')||el('p',undefined,'inspector-inline-note content-navigation-error');
                            warning.textContent=message;warning.setAttribute('role','alert');current.append(warning);
                        }
                        throw new Error(message);
                    }
                    await choose(entry);DesignerWorkspace.openInspector({focus:true});
                }));
                action.dataset.contentPath=path;action.dataset.contentTag=child.tagName;return action;
            };
            [...node.children].forEach((child,index)=>{
                const label=child.tagName==='columns'?(['table','rows'].includes(node.tagName)?'列设置':'正文分栏'):child.tagName==='header'&&['table','rows'].includes(node.tagName)?'表头':DesignerWorkspace.names[child.tagName]||child.tagName;
                const preview=['then','else'].includes(child.tagName)?child.children.length+' 个内容':(child.getAttribute('id')||child.getAttribute('test')||child.getAttribute('items')||child.textContent.trim().replace(/\s+/g,' ')).slice(0,48);
                const action=contentAction(child,'/'+index,label+(preview?' · '+preview:''));
                list.append(action);
            });panel.append(list);
        }
        if(!hasChildren&&direct&&node.tagName!=='include')panel.append(el('p','暂无内容','inspector-inline-note'));
        if(['document','section'].includes(node.tagName))panel.append(button('添加内容',()=>run(async()=>{await drafts();const path=selected.path,kind=await pickContainerContent();if(kind)await insert(kind,{path,container:true});})));
        if(['then','else','each'].includes(node.tagName))panel.append(button('在此添加内容',()=>run(()=>addContainerContent(selected.path))));
        $('structured-properties').after(panel);
    }
    function conditionNavigation(){
        const root=richNode,ownerPath=selected.path,ownerId=selected.id,session=DesignerStudioBridge.snapshot().session,initialChildren=[...root.children].map(serialize);
        const chain=conditionChain(root),panel=el('section',undefined,'content-navigation condition-navigation'),list=el('div',undefined,'content-navigation-list');
        panel.dataset.contentNavigation='';panel.setAttribute('aria-label','内部内容');
        panel.append(el('div','条件分支 · '+chain.cases.length+' + other','content-navigation-heading'),el('p','从上到下判断，只显示第一个满足条件的分支；都不满足时显示 other。','inspector-inline-note'),list);
        const contentAction=(node,label)=>{
            const suffix=conditionSuffix(root,node),id=node.getAttribute('id'),tag=node.tagName,action=button(label,()=>run(async()=>{
                if(sourceChanged()){
                    const host=$('properties').querySelector('[data-content-navigation]'),warning=host?.querySelector('.content-navigation-error')||el('p',undefined,'inspector-inline-note content-navigation-error');warning.textContent='源码有未应用修改，请先应用源码后再进入条件内容。';warning.setAttribute('role','alert');host?.append(warning);throw new Error(warning.textContent);
                }
                // Edits in this inspector deliberately change nested predicates. Compare against the
                // complete draft at click time, while XML replacement and stale foreign trees stay guarded.
                const expected=richNode===root?[...root.children].map(serialize):initialChildren;
                await drafts();let entry;
                if(DesignerStudioBridge.snapshot().session===session){
                    if(id)entry=entries.find(item=>item.id===id);
                    else{
                        const owner=entries.find(item=>ownerId?item.id===ownerId:item.path===ownerPath);
                        if(owner?.tag==='if'){
                            const children=[...nodeAt(owner.path).children];
                            if(children.length===expected.length&&children.every((child,index)=>serialize(child)===expected[index]))entry=entries.find(item=>item.path===owner.path+suffix);
                        }
                    }
                }
                if(!entry||entry.tag!==tag){
                    const host=$('properties').querySelector('[data-content-navigation]'),warning=host?.querySelector('.content-navigation-error')||el('p',undefined,'inspector-inline-note content-navigation-error');warning.textContent='内容位置已变化，请重新选择。';warning.setAttribute('role','alert');host?.append(warning);throw new Error(warning.textContent);
                }
                await choose(entry);DesignerWorkspace.openInspector({focus:true});
            }));
            action.dataset.contentPath=ownerPath+suffix;action.dataset.contentTag=tag;return action;
        };
        const preview=(branch,host,label)=>{
            if(!branch){host.append(el('p','尚无此分支','inspector-inline-note'));return;}
            const heading=contentAction(branch,label+' · '+branch.children.length+' 个内容');heading.className='condition-branch-heading';host.append(heading);
            [...branch.children].slice(0,40).forEach(content=>{
                const text=content.textContent.trim().replace(/\s+/g,' ').slice(0,160)||content.getAttribute('alt')||content.getAttribute('ref')||'空内容';
                const action=contentAction(content,text);action.className='condition-branch-content';action.title='在检查器中编辑'+(DesignerWorkspace.names[content.tagName]||content.tagName);host.append(action);
            });
            if(!branch.children.length)host.append(el('p','空分支 · 点击标题进入并添加内容','inspector-inline-note'));
            if(branch.children.length>40)host.append(el('p','其余内容请进入分支查看','inspector-inline-note'));
        };
        chain.cases.forEach((branch,index)=>{
            const card=el('div',undefined,'condition-branch-preview condition-case');card.dataset.conditionCase=String(index);card.dataset.conditionBranch='then';
            if(index){
                card.append(el('strong','否则，如果 · 第 '+(index+1)+' 分支','condition-case-title'));
                const controls=el('div',undefined,'condition-case-controls'),prefix='第 '+(index+1)+' 分支';
                let browser,input,operator,comparison,valueField,pickerSummary;
                const refreshPicker=()=>{
                    const boolean=['TRUE','FALSE'].includes(operator.value);pickerSummary.textContent=boolean?'选择布尔变量':'选择条件字段';browser?.destroy();
                    browser=DesignerBindings.mountFields(input.pickerHost,{path:ownerPath,types:boolean?['boolean']:['boolean','string','number','mixed','unknown'],selectedPath:input.value,onSelect:path=>{input.value=path;input.dispatchEvent(new Event('input',{bubbles:true}));}});
                };
                const descriptors=[['test',prefix+'字段','text'],['operator',prefix+'比较方式',['TRUE','FALSE','EQ','NE','GT','GE','LT','LE','CONTAINS']],['value',prefix+'比较值','text']];
                for(const descriptor of descriptors){
                    const name=descriptor[0],control=field(descriptor,branch.node.getAttribute(name)||(name==='operator'?'TRUE':''),value=>{
                        if(nodeXmlDirty){status('请先应用节点 XML 修改。',true);return;}
                        markRich(()=>{
                            if(value==='')branch.node.removeAttribute(name);else branch.node.setAttribute(name,value);
                            if(name==='operator'&&['TRUE','FALSE'].includes(value)){branch.node.removeAttribute('value');comparison.value='';}
                        },true);
                        if(name==='operator'){valueField.hidden=['TRUE','FALSE'].includes(value);refreshPicker();}
                    });
                    const element=control.querySelector('[data-attribute]');element.dataset.conditionField=name;
                    if(name==='test')input=element;else if(name==='operator'){operator=element;operator.querySelector('option[value=""]')?.remove();}else{comparison=element;valueField=control;}
                    controls.append(control);
                }
                const picker=el('details',undefined,'binding-field-picker'),browserHost=el('div');pickerSummary=el('summary');picker.append(pickerSummary,browserHost);input.pickerHost=browserHost;input.closest('.setting-field').append(picker);
                valueField.hidden=['TRUE','FALSE'].includes(operator.value);refreshPicker();card.append(controls);
            }
            preview(branch.yes,card,index?'本分支内容':'满足条件');
            const actions=el('div',undefined,'condition-case-actions');
            actions.append(conditionAction('添加下级条件',candidate=>{
                const current=conditionChain(candidate).cases[index],nested=conditionPredicate(current.node);current.yes.append(nested);return nested;
            }));
            const remove=index?conditionAction('删除第 '+(index+1)+' 分支',candidate=>{
                const current=conditionChain(candidate).cases[index],parent=current.node.parentElement;
                if(parent?.tagName!=='else')throw new Error('分支位置已变化，请重新选择。');
                if(current.no)current.node.replaceWith(...[...current.no.childNodes]);else current.node.remove();
            }):button('删除第 1 分支',()=>{});
            if(!index){remove.disabled=true;remove.dataset.conditionProtected='true';remove.title='保留第一个条件及其内容；可修改条件或删除整个条件容器。';}
            else remove.title='删除此分支及其内容，保留后续分支；支持撤销。';actions.append(remove);card.append(actions);list.append(card);
        });
        const other=el('div',undefined,'condition-branch-preview condition-other');other.dataset.conditionBranch='else';
        if(chain.other){
            preview(chain.other,other,'其他情况 / other');
            other.append(conditionAction('添加下级条件',candidate=>{
                const current=conditionChain(candidate),nested=conditionPredicate(current.cases.at(-1).node);current.other.append(nested);return nested;
            }));
        }
        else other.append(el('p','其他情况 / other · 暂无内容','inspector-inline-note'),conditionAction('添加不满足条件分支',candidate=>{
            const current=conditionChain(candidate).cases.at(-1);if(!current.no)current.node.append(candidate.ownerDocument.createElement('else'));
        }));
        list.append(other);
        const add=conditionAction('添加条件分支',candidate=>{
            const current=conditionChain(candidate);if(current.cases.length>=16)throw new Error('一个条件最多创建 16 个分支；现有内容保持不变。');
            const last=current.cases.at(-1),next=conditionPredicate(last.node),otherwise=next.children[1];
            if(last.no){otherwise.append(...[...last.no.childNodes]);last.no.append(next);}else{const no=candidate.ownerDocument.createElement('else');no.append(next);last.node.append(no);}
        });
        panel.append(add);
        if(chain.cases.length>=16)panel.append(el('p','已达到 16 个分支的创建上限；现有分支全部保留，可继续编辑或删除。','inspector-inline-note'));
        $('structured-properties').after(panel);
    }
    async function editFragment(name){
        await drafts();const original=state.fragments[name],identity=state.resourceIdentity;
        if(typeof original!=='string')throw new Error('片段资源已变化，请重新选择。');
        const dialog=el('dialog'),title=el('h2','编辑片段 · '+name),area=el('textarea'),error=el('p'),footer=el('div',undefined,'dialog-footer');
        dialog.setAttribute('aria-label','编辑片段内容');area.value=original;area.maxLength=1_000_000;area.rows=16;area.spellcheck=false;area.setAttribute('aria-label','片段模板 XML');error.setAttribute('role','alert');error.hidden=true;
        dialog.append(title,el('p','保留变量与作用域；应用前会检查整个候选模板，失败时保留原资源。'),area,error,footer);document.body.append(dialog);
        await new Promise(resolve=>{
            const close=()=>{dialog.close();dialog.remove();resolve();};
            const cancel=button('取消',close),apply=button('应用片段修改',()=>{void run(async()=>{
                try{
                    if(state.resourceIdentity!==identity||state.fragments[name]!==original)throw new Error('片段资源已变化，请重新打开编辑器。');
                    if(area.value===original){close();return;}
                    const encoded=await bytes(new File([area.value],name+'.xml',{type:'application/xml'}));
                    install(await request('resource',{kind:'fragment',name,bytes:encoded,source:state.source,data:state.data,validateLayout:true}));
                    await preview();close();status('片段已更新，所有引用同步生效；支持撤销。');
                }catch(failure){error.textContent=failure.message;error.hidden=false;throw failure;}
            });});
            footer.append(cancel,apply);dialog.oncancel=event=>{event.preventDefault();if(!busy)close();};dialog.showModal();area.focus();
        });
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
        work(); richDirty=true; propertyDirty=true;if(!fromBody)BodyEditor.render(richNode);nodeDirtyLabel();schedulePreview();
    }
    function appendInline(parent, tag) {
        if (tag === 'text') parent.appendChild(parent.ownerDocument.createTextNode('新文字'));
        else {
            const node=parent.ownerDocument.createElement(tag);
            if (tag === 'span') { node.setAttribute('role','bold'); node.textContent='格式文字'; }
            if (tag === 'link') { node.setAttribute('href','https://example.com'); node.textContent='链接文字'; }
            if (tag === 'image') { node.setAttribute('ref',state.images[0]?.name || 'mark'); node.setAttribute('width','12');node.setAttribute('height','12');node.setAttribute('alt','图片'); }
            if (['page-number','page-count','page-reference','number-reference'].includes(tag)) { node.setAttribute('width','36'); if(tag.endsWith('reference'))node.setAttribute('target',referenceTarget(tag)); }
            parent.appendChild(node);
        }
    }
    function inlineToolbar(parent) {
        const toolbar=el('div',undefined,'rich-toolbar');
        const original=nodeAt(selected.path),semantic=original.closest('p,heading,bullet,numbered,cell'),objects=!!semantic&&['p','cell'].includes(semantic.tagName),insideLink=!!parent.closest('link')||!!original.closest('link');
        const choices=parent.tagName==='span'?[]:[['text','文字'],['span','格式文字'],...(!insideLink?[['link','链接']]:[]),...(objects?[['image','图片'],...(!insideLink?[['page-number','页码'],['page-count','总页数'],['page-reference','目标页码'],['number-reference','目标编号']]:[])]:[])];
        choices.forEach(([tag,label])=>{
            const control=button('＋ '+label,()=>markRich(()=>{appendInline(parent,tag);renderRich();}));
            if(tag==='image'&&!state.images.length){control.disabled=true;control.title='请先在项目资源中导入图片。';}
            if(tag.endsWith('reference')&&!referenceTarget(tag)){control.disabled=true;control.title=tag==='number-reference'?'请先添加具有 ID 的题注或编号项。':'请先为预览中的目标内容设置 ID。';}
            control.dataset.inlineUnavailable=String(control.disabled);toolbar.append(control);
        });
        return toolbar;
    }
    function referenceTarget(tag){
        const kinds=tag==='number-reference'?['caption','numbered']:['p','heading','table','caption','numbered','bullet','columns','grid','item'];
        return entries.find(entry=>kinds.includes(entry.tag)&&/^[A-Za-z_][A-Za-z_0-9-]{0,127}$/.test(entry.id)&&pages.some(page=>page.hits.some(hit=>hit.path===entry.path||hit.path.startsWith(entry.path+'/'))))?.id||'';
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
                (schema[node.tagName]||[]).forEach(d=>grid.append(field(d,node.getAttribute(d[0])||'',value=>markRich(()=>{if(value)node.setAttribute(d[0],value);else node.removeAttribute(d[0]);}),node.tagName)));
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
        $('node-text').hidden=richAllowed();$('text-help').hidden=richAllowed();$('body-section').hidden=!richAllowed()&&!['span','caption','cross-reference','note'].includes(richNode.tagName);
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
        const identity=el('div');identity.id='component-identity';
        // Keep the component identity first and layout controls immediately below it.
        $('properties').prepend(identity,$('studio-properties'),$('studio-container-inspector'),properties);
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
    function bodyXml(attributes){
        const node=richNode.cloneNode(true);
        if(node.tagName==='if'){
            // A text control may normalize XML attribute whitespace. Preserve attributes without an explicit edit.
            for(const name of changedAttributes){if(Object.hasOwn(attributes,name))node.setAttribute(name,attributes[name]);else node.removeAttribute(name);}
        }else{
            [...node.attributes].forEach(attribute=>node.removeAttribute(attribute.name));Object.entries(attributes).forEach(([name,value])=>node.setAttribute(name,value));
        }
        return serialize(node);
    }
    return {start,inspect,syncEditors,bodyXml,pageDraftSource,updateBody:node=>markRich(()=>{richNode=node;},true),get richDirty(){return richDirty;},pendingMessage:()=>richDirty?'正文有未应用修改；正文与属性可一起应用。':undefined};
})();
