// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {chromium} from 'playwright';

const repository=fileURLToPath(new URL('../../../',import.meta.url));
const output=path.resolve(repository,process.env.EXPORT4J_TEMPLATE_OUTPUT||'build/verification/designer-inspector-navigation/template-inspector');
const source=`<document version="1"><page width="420" height="600" margin="30"/>
<if test="enabled"><then><p id="hidden">Hidden \${title}</p></then><else><p id="shown">Shown</p></else></if>
<each items="items" as="item"><p id="loop">Loop \${item.name}</p></each>
<list items="items" as="item"><bullet id="bullet">\${item.name}</bullet></list>
<rows id="rows" items="records" as="item"><columns><column weight="1"/></columns><header><cell>Header</cell></header><row><cell>\${item.name}</cell></row></rows>
<caption id="target" kind="Figure">Caption</caption><cross-reference id="reference" kind="PAGE" target="target">See page </cross-reference>
</document>`;
const conditionSource='<document version="1"><if test="enabled"><then><p id="hidden">Hidden ${title}</p></then><else><p id="shown">Shown</p></else></if></document>';

test('one inspector navigates source structure without displacing the document canvas',{timeout:180000},async t=>{
    await mkdir(output,{recursive:true});
    const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin','java.exe'):'java';
    const distribution=process.env.EXPORT4J_DESIGNER_DIST?path.resolve(process.env.EXPORT4J_DESIGNER_DIST):path.join(repository,'export4j-designer/build/install/export4j-designer');
    const child=process.env.EXPORT4J_STUDIO_URL?undefined:spawn(java,['-Djava.awt.headless=true','-cp',path.join(distribution,'lib/*'),'com.mirakyux.export4j.designer.DesignerApp','--port=0'],{windowsHide:true});
    let logs='',browser;
    try{
        const origin=process.env.EXPORT4J_STUDIO_URL||await new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>reject(new Error('Designer startup timed out: '+logs)),20000);
            child.stdout.on('data',value=>{logs+=value;const found=logs.match(/http:\/\/127\.0\.0\.1:\d+/);if(found){clearTimeout(timer);resolve(found[0]);}});
            child.stderr.on('data',value=>logs+=value);child.once('error',error=>{clearTimeout(timer);reject(error);});
            child.once('exit',code=>{clearTimeout(timer);reject(new Error('Designer exited '+code));});
        });
        browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
        const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
        page.on('pageerror',error=>errors.push(String(error)));
        await page.goto(origin+'/?view=canvas&panel=none');await page.waitForFunction(()=>typeof state!=='undefined'&&state&&!busy&&pages.length>0);
        const idle=async()=>{await page.waitForFunction(()=>!busy&&!DesignerStudioBridge.snapshot().pending);const state=await page.evaluate(()=>({layout:DesignerStudioBridge.snapshot().layoutState,message:$('status').textContent}));assert.equal(state.layout,'synced',state.message);};
        const previewAfter=async work=>{const before=await page.evaluate(()=>previewId);await work();await page.waitForFunction(before=>previewId!==before&&DesignerStudioBridge.snapshot().layoutState==='synced',before);};
        const load=async(xml=source,data={enabled:false,visible:true,title:'Title',items:[],other:[],records:[{name:'Row'}]})=>{
            await page.evaluate(async({xml,data})=>{$('data').value=typeof data==='string'?data:JSON.stringify(data);await DesignerStudioBridge.apply(xml);await DesignerStudioBridge.flush();},{xml,data});await idle();
        };
        const select=async tag=>{await page.evaluate(tag=>document.dispatchEvent(new CustomEvent('designer-template-select',{detail:{path:entries.find(entry=>entry.tag===tag).path}})),tag);await idle();};
        const selectId=async id=>{await page.evaluate(id=>document.dispatchEvent(new CustomEvent('designer-template-select',{detail:{path:entries.find(entry=>entry.id===id).path}})),id);await idle();};
        const inspector=page.locator('#inspector-panel');
        const children=()=>inspector.locator('[data-content-navigation]');
        const enterChild=async selector=>{await children().locator(selector).first().click();await idle();};
        const crumb=async name=>{await inspector.locator('#selection-crumb').getByRole('button',{name,exact:true}).click();await idle();};
        const insertCondition=async()=>{await page.locator('.insert-menu > summary').click();await page.locator('[data-insert="if"]').click();};
        const canvasRegion=()=>page.locator('.canvas-scroll').evaluate(node=>{const rect=node.getBoundingClientRect();return {top:rect.top,height:rect.height,width:rect.width,bottom:rect.bottom,viewportHeight:innerHeight};});
        const sameCanvasHeight=(before,after,label)=>{
            // A dock may change canvas width; it must not become another row above the paper.
            assert.ok(Math.abs(after.top-before.top)<=1,`${label}: canvas moved vertically (${before.top} → ${after.top})`);
            assert.ok(Math.abs(after.height-before.height)<=1,`${label}: canvas height changed (${before.height} → ${after.height})`);
        };
        const canvasRoom=region=>{
            assert.ok(region.height>=region.viewportHeight*.5,`document canvas has only ${region.height}/${region.viewportHeight} pixels of viewport height`);
            assert.ok(region.bottom<=region.viewportHeight+1,'canvas extends below the browser viewport');
            assert.ok(region.width>=300,'document canvas is narrower than a readable editing region');
        };
        const screenshot=async name=>{
            // Layout synchronization precedes asynchronous PNG delivery; capture the current preview.
            await page.waitForFunction(()=>{
                const region=document.querySelector('.canvas-scroll').getBoundingClientRect();
                const sheets=[...document.querySelectorAll('.canvas-sheet')].filter(node=>{const rect=node.getBoundingClientRect();return rect.bottom>region.top&&rect.top<region.bottom;});
                return sheets.length>0&&sheets.every(node=>{const image=node.querySelector('.page-image');return image?.dataset.preview===previewId&&image.complete&&image.naturalWidth>0;});
            },undefined,{timeout:15000});
            await page.screenshot({path:path.join(output,name)});
        };
        await load();
        await t.test('document children and condition branches share the inspector breadcrumb',async()=>{
            await select('document');
            assert.equal(await page.locator('#logic-tab,#logic-view,#template-logic-panel,.template-logic-launcher').count(),0);
            assert.equal(await page.locator('#studio-toolbar').getByRole('button',{name:/模板逻辑/}).count(),0);
            assert.equal(await inspector.locator('#selection-crumb').count(),1);
            assert.equal(await page.locator('#editor-space #selection-crumb').count(),0);
            assert.equal(await children().locator('[data-content-tag="if"]').count(),1);
            assert.equal(await children().locator('[data-content-tag="each"]').count(),1);
            const before=await canvasRegion();canvasRoom(before);
            await enterChild('[data-content-tag="if"]');
            assert.equal(await page.getByLabel('条件数据路径',{exact:true}).inputValue(),'enabled');
            await enterChild('[data-content-tag="then"]');
            await enterChild('[data-content-tag="p"]');
            assert.equal(await page.getByRole('textbox',{name:'正文富文本',exact:true}).innerText(),'Hidden ${title}');
            assert.match(await inspector.locator('#selection-crumb').innerText(),/文档.*条件.*满足条件.*段落 #hidden/s);
            sameCanvasHeight(before,await canvasRegion(),'drilling into an inactive branch');
            await crumb('条件');
            assert.equal(await page.getByLabel('条件数据路径',{exact:true}).inputValue(),'enabled');
            await screenshot('inspector-condition.png');
        });
        await t.test('inactive branch opens a real source editor without inventing data',async()=>{
            const before=await page.evaluate(()=>state.data);
            await enterChild('[data-content-tag="then"]');await enterChild('[data-content-tag="p"]');
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Changed ${title}');
            await crumb('条件');assert.match(await page.evaluate(()=>state.source),/Changed \$\{title\}/);
            assert.equal(await page.evaluate(()=>state.data),before);
            assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().pages.some(page=>page.hits.some(hit=>hit.id==='hidden'))),false);
            await enterChild('[data-content-tag="then"]');await enterChild('[data-content-tag="p"]');
            await screenshot('inactive-branch.png');
        });
        await t.test('condition inspector changes the predicate and retains both branches',async()=>{
            await select('if');await page.locator('#structured-properties').getByLabel('条件数据路径',{exact:true}).fill('visible');
            await crumb('文档');assert.match(await page.evaluate(()=>state.source),/test="visible"/);
            assert.match(await page.evaluate(()=>state.source),/<else><p id="shown">Shown<\/p><\/else>/);
            await page.waitForFunction(()=>DesignerStudioBridge.snapshot().layoutState==='synced');
            assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().pages.some(page=>page.hits.some(hit=>hit.id==='hidden'))),true);
        });
        await t.test('empty loop template remains configurable and its paragraph editable',async()=>{
            await enterChild('[data-content-tag="each"]');await page.getByLabel('数组路径',{exact:true}).fill('other');
            await crumb('文档');assert.match(await page.evaluate(()=>state.source),/items="other"/);
            await enterChild('[data-content-tag="each"]');await enterChild('[data-content-tag="p"]');
            const body=page.getByRole('textbox',{name:'正文富文本',exact:true});assert.equal(await body.innerText(),'Loop ${item.name}');
            await body.fill('Changed loop ${item.name}');await crumb('数据循环');
            assert.match(await page.evaluate(()=>state.source),/Changed loop \$\{item\.name\}/);
            assert.deepEqual(JSON.parse(await page.evaluate(()=>state.data)).other,[]);
        });
        await t.test('nested empty loops retain the local binding scope while navigating up',async()=>{
            await load('<document version="1"><p>Visible body</p><each items="groups" as="group"><each items="group.children" as="child"><p id="nested-template">Child ${child.name}</p></each></each></document>',{groups:[]});
            await select('document');await enterChild('[data-content-tag="each"]');await enterChild('[data-content-tag="each"]');await enterChild('[data-content-tag="p"]');
            assert.match(await inspector.locator('[data-binding-help]').innerText(),/child/);
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Edited ${child.name}');
            // Both loop ancestors have the same label, so navigate through the source path.
            const outer=await page.evaluate(()=>entries.find(entry=>entry.tag==='each').path);
            await inspector.locator(`#selection-crumb [data-content-path="${outer}"]`).click();await idle();
            assert.equal(await page.getByLabel('数组路径',{exact:true}).inputValue(),'groups');
            assert.match(await page.evaluate(()=>state.source),/Edited \$\{child\.name\}/);
            assert.deepEqual(JSON.parse(await page.evaluate(()=>state.data)),{groups:[]});
        });
        await t.test('inline condition fields preserve both branches and exact test data',async()=>{
            const data='{"enabled":false,"visible":true,"title":"Title","exact":9007199254740993}';
            await load(conditionSource,data);await select('if');
            const panel=inspector.locator('[data-condition-editor]');
            await panel.locator('summary').filter({hasText:'选择布尔变量'}).click();
            assert.equal(await panel.locator('[data-binding-path="title"]').count(),0,'a condition picker must offer Boolean fields only');
            await panel.locator('[data-binding-path="visible"]').click();
            assert.equal(await panel.getByLabel('条件数据路径',{exact:true}).inputValue(),'visible');
            assert.match(await panel.locator('[data-condition-value]').innerText(),/当前显示：满足条件/);
            assert.equal(await page.locator('dialog[open]').count(),0,'choosing a condition must remain inside the inspector');
            await crumb('文档');
            assert.deepEqual(await page.evaluate(()=>{
                const node=new DOMParser().parseFromString(state.source,'application/xml').querySelector('if');
                return {test:node.getAttribute('test'),then:node.querySelector('then').textContent,else:node.querySelector('else').textContent};
            }),{test:'visible',then:'Hidden ${title}',else:'Shown'});
            assert.equal(await page.evaluate(()=>state.data),data,'field selection must preserve large numeric JSON tokens');
            await select('if');await page.getByLabel('条件数据路径',{exact:true}).fill('enabled');await crumb('文档');
            assert.match(await page.evaluate(()=>state.source),/test="enabled"/);
            assert.equal(await page.evaluate(()=>state.data),data);
            await select('if');
            const inactive=inspector.locator('.condition-branch-preview[data-condition-branch="then"]');
            assert.match(await inactive.innerText(),/Hidden \$\{title\}/);
            await inactive.locator('[data-content-tag="p"]').click();await idle();
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Direct branch ${title}');await crumb('条件');
            assert.match(await inspector.locator('.condition-branch-preview[data-condition-branch="then"]').innerText(),/Direct branch \$\{title\}/);
            assert.match(await inspector.locator('.condition-branch-preview[data-condition-branch="else"]').innerText(),/Shown/);
            assert.equal(await page.evaluate(()=>state.data),data,'opening and editing an inactive branch must not change its test data');
        });
        await t.test('condition draft and live Boolean test value update undo together',async()=>{
            await page.locator('.file-menu > summary').filter({hasText:'文件'}).click();await page.locator('#new-sample').click();
            await page.locator('#sample-list button').filter({hasText:'巡检报告'}).click();
            await page.waitForFunction(()=>$('question-dialog').open||state.source.includes('STATUS-APPROVED'));
            if(await page.locator('#question-dialog').evaluate(dialog=>dialog.open))await page.locator('#question-ok').click();
            await page.waitForFunction(()=>state.source.includes('STATUS-APPROVED'));await idle();await select('if');
            const fresh=await page.evaluate(()=>({source:state.source,data:state.data,canUndo:DesignerStudioBridge.snapshot().canUndo}));
            assert.equal(fresh.canUndo,false,'a newly opened sample must have no prior editing history');
            assert.equal(await page.getByRole('button',{name:'撤销',exact:true}).isDisabled(),true);
            await previewAfter(()=>inspector.locator('[data-condition-editor]').getByLabel('条件测试值',{exact:true}).selectOption('false'));
            assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().canUndo),true,'the first uncommitted live edit must enable undo');
            assert.equal(await page.getByRole('button',{name:'撤销',exact:true}).isEnabled(),true);
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            assert.deepEqual(await page.evaluate(()=>({source:state.source,data:state.data})),{source:fresh.source,data:fresh.data});
            assert.equal(await page.evaluate(()=>pages.some(page=>page.hits.some(hit=>hit.id==='approved'))),true);
            await page.getByRole('button',{name:'重做',exact:true}).click();await idle();
            assert.equal(JSON.parse(await page.evaluate(()=>state.data)).approved,false);
            assert.equal(await page.evaluate(()=>pages.some(page=>page.hits.some(hit=>hit.id==='pending'))),true);
            const data='{"enabled":false,"visible":false,"title":"Title","exact":9007199254740993}';
            await load(conditionSource,data);await select('if');
            const panel=inspector.locator('[data-condition-editor]');
            await panel.getByLabel('条件数据路径',{exact:true}).fill('visible');
            await page.evaluate(()=>clearTimeout(previewTimer));
            assert.equal(await panel.getByRole('button',{name:'更新测试值',exact:true}).count(),0);
            await previewAfter(()=>panel.getByLabel('条件测试值',{exact:true}).selectOption('true'));
            assert.equal(await page.evaluate(()=>state.data),data,'live previews must retain a single uncommitted editing draft');
            assert.equal(await page.evaluate(()=>pages.some(page=>page.hits.some(hit=>hit.id==='hidden'))),true);
            await crumb('文档');
            assert.match(await page.evaluate(()=>state.source),/test="visible"/);
            assert.equal(await page.evaluate(()=>state.data),'{"enabled":false,"visible":true,"title":"Title","exact":9007199254740993}');
            assert.equal(await page.evaluate(()=>DesignerStudioBridge.snapshot().pages.some(page=>page.hits.some(hit=>hit.id==='hidden'))),true);
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            assert.match(await page.evaluate(()=>state.source),/test="enabled"/);
            assert.equal(await page.evaluate(()=>state.data),data,'one undo must restore the predicate and exact test data');
        });
        await t.test('inline variable insertion restores the caret inside styled scoped content',async()=>{
            const data='{"items":[{"name":"Ada"}],"exact":9007199254740993}';
            await load('<document version="1"><each items="items" as="item"><p id="scoped-body">A <span role="bold" color="#123456">BOLD</span> Z <page-number width="24"/></p></each></document>',data);
            await selectId('scoped-body');
            await page.locator('#body-content').evaluate(area=>{
                const span=[...area.querySelectorAll('span')].find(node=>node.textContent==='BOLD'),range=document.createRange();
                area.focus();range.setStart(span.firstChild,2);range.collapse(true);const current=getSelection();current.removeAllRanges();current.addRange(range);
                document.dispatchEvent(new Event('selectionchange'));
            });
            const panel=inspector.locator('#body-bindings');
            await panel.locator('[data-binding-path="item.name"]').click();
            assert.equal(await panel.getByLabel('变量路径',{exact:true}).inputValue(),'item.name');
            await panel.getByRole('button',{name:'插入到正文',exact:true}).click();
            assert.equal(await panel.locator('.body-binding-error').isVisible(),false,await panel.locator('.body-binding-error').innerText());
            assert.equal(await page.locator('dialog[open]').count(),0,'sidebar variable insertion must not require a modal');
            await crumb('文档');
            assert.deepEqual(await page.evaluate(()=>{
                const node=new DOMParser().parseFromString(state.source,'application/xml').querySelector('#scoped-body');
                return {text:node.textContent,bold:[...node.querySelectorAll('span[role="bold"][color="#123456"]')].map(span=>span.textContent).join(''),pageNumbers:node.querySelectorAll('page-number').length};
            }),{text:'A BO${item.name}LD Z ',bold:'BO${item.name}LD',pageNumbers:1});
            assert.equal(await page.evaluate(()=>state.data),data);
        });
        await t.test('inline variable references edit one occurrence and reject malformed paths',async()=>{
            const data='{"title":"Old","subtitle":"New","exact":9007199254740993}';
            await load('<document version="1"><p id="variable-body">Before <span role="bold">BOLD</span> ${title} and <span color="#123456">${title}</span></p></document>',data);
            await selectId('variable-body');
            let panel=inspector.locator('#body-bindings');
            assert.equal(await panel.locator('[data-body-variable="title"]').count(),2);
            await panel.locator('[data-body-variable="title"]').nth(1).click();
            await panel.getByLabel('变量路径',{exact:true}).fill('subtitle');
            await panel.getByRole('button',{name:'更新变量引用',exact:true}).click();
            assert.equal(await panel.locator('.body-binding-error').isVisible(),false,await panel.locator('.body-binding-error').innerText());await crumb('文档');
            assert.deepEqual(await page.evaluate(()=>{
                const node=new DOMParser().parseFromString(state.source,'application/xml').querySelector('#variable-body');
                return {text:node.textContent,bold:node.querySelector('span[role="bold"]').textContent,color:[...node.querySelectorAll('span[color="#123456"]')].map(span=>span.textContent).join('')};
            }),{text:'Before BOLD ${title} and ${subtitle}',bold:'BOLD',color:'${subtitle}'});
            await selectId('variable-body');panel=inspector.locator('#body-bindings');
            await panel.locator('[data-body-variable="subtitle"]').click();await screenshot('inline-variable-editor.png');
            await panel.getByRole('button',{name:'改为插入变量',exact:true}).click();
            const before=await page.evaluate(()=>({source:state.source,data:state.data,body:$('body-content').innerText}));
            await panel.getByLabel('变量路径',{exact:true}).fill('bad..path');
            await panel.getByRole('button',{name:'插入到正文',exact:true}).click();
            await panel.locator('.body-binding-error[role="alert"]').waitFor({state:'visible'});
            assert.deepEqual(await page.evaluate(()=>({source:state.source,data:state.data,body:$('body-content').innerText})),before,'invalid paths must not mutate the body, source or data');
            assert.equal(await page.evaluate(()=>state.data),data);
            await screenshot('inline-variable-invalid-path.png');
        });
        await t.test('live variable values preserve multiline JSON caret drafts and invalid numeric input',async()=>{
            const data='{"title":"first\\nsecond\\r\\nthird","exact":9007199254740993}';
            await load('<document version="1"><p id="test-value">Original ${title}</p></document>',data);
            await selectId('test-value');
            let panel=inspector.locator('#body-bindings');
            await panel.locator('[data-body-variable="title"]').click();
            assert.equal(await panel.getByLabel('变量测试值',{exact:true}).inputValue(),'first\nsecond\nthird');
            assert.equal(await panel.getByRole('button',{name:'更新测试值',exact:true}).count(),0);
            await panel.getByLabel('变量测试值',{exact:true}).focus();await panel.getByLabel('变量路径',{exact:true}).focus();
            assert.equal(await page.evaluate(()=>state.data),data);
            assert.equal(await page.evaluate(()=>$('data').value),data,'viewing an untouched textarea must preserve original JSON escapes and CRLF values');
            const external='{"title":"Changed elsewhere","exact":9007199254740993}';
            await page.evaluate(value=>{$('data').value=value;},external);
            await panel.getByLabel('变量测试值',{exact:true}).fill('Stale replacement');
            await panel.locator('.body-variable-value .binding-inline-error').waitFor({state:'visible'});
            assert.match(await panel.locator('.body-variable-value .binding-inline-error').innerText(),/测试值已变化/);
            assert.equal(await page.evaluate(()=>$('data').value),external,'a stale value editor must not overwrite a pending test-data edit');
            assert.equal(await page.evaluate(()=>state.data),data,'rejecting a stale editor must not commit pending test data');
            await page.evaluate(value=>{$('data').value=value;},data);
            await panel.getByLabel('变量路径',{exact:true}).fill('exact');
            const numeric=panel.getByLabel('变量测试值',{exact:true});await numeric.fill('1e');
            await panel.locator('.body-variable-value .binding-inline-error').waitFor({state:'visible'});
            await numeric.pressSequentially('+');assert.equal(await numeric.inputValue(),'1e+');
            assert.equal(await page.evaluate(()=>$('data').value),data,'partial numeric input must not overwrite exact JSON data');
            assert.equal(await page.evaluate(()=>state.data),data);
            await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill('Edited ${title}');
            await page.evaluate(()=>clearTimeout(previewTimer));
            panel=inspector.locator('#body-bindings');await panel.locator('[data-body-variable="title"]').click();
            const live=panel.getByLabel('变量测试值',{exact:true}),text='new first\nnew second!?';
            await previewAfter(async()=>{await live.fill('new first\nnew second');await live.pressSequentially('!?');});
            assert.deepEqual(await live.evaluate(node=>({focused:node===document.activeElement,start:node.selectionStart,end:node.selectionEnd,value:node.value})),{focused:true,start:text.length,end:text.length,value:text},'live previews must retain the input and its caret across consecutive edits');
            assert.equal(await page.evaluate(()=>state.data),data,'consecutive live edits must remain one pending draft');
            await crumb('文档');
            assert.match(await page.evaluate(()=>state.source),/Edited \$\{title\}/);
            assert.equal(await page.evaluate(()=>state.data),'{"title":"new first\\nnew second!?","exact":9007199254740993}');
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            assert.match(await page.evaluate(()=>state.source),/Original \$\{title\}/);
            assert.equal(await page.evaluate(()=>state.data),data,'one undo must restore the pending body draft and original exact JSON');
        });
        await t.test('pending owner drafts relocate identified children and reject stale anonymous paths',async()=>{
            for(const identified of [true,false]){
                const id=identified?' id="pending-child"':'';
                await load(`<document version="1"><p>Visible body</p><if test="enabled"><then><p>FIRST</p><p${id}>SECOND</p></then></if></document>`);
                await select('then');
                const target=await children().locator('[data-content-tag="p"]').nth(1).getAttribute('data-content-path');
                await page.locator('[data-inspect="node-source"]').click();
                await page.locator('#node-xml').fill(`<then><p>INSERTED</p><p>FIRST</p><p${id}>SECOND</p></then>`);
                // Keep a real pending node draft until navigation flushes it; avoid timer timing deciding the scenario.
                await page.evaluate(()=>clearTimeout(previewTimer));
                await page.locator('[data-inspect="properties"]').click();
                await children().locator(`[data-content-path="${target}"]`).click();await idle();
                if(identified){
                    assert.equal(await page.evaluate(()=>selected.id),'pending-child');
                    assert.equal(await page.getByRole('textbox',{name:'正文富文本',exact:true}).innerText(),'SECOND');
                }else{
                    assert.equal(await page.evaluate(()=>selected.tag),'then','a stale anonymous source path must not select the new sibling at that index');
                    assert.match(await children().locator('.content-navigation-error[role="alert"]').innerText(),/内容位置已变化/);
                }
                assert.match(await page.evaluate(()=>state.source),/INSERTED/);
            }
        });
        await t.test('caption and reference prefixes can be edited in properties',async()=>{
            await load();await selectId('target');await page.getByLabel('节点正文',{exact:true}).fill('Updated caption');
            assert.equal(await page.getByRole('textbox',{name:'正文富文本',exact:true}).isVisible(),false);
            assert.equal(await page.locator('#structured-properties [data-attribute="role"]').count(),0);
            await selectId('reference');await page.getByLabel('节点正文',{exact:true}).fill('Go to page ');
            await select('document');assert.match(await page.evaluate(()=>state.source),/Updated caption/);assert.match(await page.evaluate(()=>state.source),/Go to page /);
        });
        await t.test('branch without content permits adding a paragraph and one undo',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><if test="enabled"><then/><else><p>Shown</p></else></if></document>');
            await select('then');await page.locator('[data-content-navigation]').getByRole('button',{name:'在此添加内容',exact:true}).click();
            await page.getByRole('button',{name:'文字段落',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('then').children.length),1);
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('then').children.length),0);
        });
        await t.test('new conditions use boolean fields and cancellation leaves source intact',async()=>{
            await load('<document version="1"><p id="body">Body</p></document>');
            await insertCondition();await page.getByRole('button',{name:'enabled',exact:true}).click();
            await page.getByRole('button',{name:'创建条件',exact:true}).click();await idle();
            assert.match(await page.evaluate(()=>state.source),/<if test="enabled">/);
            await selectId('body');const before=await page.evaluate(()=>state.source);
            await insertCondition();await page.getByRole('button',{name:'取消',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>state.source),before);
        });
        await t.test('long child lists scroll inside the inspector at desktop and compact sizes',async()=>{
            const many=`<document version="1"><page width="420" height="600" margin="30"/>${Array.from({length:64},(_,index)=>`<if test="enabled"><then><p id="hidden-${index}">Hidden ${index}</p></then><else><p id="shown-${index}">Shown ${index}</p></else></if>`).join('')}</document>`;
            await load(many);const geometry=[];
            try{
                for(const size of [{width:1600,height:900},{width:1280,height:800},{width:980,height:760}]){
                    await page.setViewportSize(size);
                    const compact=size.width<=1100;
                    await page.waitForFunction(compact=>document.querySelector('.workspace').classList.contains('compact-layout')===compact,compact);
                    await select('document');
                    if(compact)await page.keyboard.press('Escape');
                    const before=await canvasRegion();canvasRoom(before);
                    if(compact){await page.locator('.workspace-actions [data-toggle-panel="inspector"]').focus();await page.keyboard.press('Enter');}
                    const opened=await canvasRegion(),measurement={size,before,opened};geometry.push(measurement);
                    sameCanvasHeight(before,opened,`${size.width}px long child list`);
                    assert.equal(await children().locator('[data-content-tag="if"]').count(),64);
                    assert.equal(await page.locator('#navigator-panel').getAttribute('aria-modal'),null,'child navigation must not open a second modal');
                    await screenshot(`inspector-layout-${size.width}.png`);
                    await enterChild('[data-content-tag="if"]');await enterChild('[data-content-tag="then"]');
                    await crumb('文档');
                    if(compact){
                        assert.equal(await inspector.getAttribute('role'),'dialog');
                        assert.equal(await inspector.evaluate(node=>node.contains(document.activeElement)),true,'child and breadcrumb navigation must keep focus inside the same inspector modal');
                        await screenshot('inspector-breadcrumb-focus-980.png');
                        await page.keyboard.press('Escape');
                        assert.equal(await page.locator('.workspace-actions [data-toggle-panel="inspector"]').evaluate(node=>node===document.activeElement),true);
                        assert.equal(await page.locator('#editor-space').evaluate(node=>node.inert),false);
                    }
                    const closed=await canvasRegion();sameCanvasHeight(before,closed,`${size.width}px closing inspector`);canvasRoom(closed);measurement.closed=closed;
                }
            }finally{
                await writeFile(path.join(output,'canvas-geometry.json'),JSON.stringify(geometry,null,2));await page.setViewportSize({width:1440,height:1000});
            }
        });
        await t.test('first double click still edits a container paragraph without a canvas jump',async()=>{
            await load('<document version="1"><page width="420" height="600" margin="30"/><table id="double-table"><columns><column weight="1"/></columns><row><cell><p id="double-text">Double click text</p></cell></row></table></document>',{});
            await select('document');const before=await canvasRegion();
            const path=await page.evaluate(()=>entries.find(entry=>entry.id==='double-text').path);
            await page.locator(`.studio-object[data-studio-path="${path}"]`).first().dblclick();await page.locator('.ProseMirror').waitFor();
            assert.equal(await page.locator('.ProseMirror').innerText(),'Double click text');
            sameCanvasHeight(before,await canvasRegion(),'first double click inside a container');
            await page.getByRole('button',{name:'完成',exact:true}).click();await idle();
        });
        assert.deepEqual(errors,[]);await writeFile(path.join(output,'page-errors.json'),JSON.stringify(errors));
        await writeFile(path.join(output,'final.xml'),await page.evaluate(()=>state.source));
    }catch(error){await writeFile(path.join(output,'server.log'),logs);throw error;}
    finally{await browser?.close();if(child&&!child.killed)child.kill();}
});
