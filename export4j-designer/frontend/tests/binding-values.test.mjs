// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('inspector test-value editing preserves JSON tokens and respects lexical scopes', {timeout:60000}, async t=>{
    const bundle=await build({
        entryPoints:[fileURLToPath(new URL('../src/bindingValues.ts',import.meta.url))],
        bundle:true,format:'iife',globalName:'BindingValues',write:false,target:'es2022'
    });
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try {
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();
        await page.addScriptTag({content:bundle.outputFiles[0].text});
        const names=await page.evaluate(()=>{
            const cases=new Map(),check=(name,work)=>cases.set(name,work);
            const equal=(actual,expected)=>{if(JSON.stringify(actual)!==JSON.stringify(expected))throw new Error('expected '+JSON.stringify(expected)+', actual '+JSON.stringify(actual));};
            const assert=(condition,label)=>{if(!condition)throw new Error(label);};
            const refuses=work=>{let failure;try{work();}catch(error){failure=error;}assert(failure instanceof Error,'candidate must be rejected');};
            const source=body=>'<document version="1">'+body+'</document>';
            const snapshot=(data,body='<p/>')=>({source:source(body),data});
            const read=(draft,field,path='/0')=>BindingValues.bindingValue(draft,path,field);
            const edit=(draft,field,raw,path='/0')=>BindingValues.editBindingValue(draft,path,field,raw);

            check('root scalars expose decoded strings and booleans while numbers remain exact tokens',()=>{
                const draft=snapshot('{"title":"A\\nB","show":false,"total":9007199254740993,"customer":{"name":"Ada"}}');
                equal(read(draft,'title'),{status:'value',type:'string',raw:'"A\\nB"',value:'A\nB'});
                equal(read(draft,'show'),{status:'value',type:'boolean',raw:'false',value:false});
                equal(read(draft,'total'),{status:'value',type:'number',raw:'9007199254740993',value:'9007199254740993'});
                equal(read(draft,'customer.name'),{status:'value',type:'string',raw:'"Ada"',value:'Ada'});
            });

            check('editing one token preserves surrounding whitespace and all unrelated numeric lexemes',()=>{
                const data=' {\r\n "id":9007199254740993, "negative":-9007199254740993, "fraction":0.10000000000000000001, "exponent":1.2300e+9999, "nested": {"title" : "old"}, "escaped":"} \\u4e2d \\\"" } \t\r\n';
                const draft=snapshot(data);
                equal(edit(draft,'nested.title','"new"'),data.replace('"old"','"new"'));
                equal(draft.data,data);
                equal(edit(draft,'id','9007199254740995'),data.replace('9007199254740993','9007199254740995'));
                equal(edit(draft,'exponent','1.234567890123456789e+9998'),data.replace('1.2300e+9999','1.234567890123456789e+9998'));
            });

            check('candidate whitespace is not inserted outside the replaced token',()=>{
                const draft=snapshot('{ "show" : false , "title":"old" }');
                equal(edit(draft,'show',' \r\n true\t '),'{ "show" : true , "title":"old" }');
                const raw=JSON.stringify('中文 "quoted"\n\\tail');
                const changed=edit(draft,'title',raw);
                equal(JSON.parse(changed).title,'中文 "quoted"\n\\tail');
                assert(changed.startsWith('{ "show" : false , "title":'),'unrelated characters stay exact');
            });

            check('escaped object keys and prototype-named own keys resolve safely',()=>{
                const draft=snapshot('{"ti\\u0074le":"old","__proto__":{"value":"own"},"constructor":true}');
                equal(read(draft,'title').value,'old');
                equal(edit(draft,'title','"new"'),'{"ti\\u0074le":"new","__proto__":{"value":"own"},"constructor":true}');
                equal(read(draft,'__proto__.value').value,'own');
                equal(edit(draft,'constructor','false'),draft.data.replace('true','false'));
                equal(read(draft,'toString'),{status:'missing'});
                equal(Object.prototype.value,undefined);
            });

            check('missing fields and non-scalar targets cannot create or replace data',()=>{
                const draft=snapshot('{"object":{"name":"A"},"items":[1,2],"empty":null}');
                for(const [field,type] of [['object','object'],['items','array'],['empty','null']]){
                    equal(read(draft,field),{status:'invalid',type});
                    refuses(()=>edit(draft,field,'"replacement"'));
                }
                for(const field of ['missing','object.missing','items.name','empty.name']){
                    equal(read(draft,field),{status:'missing'});refuses(()=>edit(draft,field,'"created"'));
                }
                equal(draft.data,'{"object":{"name":"A"},"items":[1,2],"empty":null}');
            });

            check('list and each aliases hide same-named root fields even with absent iteration data',()=>{
                for(const tag of ['list','each']){
                    const draft=snapshot('{"item":{"name":"ROOT_PRIVATE"},"items":[]}',`<${tag} items="items" as="item"><p/></${tag}>`);
                    equal(read(draft,'item.name','/0/0'),{status:'repeated'});
                    refuses(()=>edit(draft,'item.name','"must not update root"','/0/0'));
                    equal(read(draft,'item.name','/0').value,'ROOT_PRIVATE');
                    equal(edit(draft,'item.name','"root control"','/0'),draft.data.replace('ROOT_PRIVATE','root control'));
                }
            });

            check('nested alias scopes prohibit editing both outer and inner repeated values',()=>{
                const draft=snapshot('{"customer":{"name":"root"},"entry":{"name":"root"},"title":"root title","customers":[]}',
                    '<each items="customers" as="customer"><list items="customer.entries" as="entry"><bullet/></list></each>');
                equal(read(draft,'customer.name','/0/0/0'),{status:'repeated'});
                equal(read(draft,'entry.name','/0/0/0'),{status:'repeated'});
                equal(read(draft,'title','/0/0/0').value,'root title');
            });

            check('dynamic table header uses root data while body fields are repeated',()=>{
                const rows='<rows items="records" as="record"><columns><column weight="1"/></columns><header><cell/></header><row><cell/></row></rows>';
                const draft=snapshot('{"record":{"name":"ROOT"},"records":[{"name":"ROW"}]}',rows);
                equal(read(draft,'record.name','/0/1/0').value,'ROOT');
                equal(edit(draft,'record.name','"EDITED"','/0/1/0'),draft.data.replace('ROOT','EDITED'));
                equal(read(draft,'record.name','/0/2/0'),{status:'repeated'});
                refuses(()=>edit(draft,'record.name','"wrong"','/0/2/0'));
            });

            check('new tokens must be valid JSON and retain the original scalar type',()=>{
                const draft=snapshot('{"title":"old","show":true,"total":3}');
                for(const raw of ['unquoted','"unterminated','"bad\\x"','"bad\nnewline"','"ok" trailing','"a" "b"','NaN','Infinity','\u00a0"value"'])refuses(()=>edit(draft,'title',raw));
                for(const raw of ['"false"','0','null','{}','[]'])refuses(()=>edit(draft,'show',raw));
                for(const raw of ['01','+1','.5','1.','1e','--1','NaN','Infinity','true','"3"'])refuses(()=>edit(draft,'total',raw));
                equal(edit(draft,'total','-0.10000000000000000001e+9999'),'{"title":"old","show":true,"total":-0.10000000000000000001e+9999}');
                equal(draft.data,'{"title":"old","show":true,"total":3}');
            });

            check('malformed source data and duplicate decoded keys reject atomically',()=>{
                for(const data of ['{"title":"old"} trailing','{"title":"old",}','{"title":"old","broken":[1,]}','{"title":"old","other":01}',
                    '{"title":"old","other":"bad\\x"}','{"title":"old","other":"bad\nnewline"}','{"title":"old","title":"duplicate"}',
                    '{"title":"old","ti\\u0074le":"duplicate"}','[]','null','true','{"title":']){
                    const draft=snapshot(data);
                    equal(read(draft,'title'),{status:'invalid'});
                    refuses(()=>edit(draft,'title','"new"'));
                    equal(draft.data,data);
                }
            });

            check('invalid field and selection paths never broaden the edit target',()=>{
                const draft=snapshot('{"title":"old"}');
                for(const field of ['', 'title..name','title[0]','title/name','x'.repeat(257)]){
                    equal(read(draft,field),{status:'invalid'});refuses(()=>edit(draft,field,'"new"'));
                }
                for(const path of ['/99','/0/../0']){
                    equal(read(draft,'title',path),{status:'invalid'});refuses(()=>edit(draft,'title','"new"',path));
                }
                const external={...draft,source:'<!DOCTYPE document SYSTEM "https://invalid.example/dtd"><document><p/></document>'};
                equal(read(external,'title'),{status:'invalid'});refuses(()=>edit(external,'title','"new"'));
            });

            check('source, data, work, depth and output budgets are enforced before producing changes',()=>{
                let nested='true';for(let index=0;index<49;index++)nested='{"child":'+nested+'}';
                const fixtures=[
                    snapshot('{"title":"old","nested":'+nested+'}'),
                    snapshot('{"title":"old","items":['+'0,'.repeat(20_001)+'0]}'),
                    snapshot('{"title":"'+'x'.repeat(1_000_000)+'"}'),
                    {...snapshot('{"title":"old"}'),source:source('<p>'+'x'.repeat(1_000_000)+'</p>')}
                ];
                for(const draft of fixtures){
                    equal(read(draft,'title'),{status:'invalid'});refuses(()=>edit(draft,'title','"new"'));
                }
                const draft=snapshot('{"title":"old","padding":"'+'x'.repeat(999_900)+'"}');
                assert(read(draft,'title').status==='value','within-budget source remains editable');
                refuses(()=>edit(draft,'title','"'+'x'.repeat(200)+'"'));
                refuses(()=>edit(snapshot('{"title":"old"}'),'title','"'+'x'.repeat(1_000_000)+'"'));
            });
            globalThis.BindingValueChecks=cases;
            return [...cases.keys()];
        });
        for(const name of names)await t.test(name,{timeout:10000},()=>page.evaluate(name=>globalThis.BindingValueChecks.get(name)(),name));
    } finally {
        await browser.close();
    }
});
