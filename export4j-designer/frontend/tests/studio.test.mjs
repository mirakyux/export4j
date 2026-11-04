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
const distribution=process.env.EXPORT4J_DESIGNER_DIST?path.resolve(process.env.EXPORT4J_DESIGNER_DIST):path.join(repository,'export4j-designer/build/install/export4j-designer');
const scenarioFilter=process.env.EXPORT4J_STUDIO_SCENARIOS?new RegExp(process.env.EXPORT4J_STUDIO_SCENARIOS):undefined;
const source=`<document version="1"><page width="420" height="600" margin="30"/>
<header height="24"><p id="header">Header</p></header><footer height="24"><p id="footer">Footer</p></footer>
<p id="first" line-height="18">Hello <span color="#224488">mixed</span> \${name}</p>
<p id="second" line-height="18">Second paragraph</p>
<table id="table"><columns><column weight="1"/><column weight="1"/></columns><row><cell>Cell A</cell><cell>Cell B</cell></row></table>
</document>`;

test('canvas editing keeps template semantics and engine output', {timeout:480000}, async t=>{
    await mkdir(output,{recursive:true});
    const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
    const child=suppliedOrigin?undefined:spawn(java,['-Djava.awt.headless=true','-cp',path.join(distribution,'lib/*'),
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
        const idle=async()=>{await page.waitForFunction(()=>!busy&&!DesignerStudioBridge.snapshot().pending&&(document.querySelector('#container-template-state')||pages.length>0&&document.querySelector('.page-image')?.dataset.preview===previewId));};
        const load=async(xml=source,data={name:'Ada'})=>{await page.mouse.up();await page.keyboard.press('Escape');await page.evaluate(async ({xml,data})=>{$('data').value=JSON.stringify(data);await DesignerStudioBridge.apply(xml);await DesignerStudioBridge.flush();await DesignerStudioBridge.select([]);},{xml,data});await idle();await page.locator('.canvas-scroll').evaluate(node=>node.scrollTop=0);};
        const xml=()=>page.evaluate(()=>state.source);
        const object=async id=>{const item=await page.evaluate(id=>entries.find(entry=>entry.id===id),id);assert.ok(item,id);return page.locator(`.studio-object[data-studio-path="${item.path}"]`).first();};
        const edit=async id=>{await (await object(id)).dblclick();await page.locator('.ProseMirror').waitFor();};
        const finish=async()=>{await page.getByRole('button',{name:'完成',exact:true}).click();await idle();};
        const inspectorChild=async path=>{
            const route=await page.evaluate(path=>{const from=selected.path;return entries.filter(entry=>entry.path!==from&&entry.path.startsWith(from+'/')&&(entry.path===path||path.startsWith(entry.path+'/'))).map(entry=>entry.path);},path);
            assert.ok(route.length,'target must be inside the selected container: '+path);
            for(const step of route){await page.locator(`#inspector-panel [data-content-navigation] [data-content-path="${step}"]`).click();await idle();}
        };
        const inspectorAncestor=async tag=>{
            const target=await page.evaluate(tag=>entries.filter(entry=>entry.tag===tag&&selected.path.startsWith(entry.path+'/')).at(-1)?.path,tag);
            assert.notEqual(target,undefined,'missing inspector ancestor '+tag);
            await page.locator(`#inspector-panel #selection-crumb [data-content-path="${target}"]`).click();await idle();
        };
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
        await check('blank pages accept their first pasted image and preserve undo',async()=>{
            const cases=[
                ['blank','<document version="1"><page width="420" height="600" margin="30"/></document>',0,''],
                ['header','<document version="1"><page width="420" height="600" margin="30"/><header height="60"><p>HEADER</p></header></document>',0,''],
                ['empty-section','<document version="1"><page width="420" height="600" margin="30"/><section id="first"/><section id="last"/></document>',1,'/2']
            ];
            for(const [name,source,pageIndex,owner] of cases){
                await load(source,{});const before=await xml(),resources=await page.evaluate(()=>state.images.length);
                assert.equal(await page.evaluate(index=>pages[index].ownerPath,pageIndex),owner);
                await page.evaluate(async({pageIndex,owner})=>{
                    const canvas=document.createElement('canvas');canvas.width=40;canvas.height=40;canvas.getContext('2d').fillRect(0,0,40,40);
                    const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));
                    await DesignerStudioBridge.paste(new File([blob],'blank.png',{type:'image/png'}),pageIndex,30,30,owner);
                },{pageIndex,owner});await idle();
                assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().layoutState),'synced',name);
                assert.equal(await page.evaluate(()=>state.images.length),resources+1,name);
                const inserted=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),image=document.querySelector('image');return {width:image.getAttribute('width'),height:image.getAttribute('height'),owner:image.closest('section')?.id||''};});
                assert.deepEqual(inserted,{width:'30',height:'30',owner:name==='empty-section'?'last':''});
                await page.locator('#undo').click();await idle();assert.equal(await xml(),before);assert.equal(await page.evaluate(()=>state.images.length),resources);
                await page.locator('#redo').click();await idle();assert.equal(await page.evaluate(()=>state.images.length),resources+1);
                const [download]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
                await download.saveAs(path.join(output,'first-paste-'+name+'.pdf'));await page.locator('#close-export').click();
            }
        });
        await check('blank canvas selects page or document properties and commits active text',async()=>{
            await load();await edit('second');await page.keyboard.press('End');await page.keyboard.type(' retained');
            await page.locator('.studio-background').first().click({position:{x:12,y:130}});await idle();
            await page.waitForFunction(()=>selected?.tag==='document');assert.equal(await page.locator('.ProseMirror').count(),0);
            assert.match(await xml(),/Second paragraph retained/);assert.equal(await page.locator('#node-page-settings').isVisible(),true);
            await page.locator('#structured-properties').getByLabel('标题',{exact:true}).fill('Document properties');
            await page.evaluate(async()=>{await drafts();});await idle();assert.match(await xml(),/title="Document properties"/);
            await load('<document version="1"><page width="420" height="600" margin="30"/><section id="first"><p id="owner-text">FIRST</p></section><section id="empty"/></document>',{});
            await (await object('owner-text')).click();
            await page.locator('.studio-background').nth(1).click({position:{x:12,y:120}});await page.waitForFunction(()=>selected?.id==='empty');
            assert.equal(await page.locator('#node-page-settings').isVisible(),true);
            await page.locator('#node-page-settings').click();await page.locator('#page-dialog').waitFor();
            assert.equal(await page.locator('#page-scope').inputValue(),'empty');await page.locator('#apply-page').click();await idle();
            const scroll=page.locator('.canvas-scroll'),bounds=await scroll.boundingBox();assert.ok(bounds);
            await page.mouse.click(bounds.x+5,bounds.y+20);await page.waitForFunction(()=>selected?.tag==='document');
            assert.match(await xml(),/id="owner-text"/);assert.equal(await page.locator('#node-page-settings').isVisible(),true);
            await page.screenshot({path:path.join(output,'blank-document-properties.png'),fullPage:true});
        });
        await check('page properties ignore component shortcuts and ordinary component commands still work',async()=>{
            const sectioned='<document version="1"><page width="420" height="600" margin="30"/><section id="one"><p id="shortcut-first">KEEP ONE</p></section><section id="two"><p id="shortcut-next">KEEP TWO</p></section></document>';
            for(const [source,index,owner] of [
                [sectioned,0,'one'],
                ['<document version="1"><page width="420" height="600" margin="30"/><section id="one"><p id="shortcut-first">KEEP ONE</p></section><section id="empty"/></document>',1,'empty'],
                ['<document version="1"><page width="420" height="600" margin="30"/><p id="shortcut-first">KEEP DOCUMENT</p></document>',0,'']
            ]){
                await load(source,{});await (await object('shortcut-first')).click();
                await page.locator('.studio-background').nth(index).click({position:{x:12,y:120}});await idle();
                await page.waitForFunction(owner=>selected?.id===owner&&['document','section'].includes(selected.tag),owner);
                assert.equal(await page.locator('#node-page-settings').isVisible(),true);
                const capture=()=>page.evaluate(()=>({source:state.source,data:state.data,resourceIdentity:state.resourceIdentity,revision:state.revision,pages:pages.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
                const before=await capture();
                for(const focus of ['page','canvas']){
                    if(focus==='canvas'){
                        await page.keyboard.press('Tab');
                        assert.equal(await page.evaluate(()=>document.activeElement?.getAttribute('aria-label')),'插入内容');
                        await page.keyboard.press('Enter');
                        await page.getByRole('menu',{name:'插入内容',exact:true}).waitFor();
                        await page.keyboard.press('Escape');
                        await page.getByRole('menu',{name:'插入内容',exact:true}).waitFor({state:'hidden'});
                        for(let step=0;step<5;step++){
                            await page.keyboard.press('Shift+Tab');
                            if(await page.locator('.canvas-scroll').evaluate(node=>node===document.activeElement))break;
                        }
                        assert.equal(await page.locator('.canvas-scroll').evaluate(node=>node===document.activeElement),true);
                    }
                    for(const key of ['Delete','Backspace','Control+d','Meta+d','Alt+ArrowUp','Alt+ArrowDown']){
                        await page.keyboard.press(key);await page.evaluate(()=>DesignerStudioBridge.settled());await idle();
                        assert.deepEqual(await capture(),before,owner+' '+focus+' '+key+' must preserve content, resources and history');
                    }
                }
                await page.keyboard.press('Shift+F10');
                await page.getByRole('menu',{name:'在空白区域插入',exact:true}).waitFor();
                assert.equal(await page.getByRole('menuitem',{name:'删除所选元素Delete',exact:true}).count(),0);
                await page.keyboard.press('Escape');assert.deepEqual(await capture(),before);
            }
            await load(sectioned,{});const before=await xml();await (await object('shortcut-first')).click();
            await page.keyboard.press('Control+d');await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('section').length),2);
            assert.deepEqual(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('section:first-of-type > p')].map(node=>node.textContent)),['KEEP ONE','KEEP ONE']);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before);
            for(const key of ['Delete','Backspace']){
                await (await object('shortcut-first')).click();await page.keyboard.press(key);await idle();
                assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('section').length),2);
                assert.doesNotMatch(await xml(),/KEEP ONE/);assert.match(await xml(),/KEEP TWO/);
                await page.locator('#undo').click();await idle();assert.equal(await xml(),before);
            }
            // Choosing a section explicitly in the structure tree retains its element commands.
            await page.locator('.tree-item[data-path="/1"]').click();await page.keyboard.press('Control+d');await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('section').length),3);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before);
            await page.locator('.tree-item[data-path="/1"]').click();await page.keyboard.press('Delete');await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('section').length),1);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before);
        });
        await check('blank page selection keeps navigation synchronized without scrolling',async()=>{
            await load('<document version="1"><page width="420" height="220" margin="30"/><section id="one"><p>ONE</p></section><section id="two"><p>TWO</p></section></document>',{});
            const before=await xml(),scroll=await page.locator('.canvas-scroll').evaluate(node=>node.scrollTop);
            for(const index of [1,0]){
                await page.locator('.studio-background').nth(index).click({position:{x:12,y:100}});await idle();
                assert.equal(await page.evaluate(()=>currentPage),index);
                assert.equal(await page.locator('#page-number').textContent(),(index+1)+' / 2');
                assert.equal(await page.locator('#page-jump').inputValue(),String(index));
                assert.equal(await page.locator('#previous').isDisabled(),index===0);
                assert.equal(await page.locator('#next').isDisabled(),index===1);
                assert.equal(await page.locator('.canvas-scroll').evaluate(node=>node.scrollTop),scroll);
                assert.equal(await page.evaluate(()=>selected.id),index===0?'one':'two');
            }
            await page.locator('#next').click();await idle();assert.equal(await page.locator('#page-jump').inputValue(),'1');
            await page.locator('#previous').click();await idle();assert.equal(await page.locator('#page-jump').inputValue(),'0');
            assert.equal(await xml(),before);
            await page.screenshot({path:path.join(output,'blank-page-navigation.png'),fullPage:true});
        });
        await check('page ownership survives section renames preview delays failures and relocation',async()=>{
            const source='<document version="1"><page width="420" height="220" margin="30"/><section id="one"><p id="owner-anchor">ONE</p></section><section id="empty"/></document>';
            for(const [index,renamed,delayed] of [[0,'renamed-one',false],[1,'renamed-empty',true]]){
                await load(source,{});await (await object('owner-anchor')).click();
                await page.locator('.studio-background').nth(index).click({position:{x:12,y:100}});await idle();
                let release;const gate=new Promise(resolve=>{release=resolve;});
                const handler=async route=>{await gate;await route.continue();};
                if(delayed)await page.route('**/api/preview',handler);
                try{
                    const request=delayed?page.waitForRequest('**/api/preview'):undefined;
                    await page.locator('#component-identity [data-attribute="id"]').fill(renamed);
                    await page.locator('.studio-background').nth(index).click({position:{x:12,y:100}});
                    if(request)await request;
                    await page.waitForFunction(id=>state.source.includes('id="'+id+'"'),renamed);
                    assert.equal(await page.evaluate(()=>selected.tag),'section');
                    assert.equal(await page.evaluate(()=>selected.id),renamed);
                }finally{release();if(delayed)await page.unrouteAll({behavior:'wait'});}
                await idle();await page.waitForFunction(id=>selected?.id===id,renamed);
                assert.equal(await page.evaluate(()=>selected.path),'/'+(index+1));
            }
            await load(source,{});await page.locator('.studio-background').first().click({position:{x:12,y:100}});await idle();
            const failure=route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({error:'controlled page ownership preview failure'})});
            await page.route('**/api/preview',failure);
            try{
                await page.locator('#component-identity [data-attribute="id"]').fill('retained-owner');
                await page.locator('.studio-background').first().click({position:{x:12,y:100}});
                await page.waitForFunction(()=>!busy&&DesignerStudioBridge.snapshot().layoutState==='failed');
                assert.equal(await page.evaluate(()=>selected.tag),'section');assert.equal(await page.evaluate(()=>selected.id),'retained-owner');
                assert.match(await xml(),/id="retained-owner"/);
            }finally{await page.unroute('**/api/preview',failure);}
            await page.evaluate(()=>preview());await idle();
            await load(source,{});
            let release;const gate=new Promise(resolve=>{release=resolve;});
            const handler=async route=>{await gate;await route.continue();};await page.route('**/api/preview',handler);
            try{
                await page.evaluate(async source=>{await DesignerStudioBridge.apply(source);await DesignerStudioBridge.flush();},source.replace('<section id="one">','<section id="inserted"/><section id="one">'));
                await page.locator('.studio-background').first().click({position:{x:12,y:100}});
                await page.waitForFunction(()=>!busy);assert.equal(await page.evaluate(()=>selected.id),'one');assert.equal(await page.evaluate(()=>selected.path),'/2');
            }finally{release();await page.unrouteAll({behavior:'wait'});}
            await idle();
        });
        await check('page ownership follows source identity across repagination and refuses ambiguous owners',async()=>{
            const first='<p id="identity-first">ONE</p>',growth='<p id="identity-first" line-height="100">ONE</p><p id="identity-extra" line-height="100">MORE</p>';
            for(const [kind,content,renamed,neighbor=''] of [['section-id','<p id="identity-next">TWO</p>',false],['child-id','<p id="identity-next">TWO</p>',true],['content','<p>TWO</p>',true],['empty','',true],['empty-neighbor','',true,'<section id="unchanged"/>']]){
                const source='<document version="1"><page width="420" height="220" margin="30"/><section id="one">'+first+'</section><section id="two">'+content+'</section>'+neighbor+'</document>';
                await load(source,{});
                const changed=source.replace(first,growth).replace('id="two"','id="'+(renamed?'renamed-two':'two')+'"');
                let release;const gate=new Promise(resolve=>{release=resolve;});
                await page.route('**/api/preview',async route=>{await gate;await route.continue();});
                try{
                    const requested=page.waitForRequest('**/api/preview');
                    await page.locator('#source-tab').click();await page.locator('#source').fill(changed);await page.locator('#canvas-tab').click();
                    assert.equal(await page.locator('.studio-background').count(),neighbor?3:2,'the clicked page must still be the old second section');
                    await page.locator('.studio-background').nth(1).click({position:{x:12,y:100}});await requested;
                }finally{release();await page.unrouteAll({behavior:'wait'});}
                await idle();assert.equal(await page.evaluate(()=>pages.length),neighbor?4:3,kind);
                assert.equal(await page.evaluate(()=>selected?.id),renamed?'renamed-two':'two',kind);
                assert.equal(await page.evaluate(()=>selected?.path),'/2',kind);
                assert.equal(await xml(),changed);
            }
            for(const [kind,sections,replacements] of [
                ['empty','<section id="two"/><section id="three"/>','<section id="renamed-two"/><section id="renamed-three"/>'],
                ['moved-child','<section id="two"><p id="identity-next">TWO</p></section><section id="three"><p>THREE</p></section>','<section id="renamed-two"/><section id="three"><p>THREE</p><p id="identity-next">TWO</p></section>'],
                ['split-children','<section id="two"><p id="identity-next">TWO</p><p id="identity-split">SPLIT</p></section><section id="three"><p>THREE</p></section>','<section id="renamed-two"><p id="identity-next">TWO</p></section><section id="renamed-three"><p>THREE</p><p id="identity-split">SPLIT</p></section>']
            ]){
                const source='<document version="1"><page width="420" height="220" margin="30"/><section id="one">'+first+'</section>'+sections+'<section id="keeper"><p>KEEP</p></section></document>';
                await load(source,{});await page.getByRole('treeitem',{name:'分节 keeper',exact:true}).click();
                const changed=source.replace(first,growth).replace(sections,replacements);
                let release;const gate=new Promise(resolve=>{release=resolve;});await page.route('**/api/preview',async route=>{await gate;await route.continue();});
                try{
                    const requested=page.waitForRequest('**/api/preview');
                    await page.locator('#source-tab').click();await page.locator('#source').fill(changed);await page.locator('#canvas-tab').click();
                    await page.locator('.studio-background').nth(1).click({position:{x:12,y:100}});await requested;
                }finally{release();await page.unrouteAll({behavior:'wait'});}
                await idle();assert.equal(await page.evaluate(()=>selected?.id),'keeper',kind+': an ambiguous old page must not select a different section after refresh');
                assert.match(await page.locator('#status').textContent(),/页面归属无法确认/);assert.equal(await xml(),changed);
                await page.locator('.studio-background').nth(2).click({position:{x:12,y:100}});await idle();
                assert.equal(await page.evaluate(()=>selected?.id),'renamed-two','choosing the page again uses its current owner');
            }
        });
        const refreshedMenuInsert=async({entry,kind,tag,changed,dataOnly=false})=>{
            const menu=page.getByRole('menu',{name:entry==='button'?'插入内容':'在空白区域插入',exact:true});
            const open=async index=>{
                if(entry==='button')await page.locator('.studio-host').nth(index).getByRole('button',{name:'插入内容',exact:true}).click();
                else await page.locator('.studio-background').nth(index).click({button:'right',position:{x:12,y:100}});
                await menu.waitFor();
            };
            let release;const gate=new Promise(resolve=>{release=resolve;});
            await page.route('**/api/preview',async route=>{await gate;await route.continue();});
            try{
                const requested=page.waitForRequest('**/api/preview');
                if(dataOnly)await page.evaluate(()=>{$('data').value=JSON.stringify({items:['ONE','MORE']});void preview();});
                else{await page.locator('#source-tab').click();await page.locator('#source').fill(changed);await page.locator('#canvas-tab').click();}
                await requested;assert.equal(await page.locator('.studio-background').count(),2);
                await open(1);
                release();await page.unrouteAll({behavior:'wait'});await idle();
                assert.equal(await page.locator('.studio-background').count(),3);
                assert.equal(await menu.count(),0,'a menu opened for an obsolete physical page must close after repagination');
                assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().source),changed,'refreshing an open menu must preserve the source draft');
            }finally{release();await page.unrouteAll({behavior:'wait'});}
            await page.evaluate(async()=>{await drafts();});await idle();
            assert.equal(await xml(),changed,'the refreshed source and data must be committed before testing insertion history');
            await open(2);
            await menu.getByRole('menuitem',{name:(entry==='context'?'插入':'')+kind,exact:true}).click();
            if(tag==='p'){await page.locator('.ProseMirror').waitFor();await page.keyboard.press('Control+Enter');}
            else if(tag==='grid'){await page.locator('#grid-layout-dialog').waitFor();await page.locator('[data-grid-layout="halves"]').click();}
            else{
                const dialog=page.getByRole('dialog',{name:'绑定数组数据',exact:true});await dialog.waitFor();
                await dialog.getByRole('textbox',{name:'数组路径',exact:true}).fill('items');await dialog.getByRole('textbox',{name:'迭代变量',exact:true}).fill('record');
                await dialog.getByRole('button',{name:'创建'+kind,exact:true}).click();
            }
            await idle();
            if(await page.locator('.ProseMirror').count()){await page.keyboard.press('Control+Enter');await idle();}
            const {contents,expectedFirst}=await page.evaluate(changed=>{
                const canonical=node=>node.nodeType===1?{tag:node.tagName,attrs:[...node.attributes].map(attribute=>[attribute.name,attribute.value]).sort(([a],[b])=>a<b?-1:a>b?1:0),children:[...node.childNodes].map(canonical)}:{type:node.nodeType,text:node.nodeValue};
                const document=new DOMParser().parseFromString(state.source,'application/xml'),expected=new DOMParser().parseFromString(changed,'application/xml');
                return {contents:[...document.querySelectorAll('document > section')].map(section=>({id:section.id,structure:canonical(section),tags:[...section.children].map(node=>node.tagName)})),expectedFirst:canonical(expected.querySelector('section[id="one"]'))};
            },changed);
            assert.deepEqual(contents[0].structure,expectedFirst,'the former physical page must not receive the new content');
            assert.equal(contents[1].id,'two');assert.deepEqual(contents[1].tags,[tag]);
            assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().layoutState),'synced');
            for(let step=0;step<4&&await xml()!==changed;step++){await page.locator('#undo').click();await idle();}
            assert.equal(await xml(),changed,'insertion into the refreshed owner must remain undoable');
        };
        await check('insertion menus close when source repagination invalidates their page',async()=>{
            const first='<p id="menu-first">ONE</p>',source='<document version="1"><page width="420" height="220" margin="30"/><section id="one">'+first+'</section><section id="two"/></document>';
            const changed=source.replace(first,'<p id="menu-first" line-height="100">ONE</p><p id="menu-extra" line-height="100">MORE</p>');
            for(const [entry,kind,tag] of [['button','段落','p'],['button','独立图文栅格','grid'],['button','数据表格','rows'],['context','段落','p']]){
                await load(source,{items:[{name:'Alpha'}]});
                await refreshedMenuInsert({entry,kind,tag,changed});
            }
        });
        await check('insertion menus close when data-only repagination invalidates their page',async()=>{
            const source='<document version="1"><page width="420" height="220" margin="30"/><section id="one"><each items="items" as="item"><p id="menu-data" line-height="100">${item}</p></each></section><section id="two"/></document>';
            for(const entry of ['button','context']){
                await load(source,{items:['ONE']});
                await refreshedMenuInsert({entry,kind:'段落',tag:'p',changed:source,dataOnly:true});
                assert.deepEqual(await page.evaluate(()=>JSON.parse(state.data).items),['ONE','MORE']);
            }
        });
        await check('blank page insertion rechecks its target after awaiting pending edits',async()=>{
            const first='<p id="waiting-first">ONE</p>',source='<document version="1"><page width="420" height="220" margin="30"/><section id="one">'+first+'</section><section id="two"/></document>';
            const changed=source.replace(first,'<p id="waiting-first" line-height="100">ONE</p><p id="waiting-extra" line-height="100">MORE</p>');
            await load(source,{});
            let release;const gate=new Promise(resolve=>{release=resolve;});await page.route('**/api/preview',async route=>{await gate;await route.continue();});
            try{
                const requested=page.waitForRequest('**/api/preview');
                await page.locator('#source-tab').click();await page.locator('#source').fill(changed);await page.locator('#canvas-tab').click();await requested;
                assert.equal(await page.locator('.studio-background').count(),2);
                await page.locator('.studio-background').nth(1).click({button:'right',position:{x:12,y:100}});
                const before=await page.evaluate(()=>({source:DesignerStudioBridge.snapshot().source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
                await page.evaluate(()=>{const original=DesignerStudioBridge.settled;window.menuOriginalSettled=original;window.menuAwaitingSettled=false;window.menuSettledGate=new Promise(resolve=>window.menuReleaseSettled=resolve);DesignerStudioBridge.settled=async()=>{await original();window.menuAwaitingSettled=true;await window.menuSettledGate;};});
                await page.getByRole('menu',{name:'在空白区域插入',exact:true}).getByRole('menuitem',{name:'插入段落',exact:true}).click();
                await page.waitForFunction(()=>window.menuAwaitingSettled);
                release();await page.unrouteAll({behavior:'wait'});await page.waitForFunction(()=>pages.length===3);
                await page.evaluate(()=>{DesignerStudioBridge.settled=window.menuOriginalSettled;window.menuReleaseSettled();});await idle();
                assert.deepEqual(await page.evaluate(()=>({source:DesignerStudioBridge.snapshot().source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo})),before,'an insertion started on the old page must reject after pending work refreshes the pages');
                assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().source),changed);assert.equal(await page.locator('.ProseMirror').count(),0);
            }finally{
                await page.evaluate(()=>{if(window.menuOriginalSettled)DesignerStudioBridge.settled=window.menuOriginalSettled;window.menuReleaseSettled?.();delete window.menuOriginalSettled;delete window.menuAwaitingSettled;delete window.menuReleaseSettled;delete window.menuSettledGate;});
                release();await page.unrouteAll({behavior:'wait'});
            }
        });
        await check('blank page property selection rechecks its target after awaiting pending edits',async()=>{
            const first='<p id="property-first">ONE</p>',source='<document version="1"><page width="420" height="220" margin="30"/><section id="one">'+first+'</section><section id="two"/></document>';
            const changed=source.replace(first,'<p id="property-first" line-height="100">ONE</p><p id="property-extra" line-height="100">MORE</p>');
            await load(source,{});await page.evaluate(()=>DesignerStudioBridge.select([entries.find(entry=>entry.id==='property-first').path]));
            let release;const gate=new Promise(resolve=>{release=resolve;});await page.route('**/api/preview',async route=>{await gate;await route.continue();});
            try{
                const requested=page.waitForRequest('**/api/preview');
                await page.locator('#source-tab').click();await page.locator('#source').fill(changed);await page.locator('#canvas-tab').click();await requested;
                assert.equal(await page.locator('.studio-background').count(),2);
                const before=await page.evaluate(()=>({source:DesignerStudioBridge.snapshot().source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
                await page.evaluate(()=>{const original=DesignerStudioBridge.settled;window.propertyOriginalSettled=original;window.propertyAwaitingSettled=false;window.propertySettledGate=new Promise(resolve=>window.propertyReleaseSettled=resolve);DesignerStudioBridge.settled=async()=>{await original();window.propertyAwaitingSettled=true;await window.propertySettledGate;};});
                await page.locator('.studio-background').nth(1).click({position:{x:12,y:100}});await page.waitForFunction(()=>busy&&window.propertyAwaitingSettled);
                release();await page.unrouteAll({behavior:'wait'});await page.waitForFunction(()=>pages.length===3);
                await page.evaluate(()=>{DesignerStudioBridge.settled=window.propertyOriginalSettled;window.propertyReleaseSettled();});await idle();
                assert.equal(await page.evaluate(()=>selected?.id),'property-first','an obsolete blank-page click must preserve the previous component selection');
                assert.deepEqual(await page.evaluate(()=>({source:DesignerStudioBridge.snapshot().source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo})),before,'rejecting an obsolete property target must preserve drafts, resources and history');
                assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().source),changed);assert.match(await page.locator('#status').textContent(),/重新选择/);
            }finally{
                await page.evaluate(()=>{if(window.propertyOriginalSettled)DesignerStudioBridge.settled=window.propertyOriginalSettled;window.propertyReleaseSettled?.();delete window.propertyOriginalSettled;delete window.propertyAwaitingSettled;delete window.propertyReleaseSettled;delete window.propertySettledGate;});
                release();await page.unrouteAll({behavior:'wait'});
            }
            await page.locator('.studio-background').nth(2).click({position:{x:12,y:100}});await idle();await page.waitForFunction(()=>selected?.id==='two');
            assert.equal(await page.locator('#node-page-settings').isVisible(),true);assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().source),changed);
        });
        await check('grid chooser rejects a page target invalidated while the dialog is open',async()=>{
            const first='<p id="chooser-first">ONE</p>',source='<document version="1"><page width="420" height="220" margin="30"/><section id="one">'+first+'</section><section id="two"/></document>';
            const changed=source.replace(first,'<p id="chooser-first" line-height="100">ONE</p><p id="chooser-extra" line-height="100">MORE</p>');
            await load(source,{});
            await page.evaluate(async changed=>{await DesignerStudioBridge.apply(changed);await DesignerStudioBridge.flush();clearTimeout(previewTimer);},changed);
            let release;const gate=new Promise(resolve=>{release=resolve;});await page.route('**/api/preview',async route=>{await gate;await route.continue();});
            try{
                const requested=page.waitForRequest('**/api/preview');
                await page.evaluate(()=>{void preview();});await requested;
                assert.equal(await page.locator('.studio-background').count(),2);
                await page.locator('.studio-host').nth(1).getByRole('button',{name:'插入内容',exact:true}).click();
                await page.getByRole('menu',{name:'插入内容',exact:true}).getByRole('menuitem',{name:'独立图文栅格',exact:true}).click();await page.locator('#grid-layout-dialog').waitFor();
                const before=await page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
                release();await page.unrouteAll({behavior:'wait'});await page.waitForFunction(()=>pages.length===3);
                await page.locator('[data-grid-layout="halves"]').click();await idle();
                assert.equal(await page.locator('#grid-layout-dialog').evaluate(node=>node.open),false);
                assert.deepEqual(await page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo})),before,'confirming an obsolete grid target must preserve source, resources and history');
                assert.equal(await xml(),changed);
            }finally{release();await page.unrouteAll({behavior:'wait'});await page.keyboard.press('Escape');}
        });
        await check('document property insertion uses the current body section from keyboard and toolbar',async()=>{
            for(const [body,index,owner,entry] of [
                ['<section id="one"><p>ONE</p></section><section id="two"><p>TWO</p></section>',1,'two','keyboard'],
                ['<section id="one"><p>ONE</p></section><section id="empty"/>',1,'empty','keyboard'],
                ['<section id="one"><p>ONE</p></section><section id="empty"/>',1,'empty','toolbar'],
                ['<p>ROOT</p>',0,'','keyboard']
            ]){
                await load('<document version="1"><page width="420" height="220" margin="30"/>'+body+'</document>',{});
                await page.locator('.studio-background').nth(index).click({position:{x:12,y:100}});await idle();
                const bounds=await page.locator('.canvas-scroll').boundingBox();assert.ok(bounds);
                await page.mouse.click(bounds.x+5,bounds.y+20);await page.waitForFunction(()=>!busy&&selected.tag==='document');
                const before=await xml();
                if(entry==='keyboard'){
                    await page.keyboard.press('Shift+F10');await page.getByRole('menu',{name:'在空白区域插入',exact:true}).getByRole('menuitem',{name:'插入段落',exact:true}).click();
                }else{await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="p"]').click();}
                await page.locator('.ProseMirror').waitFor();await page.keyboard.press('Home');await page.keyboard.press('Shift+End');await page.keyboard.type('INSERTED BODY');await page.keyboard.press('Control+Enter');await idle();
                const inserted=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),node=[...document.querySelectorAll('p')].find(node=>node.textContent==='INSERTED BODY');return {owner:node?.closest('section')?.id||'',rootCount:document.querySelectorAll('document > p').length};});
                assert.deepEqual(inserted,{owner,rootCount:owner?0:2});
                assert.deepEqual(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('p')].filter(node=>node.textContent!=='INSERTED BODY').map(node=>node.textContent)),[...body.matchAll(/<p>(.*?)<\/p>/g)].map(match=>match[1]));
                for(let step=0;step<4&&await xml()!==before;step++){await page.locator('#undo').click();await idle();}
                assert.equal(await xml(),before,'insert and text edits must remain undoable');
            }
        });
        await check('empty page insertion uses its owner instead of another selected section',async()=>{
            for(const [kind,entry,tag] of [
                ['段落','button','p'],['独立图文栅格','button','grid'],
                ['数据列表','button','list'],['数据表格','button','rows'],
                ['段落','context','p'],['独立图文栅格','context','grid']
            ]){
                await load('<document version="1"><page width="420" height="220" margin="30"/><section id="one"><p id="other-section">ONE</p></section><section id="empty"/></document>',{items:[{name:'Alpha'}]});
                await (await object('other-section')).click();
                const before=await xml();
                if(entry==='button')await page.locator('.studio-host').nth(1).getByRole('button',{name:'插入内容',exact:true}).click();
                else await page.locator('.studio-background').nth(1).click({button:'right',position:{x:12,y:100}});
                await page.getByRole('menu',{name:entry==='button'?'插入内容':'在空白区域插入',exact:true}).getByRole('menuitem',{name:(entry==='context'?'插入':'')+kind,exact:true}).click();
                if(kind==='段落'){await page.locator('.ProseMirror').waitFor();await page.keyboard.type('EMPTY PAGE');await page.keyboard.press('Control+Enter');}
                else if(kind==='独立图文栅格'){await page.locator('#grid-layout-dialog').waitFor();await page.locator('[data-grid-layout="halves"]').click();}
                else{
                    const dialog=page.getByRole('dialog',{name:'绑定数组数据',exact:true});await dialog.waitFor();
                    await dialog.getByRole('textbox',{name:'数组路径',exact:true}).fill('items');await dialog.getByRole('textbox',{name:'迭代变量',exact:true}).fill('record');
                    await dialog.getByRole('button',{name:'创建'+kind,exact:true}).click();
                }
                await idle();
                if(await page.locator('.ProseMirror').count()){await page.keyboard.press('Control+Enter');await idle();}
                const contents=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return [...document.querySelectorAll('document > section')].map(section=>[...section.children].map(node=>({tag:node.tagName,text:node.textContent})));});
                assert.deepEqual(contents[0],[{tag:'p',text:'ONE'}]);assert.equal(contents[1].length,1);
                assert.equal(contents[1][0].tag,tag);
                if(kind==='段落')assert.ok(contents[1][0].text.includes('EMPTY PAGE'));
                assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().layoutState),'synced');
                for(let step=0;step<4&&await xml()!==before;step++){await page.locator('#undo').click();await idle();}
                assert.equal(await xml(),before,entry+' '+kind+' must remain undoable');
            }
            await load('<document version="1"><page width="420" height="600" margin="30"/><navigation headings="true"><contents title="Contents"/></navigation><section id="body"><heading id="contents-heading" level="1">TITLE</heading></section><section id="empty"/></document>',{});
            await page.locator('.studio-background').first().click({position:{x:12,y:180}});await idle();
            assert.equal(await page.evaluate(()=>selected.tag),'document');
            const capture=()=>page.evaluate(()=>({source:state.source,data:state.data,revision:state.revision,resourceIdentity:state.resourceIdentity,selection:selectedPaths,canUndo:DesignerStudioBridge.snapshot().canUndo}));
            const before=await capture();
            for(const entry of ['keyboard','button']){
                if(entry==='keyboard')await page.keyboard.press('Shift+F10');
                else await page.locator('.studio-host').first().getByRole('button',{name:'插入内容',exact:true}).click();
                await page.getByRole('menu',{name:entry==='keyboard'?'在空白区域插入':'插入内容',exact:true}).getByRole('menuitem',{name:entry==='keyboard'?'插入段落':'段落',exact:true}).click();
                await page.waitForFunction(()=>!busy&&$('status').textContent.includes('此页不属于正文分节'));
                assert.deepEqual(await capture(),before,'generated contents page must reject insertion before source or resources change');
                await page.keyboard.press('Escape');
            }
        });
        await check('workflow v2 table column borders support drag cancel keyboard and exact sizing',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><table id="sizing" padding-left="9" padding-right="11"><columns><column weight="1"/><column weight="1"/></columns><header><cell>FIRST</cell><cell>SECOND</cell></header><row><cell>VALUE-A</cell><cell>VALUE-B</cell></row></table></document>',{});
            await (await object('sizing')).click();const divider=page.getByRole('separator',{name:'调整表格第 1、2 列宽',exact:true});await divider.waitFor();
            const before=await xml(),box=await divider.boundingBox();assert.ok(box);const initial=Number(await divider.getAttribute('aria-valuenow'));
            await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+35,box.y+box.height/2,{steps:5});await page.keyboard.press('Escape');await page.mouse.up();assert.equal(await xml(),before,'Esc must cancel without changing column declarations');
            const again=await divider.boundingBox();await page.mouse.move(again.x+again.width/2,again.y+again.height/2);await page.mouse.down();await page.mouse.move(again.x+again.width/2+35,again.y+again.height/2,{steps:5});await page.mouse.up();await idle();
            assert.ok(Number(await divider.getAttribute('aria-valuenow'))>initial+15);assert.match(await xml(),/VALUE-A/);assert.match(await xml(),/VALUE-B/);assert.match(await xml(),/<column width=/);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before);await page.locator('#redo').click();await idle();
            await divider.focus();const prior=Number(await divider.getAttribute('aria-valuenow'));await page.keyboard.press('ArrowLeft');await idle();assert.ok(Math.abs(Number(await divider.getAttribute('aria-valuenow'))-(prior-1))<.01);
            await page.getByLabel('精确列宽',{exact:true}).click();await page.getByLabel('当前列宽（pt）',{exact:true}).fill('200');await page.getByRole('button',{name:'应用列宽',exact:true}).click();await idle();assert.ok(Math.abs(Number(await divider.getAttribute('aria-valuenow'))-200)<.01);
            await page.screenshot({path:path.join(output,'workflow-v2-table-widths.png'),fullPage:true});
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'workflow-v2-table-widths.pdf'));await page.locator('#close-export').click();
        });
        await check('workflow v2 merged tables keep spans during direct row and column operations',async()=>{
            const errorsBefore=errors.length;
            await load('<document version="1"><page size="A4" margin="42"/><table id="merged-edit"><columns><column weight="1"/><column weight="1"/></columns><row><cell row-span="2">MERGED</cell><cell>RIGHT-A</cell></row><row><cell>RIGHT-B</cell></row><row><cell>LEFT-C</cell><cell>RIGHT-C</cell></row></table></document>',{});
            await page.locator('.tree-item[data-path="/1/1/1"]').click();const actions=page.getByLabel('当前单元格的行列与合并',{exact:true});await actions.click();await page.getByRole('button',{name:'在下方插入行',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('table > row > cell').getAttribute('row-span')),'3');
            await actions.click();await page.getByRole('button',{name:'在右侧插入列',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('table > columns > column').length),3);assert.match(await xml(),/RIGHT-A/);assert.match(await xml(),/RIGHT-B/);
            await actions.click();await page.getByRole('button',{name:'选格与合并／拆分',exact:true}).click();await page.locator('#table-dialog').waitFor();assert.ok(await page.locator('#table-cell-grid button').count()>0);
            await page.locator('#table-cell-grid button').first().click();assert.equal(await page.locator('#table-cell-grid button').first().getAttribute('aria-pressed'),'true');
            await page.locator('#table-cell-grid button').nth(1).click({modifiers:['Control']});assert.match(await page.locator('#table-selection-count').textContent(),/已选 2 格/);
            await page.locator('#close-table-dialog').click();assert.equal(await page.locator('#table-dialog').isVisible(),false);
            assert.deepEqual(errors.slice(errorsBefore),[],'selecting cells and closing layout must also work while the inspector only exposes the owning-table shortcut');
            await page.screenshot({path:path.join(output,'workflow-v2-merged-table.png'),fullPage:true});
        });
        await check('workflow v2 inner paragraph commands preserve the outer container',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><table id="inner-actions"><columns><column weight="1"/></columns><row><cell><p id="inner-first">INNER <span role="bold">BOLD</span></p><p id="inner-next">NEXT</p></cell></row></table></document>',{});
            await edit('inner-first');await finish();await page.getByLabel('更多容器操作',{exact:true}).click();await page.getByRole('button',{name:'复制当前段落',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('table').length),1);assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('cell > p').length),3);
            await page.getByLabel('更多容器操作',{exact:true}).click();await page.getByRole('button',{name:'删除当前段落',exact:true}).click();await idle();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('cell > p').length),2);assert.match(await xml(),/INNER/);assert.match(await xml(),/NEXT/);
            await page.locator('#undo').click();await idle();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('cell > p').length),3);
        });
        await check('workflow v2 sample arrays preserve unrelated exact JSON values through both insert menus',async()=>{
            for(const kind of ['list','rows']){
                await load('<document version="1"><page size="A4" margin="42"/><p id="exact-anchor">EXACT DATA</p></document>',{});
                const raw='{"invoiceId":9007199254740993,"amount":0.10000000000000000001,"exponent":1.2300e-40}';
                await page.evaluate(async raw=>{$('data').value=raw;await DesignerStudioBridge.apply(state.source);await DesignerStudioBridge.flush();},raw);await idle();
                await (await object('exact-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="'+kind+'"]').click();
                const dialog=page.getByRole('dialog',{name:'绑定数组数据',exact:true});await dialog.waitFor();await dialog.getByRole('button',{name:kind==='list'?'创建数据列表':'创建数据表格',exact:true}).click();await idle();
                const actual=await page.evaluate(()=>state.data);for(const token of ['9007199254740993','0.10000000000000000001','1.2300e-40'])assert.ok(actual.includes(token),kind+': '+actual);assert.ok(actual.includes('示例项'));
                await writeFile(path.join(output,'workflow-v2-exact-'+kind+'.json'),actual);
            }
        });
        await check('workflow v2 mixed resource and text operations undo and redo in their actual order',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><p id="history-mixed">HISTORY</p></document>',{});
            const png=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=8;canvas.height=6;canvas.getContext('2d').fillRect(0,0,8,6);return canvas.toDataURL('image/png').split(',')[1];});
            await page.locator('#resources-tab').click();await page.evaluate(()=>{assetKind='image';$('asset-file').value='';});await page.locator('#asset-file').setInputFiles({name:'mixed-history.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});await page.locator('#question-answer').fill('mixed_history_asset');await page.locator('#question-ok').click();await idle();
            await page.locator('#navigator-tab').click();await edit('history-mixed');await page.keyboard.press('Control+End');await page.keyboard.insertText('-EDIT');await finish();
            const imageExists=()=>page.evaluate(()=>state.images.some(image=>image.name==='mixed_history_asset'));
            await page.locator('#undo').click();await idle();assert.match(await xml(),/>HISTORY<\/p>/);assert.equal(await imageExists(),true);assert.equal(await page.locator('#undo').isEnabled(),true);
            await page.locator('#undo').click();await idle();assert.equal(await imageExists(),false);await page.locator('#redo').click();await idle();assert.equal(await imageExists(),true);assert.match(await xml(),/>HISTORY<\/p>/);
            await page.locator('#redo').click();await idle();assert.match(await xml(),/HISTORY-EDIT/);assert.equal(await imageExists(),true);
        });
        await check('workflow v2 HTML table paste preserves separators inside the current cell',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><table id="clipboard-table"><columns><column weight="1"/><column weight="1"/></columns><row><cell>A</cell><cell>B</cell></row><row><cell>C</cell><cell>D</cell></row></table></document>',{});
            await page.locator('.studio-object[data-studio-path="/1/1/0"]').first().dblclick();await page.locator('.ProseMirror').waitFor();await page.locator('.ProseMirror').focus();await page.keyboard.press('Control+a');
            await page.locator('.ProseMirror').evaluate(element=>{const transfer=new DataTransfer();transfer.setData('text/plain','ONE\tTWO\nTHREE\tFOUR');transfer.setData('text/html','<table><tr><td>ONE</td><td>TWO</td></tr><tr><td>THREE</td><td>FOUR</td></tr></table>');element.dispatchEvent(new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true}));});await finish();
            assert.deepEqual(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('cell')].map(cell=>cell.textContent)),['ONE\tTWO\nTHREE\tFOUR','B','C','D']);
        });
        await check('workflow v2 preview failures never show a synchronized success label',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><table id="status-current"><columns><column weight="1"/><column weight="1"/></columns><row><cell>VALID ${name}</cell><cell>UNCHANGED</cell></row></table></document>',{name:'PRESENT'});await page.getByRole('button',{name:'精确排版',exact:true}).click();await idle();
            await (await object('status-current')).click();await page.locator('.studio-table-divider').waitFor();
            await page.evaluate(async()=>{$('data').value='{}';await DesignerStudioBridge.apply(state.source);await DesignerStudioBridge.flush();});await page.waitForFunction(()=>!busy&&DesignerStudioBridge.snapshot().layoutState==='failed');
            assert.match(await page.locator('#status').textContent(),/MISSING_VALUE/);assert.doesNotMatch(await page.locator('.studio-sync-state').textContent(),/版式已同步/);assert.match(await page.locator('#preview-status').textContent(),/保留上次预览/);assert.equal(await page.locator('.studio-table-divider').count(),0);
            await page.screenshot({path:path.join(output,'workflow-v2-preview-failed.png'),fullPage:true});
            await page.evaluate(async()=>{$('data').value='{"name":"RECOVERED"}';await DesignerStudioBridge.apply(state.source);await DesignerStudioBridge.flush();});await idle();await page.locator('.studio-table-divider').waitFor();
        });
        await check('grid preview failure cancels a live drag without changing the source',async()=>{
            const source='<document version="1"><page width="420" height="600" margin="30"/><grid id="grid" tracks="12" column-gap="12"><item id="left" row="1" column-start="1" column-span="6"><p>${body}</p></item><item id="right" row="1" column-start="7" column-span="6"><p>B</p></item></grid></document>';
            await load(source,{body:'Short body'});await page.getByRole('button',{name:'精确排版',exact:true}).click();await idle();
            await page.evaluate(async()=>{await DesignerStudioBridge.choose(entries.find(entry=>entry.id==='left').path);});
            const divider=page.locator('.studio-grid-divider');await divider.waitFor();const bounds=await divider.boundingBox();assert.ok(bounds);
            await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();await page.mouse.move(bounds.x+bounds.width/2+35,bounds.y+bounds.height/2);
            await page.locator('[data-grid-drag="boundary"]').waitFor();
            await page.evaluate(async()=>{$('data').value=JSON.stringify({body:'overflow '.repeat(3000)});await DesignerStudioBridge.apply(state.source);await DesignerStudioBridge.flush();});
            await page.waitForFunction(()=>!busy&&DesignerStudioBridge.snapshot().layoutState==='failed');
            assert.equal(await divider.count(),0);assert.equal(await page.locator('[data-grid-drag="boundary"]').count(),0);
            await page.mouse.up();await page.evaluate(async()=>{await DesignerStudioBridge.settled();});
            assert.equal(await xml(),source);assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().layoutState),'failed');
            await page.screenshot({path:path.join(output,'grid-preview-failed.png'),fullPage:true});
        });
        await check('fixed component drag refuses pending preview geometry and works after synchronization',async()=>{
            const source='<document version="1"><page width="420" height="300" margin="30"/><foreground><box x="40" y="70" width="160" height="50"><p id="pending-fixed" line-height="20">FIXED</p></box></foreground><p id="pending-body">BODY</p></document>',changed=source.replace('x="40"','x="100"');
            await load(source,{});await page.getByRole('button',{name:'精确排版',exact:true}).click();await idle();await page.evaluate(()=>DesignerStudioBridge.select([entries.find(entry=>entry.id==='pending-fixed').path]));
            const capture=()=>page.evaluate(()=>({source:state.source,data:state.data,revision:state.revision,identity:state.resourceIdentity,resources:state.images.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
            let release;const gate=new Promise(resolve=>{release=resolve;});await page.route('**/api/preview',async route=>{await gate;await route.continue();});
            try{
                const requested=page.waitForRequest('**/api/preview');
                await page.evaluate(async changed=>{await DesignerStudioBridge.apply(changed);await DesignerStudioBridge.flush();clearTimeout(previewTimer);void preview();},changed);await requested;
                const before=await capture(),handle=page.getByRole('button',{name:'拖动固定组件',exact:true}),bounds=await handle.boundingBox();assert.ok(bounds);
                assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().layoutState),'pending');
                await page.locator('.studio-surface').first().focus();await page.keyboard.press('ArrowRight');
                assert.deepEqual(await capture(),before,'keyboard geometry must not replace the current x=100 source with a coordinate from the old preview');
                await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();
                assert.equal(await page.locator('.studio-surface[data-gesture]').count(),0,'pending preview geometry must not start a component gesture');
                release();await page.unrouteAll({behavior:'wait'});await page.waitForFunction(()=>DesignerStudioBridge.snapshot().layoutState==='synced');
                await page.mouse.move(bounds.x+bounds.width/2+20,bounds.y+bounds.height/2+20,{steps:4});await page.mouse.up();await idle();
                assert.deepEqual(await capture(),before,'releasing an obsolete drag must preserve the current source, resources and history');assert.equal(await xml(),changed);
            }finally{release();await page.unrouteAll({behavior:'wait'});await page.mouse.up();}
            await page.locator('.studio-surface').first().focus();await page.keyboard.press('ArrowRight');await idle();
            assert.equal(await page.evaluate(()=>Number(new DOMParser().parseFromString(state.source,'application/xml').querySelector('box').getAttribute('x'))),101,'a fresh synchronized keyboard edit must move the current x=100 component by 1 pt');
            await page.locator('#undo').click();await idle();assert.equal(await xml(),changed,'undo the keyboard edit before testing the fresh drag');
            await holdDrag(page.getByRole('button',{name:'拖动固定组件',exact:true}),20,16);
            const moved=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),box=document.querySelector('box');return {x:Number(box.getAttribute('x')),y:Number(box.getAttribute('y')),text:document.documentElement.textContent};});
            assert.ok(moved.x>100&&moved.y>70,'a fresh synchronized gesture must move the current component');assert.equal(moved.text,'FIXEDBODY');
            await page.locator('#undo').click();await idle();assert.equal(await xml(),changed,'the fresh drag must remain undoable');
        });
        await check('grid swaps survive scrolling between pages and remain cancellable and undoable',async()=>{
            const source='<document version="1"><page width="420" height="220" margin="30"/><grid id="scroll-grid" tracks="12"><item id="scroll-first" row="1" column-start="1" column-span="12"><p id="scroll-a" line-height="120">ROW ONE</p></item><item id="scroll-next" row="2" column-start="1" column-span="12"><p id="scroll-b" line-height="120">ROW TWO</p></item></grid></document>';
            await page.setViewportSize({width:1440,height:700});
            try{
                await load(source,{});assert.equal(await page.evaluate(()=>pages.length),2);
                const dragAcross=async()=>{
                    await page.locator('#page-jump').selectOption('0');await page.waitForFunction(()=>currentPage===0);
                    await page.getByRole('treeitem',{name:'栅格项 scroll-first',exact:true}).click();
                    const grip=await page.getByRole('button',{name:'拖动栅格项 scroll-first',exact:true}).boundingBox();assert.ok(grip);
                    await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2);await page.mouse.down();await page.locator('[data-grid-drag="swap"]').waitFor();
                    const identity=await page.evaluate(()=>({source:state.source,revision:state.revision,previewId}));
                    await page.mouse.wheel(0,450);await page.waitForFunction(()=>currentPage===1);
                    assert.deepEqual(await page.evaluate(()=>({source:state.source,revision:state.revision,previewId})),identity,'scrolling must keep the same source and precise preview');
                    assert.equal(await page.locator('[data-grid-drag="swap"]').count(),1,'scrolling to a different current page must preserve the drag');
                    const target=await page.locator('.studio-object[data-studio-path="/1/1"]').boundingBox();assert.ok(target);
                    await page.mouse.move(target.x+target.width/2,target.y+target.height/2,{steps:5});await page.locator('.studio-grid-swap-target').waitFor();
                };
                await dragAcross();await page.mouse.up();await idle();
                assert.deepEqual(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('item')].map(node=>({id:node.id,row:node.getAttribute('row'),text:node.textContent}))),[{id:'scroll-next',row:'1',text:'ROW TWO'},{id:'scroll-first',row:'2',text:'ROW ONE'}]);
                await page.screenshot({path:path.join(output,'grid-swap-after-scroll.png'),fullPage:true});
                await page.locator('#undo').click();await idle();assert.equal(await xml(),source);
                await dragAcross();await page.keyboard.press('Escape');await page.mouse.up();await idle();
                assert.equal(await page.locator('[data-grid-drag="swap"]').count(),0);assert.equal(await xml(),source);
            }finally{await page.mouse.up();await page.setViewportSize({width:1440,height:1050});}
        });
        await check('image paste respects page margins in pt mm cm and in including edge and section overrides',async()=>{
            const cases=['margin="72"','margin="72pt"','margin="25.4mm"','margin="2.54cm"','margin="1in"','margin="12" margin-left="1in" margin-right="25.4mm" margin-top="2.54cm" margin-bottom="72pt"'];
            for(const attributes of cases){
                await load('<document version="1"><page width="420" height="600" '+attributes+'/><p id="paste-unit">Content</p></document>',{});
                await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=1000;canvas.height=1000;canvas.getContext('2d').fillRect(0,0,1000,1000);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'units.png',{type:'image/png'}),0,0,0,'/1');});await idle();
                const images=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('image')].map(image=>({width:Number(image.getAttribute('width')),height:Number(image.getAttribute('height'))})));
                assert.deepEqual(images,[{width:272,height:272}],attributes);
            }
            await load('<document version="1"><page width="420" height="600" margin="12"/><section id="paste-section"><page margin="1in"/><p id="paste-unit">Section content</p></section></document>',{});
            await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=1000;canvas.height=1000;canvas.getContext('2d').fillRect(0,0,1000,1000);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'section.png',{type:'image/png'}),0,0,0,'/1/1');});await idle();
            assert.deepEqual(await page.evaluate(()=>{const image=new DOMParser().parseFromString(state.source,'application/xml').querySelector('section image');return {width:Number(image?.getAttribute('width')),height:Number(image?.getAttribute('height'))};}),{width:272,height:272});
        });
        await check('image paste uses measured body and remaining shared page region space',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            const cases=[
                ['body','<document version="1"><page width="420" height="600" margin="30"/><header height="60"><p>HEADER</p></header><footer height="60"><p>FOOTER</p></footer><p id="image-anchor">BODY</p></document>','/3',100,1000,416],
                ['header','<document version="1"><page width="420" height="600" margin="30"/><header height="60"><p id="image-anchor" line-height="18" space-after="6">HEADER</p></header><p>BODY</p></document>','/1/0',1000,1000,32],
                ['footer','<document version="1"><page width="420" height="600" margin="30"/><footer height="60"><p id="image-anchor" line-height="18" space-after="6">FOOTER</p></footer><p>BODY</p></document>','/1/0',1000,1000,32],
                ['shared','<document version="1"><page width="420" height="600" margin="30"/><header source-id="shared-image-header" height="100"><p id="image-anchor">One two three four five six seven eight nine ten eleven twelve.</p></header><section id="wide"><p>WIDE</p></section><section id="narrow"><page width="220" height="600"/><header source-ref="shared-image-header"/><p>NARROW</p></section></document>','/1/0',1000,1000,96],
                ['cell','<document version="1"><page width="420" height="600" margin="30"/><table><columns><column width="80"/><column weight="1"/></columns><row><cell>CELL</cell><cell>OTHER</cell></row></table></document>','/1/1/0',1000,1000,64],
                ['columns','<document version="1"><page width="420" height="600" margin="30"/><columns count="2"><p id="image-anchor">COLUMN</p></columns></document>','/1/0',100,1000,536],
                ['overlay','<document version="1"><page width="420" height="600" margin="30" margin-top="260"/><foreground><box x="30" y="30" width="160" height="80"><p id="image-anchor" line-height="18" space-after="6">BOX</p></box></foreground><section id="one"><p>BODY ONE</p></section><section id="two"><page width="320" height="600"/><p>BODY TWO</p></section></document>','/1/0/0',1000,1000,156]
            ];
            for(const [name,source,placementPath,width,height,maximum] of cases){
                await load(source,{});const before=await page.evaluate(()=>({source:state.source,resources:state.images.length,identity:state.resourceIdentity}));
                await page.evaluate(async({placementPath,width,height})=>{const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;canvas.getContext('2d').fillRect(0,0,width,height);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'measured.png',{type:'image/png'}),0,0,0,placementPath);},{placementPath,width,height});await idle();
                const actual=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),image=document.querySelector('image[ref^="pasted_"]:last-of-type');return {source:state.source,layout:DesignerStudioBridge.snapshot().layoutState,status:$('status').textContent,height:Number(image?.getAttribute('height')),resources:state.images.length};});
                assert.equal(actual.layout,'synced',name+': '+actual.status);assert.ok(actual.height>0&&actual.height<=maximum+1e-6,name+': '+actual.height);assert.equal(actual.resources,before.resources+1,name);
                await page.locator('#undo').click();await idle();assert.equal(await xml(),before.source,name);assert.equal(await page.evaluate(()=>state.resourceIdentity),before.identity,name);
                await page.locator('#redo').click();await idle();assert.equal(await xml(),actual.source,name);assert.equal(await page.evaluate(()=>state.images.length),before.resources+1,name);
                await writeFile(path.join(output,'image-paste-'+name+'.xml'),actual.source);
                if(['body','header','footer','shared','overlay'].includes(name)){
                    const [download]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
                    await download.saveAs(path.join(output,'image-paste-'+name+'.pdf'));await page.locator('#close-export').click();
                }
            }
        });
        await check('image paste fits the remaining atomic grid item height and preserves history',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            await load('<document version="1"><page width="420" height="220" margin="30"/><grid id="image-grid" tracks="12" column-gap="12"><item id="left" row="1" column-start="1" column-span="6"><p id="image-anchor" line-height="20">EXISTING</p></item><item id="right" row="1" column-start="7" column-span="6"><p line-height="20">RIGHT</p></item></grid></document>',{});
            const before=await page.evaluate(()=>({source:state.source,resources:state.images.length,identity:state.resourceIdentity}));
            await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=100;canvas.height=1000;canvas.getContext('2d').fillRect(0,0,100,1000);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'atomic-grid.png',{type:'image/png'}),0,0,0,entries.find(entry=>entry.id==='image-anchor').path);});await idle();
            const actual=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),image=document.querySelector('image[ref^="pasted_"]');return {source:state.source,layout:DesignerStudioBridge.snapshot().layoutState,status:$('status').textContent,height:Number(image?.getAttribute('height')),width:Number(image?.getAttribute('width')),lineHeight:Number(image?.parentElement.getAttribute('line-height')),resources:state.images.length,leftText:document.querySelector('item[id="left"]').textContent,rightText:document.querySelector('item[id="right"]').textContent};});
            assert.equal(actual.layout,'synced',actual.status);assert.equal(actual.resources,before.resources+1,actual.status);
            // The 160 pt body holds the original 20 pt line, 6 pt trailing space and a 134 pt image line.
            assert.ok(actual.height>0&&actual.height<=130+1e-6,'image must fit the remaining 130 pt: '+actual.height);
            assert.ok(Math.abs(actual.width/actual.height-.1)<1e-6,'scaling must preserve the original aspect ratio');
            assert.ok(actual.lineHeight<=134+1e-6,'image paragraph must fit the remaining line height');
            assert.equal(actual.leftText,'EXISTING');assert.equal(actual.rightText,'RIGHT');assert.equal(await page.evaluate(()=>pages.length),1);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before.source);assert.equal(await page.evaluate(()=>state.resourceIdentity),before.identity);
            await page.locator('#redo').click();await idle();assert.equal(await xml(),actual.source);assert.equal(await page.evaluate(()=>state.images.length),before.resources+1);
            await writeFile(path.join(output,'image-paste-atomic-grid.xml'),actual.source);
            const [download]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
            await download.saveAs(path.join(output,'image-paste-atomic-grid.pdf'));await page.locator('#close-export').click();
        });
        await check('image paste rejects an atomic grid item without line space before writing',async()=>{
            await load('<document version="1"><page width="420" height="220" margin="30"/><grid id="full-image-grid" tracks="12"><item id="left" row="1" column-start="1" column-span="12"><p id="image-full" line-height="150">EXISTING</p></item></grid></document>',{});
            const capture=()=>page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,resources:state.images.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo})),before=await capture();
            const writes=[],watch=request=>{if(request.url().endsWith('/api/resource'))writes.push(request.url());};page.on('request',watch);
            try{await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=100;canvas.height=1000;const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'full-grid.png',{type:'image/png'}),0,0,0,entries.find(entry=>entry.id==='image-full').path);});await idle();}
            finally{page.off('request',watch);}
            assert.equal(writes.length,0,'an item without line space must reject before posting image bytes');
            assert.match(await page.locator('#status').textContent(),/没有足够.*原文和资源未修改/);assert.deepEqual(await capture(),before);
            assert.deepEqual(await page.evaluate(async()=>{const current=await request('state');return {source:current.source,revision:current.revision,identity:current.resourceIdentity,resources:current.images.length};}),{source:before.source,revision:before.revision,identity:before.identity,resources:before.resources});
        });
        await check('image paste fits an occupied table cell and rejects a full atomic row',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            const source=height=>'<document version="1"><page width="420" height="220" margin="30"/><table id="cell-image-table"><columns><column weight="1"/></columns><row><cell><p id="cell-image-anchor" line-height="'+height+'" space-after="6">EXISTING CELL</p></cell></row></table></document>';
            const paste=()=>page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=100;canvas.height=1000;canvas.getContext('2d').fillRect(0,0,100,1000);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'occupied-cell.png',{type:'image/png'}),0,0,0,entries.find(entry=>entry.id==='cell-image-anchor').path);});
            const capture=()=>page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,resources:state.images.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
            await load(source(20),{});const before=await capture();await paste();await idle();
            const actual=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),image=document.querySelector('image[ref^="pasted_"]'),cell=document.querySelector('p[id="cell-image-anchor"]').closest('cell');return {source:state.source,layout:DesignerStudioBridge.snapshot().layoutState,status:$('status').textContent,height:Number(image?.getAttribute('height')),width:Number(image?.getAttribute('width')),lineHeight:Number(image?.parentElement.getAttribute('line-height')),owner:image?.closest('table')?.id,sameCell:!!image&&image.closest('cell')===cell,resources:state.images.length,text:cell.textContent};});
            assert.equal(actual.layout,'synced',actual.status);assert.equal(actual.resources,before.resources+1,actual.status);
            // A 160 pt row reserves 12 pt for padding, 20 pt for the existing line and 6 pt for its trailing space.
            assert.ok(Math.abs(actual.height-118)<1e-6,'the image must use the remaining 118 pt: '+actual.height);
            assert.ok(Math.abs(actual.width/actual.height-.1)<1e-6);assert.ok(Math.abs(actual.lineHeight-122)<1e-6);
            assert.equal(actual.owner,'cell-image-table');assert.equal(actual.sameCell,true);assert.equal(actual.text,'EXISTING CELL');assert.equal(await page.evaluate(()=>pages.length),1);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before.source);assert.equal(await page.evaluate(()=>state.resourceIdentity),before.identity);
            await page.locator('#redo').click();await idle();assert.equal(await xml(),actual.source);assert.equal(await page.evaluate(()=>state.images.length),before.resources+1);
            await writeFile(path.join(output,'image-paste-occupied-cell.xml'),actual.source);
            await load(source(140),{});const full=await capture(),writes=[],watch=request=>{if(request.url().endsWith('/api/resource'))writes.push(request.url());};page.on('request',watch);
            try{await paste();await idle();}finally{page.off('request',watch);}
            assert.equal(writes.length,0,'a full atomic row must reject before posting image bytes');
            assert.match(await page.locator('#status').textContent(),/没有足够.*原文和资源未修改/);assert.deepEqual(await capture(),full);
            assert.deepEqual(await page.evaluate(async()=>{const current=await request('state');return {source:current.source,revision:current.revision,identity:current.resourceIdentity,resources:current.images.length};}),{source:full.source,revision:full.revision,identity:full.identity,resources:full.resources});
        });
        const checkAtomicImageFit=async({source,name,height,lineHeight,width,pageCount=1,bitmapWidth=100,bitmapHeight=1000})=>{
            await load(source,{});const before=await page.evaluate(()=>({source:state.source,resources:state.images.length,identity:state.resourceIdentity}));
            await page.evaluate(async({bitmapWidth,bitmapHeight})=>{const canvas=document.createElement('canvas');canvas.width=bitmapWidth;canvas.height=bitmapHeight;canvas.getContext('2d').fillRect(0,0,bitmapWidth,bitmapHeight);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'atomic-space.png',{type:'image/png'}),0,0,0,entries.find(entry=>entry.id==='anchor').path);},{bitmapWidth,bitmapHeight});await idle();
            const actual=await page.evaluate(source=>{
                const document=new DOMParser().parseFromString(state.source,'application/xml'),original=new DOMParser().parseFromString(source,'application/xml'),originalRefs=new Set([...original.querySelectorAll('image')].map(image=>image.getAttribute('ref'))),newImages=[...document.querySelectorAll('image')].filter(image=>!originalRefs.has(image.getAttribute('ref'))),image=newImages[0],anchor=document.querySelector('[id="anchor"]');
                const canonical=node=>node.nodeType===1?{tag:node.tagName,attrs:[...node.attributes].map(attribute=>[attribute.name,attribute.value]).sort(([a],[b])=>a<b?-1:a>b?1:0),children:[...node.childNodes].map(canonical)}:{type:node.nodeType,text:node.nodeValue};
                const result={source:state.source,identity:state.resourceIdentity,resources:state.images.length,newImages:newImages.length,layout:DesignerStudioBridge.snapshot().layoutState,status:$('status').textContent,height:Number(image?.getAttribute('height')),width:Number(image?.getAttribute('width')),lineHeight:Number(image?.parentElement.getAttribute('line-height')),sameParent:!!image&&image.parentElement.parentElement===anchor.parentElement};
                image?.parentElement.remove();return {...result,preserved:canonical(document.documentElement),original:canonical(original.documentElement)};
            },before.source);
            assert.equal(actual.layout,'synced',name+': '+actual.status);assert.equal(actual.resources,before.resources+1,name+': '+actual.status);assert.equal(actual.newImages,1,name+': exactly one new image reference');
            assert.ok(Math.abs(actual.height-height)<1e-6,name+': expected '+height+' pt image, got '+actual.height);assert.ok(Math.abs(actual.lineHeight-lineHeight)<1e-6,name+': expected '+lineHeight+' pt line, got '+actual.lineHeight);
            if(width!==undefined)assert.ok(Math.abs(actual.width-width)<1e-6,name+': expected '+width+' pt width, got '+actual.width);
            assert.ok(Math.abs(actual.width/actual.height-bitmapWidth/bitmapHeight)<1e-6,name+': image aspect ratio');assert.equal(actual.sameParent,true,name+': insertion parent');
            assert.deepEqual(actual.preserved,actual.original,name+': all original structure, attributes and content must be preserved');assert.equal(await page.evaluate(()=>pages.length),pageCount,name);
            await page.locator('#undo').click();await idle();assert.equal(await xml(),before.source,name);assert.equal(await page.evaluate(()=>state.resourceIdentity),before.identity,name);
            await page.locator('#redo').click();await idle();assert.equal(await xml(),actual.source,name);assert.equal(await page.evaluate(()=>state.resourceIdentity),actual.identity,name);assert.equal(await page.evaluate(()=>state.images.length),before.resources+1,name);
            await writeFile(path.join(output,'image-paste-'+name+'.xml'),actual.source);
            const [download]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
            await download.saveAs(path.join(output,'image-paste-'+name+'.pdf'));await page.locator('#close-export').click();
        };
        await check('image paste preserves target cell slack inside full atomic containers',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            const paragraph='<p id="anchor" line-height="20" space-after="6">EXISTING</p>';
            const table=rightHeight=>'<table id="inner"><columns><column weight="1"/><column weight="1"/></columns><row><cell>'+paragraph+'</cell><cell line-height="'+rightHeight+'">TALL</cell></row></table>';
            const document=body=>'<document version="1"><page width="420" height="220" margin="30"/>'+body+'</document>';
            for(const fixture of [
                {name:'full-table-inside-grid',height:118,lineHeight:122,source:document('<grid tracks="12"><item id="grid-item" row="1" column-start="1" column-span="12">'+table(148)+'</item></grid>')},
                {name:'full-table-inside-atomic-cell',height:106,lineHeight:110,source:document('<table id="outer"><columns><column weight="1"/></columns><row><cell>'+table(136)+'</cell></row></table>')}
            ])await checkAtomicImageFit(fixture);
        });
        await check('image paste fits the body region above an avoidance box',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            await checkAtomicImageFit({name:'body-grid-after-avoidance',height:90,lineHeight:94,source:'<document version="1"><page width="420" height="220" margin="30"/><foreground><box x="30" y="150" width="360" height="30" body-flow="AVOID"><p>BLOCK</p></box></foreground><grid tracks="12"><item id="grid-item" row="1" column-start="1" column-span="12"><p id="anchor" line-height="20" space-after="6">EXISTING</p></item></grid></document>'});
        });
        await check('image paste fits body and column paragraphs around avoidance boxes',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            const paragraph='<p id="anchor" line-height="20" space-after="6">EXISTING</p>',document=body=>'<document version="1"><page width="420" height="220" margin="30"/><foreground><box x="30" y="150" width="360" height="30" body-flow="AVOID"><p>BLOCK</p></box></foreground>'+body+'</document>';
            for(const fixture of [
                {name:'body-paragraph-after-avoidance',height:116,lineHeight:120,pageCount:2,source:document(paragraph)},
                {name:'column-paragraph-after-avoidance',height:116,lineHeight:120,pageCount:1,source:document('<columns count="2" gap="12">'+paragraph+'</columns>')}
            ])await checkAtomicImageFit(fixture);
        });
        await check('image paste honors keep with next on paragraphs and headings without changing their attributes',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            for(const tag of ['p','heading']){
                const attributes=tag==='heading'?' level="2"':'';
                await checkAtomicImageFit({name:tag+'-keep-with-next',height:130,lineHeight:134,source:'<document version="1"><page width="420" height="220" margin="30"/><'+tag+attributes+' id="anchor" size="12" line-height="20" space-before="0" space-after="6" keep-with-next="true">KEPT</'+tag+'></document>'});
            }
        });
        await check('image paste keeps the preceding title with its grid or table container',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            const title='<p id="title" size="12" line-height="20" space-before="0" space-after="6" keep-with-next="true">KEPT</p>',paragraph='<p id="anchor" size="12" line-height="20" space-after="6">EXISTING</p>';
            for(const [owner,container,height,lineHeight] of [
                ['grid','<grid tracks="12"><item row="1" column-start="1" column-span="12">'+paragraph+'</item></grid>',104,108],
                ['table','<table><columns><column weight="1"/></columns><row><cell>'+paragraph+'</cell></row></table>',92,96]
            ])await checkAtomicImageFit({name:'kept-title-'+owner,source:'<document version="1"><page width="420" height="220" margin="30"/>'+title+container+'</document>',height,lineHeight});
        });
        await check('image paste measures container width instead of the previous inline image width',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();const resource=await page.evaluate(()=>state.images[0].name);
            const paragraph='<p id="anchor" size="12" line-height="18" space-after="0"><image ref="'+resource+'" width="10" height="10" alt="original"/></p>';
            for(const [owner,body,width] of [
                ['body',paragraph,356],
                ['grid','<grid tracks="12"><item row="1" column-start="1" column-span="12">'+paragraph+'</item></grid>',356],
                ['cell','<table><columns><column weight="1"/></columns><row><cell>'+paragraph+'</cell></row></table>',344]
            ])await checkAtomicImageFit({name:owner+'-existing-inline-image',source:'<document version="1"><page width="420" height="300" margin="30"/>'+body+'</document>',bitmapWidth:1000,bitmapHeight:100,width,height:width/10,lineHeight:width/10+4});
        });
        await check('image paste rejects a path invalidated while awaiting pending edits and allows a fresh retry',async()=>{
            const source='<document version="1"><page width="420" height="300" margin="30"/><p id="paste-anchor">ORIGINAL</p><p id="paste-other">OTHER</p></document>',changed='<document version="1"><page width="420" height="300" margin="30"/><p id="paste-other">OTHER</p><p id="paste-anchor">ORIGINAL</p></document>';
            await load(source,{});
            const capture=()=>page.evaluate(()=>({source:state.source,data:state.data,revision:state.revision,identity:state.resourceIdentity,resources:state.images.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
            const writes=[],watch=request=>{if(request.url().endsWith('/api/resource'))writes.push(request.url());};page.on('request',watch);
            try{
                await page.evaluate(()=>{const original=DesignerStudioBridge.settled;window.stalePasteOriginalSettled=original;window.stalePasteWaiting=false;window.stalePasteGate=new Promise(resolve=>window.stalePasteRelease=resolve);DesignerStudioBridge.settled=async()=>{await original();window.stalePasteWaiting=true;await window.stalePasteGate;};const canvas=document.createElement('canvas');canvas.width=100;canvas.height=1000;canvas.toBlob(blob=>{window.stalePasteAttempt=DesignerStudioBridge.paste(new File([blob],'stale-path.png',{type:'image/png'}),0,0,0,entries.find(entry=>entry.id==='paste-anchor').path);},'image/png');});
                await page.waitForFunction(()=>window.stalePasteWaiting);
                await page.evaluate(async changed=>{await DesignerStudioBridge.apply(changed);await DesignerStudioBridge.flush();clearTimeout(previewTimer);await preview();},changed);const before=await capture();
                await page.evaluate(async()=>{DesignerStudioBridge.settled=window.stalePasteOriginalSettled;window.stalePasteRelease();await window.stalePasteAttempt;});await idle();
                assert.equal(writes.length,0,'an invalidated paste path must reject before posting image bytes');assert.deepEqual(await capture(),before,'the rejected paste must preserve the independent source change, resources and history');
                assert.equal(await xml(),changed);assert.match(await page.locator('#status').textContent(),/粘贴位置.*变化.*重新选择/);
            }finally{
                page.off('request',watch);await page.evaluate(()=>{if(window.stalePasteOriginalSettled)DesignerStudioBridge.settled=window.stalePasteOriginalSettled;window.stalePasteRelease?.();delete window.stalePasteOriginalSettled;delete window.stalePasteWaiting;delete window.stalePasteGate;delete window.stalePasteRelease;delete window.stalePasteAttempt;});
            }
            const before=await capture();
            await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=40;canvas.height=40;canvas.getContext('2d').fillRect(0,0,40,40);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'fresh-path.png',{type:'image/png'}),0,0,0,entries.find(entry=>entry.id==='paste-anchor').path);});await idle();
            const fresh=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),image=document.querySelector('image[ref^="pasted_"]');return {resources:state.images.length,previousId:image?.parentElement.previousElementSibling?.id,text:document.documentElement.textContent};});
            assert.equal(fresh.resources,before.resources+1);assert.equal(fresh.previousId,'paste-anchor');assert.equal(fresh.text,'OTHERORIGINAL');assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().layoutState),'synced');
            await page.locator('#undo').click();await idle();assert.equal(await xml(),changed);assert.equal(await page.evaluate(()=>state.resourceIdentity),before.identity);
        });
        await check('image paste rejects full regions and failed candidate layout without source or resource changes',async()=>{
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            await load('<document version="1"><page width="420" height="600" margin="30"/><header height="24"><p id="full-header">UNCHANGED HEADER</p></header><p>BODY</p></document>',{});
            const before=await page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,resources:state.images.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
            await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));await DesignerStudioBridge.paste(new File([blob],'full.png',{type:'image/png'}),0,0,0,'/1/0');});await idle();
            assert.match(await page.locator('#status').textContent(),/没有足够.*原文和资源未修改/);
            assert.deepEqual(await page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,resources:state.images.length,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo})),before);
            const rejected=await page.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=8;canvas.height=8;const encoded=canvas.toDataURL('image/png').split(',')[1],xmlDoc=new DOMParser().parseFromString(state.source,'application/xml'),paragraph=xmlDoc.createElement('p');paragraph.setAttribute('line-height','1000');const image=xmlDoc.createElement('image');for(const [key,value] of Object.entries({ref:'preflight_rejected_image',width:32,height:32}))image.setAttribute(key,String(value));paragraph.append(image);xmlDoc.documentElement.append(paragraph);try{await request('resource',{kind:'image',name:'preflight_rejected_image',bytes:encoded,source:new XMLSerializer().serializeToString(xmlDoc),data:state.data,validateLayout:true});return {accepted:true};}catch(error){return {accepted:false,message:error.message};}});
            assert.equal(rejected.accepted,false);assert.match(rejected.message,/exceeds|height|too tall/);
            assert.deepEqual(await page.evaluate(async()=>{const current=await request('state');return {source:current.source,revision:current.revision,identity:current.resourceIdentity,resources:current.images.length};}),{source:before.source,revision:before.revision,identity:before.identity,resources:before.resources});
        });
        await check('image paste cancellation before registration preserves source resources and history',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><p id="cancel-image">UNCHANGED</p></document>',{});
            const before=await page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo}));
            try{
                await page.evaluate(()=>{window.imagePasteBitmap=window.createImageBitmap;window.imagePasteReady=false;window.createImageBitmap=async(...args)=>{const bitmap=await window.imagePasteBitmap(...args);window.imagePasteReady=true;await new Promise(resolve=>window.imagePasteRelease=resolve);return bitmap;};const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;canvas.toBlob(blob=>{void DesignerStudioBridge.paste(new File([blob],'cancelled.png',{type:'image/png'}),0,0,0,'/1');},'image/png');});
                await page.waitForFunction(()=>window.imagePasteReady);await Promise.all([page.waitForResponse(response=>response.url().endsWith('/api/cancel')),page.locator('#cancel').click()]);
                await page.waitForFunction(()=>$('status').textContent.includes('已请求取消'));await page.evaluate(()=>window.imagePasteRelease());await idle();
                assert.match(await page.locator('#status').textContent(),/已取消图片粘贴.*原文和资源未修改/);
                assert.deepEqual(await page.evaluate(()=>({source:state.source,revision:state.revision,identity:state.resourceIdentity,canUndo:DesignerStudioBridge.snapshot().canUndo,canRedo:DesignerStudioBridge.snapshot().canRedo})),before);
                assert.deepEqual(await page.evaluate(async()=>{const current=await request('state');return {source:current.source,revision:current.revision,identity:current.resourceIdentity};}),{source:before.source,revision:before.revision,identity:before.identity});
            }finally{await page.evaluate(()=>{window.imagePasteRelease?.();if(window.imagePasteBitmap)window.createImageBitmap=window.imagePasteBitmap;delete window.imagePasteBitmap;delete window.imagePasteRelease;delete window.imagePasteReady;});}
        });
        await check('workflow v2 caret inserts full width content without manual paragraph assembly',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><columns id="caret-flow" count="2" gap="18"><p id="caret-text">LEFT <span role="bold">STRONG</span> RIGHT ${name}</p><p id="caret-next">AFTER</p></columns></document>',{name:'BOUND'});
            const original=await xml();await edit('caret-text');await page.keyboard.press('Control+Home');for(let index=0;index<5;index++)await page.keyboard.press('ArrowRight');
            await page.getByRole('button',{name:'光标处通栏图片',exact:true}).click();await page.locator('#image-picker').waitFor();await page.getByRole('button',{name:'关闭图片选择',exact:true}).click();assert.equal(await xml(),original);assert.equal(await page.locator('.ProseMirror').count(),1);
            await page.locator('.ProseMirror').focus();await page.keyboard.press('Control+Home');for(let index=0;index<4;index++)await page.keyboard.press('ArrowRight');
            const boundary=await page.evaluate(()=>{
                const editor=document.querySelector('.ProseMirror'),text=editor.querySelector('p').firstChild,range=document.createRange();
                range.setStart(text,5);range.collapse(true);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
                let command;document.addEventListener('designer-insert-full-width',event=>{command=event.detail.caret;},{capture:true,once:true});
                const nativeOffset=selection.anchorOffset;
                // A native caret can move before selectionchange reaches ProseMirror. Read it in this task.
                [...document.querySelectorAll('button')].find(button=>button.textContent==='光标处通栏表格').click();
                return {nativeOffset,command};
            });
            assert.equal(boundary.nativeOffset,5);assert.deepEqual(boundary.command,{offset:5,size:19,empty:true});
            await page.waitForFunction(()=>!busy&&state.source.includes('<table'));await idle();
            const parts=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),body=[...document.documentElement.children].filter(node=>node.tagName!=='page');return {tags:body.map(node=>node.tagName),text:body.filter(node=>node.tagName==='columns').map(node=>node.textContent),bold:document.querySelector('span[role="bold"]').textContent,variables:body.filter(node=>node.tagName==='columns')[1].textContent};});
            assert.deepEqual(parts.tags,['columns','table','columns']);assert.equal(parts.text[0],'LEFT ');assert.match(parts.text[1],/STRONG RIGHT \$\{name\}AFTER/);assert.equal(parts.bold,'STRONG');assert.match(parts.variables,/\$\{name\}/);
            await page.screenshot({path:path.join(output,'workflow-v2-caret-full-width.png'),fullPage:true});
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'workflow-v2-caret-full-width.pdf'));await page.locator('#close-export').click();
            await page.locator('#undo').click();await idle();assert.equal(await xml(),original);await page.locator('#redo').click();await idle();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('columns').length),3);
        });
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
        await check('body columns expose flow settings and preserve editable children through save and reopen',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><heading id="title" level="1">Column editing</heading><columns id="flow" count="2" gap="18" balanced="true" weights="1 2"><p id="column-first">First editable paragraph.</p><p id="column-long">'+('Continuous content flows in reading order. '.repeat(50))+'</p></columns><p id="ending">Full width ending.</p></document>',{});
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            await page.getByRole('treeitem',{name:'正文分栏 flow',exact:true}).click();
            assert.equal(await page.getByLabel('正文栏数',{exact:true}).inputValue(),'2');
            await page.getByLabel('正文栏数',{exact:true}).selectOption('3');
            await page.getByRole('treeitem',{name:'正文分栏 flow',exact:true}).click();
            await page.waitForFunction(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#flow')?.getAttribute('count')==='3');await idle();
            assert.equal(await page.getByLabel('栏宽比例',{exact:true}).inputValue(),'');
            await page.getByLabel('栏距（pt）',{exact:true}).fill('12');
            await page.getByLabel('栏宽比例',{exact:true}).fill('1 2 1');
            await page.getByLabel('末页平衡',{exact:true}).selectOption('false');
            await page.getByRole('treeitem',{name:'正文分栏 flow',exact:true}).click();
            await page.waitForFunction(()=>{const node=new DOMParser().parseFromString(state.source,'application/xml').querySelector('#flow');return node?.getAttribute('gap')==='12'&&node.getAttribute('weights')==='1 2 1'&&node.getAttribute('balanced')==='false';});await idle();
            assert.equal(await page.locator('#table-tools').isVisible(),false,'body flow settings must not expose table cell tools');
            await page.getByRole('treeitem',{name:'段落 column-first',exact:true}).click();
            assert.equal(await page.locator('[data-attribute="keep-with-next"] option[value="true"]').evaluate(option=>option.disabled),true);
            assert.equal(await page.locator('[data-attribute="page-break-before"] option[value="true"]').evaluate(option=>option.disabled),true);
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Edited inside the column flow.');
            await page.getByRole('treeitem',{name:'正文分栏 flow',exact:true}).click();await idle();
            assert.match(await xml(),/Edited inside the column flow/);
            await page.locator('[data-insert="p"]').evaluate(button=>button.click());
            await page.waitForFunction(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#flow')?.children.length===3);await idle();
            assert.equal(await page.evaluate(()=>selected.path.split('/').length),3,'inserting with the flow selected must add a child');
            await page.locator('.studio-surface').first().focus();await page.keyboard.press('Alt+ArrowUp');await idle();
            const order=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelector('#flow').children].map(node=>node.id));
            assert.equal(order[0],'column-first');assert.equal(order[2],'column-long');
            await page.getByRole('treeitem',{name:'段落 column-first',exact:true}).click();
            await page.locator('#studio-container-inspector').getByLabel('更多容器操作',{exact:true}).click();
            await page.getByRole('button',{name:'插入通栏表格',exact:true}).click();await idle();
            const wideId=await page.evaluate(()=>selected.id);assert.equal(await page.evaluate(()=>nodeAt(selected.path).parentElement.tagName),'document');
            await page.getByRole('treeitem',{name:'段落 '+order[1],exact:true}).click();
            await page.locator('#studio-container-inspector').getByLabel('更多容器操作',{exact:true}).click();
            await page.getByRole('button',{name:'插入通栏图片',exact:true}).click();
            await page.locator('#image-picker').getByRole('button',{name:'图片资源 mark',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>nodeAt(selected.path).parentElement.tagName),'document');
            assert.equal(await page.evaluate(id=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#'+id)?.parentElement.tagName,wideId),'document');
            const regions=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('document > columns')].map(node=>Object.fromEntries(['count','gap','weights','balanced'].map(name=>[name,node.getAttribute(name)]))));
            assert.ok(regions.length>=3);assert.ok(regions.every(value=>JSON.stringify(value)===JSON.stringify({count:'3',gap:'12',weights:'1 2 1',balanced:'false'})));
            await page.getByRole('treeitem',{name:'正文分栏 flow',exact:true}).click();await idle();
            await page.screenshot({path:path.join(output,'body-columns-three.png'),fullPage:true});
            const retained=await xml();await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
            const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);
            const saved=path.join(output,'body-columns-template.zip');await download.saveAs(saved);await page.locator('#close-template-export').click();
            await page.locator('#open-file').setInputFiles(saved);await idle();assert.equal(await xml(),retained);
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
            await pdf.saveAs(path.join(output,'body-columns.pdf'));await page.locator('#close-export').click();
        });
        await check('built in body columns samples open with full width tables and pictures between regions',async()=>{
            for(const [name,prefix,label] of [['双栏学术短文','article','two'],['三栏简报','briefing','three']]){
                await page.locator('#new-sample').evaluate(button=>button.click());
                await page.locator('#sample-list button').filter({hasText:name}).click();
                await page.waitForFunction(prefix=>$('question-dialog').open||state.source.includes('id="'+prefix+'-wide-table"'),prefix);
                if(await page.locator('#question-dialog').evaluate(dialog=>dialog.open))await page.locator('#question-ok').click();
                await page.waitForFunction(prefix=>state.source.includes('id="'+prefix+'-wide-table"'),prefix);await idle();
                await page.getByRole('treeitem',{name:'正文分栏 '+prefix+'-body',exact:true}).click();
                assert.equal(await page.getByLabel('正文栏数',{exact:true}).inputValue(),label==='two'?'2':'3');
                const evidence=await page.evaluate(prefix=>pages.flatMap((page,index)=>page.hits.filter(hit=>[prefix+'-wide-table',prefix+'-wide-figure',prefix+'-body-after'].includes(hit.id)).map(hit=>({...hit,page:index}))),prefix);
                assert.equal(evidence.filter(hit=>hit.id===prefix+'-wide-table').length,1);
                assert.equal(evidence.filter(hit=>hit.id===prefix+'-wide-figure').length,1);
                assert.ok(evidence.some(hit=>hit.id===prefix+'-wide-table'&&hit.width>500));
                await writeFile(path.join(output,'body-columns-sample-'+label+'-geometry.json'),JSON.stringify(evidence,null,2));
                await page.screenshot({path:path.join(output,'body-columns-sample-'+label+'.png'),fullPage:true});
                await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
                const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);await bundle.saveAs(path.join(output,'body-columns-sample-'+label+'.zip'));await page.locator('#close-template-export').click();
                await writeFile(path.join(output,'body-columns-sample-'+label+'-data.json'),await page.evaluate(()=>state.data));
                const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);
                await pdf.saveAs(path.join(output,'body-columns-sample-'+label+'.pdf'));await page.locator('#close-export').click();
            }
        });
        await check('binding ux inserts variables into sidebar list items and table cells using their actual scopes',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><bullet id="plain-item">Patient: </bullet><list items="medicines" as="drug"><bullet id="med-item">Medicine: </bullet></list><rows id="med-table" items="medicines" as="record"><columns><column weight="1"/></columns><header><cell>Header</cell></header><row><cell>Row: </cell></row></rows></document>',{patient:{name:'Sample Patient'},medicines:[{name:'Drug Alpha',dose:'5 mg'},{name:'Drug Beta',dose:'10 mg'}]});
            const variables=page.locator('#body-bindings'),chooseVariable=async path=>{await variables.locator(`[data-binding-path="${path}"]`).click();assert.equal(await variables.getByRole('textbox',{name:'变量路径',exact:true}).inputValue(),path);await variables.getByRole('button',{name:'插入到正文',exact:true}).click();assert.equal(await page.getByRole('dialog',{name:'插入变量',exact:true}).count(),0);};
            await page.getByRole('treeitem',{name:'列表项 plain-item',exact:true}).click();await page.getByRole('textbox',{name:'正文富文本',exact:true}).click();await page.keyboard.press('Control+End');
            await page.locator('#direct-body').getByRole('button',{name:'插入变量',exact:true}).click();await chooseVariable('patient.name');await page.getByRole('treeitem',{name:'列表项 plain-item',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#plain-item').textContent),'Patient: ${patient.name}');
            await page.getByRole('treeitem',{name:'列表项 med-item',exact:true}).click();await page.getByRole('textbox',{name:'正文富文本',exact:true}).click();await page.keyboard.press('Control+End');await page.locator('#direct-body').getByRole('button',{name:'插入变量',exact:true}).click();
            assert.match(await page.locator('[data-binding-help]').textContent(),/drug/);assert.equal(await variables.locator('[data-binding-path="record.name"]').count(),0);await chooseVariable('drug.name');await page.getByRole('treeitem',{name:'数据列表',exact:true}).click();await idle();
            assert.match(await xml(),/\$\{drug\.name\}/);assert.equal(await page.getByLabel('数据路径',{exact:true}).inputValue(),'medicines');assert.equal(await page.locator('#inspector-panel').getByLabel('迭代变量',{exact:true}).inputValue(),'drug');
            const cells=await page.evaluate(()=>entries.filter(entry=>entry.tag==='cell'&&entry.path.startsWith(entries.find(entry=>entry.id==='med-table').path+'/')).map(entry=>entry.path));assert.equal(cells.length,2);
            await page.locator(`.tree-item[data-path="${cells[0]}"]`).click();const headerSource=await xml();await page.locator('#direct-body').getByRole('button',{name:'插入变量',exact:true}).click();assert.equal(await variables.locator('[data-binding-path="record.name"]').count(),0,'a rows header must not offer the body-row alias');assert.equal(await xml(),headerSource,'opening the inline picker without inserting must not change the header');
            await page.locator(`.tree-item[data-path="${cells[1]}"]`).click();await page.getByRole('textbox',{name:'正文富文本',exact:true}).click();await page.keyboard.press('Control+End');await page.locator('#direct-body').getByRole('button',{name:'插入变量',exact:true}).click();await chooseVariable('record.name');await page.getByRole('treeitem',{name:'动态表格 med-table',exact:true}).click();await idle();assert.match(await xml(),/\$\{record\.name\}/);
            await page.screenshot({path:path.join(output,'binding-ux-table.png'),fullPage:true});
            const retained=await xml();await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);const saved=path.join(output,'binding-ux-template.zip');await bundle.saveAs(saved);await page.locator('#close-template-export').click();const dataFile=path.join(output,'binding-ux-data.json');await writeFile(dataFile,await page.evaluate(()=>state.data));await page.locator('#open-file').setInputFiles(saved);await page.waitForFunction(()=>!busy);assert.equal(await xml(),retained);await page.locator('#data-file').setInputFiles(dataFile);await idle();
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'binding-ux.pdf'));await page.locator('#close-export').click();
        });
        await check('binding ux canvas variable insertion retains the caret cancellation and undo',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><p id="variable-text">Before </p></document>',{patient:{name:'Sample Patient'}});await page.getByRole('button',{name:'精确排版',exact:true}).click();await edit('variable-text');await page.keyboard.press('End');await page.keyboard.insertText('typed ');
            const editor=page.locator('.ProseMirror'),button=page.locator('.studio-rich').getByRole('button',{name:'插入变量',exact:true});await button.click();const picker=page.getByRole('dialog',{name:'插入变量',exact:true});await picker.waitFor();await page.screenshot({path:path.join(output,'binding-ux-variable-picker.png'),fullPage:true});await picker.getByRole('button',{name:'patient.name',exact:true}).click();await picker.getByRole('button',{name:'插入',exact:true}).click();
            assert.equal(await editor.locator('[data-kind="variable"]').textContent(),'${patient.name}');await page.keyboard.press('Control+z');assert.equal(await editor.locator('[data-kind="variable"]').count(),0);assert.match(await editor.textContent(),/Before typed /,'undoing the variable must retain the preceding text');await page.keyboard.press('Control+Shift+z');assert.equal(await editor.locator('[data-kind="variable"]').textContent(),'${patient.name}');
            const beforeCancel=await editor.textContent();await button.click();await picker.getByRole('textbox',{name:'变量路径',exact:true}).fill('patient.other');await picker.getByRole('button',{name:'取消',exact:true}).click();assert.equal(await editor.textContent(),beforeCancel);await finish();assert.match(await xml(),/Before typed \$\{patient\.name\}/);
        });
        await check('binding ux exposes grouped containers and explicit array settings from both insert menus',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><p id="binding-anchor">Anchor</p></document>',{medicines:[{name:'Drug Alpha',dose:'5 mg'},{name:'Drug Beta',dose:'10 mg'}]});await (await object('binding-anchor')).click();await page.locator('.insert-menu > summary').click();assert.equal(await page.locator('[data-insert="grid"]').textContent(),'独立图文栅格');assert.equal(await page.locator('[data-insert="grid"]').isVisible(),true);await page.screenshot({path:path.join(output,'binding-ux-container-menu.png'),fullPage:true});
            await page.locator('[data-insert="list"]').click();let binding=page.getByRole('dialog',{name:'绑定数组数据',exact:true});await binding.waitFor();await binding.getByRole('textbox',{name:'数组路径',exact:true}).fill('medicines');await binding.getByRole('textbox',{name:'迭代变量',exact:true}).fill('drug');await binding.getByRole('button',{name:'创建数据列表',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('list').getAttribute('items')),'medicines');assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('list').getAttribute('as')),'drug');
            await (await object('binding-anchor')).click();await page.getByRole('button',{name:'在后面插入',exact:true}).click();const menu=page.getByRole('menu',{name:'插入内容',exact:true});await menu.waitFor();assert.equal(await menu.getByRole('menuitem',{name:'独立图文栅格',exact:true}).isVisible(),true);await menu.getByRole('menuitem',{name:'数据表格',exact:true}).click();await binding.waitFor();await binding.getByRole('textbox',{name:'数组路径',exact:true}).fill('medicines');await binding.getByRole('textbox',{name:'迭代变量',exact:true}).fill('record');await binding.getByRole('button',{name:'创建数据表格',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('rows').getAttribute('items')),'medicines');assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('rows').getAttribute('as')),'record');
            await (await object('binding-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="rows"]').click();await binding.waitFor();const beforeCancel=await xml();await binding.getByRole('button',{name:'取消',exact:true}).click();assert.equal(await xml(),beforeCancel);await page.setViewportSize({width:980,height:820});await page.locator('.insert-menu > summary').click();await page.screenshot({path:path.join(output,'binding-ux-menu-narrow.png'),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.keyboard.press('Escape');await page.setViewportSize({width:1440,height:1050});
        });
        await check('binding ux keeps insertion scope and refuses an empty object as default text',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><each items="groups" as="group"><p id="nested-anchor">Nested</p></each></document>',{groups:[{children:[{name:'Child'}]}]});
            await page.getByRole('treeitem',{name:'段落 nested-anchor',exact:true}).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="list"]').click();
            const dialog=page.getByRole('dialog',{name:'绑定数组数据',exact:true});await dialog.waitFor();assert.equal(await dialog.getByRole('button',{name:'group.children',exact:true}).isVisible(),true);await dialog.getByRole('button',{name:'group.children',exact:true}).click();assert.equal(await dialog.getByRole('textbox',{name:'重复内容字段',exact:true}).inputValue(),'item.name');await dialog.getByRole('button',{name:'取消',exact:true}).click();
            await load('<document version="1"><p id="object-anchor">Objects</p></document>',{items:[{}]});await (await object('object-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="rows"]').click();await dialog.waitFor();assert.equal(await dialog.getByRole('textbox',{name:'重复内容字段',exact:true}).inputValue(),'');const before=await xml();await dialog.getByRole('button',{name:'创建数据表格',exact:true}).click();assert.equal(await dialog.locator('#binding-error').isVisible(),true);assert.equal(await xml(),before);await dialog.getByRole('button',{name:'取消',exact:true}).click();
            await load('<document version="1"><p id="empty-anchor">Empty</p></document>',{title:'Original'});await (await object('empty-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="list"]').click();await dialog.waitFor();assert.equal(await dialog.locator('#binding-sample-hint').isVisible(),true);await dialog.getByRole('button',{name:'创建数据列表',exact:true}).click();await idle();assert.deepEqual(await page.evaluate(()=>JSON.parse(state.data)),{title:'Original',items:[{name:'示例项'}]});await page.locator('#undo').click();await idle();assert.deepEqual(await page.evaluate(()=>JSON.parse(state.data)),{title:'Original'});assert.equal(await page.evaluate(()=>!!new DOMParser().parseFromString(state.source,'application/xml').querySelector('list')),false);
        });
        await check('container ux inserted tables enter content and expose undoable row and column editing',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><p id="container-anchor">Anchor</p></document>',{});await (await object('container-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="table"]').click();
            await page.locator('.ProseMirror').waitFor();await page.locator('.ProseMirror').focus();await page.keyboard.press('Control+a');await page.keyboard.insertText('CONTAINER-HEADER');await inspectorAncestor('table');await page.locator('.ProseMirror').waitFor({state:'detached'});await idle();
            const tableId=await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('table').id);
            await page.getByRole('treeitem',{name:'表格 '+tableId,exact:true}).click();await page.locator('.studio-surface').first().focus();await page.keyboard.press('F2');await page.locator('.ProseMirror').waitFor();assert.equal(await page.locator('.ProseMirror').textContent(),'CONTAINER-HEADER');await finish();
            await page.getByRole('treeitem',{name:'表格 '+tableId,exact:true}).click();const panel=page.locator('#studio-container-inspector'),tableAction=async name=>{await panel.getByLabel('当前单元格的行列与合并',{exact:true}).click();await panel.getByRole('button',{name,exact:true}).click();await idle();};await tableAction('在下方插入行');await tableAction('在右侧插入列');
            assert.deepEqual(await page.evaluate(()=>{const table=new DOMParser().parseFromString(state.source,'application/xml').querySelector('table');return {rows:table.querySelectorAll(':scope > row').length,columns:table.querySelectorAll(':scope > columns > column').length,cells:[...table.querySelectorAll(':scope > header,:scope > row')].map(row=>row.children.length)};}),{rows:2,columns:3,cells:[3,3,3]});
            await page.screenshot({path:path.join(output,'container-ux-table-controls.png'),fullPage:true});await tableAction('删除当前列');await tableAction('删除当前行');assert.match(await xml(),/CONTAINER-HEADER/);assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('table > row').length),1);await page.locator('#undo').click();await idle();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('table > row').length),2);await page.locator('#redo').click();await idle();
            await load('<document version="1"><page size="A4" margin="42"/><table id="last-cell-table"><columns><column weight="1"/></columns><row><cell><p id="last-cell-text">KEEP LAST CELL</p></cell></row></table></document>',{});
            await page.getByRole('treeitem',{name:'表格 last-cell-table',exact:true}).click();
            const lastCellButtons=async()=>{
                const actions=panel.getByLabel('当前单元格的行列与合并',{exact:true});
                if(!await actions.evaluate(node=>node.closest('details').open))await actions.click();
                assert.equal(await panel.getByRole('button',{name:'删除当前行',exact:true}).isDisabled(),true,'the last body row must remain protected after legacy UI refreshes');
                assert.equal(await panel.getByRole('button',{name:'删除当前列',exact:true}).isDisabled(),true,'the last column must remain protected after legacy UI refreshes');
            };
            await lastCellButtons();
            const childPath=await page.evaluate(()=>entries.find(entry=>entry.id==='last-cell-text').path);
            await inspectorChild(childPath);await inspectorAncestor('table');await lastCellButtons();
            await page.getByRole('button',{name:'精确排版',exact:true}).click();await idle();await lastCellButtons();
            const spacing=page.locator('#structured-properties [data-group="间距与缩进"]');
            if(!await spacing.evaluate(node=>node.open))await spacing.locator('summary').click();
            await page.getByLabel('上内边距',{exact:true}).fill('7');
            await inspectorChild(childPath);await inspectorAncestor('table');await lastCellButtons();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#last-cell-table').getAttribute('padding-top')),'7');
            assert.match(await xml(),/KEEP LAST CELL/);
        });
        await check('container ux empty arrays keep list and row prototypes editable without adding fake data',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><p id="empty-title">Empty array prototypes</p><list items="records" as="item"><bullet id="empty-item">${item.name}</bullet></list><rows id="empty-rows" items="records" as="record"><columns><column weight="1"/></columns><header><cell>Record</cell></header><row><cell>${record.name}</cell></row></rows></document>',{records:[]});
            await page.getByRole('treeitem',{name:'数据列表',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'编辑内部内容',exact:true}).click();const body=page.getByRole('textbox',{name:'正文富文本',exact:true});await body.waitFor();await page.waitForFunction(()=>document.activeElement===document.querySelector('#body-content'));await body.fill('EMPTY-LIST ${item.name}');await page.getByRole('treeitem',{name:'动态表格 empty-rows',exact:true}).click();await idle();
            const rowCell=await page.evaluate(()=>entries.find(entry=>entry.id==='empty-rows').path+'/2/0');await inspectorChild(rowCell);await body.focus();await page.waitForFunction(()=>document.activeElement===document.querySelector('#body-content'));await body.fill('EMPTY-ROW ${record.name}');await page.getByRole('treeitem',{name:'动态表格 empty-rows',exact:true}).click();await idle();assert.deepEqual(await page.evaluate(()=>JSON.parse(state.data)),{records:[]});assert.match(await xml(),/EMPTY-LIST \$\{item\.name\}/);assert.match(await xml(),/EMPTY-ROW \$\{record\.name\}/);
            await page.screenshot({path:path.join(output,'container-ux-empty-array.png'),fullPage:true});await page.evaluate(async()=>{$('data').value=JSON.stringify({records:[{name:'CONTAINER-BOUND'}]});await DesignerStudioBridge.apply(state.source);await DesignerStudioBridge.flush();});await idle();
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);await bundle.saveAs(path.join(output,'container-ux-template.zip'));await page.locator('#close-template-export').click();
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'container-ux.pdf'));await page.locator('#close-export').click();
        });
        await check('container ux appends inside grid and cells preserving existing mixed content',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><grid id="content-grid" tracks="2"><item id="content-item" row="1" column-start="1" column-span="2"><p id="inner-first">FIRST-CONTENT</p><p id="inner-second">SECOND-CONTENT</p></item></grid></document>',{});await page.getByRole('treeitem',{name:'栅格项 content-item',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'添加内容',exact:true}).click();const dialog=page.getByRole('dialog',{name:'在容器内添加',exact:true});await dialog.waitFor();await dialog.getByRole('button',{name:'文字段落',exact:true}).click();await page.locator('.ProseMirror').waitFor();await page.locator('.ProseMirror > p').last().fill('THIRD-CONTENT');await finish();assert.deepEqual(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('item > p')].map(node=>node.textContent)),['FIRST-CONTENT','SECOND-CONTENT','THIRD-CONTENT']);
            await load('<document version="1"><page size="A4" margin="42"/><table id="block-container"><columns><column weight="1"/></columns><row><cell>KEPT <span role="bold">BOLD</span> ${name}</cell></row></table></document>',{name:'BOUND'});await page.getByRole('treeitem',{name:'表格 block-container',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'添加内容',exact:true}).click();await dialog.getByRole('button',{name:'文字段落',exact:true}).click();await page.locator('.ProseMirror').waitFor();await page.locator('.ProseMirror > p').last().fill('CELL-APPEND');await finish();assert.deepEqual(await page.evaluate(()=>{const cell=new DOMParser().parseFromString(state.source,'application/xml').querySelector('cell');return {tags:[...cell.children].map(node=>node.tagName),content:[...cell.children].map(node=>node.textContent),bold:cell.querySelector('span')?.getAttribute('role')};}),{tags:['p','p'],content:['KEPT BOLD ${name}','CELL-APPEND'],bold:'bold'});await page.screenshot({path:path.join(output,'container-ux-cell-blocks.png'),fullPage:true});
            await load('<document version="1"><page size="A4" margin="42"/><table id="second-block-table"><columns><column weight="1"/><column weight="1"/></columns><row><cell>FIRST-CELL</cell><cell><p id="chosen-cell-text">SECOND-CELL</p></cell></row></table></document>',{});
            await page.getByRole('treeitem',{name:'表格 second-block-table',exact:true}).click();
            const panel=page.locator('#studio-container-inspector'),cellPath=await page.evaluate(()=>entries.find(entry=>entry.id==='second-block-table').path+'/1/1');
            await inspectorChild(cellPath);await panel.getByRole('button',{name:'编辑内部内容',exact:true}).click();
            const editor=page.locator('.ProseMirror');await editor.waitFor();assert.equal(await editor.textContent(),'SECOND-CELL');
            await editor.fill('SECOND-CELL-EDIT');await inspectorAncestor('table');
            await page.waitForFunction(()=>selected?.id==='second-block-table'&&!document.querySelector('.ProseMirror'));await idle();
            assert.deepEqual(await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('#second-block-table > row > cell')].map(cell=>cell.textContent)),['FIRST-CELL','SECOND-CELL-EDIT']);
        });
        await check('container ux adds images and editable tables inside the chosen grid item',async()=>{
            await page.evaluate(async()=>{if(state.images.some(image=>image.name==='mark'))return;const canvas=document.createElement('canvas');canvas.width=40;canvas.height=24;const context=canvas.getContext('2d');context.fillStyle='#224488';context.fillRect(0,0,40,24);install(await request('resource',{kind:'image',name:'mark',bytes:canvas.toDataURL('image/png').split(',')[1]}));});
            await load('<document version="1"><page size="A4" margin="42"/><grid id="media-grid" tracks="2"><item id="media-left" row="1" column-start="1" column-span="1"><p id="media-text">GRID-MEDIA</p></item><item id="media-right" row="1" column-start="2" column-span="1"><p id="media-other">UNCHANGED-SIDEBAR</p></item></grid></document>',{});await page.getByRole('treeitem',{name:'栅格项 media-left',exact:true}).click();const panel=page.locator('#studio-container-inspector'),dialog=page.getByRole('dialog',{name:'在容器内添加',exact:true});await panel.getByRole('button',{name:'添加内容',exact:true}).click();await dialog.getByRole('button',{name:'图片',exact:true}).click();await page.locator('#image-picker').getByRole('button',{name:'图片资源 mark',exact:true}).click();await idle();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('image').closest('item').id),'media-left');
            await page.locator('#studio-container-inspector').getByRole('button',{name:'添加内容',exact:true}).click();await dialog.getByRole('button',{name:'表格',exact:true}).click();await page.locator('.ProseMirror').waitFor();await page.locator('.ProseMirror').fill('GRID-TABLE');await finish();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('table').closest('item').id),'media-left');assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#media-other').textContent),'UNCHANGED-SIDEBAR');await page.screenshot({path:path.join(output,'container-ux-grid-media.png'),fullPage:true});
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'container-ux-mixed.pdf'));await page.locator('#close-export').click();
        });
        await check('workflow opening XML and ZIP replaces preview content and identity immediately',async()=>{
            await load();const previous=await page.evaluate(()=>previewId),opened='<document version="1"><page size="A4" margin="42"/><p id="workflow-opened">WORKFLOW-OPENED</p></document>';
            const file=path.join(output,'workflow-open.xml');await writeFile(file,opened);await page.locator('#open-file').setInputFiles(file);await idle();
            assert.notEqual(await page.evaluate(()=>previewId),previous);assert.deepEqual(await page.evaluate(()=>pages.flatMap(page=>page.hits.map(hit=>hit.id))),['workflow-opened']);
            await page.locator('#save').click();const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);const saved=path.join(output,'workflow-open.zip');await bundle.saveAs(saved);await page.locator('#close-template-export').click();
            await load();await page.locator('#open-file').setInputFiles(saved);await idle();assert.deepEqual(await page.evaluate(()=>pages.flatMap(page=>page.hits.map(hit=>hit.id))),['workflow-opened']);
            await page.screenshot({path:path.join(output,'workflow-opened.png'),fullPage:true});
        });
        await check('workflow clipboard images stay inside source containers and undo resource and content together',async()=>{
            for(const [kind,body] of [
                ['grid','<grid id="paste-grid" tracks="2"><item id="paste-owner" row="1" column-start="1" column-span="1"><p id="paste-text">GRID ${name}</p></item><item row="1" column-start="2" column-span="1"><p id="paste-neighbor">NEIGHBOR</p></item></grid>'],
                ['columns','<columns id="paste-owner" count="2"><p id="paste-text">COLUMNS ${name}</p></columns>'],
                ['cell','<table id="paste-table"><columns><column weight="1"/><column weight="1"/></columns><row><cell><p id="paste-text">CELL ${name}</p></cell><cell>NEIGHBOR</cell></row></table>']
            ]){
                await load('<document version="1"><page size="A4" margin="42"/>'+body+'<p id="paste-tail">UNCHANGED-TAIL</p></document>',{name:'BOUND'});
                const images=await page.evaluate(()=>state.images.length);await edit('paste-text');await page.locator('.ProseMirror').focus();await page.keyboard.press('Control+End');await page.keyboard.insertText('-DRAFT');
                await page.locator('.ProseMirror').evaluate(element=>{const canvas=document.createElement('canvas');canvas.width=48;canvas.height=24;canvas.getContext('2d').fillRect(0,0,48,24);const bytes=Uint8Array.from(atob(canvas.toDataURL('image/png').split(',')[1]),char=>char.charCodeAt(0)),data=new DataTransfer();data.items.add(new File([bytes],'workflow.png',{type:'image/png'}));element.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));});
                await page.waitForFunction(()=>state.source.includes('ref="pasted_')&&!busy);await idle();
                const content=await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml'),image=document.querySelector('image[ref^="pasted_"]'),owner=document.querySelector('#paste-text').closest('cell,item,columns');return {inside:owner.contains(image),draft:document.querySelector('#paste-text').textContent,tail:document.documentElement.lastElementChild.id,images:state.images.length};});
                assert.equal(content.inside,true,kind);assert.match(content.draft,/\$\{name\}-DRAFT$/);assert.equal(content.tail,'paste-tail');assert.equal(content.images,images+1);
                await page.locator('#undo').click();await idle();assert.equal(await page.evaluate(()=>state.source.includes('ref="pasted_')),false);assert.equal(await page.evaluate(()=>state.images.length),images);assert.match(await xml(),/\$\{name\}-DRAFT/);
                await page.locator('#redo').click();await idle();
                assert.equal(await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return document.querySelector('#paste-text').closest('cell,item,columns').contains(document.querySelector('image[ref^="pasted_"]'));}),true);assert.equal(await page.evaluate(()=>state.images.length),images+1);
                await page.screenshot({path:path.join(output,'workflow-paste-'+kind+'.png'),fullPage:true});
                await writeFile(path.join(output,'workflow-paste-'+kind+'.xml'),await xml());
                const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'workflow-paste-'+kind+'.pdf'));await page.locator('#close-export').click();
            }
        });
        await check('workflow table editing keeps the chosen cell and single clicks continue inside the same table',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><table id="workflow-table"><columns><column weight="1"/><column weight="1"/></columns><row><cell><p id="workflow-first">FIRST-CELL</p></cell><cell><p id="workflow-second">SECOND-CELL</p></cell></row></table><p id="workflow-outside">OUTSIDE</p></document>',{});
            await page.getByRole('treeitem',{name:'段落 workflow-second',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'编辑内部内容',exact:true}).click();await page.locator('.ProseMirror').waitFor();assert.equal(await page.locator('.ProseMirror').textContent(),'SECOND-CELL');
            await (await object('workflow-first')).click();await page.waitForFunction(()=>document.querySelector('.ProseMirror')?.textContent==='FIRST-CELL');await page.locator('.ProseMirror').focus();await page.keyboard.press('Control+End');await page.keyboard.insertText('-KEPT');
            await (await object('workflow-outside')).click();await page.locator('.ProseMirror').waitFor({state:'detached'});await idle();assert.match(await xml(),/FIRST-CELL-KEPT/);
        });
        await check('workflow block cells edit adjacent paragraphs and Tab navigates cells without crossing data scopes',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><table id="keyboard-table"><columns><column weight="1"/><column weight="1"/></columns><row><cell><p id="keyboard-one">ALPHA ${name}</p><p id="keyboard-two">BETA</p></cell><cell><p id="keyboard-next">GAMMA</p></cell></row></table></document>',{name:'BOUND'});
            await page.getByRole('button',{name:'精确排版',exact:true}).click();await idle();
            await edit('keyboard-one');assert.equal(await page.locator('.ProseMirror > p').count(),2);await page.locator('.ProseMirror').focus();await page.keyboard.press('Control+End');await page.keyboard.press('Enter');await page.keyboard.insertText('DELTA');
            assert.equal(await page.locator('.ProseMirror > p').count(),3);await page.keyboard.press('Home');await page.keyboard.press('Backspace');assert.equal(await page.locator('.ProseMirror > p').count(),2);assert.match(await page.locator('.ProseMirror').textContent(),/BETADELTA$/);await page.keyboard.press('Enter');assert.equal(await page.locator('.ProseMirror > p').count(),3);assert.match(await page.evaluate(()=>DesignerStudioBridge.snapshot().source),/DELTA/);
            const composing=await page.locator('.ProseMirror').evaluate(element=>{const event=new KeyboardEvent('keydown',{key:'Tab',isComposing:true,bubbles:true,cancelable:true});element.dispatchEvent(event);return event.defaultPrevented;});assert.equal(composing,false);
            await page.keyboard.press('Tab');await page.waitForFunction(()=>document.querySelector('.ProseMirror')?.textContent==='GAMMA');await page.keyboard.press('Shift+Tab');await page.waitForFunction(()=>document.querySelector('.ProseMirror')?.textContent?.includes('DELTA'));assert.equal(await page.locator('.ProseMirror > p').count(),3);await finish();
            assert.deepEqual(await page.evaluate(()=>{const document=new DOMParser().parseFromString(state.source,'application/xml');return [...document.querySelector('cell').children].map(node=>node.textContent);}),['ALPHA ${name}','BETA','DELTA']);assert.match(await xml(),/id="keyboard-one"/);assert.match(await xml(),/id="keyboard-two"/);
            await page.screenshot({path:path.join(output,'workflow-table-keyboard.png'),fullPage:true});
        });
        await check('workflow table navigation preserves untouched XML and visits merged and bound anchors once',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><table id="anchor-table"><columns><column weight="1"/><column weight="1"/><column weight="1"/></columns><row><cell column-span="2">MERGED ${name}</cell><cell row-span="2">SIDE</cell></row><row><cell>LOWER</cell><cell>LAST</cell></row></table></document>',{name:'BOUND'});
            const untouched=await xml();await page.getByRole('treeitem',{name:'表格 anchor-table',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'编辑内部内容',exact:true}).click();await page.locator('.ProseMirror').waitFor();
            await page.locator('.ProseMirror [data-kind="variable"]').click();assert.equal(await page.locator('.ProseMirror [data-kind="variable"].ProseMirror-selectednode').count(),1);
            for(const next of ['SIDE','LOWER','LAST']){await page.keyboard.press('Tab');await page.waitForFunction(value=>document.querySelector('.ProseMirror')?.textContent===value,next);assert.equal(await xml(),untouched);}
            await page.keyboard.press('Tab');await page.locator('.ProseMirror').waitFor({state:'detached'});await idle();assert.equal(await xml(),untouched);assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('row').length),2);
            await page.getByRole('treeitem',{name:'表格 anchor-table',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'编辑内部内容',exact:true}).click();await page.locator('.ProseMirror').waitFor();await page.keyboard.press('Shift+Tab');await page.locator('.ProseMirror').waitFor({state:'detached'});await idle();assert.equal(await xml(),untouched);
            const data={records:[{name:'Alpha',dose:'10 mg'},{name:'Beta',dose:'20 mg'}]};
            await load('<document version="1"><page size="A4" margin="42"/><rows id="anchor-bound" items="records" as="record"><columns><column weight="1"/><column weight="1"/></columns><header><cell>NAME</cell><cell>DOSE</cell></header><row><cell>${record.name}</cell><cell>${record.dose}</cell></row></rows></document>',data);
            const bound=await xml();await page.getByRole('treeitem',{name:'动态表格 anchor-bound',exact:true}).click();await page.locator('#studio-container-inspector').getByRole('button',{name:'编辑内部内容',exact:true}).click();await page.locator('.ProseMirror').waitFor();
            for(const next of ['DOSE','${record.name}','${record.dose}']){await page.keyboard.press('Tab');await page.waitForFunction(value=>document.querySelector('.ProseMirror')?.textContent===value,next);assert.equal(await xml(),bound);}
            await page.keyboard.press('Tab');await page.locator('.ProseMirror').waitFor({state:'detached'});await idle();assert.equal(await xml(),bound);assert.deepEqual(await page.evaluate(()=>JSON.parse(state.data)),data);
        });
        await check('workflow both table insertion entry points use the same semantic header',async()=>{
            const blank='<document version="1"><page size="A4" margin="42"/><p id="factory-anchor">ANCHOR</p></document>',tables=[];
            await load(blank,{});await (await object('factory-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="table"]').click();await page.locator('.ProseMirror').waitFor();await finish();tables.push(await page.evaluate(()=>{const table=new DOMParser().parseFromString(state.source,'application/xml').querySelector('table');table.removeAttribute('id');return new XMLSerializer().serializeToString(table);}));
            await load(blank,{});await (await object('factory-anchor')).click();await page.getByRole('button',{name:'在后面插入',exact:true}).click();await page.getByRole('menu',{name:'插入内容',exact:true}).getByRole('menuitem',{name:'表格',exact:true}).click();await page.locator('.ProseMirror').waitFor();await finish();tables.push(await page.evaluate(()=>{const table=new DOMParser().parseFromString(state.source,'application/xml').querySelector('table');table.removeAttribute('id');return new XMLSerializer().serializeToString(table);}));assert.equal(tables[0],tables[1]);assert.match(tables[0],/<header>/);
        });
        await check('workflow compact panels keep the canvas usable and Escape restores focus without changing desktop preferences',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><grid id="compact-grid" tracks="2"><item id="compact-item" row="1" column-start="1" column-span="2"><p id="compact-text">COMPACT-CONTENT</p></item></grid></document>',{});await page.getByRole('button',{name:'便捷编辑',exact:true}).click();await page.getByRole('treeitem',{name:'栅格项 compact-item',exact:true}).click();const source=await xml();
            for(const width of [1024,980,768]){
                await page.setViewportSize({width,height:900});await page.keyboard.press('Escape');
                assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);assert.ok(await page.locator('.canvas-scroll').evaluate(node=>node.clientWidth)>=width-70,'compact panels must not reserve most of the canvas');
                await page.locator('.workspace-actions [data-toggle-panel="inspector"]').click();await page.locator('#inspector-panel').waitFor({state:'visible'});await page.locator('#panel-backdrop').waitFor({state:'visible'});await page.keyboard.press('Escape');await page.locator('#panel-backdrop').waitFor({state:'hidden'});
                assert.equal(await page.evaluate(()=>document.activeElement===document.querySelector('.workspace-actions [data-toggle-panel="inspector"]')),true);assert.equal(await xml(),source);
                await page.waitForFunction(()=>{const paper=document.querySelector('.page'),text=document.querySelector('.studio-text-preview > p');return paper&&text&&Math.abs(parseFloat(getComputedStyle(text).fontSize)-12*paper.getBoundingClientRect().width/pages[0].width)<.25;});
                await page.screenshot({path:path.join(output,'workflow-compact-'+width+'.png'),fullPage:true});
            }
            await page.setViewportSize({width:1440,height:1050});await page.locator('#navigator-panel').waitFor({state:'visible'});await page.locator('#inspector-panel').waitFor({state:'visible'});assert.equal(await page.locator('#studio-container-inspector').getByRole('button',{name:'编辑内部内容',exact:true}).count(),1);assert.equal(await page.getByRole('button',{name:'编辑此格',exact:true}).count(),0);
        });
        await check('grid ux starts blank supports direct editing resize split merge swap and saved output',async()=>{
            await page.locator('.file-menu > summary').filter({hasText:/^文件$/}).click();await page.locator('#new-blank').click();
            await page.waitForFunction(()=>$('question-dialog').open||!new DOMParser().parseFromString(state.source,'application/xml').querySelector('p,grid'));
            if(await page.locator('#question-dialog').evaluate(dialog=>dialog.open))await page.locator('#question-ok').click();
            await idle();await page.getByRole('button',{name:'精确排版',exact:true}).click();
            await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="grid"]').click();
            const picker=page.getByRole('dialog',{name:'选择栅格布局',exact:true});await picker.waitFor();
            await page.screenshot({path:path.join(output,'grid-ux-layout-picker.png'),fullPage:true});
            await picker.getByRole('button',{name:'两等分',exact:true}).click();await idle();
            const itemState=()=>page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelector('grid').children].filter(node=>node.tagName==='item').map(node=>({id:node.id,row:+node.getAttribute('row'),start:+node.getAttribute('column-start'),span:+node.getAttribute('column-span'),text:node.textContent})));
            let items=await itemState();assert.equal(items.length,2);assert.ok(items.every(item=>!item.text.trim()),'new grid must contain empty editable content, not authored demo paragraphs');
            const gridId=await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('grid').id),first=items[0].id,second=items[1].id;
            assert.equal(await page.getByText('高级轨道设置',{exact:true}).evaluate(node=>node.closest('details').open),false);
            await page.screenshot({path:path.join(output,'grid-ux-empty.png'),fullPage:true});
            const doubleClickCell=async id=>{const cell=await (await object(id)).boundingBox();assert.ok(cell);await page.mouse.dblclick(cell.x+22,cell.y+cell.height/2);await page.locator('.ProseMirror').waitFor();};
            await doubleClickCell(first);
            await page.locator('.ProseMirror').evaluate(element=>{const data=new DataTransfer();data.setData('text/plain','UX-LEFT A paragraph typed into the first empty region.\n\nUX-CONTINUED A second paragraph belongs to the same region.');element.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));});await finish();
            assert.match(await xml(),/UX-LEFT/);assert.match(await xml(),/UX-CONTINUED/);
            await doubleClickCell(second);await page.locator('.ProseMirror').fill('UX-RIGHT A separately authored sidebar.');await finish();
            const chooseItem=async id=>{await page.getByRole('treeitem',{name:'栅格项 '+id,exact:true}).click();};
            await chooseItem(first);
            const gridTools=page.locator('#studio-container-inspector').getByRole('toolbar',{name:'栅格项操作',exact:true}),tools=await gridTools.boundingBox(),canvas=await page.locator('.canvas-scroll').boundingBox();assert.ok(tools&&canvas&&tools.x>=canvas.x+canvas.width-1,'grid commands must stay in the inspector without covering the document canvas');
            assert.equal(await page.locator('#studio-toolbar').getByRole('toolbar',{name:'栅格项操作',exact:true}).count(),0);
            const separator=page.getByRole('separator',{name:'调整两格宽度 '+first+' '+second,exact:true});await separator.waitFor();
            const geometry=await page.evaluate(id=>pages.flatMap(page=>page.hits).find(hit=>hit.id===id),gridId);
            const boundary=await separator.boundingBox();assert.ok(boundary);
            const physicalScale=await page.evaluate(()=>document.querySelector('.page-image').getBoundingClientRect().width/pages[0].width),trackStep=(geometry.width+12)/12*physicalScale;
            await page.mouse.move(boundary.x+boundary.width/2,boundary.y+boundary.height/2);await page.mouse.down();await page.mouse.move(boundary.x+boundary.width/2+2*trackStep,boundary.y+boundary.height/2,{steps:5});
            await page.screenshot({path:path.join(output,'grid-ux-resize-preview.png'),fullPage:true});await page.mouse.up();await idle();
            items=await itemState();assert.deepEqual(items.map(item=>[item.start,item.span]),[[1,8],[9,4]]);
            await page.locator('#undo').click();await idle();assert.deepEqual((await itemState()).map(item=>item.span),[6,6],'one drag must be one undo step');
            await page.locator('#redo').click();await idle();await chooseItem(first);
            const beforeCancel=await xml(),handle=await separator.boundingBox();assert.ok(handle);await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();await page.mouse.move(handle.x+handle.width/2-trackStep,handle.y+handle.height/2,{steps:3});await page.keyboard.press('Escape');await page.mouse.up();assert.equal(await xml(),beforeCancel,'Escape must discard a pending grid resize');
            await page.getByRole('toolbar',{name:'栅格项操作',exact:true}).getByRole('button',{name:'拆分',exact:true}).click();await idle();items=await itemState();assert.equal(items.length,3);assert.deepEqual(items.map(item=>item.span),[4,4,4]);assert.match(items[0].text,/UX-CONTINUED/);assert.equal(items[1].text.trim(),'');
            await chooseItem(first);await page.getByRole('toolbar',{name:'栅格项操作',exact:true}).getByRole('button',{name:'与右格合并',exact:true}).click();await idle();assert.equal((await itemState()).length,2);assert.match((await itemState())[0].text,/UX-CONTINUED/);
            await chooseItem(first);const drag=page.getByRole('button',{name:'拖动栅格项 '+first,exact:true}),dragBox=await drag.boundingBox(),target=await (await object(second)).boundingBox();assert.ok(dragBox&&target);
            await page.mouse.move(dragBox.x+dragBox.width/2,dragBox.y+dragBox.height/2);await page.mouse.down();await page.mouse.move(target.x+target.width/2,target.y+target.height/2,{steps:8});await page.mouse.up();await idle();
            items=await itemState();assert.deepEqual(items.map(item=>item.id),[second,first]);assert.match(items[0].text,/UX-RIGHT/);assert.match(items[1].text,/UX-LEFT/);
            await page.locator('#undo').click();await idle();assert.deepEqual((await itemState()).map(item=>item.id),[first,second]);
            assert.equal(await page.getByText('高级轨道设置',{exact:true}).evaluate(node=>node.closest('details').open),false,'routine interaction must not require numeric track settings');
            await chooseItem(first);await page.screenshot({path:path.join(output,'grid-ux-canvas.png'),fullPage:true});
            const retained=await xml();await writeFile(path.join(output,'grid-ux-geometry.json'),JSON.stringify(await page.evaluate(()=>pages),null,2));
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);const saved=path.join(output,'grid-ux-template.zip');await bundle.saveAs(saved);await page.locator('#close-template-export').click();
            await page.locator('#open-file').setInputFiles(saved);await idle();assert.equal(await xml(),retained);
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'grid-ux.pdf'));await page.locator('#close-export').click();
        });
        await check('grid ux places selected existing content side by side without copying or losing source',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><p id="existing-left">EXISTING-LEFT Keep the original paragraph.</p><p id="existing-right"><span color="#224488">EXISTING-RIGHT Keep this original style.</span></p></document>',{});
            await (await object('existing-left')).click();await (await object('existing-right')).click({modifiers:['Control']});
            await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="grid"]').click();const picker=page.getByRole('dialog',{name:'选择栅格布局',exact:true});await picker.waitFor();
            await page.getByRole('checkbox',{name:'将所选内容并排',exact:true}).check();await picker.getByRole('button',{name:'两等分',exact:true}).click();await idle();
            const retained=await page.evaluate(()=>{const d=new DOMParser().parseFromString(state.source,'application/xml');return {ids:[...d.querySelector('grid').children].map(item=>item.querySelector('p').id),left:d.querySelector('#existing-left').textContent,right:d.querySelector('#existing-right').textContent,color:d.querySelector('#existing-right span').getAttribute('color'),count:d.querySelectorAll('p').length};});
            assert.deepEqual(retained,{ids:['existing-left','existing-right'],left:'EXISTING-LEFT Keep the original paragraph.',right:'EXISTING-RIGHT Keep this original style.',color:'#224488',count:2});
            await page.locator('#undo').click();await idle();assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('grid')),null);assert.match(await xml(),/EXISTING-LEFT/);
        });
        await check('grid ux supports keyboard alternatives row presets and cancellation without content loss',async()=>{
            await load('<document version="1"><page size="A4" margin="42"/><grid id="ux-grid" tracks="12" column-gap="12" row-gap="12"><item id="ux-a" row="1" column-start="1" column-span="4"><p id="ux-p-a">Keep Alpha.</p></item><item id="ux-b" row="1" column-start="5" column-span="4"><p id="ux-p-b">Keep Beta.</p></item><item id="ux-c" row="1" column-start="9" column-span="4"><p id="ux-p-c">Keep Gamma.</p></item></grid></document>',{});
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            const select=async id=>page.getByRole('treeitem',{name:'栅格项 '+id,exact:true}).click();
            const cells=()=>page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('grid > item')].map(node=>({id:node.id,row:+node.getAttribute('row'),span:+node.getAttribute('column-span'),text:node.textContent})));
            await select('ux-a');const divider=page.getByRole('separator',{name:'调整两格宽度 ux-a ux-b',exact:true});
            await divider.focus();await page.keyboard.press('ArrowRight');await idle();assert.deepEqual((await cells()).map(cell=>cell.span),[5,3,4]);
            assert.equal(await page.evaluate(()=>selectedPaths.length),1,'resizing must retain one selected cell and its tools');
            await page.locator('#undo').click();await idle();await select('ux-a');
            const bar=page.getByRole('toolbar',{name:'栅格项操作',exact:true});await bar.getByRole('button',{name:'栅格宽度与顺序',exact:true}).click();await bar.getByRole('button',{name:'更宽',exact:true}).click();await idle();assert.deepEqual((await cells()).map(cell=>cell.span),[5,3,4]);
            await page.locator('#undo').click();await idle();await select('ux-a');
            await bar.getByRole('button',{name:'栅格宽度与顺序',exact:true}).click();await bar.getByRole('button',{name:'向后交换',exact:true}).click();await idle();assert.deepEqual((await cells()).map(cell=>cell.id),['ux-b','ux-a','ux-c']);
            await page.locator('#undo').click();await idle();await select('ux-a');
            const unchanged=await xml(),bounds=await divider.boundingBox();assert.ok(bounds);await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();await page.mouse.move(bounds.x+40,bounds.y+bounds.height/2,{steps:3});await divider.dispatchEvent('pointercancel',{pointerId:1,pointerType:'mouse'});await page.mouse.up();assert.equal(await xml(),unchanged,'pointer cancellation must discard a preview');
            const grip=await page.getByRole('button',{name:'拖动栅格项 ux-a',exact:true}).boundingBox(),target=await (await object('ux-b')).boundingBox();assert.ok(grip&&target);await page.mouse.move(grip.x+grip.width/2,grip.y+grip.height/2);await page.mouse.down();await page.mouse.move(target.x+target.width/2,target.y+target.height/2,{steps:4});await page.keyboard.press('Escape');await page.mouse.up();assert.equal(await xml(),unchanged,'Escape must cancel a pending swap');
            await (await object('ux-p-a')).click();await bar.getByRole('button',{name:'本行布局',exact:true}).click();await page.getByRole('dialog',{name:'选择栅格布局',exact:true}).getByRole('button',{name:'两等分',exact:true}).click();await idle();let actual=await cells();assert.deepEqual(actual.map(cell=>cell.span),[6,6]);assert.equal(actual[0].text,'Keep Alpha.');assert.equal(actual[1].text,'Keep Beta.Keep Gamma.');
            await page.locator('#undo').click();await idle();assert.deepEqual((await cells()).map(cell=>cell.text),['Keep Alpha.','Keep Beta.','Keep Gamma.']);
            await select('ux-a');await bar.getByRole('button',{name:'添加行',exact:true}).click();const picker=page.getByRole('dialog',{name:'选择栅格布局',exact:true});await picker.waitFor();await picker.getByRole('button',{name:'取消',exact:true}).click();assert.equal(await xml(),unchanged,'cancelled add-row must not write source or history');
            await bar.getByRole('button',{name:'添加行',exact:true}).click();await picker.getByRole('button',{name:'三等分',exact:true}).click();await idle();actual=await cells();assert.deepEqual(actual.slice(3).map(cell=>[cell.row,cell.span,cell.text]),[[2,4,''],[2,4,''],[2,4,'']]);
            await page.setViewportSize({width:980,height:820});await page.screenshot({path:path.join(output,'grid-ux-narrow.png'),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'grid controls must fit a narrow workbench');await page.setViewportSize({width:1440,height:1050});
        });
        await check('grid editor creates tracked items and retains coordinates content undo and saved source',async()=>{
            await load('<document version="1"><page width="595" height="842" margin="36"/><p id="grid-anchor" size="12" line-height="18">Grid editing from the insertion menu.</p></document>',{});
            if(!await page.evaluate(()=>state.images.some(image=>image.name==='mark'))){
                const bytes=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=40;canvas.height=24;const context=canvas.getContext('2d');context.fillStyle='#224488';context.fillRect(0,0,40,24);return canvas.toDataURL('image/png').split(',')[1];});
                await page.locator('#resources-tab').click();await page.evaluate(()=>{assetKind='image';$('asset-file').value='';});await page.locator('#asset-file').setInputFiles({name:'mark.png',mimeType:'image/png',buffer:Buffer.from(bytes,'base64')});await page.locator('#question-answer').fill('mark');await page.locator('#question-ok').click();await idle();await page.locator('#navigator-tab').click();await page.locator('#canvas-tab').click();
            }
            await page.getByRole('button',{name:'精确排版',exact:true}).click();
            await (await object('grid-anchor')).click();await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="grid"]').click();
            await page.getByRole('dialog',{name:'选择栅格布局',exact:true}).getByRole('button',{name:'两等分',exact:true}).click();
            await page.waitForFunction(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('grid'));await idle();
            const created=await page.evaluate(()=>{const grid=new DOMParser().parseFromString(state.source,'application/xml').querySelector('grid');return {id:grid.id,items:[...grid.children].map(item=>({id:item.id,textId:item.querySelector('p').id}))};}),gridId=created.id,left=created.items[0].id,right=created.items[1].id;
            const advanced=async()=>{const details=page.locator('details.grid-advanced');if(!await details.evaluate(node=>node.open))await details.locator('summary').click();};
            await page.getByRole('treeitem',{name:'栅格 '+gridId,exact:true}).click();await advanced();
            assert.equal(await page.getByLabel('栅格轨数',{exact:true}).inputValue(),'12');
            await page.getByLabel('轨间距（pt）',{exact:true}).fill('8');await page.getByLabel('行间距（pt）',{exact:true}).fill('10');
            await page.getByRole('treeitem',{name:'栅格 '+gridId,exact:true}).click();await idle();
            await page.getByRole('treeitem',{name:'栅格项 '+left,exact:true}).click();
            await advanced();
            assert.equal(await page.evaluate(()=>selected.id),left);assert.match(await (await object(left)).getAttribute('class'),/studio-grid-item.*is-selected|is-selected.*studio-grid-item/);
            assert.equal(await page.getByRole('button',{name:'拖动排序',exact:true}).count(),0,'explicit item coordinates must not offer an invalid source-only reorder');
            assert.equal(await page.getByLabel('起始轨',{exact:true}).inputValue(),'1');assert.equal(await page.getByLabel('跨轨数',{exact:true}).inputValue(),'6');
            const beforeInvalid=await xml();await page.getByLabel('跨轨数',{exact:true}).fill('8');
            await page.waitForFunction(()=>$('status').textContent.includes('草稿无法预览'));
            assert.equal(await xml(),beforeInvalid,'an overlapping item draft must retain the last saved source');
            await page.getByLabel('跨轨数',{exact:true}).fill('4');await page.getByRole('treeitem',{name:'栅格项 '+left,exact:true}).click();await idle();
            const narrow=await page.evaluate(id=>pages.flatMap(page=>page.hits).find(hit=>hit.id===id).width,left);
            assert.ok(narrow<180&&narrow>150,'four tracks must change the actual engine item width');
            await advanced();await page.getByLabel('栅格行号',{exact:true}).fill('2');await page.getByRole('treeitem',{name:'栅格项 '+left,exact:true}).click();await idle();
            assert.deepEqual(await page.evaluate(id=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelector('#'+id).children].map(node=>node.id),gridId),[right,left]);
            await page.locator('#undo').click();await idle();assert.equal(await page.evaluate(id=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#'+id).getAttribute('row'),left),'1');
            await page.locator('#redo').click();await idle();assert.equal(await page.evaluate(id=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('#'+id).getAttribute('row'),left),'2');
            await page.getByRole('treeitem',{name:'段落 '+created.items[0].textId,exact:true}).click();
            assert.equal(await page.locator('[data-attribute="rotation"]').isDisabled(),true);
            assert.equal(await page.locator('[data-attribute="keep-with-next"] option[value="true"]').evaluate(option=>option.disabled),true);
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Edited text inside the grid item.');
            await page.getByRole('treeitem',{name:'栅格项 '+left,exact:true}).click();await idle();assert.match(await xml(),/Edited text inside the grid item/);
            await page.locator('#studio-container-inspector').getByRole('button',{name:'添加内容',exact:true}).click();await page.getByRole('dialog',{name:'在容器内添加',exact:true}).getByRole('button',{name:'表格',exact:true}).click();await idle();
            const tableId=await page.evaluate(()=>nodeAt(selected.path).closest('table').id);assert.equal(await page.evaluate(()=>nodeAt(selected.path).closest('table').parentElement.tagName),'item');
            const cell=await page.evaluate(id=>entries.find(entry=>entry.tag==='cell'&&entry.path.startsWith(entries.find(entry=>entry.id===id).path+'/')).path,tableId);
            await page.evaluate(async path=>choose(entries.find(entry=>entry.path===path)),cell);
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Edited grid table cell');
            await page.getByRole('treeitem',{name:'栅格项 '+right,exact:true}).click();await idle();assert.match(await xml(),/Edited grid table cell/);
            await page.locator('#studio-container-inspector').getByRole('button',{name:'添加内容',exact:true}).click();await page.getByRole('dialog',{name:'在容器内添加',exact:true}).getByRole('button',{name:'图片',exact:true}).click();await page.locator('#image-picker').getByRole('button',{name:'图片资源 mark',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>nodeAt(selected.path).parentElement.tagName),'item');
            for(const label of ['添加 6 + 6 行','添加 8 + 4 行','添加 4 + 4 + 4 行']){
                await page.getByRole('treeitem',{name:'栅格 '+gridId,exact:true}).click();await advanced();await page.getByRole('button',{name:label,exact:true}).click();await idle();
                const ids=await page.evaluate(()=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('[id]')].map(node=>node.id));assert.equal(new Set(ids).size,ids.length,'each row preset must give every item and paragraph a unique ID');
            }
            const rows=await page.evaluate(id=>[...new DOMParser().parseFromString(state.source,'application/xml').querySelector('#'+id).children].map(node=>[+node.getAttribute('row'),+node.getAttribute('column-start'),+node.getAttribute('column-span')]),gridId);
            assert.deepEqual(rows.slice(-7),[[3,1,6],[3,7,6],[4,1,8],[4,9,4],[5,1,4],[5,5,4],[5,9,4]]);
            await page.getByRole('treeitem',{name:'栅格项 '+left,exact:true}).click();await idle();await page.screenshot({path:path.join(output,'grid-editor.png'),fullPage:true});
            const retained=await xml();await writeFile(path.join(output,'grid-editor-geometry.json'),JSON.stringify(await page.evaluate(()=>pages),null,2));
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
            const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);const saved=path.join(output,'grid-editor-template.zip');await bundle.saveAs(saved);await page.locator('#close-template-export').click();
            await page.locator('#open-file').setInputFiles(saved);await idle();assert.equal(await xml(),retained);
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'grid-editor.pdf'));await page.locator('#close-export').click();
        });
        await check('built in grid editor sample combines offsets spanning items and sibling column flows',async()=>{
            await page.locator('#new-sample').evaluate(button=>button.click());await page.locator('#sample-list button').filter({hasText:'十二轨栅格编辑'}).click();
            await page.waitForFunction(()=>$('question-dialog').open||state.source.includes('id="editor-grid"'));if(await page.locator('#question-dialog').evaluate(dialog=>dialog.open))await page.locator('#question-ok').click();
            await page.waitForFunction(()=>state.source.includes('id="editor-grid"'));await idle();
            await page.getByRole('button',{name:'精确排版',exact:true}).click();await page.getByRole('treeitem',{name:'栅格 editor-grid',exact:true}).click();assert.equal(await page.getByLabel('栅格轨数',{exact:true}).inputValue(),'12');
            await page.setViewportSize({width:1440,height:1250});await page.getByLabel('缩放',{exact:true}).selectOption('0.75');await page.locator('.canvas-scroll').evaluate(node=>node.scrollTop=0);
            await page.screenshot({path:path.join(output,'grid-editor-sample-settings.png'),fullPage:true});
            await page.getByRole('treeitem',{name:'栅格项 grid-offset',exact:true}).click();assert.equal(await page.getByLabel('起始轨',{exact:true}).inputValue(),'3');assert.equal(await page.getByLabel('跨轨数',{exact:true}).inputValue(),'8');
            const geometry=await page.evaluate(()=>pages);assert.ok(geometry.flatMap(page=>page.hits).some(hit=>hit.id==='grid-evidence'&&hit.width>500));assert.ok(geometry.flatMap(page=>page.hits).some(hit=>hit.id==='grid-figure'&&hit.images[0]?.width===320));
            assert.ok(geometry.flatMap(page=>page.hits).some(hit=>hit.id==='grid-before'));assert.ok(geometry.flatMap(page=>page.hits).some(hit=>hit.id==='grid-after'));
            await writeFile(path.join(output,'grid-editor-sample-geometry.json'),JSON.stringify(geometry,null,2));await writeFile(path.join(output,'grid-editor-sample-data.json'),await page.evaluate(()=>state.data));await page.screenshot({path:path.join(output,'grid-editor-sample.png'),fullPage:true});
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);await bundle.saveAs(path.join(output,'grid-editor-sample.zip'));await page.locator('#close-template-export').click();
            const [pdf]=await Promise.all([page.waitForEvent('download'),(async()=>{await page.locator('#export').click();await page.locator('#format').selectOption('PDF');await page.locator('#download-export').click();})()]);await pdf.saveAs(path.join(output,'grid-editor-sample.pdf'));await page.locator('#close-export').click();
            await page.getByLabel('缩放',{exact:true}).selectOption('fit');await page.setViewportSize({width:1440,height:1050});
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
            await page.locator('.studio-background').nth(1).click({position:{x:90,y:200}});await page.waitForFunction(()=>selected?.id==='part-two');
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
