// SPDX-License-Identifier: Apache-2.0
'use strict';
const DesignerTables = (() => {
    let helpers, tablePath, picked = new Set(), anchorPath, scope='all', column='0';
    const ownRows = table => [...table.children].filter(node=>['header','row'].includes(node.tagName));
    const nodePath = node => {const parts=[];while(node.parentElement){parts.unshift([...node.parentElement.children].indexOf(node));node=node.parentElement;}return parts.length?'/'+parts.join('/'):'';};
    const make = (tag,text) => {const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
    const action = (label,work) => {const node=make('button',label);node.type='button';node.addEventListener('click',()=>run(work));return node;};
    const hint = text => {const node=make('span','ⓘ');node.className='field-help';node.title=text;node.tabIndex=0;node.setAttribute('role','note');node.setAttribute('aria-label',text);return node;};
    function geometry(table) {
        const rows=ownRows(table), width=table.querySelector(':scope > columns').children.length;
        if(width*rows.length>100000)throw new Error('表格编辑网格超过预算。');
        const slots=rows.map(()=>Array(width)),cells=[];
        rows.forEach((row,y)=>{let x=0;[...row.children].forEach(cell=>{
            while(slots[y][x])x++;
            const item={node:cell,path:nodePath(cell),x,y,w:Number(cell.getAttribute('column-span')||1),h:Number(cell.getAttribute('row-span')||1)};
            if(item.w<1||item.h<1||x+item.w>width||y+item.h>rows.length)throw new Error('单元格跨度超出网格。');
            for(let r=y;r<y+item.h;r++)for(let c=x;c<x+item.w;c++){if(slots[r][c])throw new Error('单元格跨度重叠。');slots[r][c]=item;}
            cells.push(item);x+=item.w;
        });});
        return {rows,width,slots,cells};
    }
    function targets(table) {
        const grid=geometry(table);
        return grid.cells.filter(cell=>scope==='all'||scope==='header'&&grid.rows[cell.y].tagName==='header'
            ||scope==='body'&&grid.rows[cell.y].tagName==='row'||scope==='selection'&&picked.has(cell.path)
            ||scope==='column'&&cell.x<=Number(column)&&cell.x+cell.w>Number(column)).map(cell=>cell.node);
    }
    function stage(table) {
        Editor.updateBody(table);
    }
    // Rebuild from the visible source draft, so consecutive inputs compose without stale snapshots.
    function mutate(work) {
        try {
            const root=helpers.xml(draftSource()),table=nodeForPath(root,tablePath);
            work(table);stage(table);
        }catch(error){status(error.message,true);}
    }
    function detailField(descriptor) {
        const table=nodeForPath(helpers.xml(draftSource()),tablePath),cells=targets(table);
        const values=cells.map(cell=>cell.getAttribute(descriptor[0])||''),value=values.length&&values.every(v=>v===values[0])?values[0]:'';
        const control=helpers.field(descriptor,value,next=>mutate(table=>{
            const cells=targets(table);if(!cells.length)throw new Error('请先选择要调整的单元格。');
            cells.forEach(cell=>{if(next==='')cell.removeAttribute(descriptor[0]);else cell.setAttribute(descriptor[0],next);});
        }));
        control.querySelector('input,select').setAttribute('aria-label','选区'+descriptor[1]);return control;
    }
    function renderDetails() {
        const host=$('table-cell-properties');if(!host)return;host.replaceChildren();
        for(const d of [['size','字号','number'],['line-height','行高','number'],['align','对齐',['LEFT','CENTER','RIGHT','JUSTIFY']],
            ['role','字体角色',['','bold']],['color','文字颜色','text'],['line-break','断行',['STANDARD','CJK_STRICT']]])host.append(detailField(d));
    }
    function drawGrid() {
        const table=nodeForPath(helpers.xml(draftSource()),tablePath),grid=geometry(table),host=$('table-cell-grid');host.replaceChildren();
        const html=make('table');html.setAttribute('aria-label','单元格选择网格');
        grid.rows.forEach((row,y)=>{const tr=make('tr');for(let x=0;x<grid.width;x++){
            const cell=grid.slots[y][x];if(!cell||cell.y!==y||cell.x!==x)continue;
            const td=make(row.tagName==='header'?'th':'td');td.colSpan=cell.w;td.rowSpan=cell.h;
            const select=make('button',cell.node.textContent.trim().slice(0,28)||'空单元格');select.type='button';select.dataset.cellPath=cell.path;
            select.title=`第 ${y+1} 行，第 ${x+1} 列 · ${cell.h} 行 × ${cell.w} 列`;
            select.setAttribute('aria-label',`选择第 ${y+1} 行第 ${x+1} 列单元格`);select.setAttribute('aria-pressed',String(picked.has(cell.path)));
            select.addEventListener('click',event=>{
                if(event.shiftKey&&anchorPath){const first=grid.cells.find(item=>item.path===anchorPath);
                    const l=Math.min(first.x,cell.x),r=Math.max(first.x+first.w,cell.x+cell.w),t=Math.min(first.y,cell.y),b=Math.max(first.y+first.h,cell.y+cell.h);
                    picked=new Set(grid.cells.filter(item=>item.x<r&&item.x+item.w>l&&item.y<b&&item.y+item.h>t).map(item=>item.path));
                }else if(event.ctrlKey||event.metaKey){picked.has(cell.path)?picked.delete(cell.path):picked.add(cell.path);anchorPath=cell.path;}
                else{picked=new Set([cell.path]);anchorPath=cell.path;}
                scope='selection';$('table-style-scope').value=scope;drawGrid();renderDetails();renderRules();
            });td.append(select);tr.append(td);
        }html.append(tr);});host.append(html);
        $('table-selection-count').textContent=`已选 ${picked.size} 格 · Shift 连选矩形，Ctrl 多选`;
    }
    function selectControl(label,options,value,change) {
        const wrap=make('label',label),select=make('select');select.setAttribute('aria-label',label);
        options.forEach(([v,text])=>{const option=make('option',text);option.value=v;select.append(option);});select.value=value;
        select.addEventListener('change',()=>change(select.value));wrap.append(select);return wrap;
    }
    function renderRules() {
        const host=$('table-rules');host.replaceChildren();
        const table=nodeForPath(helpers.xml(draftSource()),tablePath),cells=targets(table);
        const sets=cells.map(cell=>[...cell.children].filter(child=>child.tagName==='style-if'));
        const signatures=sets.map(rules=>rules.map(helpers.serialize).join(''));
        if(!cells.length){host.append(make('small','先选择样式范围或单元格。'));return;}
        if(!signatures.every(value=>value===signatures[0])){host.append(make('small','所选范围的规则不同；请选中单格编辑，或新增一条公共规则。'));return;}
        sets[0].forEach((rule,index)=>{
            const row=make('div');row.className='table-rule';const heading=make('div');heading.className='table-rule-heading';heading.append(make('strong',`规则 ${index+1}`),action('删除',async()=>{
                mutate(table=>targets(table).forEach(cell=>[...cell.children].filter(child=>child.tagName==='style-if')[index]?.remove()));renderRules();
            }));row.append(heading);
            const properties=make('div');properties.className='settings-grid';
            for(const descriptor of [['test','数据路径','text'],['operator','判断',['TRUE','FALSE','EQ','NE','GT','GE','LT','LE','CONTAINS']],['value','比较值','text'],
                ['role','字体角色',['','bold']],['color','文字颜色','text'],['size','字号','number'],['line-height','行高','number'],['align','对齐',['LEFT','CENTER','RIGHT','JUSTIFY']],
                ['underline','下划线',['true','false']],['strike','删除线',['true','false']]]){
                const control=helpers.field(descriptor,rule.getAttribute(descriptor[0])||'',value=>{
                    mutate(table=>targets(table).forEach(cell=>{
                        const item=[...cell.children].filter(child=>child.tagName==='style-if')[index];
                        if(value==='')item.removeAttribute(descriptor[0]);else item.setAttribute(descriptor[0],value);
                        if(descriptor[0]==='operator'&&['TRUE','FALSE'].includes(value))item.removeAttribute('value');
                    }));
                    if(descriptor[0]==='operator')renderRules();
                });control.querySelector('input,select').setAttribute('aria-label',`规则 ${index+1} ${descriptor[1]}`);properties.append(control);
            }
            row.append(properties);host.append(row);
        });
    }
    function inspect(node) {
        const table=['table','rows'].includes(node.tagName)?node:node.closest('table,rows');
        $('table-tools').hidden=!table||selectedPaths.length>1;if(!table||selectedPaths.length>1)return;
        if(table!==node){
            $('table-tools').replaceChildren(action('编辑表格样式与合并',async()=>{
                const entry=entries.find(item=>item.path===nodePath(table));if(entry)await choose(entry);
            }));return;
        }
        const path=nodePath(table);
        if(tablePath!==path){picked.clear();anchorPath=undefined;scope=table.tagName==='rows'?'body':'all';}tablePath=path;
        const available=new Set(geometry(table).cells.map(cell=>cell.path));picked=new Set([...picked].filter(path=>available.has(path)));
        if(node.tagName==='cell'){picked=new Set([selected.path]);scope='selection';}
        const host=$('table-tools');host.replaceChildren();
        const preset=selectControl('表格预设',[['','自定义 / 选择预设'],['compact','紧凑网格'],['spacious','宽松网格'],['borderless','无边框']],'',value=>{
            if(!value)return;run(async()=>{await drafts();const table=nodeAt(tablePath).cloneNode(true);
                const d=value==='compact'?{p:3,s:9,l:15,b:true}:value==='spacious'?{p:9,s:12,l:20,b:true}:{p:6,s:11,l:18,b:false};
                for(const side of ['top','right','bottom','left'])table.setAttribute('padding-'+side,String(d.p));table.setAttribute('draw-borders',String(d.b));
                ownRows(table).forEach(row=>[...row.children].forEach(cell=>{cell.setAttribute('size',String(d.s));cell.setAttribute('line-height',String(d.l));}));
                await edit({operation:'replace',path:tablePath,xml:helpers.serialize(table)});
            });
        });preset.querySelector('select').id='table-preset';preset.querySelector('select').setAttribute('aria-label','表格样式预设');host.append(preset);
        const toolbar=make('div');toolbar.className='table-commands';toolbar.append(action('单元格布局 / 合并',async()=>{await drafts();drawGrid();$('table-dialog').showModal();}));host.append(toolbar);
        const options=[['all','整张表格'],['header','表头'],['body','正文行'],['column','指定列'],['selection','选中的单元格']];
        const scopeField=selectControl('样式范围',options,scope,value=>{scope=value;$('table-style-column').hidden=value!=='column';renderDetails();renderRules();});
        scopeField.querySelector('select').id='table-style-scope';host.append(scopeField);
        const colField=selectControl('样式列',Array.from({length:geometry(table).width},(_,i)=>[String(i),`第 ${i+1} 列`]),column,value=>{column=value;renderDetails();renderRules();});
        colField.id='table-style-column';colField.hidden=scope!=='column';host.append(colField);
        const details=make('div');details.id='table-cell-properties';details.className='settings-grid';host.append(details);renderDetails();
        const rules=make('details'),summary=make('summary','条件样式');summary.append(hint('按顺序执行，后命中规则只覆盖所声明属性。数据路径无需 ${}，动态表格使用 record.xxx 等迭代变量。仅支持行内单元格。'));
        const list=make('div');list.id='table-rules';rules.append(summary,list,action('新增条件规则',async()=>{
            await drafts();
            const table=nodeAt(tablePath),sample=table.tagName==='rows'&&scope!=='header'?table.getAttribute('as')+'.flag':'flag';
            const path=await ask('条件数据路径（布尔值；之后可修改比较方式）',sample);if(path===null)return;
            mutate(table=>{const cells=targets(table);if(!cells.length)throw new Error('请先选择要添加条件样式的单元格。');
                cells.forEach(cell=>{if([...cell.children].some(n=>['p','table'].includes(n.tagName)))throw new Error('条件样式目前用于行内单元格；块格请编辑内部段落。');
                    const rule=table.ownerDocument.createElement('style-if');rule.setAttribute('test',path);rule.setAttribute('operator','TRUE');rule.setAttribute('color','#C0392B');cell.append(rule);});});renderRules();
        }));host.append(rules);renderRules();
    }
    function start(value) {
        helpers=value;
        const dialog=make('dialog');dialog.id='table-dialog';dialog.innerHTML='<div class="dialog-heading"><h2>单元格布局</h2><button id="close-table-dialog" aria-label="关闭单元格布局">×</button></div><p id="table-selection-count"></p><div id="table-cell-grid"></div><div class="table-commands" id="table-grid-actions"></div><p id="table-grid-error" role="status"></p>';
        document.body.append(dialog);
        $('close-table-dialog').addEventListener('click',()=>{dialog.close();renderDetails();renderRules();});
        const operation=kind=>async()=>{
            try{await drafts();await edit({operation:kind,path:tablePath,paths:[...picked],cell:[...picked][0]});picked.clear();anchorPath=undefined;drawGrid();$('table-grid-error').textContent='已更新；原单元格内容保留在左上格，可撤销。';}
            catch(error){$('table-grid-error').textContent=error.message;}
        };
        $('table-grid-actions').append(action('合并所选单元格',operation('merge-cells')),action('拆分所选单元格',async()=>{
            if(picked.size!==1){$('table-grid-error').textContent='请选择一个已合并的单元格。';return;}await operation('split-cell')();
        }));
    }
    return {start,inspect};
})();
