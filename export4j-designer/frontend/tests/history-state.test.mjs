// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright';

// These regressions require executing the bridge queue and observing identities; type checks cannot detect them.
test('bridge history preserves resource operations and reports the current preview identity', {timeout:60000}, async t=>{
    const source=await readFile(new URL('../../src/main/resources/designer/web/studio-bridge.js',import.meta.url),'utf8');
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try {
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();
        await page.setContent('<textarea id="source"></textarea><textarea id="data"></textarea><div id="preview-status"></div>');
        await page.addScriptTag({content:`
            const $=id=>document.getElementById(id);
            let state,selected,selectedPaths=[],entries=[],pages=[],currentPage=0,busy=false,propertyDirty=false,nodeXmlDirty=false;
            let previewId='',previewTimer,previewGeneration=0,previewFormats={},calls=[],server,projects=[],serverCursor=0,rejectRestore=false,pageSourceOverride;
            const DesignerStudio={sync(){},shouldPreview(){return true;}};
            const Editor={pageDraftSource:source=>pageSourceOverride??source};
            const sameText=(left,right)=>left.replace(/\\r\\n?/g,'\\n')===right.replace(/\\r\\n?/g,'\\n');
            const sourceChanged=()=>!sameText($('source').value,state.source),dataChanged=()=>!sameText($('data').value,state.data);
            const draftSource=()=>$('source').value;
            function inspect(){} function markDirty(){} function refreshButtons(){} function status(){} function diagnostics(){} function schedulePreview(){}
            async function run(work){if(busy)return;busy=true;try{await work();}finally{busy=false;}}
            function outline(source){const walk=(node,path='')=>({path,id:node.getAttribute('id')||'',tag:node.tagName,label:node.tagName,children:[...node.children].map((child,index)=>walk(child,path+'/'+index))});return walk(new DOMParser().parseFromString(source,'application/xml').documentElement);}
            function response(project){return {...project,outline:outline(project.source),revision:server?.revision||1,canUndo:serverCursor>0,canRedo:serverCursor<projects.length-1,historyResources:[...new Set(projects.map(value=>value.resourceIdentity))]};}
            function install(value,changed=true,options={}){DesignerStudioBridge.accepted(value,options);state=value;$('source').value=value.source;$('data').value=value.data;entries=[];const visit=node=>{entries.push(node);node.children.forEach(visit);};visit(value.outline);selectedPaths=entries.some(entry=>entry.path==='/0')?['/0']:[];selected=entries.find(entry=>entry.path===selectedPaths[0]);}
            async function request(path,payload){
                calls.push({path,...payload});
                if(path==='apply'&&payload.source.includes('BAD_COMPILE'))throw new Error('compile rejected');
                if(path==='restore'){
                    if(rejectRestore){rejectRestore=false;throw new Error('resource unavailable');}
                    const exact=projects.findIndex(value=>value.source===payload.source&&value.data===payload.data&&value.resourceIdentity===payload.resourceIdentity);
                    if(exact>=0)serverCursor=exact;
                    else {if(!projects.some(value=>value.resourceIdentity===payload.resourceIdentity))throw new Error('resource unavailable');projects[serverCursor]={...projects[serverCursor],...payload};}
                }else if(path==='apply'||path==='resource'){
                    projects=projects.slice(0,serverCursor+1);projects.push({...projects[serverCursor],...payload});serverCursor++;
                    if(projects.length>9){projects.shift();serverCursor--;}
                }else throw new Error('unexpected request '+path);
                server={...response(projects[serverCursor]),revision:(server?.revision||0)+1};return {...server};
            }
            const template=text=>'<document version="1"><p id="text">'+text+'</p></document>';
            async function reset(){await DesignerStudioBridge.settled();projects=[{source:template('Original'),data:'{"name":"Ada"}',resourceIdentity:'r0'}];serverCursor=0;server={...response(projects[0]),revision:(server?.revision||0)+1};calls=[];rejectRestore=false;pageSourceOverride=undefined;install({...server},true,{session:true});ack();}
            function currentIdentity(){return {source:$('source').value,data:$('data').value,resourceIdentity:state.resourceIdentity};}
            function ack(){DesignerStudioBridge.previewed($('source').value,currentIdentity());}
            async function resource(id){install(await request('resource',{source:state.source,data:state.data,resourceIdentity:id}));ack();}
            async function edit(text){await DesignerStudioBridge.apply(template(text));await DesignerStudioBridge.flush();ack();}
            async function step(direction){await DesignerStudioBridge.history(direction);await DesignerStudioBridge.settled();ack();return view();}
            function view(){const snap=DesignerStudioBridge.snapshot();return {source:state.source,data:state.data,resource:state.resourceIdentity,serverSource:server.source,serverResource:server.resourceIdentity,canUndo:snap.canUndo,canRedo:snap.canRedo,layoutState:snap.layoutState,pending:snap.pending,boundary:snap.historyBoundary};}
        `});
        await page.addScriptTag({content:source});

        await t.test('resource import followed by text undoes twice and redoes twice without replay apply snapshots',async()=>{
            const result=await page.evaluate(async()=>{await reset();await resource('r1');await edit('Edited');const before=view(),undoText=await step('undo'),undoResource=await step('undo'),redoResource=await step('redo'),redoText=await step('redo');return {before,undoText,undoResource,redoResource,redoText,requests:calls.map(call=>call.path)};});
            assert.match(result.before.source,/Edited/);
            assert.match(result.undoText.source,/Original/);assert.equal(result.undoText.resource,'r1');
            assert.equal(result.undoResource.resource,'r0');assert.equal(result.undoResource.canUndo,false);
            assert.equal(result.redoResource.resource,'r1');assert.match(result.redoResource.source,/Original/);
            assert.match(result.redoText.source,/Edited/);assert.equal(result.redoText.canRedo,false);
            for(const value of [result.undoText,result.undoResource,result.redoResource,result.redoText]){assert.equal(value.serverSource,value.source);assert.equal(value.serverResource,value.resource);}
            assert.deepEqual(result.requests,['resource','apply','restore','restore','restore','restore']);
        });
        await t.test('two resource identities retain their ordered text states across repeated undo and redo',async()=>{
            const states=await page.evaluate(async()=>{await reset();await resource('r1');await edit('First');await resource('r2');await edit('Second');const result=[];for(let round=0;round<2;round++){for(let index=0;index<4;index++)result.push(await step('undo'));for(let index=0;index<4;index++)result.push(await step('redo'));}return result.map(value=>[new DOMParser().parseFromString(value.source,'application/xml').querySelector('p').textContent,value.resource,value.serverResource]);});
            const cycle=[['First','r2','r2'],['First','r1','r1'],['Original','r1','r1'],['Original','r0','r0'],['Original','r1','r1'],['First','r1','r1'],['First','r2','r2'],['Second','r2','r2']];
            assert.deepEqual(states,[...cycle,...cycle]);
        });
        await t.test('a rejected resource restore leaves the logical cursor and current document unchanged',async()=>{
            const result=await page.evaluate(async()=>{await reset();await resource('r1');rejectRestore=true;const before=view();let message;try{await step('undo');}catch(error){message=error.message;}return {before,after:view(),message,then:await step('undo')};});
            assert.equal(result.message,'resource unavailable');assert.deepEqual(result.after,result.before);assert.equal(result.then.resource,'r0');
        });
        await t.test('evicted resource snapshots stop at an explained history boundary',async()=>{
            const result=await page.evaluate(async()=>{await reset();await resource('r1');for(let index=0;index<9;index++)await edit('Text '+index);for(let index=0;index<9;index++)await step('undo');return view();});
            assert.equal(result.resource,'r1');assert.match(result.source,/Original/);assert.equal(result.canUndo,false);assert.match(result.boundary,/资源.*保留范围/);
        });
        await t.test('failed preview is distinct from pending and matching source alone never proves synchronization',async()=>{
            const result=await page.evaluate(async()=>{await reset();const initial=view();$('data').value='{}';const stale=view(),failedIdentity=currentIdentity();DesignerStudioBridge.previewing(failedIdentity);const pending=view();DesignerStudioBridge.previewFailed(failedIdentity);const failed=view();$('data').value='{"name":"Grace"}';const repaired=view();DesignerStudioBridge.previewing(currentIdentity());ack();const recovered=view();return {initial,stale,pending,failed,repaired,recovered};});
            assert.equal(result.initial.layoutState,'synced');assert.equal(result.stale.layoutState,'stale');
            assert.equal(result.pending.layoutState,'pending');assert.equal(result.failed.pending,false);assert.equal(result.failed.layoutState,'failed');
            assert.equal(result.repaired.layoutState,'stale');assert.equal(result.recovered.layoutState,'synced');
        });
        await t.test('stale source, data and resource preview identities cannot clear the current pending state',async()=>{
            const result=await page.evaluate(async()=>{await reset();const old=currentIdentity();await resource('r1');DesignerStudioBridge.previewing(currentIdentity());const accepted=[DesignerStudioBridge.previewed(old.source,old),DesignerStudioBridge.previewFailed(old)];const current=view();ack();return {accepted,current,final:view()};});
            assert.deepEqual(result.accepted,[false,false]);assert.equal(result.current.layoutState,'pending');assert.equal(result.final.layoutState,'synced');
        });
        await t.test('compile rejection reports failure and successful correction still waits for preview',async()=>{
            const result=await page.evaluate(async()=>{await reset();await DesignerStudioBridge.apply(template('BAD_COMPILE'));await DesignerStudioBridge.settled();const failed=view();await DesignerStudioBridge.apply(template('Corrected'));await DesignerStudioBridge.settled();const pending=view();ack();return {failed,pending,final:view()};});
            assert.equal(result.failed.layoutState,'failed');assert.equal(result.failed.pending,false);assert.equal(result.pending.layoutState,'pending');assert.equal(result.final.layoutState,'synced');
        });
        await t.test('page-setting drafts are synchronized only with the actual submitted preview source',async()=>{
            const result=await page.evaluate(async()=>{await reset();pageSourceOverride=template('Page draft');const stale=view(),submitted={...currentIdentity(),source:pageSourceOverride};DesignerStudioBridge.previewing(submitted);const pending=view(),accepted=DesignerStudioBridge.previewed(state.source,submitted),synced=view();pageSourceOverride=undefined;return {stale,pending,accepted,synced,closed:view()};});
            assert.equal(result.stale.layoutState,'stale');assert.equal(result.pending.layoutState,'pending');assert.equal(result.accepted,true);assert.equal(result.synced.layoutState,'synced');assert.equal(result.closed.layoutState,'stale');
        });
    }finally{await browser.close();}
});
