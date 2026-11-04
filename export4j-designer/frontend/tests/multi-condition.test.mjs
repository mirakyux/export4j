// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {chromium} from 'playwright';

const repository=fileURLToPath(new URL('../../../',import.meta.url));
const output=path.resolve(repository,process.env.EXPORT4J_CONDITION_OUTPUT||'build/verification/designer-multi-condition/browser');
const scenarioFilter=process.env.EXPORT4J_CONDITION_SCENARIOS?new RegExp(process.env.EXPORT4J_CONDITION_SCENARIOS):undefined;
const initial='<document version="1"><page width="420" height="600" margin="30"/><p id="always">ALWAYS</p><if test="enabled"><then><p id="branch-one"><span role="bold" color="#224488">BRANCH_ONE</span></p></then><else><p id="branch-other">BRANCH_OTHER</p></else></if></document>';
const data=status=>`{"status":${status},"alternative":3,"enabled":true,"exact":9007199254740993}`;

test('inspector builds ordered comparisons with editable branches and faithful exports',{timeout:240000},async t=>{
    await mkdir(output,{recursive:true});
    const distribution=process.env.EXPORT4J_DESIGNER_DIST?path.resolve(process.env.EXPORT4J_DESIGNER_DIST):path.join(repository,'export4j-designer/build/install/export4j-designer');
    const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
    const child=spawn(java,['-Djava.awt.headless=true','-cp',path.join(distribution,'lib/*'),'com.mirakyux.export4j.designer.DesignerApp','--port=0'],{windowsHide:true});
    let logs='',browser,page,constructed;
    const errors=[],results=[],branchIds={BRANCH_ONE:'branch-one',BRANCH_OTHER:'branch-other'};
    try{
        const origin=await new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>reject(new Error('Designer startup timed out: '+logs)),20000);
            child.stdout.on('data',value=>{logs+=value;const found=logs.match(/http:\/\/127\.0\.0\.1:\d+/);if(found){clearTimeout(timer);resolve(found[0]);}});
            child.stderr.on('data',value=>logs+=value);child.once('error',error=>{clearTimeout(timer);reject(error);});
            child.once('exit',code=>{clearTimeout(timer);reject(new Error('Designer exited '+code));});
        });
        browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
        page=await browser.newPage({viewport:{width:1440,height:1000},acceptDownloads:true});page.setDefaultTimeout(15000);
        page.on('pageerror',error=>errors.push(String(error)));
        await page.goto(origin+'/?view=canvas&panel=none');await page.waitForFunction(()=>typeof state!=='undefined'&&state&&!busy&&pages.length>0);
        const idle=async()=>{
            await page.waitForFunction(()=>{const current=DesignerStudioBridge.snapshot();return !busy&&!current.pending&&['synced','failed'].includes(current.layoutState);});
            const current=await page.evaluate(()=>({layout:DesignerStudioBridge.snapshot().layoutState,message:$('status').textContent}));assert.equal(current.layout,'synced',current.message);
        };
        const previewAfter=async work=>{const before=await page.evaluate(()=>previewId);await work();await page.waitForFunction(before=>previewId!==before&&DesignerStudioBridge.snapshot().layoutState==='synced',before);};
        const load=async(xml=initial,raw=data(1))=>{await page.evaluate(async({xml,raw})=>{$('data').value=raw;await DesignerStudioBridge.apply(xml);await DesignerStudioBridge.flush();},{xml,raw});await idle();};
        const setData=async raw=>{await page.evaluate(async raw=>{$('data').value=raw;await DesignerStudioBridge.apply(state.source);await DesignerStudioBridge.flush();},raw);await idle();};
        const select=async tag=>{await page.evaluate(tag=>document.dispatchEvent(new CustomEvent('designer-template-select',{detail:{path:entries.find(entry=>entry.tag===tag).path}})),tag);await idle();};
        const inspector=page.locator('#inspector-panel'),editor=()=>inspector.locator('[data-condition-editor]'),card=index=>inspector.locator(`[data-condition-case="${index}"]`);
        const pauseDraft=()=>page.evaluate(()=>clearTimeout(previewTimer));
        const predicate=async(index,{field,operator,value})=>{
            const host=index===0?editor():card(index),prefix=index===0?'':`第 ${index+1} 分支`;
            await host.getByLabel(index===0?'条件数据路径':prefix+'字段',{exact:true}).fill(field);await pauseDraft();
            await host.getByLabel(index===0?'比较方式':prefix+'比较方式',{exact:true}).selectOption(operator);await pauseDraft();
            if(!['TRUE','FALSE'].includes(operator)){await host.getByLabel(index===0?'比较值':prefix+'比较值',{exact:true}).fill(value);await pauseDraft();}
        };
        const commit=()=>select('document');
        const writeBody=async text=>{
            const canvasEditor=page.locator('.ProseMirror');
            if(await canvasEditor.isVisible()){await canvasEditor.focus();await page.keyboard.press('Control+a');await page.keyboard.insertText(text);}
            else await page.getByRole('textbox',{name:'正文富文本',exact:true}).fill(text);
        };
        const addParagraph=async(index,text)=>{
            await select('if');await card(index).locator('[data-content-tag="then"]').first().click();await idle();
            await inspector.locator('[data-content-navigation]').getByRole('button',{name:'在此添加内容',exact:true}).click();
            await page.getByRole('button',{name:'文字段落',exact:true}).click();await idle();
            await writeBody(text);
            const id=await page.evaluate(()=>selected.id);assert.ok(id,'an inserted branch paragraph must have an addressable identifier');
            await commit();return id;
        };
        const visible=async(expected,known=branchIds)=>{
            const ids=await page.evaluate(()=>pages.flatMap(page=>page.hits.map(hit=>hit.id)));
            const matches=Object.entries(known).filter(([,id])=>ids.includes(id)).map(([name])=>name).sort();
            assert.deepEqual(matches,[...expected].sort(),'only the matching ordered branch may be laid out');
            for(const name of expected)assert.equal(ids.filter(id=>id===known[name]).length,1,name+' must appear exactly once');
            assert.ok(ids.includes('always'),'independent document content must remain visible');
        };
        const screenshot=async name=>{
            await page.waitForFunction(()=>[...document.querySelectorAll('.canvas-sheet')].every(sheet=>{const image=sheet.querySelector('.page-image');return image?.dataset.preview===previewId&&image.complete&&image.naturalWidth>0;}));
            await page.screenshot({path:path.join(output,name)});
        };
        const exportDocument=async(format,name)=>{
            await idle();await page.locator('#export').click();await page.locator('#format').selectOption(format);
            const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#download-export').click()]);
            await download.saveAs(path.join(output,name));await page.locator('#close-export').click();
        };
        const check=async(name,work)=>{if(scenarioFilter&&!scenarioFilter.test(name))return;return t.test(name,async()=>{
            try{await work();results.push({name,passed:true});}
            catch(error){
                results.push({name,passed:false,error:String(error)});
                const evidence='failure-'+String(results.length).padStart(2,'0');
                await writeFile(path.join(output,evidence+'-state.json'),JSON.stringify(await page.evaluate(()=>{
                    const snapshot=DesignerStudioBridge.snapshot();
                    return {source:state.source,data:state.data,rawSource:$('source').value,rawData:$('data').value,selection:selected,layout:snapshot.layoutState,currentIdentity:{source:Editor.pageDraftSource(snapshot.source),data:$('data').value,resourceIdentity:state.resourceIdentity},pending:snapshot.pending,propertyDirty:typeof propertyDirty==='undefined'?undefined:propertyDirty,nodeXmlDirty:typeof nodeXmlDirty==='undefined'?undefined:nodeXmlDirty,hits:pages.flatMap(page=>page.hits),status:$('status').textContent};
                }),null,2));
                await page.screenshot({path:path.join(output,evidence+'.png')});throw error;
            }
        });};

        // Canvas prose defers PDF measurement in the convenience mode; this suite accepts exact output.
        await page.getByRole('button',{name:'精确排版',exact:true}).click();await idle();

        await check('a Boolean condition becomes three ordered numeric cases without writing XML',async()=>{
            await load();await select('if');await predicate(0,{field:'status',operator:'EQ',value:'1'});await commit();
            for(const [index,marker] of [[1,'BRANCH_TWO'],[2,'BRANCH_THREE']]){
                await select('if');await inspector.getByRole('button',{name:'添加条件分支',exact:true}).click();await idle();
                assert.equal(await inspector.locator('[data-condition-case]').count(),index+1);
                await predicate(index,{field:'status',operator:'EQ',value:String(index+1)});await commit();
                branchIds[marker]=await addParagraph(index,marker);
            }
            await select('if');assert.equal(await inspector.locator('[data-condition-case]').count(),3);
            assert.equal(await inspector.locator('#selection-crumb').count(),1);
            assert.equal(await page.locator('dialog[open]').count(),0);
            constructed=await page.evaluate(()=>state.source);
            assert.deepEqual(await page.evaluate(()=>{
                const doc=new DOMParser().parseFromString(state.source,'application/xml'),one=doc.querySelector('document > if'),two=one.querySelector(':scope > else > if'),three=two.querySelector(':scope > else > if');
                return {values:[one,two,three].map(node=>[node.getAttribute('test'),node.getAttribute('operator'),node.getAttribute('value')]),other:three.querySelector(':scope > else').textContent,style:doc.querySelector('#branch-one > span').getAttribute('role')};
            }),{values:[['status','EQ','1'],['status','EQ','2'],['status','EQ','3']],other:'BRANCH_OTHER',style:'bold'});
            await visible(['BRANCH_ONE']);await screenshot('three-case-inspector.png');
            await writeFile(path.join(output,'constructed.xml'),constructed);
        });
        await check('each numeric case and other produce exclusive previews and real export files',async()=>{
            assert.ok(constructed,'the UI-built chain is required for export acceptance');
            const evidence=[];
            for(const [status,marker] of [[1,'BRANCH_ONE'],[2,'BRANCH_TWO'],[3,'BRANCH_THREE'],[99,'BRANCH_OTHER']]){
                await setData(data(status));await visible([marker]);
                evidence.push({status,marker,hits:await page.evaluate(()=>pages.flatMap(page=>page.hits.map(hit=>hit.id)))});
                await exportDocument('PDF',`status-${status}.pdf`);
                if(status===2)await exportDocument('DOCX','status-2.docx');
            }
            await writeFile(path.join(output,'branch-previews.json'),JSON.stringify(evidence,null,2));
            await page.locator('#save').click();await page.locator('#template-export-dialog').waitFor();
            const [bundle]=await Promise.all([page.waitForEvent('download'),page.locator('#download-template').click()]);
            const saved=path.join(output,'three-case-template.zip');await bundle.saveAs(saved);await page.locator('#close-template-export').click();
            await load();await page.locator('#open-file').setInputFiles(saved);await page.waitForFunction(()=>!busy&&state.data==='{}');
            assert.equal(await page.evaluate(()=>state.source),constructed,'saving and reopening must preserve the complete chain and inline formatting');
            await setData(data(3));await visible(['BRANCH_THREE']);await select('if');
            assert.equal(await inspector.locator('[data-condition-case]').count(),3);
            await screenshot('reopened-three-case.png');
        });
        await check('field operator and exact numeric comparison drafts remain editable and undo atomically',async()=>{
            assert.ok(constructed);await load(constructed);await select('if');
            await predicate(0,{field:'alternative',operator:'GE',value:'2'});await commit();await visible(['BRANCH_ONE']);
            assert.deepEqual(await page.evaluate(()=>{
                const node=new DOMParser().parseFromString(state.source,'application/xml').querySelector('document > if');
                return [node.getAttribute('test'),node.getAttribute('operator'),node.getAttribute('value')];
            }),['alternative','GE','2']);
            await setData(data(2));await visible(['BRANCH_ONE']);
            await setData('{"status":3,"alternative":1,"enabled":true,"exact":9007199254740993}');await visible(['BRANCH_THREE']);
            const multiline='line one\nline two',textData='{"status":99,"alternative":3,"enabled":true,"category":"line one\\nline two","exact":9007199254740993}';
            await load(constructed,textData);await select('if');await predicate(0,{field:'category',operator:'EQ',value:multiline});await commit();await visible(['BRANCH_ONE']);
            await select('if');assert.equal(await editor().getByLabel('比较值',{exact:true}).inputValue(),multiline);
            await inspector.getByRole('button',{name:'添加条件分支',exact:true}).click();await idle();
            assert.equal(await editor().getByLabel('比较值',{exact:true}).inputValue(),multiline,'adding another case must preserve an untouched multiline comparison');
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('document > if').getAttribute('value')),multiline);
            await visible(['BRANCH_ONE']);await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            await setData(textData.replace('line one','LINE one'));await visible(['BRANCH_OTHER']);
            await load(constructed);await select('if');
            await predicate(0,{field:'status',operator:'EQ',value:'9007199254740993'});
            assert.equal(await editor().getByRole('button',{name:'更新测试值',exact:true}).count(),0);
            await previewAfter(()=>editor().getByLabel('条件测试值',{exact:true}).fill('9007199254740993'));await visible(['BRANCH_ONE']);
            assert.equal(await page.evaluate(()=>state.data),data(1),'live comparison previews must keep test data in one editing draft');
            await commit();
            assert.equal(await page.evaluate(()=>state.data),data('9007199254740993'));await visible(['BRANCH_ONE']);
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>state.source),constructed);assert.equal(await page.evaluate(()=>state.data),data(1));
            await select('if');await predicate(0,{field:'status',operator:'EQ',value:'9007199254740993'});await commit();
            await setData(data('9007199254740992'));await visible(['BRANCH_OTHER']);
            await setData(data('9007199254740993'));await visible(['BRANCH_ONE']);
        });
        await check('deleting a later case preserves following cases and other while protecting the first case',async()=>{
            await load(constructed);await select('if');
            assert.equal(await inspector.getByRole('button',{name:'删除第 1 分支',exact:true}).isDisabled(),true);
            await inspector.getByRole('button',{name:'删除第 2 分支',exact:true}).click();await idle();
            assert.equal(await inspector.locator('[data-condition-case]').count(),2);
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelectorAll('if').length),2);
            await setData(data(2));await visible(['BRANCH_OTHER']);await setData(data(3));await visible(['BRANCH_THREE']);
            // Restore the deletion before changing test data, so it is one history operation.
            await load(constructed);await select('if');await inspector.getByRole('button',{name:'删除第 2 分支',exact:true}).click();await idle();
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();assert.equal(await page.evaluate(()=>state.source),constructed);
            await select('if');await editor().getByLabel('比较值',{exact:true}).fill('42');await pauseDraft();
            await inspector.getByRole('button',{name:'添加条件分支',exact:true}).click();await idle();
            assert.equal(await inspector.locator('[data-condition-case]').count(),4);
            assert.equal(await editor().getByLabel('比较值',{exact:true}).inputValue(),'42','structural editing must include pending comparison changes');
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();
            assert.equal(await page.evaluate(()=>state.source),constructed,'one undo must restore both the pending comparison and added case');
            await select('if');
            for(const prefix of ['<p id="pending-prefix">PENDING SOURCE</p>','<if test="enabled"><then><p id="pending-condition">PENDING CONDITION</p></then></if>']){
                const pending=constructed.replace('<if ',prefix+'<if ');
                await page.evaluate(value=>{$('source').value=value;},pending);
                await inspector.getByRole('button',{name:'添加条件分支',exact:true}).click();
                await editor().locator('.condition-editor-error').waitFor({state:'visible'});
                assert.match(await editor().locator('.condition-editor-error').innerText(),/源码.*未应用/);
                assert.equal(await page.evaluate(()=>state.source),constructed,'a stale selected path must not mutate the applied document even when its replacement has the same tag');
                assert.equal(await page.evaluate(()=>$('source').value),pending,'rejecting stale navigation must retain the unapplied source draft');
                const selectedPath=await page.evaluate(()=>selected.path);
                await card(0).locator('[data-content-tag="then"]').first().click();
                await inspector.locator('[data-content-navigation] .content-navigation-error').waitFor({state:'visible'});
                assert.match(await inspector.locator('[data-content-navigation] .content-navigation-error').innerText(),/源码.*未应用/);
                assert.equal(await page.evaluate(()=>selected.path),selectedPath,'stale source edits must not navigate into the replacement condition');
                assert.equal(await page.evaluate(()=>state.source),constructed);
                assert.equal(await page.evaluate(()=>$('source').value),pending);
                await page.evaluate(value=>{$('source').value=value;},constructed);await idle();
            }
        });
        await check('nested conditions remain in their parent case instead of becoming sibling cases',async()=>{
            await load(constructed);await select('if');await card(0).getByRole('button',{name:'添加下级条件',exact:true}).click();await idle();
            const nestedPath=await page.evaluate(()=>selected.path);assert.notEqual(nestedPath,await page.evaluate(()=>entries.find(entry=>entry.tag==='if').path));
            await predicate(0,{field:'enabled',operator:'TRUE'});await commit();
            await page.evaluate(path=>document.dispatchEvent(new CustomEvent('designer-template-select',{detail:{path}})),nestedPath);await idle();
            await card(0).locator('[data-content-tag="then"]').first().click();await idle();
            await inspector.locator('[data-content-navigation]').getByRole('button',{name:'在此添加内容',exact:true}).click();await page.getByRole('button',{name:'文字段落',exact:true}).click();await idle();
            await writeBody('NESTED');const nestedId=await page.evaluate(()=>selected.id);await commit();
            await select('if');assert.equal(await inspector.locator('[data-condition-case]').count(),3);
            const known={...branchIds,NESTED:nestedId};await setData(data(1));await visible(['BRANCH_ONE','NESTED'],known);
            await setData('{"status":1,"alternative":3,"enabled":false,"exact":9007199254740993}');await visible(['BRANCH_ONE'],known);
            assert.equal(await page.evaluate(()=>new DOMParser().parseFromString(state.source,'application/xml').querySelector('document > if > then > if').getAttribute('test')),'enabled');
        });
        await check('array aliases and original Boolean conditions retain their lexical behavior',async()=>{
            const alias='<document version="1"><p id="always">ALWAYS</p><each items="records" as="item"><if test="item.status" operator="EQ" value="2"><then><p id="alias-two">ALIAS_TWO</p></then><else><p id="alias-other">ALIAS_OTHER</p></else></if></each></document>';
            await load(alias,'{"records":[{"status":2}],"exact":9007199254740993}');await select('if');
            assert.equal(await editor().getByLabel('条件数据路径',{exact:true}).inputValue(),'item.status');
            assert.match(await inspector.locator('[data-binding-help]').innerText(),/item/);
            await visible(['ALIAS_TWO'],{ALIAS_TWO:'alias-two@0',ALIAS_OTHER:'alias-other@0'});
            await predicate(0,{field:'item.status',operator:'EQ',value:'3'});await commit();await visible(['ALIAS_OTHER'],{ALIAS_TWO:'alias-two@0',ALIAS_OTHER:'alias-other@0'});
            assert.equal(await page.evaluate(()=>state.data),'{"records":[{"status":2}],"exact":9007199254740993}');
            await load();await select('if');assert.equal(await editor().getByLabel('比较方式',{exact:true}).inputValue(),'TRUE');
            assert.equal(await editor().getByRole('button',{name:'更新测试值',exact:true}).count(),0);
            await previewAfter(()=>editor().getByLabel('条件测试值',{exact:true}).selectOption('false'));await visible(['BRANCH_OTHER']);
            await page.getByRole('button',{name:'撤销',exact:true}).click();await idle();await visible(['BRANCH_ONE']);
        });
        assert.ok(results.length,'No browser scenarios matched EXPORT4J_CONDITION_SCENARIOS');assert.deepEqual(errors,[]);await writeFile(path.join(output,'page-errors.json'),JSON.stringify(errors));
    }finally{
        await writeFile(path.join(output,'results.json'),JSON.stringify(results,null,2));await writeFile(path.join(output,'server.log'),logs);
        await browser?.close();if(!child.killed)child.kill();
    }
});
