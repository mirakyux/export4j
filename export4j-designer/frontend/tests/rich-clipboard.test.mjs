// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('rich editor clipboard preserves table value boundaries', {timeout:60000}, async t=>{
    const bundle=await build({stdin:{contents:"export {mountRich,toXml,selectPosition} from './rich';export {undo,redo} from 'prosemirror-history';",resolveDir:fileURLToPath(new URL('../src/',import.meta.url)),loader:'ts'},bundle:true,format:'iife',globalName:'RichClipboard',write:false,target:'es2022'});
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try{
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.request().url()==='https://clipboard.invalid/'?route.fulfill({contentType:'text/html',body:'<!doctype html><html><body></body></html>'}):route.abort());
        const page=await context.newPage();page.setDefaultTimeout(10000);await page.goto('https://clipboard.invalid/');await page.addScriptTag({content:bundle.outputFiles[0].text});
        const paste=input=>page.evaluate(({html,plain,initial='',inlineOnly=true,image=false,editable=true,history=false})=>{
            const host=document.body.appendChild(document.createElement('div')),original=new DOMParser().parseFromString('<cell/>','application/xml').documentElement;
            original.textContent=initial;let changed=0,images=0;
            const view=RichClipboard.mountRich(host,original,{changed(){changed++;},paste(){images++;},commit(){},merge(){return false;},inlineOnly,editable:()=>editable});
            try{
                RichClipboard.selectPosition(view,'end');const data=new DataTransfer();
                if(html!==undefined)data.setData('text/html',html);if(plain!==undefined)data.setData('text/plain',plain);
                if(image)data.items.add(new File([new Uint8Array([137,80,78,71])],'clip.png',{type:'image/png'}));
                view.dom.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));
                const snapshot=()=>RichClipboard.toXml(view,original,[]).map(node=>({xml:new XMLSerializer().serializeToString(node),text:node.textContent}));
                const after=snapshot(),transactions=changed;let undone,redone;
                if(history){RichClipboard.undo(view.state,view.dispatch);undone=snapshot();RichClipboard.redo(view.state,view.dispatch);redone=snapshot();}
                return {after,transactions,images,undone,redone};
            }finally{view.destroy();host.remove();}
        },input);
        const html='<table><tr><td>ONE</td><td><b>TWO</b></td></tr><tr><td>THREE</td><td>FOUR</td></tr></table>',plain='ONE\tTWO\nTHREE\tFOUR';
        await t.test('table HTML uses the supplied TSV text without losing separators',async()=>{
            const expected=await paste({plain}),actual=await paste({html,plain});
            assert.equal(expected.after[0].text,plain);assert.deepEqual(actual.after,expected.after);assert.equal(actual.transactions,1);
        });
        await t.test('HTML-only tables retain row and cell boundaries including empty cells',async()=>{
            assert.equal((await paste({html})).after[0].text,plain);
            const empty='<table><thead><tr><th></th><th>B</th></tr></thead><tbody><tr><td>C</td><td></td></tr></tbody></table>';
            assert.equal((await paste({html:empty})).after[0].text,'\tB\nC\t');
        });
        await t.test('HTML-only table cells retain explicit text breaks',async()=>{
            const result=await paste({html:'<table><tr><td><p>First</p><p>Second<br>Third</p></td><td>Right</td></tr></table>'});
            assert.equal(result.after[0].text,'First\nSecond\nThird\tRight');
        });
        await t.test('table fallback participates in one ordinary undo and redo step',async()=>{
            const result=await paste({html,plain,initial:'Original:',history:true});
            assert.equal(result.after[0].text,'Original:'+plain);assert.equal(result.transactions,1);assert.equal(result.undone[0].text,'Original:');assert.deepEqual(result.redone,result.after);
        });
        await t.test('ordinary allowed HTML formatting remains rich',async()=>{
            const result=await paste({html:'<p><strong>Bold</strong> <u>Under</u> <span style="color:rgb(18,52,86);font-size:14pt">Color</span> <a href="https://example.invalid/">Link</a></p>',plain:'ignored'});
            assert.match(result.after[0].xml,/<span role="bold">Bold<\/span>/);assert.match(result.after[0].xml,/<span underline="true">Under<\/span>/);assert.match(result.after[0].xml,/color="#123456"/);assert.match(result.after[0].xml,/size="14"/);assert.match(result.after[0].xml,/<link href="https:\/\/example.invalid\/">Link<\/link>/);
        });
        await t.test('image clipboard handling and read-only refusal keep precedence',async()=>{
            const imageResult=await paste({html,plain,image:true});assert.equal(imageResult.images,1);assert.equal(imageResult.transactions,0);assert.equal(imageResult.after[0].text,'');
            const locked=await paste({html,plain,initial:'Keep',editable:false});assert.equal(locked.after[0].text,'Keep');assert.equal(locked.transactions,0);
        });
    }finally{await browser.close();}
});
