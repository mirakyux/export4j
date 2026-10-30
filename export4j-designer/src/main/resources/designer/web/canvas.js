// SPDX-License-Identifier: Apache-2.0
'use strict';
const DesignerCanvas = (() => {
    let observer, generation=0, pending=[], loading=false, sheets=[], urls=new Map(), loadedPreview=new Map(), pointer, gesture, drop, frame;
    const host=()=>$('pages'), scroller=()=>document.querySelector('.canvas-scroll');
    const entryFor=(id,path)=>path?entries.find(entry=>entry.path===path):entries.find(entry=>entry.id&&entry.id===id.split('@')[0]);
    function sortable(entry){let value=entry,parent;while((parent=entries.find(item=>item.path===value.path.slice(0,value.path.lastIndexOf('/'))))&&['each','list','then','else','if'].includes(parent.tag))value=parent;return value;}
    function clear() {
        generation++;observer?.disconnect();pending=[];sheets=[];pointer=undefined;gesture=undefined;resetDrop();
        urls.forEach(url=>URL.revokeObjectURL(url));urls.clear();loadedPreview.clear();host().replaceChildren();
    }
    function activate(index) {
        currentPage=index;$('page-number').textContent=`${index+1} / ${pages.length}`;$('page-jump').value=String(index);refreshButtons();
    }
    function zoom() {
        const value=$('zoom').value;
        sheets.forEach((sheet,index)=>{sheet.style.width=pages[index].width*96/72*(value==='fit'?1:Number(value))+'px';sheet.style.maxWidth=value==='fit'?'100%':'none';});
    }
    function scrollTo(index) { if(sheets[index]){sheets[index].scrollIntoView({block:'start',behavior:'instant'});activate(index);} }
    function reveal(entry,pageIndex) {
        const scope=pageIndex===undefined?host():sheets[pageIndex];
        const hit=[...scope?.querySelectorAll('.hit')||[]].find(hit=>hit.dataset.path===entry.path||['each','list','if','then','else'].includes(entry.tag)&&hit.dataset.path?.startsWith(entry.path+'/'));
        hit?.scrollIntoView({block:'center',behavior:'instant'});
        if(hit&&pageIndex!==undefined)activate(pageIndex);
    }
    function listEntries(entry) {
        if(!['bullet','numbered'].includes(entry.tag))return [entry];
        const parent=entry.path.slice(0,entry.path.lastIndexOf('/')),siblings=entries.filter(item=>item.path.slice(0,item.path.lastIndexOf('/'))===parent&&item.depth===entry.depth);
        const index=siblings.findIndex(item=>item.path===entry.path),source=nodeAt(entry.path),listId=source.getAttribute('list-id');
        const same=item=>item.tag===entry.tag&&(entry.tag!=='numbered'||nodeAt(item.path).getAttribute('list-id')===listId);
        let start=index,end=index;while(start>0&&same(siblings[start-1]))start--;while(end+1<siblings.length&&same(siblings[end+1]))end++;
        return siblings.slice(start,end+1);
    }
    function highlight() {
        host().querySelectorAll('.list-selection').forEach(node=>node.remove());
        const selectedSet=new Set(selectedPaths),groups=new Map();
        host().querySelectorAll('.hit').forEach(hit=>{
            const path=hit.dataset.path,entry=entries.find(item=>item.path===path),selectedHit=selectedSet.has(path)||selectedPaths.some(value=>{
                const parent=entries.find(item=>item.path===value);return ['each','list','if','then','else'].includes(parent?.tag)&&path?.startsWith(value+'/');
            });
            const list=selectedHit&&['bullet','numbered'].includes(entry?.tag);
            hit.classList.toggle('selected',selectedHit&&!list);
            hit.classList.toggle('list-member',!!list);
            if(list){const layer=hit.parentElement,key=layer.closest('.canvas-sheet').dataset.page+':'+listEntries(entry)[0].path,group=groups.get(key)||[];group.push(hit);groups.set(key,group);}
        });
        groups.forEach(hits=>{
            const layer=hits[0].parentElement,rects=hits.map(hit=>({x:parseFloat(hit.style.left),y:parseFloat(hit.style.top),right:parseFloat(hit.style.left)+parseFloat(hit.style.width),bottom:parseFloat(hit.style.top)+parseFloat(hit.style.height)}));
            const left=Math.min(...rects.map(rect=>rect.x)),top=Math.min(...rects.map(rect=>rect.y)),right=Math.max(...rects.map(rect=>rect.right)),bottom=Math.max(...rects.map(rect=>rect.bottom));
            const outline=document.createElement('div');outline.className='list-selection';Object.assign(outline.style,{left:left+'%',top:top+'%',width:right-left+'%',height:bottom-top+'%'});layer.append(outline);
        });
    }
    async function loadNext() {
        if(loading)return;loading=true;
        try {
            while(pending.length){
                const job=pending.shift(),stamp=generation,revision=state.revision,desired=previewId;
                if(!job.sheet.isConnected||loadedPreview.get(job.index)===desired)continue;
                try{
                    const blob=await request(`page?index=${job.index}&revision=${revision}&preview=${desired}`,undefined,true);
                    if(stamp!==generation||revision!==state.revision||desired!==previewId)continue;
                    const url=URL.createObjectURL(blob),decoded=new Image();decoded.src=url;
                    try{await decoded.decode();}catch(error){URL.revokeObjectURL(url);throw error;}
                    if(stamp!==generation||revision!==state.revision||desired!==previewId){URL.revokeObjectURL(url);continue;}
                    const old=urls.get(job.index);job.image.src=url;urls.set(job.index,url);loadedPreview.set(job.index,desired);
                    job.sheet.classList.add('loaded');job.sheet.classList.remove('load-error');job.label.hidden=true;
                    if(old)URL.revokeObjectURL(old);
                }catch(error){if(stamp===generation){if(!urls.has(job.index)){job.label.textContent='预览加载失败：'+error.message;job.sheet.classList.add('load-error');}else status('预览图片更新失败：'+error.message,true);}}
            }
        }finally{loading=false;}
    }
    function render() {
        const previousScroll=scroller().scrollTop;generation++;observer?.disconnect();pending=[];pointer=undefined;gesture=undefined;resetDrop();
        $('empty-preview').hidden=pages.length>0;$('page-jump').replaceChildren();
        observer=new IntersectionObserver(changes=>{
            changes.forEach(change=>{
                const index=Number(change.target.dataset.page),sheet=change.target;
                if(change.isIntersecting){
                    if(loadedPreview.get(index)!==previewId&&!pending.some(item=>item.index===index))pending.push({index,sheet,image:sheet.querySelector('.page-image'),label:sheet.querySelector('.page-loading')});
                }else if(urls.has(index)){
                    URL.revokeObjectURL(urls.get(index));urls.delete(index);loadedPreview.delete(index);sheet.querySelector('.page-image').removeAttribute('src');sheet.classList.remove('loaded');sheet.querySelector('.page-loading').hidden=false;
                }
            });loadNext();
        },{root:scroller(),rootMargin:'600px 0px'});
        pages.forEach((model,index)=>{
            const option=document.createElement('option');option.value=index;option.textContent='第 '+(index+1)+' 页';$('page-jump').append(option);
            const sheet=sheets[index]||document.createElement('article');sheet.className='canvas-sheet';sheet.dataset.page=index;sheet.setAttribute('aria-label','第 '+(index+1)+' 页');
            let label=sheet.querySelector('.sheet-label');if(!label){label=document.createElement('div');label.className='sheet-label';}
            label.textContent=`第 ${index+1} 页 / 共 ${pages.length} 页 · ${Math.round(model.width)} × ${Math.round(model.height)} pt`;
            let boundary=sheet.querySelector('.page-boundary');if(!boundary){boundary=document.createElement('div');boundary.className='page-boundary';}boundary.replaceChildren();
            if(index>0)boundary.textContent=model.breaks.length?'':'自动分页 / 分节边界';
            model.breaks.forEach(item=>{const marker=document.createElement('button');marker.className='break-marker';marker.textContent='↵ 显式分页符';marker.title='选择源模板中的换页节点；此标记不会导出。';marker.setAttribute('aria-label','选择分页符 '+item.id);marker.onclick=async()=>{const entry=entryFor(item.id,item.path);if(entry){await choose(entry);document.querySelector(`.tree-item[data-path="${entry.path}"]`)?.scrollIntoView({block:'nearest'});}};boundary.append(marker);});
            let paper=sheet.querySelector('.page');if(!paper){paper=document.createElement('div');paper.className='page';}
            paper.oncontextmenu=event=>DesignerActions.blankContext(event,blankPlacement(index,event.clientY));
            paper.style.aspectRatio=`${model.width} / ${model.height}`;
            let image=paper.querySelector('.page-image');if(!image){image=document.createElement('img');image.className='page-image';image.draggable=false;}
            image.alt=`第 ${index+1} 页 · Export4j 引擎预览`;
            let loadingLabel=paper.querySelector('.page-loading');if(!loadingLabel){loadingLabel=document.createElement('div');loadingLabel.className='page-loading';}
            loadingLabel.textContent='正在加载第 '+(index+1)+' 页…';loadingLabel.hidden=urls.has(index);
            let layer=paper.querySelector('.hit-layer');if(!layer){layer=document.createElement('div');layer.className='hit-layer';}layer.replaceChildren();
            if(!paper.isConnected)paper.append(image,loadingLabel,layer);
            model.hits.forEach(hit=>{
                const entry=entryFor(hit.id,hit.path),node=document.createElement('div');node.className='hit';node.dataset.id=hit.id;
                Object.assign(node.style,{left:hit.x/model.width*100+'%',top:hit.y/model.height*100+'%',width:hit.width/model.width*100+'%',height:hit.height/model.height*100+'%'});
                node.setAttribute('role','button');node.tabIndex=0;node.setAttribute('aria-label','选择文档元素 '+hit.id);
                node.title=entry?`${entry.label}；拖动左上角手柄排序，双击编辑`:'此内容由模板展开；请在结构树编辑对应模板节点。';
                const select=async event=>{if(entry){const parent=entries.find(item=>item.path===entry.path.slice(0,entry.path.lastIndexOf('/')));const target=parent?.tag==='list'&&!event?.ctrlKey&&!event?.metaKey&&!event?.shiftKey?parent:entry;const group=target===entry&&!(event?.ctrlKey||event?.metaKey||event?.shiftKey)?listEntries(entry):undefined;await choose(target,event,group);const tree=[...document.querySelectorAll('.tree-item')].find(item=>item.dataset.path===target.path);tree?.scrollIntoView({block:'nearest'});}};
                node.onclick=select;node.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();select(event);}};
                node.ondblclick=async event=>{if(entry){await choose(entry,event);DesignerActions.editBody();}};
                node.oncontextmenu=event=>{if(entry)DesignerActions.context(event,entry);};
                if(entry){
                    const movable=sortable(entry);node.dataset.path=entry.path;node.dataset.sortPath=movable.path;
                    const handle=document.createElement('button');handle.className='canvas-grip';handle.textContent='⠿';handle.title=movable.path===entry.path?'拖动排序（随正文流排版）':'拖动整个循环／条件模板；循环内数据顺序由绑定数组决定。';handle.setAttribute('aria-label','拖动元素 '+hit.id);bindDrag(handle,movable);node.append(handle);
                    const source=nodeAt(entry.path),images=source.querySelectorAll('image');
                    if(source.tagName==='p'&&source.children.length===1&&images.length===1&&!source.textContent.trim()){
                        const imageNode=images[0];
                        if(hit.images.length===1&&/^\d+(\.\d+)?$/.test(imageNode.getAttribute('width')||'')&&/^\d+(\.\d+)?$/.test(imageNode.getAttribute('height')||'')){
                            const bounds=hit.images[0];Object.assign(node.style,{left:bounds.x/model.width*100+'%',top:bounds.y/model.height*100+'%',width:bounds.width/model.width*100+'%',height:bounds.height/model.height*100+'%'});
                            const resize=document.createElement('button');resize.className='image-resize';resize.setAttribute('aria-label','调整图片大小 '+hit.id);resize.title='拖动等比缩放图片；图片和行高一同写回模板。';
                            resize.onpointerdown=event=>resizeImage(event,node,entry,source,imageNode,model,paper);node.append(resize);
                        }
                    }
                }
                layer.append(node);
            });
            if(!sheet.isConnected){sheet.append(boundary,label,paper);host().append(sheet);sheets[index]=sheet;}
            observer.observe(sheet);
        });
        for(let index=pages.length;index<sheets.length;index++){sheets[index].remove();if(urls.has(index))URL.revokeObjectURL(urls.get(index));urls.delete(index);loadedPreview.delete(index);}
        sheets.length=pages.length;
        zoom();highlight();scroller().scrollTop=previousScroll;if(pages.length)activate(Math.min(currentPage,pages.length-1));
    }
    function blankPlacement(index,y){
        const sheet=sheets[index],body=[...(sheet?.querySelectorAll('.hit[data-path]')||[])].map(hit=>{
            const entry=entries.find(item=>item.path===hit.dataset.sortPath||item.path===hit.dataset.path);
            const parent=entries.find(item=>item.path===entry?.path.slice(0,entry.path.lastIndexOf('/')));
            return entry&&['document','section'].includes(parent?.tag)&&!['page','header','footer','background','foreground','navigation','notes'].includes(entry.tag)?{entry,rect:hit.getBoundingClientRect()}:undefined;
        }).filter(Boolean);
        if(body.length){const nearest=body.reduce((left,right)=>Math.abs(left.rect.top+left.rect.height/2-y)<=Math.abs(right.rect.top+right.rect.height/2-y)?left:right);return {path:nearest.entry.path,after:y>=nearest.rect.top+nearest.rect.height/2};}
        const section=entries.find(item=>item.tag==='section'&&(selected?.path===item.path||selected?.path.startsWith(item.path+'/')))||entries.find(item=>item.tag==='section');
        return {path:section?.path??'',container:true};
    }
    function editable() { return !busy&&!propertyDirty&&!sourceChanged()&&!dataChanged(); }
    function bindDrag(element,payload) {
        element.draggable=false;element.classList.add('draggable-control');let suppressClick=false;
        element.addEventListener('pointerdown',event=>{
            if(event.button!==0||event.target.closest('.tree-chevron'))return;suppressClick=false;
            const active=payload.path&&selectedPaths.length>1&&selectedPaths.includes(payload.path)?{...payload,paths:selectedPaths.slice()}:payload;
            gesture={id:event.pointerId,x:event.clientX,y:event.clientY,payload:active,element,moved:false,suppress:()=>{suppressClick=true;}};
            element.setPointerCapture(event.pointerId);
        });
        element.addEventListener('click',event=>{if(suppressClick){event.preventDefault();event.stopImmediatePropagation();suppressClick=false;}},true);
        element.addEventListener('dragstart',event=>event.preventDefault());
    }
    function resetDrop(){document.querySelectorAll('.drop-before,.drop-after').forEach(node=>node.classList.remove('drop-before','drop-after'));document.body.classList.remove('canvas-dragging');drop=undefined;}
    function targetAt(event) {
        const under=document.elementFromPoint(event.clientX,event.clientY);
        let hit=under?.closest('.tree-item,.hit[data-path]');
        if(!hit){const paper=under?.closest('.page');if(!paper)return;const candidates=[...paper.querySelectorAll('.hit[data-path]')];hit=candidates.sort((a,b)=>Math.abs(a.getBoundingClientRect().top-event.clientY)-Math.abs(b.getBoundingClientRect().top-event.clientY))[0];}
        if(!hit)return;const entry=entries.find(item=>item.path===(hit.dataset.sortPath||hit.dataset.path)),rect=hit.getBoundingClientRect();
        if(!entry?.path)return;
        if(dragging?.path&&(entry.path===dragging.path||dragging.paths?.includes(entry.path)||entry.path.slice(0,entry.path.lastIndexOf('/'))!==dragging.path.slice(0,dragging.path.lastIndexOf('/'))))return;
        return {hit,entry,after:event.clientY>rect.top+rect.height/2};
    }
    async function reorder(from,target,after){
        if(from.paths?.length>1){await edit({operation:'batch-relocate',paths:from.paths,target:target.path,after});return;}
        const parent=from.path.slice(0,from.path.lastIndexOf('/')),start=Number(from.path.split('/').at(-1)),end=Number(target.path.split('/').at(-1));
        const index=end+(after?1:0)-(start<end?1:0);
        await edit({operation:'relocate',path:from.path,target:target.path,after});
        const moved=entries.find(entry=>entry.path===parent+'/'+index);if(moved){await choose(moved);document.querySelector(`.tree-item[data-path="${moved.path}"]`)?.scrollIntoView({block:'nearest'});DesignerCanvas.reveal(moved);}
    }
    function resizeImage(event,node,entry,source,imageNode,model,paper){
        event.preventDefault();event.stopPropagation();if(!editable())return;
        const width=Number(imageNode.getAttribute('width')),height=Number(imageNode.getAttribute('height')),scale=paper.getBoundingClientRect().width/model.width;
        pointer={id:event.pointerId,x:event.clientX,y:event.clientY,width,height,scale,node,entry,source,imageNode,pageWidth:model.width,pageHeight:model.height,originalWidth:node.style.width,originalHeight:node.style.height,revision:state.revision};
        event.target.setPointerCapture(event.pointerId);node.classList.add('resizing');
    }
    function start(){
        document.querySelectorAll('[data-insert]').forEach(button=>{bindDrag(button,{tag:button.dataset.insert});button.title=button.dataset.insert==='section'?'选中顶层正文后从此处新建分节。':'点击插入，或拖到画布中的插入线。';});
        host().addEventListener('dragover',event=>{if(!dragging||!editable())return;const target=targetAt(event);resetDrop();if(target){event.preventDefault();drop=target;target.hit.classList.add(target.after?'drop-after':'drop-before');}});
        host().addEventListener('drop',event=>{event.preventDefault();const target=drop,from=dragging;dragging=undefined;resetDrop();if(!target||!from)return;
            run(async()=>{if(from.path){await reorder(from,target.entry,target.after);}else await insert(from.tag,{path:target.entry.path,after:target.after},from.imageName);});
        });
        scroller().addEventListener('scroll',()=>{cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{if(!sheets.length)return;const top=scroller().getBoundingClientRect().top+90;let index=0;sheets.forEach((sheet,n)=>{if(sheet.getBoundingClientRect().top<=top)index=n;});if(index!==currentPage)activate(index);});});
        scroller().addEventListener('contextmenu',event=>{if(event.target.closest('.page,.hit,.break-marker'))return;DesignerActions.blankContext(event,blankPlacement(currentPage,event.clientY));});
        $('page-jump').onchange=()=>scrollTo(Number($('page-jump').value));
        document.addEventListener('pointermove',event=>{
            if(!gesture||event.pointerId!==gesture.id)return;
            if(!gesture.moved&&Math.hypot(event.clientX-gesture.x,event.clientY-gesture.y)<5)return;
            gesture.moved=true;gesture.suppress();event.preventDefault();
            if(!editable()){status('请先完成当前编辑，再拖动。',true);return;}
            dragging=gesture.payload;const target=targetAt(event);resetDrop();
            document.body.classList.add('canvas-dragging');
            if(target){drop=target;target.hit.classList.add(target.after?'drop-after':'drop-before');}
            const scrollHost=document.elementFromPoint(event.clientX,event.clientY)?.closest('#tree')||scroller(),area=scrollHost.getBoundingClientRect();if(event.clientX>=area.left&&event.clientX<=area.right){if(event.clientY>area.bottom-28)scrollHost.scrollTop+=24;else if(event.clientY<area.top+28)scrollHost.scrollTop-=24;}
        });
        document.addEventListener('pointerup',event=>{
            if(!gesture||event.pointerId!==gesture.id)return;
            const value=gesture,target=drop;gesture=undefined;dragging=undefined;resetDrop();
            if(!value.moved)return;event.preventDefault();value.suppress();
            if(!target||!editable())return;
            run(async()=>{if(value.payload.path)await reorder(value.payload,target.entry,target.after);else await insert(value.payload.tag,{path:target.entry.path,after:target.after},value.payload.imageName);});
        });
        document.addEventListener('pointermove',event=>{if(!pointer||event.pointerId!==pointer.id)return;const factor=Math.max(.1,Math.min(8,1+(event.clientX-pointer.x)/pointer.scale/pointer.width));pointer.factor=factor;pointer.node.style.width=pointer.width*factor/pointer.pageWidth*100+'%';pointer.node.style.height=pointer.height*factor/pointer.pageHeight*100+'%';pointer.node.title=`${(pointer.width*factor).toFixed(1)} × ${(pointer.height*factor).toFixed(1)} pt`;});
        document.addEventListener('pointerup',event=>{if(!pointer||event.pointerId!==pointer.id)return;const value=pointer;pointer=undefined;value.node.classList.remove('resizing');if(!value.factor||value.revision!==state.revision)return;
            run(async()=>{const width=+(value.width*value.factor).toFixed(2),height=+(value.height*value.factor).toFixed(2);value.imageNode.setAttribute('width',width);value.imageNode.setAttribute('height',height);value.source.setAttribute('line-height',String(Math.max(Number(value.source.getAttribute('line-height')||20),height+10)));await edit({operation:'replace',path:value.entry.path,xml:new XMLSerializer().serializeToString(value.source)});});
        });
        document.addEventListener('pointercancel',()=>{if(pointer){pointer.node.style.width=pointer.originalWidth;pointer.node.style.height=pointer.originalHeight;pointer.node.classList.remove('resizing');}pointer=undefined;if(gesture?.moved)gesture.suppress();gesture=undefined;dragging=undefined;resetDrop();highlight();});
        new ResizeObserver(()=>zoom()).observe(scroller());
    }
    return {start,render,clear,zoom,scrollTo,highlight,reveal,bindDrag};
})();

