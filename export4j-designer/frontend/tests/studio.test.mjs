// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {chromium} from 'playwright';

const repository=fileURLToPath(new URL('../../../',import.meta.url));
const output=path.resolve(repository,process.env.EXPORT4J_STUDIO_OUTPUT||'build/verification/designer-studio/browser');
const suppliedOrigin=process.env.EXPORT4J_STUDIO_URL;
const scenarioFilter=process.env.EXPORT4J_STUDIO_SCENARIOS?new RegExp(process.env.EXPORT4J_STUDIO_SCENARIOS):undefined;
const source=`<document version="1"><page width="420" height="600" margin="30"/>
<header height="24"><p id="header">Header</p></header><footer height="24"><p id="footer">Footer</p></footer>
<p id="first" line-height="18">Hello <span color="#224488">mixed</span> \${name}</p>
<p id="second" line-height="18">Second paragraph</p>
<table id="table"><columns><column weight="1"/><column weight="1"/></columns><row><cell>Cell A</cell><cell>Cell B</cell></row></table>
</document>`;

test('canvas editing keeps template semantics and engine output', {timeout:360000}, async t=>{
    await mkdir(output,{recursive:true});
    const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
    const child=suppliedOrigin?undefined:spawn(java,['-Djava.awt.headless=true','-cp',path.join(repository,'export4j-designer/build/install/export4j-designer/lib/*'),
        'com.mirakyux.export4j.designer.DesignerApp','--port=0'],{windowsHide:true});
    let logs='',browser;
    try {
        const origin=suppliedOrigin||await new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>reject(new Error('Designer startup timed out: '+logs)),20000);
            child.stdout.on('data',value=>{logs+=value;const found=logs.match(/http:\/\/127\.0\.0\.1:\d+/);if(found){clearTimeout(timer);resolve(found[0]);}});
            child.stderr.on('data',value=>{logs+=value;});child.once('error',error=>{clearTimeout(timer);reject(error);});
            child.once('exit',code=>{clearTimeout(timer);reject(new Error('Designer exited: '+code+' '+logs));});
        });
        browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true});
        const page=await browser.newPage({viewport:{width:1440,height:1050},acceptDownloads:true});
        page.setDefaultTimeout(12000);
        const errors=[],executed=[];page.on('pageerror',error=>errors.push(error.message));
        const check=async(name,work)=>{if(scenarioFilter&&!scenarioFilter.test(name))return;executed.push(name);return t.test(name,async()=>{try{await work();}catch(error){const details=await page.evaluate(()=>{const editor=document.querySelector('.ProseMirror'),view=editor?.pmViewDesc?.view,selection=document.getSelection(),toolbar=document.querySelector('[aria-label="画布文字格式"]');let coords;try{coords=view?{from:view.coordsAtPos(view.state.selection.from),to:view.coordsAtPos(view.state.selection.to)}:null;}catch(error){coords=String(error);}return {status:$('status').textContent,busy,selection:selectedPaths,images:state.images,active:document.activeElement?.outerHTML,domSelection:selection?.toString(),pmSelection:view?.state.selection.toJSON(),editor:editor?.getBoundingClientRect().toJSON(),coords,toolbar:toolbar?.getBoundingClientRect().toJSON(),toolbarStyle:toolbar?.getAttribute('style'),canvasScroll:document.querySelector('.canvas-scroll')?.scrollTop};});await writeFile(path.join(output,'failure-state-'+name.slice(0,24).replaceAll(' ','-')+'.json'),JSON.stringify(details,null,2));console.error(name,error,details);await page.screenshot({path:path.join(output,'failure-'+name.slice(0,14).replaceAll(' ','-')+'.png')});throw error;}});};
        await page.goto(origin);await page.waitForFunction(()=>typeof state!=='undefined'&&state&&!busy&&pages.length>0);
        const idle=async()=>{await page.waitForFunction(()=>!busy&&!DesignerStudioBridge.snapshot().pending&&pages.length>0&&document.querySelector('.page-image')?.dataset.preview===previewId);};
        const load=async(xml=source,data={name:'Ada'})=>{await page.mouse.up();await page.keyboard.press('Escape');await page.evaluate(async ({xml,data})=>{$('data').value=JSON.stringify(data);await DesignerStudioBridge.apply(xml);await DesignerStudioBridge.flush();await DesignerStudioBridge.select([]);},{xml,data});await idle();await page.locator('.canvas-scroll').evaluate(node=>node.scrollTop=0);};
        const xml=()=>page.evaluate(()=>state.source);
        const object=async id=>{const item=await page.evaluate(id=>entries.find(entry=>entry.id===id),id);assert.ok(item,id);return page.locator(`.studio-object[data-studio-path="${item.path}"]`).first();};
        const edit=async id=>{await (await object(id)).dblclick();await page.locator('.ProseMirror').waitFor();};
        const finish=async()=>{await page.getByRole('button',{name:'完成',exact:true}).click();await idle();};
        const holdDrag=async(locator,dx,dy,kind='move')=>{
            const box=await locator.boundingBox();assert.ok(box);
            await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
            await page.locator(`.studio-surface[data-gesture="${kind}"]`).waitFor({state:'visible'});
            await page.mouse.move(box.x+box.width/2+dx,box.y+box.height/2+dy,{steps:6});await page.mouse.up();await idle();
        };
        const holdSortTo=async(locator,target,after=false)=>{
            await locator.scrollIntoViewIfNeeded();const box=await locator.boundingBox();assert.ok(box);
            await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
            await page.locator('.studio-surface[data-gesture="sort"]').waitFor({state:'visible'});
            const destination=await target.boundingBox();assert.ok(destination);
            await page.mouse.move(destination.x+destination.width/2,destination.y+destination.height*(after?.8:.2),{steps:8});
            await page.locator('.studio-drop-indicator').waitFor({state:'visible'});
            await page.mouse.up();await idle();
        };
        const bodyOrder=()=>page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').documentElement.children].filter(node=>node.id).map(node=>node.id));
        const fixSelection=async()=>{await page.getByLabel('块排版方式').selectOption('FIXED');await idle();};
        await check('single click selects, double click edits and keyboard enters the same editor',async()=>{
            await load();const before=await xml();await (await object('second')).click();
            assert.equal(await page.locator('.ProseMirror').count(),0);assert.equal(await xml(),before);
            assert.match(await (await object('second')).getAttribute('class'),/\bis-selected\b/);
            await page.getByRole('button',{name:'拖动排序',exact:true}).waitFor({state:'visible'});
            assert.equal(await page.getByLabel('调整对象大小 e',{exact:true}).count(),0,'automatic flow must not display fixed-coordinate resize controls');
            assert.equal(await page.getByLabel('正文相遇时').count(),0,'body overlap and avoidance belong only to fixed components');
            await edit('second');await finish();assert.equal(await page.evaluate(()=>selected?.id),'second','finishing an unchanged continuous editor must retain the chosen component');
            await page.locator('.studio-surface').first().focus();await page.keyboard.press('F2');
            await page.locator('.ProseMirror').waitFor();assert.equal(await page.locator('.ProseMirror > p').last().textContent(),'Second paragraph');await finish();
        });
        await check('quick movement and cancelled long press never change geometry',async()=>{
            await load();const before=await xml(),box=await (await object('second')).boundingBox();
            await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
            await page.mouse.move(box.x+box.width/2+18,box.y+box.height/2+8);await page.mouse.up();
            assert.equal(await page.locator('.studio-surface[data-gesture]').count(),0);assert.equal(await xml(),before);
            await (await object('second')).click();
            const target=await (await object('second')).boundingBox();await page.mouse.move(target.x+target.width/2,target.y+target.height/2);await page.mouse.down();
            await page.locator('.studio-surface[data-gesture="sort"]').waitFor({state:'visible'});
            await page.keyboard.press('Escape');await page.mouse.move(target.x+target.width/2+12,target.y+target.height/2+10);await page.mouse.up();
            assert.equal(await page.locator('.studio-surface[data-gesture]').count(),0);assert.equal(await xml(),before);
            await edit('second');await finish();
        });
        await check('drag feedback shows flow placeholders and fixed alignment guides before commit',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><p id="one">One</p><p id="two">Two</p><p id="three">Three</p></document>');
            await (await object('one')).click();const handle=await page.getByRole('button',{name:'拖动排序',exact:true}).boundingBox(),target=await (await object('three')).boundingBox();assert.ok(handle&&target);
            await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();
            await page.locator('.studio-surface[data-gesture="sort"]').waitFor({state:'visible'});
            await page.mouse.move(target.x+target.width/2,target.y+target.height*.8,{steps:6});
            await page.locator('.studio-drop-placeholder').waitFor({state:'visible'});await page.locator('.studio-drag-preview').first().waitFor({state:'visible'});
            assert.deepEqual(await bodyOrder(),['one','two','three'],'a drag preview must not write source order before release');
            await page.screenshot({path:path.join(output,'flow-drag-feedback.png'),fullPage:true});await page.mouse.up();await idle();
            assert.deepEqual(await bodyOrder(),['two','three','one']);assert.doesNotMatch(await xml(),/<foreground|<box/);assert.equal(await page.getByLabel('块排版方式').inputValue(),'FLOW');
            await load();await (await object('second')).click();await fixSelection();
            const before=await xml(),bounds=await (await object('second')).boundingBox();assert.ok(bounds);
            await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();
            await page.locator('.studio-surface[data-gesture="move"]').waitFor({state:'visible'});
            await page.mouse.move(bounds.x+bounds.width/2+3,bounds.y+bounds.height/2+30,{steps:6});
            await page.locator('.studio-snap-guide').first().waitFor({state:'visible'});assert.equal(await xml(),before);assert.equal(await page.getByLabel('块排版方式').inputValue(),'FIXED');
            await page.screenshot({path:path.join(output,'fixed-snap-feedback.png'),fullPage:true});await page.mouse.up();await idle();
            assert.equal(await page.evaluate(()=>Number(new DOMParser().parseFromString(state.source,'application/xml').querySelector('foreground > box').getAttribute('x'))),30,'nearby body alignment must snap to its actual left edge');
        });
        await check('inline Chinese input and formatting preserve variables and mixed runs',async()=>{
            await load();await page.getByRole('button',{name:'精确排版',exact:true}).click();await edit('first');
            const view=await page.locator('.ProseMirror').elementHandle();
            await page.keyboard.press('End');await page.keyboard.insertText(' 中文');
            await page.waitForFunction(()=>document.querySelector('.ProseMirror')?.textContent.includes('中文'));
            await page.waitForFunction(()=>document.querySelector('#preview-status').textContent.includes('ms'));
            assert.equal(await view.evaluate(node=>node===document.querySelector('.ProseMirror')),true,'live preview must preserve the editor and caret');
            await page.keyboard.insertText('继续');assert.match(await page.locator('.ProseMirror > p').first().textContent(),/中文继续/,'input after a precision response must remain at the same caret');
            assert.equal(await page.getByRole('toolbar',{name:'画布文字格式'}).count(),0,'a collapsed caret must not show the selection toolbar');
            await page.keyboard.press('Home');await page.keyboard.press('Shift+End');await page.getByRole('toolbar',{name:'画布文字格式'}).getByRole('button',{name:'粗体',exact:true}).click();await finish();
            const value=await xml();assert.match(value,/\$\{name\}/);assert.match(value,/中文继续/);assert.match(value,/role="bold"/);assert.match(value,/color="#224488"/);
        });
        await check('convenient editing stays local while typing and both modes share the same template',async()=>{
            await load();await page.getByRole('button',{name:'便捷编辑',exact:true}).click();
            const persisted=await xml(),tableBefore=await (await object('table')).boundingBox(),pageCount=await page.evaluate(()=>pages.length);assert.ok(tableBefore);
            let previews=0;const count=request=>{if(request.url().endsWith('/api/preview'))previews++;};page.on('request',count);
            await edit('second');await page.keyboard.press('End');await page.keyboard.type(' local draft');
            assert.equal(await page.locator('.ProseMirror > p').last().textContent(),'Second paragraph local draft');
            assert.equal(await page.locator('#preview-status').textContent(),'便捷编辑 · 完成后校验精确版式');
            for(const line of ['extra one','extra two','extra three']){await page.keyboard.press('Shift+Enter');await page.keyboard.type(line);}
            await page.keyboard.press('Home');await page.keyboard.press('Shift+End');await page.getByRole('toolbar',{name:'画布文字格式'}).getByLabel('画布文字字号').selectOption('24');
            await page.waitForFunction(before=>{const table=entries.find(entry=>entry.id==='table');return table&&document.querySelector(`.studio-object[data-studio-path="${table.path}"]`)?.getBoundingClientRect().top>before+20;},tableBefore.y);
            assert.ok(await page.locator('.studio-flow-tail[data-reflow]').count()>0);assert.equal(await page.evaluate(()=>pages.length),pageCount);assert.equal(await xml(),persisted,'temporary browser reflow must not replace the saved source or engine page model');
            const draft=await page.locator('.ProseMirror').textContent();
            const revision=await page.evaluate(()=>state.revision);
            await page.screenshot({path:path.join(output,'convenient-editing.png'),fullPage:true});
            assert.equal(previews,0);assert.equal(await page.evaluate(()=>state.revision),revision);
            const editor=await page.locator('.ProseMirror').elementHandle();
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            assert.equal(await editor.evaluate(node=>node===document.querySelector('.ProseMirror')),true);
            assert.equal(await page.locator('.ProseMirror').textContent(),draft);
            await page.getByRole('button',{name:'便捷编辑',exact:true}).click();
            await finish();assert.match(await xml(),/local draft/);page.off('request',count);
            const saved=await xml();await page.getByRole('button',{name:'精确排版',exact:true}).click();assert.equal(await xml(),saved);
            assert.equal(await page.locator('.studio-text-preview').count(),0);
        });
        await check('paragraph splits remain unique across subsequent input and undo',async()=>{
            await load();await edit('second');await page.keyboard.press('End');await page.keyboard.press('Enter');await page.keyboard.type('Third');await page.keyboard.type(' line');await finish();
            const value=await xml();assert.equal((value.match(/Third line/g)||[]).length,1);assert.equal((value.match(/id="edit-/g)||[]).length,1);
            await page.locator('#undo').click();await idle();assert.doesNotMatch(await xml(),/Third line/);await page.locator('#redo').click();await idle();assert.match(await xml(),/Third line/);
        });
        await check('continuous editing selects and joins adjacent paragraphs without losing template objects',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><p id="alpha">Alpha ${name}</p><p id="beta" color="#336699">Beta <span role="bold">strong</span><page-number width="24"/></p></document>');
            await edit('alpha');assert.equal(await page.locator('.ProseMirror > p').count(),2,'adjacent source paragraphs must share a continuous text editor');
            await page.keyboard.press('Control+Home');await page.keyboard.press('Control+Shift+End');
            const selected=await page.evaluate(()=>document.getSelection().toString());
            assert.ok(selected.includes('Alpha')&&selected.includes('Beta')&&selected.includes('${name}'),'rapid document navigation must select the complete source');
            const toolbar=page.getByRole('toolbar',{name:'画布文字格式'});await toolbar.waitFor({state:'visible'});
            await toolbar.getByRole('button',{name:'下划线',exact:true}).click();await page.keyboard.press('Control+Enter');await idle();
            const retained=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return {ids:[...document.querySelectorAll('p')].map(node=>node.id),color:document.querySelector('#beta').getAttribute('color'),fields:document.querySelectorAll('page-number').length,underlined:document.querySelectorAll('span[underline="true"]').length};});
            assert.deepEqual(retained.ids,['alpha','beta']);assert.equal(retained.color,'#336699');assert.equal(retained.fields,1);assert.ok(retained.underlined>=2);
            await edit('beta');await page.keyboard.press('Control+End');await page.keyboard.press('Home');await page.keyboard.press('Backspace');
            assert.equal(await page.locator('.ProseMirror > p').count(),1,'Backspace at the second paragraph start must join the previous paragraph');
            await page.keyboard.press('Control+z');assert.equal(await page.locator('.ProseMirror > p').count(),2);
            await page.keyboard.press('Control+Shift+z');assert.equal(await page.locator('.ProseMirror > p').count(),1);
            await page.keyboard.press('Control+Enter');await idle();
            const value=await xml();assert.equal((value.match(/\$\{name\}/g)||[]).length,1);assert.match(value,/Alpha/);assert.match(value,/Beta/);assert.match(value,/strong/);assert.equal((value.match(/<page-number\b/g)||[]).length,1);
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
            const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);await download.saveAs(path.join(output,'continuous-template.zip'));await page.locator('#close-template-export').click();
            await page.screenshot({path:path.join(output,'continuous-editing.png'),fullPage:true});
        });
        await check('selection formatting survives inspector scrolling and follows canvas scrolling',async()=>{
            await load('<document version="1"><page width="420" height="1200" margin="30"/><p id="scroll-text" space-before="150">SCROLL FORMAT</p></document>');
            await edit('scroll-text');await page.keyboard.press('Home');await page.keyboard.press('Shift+End');
            const toolbar=page.getByRole('toolbar',{name:'画布文字格式'});await toolbar.waitFor({state:'visible'});
            const selected=await page.evaluate(()=>document.getSelection().toString());assert.ok(selected.length);
            const scroll=await page.locator('#properties').evaluate(node=>{const before=node.scrollTop;node.scrollTop=before+40;return {before,after:node.scrollTop};});
            assert.ok(scroll.after>scroll.before,'the inspector must really scroll');
            await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
            assert.equal(await toolbar.isVisible(),true);assert.equal(await page.evaluate(()=>document.getSelection().toString()),selected);
            const before=await toolbar.boundingBox(),textBefore=await page.locator('.ProseMirror > p').boundingBox();
            const canvasScroll=await page.locator('.canvas-scroll').evaluate(node=>{const before=node.scrollTop;node.scrollTop+=30;return {before,after:node.scrollTop};});
            assert.ok(canvasScroll.after>canvasScroll.before,'the canvas must really scroll');
            await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
            assert.equal(await toolbar.isVisible(),true);assert.equal(await page.evaluate(()=>document.getSelection().toString()),selected);
            const after=await toolbar.boundingBox(),textAfter=await page.locator('.ProseMirror > p').boundingBox();
            await writeFile(path.join(output,'toolbar-scroll-geometry.json'),JSON.stringify({before,after,textBefore,textAfter,canvasScroll,scroll},null,2));
            assert.ok(Math.abs((after.y-before.y)-(textAfter.y-textBefore.y))<2,'the toolbar must follow the selected text');
            await toolbar.getByRole('button',{name:'下划线',exact:true}).click();await finish();assert.match(await xml(),/underline="true"/);
            await page.screenshot({path:path.join(output,'selection-toolbar-scroll.png'),fullPage:true});
        });
        await check('replacing a same-size image undoes the resource before the previous text edit',async()=>{
            await load();
            const upload=async color=>{
                const bytes=await page.evaluate(color=>{const canvas=document.createElement('canvas');canvas.width=40;canvas.height=24;const context=canvas.getContext('2d');context.fillStyle=color;context.fillRect(0,0,40,24);return canvas.toDataURL('image/png').split(',')[1];},color);
                await page.locator('#resources-tab').click();await page.evaluate(async()=>{await drafts();assetKind='image';$('asset-file').value='';});
                await page.locator('#asset-file').setInputFiles({name:'replace.png',mimeType:'image/png',buffer:Buffer.from(bytes,'base64')});
                await page.locator('#question-answer').fill('undo_test');await page.locator('#question-ok').click();await idle();
            };
            const pixel=()=>page.evaluate(async()=>{const response=await fetch('/api/image?name=undo_test&revision='+state.revision,{headers:{'X-Designer-Token':token}});const image=await createImageBitmap(await response.blob());const canvas=document.createElement('canvas');canvas.width=40;canvas.height=24;const context=canvas.getContext('2d');context.drawImage(image,0,0);image.close();return [...context.getImageData(1,1,1,1).data];});
            await upload('#ff0000');await load('<document version="1"><page width="420" height="600" margin="30"/><p id="text">Before</p><p line-height="32"><image ref="undo_test" width="40" height="24"/></p></document>');
            await page.locator('#canvas-tab').click();await edit('text');await page.keyboard.press('End');await page.keyboard.type(' edited');await finish();
            const source=await xml(),identity=await page.evaluate(()=>state.resourceIdentity);await upload('#0000ff');
            assert.equal(await xml(),source);assert.notEqual(await page.evaluate(()=>state.resourceIdentity),identity);assert.deepEqual(await pixel(),[0,0,255,255]);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),source);assert.deepEqual(await pixel(),[255,0,0,255]);
            await page.locator('#redo').click();await idle();assert.equal(await xml(),source);assert.deepEqual(await pixel(),[0,0,255,255]);
            await page.locator('#undo').click();await idle();await page.locator('#undo').click();await idle();
            assert.doesNotMatch(await xml(),/Before edited/);assert.deepEqual(await pixel(),[255,0,0,255]);
            await page.screenshot({path:path.join(output,'resource-replacement-undo.png'),fullPage:true});
        });
        await check('sidebar properties compose with active canvas text and survive subsequent text undo',async()=>{
            await load();await page.getByRole('button',{name:'便捷编辑',exact:true}).click();await edit('second');await page.keyboard.press('End');await page.keyboard.type(' canvas');
            await page.locator('#structured-properties').getByLabel('对齐',{exact:true}).selectOption('CENTER');
            await page.locator('#structured-properties').getByLabel('字号',{exact:true}).fill('14');
            await page.locator('.ProseMirror > p').last().click();await page.keyboard.press('End');await page.keyboard.type(' final');await page.keyboard.press('Control+Enter');await idle();
            const properties=()=>page.evaluate(()=>{const node=new DOMParser().parseFromString(state.source,'application/xml').querySelector('#second');return {text:node.textContent,align:node.getAttribute('align'),size:node.getAttribute('size')};});
            assert.deepEqual(await properties(),{text:'Second paragraph canvas final',align:'CENTER',size:'14'});
            await edit('second');await page.keyboard.press('End');await page.keyboard.type(' again');await page.keyboard.press('Control+Enter');await idle();
            await page.locator('#undo').click();await idle();assert.deepEqual(await properties(),{text:'Second paragraph canvas final',align:'CENTER',size:'14'});
            await page.locator('#redo').click();await idle();assert.deepEqual(await properties(),{text:'Second paragraph canvas final again',align:'CENTER',size:'14'});
        });
        await check('a cross-page paragraph explicitly edits its complete source',async()=>{
            const words=Array.from({length:80},(_,index)=>'word'+String(index).padStart(2,'0')).join(' ');
            await load('<document version="1"><page width="240" height="180" margin="20"/><p id="long" line-height="18">'+words+'</p></document>');
            assert.ok(await page.evaluate(()=>pages.length)>1);await edit('long');
            await page.getByText('跨页段落 · 编辑完整原文',{exact:true}).waitFor({state:'visible'});assert.equal(await page.locator('.ProseMirror').textContent(),words);
            await page.keyboard.press('Control+End');await page.keyboard.type(' tail');await page.keyboard.press('Control+Enter');await idle();
            const text=await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#long').textContent);assert.equal(text,words+' tail');
            await page.screenshot({path:path.join(output,'cross-page-source.png'),fullPage:true});
            await load();assert.equal(await page.evaluate(()=>pages.length),1);await edit('second');await page.keyboard.press('End');await page.keyboard.type(' after paging');await page.keyboard.press('Control+Enter');await idle();
            assert.match(await xml(),/Second paragraph after paging/);assert.deepEqual(errors,[],'reducing the page count must leave the canvas and its editor usable');
        });
        await check('block insertion and actions stay beside the selected component and edit the new block',async()=>{
            await load();await (await object('second')).click();
            const insert=page.getByRole('button',{name:'在后面插入',exact:true});
            const selected=await (await object('second')).boundingBox(),button=await insert.boundingBox();assert.ok(selected&&button);
            assert.ok(button.y>=selected.y-40&&button.y<=selected.y+selected.height+40,'insert command must remain next to its component');
            await insert.click();const menu=page.getByRole('menu',{name:'插入内容'});await menu.waitFor();
            await menu.getByRole('menuitem',{name:'段落',exact:true}).click();await page.locator('.ProseMirror').waitFor();
            await page.keyboard.press('Home');await page.keyboard.press('Shift+End');await page.keyboard.type('Inserted block');await page.keyboard.press('Control+Enter');await idle();
            const added=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),nodes=[...document.documentElement.children],node=nodes.find(node=>node.tagName==='p'&&node.textContent==='Inserted block');return {id:node?.id,first:document.querySelector('#first').textContent,second:document.querySelector('#second').textContent,index:node&&nodes.indexOf(node),after:nodes.indexOf(document.querySelector('#second')),before:nodes.indexOf(document.querySelector('#table'))};});
            assert.ok(added.id);assert.equal(added.first,'Hello mixed ${name}');assert.equal(added.second,'Second paragraph');assert.equal(added.index,added.after+1);assert.equal(added.before,added.index+1);assert.doesNotMatch(await xml(),/<foreground|<box/);
            await (await object(added.id)).click();await page.getByRole('button',{name:'组件操作',exact:true}).click();
            await page.getByRole('menu',{name:'组件操作'}).getByRole('menuitem',{name:/^创建副本/}).click();await idle();
            assert.equal(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('p')].filter(node=>node.textContent==='Inserted block').length),2);
            const before=await xml();await page.getByRole('button',{name:'组件操作',exact:true}).click();await page.keyboard.press('Escape');
            assert.equal(await page.getByRole('menu',{name:'组件操作'}).count(),0);assert.equal(await xml(),before);
            await page.screenshot({path:path.join(output,'block-insertion.png'),fullPage:true});
        });
        await check('editing and unified undo remain responsive while a real engine apply is delayed',async()=>{
            await load();let release,held=false;
            const gate=new Promise(resolve=>{release=resolve;});
            const delayed=async route=>{if(!held){held=true;await gate;}await route.continue();};
            await page.route('**/api/apply',delayed);
            try {
                const applying=page.waitForRequest(request=>request.url().endsWith('/api/apply'));
                await edit('second');await page.keyboard.press('End');await page.keyboard.type(' timeline');await page.keyboard.press('Control+Enter');await applying;
                assert.match(await xml(),/Second paragraph timeline/);assert.equal(await page.locator('body.busy').count(),0);
                await (await object('first')).click();await page.getByLabel('块排版方式').selectOption('FIXED');
                await page.waitForFunction(()=>state.source.includes('<box'));
                await page.locator('#undo').click();assert.doesNotMatch(await xml(),/<foreground|<box/);assert.match(await xml(),/Second paragraph timeline/);
                await page.locator('#undo').click();assert.doesNotMatch(await xml(),/timeline/);
                await page.locator('#redo').click();assert.match(await xml(),/Second paragraph timeline/);
                await page.locator('#redo').click();assert.match(await xml(),/<box/);
                assert.equal(await page.locator('body.busy').count(),0,'waiting for precision validation must not lock local editing');
            } finally {release();await page.unrouteAll({behavior:'wait'});}
            await idle();
            const saved=await page.evaluate(async()=>({local:state.source,engine:(await request('state')).source}));assert.equal(saved.engine,saved.local,'the real engine must eventually receive the latest undo/redo result');
            await page.screenshot({path:path.join(output,'optimistic-history.png'),fullPage:true});
        });
        await check('resource uploads preserve the active draft and temporarily prevent conflicting text input',async()=>{
            await load();await page.getByRole('button',{name:'便捷编辑',exact:true}).click();await edit('second');await page.keyboard.press('End');await page.keyboard.type(' resource draft');
            const image=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=8;canvas.height=6;const context=canvas.getContext('2d');context.fillStyle='#44aa88';context.fillRect(0,0,8,6);return canvas.toDataURL('image/png').split(',')[1];});
            const filename=path.join(output,'resource-lock.png');await writeFile(filename,Buffer.from(image,'base64'));
            let release,responseReady;const gate=new Promise(resolve=>{release=resolve;}),ready=new Promise(resolve=>{responseReady=resolve;});
            const delayed=async route=>{const response=await route.fetch();responseReady();await gate;await route.fulfill({response});};await page.route('**/api/resource',delayed);
            try {
                await page.locator('#resources-tab').click();const opening=page.waitForEvent('filechooser');await page.locator('#add-image').click();await (await opening).setFiles(filename);
                await page.locator('#question-answer').fill('studio_lock');await page.locator('#question-ok').click();
                await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('The real resource response did not arrive')),12000);ready.then(()=>{clearTimeout(timer);resolve();},error=>{clearTimeout(timer);reject(error);});});
                await page.waitForFunction(()=>document.querySelector('.ProseMirror')?.getAttribute('contenteditable')==='false');
                const before=await page.evaluate(()=>({source:$('source').value,text:document.querySelector('.ProseMirror').textContent}));assert.match(before.source,/Second paragraph resource draft/);
                await page.locator('.ProseMirror').focus();await page.keyboard.type(' blocked input');
                assert.deepEqual(await page.evaluate(()=>({source:$('source').value,text:document.querySelector('.ProseMirror').textContent})),before,'an older resource response must not race new text input');
            } finally {release();await page.unrouteAll({behavior:'wait'});}
            await idle();assert.equal(await page.locator('.ProseMirror').getAttribute('contenteditable'),'true');
            assert.match(await xml(),/Second paragraph resource draft/);assert.equal(await page.evaluate(()=>state.images.some(image=>image.name==='studio_lock')),true);
            await page.locator('.ProseMirror > p').last().click();await page.keyboard.press('End');await page.keyboard.type(' resumed');await page.keyboard.press('Control+Enter');await idle();assert.match(await xml(),/Second paragraph resource draft resumed/);
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
            const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);await download.saveAs(path.join(output,'resource-lock-template.zip'));await page.locator('#close-template-export').click();
        });
        await check('invalid XML keeps the last valid canvas and recovers after a source correction',async()=>{
            await load();const before=await xml(),preview=await page.evaluate(()=>previewId);
            await page.locator('#source-tab').click();const editor=page.getByRole('textbox',{name:'XML 源码',exact:true});
            const invalid='<document version="1"><p>Broken source</document>';await editor.fill(invalid);
            await page.waitForFunction(()=>document.querySelector('#preview-status').textContent.includes('草稿有错误'));
            assert.equal(await editor.inputValue(),invalid);assert.equal(await xml(),before);assert.equal(await page.evaluate(()=>previewId),preview);assert.equal(await page.locator('.page-image').count(),1);
            await page.locator('#editor-feedback').waitFor({state:'visible'});
            await editor.fill(before.replace('Second paragraph','Recovered paragraph'));await page.waitForFunction(()=>/\bms\b/.test(document.querySelector('#preview-status').textContent));
            await page.locator('#canvas-tab').click();await idle();
            assert.match(await page.evaluate(()=>$('source').value),/Recovered paragraph/);assert.equal(await page.locator('#editor-feedback').isVisible(),false);
            await (await object('second')).click();await idle();assert.match(await xml(),/Recovered paragraph/);assert.equal((await xml()).includes('${name}'),true);assert.match(await (await object('second')).getAttribute('class'),/\bis-selected\b/);
            await page.screenshot({path:path.join(output,'recovered-source.png'),fullPage:true});
        });
        await check('table cell editing and whole-table flow sorting retain allocated space',async()=>{
            await load();const cellPath=await page.evaluate(()=>entries.find(entry=>entry.tag==='cell').path);
            await page.locator(`.studio-object[data-studio-path="${cellPath}"]`).dblclick();await page.locator('.ProseMirror').waitFor();await page.keyboard.press('Control+a');await page.keyboard.type('Edited cell');await finish();
            assert.match(await xml(),/<cell>Edited cell<\/cell>/);assert.match(await xml(),/Cell B/);
            assert.deepEqual(await page.evaluate(()=>selectedPaths),[cellPath],'editing an ID-less cell must not select every ID-less outline node');
            await holdSortTo(page.locator(`.studio-object[data-studio-path="${cellPath}"]`),await object('first'));
            assert.deepEqual((await bodyOrder()).slice(-3),['table','first','second']);
            assert.doesNotMatch(await xml(),/<foreground|<background|<box/);
            assert.match(await xml(),/<cell>Edited cell<\/cell>/);
            const bounds=await page.evaluate(()=>['table','first'].map(id=>{const hit=pages[0].hits.find(hit=>hit.id===id);return {y:hit.y,height:hit.height};}));
            assert.ok(bounds[1].y>=bounds[0].y+bounds[0].height-.01,'body must reserve the sorted table height');
            await fixSelection();
            const fixed=await page.evaluate(()=>({table:entries.find(entry=>entry.id==='table').path,cell:entries.find(entry=>entry.tag==='cell').path}));
            await page.locator(`.studio-object[data-studio-path="${fixed.cell}"]`).click();
            assert.deepEqual(await page.evaluate(()=>selectedPaths),[fixed.table],'single click on a fixed table cell selects the owning component');
            assert.equal(await page.locator('.ProseMirror').count(),0);await page.getByRole('button',{name:'拖动固定组件',exact:true}).waitFor({state:'visible'});
            assert.equal(await page.locator('.studio-controls').count(),1,'a selected fixed table must have one component frame, not separate cell controls');
            const frame=await page.locator('.studio-controls').boundingBox(),table=await (await object('table')).boundingBox();
            assert.ok(Math.abs(frame.x-table.x)<.5&&Math.abs(frame.y-table.y)<.5&&Math.abs(frame.width-table.width)<.5&&Math.abs(frame.height-table.height)<.5,'fixed table controls must follow its outer bounds');
            await page.locator(`.studio-object[data-studio-path="${fixed.cell}"]`).dblclick();await page.locator('.ProseMirror').waitFor();
            assert.equal(await page.locator('.ProseMirror').textContent(),'Edited cell');await finish();
        });
        await check('multiline rich paste stays inside one table cell and supports text undo',async()=>{
            await load();const cellPath=await page.evaluate(()=>entries.find(entry=>entry.tag==='cell').path);
            await page.locator(`.studio-object[data-studio-path="${cellPath}"]`).dblclick();await page.locator('.ProseMirror').waitFor();await page.keyboard.press('Control+a');
            await page.locator('.ProseMirror').evaluate(element=>{
                const data=new DataTransfer();data.setData('text/html','<p>First <strong>bold</strong></p><p>Second ${name}</p>');data.setData('text/plain','First bold\nSecond ${name}');
                element.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));
            });
            assert.equal(await page.locator('.ProseMirror > p').count(),1,'a multiline clipboard must not create extra cells');
            assert.match(await page.locator('.ProseMirror').textContent(),/First boldSecond \$\{name\}/);
            await page.keyboard.press('Control+z');assert.equal(await page.locator('.ProseMirror').textContent(),'Cell A');
            await page.keyboard.press('Control+Shift+z');assert.match(await page.locator('.ProseMirror').textContent(),/First boldSecond/);await finish();
            const table=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return {cells:document.querySelectorAll('cell').length,columns:document.querySelectorAll('column').length,text:document.querySelector('cell').textContent,bold:!!document.querySelector('cell span[role="bold"]'),next:document.querySelectorAll('cell')[1].textContent};});
            assert.equal(table.cells,2);assert.equal(table.columns,2);assert.equal(table.text,'First bold\nSecond ${name}');assert.equal(table.bold,true);assert.equal(table.next,'Cell B');
        });
        await check('component actions after cell editing copy and delete the whole table',async()=>{
            await load();const cellPath=await page.evaluate(()=>entries.find(entry=>entry.tag==='cell').path);
            await page.locator(`.studio-object[data-studio-path="${cellPath}"]`).dblclick();await page.locator('.ProseMirror').waitFor();await page.keyboard.press('End');await page.keyboard.type(' changed');await finish();
            await page.getByRole('button',{name:'组件操作',exact:true}).click();await page.getByRole('menu',{name:'组件操作'}).getByRole('menuitem',{name:/^创建副本/}).click();await idle();
            const counts=()=>page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return {tables:document.querySelectorAll('table').length,cells:document.querySelectorAll('cell').length,body:document.querySelectorAll('document > p').length};});
            assert.deepEqual(await counts(),{tables:2,cells:4,body:2});await page.locator('#undo').click();await idle();assert.deepEqual(await counts(),{tables:1,cells:2,body:2});
            await page.locator(`.studio-object[data-studio-path="${cellPath}"]`).dblclick();await page.locator('.ProseMirror').waitFor();await finish();
            await page.locator('.studio-surface').first().focus();await page.keyboard.press('Delete');await idle();assert.deepEqual(await counts(),{tables:0,cells:0,body:2});
            await page.locator('#undo').click();await idle();assert.deepEqual(await counts(),{tables:1,cells:2,body:2});assert.match(await xml(),/Cell A changed/);
        });
        await check('copying a bound loop retains source IDs and redirects references inside the copy',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><each items="records" as="record"><p id="entry">${record.label}</p><p id="ref">See <page-reference target="entry" width="24"/></p></each></document>',{records:[{code:'a',label:'Record Alpha'},{code:'b',label:'Record Beta'}]});
            await (await object('entry')).click();await page.getByRole('button',{name:'组件操作',exact:true}).click();await page.getByRole('menu',{name:'组件操作'}).getByRole('menuitem',{name:/^创建副本/}).click();await idle();
            const copies=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('each')].map(node=>({items:node.getAttribute('items'),as:node.getAttribute('as'),id:node.querySelector('p').id,target:node.querySelector('page-reference').getAttribute('target'),text:node.querySelector('p').textContent})));
            assert.equal(copies.length,2);assert.notEqual(copies[0].id,copies[1].id);
            assert.equal(copies[0].id,'entry');for(const copy of copies){assert.equal(copy.items,'records');assert.equal(copy.as,'record');assert.ok(copy.id);assert.equal(copy.target,copy.id);assert.equal(copy.text,'${record.label}');}
            await page.locator('#export').click();await page.locator('#format').selectOption('PDF');
            const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-export').click()]);await download.saveAs(path.join(output,'dynamic-copy.pdf'));await page.locator('#close-export').click();
        });
        await check('blank canvas insertion belongs to the current section',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><section id="part-one"><p id="section-first">First section</p></section><section id="part-two"><p id="section-second">Second section</p></section></document>');
            await (await object('section-second')).click();
            await page.locator('.studio-background').nth(1).click({position:{x:90,y:200}});await page.waitForFunction(()=>selectedPaths.length===0);
            const insert=page.locator('.studio-host').nth(1).getByRole('button',{name:'插入内容',exact:true});
            const overlaps=await insert.evaluate(button=>{const rect=button.getBoundingClientRect();return [...button.closest('.studio-host').querySelectorAll('.studio-object')].filter(node=>{const other=node.getBoundingClientRect();return Math.min(rect.right,other.right)>Math.max(rect.left,other.left)&&Math.min(rect.bottom,other.bottom)>Math.max(rect.top,other.top);}).map(node=>node.getAttribute('data-studio-path'));});
            assert.deepEqual(overlaps,[],'the blank insertion button must not cover existing document content');
            await insert.click();await page.getByRole('menu',{name:'插入内容'}).getByRole('menuitem',{name:'段落',exact:true}).click();
            await page.locator('.ProseMirror').waitFor();await page.keyboard.type('Second section added');await page.keyboard.press('Control+Enter');await idle();
            const sections=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return {rootParagraphs:document.querySelectorAll('document > p').length,parts:[...document.querySelectorAll('section')].map(node=>[...node.querySelectorAll(':scope > p')].map(node=>node.textContent))};});
            assert.equal(sections.rootParagraphs,0);assert.deepEqual(sections.parts,[['First section'],['Second section','Second section added']]);
        });
        await check('dynamic rows sort as one bound component using cells and the visible handle',async()=>{
            const dynamic='<document version="1"><page width="420" height="600" margin="30"/><p id="before">Before table</p><rows id="dynamic" items="records" as="record"><columns><column weight="1"/><column weight="1"/></columns><row><cell>${record.drug}</cell><cell>${record.dose}</cell></row></rows><p id="after">After table</p></document>';
            await load(dynamic,{records:[{drug:'Medicine A',dose:'20 mg'},{drug:'Medicine B',dose:'40 mg'}]});
            const cellPath=await page.evaluate(()=>entries.find(entry=>entry.tag==='cell').path);
            await holdSortTo(page.locator(`.studio-object[data-studio-path="${cellPath}"]`).first(),await object('before'));
            assert.deepEqual(await bodyOrder(),['dynamic','before','after']);
            assert.match(await xml(),/<rows[^>]*items="records"[^>]*as="record"/);assert.match(await xml(),/\$\{record\.drug\}/);
            assert.doesNotMatch(await xml(),/<foreground|<box/);
            const handle=page.getByRole('button',{name:'拖动排序',exact:true}).first();await handle.waitFor({state:'visible'});
            const box=await handle.boundingBox(),destination=await (await object('after')).boundingBox();assert.ok(box&&destination);
            await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
            await page.locator('.studio-surface[data-gesture="sort"]').waitFor({state:'visible'});
            await page.mouse.move(destination.x+destination.width/2,destination.y+destination.height*.8,{steps:8});
            await page.locator('.studio-drop-indicator').waitFor({state:'visible'});await page.mouse.up();await idle();
            assert.deepEqual(await bodyOrder(),['before','after','dynamic']);
            assert.match(await xml(),/<rows[^>]*items="records"[^>]*as="record"/);assert.doesNotMatch(await xml(),/<foreground|<box/);
            assert.equal(await page.evaluate(()=>pages.flatMap(page=>page.hits).filter(hit=>hit.id.startsWith('dynamic')).length)>0,true);
            await page.screenshot({path:path.join(output,'flow-dynamic-table.png'),fullPage:true});
        });
        await check('flow multi-selection sorts together and only explicit component properties enable coordinates',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><p id="one">One</p><p id="two">Two</p><p id="three">Three</p></document>');
            await (await object('one')).click();await (await object('two')).click({modifiers:['Control']});
            await page.waitForFunction(()=>selectedPaths.length===2);
            const before=await xml();await page.locator('.studio-surface').first().focus();await page.keyboard.press('ArrowRight');await idle();
            assert.equal(await xml(),before,'plain arrows must never convert a flow component to fixed positioning');
            await holdSortTo(await object('one'),await object('three'),true);
            assert.deepEqual(await bodyOrder(),['three','one','two']);assert.doesNotMatch(await xml(),/<foreground|<box/);
            await page.locator('.studio-surface').first().focus();await page.keyboard.press('Alt+ArrowUp');await idle();
            assert.deepEqual(await bodyOrder(),['one','two','three']);assert.doesNotMatch(await xml(),/<foreground|<box/);
            await (await object('two')).click();await page.locator('.studio-surface').first().focus();await page.keyboard.press('Alt+ArrowUp');await idle();
            assert.deepEqual(await bodyOrder(),['two','one','three']);
        });
        await check('header and footer support direct canvas text editing',async()=>{
            await load();for(const id of ['header','footer']){await edit(id);await page.keyboard.press('End');await page.keyboard.type(' changed');await finish();}
            assert.match(await xml(),/Header changed/);assert.match(await xml(),/Footer changed/);
        });
        await check('fixed positions, adaptive frames and physical target pages reach Java layout',async()=>{
            await load();await edit('second');await finish();
            await page.getByLabel('块排版方式').selectOption('FIXED');await idle();
            await page.getByLabel('高度规则').selectOption('AUTO');await idle();
            await page.getByLabel('宽度规则').selectOption('FILL');await idle();
            const number=page.getByLabel('物理页（0 为每页）');await number.fill('2');await number.press('Enter');await idle();
            assert.equal(await page.evaluate(()=>pages.length),2);
            const value=await xml();assert.match(value,/page="2"/);assert.match(value,/height-mode="AUTO"/);assert.match(value,/width-mode="FILL"/);
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#second').getAttribute('size')),'12.0','moving into foreground must keep the original body size');
            const hits=await page.evaluate(()=>pages.flatMap((p,index)=>p.hits.filter(hit=>hit.id==='second').map(hit=>({index,...hit}))));assert.equal(hits.length,1);assert.equal(hits[0].index,1);
            await writeFile(path.join(output,'fixed-hits.json'),JSON.stringify(hits));
            for(const format of ['PDF','SVG','PNG']){
                await page.locator('#export').click();await page.locator('#format').selectOption(format);
                const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-export').click()]);
                await download.saveAs(path.join(output,'fixed.'+({PDF:'pdf',SVG:'svg.zip',PNG:'png.zip'})[format]));await page.locator('#close-export').click();
            }
            await number.fill('1');await number.press('Enter');await idle();
            await page.getByLabel('块排版方式').selectOption('FLOW');await idle();
            assert.deepEqual((await bodyOrder()).slice(-3),['first','second','table'],'returning to flow must use the nearby body location rather than append to the end');
            assert.doesNotMatch(await xml(),/<foreground|<box/);
        });
        await check('fixed component overlap and avoidance properties control actual body pagination',async()=>{
            await load();await (await object('second')).click();await fixSelection();
            const placement=()=>page.evaluate(()=>{const xml=new DOMParser().parseFromString(state.source,'application/xml'),box=xml.querySelector('foreground > box');return {x:Number(box.getAttribute('x')),y:Number(box.getAttribute('y')),width:Number(box.getAttribute('width')),height:Number(box.getAttribute('height')),bodyFlow:box.getAttribute('body-flow'),table:pages.flatMap(page=>page.hits).find(hit=>hit.id==='table')};});
            await page.getByLabel('正文相遇时').selectOption('OVERLAP');await idle();const overlap=await placement();
            await page.getByLabel('正文相遇时').selectOption('AVOID');await idle();const avoidance=await placement();
            assert.equal(avoidance.bodyFlow,'AVOID');assert.equal(avoidance.y,overlap.y);assert.equal(avoidance.x,overlap.x);
            assert.ok(avoidance.table.y>=avoidance.y+avoidance.height-.01,'body table must avoid the fixed component full vertical footprint');
            assert.ok(avoidance.table.y>overlap.table.y,'switching the component property must change real engine body placement');
            const [download]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
            await download.saveAs(path.join(output,'avoidance.pdf'));await page.locator('#close-export').click();
            await writeFile(path.join(output,'avoidance-hits.json'),JSON.stringify(avoidance));
            await page.getByLabel('正文相遇时').selectOption('OVERLAP');await idle();const restored=await placement();
            assert.equal(restored.bodyFlow,'OVERLAP');assert.equal(restored.table.y,overlap.table.y);
            await holdDrag(await object('second'),12,8);assert.equal((await placement()).bodyFlow,'OVERLAP','dragging must retain the component body-flow property');
            await page.getByLabel('正文相遇时').selectOption('AVOID');await idle();
            await page.screenshot({path:path.join(output,'fixed-avoidance.png'),fullPage:true});
        });
        await check('pointer move, resize and rotation persist real geometry',async()=>{
            await load();await (await object('second')).click();await fixSelection();
            const drag=async(locator,dx,dy)=>{const box=await locator.boundingBox();assert.ok(box);await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+dx,box.y+box.height/2+dy,{steps:6});await page.mouse.up();await idle();};
            await holdDrag(await object('second'),12,20);assert.match(await xml(),/<foreground>/);
            const edge=page.getByLabel('调整对象大小 n',{exact:true}).last(),edgeBox=await edge.boundingBox();
            assert.equal(await edge.evaluate(node=>getComputedStyle(node).cursor),'ns-resize');
            // Hit the border away from its centre marker, proving the whole edge works.
            await page.mouse.move(edgeBox.x+edgeBox.width*.25,edgeBox.y+edgeBox.height/2);await page.mouse.down();
            await page.mouse.move(edgeBox.x+edgeBox.width*.25,edgeBox.y+edgeBox.height/2-12,{steps:5});await page.mouse.up();await idle();
            assert.match(await xml(),/height-mode="FIXED"/);
            await drag(page.getByLabel('调整对象大小 e',{exact:true}).last(),-36,0);assert.match(await xml(),/width-mode="FIXED"/);
            await drag(page.getByLabel('旋转对象',{exact:true}).last(),22,12);assert.match(await xml(),/rotation="-?\d+/);
            await page.screenshot({path:path.join(output,'fixed-object.png'),fullPage:true});
        });
        await check('marquee selects objects and keyboard moves the group together',async()=>{
            await load();
            const first=await (await object('first')).boundingBox(),second=await (await object('second')).boundingBox();
            await page.mouse.move(first.x-8,first.y+1);await page.mouse.down();await page.mouse.move(second.x+second.width+4,second.y+second.height-1,{steps:8});await page.mouse.up();
            await page.waitForFunction(()=>selectedPaths.length===2);await fixSelection();
            await page.locator('.studio-surface').first().focus();await page.keyboard.press('ArrowRight');await idle();
            const boxes=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('foreground > box')].map(box=>box.getAttribute('x')));assert.equal(boxes.length,2);assert.equal(boxes[0],boxes[1]);
        });
        await check('long press moves the existing multiple selection together',async()=>{
            await load();await (await object('first')).click();await (await object('second')).click({modifiers:['Control']});
            await page.waitForFunction(()=>selectedPaths.length===2);await fixSelection();
            const before=await page.evaluate(()=>['first','second'].map(id=>pages[0].hits.find(hit=>hit.id===id).y));
            await holdDrag(await object('first'),12,8);
            const boxes=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('foreground > box')].map(box=>({x:Number(box.getAttribute('x')),y:Number(box.getAttribute('y'))})));
            assert.equal(boxes.length,2);assert.equal(boxes[0].x,boxes[1].x);assert.ok(boxes[0].x>30,'the group must move right, with any alignment snap applied equally');
            const movement=boxes.map((box,index)=>box.y-before[index]);assert.equal(movement[0],movement[1],JSON.stringify({before,boxes}));assert.ok(movement[0]>0);
        });
        await check('list selection keeps one outside frame and double click edits its prototype',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><bullet id="one">First item</bullet><bullet id="two">Second item</bullet><p id="tail">After list</p></document>');
            await (await object('one')).click();
            await page.waitForFunction(()=>selectedPaths.length===2);
            assert.equal(await page.evaluate(()=>selectedPaths.length),2);
            const outline=page.locator('.studio-flow-controls');assert.equal(await outline.count(),1);await outline.waitFor({state:'visible'});
            const frame=await outline.boundingBox(),first=await (await object('one')).boundingBox(),second=await (await object('two')).boundingBox();assert.ok(frame&&first&&second);assert.ok(Math.abs(frame.y-first.y)<.5&&Math.abs(frame.y+frame.height-second.y-second.height)<.5,'the single list frame must surround both items');
            assert.equal(await page.locator('.studio-object.is-selected').count(),0);
            await holdSortTo(await object('one'),await object('tail'),true);
            assert.deepEqual(await bodyOrder(),['tail','one','two']);assert.doesNotMatch(await xml(),/<foreground|<box/);
            await edit('one');await page.keyboard.press('End');await page.keyboard.type(' edited');await finish();
            assert.match(await xml(),/First item edited/);assert.match(await xml(),/Second item/);
        });
        await check('clipboard image becomes an embedded resource and resizes its actual pixels',async()=>{
            await load();await page.locator('.studio-surface').first().focus();
            await page.locator('.studio-surface').first().evaluate(element=>{
                const canvas=document.createElement('canvas');canvas.width=80;canvas.height=40;const context=canvas.getContext('2d');context.fillStyle='#c04433';context.fillRect(0,0,80,40);
                const data=atob(canvas.toDataURL('image/png').split(',')[1]),bytes=Uint8Array.from(data,char=>char.charCodeAt(0));
                const transfer=new DataTransfer();transfer.items.add(new File([bytes],'clipboard.png',{type:'image/png'}));element.dispatchEvent(new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true}));
            });await page.waitForFunction(()=>state.source.includes('ref="pasted_')&&!busy);await idle();
            const value=await xml();assert.match(value,/<image[^>]+ref="pasted_/);assert.match(value,/alt="clipboard.png"/);
            const id=await page.evaluate(()=>entries.find(entry=>entry.tag==='p'&&entry.id.startsWith('image-')).id);
            await (await object(id)).click();await fixSelection();const handle=page.getByLabel('调整对象大小 se',{exact:true}).last(),bounds=await handle.boundingBox();
            await page.mouse.move(bounds.x+4,bounds.y+4);await page.mouse.down();await page.mouse.move(bounds.x+30,bounds.y+18,{steps:5});await page.mouse.up();await idle();
            const size=await page.evaluate(id=>{const document=new DOMParser().parseFromString(state.source,'application/xml');const image=[...document.querySelectorAll('p')].find(p=>p.id===id).querySelector('image');return Number(image.getAttribute('width'));},id);assert.ok(size>60);
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
            const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);await download.saveAs(path.join(output,'edited-template.zip'));await page.locator('#close-template-export').click();
        });
        await check('narrow workbench fits without horizontal overflow and scripts remain error free',async()=>{
            await page.setViewportSize({width:980,height:900});await page.screenshot({path:path.join(output,'workbench-980.png'),fullPage:true});
            assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);assert.deepEqual(errors,[]);
        });
        assert.ok(executed.length,'No browser scenarios matched EXPORT4J_STUDIO_SCENARIOS');
        await writeFile(path.join(output,'browser-scenarios.json'),JSON.stringify(executed));
        await writeFile(path.join(output,'browser-errors.json'),JSON.stringify(errors));
    } finally {await browser?.close();child?.kill();if(child)await writeFile(path.join(output,'server.log'),logs);}
});
