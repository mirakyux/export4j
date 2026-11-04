// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('variable metadata follows template lexical scopes without exposing values', {timeout:60000}, async t=>{
    const bundle=await build({
        entryPoints:[fileURLToPath(new URL('../src/bindings.ts',import.meta.url))],
        bundle:true,format:'iife',globalName:'Bindings',write:false,target:'es2022'
    });
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try {
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();await page.addScriptTag({content:bundle.outputFiles[0].text});
        const names=await page.evaluate(()=>{
            const cases=new Map(),check=(name,work)=>cases.set(name,work);
            const equal=(actual,expected,label='value')=>{
                if(JSON.stringify(actual)!==JSON.stringify(expected))throw new Error(label+': expected '+JSON.stringify(expected)+', actual '+JSON.stringify(actual));
            };
            const assert=(condition,label)=>{if(!condition)throw new Error(label);};
            const refuses=(work,label)=>{let error;try{work();}catch(value){error=value;}assert(error instanceof Error,label+' must reject');};
            const fields=result=>Object.fromEntries(result.fields.map(field=>[field.path,field.type]));
            const source=body=>'<document version="1">'+body+'</document>';
            const read=(xml,path,data)=>Bindings.bindingContext({source:xml,data:JSON.stringify(data)},path);
            const list='<list items="items" as="item"><bullet id="b">${item.name}</bullet></list>';
            const rows='<rows items="records" as="record"><columns><column weight="1"/></columns><header><cell>${title}</cell></header><row><cell>${record.code}</cell></row></rows>';

            check('root fields expose valid paths and types with no test-data values',()=>{
                const data={title:'CONFIDENTIAL_TITLE_91',enabled:true,total:3,customer:{name:'CONFIDENTIAL_NAME_72',records:[{code:'PRIVATE_CODE'}]},'invalid-key':'HIDDEN',['x'.repeat(257)]:'HIDDEN_LONG_KEY'};
                const result=read(source('<p/>'),'/0',data);
                equal(result.scopes,[{kind:'root',path:''}]);
                equal(fields(result),{'customer':'object','customer.name':'string','customer.records':'array','enabled':'boolean','title':'string','total':'number'});
                const serialized=JSON.stringify(result);
                for(const value of ['CONFIDENTIAL_TITLE_91','CONFIDENTIAL_NAME_72','PRIVATE_CODE','HIDDEN'])assert(!serialized.includes(value),'must not expose '+value);
            });

            check('standalone bullet uses root fields and never invents an item alias',()=>{
                const result=read(source('<bullet>${title}</bullet>'),'/0',{title:'Title',items:[{name:'A'}]});
                equal(result.scopes,[{kind:'root',path:''}]);
                equal(fields(result),{items:'array',title:'string'});
            });

            check('data-list prototype has its alias and merges heterogeneous field metadata',()=>{
                const result=read(source(list),'/0/0',{title:'Title',items:[{name:'A',score:1},{name:'B',score:'unknown',extra:true}]});
                equal(result.scopes,[{kind:'root',path:''},{kind:'list',path:'/0',items:'items',alias:'item'}]);
                equal(fields(result),{'item':'object','item.extra':'boolean','item.name':'string','item.score':'mixed','items':'array','title':'string'});
            });

            check('list and rows control attributes only see the external scope',()=>{
                for(const xml of [source(list),source(rows)]){
                    const result=read(xml,'/0',{items:[{name:'A'}],records:[{code:'X'}]});
                    equal(result.scopes,[{kind:'root',path:''}]);
                    equal(fields(result),{items:'array',records:'array'});
                }
            });

            check('dynamic table headers cannot see the current row alias',()=>{
                const result=read(source(rows),'/0/1/0',{title:'Title',records:[{code:'X'}]});
                equal(result.scopes,[{kind:'root',path:''}]);
                equal(fields(result),{records:'array',title:'string'});
                assert(result.hint.includes('表头'),'header explanation');
            });

            check('dynamic table row prototype sees its own alias and root fields',()=>{
                const result=read(source(rows),'/0/2/0',{title:'Title',records:[{code:'X'}]});
                equal(result.scopes,[{kind:'root',path:''},{kind:'rows',path:'/0',items:'records',alias:'record'}]);
                equal(fields(result),{'record':'object','record.code':'string','records':'array','title':'string'});
            });

            check('nested rows retain outer each scope in headers and add row scope only in the body',()=>{
                const xml=source('<each items="customers" as="customer">'+rows.replace('items="records"','items="customer.records"')+'</each>');
                const data={title:'Title',customers:[{name:'A',records:[{code:'X'}]},{name:'B',records:[{code:'Y',flag:true}]}]};
                const header=read(xml,'/0/0/1/0',data),body=read(xml,'/0/0/2/0',data);
                equal(header.scopes.map(scope=>scope.kind),['root','each']);
                assert(fields(header)['customer.name']==='string','outer alias in header');
                assert(!Object.hasOwn(fields(header),'record'),'no row alias in header');
                equal(body.scopes.map(scope=>scope.kind),['root','each','rows']);
                equal(fields(body)['record.code'],'string');equal(fields(body)['record.flag'],'boolean');
                equal(fields(body)['customer.records'],'array');
            });

            check('inner aliases shadow the entire root and outer declaration',()=>{
                const xml=source('<each items="groups" as="item"><list items="item.children" as="item"><bullet>${item.name}</bullet></list></each>');
                const result=read(xml,'/0/0/0',{item:{rootOnly:'Root'},groups:[{outerOnly:'Outer',children:[{name:'Inner'}]}]});
                equal(result.scopes.map(scope=>[scope.kind,scope.alias]),[['root',undefined],['each','item'],['list','item']]);
                equal(fields(result),{'groups':'array','item':'object','item.name':'string'});
            });

            check('static tables, conditions and grid items add no data aliases',()=>{
                const xml=source('<if test="show"><then><grid><item row="1" column-start="1" column-span="12"><table><columns><column weight="1"/></columns><row><cell>${title}</cell></row></table></item></grid></then></if>');
                const result=read(xml,'/0/0/0/0/0/1/0',{show:true,title:'Title'});
                equal(result.scopes,[{kind:'root',path:''}]);equal(fields(result),{show:'boolean',title:'string'});
            });

            check('empty, missing and scalar iteration data keep manual alias entry available',()=>{
                for(const data of [{items:[]},{},{items:'wrong type'}]){
                    const result=read(source(list),'/0/0',data);
                    equal(fields(result).item,'unknown');assert(result.hint.includes('手写'),'manual path hint');
                    equal(result.scopes[1].alias,'item');
                }
            });

            check('invalid JSON and invalid roots do not suppress known lexical scope metadata',()=>{
                for(const data of ['{broken','[]','null','true',undefined]){
                    const result=Bindings.bindingContext({source:source(list),data},'/0/0');
                    equal(result.scopes[1],{kind:'list',path:'/0',items:'items',alias:'item'});
                    equal(fields(result),{item:'unknown'});assert(result.hint.includes('JSON'),'invalid JSON explanation');
                }
            });

            check('literal text mode warns without hiding array paths needed by loop controls',()=>{
                const result=read(source(list).replace('version="1"','version="1" literal-text="true"'),'/0/0',{items:[{name:'A'}]});
                assert(result.hint.includes('不会绑定'),'literal mode explanation');equal(fields(result).items,'array');
            });

            check('declarations, malformed XML and stale source paths refuse before data scanning',()=>{
                refuses(()=>read('<!DOCTYPE document SYSTEM "https://invalid.example/dtd"><document/>','',{}),'external doctype');
                refuses(()=>read('<document><!ENTITY x "a"></document>','',{}),'entity declaration');
                refuses(()=>read('<document><p></document>','',{}),'malformed XML');
                refuses(()=>read(source('<p/>'),'/99',{}),'stale path');
                refuses(()=>read(source('<p/>'),'/0/../0',{}),'non-source path');
            });

            check('field, sample, depth and XML budgets are finite and explain incomplete suggestions',()=>{
                const many=Object.fromEntries(Array.from({length:1100},(_,index)=>['field'+index,index]));
                const limited=read(source('<p/>'),'/0',many);
                assert(limited.fields.length<=1000,'field count bound');assert(limited.hint.includes('预算'),'field bound explanation');
                const items=Array.from({length:201},(_,index)=>index===200?{unscanned:true}:{known:true});
                const sampled=read(source(list),'/0/0',{items});
                equal(fields(sampled)['item.known'],'boolean');assert(!Object.hasOwn(fields(sampled),'item.unscanned'),'array sampling bound');
                assert(sampled.hint.includes('预算'),'sample bound explanation');
                let deep={leaf:'Hidden'};for(let depth=0;depth<24;depth++)deep={child:deep};
                assert(read(source('<p/>'),'/0',{deep}).hint.includes('预算'),'depth bound explanation');
                refuses(()=>read(source('<p/>'.repeat(20001)),'/0',{}),'XML element budget');
            });

            check('null and non-finite test values are metadata only and report unsupported binding data',()=>{
                const result=Bindings.bindingContext({source:source('<p/>'),data:'{"empty":null,"huge":1e999}'},'/0');
                equal(fields(result),{empty:'null',huge:'unsupported'});assert(result.hint.includes('不受支持'),'unsupported data explanation');
            });

            check('own prototype-named keys remain ordinary paths without altering objects',()=>{
                const result=Bindings.bindingContext({source:source('<p/>'),data:'{"__proto__":{"name":"PRIVATE"},"constructor":"VALUE"}'},'/0');
                equal(fields(result),JSON.parse('{"__proto__":"object","__proto__.name":"string","constructor":"string"}'));
                equal(Object.prototype.name,undefined);assert(!JSON.stringify(result).includes('PRIVATE'),'own-key values stay private');
            });
            globalThis.BindingChecks=cases;return [...cases.keys()];
        });
        for(const name of names)await t.test(name,{timeout:10000},()=>page.evaluate(name=>globalThis.BindingChecks.get(name)(),name));
    } finally {
        await browser.close();
    }
});

