// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('table borders and explicit inner actions preserve semantic content',{timeout:60000},async t=>{
    const bundles=await Promise.all(['tableWidths','blocks'].map(async name=>({name,code:(await build({entryPoints:[fileURLToPath(new URL('../src/'+name+'.ts',import.meta.url))],bundle:true,format:'iife',globalName:name,write:false,target:'es2022'})).outputFiles[0].text})));
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try{
        const page=await browser.newPage();await page.route('http://127.0.0.1/**',route=>route.fulfill({contentType:'text/html',body:'<html><body></body></html>'}));await page.goto('http://127.0.0.1/table-width-fixture');for(const bundle of bundles)await page.addScriptTag({content:bundle.code});
        const completed=await page.evaluate(()=>{
            const cases=[],check=(name,work)=>cases.push([name,work]),equal=(a,b)=>{if(JSON.stringify(a)!==JSON.stringify(b))throw Error(JSON.stringify(a)+' != '+JSON.stringify(b));},refuses=work=>{try{work();}catch{return;}throw Error('Expected rejection');};
            const source='<document><table id="outer"><columns><column weight="1"/><column weight="2"/></columns><row><cell>A<span role="bold">B</span></cell><cell>C</cell></row></table></document>';
            const snapshot={source,currentPage:0,pages:[{width:594,height:842,hits:[
                {path:'/0',x:42,y:60,width:510,height:40},
                {path:'/0/1/0',x:51,y:68,width:160,height:16,tableCell:{x:42,y:60,width:180,height:40,column:0,columnSpan:1}},
                {path:'/0/1/1',x:231,y:68,width:310,height:16,tableCell:{x:222,y:60,width:330,height:40,column:1,columnSpan:1}}
            ]}]};
            check('engine borders use padded cell rectangles rather than text widths',()=>equal(tableWidths.tableGeometry(snapshot,'/0/1/0',0),{path:'/0',left:42,top:60,width:510,height:40,widths:[180,330]}));
            check('resizing keeps pair width and rich content and changes only column sizing',()=>{
                const candidate=tableWidths.resizeTableColumns(snapshot,'/0/1/0',[180,330],1,200),document=new DOMParser().parseFromString(candidate,'application/xml');
                equal([...document.querySelectorAll('column')].map(column=>column.getAttribute('width')),['200','310']);equal(document.querySelectorAll('column[weight]').length,0);
                equal(document.querySelector('span').getAttribute('role'),'bold');equal([...document.querySelectorAll('cell')].map(cell=>cell.textContent),['AB','C']);equal(snapshot.source,source);
            });
            check('invalid and excessively narrow widths reject without producing a candidate',()=>{
                for(const width of [NaN,Infinity,-1,0,1,510,511])refuses(()=>tableWidths.resizeTableColumns(snapshot,'/0',[180,330],1,width));
                refuses(()=>tableWidths.resizeTableColumns(snapshot,'/0',[180],1,100));refuses(()=>tableWidths.resizeTableColumns(snapshot,'/0',[180,330],0,100));equal(snapshot.source,source);
            });
            check('fully merged static weights resolve hidden borders without using text geometry',()=>{
                const merged={...snapshot,source:source.replace('<cell>A<span role="bold">B</span></cell><cell>C</cell>','<cell column-span="2">Merged</cell>'),pages:[{width:594,height:842,hits:[{path:'/0/1/0',tableCell:{x:42,y:60,width:510,height:40,column:0,columnSpan:2}}]}]};
                equal(tableWidths.tableGeometry(merged,'/0',0).widths,[170,340]);
                equal(tableWidths.tableGeometry({...merged,source:merged.source.replace('weight="1"','min-width="24"')},'/0',0),null);
            });
            check('dynamic and rotated tables expose no guessed draggable border',()=>{
                equal(tableWidths.tableGeometry({...snapshot,source:source.replace('weight="1"','weight="${ratio}"')},'/0',0),null);
                equal(tableWidths.tableGeometry({...snapshot,source:source.replace('id="outer"','id="outer" rotation="30"')},'/0',0),null);
                for(const layoutState of ['failed','stale','pending'])equal(tableWidths.tableGeometry({...snapshot,layoutState},'/0',0),null);
                equal(tableWidths.tableGeometry({...snapshot,pages:[{...snapshot.pages[0],hits:[...snapshot.pages[0].hits,{path:'/0',x:42,y:160,width:510,height:40}]}]},'/0',0),null);
            });
            const nested='<document><table id="t"><columns><column weight="1"/></columns><row><cell><p id="first">A<span role="bold">B</span></p><p id="second">Second</p></cell></row></table></document>',inner={source:nested,selectedPaths:['/0/1/0/0']};
            check('explicit inner copy copies one paragraph while component copy retains whole table scope',()=>{
                const exact=blocks.duplicateBlocks(inner,'content'),document=new DOMParser().parseFromString(exact.source,'application/xml');
                equal(document.querySelectorAll('table').length,1);equal([...document.querySelectorAll('p')].map(node=>node.textContent),['AB','AB','Second']);equal(document.querySelectorAll('p#first').length,1);equal(document.querySelectorAll('span[role="bold"]').length,2);
                equal(new DOMParser().parseFromString(blocks.duplicateBlocks(inner).source,'application/xml').querySelectorAll('table').length,2);
            });
            check('explicit inner delete preserves container and other content and protects references',()=>{
                const exact=blocks.removeContentBlock(inner,'/0/1/0/0'),document=new DOMParser().parseFromString(exact.source,'application/xml');equal(document.querySelectorAll('table').length,1);equal([...document.querySelectorAll('p')].map(node=>node.textContent),['Second']);
                refuses(()=>blocks.removeContentBlock({...inner,source:nested.replace('</document>','<p><page-reference target="first" width="24"/></p></document>')},'/0/1/0/0'));
                refuses(()=>blocks.removeContentBlock({source:'<document><columns count="2"><p>Only</p></columns></document>'},'/0/0'));
                for(const content of ['<p>Only</p>','<table><columns><column weight="1"/></columns><row><cell>Only</cell></row></table>'])refuses(()=>blocks.removeContentBlock({source:'<document><grid tracks="12"><item row="1" column-start="1" column-span="12">'+content+'</item></grid></document>'},'/0/0/0'));
                refuses(()=>blocks.duplicateBlocks({source:'<document><list items="items" as="item"><bullet>${item.name}</bullet></list></document>',selectedPaths:['/0/0']},'content'));
            });
            return cases.map(([name,work])=>{work();return name;});
        });
        for(const name of completed)await t.test(name,()=>{});
    }finally{await browser.close();}
});

