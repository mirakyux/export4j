// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

// Browser font ownership and out-of-order loads cannot be verified by TypeScript checks.
test('canvas fonts follow resource changes and discard stale asynchronous loads',{timeout:60000},async t=>{
  const bundle=await build({entryPoints:[fileURLToPath(new URL('../src/fonts.ts',import.meta.url))],bundle:true,
    format:'iife',globalName:'CanvasFonts',write:false,target:'es2022'});
  const fixtures=await Promise.all(['LiberationSans-Regular.ttf','LiberationSerif-Regular.ttf'].map(async name=>
    (await readFile(new URL('../../../assets/fonts/'+name,import.meta.url))).toString('base64')));
  const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
  try{
    const page=await browser.newPage();
    await page.addScriptTag({content:bundle.outputFiles[0].text});
    await page.evaluate(fixtures=>{
      window.fontBuffers=fixtures.map(value=>Uint8Array.from(atob(value),char=>char.charCodeAt(0)).buffer);
      window.measure=()=>{const context=document.createElement('canvas').getContext('2d');context.font='40px "Export4j body"';return context.measureText('MMM iii wide narrow').width;};
    },fixtures);
    await t.test('replace and undo change the actual browser face without accumulating old fonts',async()=>{
      const result=await page.evaluate(async()=>{
        document.fonts.clear();let selected=0,calls=0;const errors=[];
        const unrelated=await new FontFace('Unrelated',fontBuffers[0]).load();document.fonts.add(unrelated);
        const refresh=CanvasFonts.createFontLoader(async()=>{calls++;return fontBuffers[selected];},error=>errors.push(String(error)));
        await refresh('original',1);const original=measure(),previous=[...document.fonts].filter(font=>font!==unrelated);
        await refresh('original',1);const repeatedCalls=calls;
        selected=1;await refresh('replacement',1);const replacement=measure(),oldRetained=previous.some(font=>document.fonts.has(font));
        selected=0;await refresh('original',1);const undone=measure();
        await refresh('original',2);
        return {original,replacement,undone,repeatedCalls,calls,count:document.fonts.size,oldRetained,unrelated:document.fonts.has(unrelated),errors};
      });
      assert.notEqual(result.original,result.replacement);assert.equal(result.undone,result.original);
      assert.equal(result.repeatedCalls,3);assert.equal(result.calls,12);assert.equal(result.count,4);
      assert.equal(result.oldRetained,false);assert.equal(result.unrelated,true);assert.deepEqual(result.errors,[]);
    });
    await t.test('older completion cannot overwrite the latest resource or report a stale failure',async()=>{
      const result=await page.evaluate(async()=>{
        document.fonts.clear();const releases=[],errors=[];let delayed=true;
        const refresh=CanvasFonts.createFontLoader(()=>delayed?new Promise((resolve,reject)=>releases.push({resolve,reject})):Promise.resolve(fontBuffers[1]),error=>errors.push(String(error)));
        const old=refresh('old',1);delayed=false;await refresh('latest',1);const latest=measure();
        releases[0].reject(new Error('obsolete failure'));releases[1].resolve(fontBuffers[0]);releases[2].resolve(fontBuffers[0]);
        await old;return {latest,after:measure(),count:document.fonts.size,errors};
      });
      assert.equal(result.after,result.latest);assert.equal(result.count,3);assert.deepEqual(result.errors,[]);
    });
    await t.test('removed optional roles and failed required roles do not retain previous resource faces',async()=>{
      const result=await page.evaluate(async()=>{
        document.fonts.clear();let mode=0;const errors=[];
        const refresh=CanvasFonts.createFontLoader(name=>mode===0||mode===1&&name==='Regular.ttf'?Promise.resolve(fontBuffers[0]):Promise.reject(new Error(mode===1?'missing optional':'missing regular')),error=>errors.push(String(error)));
        await refresh('all',1);const previous=[...document.fonts];mode=1;await refresh('regular-only',1);
        const remaining=[...document.fonts].map(font=>[font.family,font.weight]);
        const oldRetained=previous.some(font=>document.fonts.has(font));mode=2;await refresh('failed',1);
        return {remaining,oldRetained,count:document.fonts.size,errors};
      });
      assert.deepEqual(result.remaining,[['Export4j body','400']]);assert.equal(result.oldRetained,false);
      assert.equal(result.count,0);
      assert.deepEqual(result.errors,['Error: missing regular']);
    });
  }finally{await browser.close();}
});