test('sample arrays preserve exact JSON tokens through list and rows binding dialogs', {timeout:30000}, async()=>{
    const [script,index,bundle]=await Promise.all([
        readFile(new URL('../../src/main/resources/designer/web/bindings.js',import.meta.url),'utf8'),
        readFile(new URL('../../src/main/resources/designer/web/index.html',import.meta.url),'utf8'),
        build({entryPoints:[fileURLToPath(new URL('../src/bindings.ts',import.meta.url))],bundle:true,format:'iife',globalName:'Bindings',write:false,target:'es2022'})
    ]);
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try {
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();
        const dialog=index.match(/<dialog id="binding-dialog"[\s\S]*?<\/dialog>/)?.[0];
        if(!dialog)throw new Error('binding dialog must exist');
        await page.setContent('<textarea id="data"></textarea>'+dialog);
        await page.addScriptTag({content:bundle.outputFiles[0].text});
        await page.addScriptTag({content:`
            window.$=id=>document.getElementById(id);
            window.DesignerStudio={bindingContext:Bindings.bindingContext};
            window.bindingDraft={source:'<document version="1"><p>kept</p></document>',data:'{}'};
            window.DesignerStudioBridge={snapshot:()=>window.bindingDraft};
        `});
        await page.addScriptTag({content:script});
        await page.evaluate(async()=>{
            const assert=(condition,label)=>{if(!condition)throw new Error(label);};
            const exact='{"invoiceId":9007199254740993,"negative":-9007199254740993,"decimal":0.10000000000000000001,"exponent":1.2300e+9999,"nested":{"value":9007199254740993123456789},"escaped":"} \\u4e2d \\\""} \r\n';
            const originalMembers=exact.slice(0,exact.lastIndexOf('}'));
            const candidate=DesignerBindings.listFallback(exact);
            assert(candidate.data.startsWith(originalMembers),'existing member tokens must remain verbatim');
            assert(candidate.data.endsWith('} \r\n'),'trailing object whitespace must remain verbatim');
            assert(JSON.parse(candidate.data).items[0].name==='示例项','sample array must be valid JSON');

            for(const kind of ['list','rows']){
                bindingDraft.data=exact;$('data').value=exact;
                const editorOriginal=$('data').value;
                const confirming=DesignerBindings.pickSource(kind,'',{fallback:DesignerBindings.listFallback(exact)});
                assert($('binding-path').value==='items',kind+' default array');
                assert($('binding-template-field').value===(kind==='rows'?'record.name':'item.name'),kind+' initial field');
                $('binding-confirm').click();
                const result=await confirming;
                assert(result.data===candidate.data,kind+' confirmation must preserve all original tokens');
                assert(bindingDraft.data===exact&&$('data').value===editorOriginal,kind+' dialog must not commit test data');
                const cancelling=DesignerBindings.pickSource(kind,'',{fallback:DesignerBindings.listFallback(exact)});
                $('binding-cancel').click();
                assert(await cancelling===null,kind+' cancellation must discard the sample candidate');
                assert(bindingDraft.data===exact&&$('data').value===editorOriginal,kind+' cancellation must retain original test data');
            }

            const collision=DesignerBindings.listFallback('{"items":9007199254740993,"items2":true}');
            assert(collision.path==='items3','existing scalar keys must not be overwritten');
            assert(collision.data.includes('"items":9007199254740993'),'colliding scalar token must remain precise');
            for(const source of ['{}',' { \r\n\t } \n']){
                assert(JSON.parse(DesignerBindings.listFallback(source).data).items[0].name==='示例项','empty object must accept the array');
            }
            const existing=DesignerBindings.listFallback('{"invoiceId":9007199254740993,"records":[{"name":"Known"}]}');
            assert(existing.path==='records'&&!Object.hasOwn(existing,'data'),'existing array must leave data unchanged');
            for(const source of ['[]','null','{"broken":','{"padding":"'+'x'.repeat(999_986)+'"}']){
                let failure;try{DesignerBindings.listFallback(source);}catch(error){failure=error;}
                assert(failure instanceof Error,'invalid or over-budget sample candidate must fail');
            }
        });
    } finally {
        await browser.close();
    }
});

