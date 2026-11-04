// SPDX-License-Identifier: Apache-2.0
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from 'playwright';

test('full-width insertion preserves caret semantics and column scopes', {timeout:60000}, async t=>{
    const bundle=await build({
        stdin:{contents:`export * from './columnInsertion';
            export {mountRich,createRichSession,editorDocument} from './rich';
            export {TextSelection} from 'prosemirror-state';`,resolveDir:fileURLToPath(new URL('../src/',import.meta.url))},
        bundle:true,format:'iife',globalName:'Columns',write:false,target:'es2022'
    });
    const browser=await chromium.launch({channel:process.env.EXPORT4J_BROWSER_CHANNEL||'chrome',headless:true,timeout:20000});
    try {
        const context=await browser.newContext({serviceWorkers:'block'});
        await context.route('**/*',route=>route.abort());
        const page=await context.newPage();await page.addScriptTag({content:bundle.outputFiles[0].text});
        const names=await page.evaluate(()=>{
            const checks=new Map(),check=(name,work)=>checks.set(name,work);
            const assert=(value,label)=>{if(!value)throw new Error(label);};
            const equal=(actual,expected,label)=>assert(JSON.stringify(actual)===JSON.stringify(expected),label+': '+JSON.stringify(actual));
            const refuses=(work,pattern)=>{let error;try{work();}catch(value){error=value;}assert(error instanceof Error&&pattern.test(error.message),'expected rejection '+pattern+', actual '+error);};
            const parse=source=>new DOMParser().parseFromString(source,'application/xml');
            const table='<table id="wide"><columns><column weight="1"/></columns><row><cell>Full width</cell></row></table>';
            const source='<document version="1"><columns id="flow" count="3" gap="12" weights="1 2 1" balanced="true">'+
                '<!--prefix--><p id="paragraph" size="11" line-height="24" color="#123456" indent="8">prefix '+
                '<span role="bold" color="#224488" size="14" baseline-shift="2">left ${person.name}'+
                '<link href="https://example.com/report?a=1&amp;b=2">middle😀END</link></span>'+
                '<image ref="mark" width="12" height="9" alt="inline mark"/><page-number width="20"/> tail'+
                '<style-if test="enabled" color="#556677"/></p>'+
                '<each items="paragraphs" as="paragraph"><p id="loop">${paragraph}</p></each><p id="last">Last</p><!--tail--></columns></document>';
            const path='/0/0',paragraph=parse(source).getElementById('paragraph');
            const model=Columns.editorDocument(paragraph).firstChild;
            const size=model.content.size;
            const withCaret=(offset,xml=source)=>Columns.prepareColumnInsertion(xml,path,{offset,size,empty:true});

            check('direct paragraphs have a canvas boundary while dynamic descendants explain their scope',()=>{
                const direct=Columns.columnInsertionTarget(source,path);
                assert(direct.editable&&direct.paragraph,'direct paragraph boundary');
                equal(direct.columnsPath,'/0','owning columns path');
                const nested=Columns.columnInsertionTarget(source,'/0/1/0');
                assert(!nested.editable&&nested.reason.includes('循环'),'dynamic descendants are not lifted');
                assert(Columns.columnInsertionTarget(source,'/0/1').editable,'whole loop remains a valid after boundary');
                assert(!Columns.columnInsertionTarget(source,'/0').editable,'selecting columns alone needs a content boundary');
            });
            check('real ProseMirror caret splits marked linked text while retaining every XML attribute',()=>{
                const document=parse(source),original=document.getElementById('paragraph'),host=window.document.createElement('div');window.document.body.append(host);
                const editor=Columns.mountRich(host,original,{changed(){},paste(){},commit(){},merge:()=>false,inlineOnly:false,session:Columns.createRichSession([original])});
                try{
                    let position;
                    editor.state.doc.descendants((node,offset)=>{if(node.isText&&node.text==='middle😀END')position=offset+3;});
                    assert(Number.isInteger(position),'actual PM linked text position');
                    editor.dispatch(editor.state.tr.setSelection(Columns.TextSelection.create(editor.state.doc,position)));
                    const selection=editor.state.selection;
                    const boundary=Columns.prepareColumnInsertion(source,path,{offset:selection.$from.parentOffset,size:selection.$from.parent.content.size,empty:selection.empty});
                    const changed=Columns.insertColumnFullWidth(boundary.source,boundary.path,table,boundary.after),tree=parse(changed.source);
                    equal([...tree.documentElement.children].map(node=>node.tagName),['columns','table','columns'],'body order');
                    const [head,,tail]=tree.documentElement.children,before=head.querySelector('p'),after=tail.querySelector('p');
                    equal(before.querySelector('link').textContent,'mid','head keeps text before caret');
                    equal(after.querySelector('link').textContent,'dle😀END','tail keeps text after caret');
                    for(const node of [before,after]){
                        for(const name of ['size','line-height','color','indent'])equal(node.getAttribute(name),original.getAttribute(name),'paragraph '+name);
                        for(const name of ['role','color','size','baseline-shift'])equal(node.querySelector('span').getAttribute(name),original.querySelector('span').getAttribute(name),'span '+name);
                        equal(node.querySelector('link').getAttribute('href'),'https://example.com/report?a=1&b=2','link href');
                        assert(node.querySelector('style-if').isEqualNode(original.querySelector('style-if')),'conditional paragraph style retained');
                    }
                    equal(before.getAttribute('id'),'paragraph','original paragraph identity');
                    assert(after.getAttribute('id')!=='paragraph','tail gets an independent identity');
                    equal(tree.querySelectorAll('image').length,1,'original inline image exactly once');
                    assert(tree.querySelector('image').isEqualNode(original.querySelector('image')),'image attributes retained');
                    assert(tree.querySelector('page-number').isEqualNode(original.querySelector('page-number')),'field attributes retained');
                    assert(before.textContent.includes('${person.name}'),'variable literal retained');
                    assert(tail.querySelector('each').isEqualNode(document.querySelector('each')),'following loop and binding scope remain intact');
                    for(const name of ['count','gap','weights','balanced'])equal(tail.getAttribute(name),head.getAttribute(name),'column '+name);
                    equal((changed.source.match(/<!--prefix-->/g)||[]).length,1,'leading comment exactly once');
                    equal((changed.source.match(/<!--tail-->/g)||[]).length,1,'trailing comment exactly once');
                    equal(changed.paths,['/1'],'inserted canvas path');
                }finally{editor.destroy();host.remove();}
            });
            check('variables, inline fields and images occupy atomic PM positions',()=>{
                const fixture='<document version="1"><columns id="flow"><p id="p">A${very.long.variable}<image ref="mark" width="9" height="7"/><page-count width="20"/>B</p></columns></document>';
                const p=parse(fixture).getElementById('p'),paragraph=Columns.editorDocument(p).firstChild;
                equal(paragraph.content.size,5,'PM text plus three atomic tokens');
                for(const [offset,left,right] of [[1,'A','${very.long.variable}B'],[2,'A${very.long.variable}','B'],[3,'A${very.long.variable}','B'],[4,'A${very.long.variable}','B']]){
                    const boundary=Columns.prepareColumnInsertion(fixture,'/0/0',{offset,size:5,empty:true}),tree=parse(boundary.source),parts=tree.querySelectorAll('p');
                    equal(parts[0].textContent,left,'literal left side at '+offset);equal(parts[1].textContent,right,'literal right side at '+offset);
                    equal(tree.querySelectorAll('image').length,1,'image once at '+offset);equal(tree.querySelectorAll('page-count').length,1,'field once at '+offset);
                    const hasImage=!!parts[0].querySelector('image');equal(hasImage,offset>=3,'image side at '+offset);
                    equal(!!parts[0].querySelector('page-count'),offset>=4,'field side at '+offset);
                }
            });
            check('UTF-16 caret boundaries preserve supplementary Unicode characters',()=>{
                const fixture='<document version="1"><columns><p id="p">A😀B</p></columns></document>';
                const boundary=Columns.prepareColumnInsertion(fixture,'/0/0',{offset:3,size:4,empty:true}),parts=parse(boundary.source).querySelectorAll('p');
                equal([...parts].map(node=>node.textContent),['A😀','B'],'UTF-16 caret mapping');
                refuses(()=>Columns.prepareColumnInsertion(fixture,'/0/0',{offset:2,size:4,empty:true}),/Unicode/);
            });
            check('start and end carets do not manufacture empty paragraphs or column regions',()=>{
                const fixture='<document version="1"><columns id="flow" count="2"><p id="only">Only</p></columns></document>';
                for(const offset of [0,4]){
                    const boundary=Columns.prepareColumnInsertion(fixture,'/0/0',{offset,size:4,empty:true});
                    equal(boundary.source,fixture,'boundary source remains literal');
                    const result=Columns.insertColumnFullWidth(boundary.source,boundary.path,table,boundary.after),tree=parse(result.source);
                    equal(tree.querySelectorAll('document > columns').length,1,'one nonempty column region');
                    equal(tree.querySelectorAll('p').length,1,'no artificial empty paragraph');
                    equal([...tree.documentElement.children].map(node=>node.tagName),offset===0?['table','columns']:['columns','table'],'edge order');
                    equal(tree.querySelector('document > columns').getAttribute('id'),'flow','remaining column identity');
                }
            });
            check('selected paragraphs insert after the block without requiring a text caret',()=>{
                const boundary=Columns.prepareColumnInsertion(source,path),result=Columns.insertColumnFullWidth(boundary.source,boundary.path,table,boundary.after),tree=parse(result.source);
                equal(tree.querySelector('document > columns > p').textContent,paragraph.textContent,'whole original paragraph retained');
                equal([...tree.documentElement.children].map(node=>node.tagName),['columns','table','columns'],'after paragraph body order');
            });
            check('unique identities include content moved into the trailing region',()=>{
                const fixture='<document version="1"><columns id="flow"><p id="p">AB</p><p id="column-flow-1">Later</p><p id="column-text-1">Last</p></columns></document>';
                const boundary=Columns.prepareColumnInsertion(fixture,'/0/0',{offset:1,size:2,empty:true});
                assert(boundary.source.includes('id="column-text-2"'),'split avoids later paragraph identities');
                const fragment=table.replace('id="wide"','id="column-flow-2"'),result=Columns.insertColumnFullWidth(boundary.source,boundary.path,fragment,boundary.after),tree=parse(result.source);
                equal(tree.querySelector('document > columns:last-child').getAttribute('id'),'column-flow-3','column ID avoids inserted and trailing content');
                const ids=[...tree.querySelectorAll('[id]')].map(node=>node.getAttribute('id'));equal(new Set(ids).size,ids.length,'no duplicate identity');
            });
            check('nonempty selections and stale caret sizes reject without removing text',()=>{
                refuses(()=>Columns.prepareColumnInsertion(source,path,{offset:1,size,empty:false}),/收起文字选区/);
                refuses(()=>Columns.prepareColumnInsertion(source,path,{offset:1,size:size+1,empty:true}),/已变化/);
                refuses(()=>Columns.prepareColumnInsertion(source,path,{offset:-1,size,empty:true}),/已变化/);
                equal(parse(source).getElementById('paragraph').textContent,paragraph.textContent,'source remains unchanged');
            });
            check('dynamic and table internals cannot be lifted out of their scope',()=>{
                refuses(()=>Columns.prepareColumnInsertion(source,'/0/1/0',{offset:0,size:1,empty:true}),/循环/);
                const boundary=Columns.prepareColumnInsertion(source,'/0/1'),result=Columns.insertColumnFullWidth(boundary.source,boundary.path,table,boundary.after),tree=parse(result.source);
                assert(tree.querySelector('each').isEqualNode(parse(source).querySelector('each')),'whole loop remains intact at its permitted boundary');
                refuses(()=>Columns.insertColumnFullWidth('<document version="1"><columns>'+table+'</columns></document>','/0/0/1/0',table),/内部/);
                refuses(()=>Columns.insertColumnFullWidth(source,path,'<p>Not an image</p>'),/独立图片/);
            });
            check('image paragraphs use the same full-width interruption and retain their resource reference',()=>{
                const boundary=withCaret(1),image='<p id="wide-image" line-height="46"><image ref="figure" width="100" height="40" alt="wide image"/></p>';
                const result=Columns.insertColumnFullWidth(boundary.source,boundary.path,image,boundary.after),tree=parse(result.source);
                equal([...tree.documentElement.children].map(node=>node.tagName),['columns','p','columns'],'image body interruption');
                assert(tree.getElementById('wide-image').isEqualNode(parse(image).documentElement),'image XML remains literal');
            });
            check('declarations, malformed paths, duplicate IDs and source budgets reject before mutation',()=>{
                refuses(()=>Columns.prepareColumnInsertion('<!DOCTYPE document><document/>',''),/外部声明/);
                refuses(()=>Columns.prepareColumnInsertion('<document>'+('x'.repeat(1_000_000))+'</document>',''),/预算/);
                refuses(()=>Columns.prepareColumnInsertion(source,'/0/../0'),/位置无效/);
                refuses(()=>Columns.prepareColumnInsertion(source,'/99'),/已变化/);
                refuses(()=>Columns.prepareColumnInsertion(source.replace('id="last"','id="paragraph"'),path),/ID 重复/);
                refuses(()=>Columns.insertColumnFullWidth(source,path,table.replace('id="wide"','id="paragraph"')),/ID 重复/);
            });
            globalThis.ColumnChecks=checks;return [...checks.keys()];
        });
        for(const name of names)await t.test(name,{timeout:10000},()=>page.evaluate(name=>globalThis.ColumnChecks.get(name)(),name));
    }finally{await browser.close();}
});
