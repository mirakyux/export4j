// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('container source editing preserves table content and bound prototypes', {timeout:60000}, async t=>{
    const bundle=await build({entryPoints:[fileURLToPath(new URL('../src/containers.ts',import.meta.url))],bundle:true,format:'iife',globalName:'Containers',write:false,target:'es2022'});
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try{
        const context=await browser.newContext({serviceWorkers:'block'});await context.route('**/*',route=>route.abort());
        const page=await context.newPage();page.setDefaultTimeout(10000);await page.addScriptTag({content:bundle.outputFiles[0].text});
        const names=await page.evaluate(()=>{
            const cases=new Map(),check=(name,work)=>cases.set(name,work),parse=source=>new DOMParser().parseFromString(source,'application/xml');
            const equal=(actual,expected)=>{if(JSON.stringify(actual)!==JSON.stringify(expected))throw new Error('expected '+JSON.stringify(expected)+', actual '+JSON.stringify(actual));};
            const assert=(condition,message)=>{if(!condition)throw new Error(message);};
            const refuses=(work,pattern)=>{let error;try{work();}catch(value){error=value;}assert(error instanceof Error,'operation must refuse');if(pattern)assert(pattern.test(error.message),'unexpected reason '+error.message);};
            const state=source=>({source}),read=(source,path='/0')=>Containers.containerContext(state(source),path),edit=(source,path,operation)=>Containers.containerEdit(state(source),path,operation);
            const table='<document version="1"><table id="t"><columns><column weight="1"/><column weight="2"/></columns><header><cell>H1</cell><cell>H2</cell></header><row><cell size="12">A<span role="bold">B</span></cell><cell>${patient.name}</cell></row><row><cell>C</cell><cell>D</cell></row></table></document>';
            const texts=source=>[...parse(source).querySelector('table,rows').children].filter(node=>['row','header'].includes(node.tagName)).map(row=>[...row.children].map(cell=>cell.textContent));
            const dynamic='<document version="1"><rows id="r" items="records" as="record"><columns><column weight="1"/><column weight="1"/></columns><header><cell>Code</cell><cell>Name</cell></header><row><cell>${record.code}</cell><cell>${record.name}</cell></row></rows></document>';

            const merged='<document><table id="merged"><columns><column id="colA" weight="1"/><column id="colB" weight="1"/><column id="colC" weight="1"/></columns><header><cell id="h" column-span="2">H</cell><cell id="i">I</cell></header><row id="rowA"><cell id="a" row-span="2"><p id="inside">A<span role="bold">+</span>${name}</p></cell><cell id="b" column-span="2">B</cell></row><row id="rowB"><cell id="c">C</cell><cell id="d">D</cell></row><row><cell id="e">E</cell><cell id="f">F</cell><cell id="g">G</cell></row></table></document>';
            const coverage=source=>{
                const table=parse(source).querySelector('table,rows'),width=table.querySelector(':scope > columns').children.length,rows=[...table.children].filter(row=>['header','row'].includes(row.tagName)),slots=rows.map(()=>Array(width).fill(null));
                rows.forEach((row,y)=>{let x=0;for(const cell of row.children){while(x<width&&slots[y][x]!==null)x++;const w=+(cell.getAttribute('column-span')||1),h=+(cell.getAttribute('row-span')||1);assert(x+w<=width&&y+h<=rows.length,'span outside physical grid');for(let r=y;r<y+h;r++)for(let c=x;c<x+w;c++){assert(rows[r].tagName===row.tagName,'span crosses header boundary');equal(slots[r][c],null);slots[r][c]=cell.getAttribute('id')||'';}x+=w;}});
                assert(slots.every(row=>row.every(cell=>cell!==null)),'every physical slot must be covered once');return slots;
            };
            const once=(source,ids)=>{const document=parse(source);for(const id of ids)equal(document.querySelectorAll('[id="'+id+'"]').length,1);const values=[...document.querySelectorAll('[id]')].map(node=>node.getAttribute('id'));equal(new Set(values).size,values.length);};

            check('table context exposes physical cells and active content paths',()=>{
                const value=read(table,'/0/2/1');equal(value.kind,'table');equal(value.path,'/0');equal(value.children.length,6);equal(value.activePath,'/0/2/1');
                equal(value.table,{rows:3,bodyRows:2,columns:2,hasSpans:false,row:1,column:1,cellPath:'/0/2/1',canAddRow:true,canRemoveRow:true,canAddColumn:true,canRemoveColumn:true});
                equal(value.textPaths,['/0/1/0','/0/1/1','/0/2/0','/0/2/1','/0/3/0','/0/3/1']);
            });
            check('nearest content container distinguishes grid items and body columns',()=>{
                const source='<document><grid id="g"><item id="i" row="1" column-start="1" column-span="12"><p id="p">P</p><table id="nested"><columns><column weight="1"/></columns><row><cell>N</cell></row></table></item></grid><columns count="2"><p id="flow">F</p></columns></document>';
                equal(read(source,'/0/0/0').kind,'item');equal(read(source,'/0/0/0').children.map(child=>child.tag),['p','table']);
                equal(read(source,'/0/0/1/1/0').kind,'table');equal(read(source,'/0/0/1/0').kind,'table');equal(read(source,'/1/0').kind,'columns');equal(read('<document><p/></document>','/0'),null);
            });
            check('empty array prototypes remain available without pages or hits',()=>{
                const value=read(dynamic,'/0');equal(value.kind,'rows');equal(value.textPaths,['/0/1/0','/0/1/1','/0/2/0','/0/2/1']);equal(value.table.canAddRow,false);equal(value.table.canAddColumn,true);
                const list='<document><list items="items" as="item"><bullet id="b">${item.name}</bullet></list></document>';
                equal(read(list,'/0').textPaths,['/0/0']);equal(read(list,'/0/0').activePath,'/0/0');
            });
            check('adding a body row preserves mixed text and makes empty styled cells',()=>{
                const result=edit(table,'/0/2/0',{type:'add-row'}),document=parse(result.source);
                equal(texts(result.source),[['H1','H2'],['AB','${patient.name}'],['',''],['C','D']]);equal(document.querySelector('span').getAttribute('role'),'bold');equal(result.paths,['/0/3/0']);
                equal(document.querySelectorAll('row')[1].children.length,2);equal(texts(table),[['H1','H2'],['AB','${patient.name}'],['C','D']]);
            });
            check('inserting from a header keeps ordered headers and all prior content',()=>{
                const result=edit(table,'/0/1/0',{type:'add-row',after:false});equal(texts(result.source),[['H1','H2'],['',''],['AB','${patient.name}'],['C','D']]);equal(parse(result.source).querySelector('table').children[1].tagName,'header');
            });
            check('removing a selected body row preserves the other row and moves selection',()=>{
                const result=edit(table,'/0/2/1',{type:'remove-row'});equal(texts(result.source),[['H1','H2'],['C','D']]);equal(result.paths,['/0/2/0']);
                refuses(()=>edit(result.source,'/0/2/0',{type:'remove-row'}),/至少保留/);refuses(()=>edit(table,'/0/1/0',{type:'remove-row'}),/表头/);
            });
            check('adding a column updates all header and body rows without rewriting values',()=>{
                const result=edit(table,'/0/2/0',{type:'add-column'}),document=parse(result.source);
                equal(texts(result.source),[['H1','','H2'],['AB','','${patient.name}'],['C','','D']]);equal([...document.querySelector('columns').children].map(node=>node.getAttribute('weight')),['1','1','2']);
                equal(document.querySelectorAll('row')[0].children[1].getAttribute('size'),'12');equal(result.paths,['/0/2/1']);equal(document.querySelector('span').textContent,'B');
            });
            check('removing a column retains surviving content and rejects removing the last',()=>{
                const result=edit(table,'/0/2/0',{type:'remove-column'});equal(texts(result.source),[['H2'],['${patient.name}'],['D']]);equal(result.paths,['/0/2/0']);equal(parse(result.source).querySelector('column').getAttribute('weight'),'2');
                refuses(()=>edit(result.source,'/0',{type:'remove-column'}),/至少保留/);
            });
            check('dynamic rows edit columns while keeping the sole row and binding declarations',()=>{
                const result=edit(dynamic,'/0/2/1',{type:'add-column',after:false}),document=parse(result.source),owner=document.querySelector('rows');
                equal(owner.getAttribute('items'),'records');equal(owner.getAttribute('as'),'record');equal(owner.querySelectorAll(':scope > row').length,1);equal(texts(result.source),[['Code','','Name'],['${record.code}','','${record.name}']]);
                const removed=edit(result.source,'/0/2/1',{type:'remove-column'});equal(texts(removed.source),texts(dynamic));
                refuses(()=>edit(dynamic,'/0',{type:'add-row'}),/绑定数组/);refuses(()=>edit(dynamic,'/0',{type:'remove-row'}),/绑定数组/);
            });
            check('the unique data-list item cannot be removed or copied into invalid XML',()=>{
                const source='<document><list items="items" as="item"><bullet id="b">${item.name}</bullet></list></document>';
                refuses(()=>edit(source,'/0/0',{type:'add-list-item'}),/一个项目模板/);refuses(()=>edit(source,'/0/0',{type:'remove-list-item'}),/一个项目模板/);equal(parse(source).querySelectorAll('bullet').length,1);
            });
            check('merged table context permits structural editing while retaining minimum and header boundaries',()=>{
                const value=read(merged,'/0/2/0');equal(value.editable,true);equal(value.table.hasSpans,true);equal([value.table.canAddRow,value.table.canRemoveRow,value.table.canAddColumn,value.table.canRemoveColumn],[true,true,true,true]);
                equal(read(merged,'/0/1/0').table.canRemoveRow,false);refuses(()=>edit(merged,'/0/1/0',{type:'remove-row'}),/表头/);
                const only='<document><table><columns><column/></columns><row><cell>A</cell></row></table></document>';refuses(()=>edit(only,'/0',{type:'remove-row'}),/至少保留/);refuses(()=>edit(only,'/0',{type:'remove-column'}),/至少保留/);
            });
            check('rows outside a merge can be inserted and removed without changing its content or spans',()=>{
                const added=edit(merged,'/0/4/0',{type:'add-row'}).source;equal(coverage(added),[['h','h','i'],['a','b','b'],['a','c','d'],['e','f','g'],['','','']]);
                once(added,['a','inside','b','e','f','g']);equal(parse(added).querySelector('#a').getAttribute('row-span'),'2');equal(parse(added).querySelector('#b').getAttribute('column-span'),'2');
                const removed=edit(added,'/0/5/0',{type:'remove-row'}).source;equal(coverage(removed),coverage(merged));equal(parse(removed).querySelector('#inside').outerHTML,parse(merged).querySelector('#inside').outerHTML);
            });
            check('inserting through a vertical span expands it and creates only uncovered cells',()=>{
                const added=edit(merged,'/0/2/0',{type:'add-row'}).source;equal(coverage(added),[['h','h','i'],['a','b','b'],['a','',''],['a','c','d'],['e','f','g']]);equal(parse(added).querySelector('#a').getAttribute('row-span'),'3');once(added,['a','inside','b','c','d']);
                const allCovered='<document><table><columns><column/><column/></columns><row><cell id="whole" row-span="2" column-span="2">Keep</cell></row><row/></table></document>';
                const expanded=edit(allCovered,'/0/1/0',{type:'add-row'});equal(coverage(expanded.source),[['whole','whole'],['whole','whole'],['whole','whole']]);once(expanded.source,['whole']);assert(expanded.paths.length===1,'fully covered new row still has a valid selection');
            });
            check('column insertion expands crossing spans and never clones source IDs',()=>{
                const left=edit(merged,'/0',{type:'add-column',index:0}).source;equal(coverage(left),[['h','h','h','i'],['a','','b','b'],['a','','c','d'],['e','','f','g']]);once(left,['colA','colB','colC','a','inside','b']);equal(parse(left).querySelector('#h').getAttribute('column-span'),'3');
                const right=edit(merged,'/0',{type:'add-column',index:1}).source;equal(coverage(right),[['h','h','','i'],['a','b','b','b'],['a','c','','d'],['e','f','','g']]);once(right,['a','b','c','f']);equal(parse(right).querySelector('#b').getAttribute('column-span'),'3');
            });
            check('deleting a covered row shrinks the original vertical span exactly once',()=>{
                const source=edit(merged,'/0',{type:'remove-row',index:2}).source;equal(coverage(source),[['h','h','i'],['a','b','b'],['e','f','g']]);equal(parse(source).querySelector('#a').getAttribute('row-span'),null);once(source,['a','inside','b']);equal(parse(source).querySelectorAll('#c,#d,#rowB').length,0);
            });
            check('deleting a span anchor row moves its original content and preserves references',()=>{
                const referenced=merged.replace('</document>','<p><page-reference target="inside" width="24"/></p></document>'),source=edit(referenced,'/0',{type:'remove-row',index:1}).source;
                equal(coverage(source),[['h','h','i'],['a','c','d'],['e','f','g']]);once(source,['a','inside','c','d']);equal(parse(source).querySelector('#a').getAttribute('row-span'),null);equal(parse(source).querySelector('#inside').outerHTML,parse(merged).querySelector('#inside').outerHTML);equal(parse(source).querySelectorAll('#b,#rowA').length,0);
                for(const id of ['b','rowA'])refuses(()=>edit(merged.replace('</document>','<p><page-reference target="'+id+'" width="24"/></p></document>'),'/0',{type:'remove-row',index:1}),/引用/);
            });
            check('deleting a physical column shrinks horizontal spans and preserves surviving anchors',()=>{
                const middle=edit(merged,'/0',{type:'remove-column',index:1}).source;equal(coverage(middle),[['h','i'],['a','b'],['a','d'],['e','g']]);once(middle,['a','inside','b','h']);equal(parse(middle).querySelector('#b').getAttribute('column-span'),null);equal(parse(middle).querySelectorAll('#c,#f,#colB').length,0);
                const first=edit(merged,'/0',{type:'remove-column',index:0}).source;equal(coverage(first),[['h','i'],['b','b'],['c','d'],['f','g']]);once(first,['h','b','c','d']);equal(parse(first).querySelectorAll('#a,#inside').length,0);
                for(const id of ['inside','colA'])refuses(()=>edit(merged.replace('</document>','<p><page-reference target="'+id+'" width="24"/></p></document>'),'/0',{type:'remove-column',index:0}),/引用/);
            });
            check('merged data headers edit columns without adding records or crossing the header boundary',()=>{
                const source='<document><rows id="r" items="records" as="record"><columns><column/><column/><column/></columns><header><cell id="title" column-span="2">Title</cell><cell id="last">Last</cell></header><row><cell id="code">${record.code}</cell><cell id="name">${record.name}</cell><cell id="value">${record.value}</cell></row></rows></document>';
                const added=edit(source,'/0',{type:'add-column',index:0}).source;equal(coverage(added),[['title','title','title','last'],['code','','name','value']]);once(added,['title','code','name','value']);equal(parse(added).querySelector('rows').getAttribute('items'),'records');equal(parse(added).querySelectorAll('rows > row').length,1);
                const removed=edit(source,'/0',{type:'remove-column',index:0}).source;equal(coverage(removed),[['title','last'],['name','value']]);refuses(()=>edit(source,'/0',{type:'add-row'}),/绑定数组/);refuses(()=>edit(source,'/0',{type:'remove-row'}),/绑定数组/);
                const crossed=source.replace('column-span="2"','row-span="2"');equal(read(crossed,'/0').editable,false);refuses(()=>edit(crossed,'/0',{type:'add-column'}),/边界/);
            });
            check('merged-grid growth remains bounded before producing a candidate',()=>{
                const source='<document><table><columns>'+('<column/>'.repeat(10000))+'</columns><row><cell column-span="10000" row-span="10">Bounded</cell></row>'+('<row/>'.repeat(9))+'</table></document>';
                refuses(()=>edit(source,'/0',{type:'add-column',index:0}),/预算/);refuses(()=>edit(source,'/0',{type:'add-row',index:0}),/预算/);
                const repeatedStyle='<document><table><columns><column/><column/></columns><row><cell column-span="2" data-note="'+('x'.repeat(600000))+'">Keep</cell></row></table></document>';
                refuses(()=>edit(repeatedStyle,'/0',{type:'add-row'}),/预算/);
            });
            check('insertion before boundaries and fully covered anchor deletion retain a valid selection',()=>{
                const before=edit(merged,'/0',{type:'add-column',index:0,after:false}).source;equal(coverage(before),[['','h','h','i'],['','a','b','b'],['','a','c','d'],['','e','f','g']]);
                const rowBefore=edit(merged,'/0',{type:'add-row',index:1,after:false}).source;equal(coverage(rowBefore),[['h','h','i'],['','',''],['a','b','b'],['a','c','d'],['e','f','g']]);
                const allCovered='<document><table><columns><column/><column/></columns><row><cell id="whole" column-span="2" row-span="3">Keep</cell></row><row/><row/></table></document>';
                const removed=edit(allCovered,'/0/1/0',{type:'remove-row'});equal(coverage(removed.source),[['whole','whole'],['whole','whole']]);once(removed.source,['whole']);equal(removed.paths,['/0/1/0']);
            });
            check('block cells expose editable children and deletion protects external references',()=>{
                const source='<document><table id="t"><columns><column weight="1"/></columns><row><cell><p id="linked">Linked</p></cell></row><row><cell>Other</cell></row></table><p><page-reference target="linked" width="24"/></p></document>';
                equal(read(source,'/0').textPaths,['/0/1/0/0','/0/2/0']);refuses(()=>edit(source,'/0/1/0/0',{type:'remove-row'}),/引用/);equal(parse(source).querySelector('#linked').textContent,'Linked');
            });
            check('invalid grids, paths, declarations and budgets refuse without a candidate',()=>{
                const holes='<document><table><columns><column weight="1"/><column weight="1"/></columns><row><cell>A</cell></row></table></document>';
                equal(read(holes,'/0').editable,false);refuses(()=>edit(holes,'/0',{type:'add-column'}),/未覆盖/);
                for(const source of ['<!DOCTYPE document><document/>','<document>','<document><p id="x"/><p id="x"/></document>'])refuses(()=>read(source,''));
                refuses(()=>read(table,'/01'));refuses(()=>read(table,'/9'));refuses(()=>edit(table,'/0',{type:'add-column',index:-1}));refuses(()=>edit(table,'/0',{type:'add-row',index:10}));
                refuses(()=>read('<document>'+('<each>'.repeat(49))+'<p/>'+('</each>'.repeat(49))+'</document>',''));refuses(()=>read('<document>'+(' '.repeat(1_000_000))+'</document>',''));
            });
            const completed=[];for(const [name,work] of cases){work();completed.push(name);}return completed;
        });
        for(const name of names)await t.test(name,()=>{});
    }finally{await browser.close();}
});
