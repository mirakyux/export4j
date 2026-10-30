// SPDX-License-Identifier: Apache-2.0
'use strict';
// Workspace presentation owns navigation and disclosure, never the template or layout model.
const DesignerWorkspace = (() => {
    const names = {document:'文档',page:'页面',section:'分节',p:'段落',heading:'标题',bullet:'列表项',numbered:'编号项',list:'数据列表',each:'数据循环',table:'表格',rows:'动态表格',row:'行',cell:'单元格',columns:'列设置',column:'列',header:'页眉',footer:'页脚',image:'图片',span:'格式文字',link:'链接',caption:'题注','cross-reference':'交叉引用','page-break':'分页符',box:'定位框',if:'条件',then:'满足条件',else:'其他情况','page-number':'当前页码','page-count':'总页数'};
    const paths = {
        search:'m21 21-4.5-4.5M19 10.5a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0',
        save:'M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12l4 4v12a2 2 0 0 1-2 2ZM7 3v6h10V3M7 21v-8h10v8',
        export:'M12 3v12m-5-5 5 5 5-5M5 15v6h14v-6',undo:'M3 10h11a6 6 0 0 1 0 12M3 10l6-6M3 10l6 6',redo:'M21 10H10a6 6 0 0 0 0 12M21 10l-6-6M21 10l-6 6',
        plus:'M12 5v14M5 12h14',chevron:'m9 5 7 7-7 7',focus:'M8 3H3v5M16 3h5v5M3 16v5h5M21 16v5h-5',help:'M9 9a3 3 0 1 1 5 2c-1 1-2 1-2 3m0 3h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
        collapse:'m8 8 4 4 4-4m-8 9 4-4 4 4M4 3h16M4 22h16',up:'m5 14 7-7 7 7',down:'m5 10 7 7 7-7',trash:'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7',
        canvas:'M4 3h16v18H4ZM8 7h8M8 11h8M8 15h5',code:'m8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18',data:'M20 5c0 2-16 2-16 0s16-2 16 0ZM4 5v14c0 3 16 3 16 0V5M4 12c0 3 16 3 16 0',alert:'M12 3 1 21h22ZM12 9v5m0 3h.01',
        minimize:'M5 16h14',expand:'m6 14 6-6 6 6',
        'sidebar-left':'M3 4h18v16H3ZM9 4v16','sidebar-right':'M3 4h18v16H3ZM15 4v16',structure:'M4 3h6v6H4ZM14 15h6v6h-6ZM7 9v9h7M14 3h6v6h-6ZM10 6h4',
        text:'M4 5h16M12 5v16M8 21h8',table:'M3 3h18v18H3ZM3 9h18M9 3v18M3 15h18',image:'M3 3h18v18H3Zm0 14 6-6 7 10m-2-4 3-3 4 4M16 7h.01',list:'M9 6h12M9 12h12M9 18h12M3 6h.01M3 12h.01M3 18h.01',folder:'M3 5h6l2 3h10v13H3Z',break:'M4 3v5h16V3M4 21v-5h16v5M2 12h3m3 0h3m3 0h3m3 0h2'
    };
    const collapsed = new Set(), groupOpen = new Map();
    let initialized = false, commands = [], commandIndex = 0;
    function icon(name) {
        const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
        svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.classList.add('ui-icon');
        const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',paths[name]||paths.text);svg.append(path);return svg;
    }
    function hydrateIcons(host=document){host.querySelectorAll('[data-icon]').forEach(node=>node.replaceChildren(icon(node.dataset.icon)));}
    function nodeName(entry){return entry.tag==='header'&&entries.find(item=>item.path===parent(entry.path))?.tag==='table'?'表头':names[entry.tag]||entry.tag;}
    function parent(path){return path.slice(0,path.lastIndexOf('/'));}
    function nodeIcon(entry){return ['table','rows','row','cell','column','columns'].includes(entry.tag)?'table':entry.tag==='image'?'image':['list','bullet','numbered','each'].includes(entry.tag)?'list':entry.tag==='page-break'?'break':entry.tag==='document'?'canvas':entry.tag==='section'?'folder':'text';}
    function updateTree() {
        const query=$('tree-search').value.trim().toLowerCase(),matches=new Set();
        if(query)entries.filter(entry=>(entry.label+' '+nodeName(entry)).toLowerCase().includes(query)).forEach(entry=>{matches.add(entry.path);let path=entry.path;while(path){path=parent(path);matches.add(path);}});
        let visible=0;
        document.querySelectorAll('.tree-item').forEach(item=>{
            const path=item.dataset.path;let ancestor=path,hide=false;
            while(ancestor){ancestor=parent(ancestor);if(collapsed.has(ancestor)){hide=true;break;}}
            item.hidden=query?!matches.has(path):hide;
            if(!item.hidden)visible++;
            if(item.hasAttribute('aria-expanded'))item.setAttribute('aria-expanded',String(query||!collapsed.has(path)));
            item.tabIndex=selected?.path===path?0:-1;
        });
        $('tree-empty').hidden=visible>0;
        const first=document.querySelector('.tree-item:not([hidden])');if(first&&!document.querySelector('.tree-item[tabindex="0"]:not([hidden])'))first.tabIndex=0;
    }
    function decorateTree() {
        if(!initialized){entries.filter(entry=>['table','rows','columns','row'].includes(entry.tag)||entry.tag==='header'&&entries.find(item=>item.path===parent(entry.path))?.tag==='table').forEach(entry=>collapsed.add(entry.path));initialized=true;}
        document.querySelectorAll('.tree-item').forEach((item,index)=>{
            const entry=entries[index],children=entries.some(child=>parent(child.path)===entry.path&&child.path!==entry.path);
            item.replaceChildren();item.setAttribute('aria-level',String(entry.depth+1));item.setAttribute('aria-label',nodeName(entry)+(entry.id?' '+entry.id:''));
            const toggle=document.createElement('span');toggle.className='tree-chevron';toggle.setAttribute('aria-hidden','true');
            if(children){toggle.append(icon('chevron'));item.setAttribute('aria-expanded',String(!collapsed.has(entry.path)));toggle.addEventListener('click',event=>{event.stopPropagation();toggleTree(entry.path);});}
            item.append(toggle,icon(nodeIcon(entry)));
            const text=entry.label.slice(entry.tag.length).trim().replace(entry.id?'#'+entry.id:'','').trim();
            const title=document.createElement('span');title.className='tree-title'+(entry.id?' tree-id':'');title.textContent=entry.id||text||nodeName(entry);item.append(title);
            const caption=document.createElement('span');caption.className='tree-preview';caption.textContent=entry.id?text||nodeName(entry):'';item.append(caption);
        });updateTree();
    }
    function toggleTree(path,expand){const open=expand??collapsed.has(path);if(open)collapsed.delete(path);else collapsed.add(path);updateTree();}
    function selection() {
        if(!selected)return;
        let path=selected.path;while(path){path=parent(path);collapsed.delete(path);}updateTree();
        $('inspector-title').textContent=selectedPaths.length>1?'多选属性':nodeName(selected);
        $('inspector-subtitle').textContent=selectedPaths.length>1?`已选 ${selectedPaths.length} 个元素 · 调整共同属性`:selected.id?'#'+selected.id:selected.label.replace(selected.tag,'').trim()||'设置当前'+nodeName(selected);
        const lineage=entries.filter(entry=>entry.path===selected.path||selected.path.startsWith(entry.path+'/')||entry.path==='');
        const crumb=$('selection-crumb');crumb.replaceChildren();
        lineage.forEach((entry,index)=>{
            if(index)crumb.append(icon('chevron'));
            const button=document.createElement('button');button.type='button';button.textContent=nodeName(entry)+(entry.id?' #'+entry.id:'');button.title=entry.label;
            if(entry.path===selected.path)button.setAttribute('aria-current','location');
            button.addEventListener('click',()=>run(async()=>{await choose(entry);if(selected?.path===entry.path&&mainView==='canvas-view')DesignerCanvas.reveal(entry);}));crumb.append(button);
        });
        crumb.lastElementChild?.scrollIntoView({block:'nearest',inline:'nearest'});
        groupProperties();
    }
    function documentState(name,changed){
        const label=/\.xml$/i.test(name)?name:name==='未命名模板'?'template.xml':name.replace(/\.zip$/i,'')+'.xml';
        for(const id of ['document-filename','navigator-filename'])$(id).textContent=label;
        $('document-dirty').hidden=!changed;
        $('document-filename').parentElement.title=name+(changed?' · 模板或测试数据有未保存修改':' · 已保存');
    }
    function editorStatus(area){
        if(!area){
            const focused=document.activeElement;
            area=focused?.matches('.code-editor textarea')?focused:mainView==='source-view'?$('source'):toolVisible()&&dock.panel==='data-view'?$('data'):undefined;
        }
        if(!area){$('editor-position').textContent='';$('editor-language').textContent='设计视图';return;}
        const before=area.value.slice(0,area.selectionEnd),line=before.split('\n').length,column=before.length-before.lastIndexOf('\n');
        const count=area.selectionEnd-area.selectionStart;
        $('editor-position').textContent=`第 ${line} 行，第 ${column} 列`+(count?` · 选中 ${count} 字符`:'');
        $('editor-language').textContent=area.id==='data'?'JSON':'XML';
    }
    function fieldGroup(name){
        if(name==='id')return '标识';
        if(['items','as','test','operator','value','list-id','target','kind','restart'].includes(name))return '数据与引用';
        if(['rotation','baseline-shift'].includes(name))return '变换';
        if(name.startsWith('padding-')||name.startsWith('space-')||name.includes('indent'))return '间距与缩进';
        if(['keep-together','keep-with-next','page-break-before','min-lines-before','min-lines-after','row-split','first-page-number','line-break'].includes(name))return '分页与断行';
        if(['style','size','line-height','align','role','color','underline','strike'].includes(name)&&!['page','document'].includes(selected.tag))return '文字样式';
        return '基本属性';
    }
    function groupProperties(){
        const host=$('structured-properties');if(!host||host.dataset.grouped==='true')return;
        host.dataset.grouped='true';
        const fields=[...host.children].filter(item=>item.classList.contains('setting-field')),groups=new Map();
        fields.forEach(field=>{const key=fieldGroup(field.querySelector('[data-attribute]').dataset.attribute);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(field);});
        fields.forEach(field=>field.remove());
        const order=['标识','基本属性','数据与引用','文字样式','间距与缩进','分页与断行','变换'];
        order.forEach(name=>{
            if(!groups.has(name))return;
            const section=document.createElement('details');section.className='property-group';section.dataset.group=name;
            section.open=groupOpen.get(name)??!['间距与缩进','分页与断行','变换'].includes(name);
            const summary=document.createElement('summary');summary.textContent=name;section.append(summary);
            const grid=document.createElement('div');grid.className='settings-grid';grid.append(...groups.get(name));section.append(grid);
            section.addEventListener('toggle',()=>groupOpen.set(name,section.open));host.append(section);
        });
        const ratio=host.querySelector('.ratio-lock');if(ratio)host.querySelector('[data-group="基本属性"]>.settings-grid')?.append(ratio);
        const body=$('body-section');if(body){const id=host.querySelector('[data-group="标识"]');if(id)id.after(body);else host.prepend(body);}
        const resources=$('used-resources');if(resources)$('properties').append(resources);
    }
    function reportItem(item, exporting) {
        if(exporting)return {...item,code:item.feature||'OTHER',block:item.block||'',page:null};
        const value=typeof item==='string'?item:JSON.stringify(item);
        const page=value.match(/pageIndex[=:]\s*(-?\d+)/)?.[1];
        return {code:item.code||value.match(/code[=:]\s*"?([A-Z_]+)/)?.[1]||'OTHER',
            block:item.blockId||value.match(/blockId[=:]\s*([^,\]]+)/)?.[1]?.replace(/^"|"$/g,'')||'',
            page:page!==undefined&&Number(page)>=0?Number(page)+1:null};
    }
    function reportText(tag, text, className) {
        const node=document.createElement(tag);node.textContent=text;if(className)node.className=className;return node;
    }
    function reportLocations(group, row) {
        if(!group.locations.size)return;
        const details=document.createElement('details');details.className='diagnostic-locations';
        details.append(reportText('summary',`查看涉及位置（${group.locations.size} 项）`));
        const list=document.createElement('ul');
        [...group.locations.values()].forEach(location=>{
            const entry=entries.find(entry=>entry.id===location.block);
            const label=[location.page?'第 '+location.page+' 页':'',entry?nodeName(entry):'',location.block?'#'+location.block:'全文'].filter(Boolean).join(' · ');
            const item=document.createElement('li');
            if(entry){
                const button=reportText('button',label);button.type='button';button.title='在设计画布中选中此元素';
                button.addEventListener('click',async()=>{
                    await run(()=>choose(entry));if(selected?.path!==entry.path)return;
                    await openView('canvas-view');
                    DesignerCanvas.reveal(entry,location.page?location.page-1:undefined);
                    document.querySelector(`.tree-item[data-path="${entry.path}"]`)?.focus({preventScroll:true});
                });item.append(button);
            }else item.textContent=label;
            list.append(item);
        });details.append(list);row.append(details);
    }
    function report(report){
        const host=$('diagnostics-summary');host.replaceChildren();
        const exporting=Array.isArray(report.issues),groups=new Map();
        (exporting?report.issues:report.diagnostics||[]).forEach(item=>{
            const detail=reportItem(item,exporting),key=[detail.code,detail.action,detail.treatment,detail.reason,JSON.stringify(detail.effects)].join('|');
            if(!groups.has(key))groups.set(key,{...detail,count:0,locations:new Map()});
            const group=groups.get(key);group.count++;
            if(detail.block||detail.page)group.locations.set(detail.block+'|'+detail.page,{block:detail.block,page:detail.page});
        });
        const hasLoss=exporting&&report.losses>0,needsReview=[...groups.values()].some(group=>group.code!=='FONT_FALLBACK');
        const summary=reportText('section','', 'diagnostic-overview'+(report.error?' is-error':hasLoss||needsReview?' needs-review':''));
        summary.append(reportText('span',report.error?'需要修正':hasLoss||needsReview?'建议检查':'检查完成','diagnostic-status'));
        summary.append(reportText('h2',report.error?'本次操作未完成':exporting?hasLoss?'可以继续导出，部分内容会改变':'导出检查通过':`预览已生成，共 ${report.pages??pages.length} 页`));
        summary.append(reportText('p',report.error?'请先处理下面的问题。画布继续保留上一次成功的预览。':exporting?
            hasLoss?`本次${report.format?' '+report.format+' ':''}导出有 ${report.losses} 项内容会被简化或移除。下面逐项说明；模板和测试数据不会因此被改写。`:'本次导出检查未发现需简化或移除的内容。检查通过后仍需点击“确认并下载”。':
            groups.size?`有 ${groups.size} 类提示。先看“影响”和“建议”；下面的位置可点击定位。`:'本次排版未发现字体替换或缺字提示。导出时会另行检查所选格式的兼容情况。'));
        host.append(summary);
        if(report.error){
            const row=reportText('section','', 'diagnostic-row');row.append(reportText('h3','请检查 XML、测试数据或本次操作的设置'));
            row.append(reportText('p',report.error,'diagnostic-error-message'));
            row.append(reportText('p','修改 XML 或测试数据后会自动重新预览；导出设置修改后，请重新点击“检查导出内容”。'));
            host.append(row);
        }
        const features={ELEMENT_ROTATION:'元素旋转',PAGE_REGION_POSITION:'页眉页脚位置',INTERACTIVE_CONTENT:'链接、导航或交互内容',PAGE_LAYOUT:'页面布局',PAGE_REGIONS:'页眉页脚',BOOKMARKS:'书签',COMMENTS:'批注',TABLE:'表格',TABLE_LAYOUT:'表格布局',TEXT_STYLE:'文字样式',FONT:'字体',IMAGE:'图片',CROSS_REFERENCE:'交叉引用'};
        const treatments={DEGRADE:'简化呈现',DISCARD:'移除不兼容部分',PRESERVE:'保留原内容',SKIP:'跳过本项'};
        groups.forEach(group=>{
            const fallback=group.code==='FONT_FALLBACK',missing=group.code==='MISSING_GLYPH_REPLACED';
            const row=reportText('section','','diagnostic-row');
            const heading=reportText('div','','diagnostic-row-heading');
            heading.append(reportText('span',fallback?'外观提醒':missing?'文字有替换':exporting?'导出变化':'需要核对','diagnostic-status'));
            heading.append(reportText('h3',fallback?'部分文字使用了备用字体':missing?'部分字符无法显示，已改用替代字符':features[group.code]||'部分内容需要核对'));
            row.append(heading);
            row.append(reportText('p',fallback?'首选字体不包含这些字，排版时使用了项目资源中能显示它们的备用字体。':missing?'已配置的字体无法显示部分字符；当前预览中的这些字符已被替换。':exporting?
                `当前导出格式对这部分内容的处理：${treatments[group.treatment]||'请核对技术详情'}。同类记录 ${group.count} 条。`:'引擎报告了暂未提供通俗解释的情况，请展开技术详情核对原因。'));
            const explanation=document.createElement('dl');explanation.className='diagnostic-explanation';
            const impact=fallback?'文字仍然保留，字体外观、文字宽度和换行可能与首选字体不同。':missing?'导出前应检查相关文字，避免把替代字符当成原文。':
                group.code==='ELEMENT_ROTATION'?'旋转角度会丢失，元素内容以未旋转的方式呈现。':group.code==='PAGE_REGION_POSITION'?'页区内容保留，但位置或重叠关系可能变化。':group.code==='INTERACTIVE_CONTENT'?'导出文件将无法保留原有的导航、链接或交互对象。':
                exporting?group.treatment==='DISCARD'?'本项不兼容内容不会出现在导出文件中；具体范围请核对技术详情。':group.treatment==='DEGRADE'?'本项内容会采用较简单的表示，部分格式或功能无法保留。':'请核对本项的具体处理及技术详情。':'本项的影响尚不能自动判断，请查看技术详情。';
            const advice=fallback?'如果预览中的字形符合要求，可以继续使用；如需统一字体，请在“资源 → 字体”导入覆盖这些字符的字体，并调整字体角色。':missing?'在“资源 → 字体”导入覆盖这些字符的字体，重新预览并确认文字。':exporting?
                '需要保留原效果时，尝试其他导出格式并重新检查；接受本项变化后再确认下载。“忽略警告”仍会发生这些变化。':'展开下方技术详情，结合涉及位置检查模板与资源；无法确定时可下载报告用于排查。';
            explanation.append(reportText('dt','影响'),reportText('dd',impact),reportText('dt','建议'),reportText('dd',advice));row.append(explanation);
            if(!exporting)row.append(reportText('p',`${group.count} 条${fallback||missing?'字符级':''}记录，已按类型合并显示${fallback?'；不表示同等数量的错误':''}。`,'diagnostic-meta'));
            reportLocations(group,row);
            if(!fallback&&!missing){
                const details=document.createElement('details');details.className='diagnostic-locations';details.append(reportText('summary','查看技术详情'));
                details.append(reportText('pre',JSON.stringify({feature:group.code,reason:group.reason,action:group.action,treatment:group.treatment,effects:group.effects},null,2)));row.append(details);
            }
            host.append(row);
        });
        $('issue-count').textContent=String(groups.size+(report.error?1:0));
        $('issue-count').title='提示类型数；包含外观提醒，逐字记录会合并显示';
    }
    function commandList(){
        const simple=(name,id,key='')=>({name,key,perform:()=>$(id).click()});
        return [simple('新建空白文档','new-blank'),simple('从样例新建','new-sample'),simple('保存模板 ZIP','save','Ctrl S'),simple('打开XML / ZIP','open'),simple('另存测试数据 JSON','save-data'),simple('加载测试数据 JSON','load-data'),simple('页面设置、页眉页脚与边距','page-settings'),simple('导出文件','export'),simple('恢复默认布局','reset-layout'),{name:'切换侧边面板',key:'Ctrl B',perform:()=>togglePanel('navigator')},{name:'切换属性检查器',key:'',perform:()=>togglePanel('inspector')},
            ...[...document.querySelectorAll('[data-insert]')].map(button=>({name:'插入'+button.textContent,key:'',perform:()=>run(()=>insert(button.dataset.insert))})),
            ...[['文档结构','navigator-view'],['项目资源','resources-view'],['设计画布','canvas-view'],['XML 源码','source-view'],['测试数据 JSON','data-view'],['查看诊断','diagnostics-view']].map(([name,view])=>({name,key:'',perform:()=>openView(view)})),simple('快捷键帮助','help-open'),simple('切换专注模式','focus-mode')];
    }
    function renderCommands(){
        const query=$('command-query').value.trim().toLowerCase();commands=commandList().filter(command=>command.name.toLowerCase().includes(query));commandIndex=0;
        $('command-results').replaceChildren();commands.forEach((command,index)=>{
            const button=document.createElement('button');button.type='button';const label=document.createElement('span');label.textContent=command.name;const key=document.createElement('kbd');key.textContent=command.key;button.append(label,key);
            button.addEventListener('click',()=>executeCommand(index));$('command-results').append(button);
        });
        if(!commands.length){const empty=document.createElement('p');empty.textContent='没有匹配的操作，请尝试其他关键词。';$('command-results').append(empty);}markCommand();
    }
    function markCommand(){[...$('command-results').querySelectorAll('button')].forEach((button,index)=>button.classList.toggle('current',index===commandIndex));}
    function executeCommand(index){const command=commands[index];if(!command)return;$('command-dialog').close();command.perform();}
    const dockKey='export4j.designer.side-tools.v1';
    const toolNames={'navigator-view':'文档结构','resources-view':'项目资源','data-view':'测试数据','diagnostics-view':'诊断'};
    const dock={panel:'navigator-view',position:'left'};
    let mainView='canvas-view',viewPending=false,requestedOpen;
    function toolVisible(){return shell.navigator&&!document.body.classList.contains('focus-mode');}
    function saveDock(){try{localStorage.setItem(dockKey,JSON.stringify(dock));}catch{}}
    function viewUrl(){
        const params=new URLSearchParams(location.search);params.set('view',mainView.replace('-view',''));
        params.set('panel',toolVisible()?dock.panel.replace('-view',''):'none');
        history.replaceState(null,'','?'+params+location.hash);
    }
    function layoutDock(){
        const open=toolVisible();
        document.querySelector('.workspace').classList.toggle('tools-right',dock.position==='right');
        $('dock-content').hidden=!open;$('tool-title').textContent=toolNames[dock.panel];
        document.querySelectorAll('[data-tool]').forEach(tab=>{
            const active=open&&tab.dataset.view===dock.panel;
            tab.setAttribute('aria-selected',String(active));tab.tabIndex=tab.dataset.view===dock.panel?0:-1;
        });
        document.querySelectorAll('.tool-view').forEach(view=>view.classList.toggle('active',view.id===dock.panel&&open));
        $('dock-collapse').setAttribute('aria-expanded',String(open));
        const position=$('dock-position');position.title=dock.position==='right'?'停靠到左侧':'停靠到右侧';
        position.setAttribute('aria-label',position.title);position.replaceChildren(icon(dock.position==='right'?'sidebar-left':'sidebar-right'));
    }
    function setDockOpen(open){togglePanel('navigator',open);saveDock();viewUrl();editorStatus();}
    async function openView(view,{toggle=false}={}){
        if(!['canvas-view','source-view',...Object.keys(toolNames)].includes(view)||viewPending||busy)return;
        viewPending=true;
        try{
            const close=toggle&&toolVisible()&&dock.panel===view;
            if(!close&&propertyDirty&&['source-view','data-view'].includes(view))await drafts();
            if(toolNames[view]){dock.panel=view;setDockOpen(!close);}
            else{
                mainView=view;
                document.querySelectorAll('.editor-tabs [data-view]').forEach(tab=>{
                    const active=tab.dataset.view===view;tab.setAttribute('aria-selected',String(active));tab.tabIndex=active?0:-1;
                });
                document.querySelectorAll('.editor-view').forEach(panel=>panel.classList.toggle('active',panel.id===view));viewUrl();editorStatus();
            }
        }catch(error){status('无法切换编辑器：'+error.message,true);diagnostics({error:error.message});}
        finally{viewPending=false;}
    }
    function startDock(){
        try{
            const saved=JSON.parse(localStorage.getItem(dockKey));
            if(saved){if(toolNames[saved.panel])dock.panel=saved.panel;if(['left','right'].includes(saved.position))dock.position=saved.position;}
        }catch{}
        const params=new URLSearchParams(location.search),view=params.get('view'),panel=params.get('panel');
        mainView=view==='source'?'source-view':'canvas-view';
        if(toolNames[view+'-view']){dock.panel=view+'-view';requestedOpen=true;}
        else if(toolNames[panel+'-view']){dock.panel=panel+'-view';requestedOpen=true;}
        else if(panel==='none')requestedOpen=false;
        $('dock-collapse').addEventListener('click',()=>setDockOpen(false));
        $('dock-position').addEventListener('click',()=>{dock.position=dock.position==='right'?'left':'right';layoutShell();saveDock();});
    }
    const shellKey='export4j.designer.workbench.v2';
    const shellDefaults={navigator:true,inspector:true,navWidth:280,inspectorWidth:288};
    const shell={...shellDefaults};
    let mobileInspectorOpen=false;
    const narrow=()=>matchMedia('(max-width:700px)').matches;
    function saveShell(){try{localStorage.setItem(shellKey,JSON.stringify(shell));}catch{}}
    function sideBounds(name){
        const workspace=document.querySelector('.workspace'),other=name==='navWidth'?'inspector':'navigator';
        const otherSize=shell[other]&&!document.body.classList.contains('focus-mode')?document.querySelector(other==='navigator'?'.structure':'.inspector').getBoundingClientRect().width:0;
        const max=Math.max(0,Math.min(name==='navWidth'?420:480,workspace.clientWidth-44-8-240-otherSize));
        return {min:Math.min(name==='navWidth'?220:240,max),max};
    }
    function layoutShell(){
        const workspace=document.querySelector('.workspace'),focused=document.body.classList.contains('focus-mode');
        workspace.classList.toggle('navigator-hidden',!shell.navigator);workspace.classList.toggle('inspector-hidden',!shell.inspector);workspace.classList.toggle('mobile-inspector-open',mobileInspectorOpen);
        let nav=Math.max(220,Math.min(420,shell.navWidth)),inspector=Math.max(240,Math.min(480,shell.inspectorWidth));
        const budget=workspace.clientWidth-44-(shell.navigator?4:0)-(shell.inspector?4:0)-240;
        if(shell.navigator&&shell.inspector&&nav+inspector>budget){
            const reduceNav=Math.min(nav-220,nav+inspector-budget);nav-=Math.max(0,reduceNav);
            inspector=Math.max(240,Math.min(inspector,budget-nav));
        }else if(shell.navigator&&!shell.inspector)nav=Math.min(nav,budget);
        else if(shell.inspector&&!shell.navigator)inspector=Math.min(inspector,budget);
        workspace.style.setProperty('--nav-width',nav+'px');workspace.style.setProperty('--inspector-width',inspector+'px');
        for(const [name,id] of [['navWidth','navigator-resizer'],['inspectorWidth','inspector-resizer']]){
            const bounds=sideBounds(name),element=$(id);
            element.setAttribute('aria-valuemin',String(Math.round(bounds.min)));element.setAttribute('aria-valuemax',String(Math.round(bounds.max)));
            element.setAttribute('aria-valuenow',String(Math.round(name==='navWidth'?nav:inspector)));
        }
        document.querySelectorAll('[data-toggle-panel]').forEach(button=>{
            const active=shell[button.dataset.togglePanel]&&!focused&&(button.dataset.togglePanel!=='inspector'||!narrow()||mobileInspectorOpen);
            button.setAttribute('aria-pressed',String(active));button.setAttribute('aria-controls',button.dataset.togglePanel==='navigator'?'navigator-panel':'inspector-panel');
        });
        layoutDock();
    }
    function togglePanel(name,force){
        const active=(name==='inspector'&&narrow()?mobileInspectorOpen:shell[name])&&!document.body.classList.contains('focus-mode'),open=force??!active;
        if(name==='inspector'&&narrow())mobileInspectorOpen=open;
        const panel=$(name==='navigator'?'navigator-panel':'inspector-panel');
        if(!open&&panel.contains(document.activeElement)){if(name==='navigator')document.querySelector(`[data-tool][data-view="${dock.panel}"]`).focus();else document.querySelector('.workspace-actions [data-toggle-panel="inspector"]').focus();}
        shell[name]=open;document.body.classList.remove('focus-mode');$('focus-mode').setAttribute('aria-pressed','false');layoutShell();saveShell();viewUrl();editorStatus();
    }
    function bindResize(id,key,vertical,reverse,bounds){
        const separator=$(id);let gesture;const reversed=()=>typeof reverse==='function'?reverse():reverse;
        separator.title='拖动调整；方向键微调，Shift 加快，Home 最小，End 最大';
        separator.addEventListener('pointerdown',event=>{
            if(busy||event.button!==0)return;event.preventDefault();separator.focus();separator.setPointerCapture(event.pointerId);
            gesture={id:event.pointerId,coordinate:vertical?event.clientY:event.clientX,size:Number(separator.getAttribute('aria-valuenow'))};
            document.body.classList.add(vertical?'workbench-resizing-y':'workbench-resizing-x');
        });
        separator.addEventListener('pointermove',event=>{
            if(!gesture||event.pointerId!==gesture.id)return;
            const delta=((vertical?event.clientY:event.clientX)-gesture.coordinate)*(reversed()?-1:1),range=bounds();
            shell[key]=Math.max(range.min,Math.min(range.max,gesture.size+delta));layoutShell();
        });
        const stop=()=>{if(!gesture)return;gesture=undefined;document.body.classList.remove('workbench-resizing-x','workbench-resizing-y');saveShell();};
        separator.addEventListener('pointerup',stop);separator.addEventListener('pointercancel',stop);separator.addEventListener('lostpointercapture',stop);
        separator.addEventListener('keydown',event=>{
            if(busy)return;const range=bounds(),step=event.shiftKey?40:10,now=Number(separator.getAttribute('aria-valuenow'));
            if(event.key==='Home')shell[key]=range.min;
            else if(event.key==='End')shell[key]=range.max;
            else if(event.key===(vertical?'ArrowDown':'ArrowRight'))shell[key]=now+step*(reversed()?-1:1);
            else if(event.key===(vertical?'ArrowUp':'ArrowLeft'))shell[key]=now-step*(reversed()?-1:1);
            else return;
            event.preventDefault();shell[key]=Math.max(range.min,Math.min(range.max,shell[key]));layoutShell();saveShell();
        });
    }
    function startShell(){
        try{
            const saved=JSON.parse(localStorage.getItem(shellKey));
            if(saved){
                for(const key of ['navigator','inspector'])if(typeof saved[key]==='boolean')shell[key]=saved[key];
                for(const key of ['navWidth','inspectorWidth'])if(Number.isFinite(saved[key])&&saved[key]>0)shell[key]=saved[key];
            }
        }catch{}
        if(requestedOpen!==undefined)shell.navigator=requestedOpen;
        document.querySelectorAll('[data-toggle-panel]').forEach(button=>button.addEventListener('click',()=>togglePanel(button.dataset.togglePanel)));
        document.querySelectorAll('[data-open-view]').forEach(button=>button.addEventListener('click',()=>openView(button.dataset.openView)));
        document.querySelectorAll('[data-command]').forEach(button=>button.addEventListener('click',()=>$(button.dataset.command).click()));
        $('reset-layout').addEventListener('click',()=>{
            Object.assign(shell,shellDefaults);mobileInspectorOpen=false;document.body.classList.remove('focus-mode');$('focus-mode').setAttribute('aria-pressed','false');
            Object.assign(dock,{position:'left',panel:'navigator-view'});layoutShell();saveShell();saveDock();viewUrl();editorStatus();
        });
        bindResize('navigator-resizer','navWidth',false,()=>dock.position==='right',()=>sideBounds('navWidth'));
        bindResize('inspector-resizer','inspectorWidth',false,true,()=>sideBounds('inspectorWidth'));
        layoutShell();openView(mainView);new ResizeObserver(layoutShell).observe(document.querySelector('.workspace'));
    }
    function start(){
        hydrateIcons();$('save-menu').addEventListener('click',()=>$('save').click());
        $('select-document').addEventListener('click',()=>choose(entries[0]));
        $('tree-search').addEventListener('input',updateTree);
        $('collapse-tree').addEventListener('click',()=>{entries.filter(entry=>entry.path).forEach(entry=>collapsed.add(entry.path));updateTree();});
        $('tree').addEventListener('keydown',event=>{
            const target=event.target.closest('.tree-item');if(!target||event.altKey||event.ctrlKey||event.metaKey||event.shiftKey)return;
            const items=[...$('tree').querySelectorAll('.tree-item:not([hidden])')],index=items.indexOf(target),path=target.dataset.path;
            let next;
            if(event.key==='ArrowDown')next=items[Math.min(items.length-1,index+1)];
            else if(event.key==='ArrowUp')next=items[Math.max(0,index-1)];
            else if(event.key==='Home')next=items[0];else if(event.key==='End')next=items.at(-1);
            else if(event.key==='ArrowRight'){if(target.getAttribute('aria-expanded')==='false')toggleTree(path,true);else next=items[index+1];}
            else if(event.key==='ArrowLeft'){if(target.getAttribute('aria-expanded')==='true')toggleTree(path,false);else next=items.find(item=>item.dataset.path===parent(path));}
            else return;
            event.preventDefault();if(next){items.forEach(item=>item.tabIndex=-1);next.tabIndex=0;next.focus();}
        });
        document.querySelectorAll('details.file-menu,details.insert-menu').forEach(menu=>{
            menu.addEventListener('toggle',()=>{if(menu.open)document.querySelectorAll('details.file-menu,details.insert-menu').forEach(other=>{if(other!==menu)other.open=false;});});
            menu.addEventListener('click',event=>{if(event.target.closest('button'))menu.open=false;});
        });
        document.addEventListener('pointerdown',event=>document.querySelectorAll('details.file-menu,details.insert-menu').forEach(menu=>{if(!menu.contains(event.target))menu.open=false;}));
        $('focus-mode').addEventListener('click',()=>{const focused=document.body.classList.toggle('focus-mode');$('focus-mode').setAttribute('aria-pressed',String(focused));layoutShell();viewUrl();editorStatus();DesignerCanvas.zoom();});
        $('command-open').addEventListener('click',()=>{$('command-query').value='';renderCommands();$('command-dialog').showModal();$('command-query').focus();});
        $('command-query').addEventListener('input',renderCommands);
        $('command-query').addEventListener('keydown',event=>{if(['ArrowDown','ArrowUp'].includes(event.key)&&commands.length){event.preventDefault();commandIndex=(commandIndex+(event.key==='ArrowDown'?1:-1)+commands.length)%commands.length;markCommand();$('command-results').children[commandIndex]?.scrollIntoView({block:'nearest'});}else if(event.key==='Enter'){event.preventDefault();executeCommand(commandIndex);}});
        $('help-open').addEventListener('click',()=>$('help-dialog').showModal());$('help-close').addEventListener('click',()=>$('help-dialog').close());
        document.addEventListener('keydown',event=>{
            if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='b'&&!event.target.closest('[contenteditable=true]')&&!busy&&!document.querySelector('dialog[open]')){event.preventDefault();togglePanel('navigator');}
            if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k'){event.preventDefault();if(!busy&&!document.querySelector('dialog[open]'))$('command-open').click();}
            if(event.key==='Escape')document.querySelectorAll('details.file-menu,details.insert-menu').forEach(menu=>menu.open=false);
            if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'&&!busy&&!document.querySelector('dialog[open]')&&event.target.closest('input,textarea,[contenteditable=true]')){event.preventDefault();$('save').click();}
        });
        document.querySelectorAll('[role=tablist]').forEach(list=>{
            list.addEventListener('keydown',event=>{const vertical=list.getAttribute('aria-orientation')==='vertical';if(![vertical?'ArrowUp':'ArrowLeft',vertical?'ArrowDown':'ArrowRight','Home','End'].includes(event.key))return;const tabs=[...list.querySelectorAll('[role=tab]')],index=tabs.indexOf(event.target);if(index<0)return;event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?tabs.length-1:(index+(event.key===(vertical?'ArrowDown':'ArrowRight')?1:-1)+tabs.length)%tabs.length;openView(tabs[next].dataset.view);tabs[next].focus();});
        });
        startDock();
        startShell();
    }
    return {start,decorateTree,selection,report,openView,documentState,editorStatus,names};
})();