test('table dividers follow preview state and discard invalidated drags',{timeout:60000},async t=>{
    const code=(await build({stdin:{contents:`
        import React from 'react';
        import {createRoot} from 'react-dom/client';
        import {flushSync} from 'react-dom';
        import {TableCanvas} from './src/tableCanvas';
        const paper=document.getElementById('paper'),root=createRoot(paper);
        let current,applied=[];
        const bridge={snapshot:()=>current,apply:async(...args)=>{applied.push(args);}};
        export function show(value,render=true){current=value;if(render)flushSync(()=>root.render(React.createElement(TableCanvas,{state:current,page:0,paper,bridge,editing:false,onError:error=>{throw error;}})));}
        export function snapshot(){return current;}
        export function changes(){return applied.length;}
    `,resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'tsx'},bundle:true,format:'iife',globalName:'TableCanvasFixture',write:false,target:'es2022'})).outputFiles[0].text;
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try{
        const page=await browser.newPage();
        await page.setContent('<div id="paper" style="position:relative;width:594px;height:842px"></div>');
        await page.addScriptTag({content:code});
        await page.evaluate(()=>TableCanvasFixture.show({source:'<document><table id="table"><columns><column weight="1"/><column weight="1"/></columns><row><cell>A</cell><cell>B</cell></row></table></document>',selectedPaths:['/0'],currentPage:0,session:1,revision:1,busy:false,pending:false,layoutState:'synced',entries:[],formats:{},pages:[{width:594,height:842,hits:[{path:'/0',x:42,y:60,width:510,height:40},{path:'/0/1/0',tableCell:{x:42,y:60,width:255,height:40,column:0,columnSpan:1}},{path:'/0/1/1',tableCell:{x:297,y:60,width:255,height:40,column:1,columnSpan:1}}]}]}));
        const divider=page.locator('.studio-table-divider');
        for(const layoutState of ['stale','pending','failed'])await t.test(layoutState+' hides the previous border and cancels its active drag',async()=>{
            await divider.waitFor();const bounds=await divider.boundingBox();assert.ok(bounds);
            await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();await page.mouse.move(bounds.x+bounds.width/2+30,bounds.y+bounds.height/2);
            await page.locator('.studio-table-drag-label').waitFor();
            await page.evaluate(layoutState=>TableCanvasFixture.show({...TableCanvasFixture.snapshot(),layoutState}),layoutState);
            assert.equal(await divider.count(),0);
            await page.evaluate(()=>TableCanvasFixture.show({...TableCanvasFixture.snapshot(),layoutState:'synced'}));await divider.waitFor();
            assert.equal(await page.locator('.studio-table-drag-label').count(),0);
            await page.mouse.up();assert.equal(await page.evaluate(()=>TableCanvasFixture.changes()),0);
        });
        await t.test('a preview failure observed at pointerup rejects the late commit before React rerenders',async()=>{
            const bounds=await divider.boundingBox();assert.ok(bounds);
            await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();await page.mouse.move(bounds.x+bounds.width/2+30,bounds.y+bounds.height/2);
            await page.locator('.studio-table-drag-label').waitFor();
            await page.evaluate(()=>TableCanvasFixture.show({...TableCanvasFixture.snapshot(),layoutState:'failed'},false));
            await page.mouse.up();assert.equal(await page.evaluate(()=>TableCanvasFixture.changes()),0);
        });
    }finally{await browser.close();}
});