const DesignerAssets=(()=>{
    let generation=0,cache=new Map(),items=[],chooseImage,observers=new Set();
    function install(images){generation++;observers.forEach(observer=>observer.disconnect());observers.clear();cache.forEach(value=>value.then(url=>{if(url)URL.revokeObjectURL(url);},()=>{}));cache.clear();items=images;const host=$('images-list');host.replaceChildren();
        if(!images.length){host.textContent='暂无图片，请先添加 PNG / JPEG。';return;}
        images.forEach(image=>host.append(card(image,()=>run(()=>insert('image',undefined,image.name)))));
    }
    async function url(name){if(!items.some(image=>image.name===name))return;const stamp=generation;
        if(!cache.has(name))cache.set(name,request(`image?name=${encodeURIComponent(name)}&revision=${state.revision}`,undefined,true).then(blob=>{if(stamp!==generation)return;return URL.createObjectURL(blob);}));
        return cache.get(name);
    }
    function card(image,action){const button=document.createElement('button');button.className='image-card';button.type='button';button.title='点击选择图片；可拖入画布';button.setAttribute('aria-label','图片资源 '+image.name);
        const preview=document.createElement('img');preview.alt=image.name+' 图片预览';preview.draggable=false;preview.loading='lazy';preview.width=42;preview.height=42;
        const caption=document.createElement('span');caption.textContent=image.name;const size=document.createElement('small');size.textContent=`${image.width} × ${image.height} px`;button.append(preview,caption,size);button.onclick=action;
        const stamp=generation,watcher=new IntersectionObserver((changes,observer)=>{if(changes.some(change=>change.isIntersecting)){observer.disconnect();observers.delete(observer);url(image.name).then(value=>{if(stamp===generation&&value)preview.src=value;}).catch(error=>{preview.alt='预览失败：'+error.message;});}});observers.add(watcher);watcher.observe(preview);
        DesignerCanvas.bindDrag(button,{tag:'image',imageName:image.name});return button;
    }
    function preview(name,host){const stamp=generation;host.replaceChildren();if(!items.some(image=>image.name===name)){host.textContent='动态引用或尚未导入的图片；绑定后以实际预览为准。';return;}
        const image=document.createElement('img');image.alt=name+' 当前图片预览';host.append(image);url(name).then(value=>{if(stamp===generation&&value&&image.isConnected)image.src=value;}).catch(error=>{host.textContent='图片预览失败：'+error.message;});
    }
    function pick(){let dialog=$('image-picker');if(!dialog){dialog=document.createElement('dialog');dialog.id='image-picker';dialog.innerHTML='<div class="dialog-heading"><h2>选择图片资源</h2><button aria-label="关闭图片选择">×</button></div><p>点击缩略图插入。需要新图片时，请在左侧项目资源中添加。</p><div class="image-grid"></div>';document.body.append(dialog);dialog.querySelector('button').onclick=()=>{dialog.close();chooseImage?.(null);};dialog.oncancel=()=>chooseImage?.(null);}
        const grid=dialog.querySelector('.image-grid');grid.replaceChildren();items.forEach(image=>grid.append(card(image,()=>{dialog.close();chooseImage(image.name);})));if(!items.length)grid.textContent='暂无图片，请在左侧项目资源中添加。';dialog.showModal();return new Promise(resolve=>chooseImage=resolve);
    }
    return {install,preview,pick};
})();
