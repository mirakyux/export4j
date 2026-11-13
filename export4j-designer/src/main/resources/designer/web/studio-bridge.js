// SPDX-License-Identifier: Apache-2.0
'use strict';
// The React canvas edits the same retained XML and resource bundle as the source workbench.
const DesignerStudioBridge = (() => {
    let queue = Promise.resolve(), sequence=0, synchronized=0, syncing=false, layoutPending=false, failure, session=0;
    let history=[], cursor=-1, localHistory=false, draftBase, historyBoundary;
    let renderedIdentity, failedIdentity, syncFailureIdentity, pendingIdentity;
    let renderedSource='', mappedSource='', mappedPreview='', mappedPages=[], projectedSource='', projectedEntries=[];
    const capture=(value=state)=>({source:value.source,data:value.data,resourceIdentity:value.resourceIdentity,paths:[...selectedPaths],ids:selectedPaths.map(path=>entries.find(entry=>entry.path===path)?.id).filter(Boolean)});
    const identity=()=>{try{const source=propertyDirty?draftSource():$('source').value;return {source:typeof Editor==='undefined'?source:Editor.pageDraftSource(source),data:$('data').value,resourceIdentity:state?.resourceIdentity};}catch{return undefined;}};
    const sameIdentity=(left,right)=>!!left&&!!right&&sameText(left.source,right.source)&&sameText(left.data,right.data)&&left.resourceIdentity===right.resourceIdentity;
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
        if(sameIdentity(before,after))return;
        if(!localHistory){history=[before];cursor=0;localHistory=true;}
        history=history.slice(0,cursor+1);history.push(after);
        if(history.length>101)history.shift();cursor=history.length-1;
    }
    function retainedHistory(value){
        if(!Array.isArray(value.historyResources)||!localHistory)return;
        const retained=new Set(value.historyResources);let dropped=-1;
        for(let index=0;index<=cursor;index++)if(!retained.has(history[index].resourceIdentity))dropped=index;
        if(dropped>=0){history=history.slice(dropped+1);cursor-=dropped+1;historyBoundary='较早资源已超出撤销保留范围';}
        const unavailable=history.findIndex((item,index)=>index>cursor&&!retained.has(item.resourceIdentity));
        if(unavailable>=0)history=history.slice(0,unavailable);
    }
    function accepted(value,options={}){
        if(options.local)return;
        const old=state&&capture();
        if(options.session||options.serverHistory||!state){
            history=[];cursor=-1;localHistory=false;draftBase=undefined;historyBoundary=undefined;
            if(options.session){session++;renderedSource='';mappedSource='';mappedPreview='';mappedPages=[];projectedSource='';projectedEntries=[];layoutPending=false;renderedIdentity=undefined;failedIdentity=undefined;pendingIdentity=undefined;}
        }
        else if(old&&!options.replay)changedHistory(old,capture(value));
        retainedHistory(value);
        synchronized=sequence;failure=undefined;syncFailureIdentity=undefined;
    }
    function remappedPages(source){
        if(!renderedSource||renderedSource===source)return pages;
        if(mappedSource===source&&mappedPreview===previewId)return mappedPages;
        let old,next;try{old=parse(renderedSource);next=parse(source);}catch{return pages;}
        const oldPaths=new Map(),oldIds=new Map(),newIds=new Map(),newNodes=new Map();
        function visit(node,path,paths,ids){paths.set(path,node);const id=node.getAttribute('id');if(id){if(ids.has(id))ids.set(id,null);else ids.set(id,{node,path});}[...node.children].forEach((child,index)=>visit(child,path+'/'+index,paths,ids));}
        visit(old.documentElement,'',oldPaths,oldIds);visit(next.documentElement,'',newNodes,newIds);
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
        const oldSections=[...old.documentElement.children].filter(node=>node.tagName==='section'),newSections=[...next.documentElement.children].filter(node=>node.tagName==='section');
        const retainedSection=node=>{const id=node.getAttribute('id'),before=id&&oldIds.get(id),after=id&&newIds.get(id);return before?.node===node&&newSections.includes(after?.node)?after:null;};
        const belongsToOtherSection=(node,original)=>{const previous=oldIds.get(node.getAttribute('id'))?.node;return oldSections.includes(previous)&&previous!==original;};
        const sectionContent=node=>{const copy=node.cloneNode(true);copy.removeAttribute('id');return copy;};
        function relocateOwner(path){
            // Reflow can give this physical page number to another source section.
            if(path==='')return '';
            const original=oldPaths.get(path);if(!oldSections.includes(original))return null;
            const retained=retainedSection(original);if(retained)return retained.path;
            const owners=new Set();
            for(const child of original.querySelectorAll('[id]')){
                const id=child.getAttribute('id'),anchor=newIds.get(id);
                if(oldIds.get(id)?.node!==child||!anchor)continue;
                const owner=anchor.node.closest('section');
                if(!newSections.includes(owner)||belongsToOtherSection(owner,original))return null;
                owners.add(owner);
            }
            if(owners.size)return owners.size===1?pathOf([...owners][0]):null;
            const content=sectionContent(original);
            const previous=oldSections.filter(node=>!retainedSection(node)&&sectionContent(node).isEqualNode(content));
            const candidates=newSections.filter(node=>!belongsToOtherSection(node,original)&&sectionContent(node).isEqualNode(content));
            return previous.length===1&&candidates.length===1?pathOf(candidates[0]):null;
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
            return {...page,ownerPath:relocateOwner(page.ownerPath),hits};
        });
        mappedSource=source;mappedPreview=previewId;return mappedPages;
    }
    async function pageOwner(page){
        if(!Number.isInteger(page)||page<0)throw new Error('页面已变化，请重新选择。');
        const owner=DesignerStudioBridge.snapshot().pages[page]?.ownerPath;
        if(owner==null){
            await preview();
            const current=DesignerStudioBridge.snapshot();
            if(current.layoutState!=='synced')throw new Error('页面归属尚未确认，请完成预览后重试。');
            throw new Error('页面归属无法确认，请在最新预览中重新选择。');
        }
        if(!entries.some(entry=>entry.path===owner&&['document','section'].includes(entry.tag)))throw new Error('页面已变化，请重新选择。');
        return owner;
    }
    function scheduleSync(source,data,restore){
        const version=++sequence;syncing=true;layoutPending=true;failure=undefined;syncFailureIdentity=undefined;
        pendingIdentity={source,data,resourceIdentity:restore?.resourceIdentity??state.resourceIdentity};
        clearTimeout(previewTimer);++previewGeneration;
        $('preview-status').textContent='修改已显示 · 正在校验精确版式';
        const next=queue.catch(()=>{}).then(async()=>{
            if(version!==sequence)return;
            try{
                const value=await request(restore?'restore':'apply',{source,data,...(restore?{resourceIdentity:restore.resourceIdentity}:{})});
                Object.assign(state,{revision:value.revision,canUndo:value.canUndo,canRedo:value.canRedo,historyResources:value.historyResources});
                retainedHistory(value);
                if(version===sequence){
                    synchronized=version;failure=undefined;
                    if(DesignerStudio.shouldPreview())schedulePreview();
                    else {layoutPending=false;$('preview-status').textContent='便捷编辑 · 完成后校验精确版式';}
                }
            }catch(error){if(version===sequence){failure=error;syncFailureIdentity={source,data,resourceIdentity:restore?.resourceIdentity??state.resourceIdentity};layoutPending=false;status('精确校验未通过：'+error.message,true);diagnostics({error:error.message});$('preview-status').textContent='修改已保留 · 请修正后重试';}}
            finally{if(version===sequence){syncing=false;refreshButtons();DesignerStudio.sync();}}
        });
        queue=next;DesignerStudio.sync();
    }
    function apply(source,ids,paths){
        try{
            const document=parse(source),data=$('data').value,before=draftBase||capture();draftBase=undefined;
            const after={source,data,resourceIdentity:state.resourceIdentity,paths:paths||[...selectedPaths],ids:ids||before.ids};
            changedHistory(before,after);
            install({...state,source,data,outline:outline(document.documentElement)},true,{local:true});selection(ids,paths);
            if(synchronized!==sequence||source!==before.source||data!==before.data||failure)scheduleSync(source,data);
            return Promise.resolve();
        }catch(error){$('source').value=source;markDirty();return Promise.reject(error);}
    }
    function editingEnded(){
        const current=identity();
        if(!DesignerStudio.shouldPreview()||busy||syncing||synchronized!==sequence||failure||!current
                ||!sameText(current.source,state.source)||!sameText(current.data,state.data)
                ||sameIdentity(renderedIdentity,current)||sameIdentity(failedIdentity,current)||sameIdentity(syncFailureIdentity,current)
                ||layoutPending&&sameIdentity(pendingIdentity,current))return;
        // Server acceptance may have deferred the preview while the rich editor was still open.
        pendingIdentity=current;layoutPending=true;schedulePreview();DesignerStudio.sync();
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
        const value=history[index];
        if(value.resourceIdentity!==state.resourceIdentity){
            await perform(async()=>{
                const target=history.indexOf(value);if(target<0)throw new Error('此资源已超出撤销保留范围。');
                const restored=await request('restore',{source:value.source,data:value.data,resourceIdentity:value.resourceIdentity});
                cursor=target;draftBase=undefined;install(restored,true,{replay:true});selection(value.ids,value.paths);schedulePreview();
            });return;
        }
        cursor=index;draftBase=undefined;
        install({...state,source:value.source,data:value.data,outline:outline(parse(value.source).documentElement)},true,{local:true});selection(value.ids,value.paths);
        scheduleSync(value.source,value.data,value);
    }
    const sourcePath=node=>node.parentElement?sourcePath(node.parentElement)+'/'+[...node.parentElement.children].indexOf(node):'';
    function paragraphOwner(node){
        if(!node)return false;
        if(node.tagName==='columns'&&['table','rows'].includes(node.parentElement?.tagName))return false;
        if(node.tagName==='header'&&['table','rows'].includes(node.parentElement?.tagName))return false;
        if(['each','then','else'].includes(node.tagName)){
            let parent=node.parentElement;
            while(parent&&['each','if','then','else'].includes(parent.tagName))parent=parent.parentElement;
            return paragraphOwner(parent);
        }
        return ['document','section','header','footer','columns','item','cell','box'].includes(node.tagName);
    }
    function imagePlacement(document,path){
        const wanted=path===undefined?selected?.path:path;
        if(wanted!==undefined&&!/^(?:\/(?:0|[1-9][0-9]*))*$/.test(wanted))throw new Error('粘贴位置已变化，请重新选择正文。');
        let anchor=document.documentElement;
        if(wanted)for(const index of wanted.slice(1).split('/'))anchor=anchor?.children[Number(index)];
        if(!anchor)throw new Error('粘贴位置已变化，请重新选择正文。');
        while(['span','link','image','page-number','page-count','page-reference','number-reference','note-marker'].includes(anchor.tagName)&&anchor.parentElement)anchor=anchor.parentElement;
        if(anchor.tagName==='cell'){
            const blocks=[...anchor.children].some(child=>['p','table'].includes(child.tagName));
            if(!blocks&&[...anchor.children].some(child=>child.tagName==='style-if'))throw new Error('此格包含条件样式，不能转换为块内容；原文和规则已保留。请先调整条件规则。');
            if(!blocks&&anchor.parentElement?.tagName==='header')throw new Error('表头单元格不支持块图片粘贴，请在节点 XML 中添加行内图片。');
            return {anchor,owner:anchor,convertCell:!blocks};
        }
        if(paragraphOwner(anchor))return {anchor,owner:anchor};
        const flowTags=new Set(['p','heading','table','rows','bullet','numbered','caption','list','each','if','page-break','cross-reference','columns','grid']);
        if(flowTags.has(anchor.tagName)&&paragraphOwner(anchor.parentElement))return {anchor,owner:anchor.parentElement,after:anchor};
        throw new Error('当前位置不支持图片段落，请先选择正文、表格单元格或栅格项中的内容。');
    }
    function checkedImageBounds(width,height){
        if(!Number.isFinite(width)||!Number.isFinite(height)||width<=0||height<14)throw new Error('当前容器没有足够的图片空间，原文和资源未修改。请先增大页区或调整已有内容。');
        return {width,height};
    }
    async function pasteImage(file,page,x,y,path,checkContext=()=>{}){
        let cancelled=false;
        const cancel=()=>{cancelled=true;},check=()=>{checkContext();if(cancelled)throw new Error('已取消图片粘贴，原文和资源未修改。');};
        $('cancel').addEventListener('click',cancel);
        try{
            await drafts();
            if(DesignerStudioBridge.snapshot().layoutState!=='synced')await preview();
            check();
            const document=parse(state.source),placement=imagePlacement(document,path);
            const name='pasted_'+crypto.randomUUID().replaceAll('-',''),id='image-'+crypto.randomUUID();
            const paragraph=document.createElement('p');paragraph.setAttribute('id',id);paragraph.setAttribute('line-height','18');
            for(const [key,value] of Object.entries({size:12,'space-before':0,'space-after':0}))paragraph.setAttribute(key,String(value));
            if(placement.convertCell){
                const previous=document.createElement('p');
                for(const attribute of ['size','line-height','role','color','align','line-break'])if(placement.owner.hasAttribute(attribute))previous.setAttribute(attribute,placement.owner.getAttribute(attribute));
                previous.append(...placement.owner.childNodes);placement.owner.append(previous);
            }
            if(placement.after)placement.after.after(paragraph);else placement.owner.append(paragraph);
            if(placement.owner.tagName==='box')placement.owner.setAttribute('height-mode','AUTO');
            const space=await request('image-space',{source:new XMLSerializer().serializeToString(document),data:state.data,marker:id});check();
            const bounds=checkedImageBounds(space.width-4,space.height-4);
            const size=await createImageBitmap(file);let width,height;
            try{const factor=Math.min(1,bounds.width/(size.width*72/96),bounds.height/(size.height*72/96));width=Math.floor(size.width*72/96*factor*1e6)/1e6;height=Math.floor(size.height*72/96*factor*1e6)/1e6;}
            finally{size.close();}
            paragraph.setAttribute('line-height',String(Math.max(18,height+4)));
            const image=document.createElement('image');for(const [key,value] of Object.entries({ref:name,width,height,alt:file.name||'粘贴的图片'}))image.setAttribute(key,String(value));paragraph.append(image);
            // Resource registration and its referencing source form one server history entry.
            const encoded=await bytes(file);check();let accepted;
            try{accepted=await request('resource',{kind:'image',name,bytes:encoded,source:new XMLSerializer().serializeToString(document),data:state.data,validateLayout:true});}
            catch(error){throw new Error('图片未插入，原文和资源未修改：'+error.message,{cause:error});}
            install(accepted);
            selection([id]);await preview();
        }finally{$('cancel').removeEventListener('click',cancel);}
    }
    return {
        accepted,settled,pageOwner,
        previewing:value=>{if(sameIdentity(value,identity())){layoutPending=true;pendingIdentity=value;failedIdentity=undefined;DesignerStudio.sync();}},
        previewed:(source,value=identity())=>{if(!sameIdentity(value,identity()))return false;renderedSource=source;renderedIdentity=value;failedIdentity=undefined;syncFailureIdentity=undefined;mappedSource='';layoutPending=false;return true;},
        previewFailed:(value=identity())=>{if(!sameIdentity(value,identity()))return false;failedIdentity=value;layoutPending=false;DesignerStudio.sync();return true;},
        snapshot:()=>{const source=propertyDirty?draftSource():$('source').value;
            if(source!==state?.source&&source!==projectedSource){
                projectedSource=source;projectedEntries=[];
                try{const flatten=(entry,depth=0)=>{projectedEntries.push({...entry,depth});entry.children.forEach(child=>flatten(child,depth+1));};flatten(outline(parse(source).documentElement));}catch{projectedEntries=entries;}
            }
            const current=identity(),pending=syncing||layoutPending&&sameIdentity(pendingIdentity,current);
            const hasDraft=!!state&&(!sameText(source,state.source)||!sameText($('data').value,state.data));
            return {source,data:$('data').value,resourceIdentity:state?.resourceIdentity,fontIdentity:state?.fontIdentity,revision:state?.revision||0,layoutState:sameIdentity(syncFailureIdentity,current)||sameIdentity(failedIdentity,current)?'failed':pending?'pending':sameIdentity(renderedIdentity,current)?'synced':'stale',historyBoundary:cursor===0?historyBoundary:undefined,
            selectedPaths:[...selectedPaths],entries:source===state?.source?entries:projectedEntries,pages:remappedPages(source),currentPage,busy,formats:previewFormats,session,pending,
            canUndo:hasDraft||(localHistory?cursor>0:!!state?.canUndo),canRedo:!hasDraft&&(localHistory?cursor<history.length-1:!!state?.canRedo)};},
        choose:async(path,event,groupLists=false)=>{
            if(propertyDirty)await applyProperties();
            if(sourceChanged()||dataChanged())await apply($('source').value);
            const entry=entries.find(entry=>entry.path===path);if(entry)await DesignerCanvas.selectEntry(entry,event,groupLists);
        },
        select:navigate,
        draft:source=>{draftBase??=capture();propertyDirty=false;nodeXmlDirty=false;$('source').value=source;markDirty();if(DesignerStudio.shouldPreview())schedulePreview();else {$('preview-status').textContent='便捷编辑 · 完成后校验精确版式';clearTimeout(previewTimer);++previewGeneration;}DesignerStudio.sync();},
        apply,flush,editingEnded,history:historyStep,preview:()=>schedulePreview(),
        paste:(file,page,x,y,path)=>{
            const context=DesignerStudio.canvasContext(),check=()=>{
                const current=DesignerStudioBridge.snapshot();
                if(current.source!==context.source||current.data!==context.data||current.resourceIdentity!==context.resourceIdentity||current.session!==context.session)throw new Error('粘贴位置的内容已变化，请重新选择位置后重试。');
            };
            return perform(()=>{check();return pasteImage(file,page,x,y,path,check);});
        },
        font:async name=>{const response=await fetch('/api/font?name='+encodeURIComponent(name),{headers:{'X-Designer-Token':token}});if(!response.ok)throw new Error('字体资源不可用：'+name);return response.arrayBuffer();},
        error:message=>{status(message,true);diagnostics({error:message});},
        context:(event,path)=>{const entry=entries.find(entry=>entry.path===path);if(entry)void DesignerActions.context(event,entry);}
    };
})();