test('inline field browsing filters lexical scopes and searches beyond the rendered list', {timeout:30000}, async()=>{
    const [script,bundle]=await Promise.all([
        readFile(new URL('../../src/main/resources/designer/web/bindings.js',import.meta.url),'utf8'),
        build({entryPoints:[fileURLToPath(new URL('../src/bindings.ts',import.meta.url))],bundle:true,format:'iife',globalName:'Bindings',write:false,target:'es2022'})
    ]);
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try{
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();
        await page.setContent('<textarea id="data"></textarea><section id="inline-fields"></section>');
        await page.addScriptTag({content:bundle.outputFiles[0].text});
        await page.addScriptTag({content:`
            window.$=id=>document.getElementById(id);
            window.DesignerStudio={bindingContext:Bindings.bindingContext};
            window.bindingDraft={source:'',data:''};
            window.DesignerStudioBridge={snapshot:()=>window.bindingDraft};
        `});
        await page.addScriptTag({content:script});
        const original=await page.evaluate(()=>{
            bindingDraft.source='<document version="1"><each items="groups" as="item"><if test="item.enabled"><then><p>Body</p></then></if></each></document>';
            bindingDraft.data='{"show":false,"item":{"wrongRoot":true},"groups":[{"enabled":true,"name":"PRIVATE_VALUE","children":[]}],"exact":9007199254740993}';
            $('data').value=bindingDraft.data;
            window.inlineSelection='';
            window.inlineBrowser=DesignerBindings.mountFields($('inline-fields'),{path:'/0/0',types:['boolean'],onSelect:path=>window.inlineSelection=path});
            return {...bindingDraft};
        });
        const host=page.locator('[data-binding-browser]');
        assert.deepEqual(await host.locator('[data-binding-path]').evaluateAll(nodes=>nodes.map(node=>node.dataset.bindingPath)),['item.enabled','show']);
        assert.doesNotMatch(await host.innerText(),/PRIVATE_VALUE|wrongRoot/);
        await host.getByRole('button',{name:'item.enabled',exact:true}).click();
        assert.equal(await page.evaluate(()=>inlineSelection),'item.enabled');
        assert.deepEqual(await page.evaluate(()=>({...bindingDraft})),original,'choosing a source path must not rewrite JSON tokens or template content');
        await page.evaluate(()=>{
            inlineBrowser.destroy();
            window.inlineBrowser=DesignerBindings.mountFields($('inline-fields'),{path:'/0/0',types:['array'],onSelect:path=>window.inlineSelection=path});
        });
        assert.deepEqual(await host.locator('[data-binding-path]').evaluateAll(nodes=>nodes.map(node=>node.dataset.bindingPath)),['groups','item.children']);
        await page.evaluate(()=>{
            inlineBrowser.destroy();
            bindingDraft.source='<document version="1"><p>Body</p></document>';
            bindingDraft.data=JSON.stringify(Object.fromEntries(Array.from({length:150},(_,index)=>['flag'+String(index).padStart(3,'0'),true])));
            $('data').value=bindingDraft.data;
            window.inlineBrowser=DesignerBindings.mountFields($('inline-fields'),{path:'/0',types:['boolean'],onSelect:path=>window.inlineSelection=path});
        });
        assert.ok(await host.locator('[data-binding-path]').count()<=80,'large data models must not create an unbounded inspector list');
        assert.equal(await host.locator('[data-binding-path="flag149"]').count(),0);
        await host.getByRole('searchbox',{name:'字段搜索',exact:true}).fill('flag149');
        assert.equal(await host.locator('[data-binding-path]').count(),1,'search must use all metadata rather than only the first visible fields');
        await host.getByRole('button',{name:'flag149',exact:true}).click();
        assert.equal(await page.evaluate(()=>inlineSelection),'flag149');
    }finally{
        await browser.close();
    }
});
