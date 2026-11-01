// SPDX-License-Identifier: Apache-2.0
'use strict';
// The React canvas edits the same retained XML and resource bundle as the source workbench.
const DesignerStudioBridge = (() => {
    let queue = Promise.resolve(), sequence=0, synchronized=0, syncing=false, layoutPending=false, failure, session=0;
    let history=[], cursor=-1, localHistory=false, draftBase;
    let renderedSource='', mappedSource='', mappedPreview='', mappedPages=[], projectedSource='', projectedEntries=[];
    const capture=()=>({source:state.source,data:state.data,paths:[...selectedPaths],ids:selectedPaths.map(path=>entries.find(entry=>entry.path===path)?.id).filter(Boolean)});
    const outline=(node,path='')=>{
        const children=[...node.children].map((child,index)=>outline(child,path+'/'+index));
        const text=node.textContent.trim().replace(/\s+/g,' '),id=node.getAttribute('id')||'';
        return {path,tag:node.tagName,id,label:node.tagName+(id?' #'+id:'')+(children.length?'':'  '+(text.length>36?text.slice(0,36)+'…':text)),children};
    };
    function parse(source){const value=new DOMParser().parseFromString(source,'application/xml');if(value.querySelector('parsererror'))throw new Error('XML 无法解析，修改已保留，请修正后重试。');return value;}
    function selection(ids,paths) {
        const wanted=new Set(ids?.filter(Boolean)), chosen=wanted.size?entries.filter(entry=>entry.id&&wanted.has(entry.id)):[];
        const next=chosen.length?chosen.map(entry=>entry.path):paths?.filter(path=>entries.some(entry=>entry.path===path));
        if(next?.length){selectedPaths=next;selected=entries.find(entry=>entry.path===next[0]);inspect();}
    }
    function changedHistory(before,after){
        if(before.source===after.source&&before.data===after.data)return;
        if(!localHistory){history=[before];cursor=0;localHistory=true;}
        history=history.slice(0,cursor+1);history.push(after);
        if(history.length>101)history.shift();cursor=history.length-1;
    }
    function accepted(value,options={}){
        if(options.local)return;
        const old=state&&capture();
        // A new bundle has a different resource identity. Its server history remains authoritative.
        const resources=state&&state.resourceIdentity!==value.resourceIdentity;
        if(options.session||options.serverHistory||!state||resources){history=[];cursor=-1;localHistory=false;draftBase=undefined;if(options.session)session++;}
        else if(old&&(old.source!==value.source||old.data!==value.data))changedHistory(old,{source:value.source,data:value.data,paths:old.paths,ids:old.ids});
        synchronized=sequence;failure=undefined;
    }
    function remappedPages(source){
        if(!renderedSource||renderedSource===source)return pages;
        if(mappedSource===source&&mappedPreview===previewId)return mappedPages;
        let old,next;try{old=parse(renderedSource);next=parse(source);}catch{return pages;}
        const oldPaths=new Map(),newIds=new Map(),newNodes=new Map();
        function visit(node,path,paths,ids){paths.set(path,node);const id=node.getAttribute('id');if(id){if(ids.has(id))ids.set(id,null);else ids.set(id,{node,path});}[...node.children].forEach((child,index)=>visit(child,path+'/'+index,paths,ids));}
        visit(old.documentElement,'',oldPaths,new Map());visit(next.documentElement,'',newNodes,newIds);
        function pathOf(node){if(!node.parentElement)return '';return pathOf(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node);}
        function relocate(path){
            let node=oldPaths.get(path),suffix='';
            while(node){
                const id=node.getAttribute('id'),anchor=id&&newIds.get(id);
                if(anchor){const candidate=anchor.path+suffix;return newNodes.get(candidate)?.tagName===oldPaths.get(path)?.tagName?candidate:null;}
                if(node.parentElement)suffix='/'+[...node.parentElement.children].indexOf(node)+suffix;node=node.parentElement;
            }
            const original=oldPaths.get(path),same=newNodes.get(path);if(original&&same&&original.isEqualNode(same))return path;
            if(original){const matches=[...newNodes].filter(([,node])=>node.isEqualNode(original));if(matches.length===1)return matches[0][0];}
            return null;
        }
        function flowRoot(path){let node=newNodes.get(path);while(node?.parentElement){if(node.parentElement.tagName==='box')return null;if(['document','section','header','footer'].includes(node.parentElement.tagName)&&['p','heading','table','rows','bullet','numbered','caption','list','each','if','page-break','cross-reference'].includes(node.tagName))return pathOf(node);node=node.parentElement;}return null;}
        mappedPages=pages.map(page=>{
            const hits=page.hits.flatMap(hit=>{const path=relocate(hit.path);return path===null?[]:[{...hit,path}];});
            const groups=new Map();for(const hit of hits){const root=flowRoot(hit.path);if(root){const group=groups.get(root)||[];group.push(hit);groups.set(root,group);}}
            const parents=new Map();for(const [path,hits] of groups){const parent=path.slice(0,path.lastIndexOf('/')),siblings=parents.get(parent)||[];siblings.push({path,hits,top:Math.min(...hits.map(hit=>hit.y)),bottom:Math.max(...hits.map(hit=>hit.y+hit.height))});parents.set(parent,siblings);}
            for(const siblings of parents.values()){
                const physical=[...siblings].sort((a,b)=>a.top-b.top),logical=[...siblings].sort((a,b)=>Number(a.path.split('/').at(-1))-Number(b.path.split('/').at(-1)));
                if(physical.every((group,index)=>group===logical[index]))continue;
                const gaps=physical.slice(0,-1).map((group,index)=>Math.max(0,physical[index+1].top-group.bottom));let y=physical[0].top;
                logical.forEach((group,index)=>{const delta=y-group.top;for(const hit of group.hits){hit.y+=delta;hit.images=hit.images?.map(image=>({...image,y:image.y+delta}));}y+=group.bottom-group.top+(gaps[index]||0);});
            }
            return {...page,hits};
        });
        mappedSource=source;mappedPreview=previewId;return mappedPages;
    }
    function scheduleSync(source,data){
        const version=++sequence;syncing=true;layoutPending=true;failure=undefined;
        clearTimeout(previewTimer);++previewGeneration;
        $('preview-status').textContent='修改已显示 · 正在校验精确版式';
        const next=queue.catch(()=>{}).then(async()=>{
            if(version!==sequence)return;
            try{
                const value=await request('apply',{source,data});
                state.revision=value.revision;
                if(version===sequence){
                    synchronized=version;failure=undefined;
                    if(DesignerStudio.shouldPreview())schedulePreview();
                    else {layoutPending=false;$('preview-status').textContent='便捷编辑 · 完成后校验精确版式';}
                }
            }catch(error){if(version===sequence){failure=error;layoutPending=false;status('精确校验未通过：'+error.message,true);diagnostics({error:error.message});$('preview-status').textContent='修改已保留 · 请修正后重试';}}
            finally{if(version===sequence){syncing=false;refreshButtons();DesignerStudio.sync();}}
        });
        queue=next;DesignerStudio.sync();
    }
    function apply(source,ids,paths){
        try{
            const document=parse(source),data=$('data').value,before=draftBase||capture();draftBase=undefined;
            const after={source,data,paths:paths||[...selectedPaths],ids:ids||before.ids};
            changedHistory(before,after);
            install({...state,source,data,outline:outline(document.documentElement)},true,{local:true});selection(ids,paths);
            if(synchronized!==sequence||source!==before.source||data!==before.data||failure)scheduleSync(source,data);
            return Promise.resolve();
        }catch(error){$('source').value=source;markDirty();return Promise.reject(error);}
    }
    async function flush(commitDraft=true){
        if(!state)return;
        if(commitDraft&&(sourceChanged()||dataChanged()))await apply($('source').value);
        let waiting;do{waiting=queue;await waiting;}while(waiting!==queue);
        if(failure)throw failure;
    }
    const settled=async()=>{let waiting;do{waiting=queue;await waiting;}while(waiting!==queue);};
    const perform=work=>run(async()=>{await settled();await work();});
    async function navigate(paths){
        if(propertyDirty)await applyProperties();
        if(sourceChanged()||dataChanged())await apply($('source').value);
        selectedPaths=paths.filter(path=>entries.some(entry=>entry.path===path));
        selected=entries.find(entry=>entry.path===selectedPaths[0]);if(selected)inspect();else {highlight();DesignerStudio.sync();}
    }
    async function historyStep(direction){
        if(busy)return;
        document.dispatchEvent(new CustomEvent('designer-history',{detail:{direction}}));
        if(propertyDirty)await applyProperties();
        if(sourceChanged()||dataChanged())await apply($('source').value);
        if(!localHistory){await perform(async()=>{install(await request(direction,{}),true,{serverHistory:true});schedulePreview();});return;}
        const index=cursor+(direction==='undo'?-1:1);if(index<0||index>=history.length)return;
        cursor=index;const value=history[cursor];draftBase=undefined;
        install({...state,source:value.source,data:value.data,outline:outline(parse(value.source).documentElement)},true,{local:true});selection(value.ids,value.paths);
        scheduleSync(value.source,value.data);
    }
    return {
        accepted,settled,
        previewed:source=>{renderedSource=source;mappedSource='';layoutPending=false;},
        previewFailed:()=>{layoutPending=false;DesignerStudio.sync();},
        snapshot:()=>{const source=propertyDirty?draftSource():$('source').value;
            if(source!==state?.source&&source!==projectedSource){
                projectedSource=source;projectedEntries=[];
                try{const flatten=(entry,depth=0)=>{projectedEntries.push({...entry,depth});entry.children.forEach(child=>flatten(child,depth+1));};flatten(outline(parse(source).documentElement));}catch{projectedEntries=entries;}
            }
            return {source,revision:state?.revision||0,
            selectedPaths:[...selectedPaths],entries:source===state?.source?entries:projectedEntries,pages:remappedPages(source),currentPage,busy,formats:previewFormats,session,pending:syncing||layoutPending,
            canUndo:localHistory?cursor>0:!!state?.canUndo,canRedo:localHistory?cursor<history.length-1:!!state?.canRedo};},
        choose:async(path,event,groupLists=false)=>{
            if(propertyDirty)await applyProperties();
            if(sourceChanged()||dataChanged())await apply($('source').value);
            const entry=entries.find(entry=>entry.path===path);if(entry)await DesignerCanvas.selectEntry(entry,event,groupLists);
        },
        select:navigate,
        draft:source=>{draftBase??=capture();propertyDirty=false;nodeXmlDirty=false;$('source').value=source;markDirty();if(DesignerStudio.shouldPreview())schedulePreview();else {$('preview-status').textContent='便捷编辑 · 完成后校验精确版式';clearTimeout(previewTimer);++previewGeneration;}DesignerStudio.sync();},
        apply,flush,history:historyStep,preview:()=>schedulePreview(),
        paste:(file,page,x,y,path)=>perform(async()=>{
            await drafts();
            const name='pasted_'+crypto.randomUUID().replaceAll('-','');
            install(await request('resource',{kind:'image',name,bytes:await bytes(file)}));
            const size=await createImageBitmap(file);let width=size.width*72/96,height=size.height*72/96;size.close();
            const paper=pages[page]||{width:595,height:842},factor=Math.min(1,(paper.width-x)/width,(paper.height-y)/height);
            width=Math.max(1,width*factor);height=Math.max(1,height*factor);
            const document=new DOMParser().parseFromString(state.source,'application/xml'),id='image-'+crypto.randomUUID();
            const paragraph=document.createElement('p');paragraph.setAttribute('id',id);paragraph.setAttribute('line-height',String(Math.max(18,height+4)));
            const image=document.createElement('image');for(const [key,value] of Object.entries({ref:name,width,height,alt:file.name||'粘贴的图片'}))image.setAttribute(key,String(value));paragraph.append(image);
            const flowTags=new Set(['p','heading','table','rows','bullet','numbered','caption','list','each','if','page-break','cross-reference']);
            let anchor=path?nodeForPath(document,path):null;
            if(anchor?.tagName==='cell') {
                if(anchor.querySelector(':scope > p,:scope > table'))anchor.append(paragraph);
                else {const previous=document.createElement('p');previous.append(...[...anchor.childNodes].filter(child=>child.nodeType!==1||child.tagName!=='style-if'));anchor.append(previous,paragraph);}
            }else if(anchor?.parentElement?.tagName==='box'){
                const box=anchor.parentElement;box.append(paragraph);box.setAttribute('height-mode','AUTO');
            }else if(anchor&&flowTags.has(anchor.tagName)&&['document','section','header','footer'].includes(anchor.parentElement?.tagName))anchor.after(paragraph);
            else {
                const selectedNode=selected?.path?nodeForPath(document,selected.path):null;
                const owner=selectedNode?.closest('section')||document.documentElement;
                // Region/page declarations must stay before body content. A canvas paste with no body anchor goes to the body tail.
                if(selectedNode&&flowTags.has(selectedNode.tagName)&&selectedNode.parentElement===owner)selectedNode.after(paragraph);else owner.append(paragraph);
            }
            install(await request('apply',{source:new XMLSerializer().serializeToString(document),data:state.data}));selection([id]);await preview();
        }),
        font:async name=>{const response=await fetch('/api/font?name='+encodeURIComponent(name),{headers:{'X-Designer-Token':token}});if(!response.ok)throw new Error('字体资源不可用：'+name);return response.arrayBuffer();},
        error:message=>{status(message,true);diagnostics({error:message});},
        context:(event,path)=>{const entry=entries.find(entry=>entry.path===path);if(entry)void DesignerActions.context(event,entry);}
    };
})();
