// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('grid gestures discard invalidated preview geometry',{timeout:60000},async t=>{
    const code=(await build({stdin:{contents:`
        import React from 'react';
        import {createRoot} from 'react-dom/client';
        import {flushSync} from 'react-dom';
        import {GridCanvas} from './src/gridCanvas';
        const paper=document.getElementById('paper'),root=createRoot(paper);
        let current,applied=[];
        const bridge={snapshot:()=>current,choose:async()=>{},apply:async(...args)=>{applied.push(args);}};
        export function show(value,render=true){current=value;if(render)flushSync(()=>root.render(React.createElement(GridCanvas,{state:current,page:0,paper,bridge,editing:false,hoveredItem:null,onEdit:()=>{},onError:error=>{throw error;}})));}
        export function snapshot(){return current;}
        export function changes(){return applied;}
    `,resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'tsx'},bundle:true,format:'iife',globalName:'GridFixture',write:false,target:'es2022'})).outputFiles[0].text;
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try{
        const page=await browser.newPage();
        await page.setContent('<div id="paper" style="position:relative;width:400px;height:500px"></div><div id="studio-grid-context"></div>');
        await page.addScriptTag({content:code});
        await page.evaluate(()=>GridFixture.show({source:'<document><grid id="grid" tracks="12" column-gap="12"><item id="left" row="1" column-start="1" column-span="6"><p>A</p></item><item id="right" row="1" column-start="7" column-span="6"><p>B</p></item></grid></document>',selectedPaths:['/0/0'],currentPage:0,session:1,revision:1,busy:false,pending:false,layoutState:'synced',entries:[],formats:{},pages:[{width:400,height:500,hits:[{id:'grid',path:'/0',x:20,y:60,width:360,height:40},{id:'left',path:'/0/0',x:20,y:60,width:174,height:40},{id:'right',path:'/0/1',x:206,y:60,width:174,height:40}]}]}));
        const divider=page.locator('.studio-grid-divider');
        const drag=async()=>{
            await divider.waitFor();const bounds=await divider.boundingBox();assert.ok(bounds);
            await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();await page.mouse.move(bounds.x+bounds.width/2+31,bounds.y+bounds.height/2);
            await page.locator('[data-grid-drag="boundary"]').waitFor();
        };
        for(const layoutState of ['stale','pending','failed'])await t.test(layoutState+' hides controls and cancels a drag',async()=>{
            await drag();
            await page.evaluate(layoutState=>GridFixture.show({...GridFixture.snapshot(),layoutState,pending:layoutState==='pending'}),layoutState);
            assert.equal(await divider.count(),0);assert.equal(await page.locator('[data-grid-drag="boundary"]').count(),0);
            await page.evaluate(()=>GridFixture.show({...GridFixture.snapshot(),layoutState:'synced',pending:false}));
            await page.mouse.up();assert.equal(await page.evaluate(()=>GridFixture.changes().length),0);
        });
        for(const invalidation of ['failure','session','pages'])await t.test(invalidation+' at pointerup is rejected before rendering',async()=>{
            await drag();
            await page.evaluate(kind=>{const current=GridFixture.snapshot();GridFixture.show({...current,...(kind==='failure'?{layoutState:'failed'}:kind==='session'?{session:current.session+1}:{pages:[...current.pages]})},false);},invalidation);
            await page.mouse.up();assert.equal(await page.evaluate(()=>GridFixture.changes().length),0);
            await page.evaluate(()=>GridFixture.show({...GridFixture.snapshot(),layoutState:'synced'}));
        });
        await t.test('current-page navigation preserves a drag with unchanged layout',async()=>{
            await drag();await page.evaluate(()=>GridFixture.show({...GridFixture.snapshot(),currentPage:1}));
            assert.equal(await page.locator('[data-grid-drag="boundary"]').count(),1);
            await page.keyboard.press('Escape');await page.mouse.up();assert.equal(await page.evaluate(()=>GridFixture.changes().length),0);
            await page.evaluate(()=>GridFixture.show({...GridFixture.snapshot(),currentPage:0}));
        });
        await t.test('a synchronized drag still preserves content and changes the intended pair',async()=>{
            await drag();await page.mouse.up();
            const changes=await page.evaluate(()=>GridFixture.changes());assert.equal(changes.length,1);
            assert.match(changes[0][0],/id="left"[^>]+column-span="7"/);assert.match(changes[0][0],/id="right"[^>]+column-start="8"[^>]+column-span="5"/);
            assert.match(changes[0][0],/<p>A<\/p>/);assert.match(changes[0][0],/<p>B<\/p>/);
        });
    }finally{await browser.close();}
});
