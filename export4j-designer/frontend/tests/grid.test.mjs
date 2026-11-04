// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

// Keep the XML implementation in a real browser: its DOMParser, Element and
// XMLSerializer semantics are part of the designer's behavior. No server,
// generated bundle, fixed output directory or network access is needed.
test('grid XML edits preserve content, references and source scopes', {timeout:60000}, async t=>{
    const bundle=await build({
        entryPoints:[fileURLToPath(new URL('../src/grid.ts',import.meta.url))],
        bundle:true,format:'iife',globalName:'GridLogic',write:false,target:'es2022'
    });
    const browser=await chromium.launch({
        channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000
    });
    try {
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();
        page.setDefaultTimeout(10000);
        await page.addScriptTag({content:bundle.outputFiles[0].text});
        const names=await page.evaluate(()=>{
            const {gridContext,gridEdit,gridFromSelection,gridSelectionReason}=GridLogic;
            const cases=new Map();
            const check=(name,work)=>cases.set(name,work);
            const equal=(actual,expected,label='value')=>{
                if(JSON.stringify(actual)!==JSON.stringify(expected)){
                    throw new Error(label+': expected '+JSON.stringify(expected)+', actual '+JSON.stringify(actual));
                }
            };
            const assert=(condition,label)=>{if(!condition)throw new Error(label);};
            const refuses=(work,label)=>{
                let error;
                try {work();}catch(value){error=value;}
                assert(error instanceof Error,label+' must reject');
            };
            const parse=source=>new DOMParser().parseFromString(source,'application/xml');
            const snapshot=(source,selectedPaths=[])=>({source,selectedPaths});
            const item=(id,row,start,span,body)=>`<item id="${id}" row="${row}" column-start="${start}" column-span="${span}">${body??`<p id="${id}-p">${id}</p>`}</item>`;
            const grid=items=>`<document version="1"><grid id="g" tracks="12">${items}</grid></document>`;
            const halves=grid(item('a',1,1,6)+item('b',1,7,6));
            const positions=source=>[...parse(source).querySelector('grid').children].map(node=>[
                node.id,+node.getAttribute('row'),+node.getAttribute('column-start'),+node.getAttribute('column-span')
            ]);
            const contentIds=source=>[...parse(source).querySelectorAll('p,table')].map(node=>node.id);
            const uniqueIds=source=>{
                const ids=[...parse(source).querySelectorAll('[id]')].map(node=>node.id);
                equal(ids.length,new Set(ids).size,'unique source IDs');
            };

            check('context follows original item and descendant paths',()=>{
                const context=gridContext(halves,'/0/1/0');
                equal(context.itemPath,'/0/1');
                equal(context.items.map(value=>[value.id,value.start,value.span]),[['a',1,6],['b',7,6]]);
                equal(gridContext('<document/>',''),null);
            });

            check('layout thirds grows with unique empty paragraphs',()=>{
                const result=gridEdit(snapshot(halves),'/0',{type:'layout-row',layout:'thirds'});
                equal(positions(result.source).map(value=>value.slice(2)),[[1,4],[5,4],[9,4]]);
                equal(contentIds(result.source).slice(0,2),['a-p','b-p']);
                equal(parse(result.source).querySelector('grid').lastElementChild.textContent,'');
                uniqueIds(result.source);
            });

            check('reducing a row preserves content order and retargets removed wrapper references',()=>{
                const source=grid(item('a',1,1,4)+item('b',1,5,4)+item('c',1,9,4))
                    .replace('</document>','<p id="ref"><page-reference target="c"/><cross-reference target="c"/></p></document>');
                const result=gridEdit(snapshot(source),'/0',{type:'layout-row',layout:'halves'});
                const document=parse(result.source);
                equal(contentIds(result.source),['a-p','b-p','c-p','ref']);
                equal([...document.querySelectorAll('[target]')].map(node=>node.getAttribute('target')),['c-p','c-p']);
                equal([...document.querySelectorAll('item')].map(node=>node.id),['a','b']);
                equal([...document.querySelector('#b').children].map(node=>node.id),['b-p','c-p']);
                uniqueIds(result.source);
            });

            check('splitting preserves rich content and creates only an empty right item',()=>{
                const source=grid(item('a',1,3,8,'<p id="p">X<span color="#123456">Y</span><image ref="image" width="8" height="8"/><page-number width="12"/></p>'));
                const result=gridEdit(snapshot(source),'/0/0',{type:'split-item'});
                const document=parse(result.source);
                equal(positions(result.source).map(value=>value.slice(2)),[[3,4],[7,4]]);
                equal(document.querySelector('#p').textContent,'XY');
                equal(document.querySelector('span').getAttribute('color'),'#123456');
                equal(document.querySelectorAll('image,page-number').length,2);
                equal(document.querySelector('grid').lastElementChild.textContent,'');
                uniqueIds(result.source);
            });

            check('merging adjacent items keeps rich blocks in reading order',()=>{
                const result=gridEdit(snapshot(halves),'/0/0',{type:'merge-next'});
                equal(positions(result.source),[['a',1,1,12]]);
                equal(contentIds(result.source),['a-p','b-p']);
                equal(parse(result.source).querySelector('#a').textContent,'ab');
            });

            check('unsafe wrapper reference relocation rejects without changing the snapshot',()=>{
                refuses(()=>gridEdit(snapshot(grid(item('a',1,1,4)+item('b',1,7,6))),'/0/0',{type:'merge-next'}),'gapped merge');
                const source=grid(item('a',1,1,6)+item('b',1,7,6,'<if test="show"><then><p id="bp">B</p></then></if>'))
                    .replace('</document>','<p id="ref"><page-reference target="b"/></p></document>');
                const current=snapshot(source);
                refuses(()=>gridEdit(current,'/0/0',{type:'merge-next'}),'dynamic first anchor');
                equal(current.source,source,'snapshot after refusal');
                const dynamic=halves.replace('</document>','<p id="ref"><page-reference target="${chosen}"/></p></document>');
                refuses(()=>gridEdit(snapshot(dynamic),'/0/0',{type:'merge-next'}),'interpolated target');
                const anonymous=halves.replace('id="b-p"','').replace('</document>','<p id="ref"><page-reference target="b"/></p></document>');
                refuses(()=>gridEdit(snapshot(anonymous),'/0/0',{type:'merge-next'}),'anonymous first anchor');
            });

            check('boundaries update both spans and reject empty or fractional tracks',()=>{
                const result=gridEdit(snapshot(halves),'/0/0',{type:'boundary',leftId:'a',rightId:'b',start:5});
                equal(positions(result.source),[['a',1,1,4],['b',1,5,8]]);
                for(const start of [1,13,1.5]){
                    refuses(()=>gridEdit(snapshot(halves),'/0/0',{type:'boundary',leftId:'a',rightId:'b',start}),'invalid boundary '+start);
                }
            });

            check('anonymous grid items use their source paths for boundary operations',()=>{
                const source=halves.replace('id="a"','').replace('id="b"','');
                equal(gridContext(source,'/0').items.map(value=>value.id),['/0/0','/0/1']);
                const result=gridEdit(snapshot(source),'/0/0',{type:'boundary',leftId:'/0/0',rightId:'/0/1',start:9});
                equal(positions(result.source).map(value=>value.slice(2)),[[1,8],[9,4]]);
                equal(contentIds(result.source),['a-p','b-p']);
            });

            check('swap exchanges complete geometry across rows and refuses another grid',()=>{
                const source=grid(item('a',1,1,12)+item('b',5,4,6));
                const result=gridEdit(snapshot(source),'/0/0',{type:'swap',target:'/0/1'});
                equal(positions(result.source),[['b',1,1,12],['a',5,4,6]]);
                equal(contentIds(result.source),['b-p','a-p']);
                const other=halves.replace('</document>','<grid id="other">'+item('c',1,1,12)+'</grid></document>');
                refuses(()=>gridEdit(snapshot(other),'/0/0',{type:'swap',target:'/1/0'}),'cross-grid swap');
            });

            check('adding a row shifts only the consecutive occupied row coordinates',()=>{
                const source=grid(item('a',1,1,12)+item('b',2,1,12)+item('c',7,1,12));
                const result=gridEdit(snapshot(source),'/0/0',{type:'add-row',layout:'thirds',afterRow:1});
                const document=parse(result.source);
                equal(positions(result.source).map(value=>value[1]),[1,2,2,2,3,7]);
                equal(document.querySelector('#b').getAttribute('row'),'3');
                equal(document.querySelector('#c').getAttribute('row'),'7');
                equal([...document.querySelectorAll('item[row="2"]')].map(node=>node.textContent),['','','']);
                uniqueIds(result.source);
            });

            check('dynamic item scopes stay read only without flattening their children',()=>{
                for(const [scope,content] of [
                    ['each','<each items="rows" as="row">'+item('a',1,1,12)+'</each>'],
                    ['if','<if test="show"><then>'+item('a',1,1,12)+'</then></if>'],
                    ['include','<include ref="fragment"/>']
                ]){
                    const source=grid(content),current=snapshot(source);
                    const context=gridContext(source,'/0');
                    equal(context.editable,false,scope+' editability');
                    assert(context.reason.includes('作用域'),scope+' reason');
                    refuses(()=>gridEdit(current,'/0',{type:'add-row',layout:'full'}),scope+' mutation');
                    equal(current.source,source,scope+' snapshot after refusal');
                }
            });

            check('dynamic content inside a static item preserves bindings and comments',()=>{
                const source=grid(item('a',1,1,12,'<!--before--><each items="texts" as="text"><p id="p">${text}</p></each><!--after-->'));
                const result=gridEdit(snapshot(source),'/0/0',{type:'split-item'});
                equal(parse(result.source).querySelector('each').getAttribute('items'),'texts');
                assert(result.source.includes('${text}'),'binding text survives');
                assert(result.source.includes('<!--before-->')&&result.source.includes('<!--after-->'),'comments survive');
                equal(parse(result.source).querySelectorAll('#p').length,1);
            });

            check('selected adjacent blocks move once in source order with original IDs and styles',()=>{
                const source='<document version="1"><p id="a">A<span color="#123456">Z</span></p><!--between--><table id="b"><columns><column weight="1"/></columns><row><cell>B</cell></row></table><p id="tail">T</p></document>';
                const current=snapshot(source,['/1','/0']);
                equal(gridSelectionReason(current),null);
                const result=gridFromSelection(current,'halves'),document=parse(result.source);
                equal(contentIds(result.source),['a','b','tail']);
                equal(document.querySelectorAll('#a').length,1);
                equal(document.querySelector('#a').parentElement.tagName,'item');
                equal(document.querySelector('#tail').parentElement.tagName,'document');
                equal(document.querySelector('span').getAttribute('color'),'#123456');
                equal(result.ids.length,1);equal(result.paths,['/0']);
                equal(current.source,source,'input snapshot stays unchanged');
                assert(result.source.includes('<!--between-->'),'outside comment survives');
                uniqueIds(result.source);
            });

            check('selection distribution appends overflow blocks and leaves extra items empty',()=>{
                const source='<document><p id="a">A</p><p id="b">B</p><p id="c">C</p></document>';
                const result=gridFromSelection(snapshot(source,['/0','/1','/2']),'halves');
                equal([...parse(result.source).querySelectorAll('item')].map(node=>[...node.children].map(child=>child.id)),[['a'],['b','c']]);
                const thirds=gridFromSelection(snapshot(source,['/0','/1']),'thirds');
                equal([...parse(thirds.source).querySelectorAll('item')].map(node=>node.textContent),['A','B','']);
                uniqueIds(thirds.source);
            });

            check('nonadjacent selections, notes and unsupported pagination policies reject',()=>{
                const source='<document><p id="a">A</p><p id="b">B</p><p id="c">C</p></document>';
                for(const paths of [['/0'],['/0','/2']]){
                    assert(gridSelectionReason(snapshot(source,paths))!==null,'invalid selection explanation');
                    refuses(()=>gridFromSelection(snapshot(source,paths),'halves'),'invalid selection');
                }
                const noted=source.replace('</document>','<notes><note id="n" paragraph="a">Note</note></notes></document>');
                refuses(()=>gridFromSelection(snapshot(noted,['/0','/1']),'halves'),'note anchor');
                for(const attribute of ['keep-with-next','page-break-before']){
                    const current=source.replace('id="a"','id="a" '+attribute+'="true"');
                    refuses(()=>gridFromSelection(snapshot(current,['/0','/1']),'halves'),attribute);
                }
            });

            check('unsafe XML declarations and duplicate IDs reject before editing',()=>{
                refuses(()=>gridContext('<!DOCTYPE document [<!ENTITY a "x">]><document/>',''),'doctype');
                refuses(()=>gridContext(halves.replace('id="b"','id="a"'),'/0'),'duplicate IDs');
            });

            check('notes bound to paragraphs inside a selected table refuse before moving content',()=>{
                const source='<document><table id="a"><columns><column weight="1"/></columns><row><cell><p id="in-table">A</p></cell></row></table><p id="b">B</p><notes><note id="n" paragraph="in-table">Note</note></notes></document>';
                const current=snapshot(source,['/0','/1']);
                assert(gridSelectionReason(current)!==null,'table paragraph note explanation');
                refuses(()=>gridFromSelection(current,'halves'),'table paragraph note');
                equal(current.source,source,'table snapshot after refusal');
            });

            check('one-track items refuse splitting and incompatible row presets',()=>{
                refuses(()=>gridEdit(snapshot(grid(item('a',1,1,1))),'/0/0',{type:'split-item'}),'one-track split');
                const source=grid(item('a',1,1,1)).replace('tracks="12"','tracks="1"');
                refuses(()=>gridEdit(snapshot(source),'/0',{type:'layout-row',layout:'halves'}),'one-track halves');
            });
            globalThis.GridChecks=cases;
            return [...cases.keys()];
        });
        for(const name of names){
            await t.test(name,{timeout:10000},()=>page.evaluate(name=>globalThis.GridChecks.get(name)(),name));
        }
    } finally {
        await browser.close();
    }
});
