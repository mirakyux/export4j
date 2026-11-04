// SPDX-License-Identifier: Apache-2.0
'use strict';
window.DesignerBindings=(()=>{
    const pathPattern=/^[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*$/;
    const labels={string:'文字',number:'数字',boolean:'布尔',array:'数组',object:'对象',mixed:'混合类型',unknown:'待声明',null:'空值',unsupported:'不支持的字段'};
    let active=false;
    function listFallback(source){
        if(source.length>1_000_000)throw new Error('测试数据超过 1,000,000 个字符的上限。');
        // Parse only field metadata. Existing numbers must never pass through JSON.stringify.
        const data=JSON.parse(source),identifier=/^[A-Za-z_][A-Za-z_0-9]*$/;
        if(!data||typeof data!=='object'||Array.isArray(data))throw new Error('测试数据必须是 JSON 对象。');
        const arrays=Object.entries(data).filter(([name,value])=>identifier.test(name)&&Array.isArray(value));
        const selected=arrays.find(([name])=>name==='items')??arrays[0];
        if(!selected){
            let name='items',suffix=1;
            while(Object.hasOwn(data,name))name='items'+ ++suffix;
            // A valid object ends with this brace. Insert a new member before its trailing whitespace,
            // retaining every original numeric token, escape and whitespace character verbatim.
            const end=source.lastIndexOf('}'),at=end-source.slice(0,end).match(/[ \r\n\t]*$/)[0].length;
            const addition=(Object.keys(data).length?',':'')+'\n  "'+name+'": [{"name":"示例项"}]'+(at===end?'\n':'');
            const candidate=source.slice(0,at)+addition+source.slice(at);
            if(candidate.length>1_000_000)throw new Error('创建示例数组后测试数据超过 1,000,000 个字符的上限。');
            return {path:name,expression:'item.name',data:candidate};
        }
        const [path,items]=selected,sample=items.find(item=>item!==null);
        const field=sample&&typeof sample==='object'&&!Array.isArray(sample)
            ?Object.keys(sample).find(key=>key==='name')??Object.keys(sample).find(key=>identifier.test(key)):undefined;
        return {path,expression:field?'item.'+field:'item'};
    }
    const element=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
    const snapshot=()=>({...DesignerStudioBridge.snapshot(),data:$('data').value});
    const context=(path,current=snapshot())=>DesignerStudio.bindingContext(current,path||'');
    function pathOf(node){return node.parentElement?pathOf(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node):'';}
    function insertionContext(current,path){
        const document=new DOMParser().parseFromString(current.source,'application/xml');
        let owner=document.documentElement;if(path)for(const part of path.slice(1).split('/'))owner=owner.children[Number(part)];
        const anchor=document.createElement('p');owner.append(anchor);
        return context(pathOf(anchor),{...current,source:new XMLSerializer().serializeToString(document)});
    }
    function fieldsForArray(current,path,items,alias){
        if(!pathPattern.test(items)||!pathPattern.test(alias)||alias.includes('.'))return [];
        const document=new DOMParser().parseFromString(current.source,'application/xml');if(document.querySelector('parsererror'))return [];
        let owner=document.documentElement;if(path)for(const part of path.slice(1).split('/'))owner=owner.children[Number(part)];
        const scope=document.createElement('each'),paragraph=document.createElement('p');scope.setAttribute('items',items);scope.setAttribute('as',alias);scope.append(paragraph);owner.append(scope);
        const projected={...current,source:new XMLSerializer().serializeToString(document)};
        return context(pathOf(paragraph),projected).fields.filter(field=>(field.path===alias||field.path.startsWith(alias+'.'))&&['string','number','boolean','mixed','unknown'].includes(field.type));
    }
    function open(path,{kind,initial,fallback,condition=false}={}){
        if(active)return Promise.reject(new Error('请先完成当前变量或数据源选择。'));
        const bound=!!kind,current=snapshot(),read=()=>bound||condition?insertionContext(current,path):context(path,current);let choices=read();
        if(bound&&!choices.fields.some(field=>field.type==='array')&&fallback?.data){current.data=fallback.data;choices=read();}
        active=true;
        return new Promise(resolve=>{
            const dialog=$('binding-dialog'),input=$('binding-path'),alias=$('binding-alias'),field=$('binding-template-field'),confirm=$('binding-confirm'),list=$('binding-fields'),scopes=$('binding-scopes'),error=$('binding-error');
            $('binding-title').textContent=condition?'添加条件':bound?'绑定数组数据':'插入变量';
            $('binding-path-caption').textContent=condition?'条件数据路径':bound?'数组路径':'变量路径';input.setAttribute('aria-label',condition?'条件数据路径':bound?'数组路径':'变量路径');
            $('binding-alias-field').hidden=!bound;$('binding-template-field-label').hidden=!bound;
            confirm.textContent=condition?'创建条件':bound?(initial?'应用数据源':kind==='rows'?'创建数据表格':kind==='each'?'创建数据循环':'创建数据列表'):'插入';
            $('binding-description').textContent=condition?'选择布尔字段。值为 true 时显示满足条件分支，false 时显示另一分支；不会修改测试数据。':bound?'选择一个数组，并为每项设置迭代变量；只配置声明，不执行表达式。':'选择字段或手写数据路径。插入的是 ${path} 模板原文，预览时再读取绑定数据。';
            input.value=initial?.items||'';alias.value=initial?.as||(kind==='rows'?'record':'item');field.value='';error.textContent='';error.hidden=true;list.replaceChildren();scopes.replaceChildren();
            dialog.querySelectorAll('input,button').forEach(control=>control.disabled=false);
            for(const scope of choices.scopes){const badge=element('span',scope.kind==='root'?'根数据':`${scope.kind}：${scope.alias} ← ${scope.items}`);badge.className='binding-scope';scopes.append(badge);}
            $('binding-hint').textContent=choices.hint||'只列出字段名与类型，不显示数据值。';
            const arrays=choices.fields.filter(value=>value.type==='array');
            if(bound&&!input.value)input.value=arrays.find(value=>value.path==='items')?.path||arrays[0]?.path||fallback?.path||'';
            let previousExpression='';
            const infer=()=>{
                if(!bound)return;
                const proposed=fieldsForArray(current,path,input.value.trim(),alias.value.trim()),preferred=proposed.find(value=>value.path.endsWith('.name'))||proposed.find(value=>value.path!==alias.value.trim())||proposed[0];
                const next=preferred?.path||'';if(!field.value||field.value===previousExpression)field.value=next;previousExpression=next;
                const unknown=proposed.some(value=>value.path===alias.value.trim()&&value.type==='unknown');
                $('binding-field-hint').textContent=unknown?'数组为空或条目类型未声明，无法推断字段。对象记录请填写 '+alias.value.trim()+'.name 等具体字段；直接使用 '+alias.value.trim()+' 仅适合文字、数字或布尔条目。':proposed.length?'将以该字段生成初始内容；创建后可继续插入其他变量。':'尚无可推断的逐项字段，可手写路径；空数组不会生成正文行。';
            };
            input.oninput=infer;alias.oninput=infer;
            const offered=bound?arrays:choices.fields;
            for(const value of offered){
                const button=element('button');button.type='button';button.setAttribute('aria-label',value.path);
                button.append(element('code',value.path),element('small',labels[value.type]||value.type));
                const allowed=condition?value.type==='boolean':bound||!['array','object','null','unsupported'].includes(value.type);button.disabled=!allowed;
                if(!allowed)button.title=value.type==='array'?'数组用于数据列表或数据表格。':'请选择这个对象内的具体字段。';
                button.onclick=()=>{input.value=value.path;infer();input.focus();};list.append(button);
            }
            if(!offered.length){const empty=element('p',bound?'暂无已声明数组，可手写数组路径；没有数组时可使用默认 items 示例数据。':'暂无可选字段，可手写路径，或先在测试数据中声明字段。');empty.className='binding-empty';list.append(empty);}
            const sample=bound&&fallback?.data&&arrays.some(value=>value.path===fallback.path);$('binding-sample-hint').hidden=!sample;
            $('binding-sample-hint').textContent=sample?'当前没有数组。选择 '+fallback.path+' 时会同时创建一项示例数据，模板和数据可一起撤销。':'';
            infer();
            const finish=value=>{active=false;dialog.close();resolve(value);};
            confirm.onclick=()=>{
                const value=input.value.trim(),name=alias.value.trim(),expression=field.value.trim();let message='';
                if(value.length>256||!pathPattern.test(value))message='请填写 ASCII 点路径，例如 patient.name 或 item.code；不填写 ${} 或表达式。';
                else if(bound&&(!/^[A-Za-z_][A-Za-z_0-9]{0,127}$/.test(name)))message='迭代变量须为字母或下划线开头的标识符。';
                else if(bound&&(expression.length>256||!pathPattern.test(expression)))message='请为重复内容填写数据路径，例如 '+name+'.name。';
                else if(condition&&choices.fields.some(field=>field.path===value&&field.type!=='boolean'&&field.type!=='unknown'))message='条件须引用布尔值（true / false），不能使用文字或数字字段。';
                if(message){error.textContent=message;error.hidden=false;input.focus();return;}
                finish(bound?{items:value,as:name,expression,...(fallback?.data&&value===fallback.path?{data:fallback.data}:{})}:value);
            };
            $('binding-cancel').onclick=()=>finish(null);$('binding-close').onclick=()=>finish(null);dialog.oncancel=event=>{event.preventDefault();finish(null);};
            input.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();confirm.click();}};
            dialog.showModal();input.focus();
        });
    }
    function mountFields(host,{path='',types,onSelect,selectedPath='',searchLabel='字段搜索'}={}){
        const scopes=element('div'),search=element('input'),list=element('div'),hint=element('p'),count=element('p'),error=element('p');
        host.classList.add('binding-inline-browser');host.dataset.bindingBrowser='';
        scopes.className='binding-inline-scopes';scopes.setAttribute('aria-label','当前数据作用域');
        search.type='search';search.autocomplete='off';search.spellcheck=false;search.placeholder='搜索字段名…';search.setAttribute('aria-label',searchLabel);
        list.className='binding-inline-fields';list.setAttribute('aria-label','可选数据字段');
        hint.className='binding-inline-hint';count.className='binding-inline-count';error.className='binding-inline-error';error.setAttribute('role','alert');error.hidden=true;
        host.replaceChildren(scopes,search,list,count,hint,error);
        const allowed=types?new Set(types):null,limit=80;
        let choices,selection=selectedPath;
        function render(){
            list.replaceChildren();error.hidden=true;
            const query=search.value.trim().toLowerCase(),matches=choices.fields.filter(field=>(!allowed||allowed.has(field.type))&&field.path.toLowerCase().includes(query));
            for(const field of matches.slice(0,limit)){
                const button=element('button');button.type='button';button.dataset.bindingPath=field.path;button.setAttribute('aria-label',field.path);
                button.setAttribute('aria-pressed',String(field.path===selection));
                button.append(element('code',field.path),element('small',labels[field.type]||field.type));
                // Keep a body selection available when choosing a variable without using the search box.
                button.addEventListener('pointerdown',event=>event.preventDefault());
                button.addEventListener('click',()=>{
                    if(typeof onSelect!=='function')return;
                    error.hidden=true;
                    void Promise.resolve().then(()=>onSelect(field.path)).then(()=>{
                        selection=field.path;
                        for(const option of list.querySelectorAll('[data-binding-path]'))option.setAttribute('aria-pressed',String(option.dataset.bindingPath===selection));
                    }).catch(failure=>{error.textContent=failure instanceof Error?failure.message:String(failure);error.hidden=false;});
                });
                list.append(button);
            }
            if(!matches.length){const empty=element('p',query?'没有匹配的字段，可在路径框中直接填写。':'暂无可选字段，可直接填写数据路径。');empty.className='binding-empty';list.append(empty);}
            count.hidden=matches.length<=limit;count.textContent=matches.length>limit?`显示前 ${limit} / ${matches.length} 个字段，继续搜索可缩小范围。`:'';
        }
        function refresh(){
            scopes.replaceChildren();
            try{
                choices=context(path);
                for(const scope of choices.scopes){
                    const badge=element('span',scope.kind==='root'?'根数据':`${scope.alias} ← ${scope.items}`);badge.className='binding-scope';scopes.append(badge);
                }
                hint.textContent=choices.hint||'';hint.hidden=!choices.hint;render();
            }catch(failure){
                choices={fields:[],scopes:[],hint:''};render();hint.hidden=true;error.textContent=failure instanceof Error?failure.message:String(failure);error.hidden=false;
            }
        }
        search.addEventListener('input',render);refresh();
        return {refresh,destroy:()=>{search.removeEventListener('input',render);host.replaceChildren();}};
    }
    function mountValue(host,{path='',fieldPath='',inputLabel='变量测试值',onChange}={}){
        host.replaceChildren();host.classList.add('binding-value-editor');
        const note=text=>{const node=element('p',text);node.className='inspector-inline-note';host.append(node);};
        function refresh(){
            host.replaceChildren();if(!fieldPath)return;
            let result;try{result=DesignerStudio.bindingValue(snapshot(),path,fieldPath);}catch(failure){note(failure.message);return;}
            if(result.status!=='value'){
                note(result.status==='repeated'?'此变量来自循环记录，每条数据的值不同；请在测试数据中编辑对应记录。':result.status==='missing'?'测试数据中尚无此字段；可继续引用，并在测试数据中补充。':'此字段不是可直接编辑的文字、数字或布尔值。');return;
            }
            const label=element('label','测试值'),input=element(result.type==='boolean'?'select':result.type==='string'?'textarea':'input'),error=element('p');
            label.className='setting-field';input.setAttribute('aria-label',inputLabel);input.autocomplete='off';
            if(result.type==='string')input.rows=3;
            if(result.type==='boolean')for(const value of ['true','false']){const option=element('option',value);option.value=value;input.append(option);}
            input.value=result.type==='string'?result.value:result.raw;const displayed=input.value;input.spellcheck=false;label.append(input);error.className='binding-inline-error';error.setAttribute('role','alert');error.hidden=true;
            const session=DesignerStudioBridge.snapshot().session;let expectedRaw=result.raw,composing=false;
            const update=()=>{
                if(busy||nodeXmlDirty)return;
                try{
                    error.hidden=true;if(selected?.path!==path||DesignerStudioBridge.snapshot().session!==session)throw new Error('编辑位置已变化，请重新选择变量。');
                    const current=DesignerStudio.bindingValue(snapshot(),path,fieldPath);
                    if(current.status!=='value'||current.raw!==expectedRaw)throw new Error('测试值已变化，请重新选择变量后编辑。');
                    // Textareas normalize CRLF. Saving an untouched display must retain its original token.
                    const raw=input.value===displayed?result.raw:result.type==='string'?JSON.stringify(input.value):input.value;
                    const candidate=DesignerStudio.editBindingValue(snapshot(),path,fieldPath,raw);
                    expectedRaw=DesignerStudio.bindingValue({...snapshot(),data:candidate},path,fieldPath).raw;
                    if(candidate!==$('data').value){
                        // Preview the data draft without reinstalling the inspector and losing its caret.
                        // Navigation, export and history commit it together with the current source draft.
                        $('data').value=candidate;markDirty();refreshButtons();schedulePreview();DesignerStudio.sync();
                    }
                    onChange?.(expectedRaw);
                }catch(failure){error.textContent=failure.message+(result.type==='number'?'；保留输入，预览继续使用最后一个有效值。':'');error.hidden=false;}
            };
            input.addEventListener(result.type==='boolean'?'change':'input',event=>{if(!composing&&!event.isComposing)update();});
            input.addEventListener('compositionstart',()=>{composing=true;});input.addEventListener('compositionend',()=>{composing=false;update();});
            const hint=element('p','修改后自动预览');hint.className='inspector-inline-note';host.append(label,hint,error);
        }
        refresh();return {refresh,destroy:()=>host.replaceChildren()};
    }
    return {pick:path=>open(path),pickCondition:path=>open(path,{condition:true}),pickSource:(kind,path,options)=>open(path,{...options,kind}),context,listFallback,mountFields,mountValue};
})();
