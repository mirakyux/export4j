<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { INSERTABLE_NODES, canPlaceNode } from './playground-tree.mjs'

type Position = 'before' | 'after' | 'inside'
type TreeNode = {
  id: string; tag: string; parentId: string | null; children: TreeNode[]; depth: number
  start: number; end: number; label?: string; text?: string; draggable?: boolean
}
type TemplateTree = { root: TreeNode; nodes: TreeNode[] }
type DragItem = { type: 'insert' | 'move'; id: string; tag: string }
const props = withDefaults(defineProps<{
  tree: TemplateTree | null
  selectedId: string | null
  language: 'en' | 'zh'
  error?: string
  disabled?: boolean
}>(), { error: '', disabled: false })
const emit = defineEmits<{
  select: [id: string]
  insert: [action: { kind: string; targetId: string; position: Position }]
  move: [action: { nodeId: string; targetId: string; position: Position }]
}>()

const copy = {
  en: {
    title: 'Structure', insert: 'Insert', tree: 'Node tree', empty: 'Add a document in the XML editor.',
    invalid: 'Fix the XML to edit its structure.', up: 'Move up', down: 'Move down',
    expand: 'Expand', collapse: 'Collapse', drag: 'Drag', before: 'Place before', after: 'Place after',
    inside: 'Place inside', blocked: 'This position does not accept this node.', insertAt: 'Insert',
    noTarget: 'Select a different node to insert here.',
    names: { document: 'Document', page: 'Page settings', heading: 'Heading', p: 'Paragraph', bullet: 'Bullet',
      table: 'Table', rows: 'Data rows', row: 'Row', cell: 'Cell', columns: 'Columns', column: 'Column',
      header: 'Table header', span: 'Text style', each: 'Loop', if: 'Condition', then: 'Then', else: 'Else',
      'page-break': 'Page break' } as Record<string, string>
  },
  zh: {
    title: '结构', insert: '插入', tree: '节点树', empty: '请先在 XML 编辑区添加文档。',
    invalid: '修正 XML 后可继续编辑结构。', up: '上移', down: '下移',
    expand: '展开', collapse: '折叠', drag: '拖动', before: '放在节点之前', after: '放在节点之后',
    inside: '放入节点内部', blocked: '此位置不能放入该节点。', insertAt: '插入',
    noTarget: '请选择其他节点作为插入位置。',
    names: { document: '文档', page: '页面设置', heading: '标题', p: '段落', bullet: '项目符号',
      table: '表格', rows: '数据行', row: '行', cell: '单元格', columns: '列设置', column: '列',
      header: '表头', span: '行内样式', each: '循环', if: '条件', then: '成立分支', else: '否则分支',
      'page-break': '换页' } as Record<string, string>
  }
}
const t = computed(() => copy[props.language])
const componentId = useId()
const treeElement = ref<HTMLElement>()
const focusId = ref<string | null>(null)
const collapsed = ref(new Set<string>())
const dragItem = ref<DragItem | null>(null)
const dropTarget = ref<{ id: string; position: Position; valid: boolean } | null>(null)
const announcement = ref('')
let dragToken = ''
const DRAG_TYPE = 'application/x-export4j-structure'
const lockedTags = new Set(['document', 'page', 'columns', 'column', 'header', 'then', 'else'])
const nodeMap = computed(() => new Map((props.tree?.nodes ?? []).map(node => [node.id, node])))
const nodePositions = computed(() => {
  const positions = new Map<string, { index: number; count: number }>()
  if (props.tree) positions.set(props.tree.root.id, { index: 1, count: 1 })
  for (const node of props.tree?.nodes ?? []) node.children.forEach((child, index) => positions.set(child.id, { index: index + 1, count: node.children.length }))
  return positions
})
const selected = computed(() => nodeMap.value.get(props.selectedId ?? '') ?? null)
const visibleNodes = computed(() => {
  if (!props.tree) return []
  const result: TreeNode[] = []
  const visit = (node: TreeNode) => {
    result.push(node)
    if (!collapsed.value.has(node.id)) node.children.forEach(visit)
  }
  visit(props.tree.root)
  return result
})
const tabStop = computed(() => visibleNodes.value.some(node => node.id === focusId.value) ? focusId.value
  : visibleNodes.value.some(node => node.id === props.selectedId) ? props.selectedId : props.tree?.root.id)

function name(node: TreeNode) { return t.value.names[node.tag] ?? node.tag }
function summary(node: TreeNode) {
  if (node.tag === 'document') return ''
  const value = (node.label ?? node.text ?? '').replace(/\s+/g, ' ').trim()
  return value === node.tag || value === name(node) ? '' : value
}
function rowId(id: string) { return `${componentId}-node-${id}` }
function movable(node: TreeNode) {
  return !props.disabled && node.parentId !== null && (node.draggable ?? !lockedTags.has(node.tag))
}
function allowed(tag: string, nodeId: string | null, targetId: string, position: Position) {
  return !props.disabled && Boolean(props.tree && canPlaceNode(props.tree, tag, nodeId, targetId, position))
}
function insertion(kind: { id: string; tag: string }) {
  const target = selected.value ?? props.tree?.root
  if (!target) return null
  const position: Position | null = allowed(kind.tag, null, target.id, 'inside') ? 'inside'
    : allowed(kind.tag, null, target.id, 'after') ? 'after' : null
  return position ? { targetId: target.id, position } : null
}
function insert(kind: { id: string; tag: string }) {
  const target = insertion(kind)
  if (!target) { announcement.value = t.value.noTarget; return }
  announcement.value = ''
  emit('insert', { kind: kind.id, ...target })
}
function adjacent(direction: -1 | 1) {
  const node = selected.value
  if (!node || !movable(node)) return null
  const siblings = nodeMap.value.get(node.parentId ?? '')?.children ?? []
  const target = siblings[siblings.findIndex(item => item.id === node.id) + direction]
  const position = direction < 0 ? 'before' : 'after'
  return target && allowed(node.tag, node.id, target.id, position) ? { nodeId: node.id, targetId: target.id, position } : null
}
function moveAdjacent(direction: -1 | 1) {
  const action = adjacent(direction)
  if (action) emit('move', action as { nodeId: string; targetId: string; position: Position })
}
function choose(node: TreeNode) {
  if (props.disabled) return
  if (!dragItem.value) announcement.value = ''
  focusId.value = node.id
  emit('select', node.id)
}
function toggle(node: TreeNode) {
  if (!node.children.length) return
  const next = new Set(collapsed.value)
  if (next.has(node.id)) next.delete(node.id)
  else next.add(node.id)
  collapsed.value = next
}
async function focusNode(id: string) {
  focusId.value = id
  await nextTick()
  document.getElementById(rowId(id))?.focus({ preventScroll: true })
  document.getElementById(rowId(id))?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}
function treeKeys(event: KeyboardEvent, node: TreeNode) {
  if (event.target !== event.currentTarget) return
  const index = visibleNodes.value.findIndex(item => item.id === node.id)
  let target: TreeNode | undefined
  if (event.key === 'ArrowDown') target = visibleNodes.value[index + 1]
  else if (event.key === 'ArrowUp') target = visibleNodes.value[index - 1]
  else if (event.key === 'Home') target = visibleNodes.value[0]
  else if (event.key === 'End') target = visibleNodes.value.at(-1)
  else if (event.key === 'ArrowRight') {
    if (node.children.length && collapsed.value.has(node.id)) toggle(node)
    else target = node.children[0]
  } else if (event.key === 'ArrowLeft') {
    if (node.children.length && !collapsed.value.has(node.id)) toggle(node)
    else target = nodeMap.value.get(node.parentId ?? '')
  } else if (event.key === 'Enter' || event.key === ' ') choose(node)
  else return
  event.preventDefault()
  if (target) { choose(target); void focusNode(target.id) }
}
function beginDrag(event: DragEvent, item: DragItem) {
  if (props.disabled || !props.tree || !event.dataTransfer || !dragToken) { event.preventDefault(); return }
  dragItem.value = item
  event.dataTransfer.effectAllowed = item.type === 'move' ? 'move' : 'copy'
  event.dataTransfer.setData(DRAG_TYPE, JSON.stringify({ token: dragToken, ...item }))
  if (item.type === 'move') {
    const node = nodeMap.value.get(item.id)
    if (node) choose(node)
  }
  event.stopPropagation()
}
function clearDrag() {
  dragItem.value = null
  dropTarget.value = null
  announcement.value = ''
}
function positionAt(event: DragEvent, node: TreeNode): Position {
  const rectangle = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const ratio = (event.clientY - rectangle.top) / rectangle.height
  const item = dragItem.value
  if (ratio > .25 && ratio < .75 && item && allowed(item.tag, item.type === 'move' ? item.id : null, node.id, 'inside')) return 'inside'
  return ratio < .5 ? 'before' : 'after'
}
function dragOver(event: DragEvent, node: TreeNode) {
  const item = dragItem.value
  if (!item || !event.dataTransfer?.types.includes(DRAG_TYPE)) return
  event.stopPropagation()
  const position = positionAt(event, node)
  const valid = allowed(item.tag, item.type === 'move' ? item.id : null, node.id, position)
  if (dropTarget.value?.id !== node.id || dropTarget.value.position !== position || dropTarget.value.valid !== valid) dropTarget.value = { id: node.id, position, valid }
  announcement.value = valid ? `${t.value[position]} · ${name(node)}` : t.value.blocked
  event.dataTransfer.dropEffect = valid ? item.type === 'move' ? 'move' : 'copy' : 'none'
  if (valid) event.preventDefault()
}
function drop(event: DragEvent, node: TreeNode) {
  const item = dragItem.value
  if (!item || !event.dataTransfer) return
  event.preventDefault()
  event.stopPropagation()
  let payload: Record<string, string>
  try { payload = JSON.parse(event.dataTransfer.getData(DRAG_TYPE)) }
  catch { clearDrag(); return }
  if (!payload || payload.token !== dragToken || payload.type !== item.type || payload.id !== item.id || payload.tag !== item.tag) { clearDrag(); return }
  const position = positionAt(event, node)
  if (allowed(item.tag, item.type === 'move' ? item.id : null, node.id, position)) {
    if (item.type === 'insert') emit('insert', { kind: item.id, targetId: node.id, position })
    else emit('move', { nodeId: item.id, targetId: node.id, position })
  }
  clearDrag()
}
function dropClass(node: TreeNode) {
  if (dropTarget.value?.id !== node.id) return ''
  return dropTarget.value.valid ? `drop-${dropTarget.value.position}` : 'drop-blocked'
}
function dragLeave(event: DragEvent, node: TreeNode) {
  if (dropTarget.value?.id === node.id && !(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) dropTarget.value = null
}
function paletteIcon(tag: string) {
  return ({ heading: 'H', p: '¶', bullet: '•', table: '▦', 'page-break': '↵', each: '↻', if: '?' } as Record<string, string>)[tag] ?? '+'
}

watch(() => props.tree, () => {
  const ids = new Set(props.tree?.nodes.map(node => node.id) ?? [])
  // Source offsets identify one tree revision; retaining them could fold another node after an edit.
  collapsed.value = new Set()
  if (focusId.value && !ids.has(focusId.value)) focusId.value = null
  clearDrag()
})
watch(() => props.selectedId, async id => {
  const node = id ? nodeMap.value.get(id) : null
  if (!node) return
  const next = new Set(collapsed.value)
  let parent = nodeMap.value.get(node.parentId ?? '')
  while (parent) { next.delete(parent.id); parent = nodeMap.value.get(parent.parentId ?? '') }
  collapsed.value = next
  focusId.value = node.id
  await nextTick()
  const row = document.getElementById(rowId(node.id))
  if (!row || !treeElement.value) return
  const treeBounds = treeElement.value.getBoundingClientRect()
  const rowBounds = row.getBoundingClientRect()
  if (rowBounds.top < treeBounds.top || rowBounds.bottom > treeBounds.bottom) row.scrollIntoView({ block: 'nearest', inline: 'nearest' })
})
onMounted(() => {
  dragToken = crypto.randomUUID()
  window.addEventListener('dragend', clearDrag)
})
onBeforeUnmount(() => window.removeEventListener('dragend', clearDrag))
</script>

<template>
  <aside class="structure-panel" :aria-label="t.title">
    <div class="structure-content">
      <section class="structure-library" :aria-label="t.insert">
        <h2>{{ t.insert }}</h2>
        <div class="component-palette">
          <button v-for="kind in INSERTABLE_NODES" :key="kind.id" class="palette-item" type="button"
            :data-insert-kind="kind.id" :disabled="disabled || !tree"
            :title="insertion(kind) ? `${t.insertAt} ${kind.name[language]}` : t.noTarget"
            :draggable="Boolean(!disabled && tree)" @click="insert(kind)"
            @dragstart="beginDrag($event, { type: 'insert', id: kind.id, tag: kind.tag })" @dragend="clearDrag">
            <span class="palette-symbol" aria-hidden="true">{{ paletteIcon(kind.tag) }}</span><span>{{ kind.name[language] }}</span>
          </button>
        </div>
      </section>

      <div class="tree-heading"><h2>{{ t.tree }}</h2></div>
      <div v-if="error" class="structure-error" role="status"><p>{{ t.invalid }}</p><p>{{ error }}</p></div>
      <div v-else-if="!tree" class="structure-empty">{{ t.empty }}</div>
      <div v-else ref="treeElement" class="structure-tree" role="tree" :aria-label="t.tree">
        <div v-for="node in visibleNodes" :id="rowId(node.id)" :key="node.id" class="tree-row"
          :class="[{ selected: node.id === selectedId, 'drag-source': dragItem?.type === 'move' && dragItem.id === node.id }, dropClass(node)]"
          role="treeitem" :aria-selected="node.id === selectedId" :aria-level="node.depth + 1" :aria-disabled="disabled || undefined"
          :aria-label="`${name(node)}${summary(node) ? ` · ${summary(node)}` : ''}`"
          :aria-expanded="node.children.length ? !collapsed.has(node.id) : undefined"
          :aria-posinset="nodePositions.get(node.id)?.index" :aria-setsize="nodePositions.get(node.id)?.count"
          :tabindex="tabStop === node.id ? 0 : -1" :data-node-id="node.id" :data-node-tag="node.tag"
          :style="{ '--tree-depth': Math.min(node.depth, 8) }" :title="`${name(node)}${summary(node) ? ` · ${summary(node)}` : ''}`"
          @click="choose(node)" @focus="focusId = node.id" @keydown="treeKeys($event, node)"
          @dragover="dragOver($event, node)" @drop="drop($event, node)"
          @dragleave="dragLeave($event, node)">
          <button v-if="node.children.length" class="tree-expander" tabindex="-1" type="button"
            :aria-label="`${collapsed.has(node.id) ? t.expand : t.collapse} ${name(node)}`" @click.stop="toggle(node)">
            <svg :class="{ closed: collapsed.has(node.id) }" width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
          </button>
          <span v-else class="tree-leaf" aria-hidden="true" />
          <span class="node-label"><span class="node-name">{{ name(node) }}</span><span v-if="summary(node)" class="node-summary">{{ summary(node) }}</span></span>
          <button v-if="movable(node)" class="node-drag-handle" type="button" tabindex="-1" draggable="true"
            :aria-label="`${t.drag} ${name(node)}`" @click.stop="choose(node)"
            @dragstart="beginDrag($event, { type: 'move', id: node.id, tag: node.tag })" @dragend="clearDrag">
            <svg width="12" height="16" viewBox="0 0 12 16" fill="currentColor" aria-hidden="true"><circle cx="4" cy="4" r="1" /><circle cx="8" cy="4" r="1" /><circle cx="4" cy="8" r="1" /><circle cx="8" cy="8" r="1" /><circle cx="4" cy="12" r="1" /><circle cx="8" cy="12" r="1" /></svg>
          </button>
        </div>
      </div>
      <div class="structure-move-actions">
        <button type="button" :disabled="!adjacent(-1)" @click="moveAdjacent(-1)"><span aria-hidden="true">↑</span>{{ t.up }}</button>
        <button type="button" :disabled="!adjacent(1)" @click="moveAdjacent(1)"><span aria-hidden="true">↓</span>{{ t.down }}</button>
      </div>
      <p class="structure-announcement" :class="{ 'has-feedback': announcement && !dragItem }" role="status" aria-live="polite">{{ announcement }}</p>
    </div>
  </aside>
</template>

<style scoped>
.structure-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; height: 100%; color: var(--vp-c-text-1); background: var(--vp-c-bg); }
.tree-expander .closed { transform: rotate(-90deg); }
.structure-content { display: flex; flex: 1; flex-direction: column; min-height: 0; }
.structure-library { flex-shrink: 0; padding: 18px 12px 16px; border-bottom: 1px solid var(--vp-c-divider); }
.structure-panel h2 { margin: 0; color: var(--vp-c-text-2); font-size: 12px; font-weight: 500; line-height: 1.5; }
.component-palette { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; margin-top: 10px; }
.palette-item { display: flex; align-items: center; gap: 5px; min-width: 0; min-height: 40px; padding: 8px 6px; border: 1px solid var(--vp-c-divider); border-radius: 5px; color: var(--vp-c-text-1); background: var(--vp-c-bg); font-size: 12px; line-height: 20px; text-align: left; cursor: grab; transition: border-color 150ms ease, background-color 150ms ease; }
.palette-item > span:last-child { min-width: 0; white-space: normal; }
.palette-symbol { display: flex; align-items: center; justify-content: center; flex-shrink: 0; width: 14px; color: var(--vp-c-text-2); font-family: var(--vp-font-family-mono); font-size: 14px; font-weight: 500; }
.palette-item:not(:disabled):hover { border-color: var(--vp-c-brand-1); background: var(--vp-c-brand-soft); }
.palette-item:active { cursor: grabbing; }
.palette-item:disabled { cursor: default; opacity: .45; }
.tree-heading { flex-shrink: 0; padding: 16px 14px 8px; }
.structure-tree { flex: 1; min-width: 0; min-height: 0; overflow: auto; padding: 3px 6px 12px; overscroll-behavior: contain; }
.tree-row { position: relative; display: flex; align-items: center; gap: 2px; min-width: 0; min-height: 38px; margin: 1px 0; padding: 3px 4px 3px calc(4px + var(--tree-depth) * 11px); border: 1px solid transparent; border-radius: 4px; cursor: pointer; outline-offset: -2px; }
.tree-row:hover { background: var(--vp-c-bg-soft); }
.tree-row.selected { color: var(--vp-c-brand-1); background: var(--vp-c-brand-soft); }
.tree-row.drag-source { opacity: .5; }
.tree-expander { display: flex; align-items: center; justify-content: center; flex-shrink: 0; width: 22px; height: 30px; padding: 0; border: 0; color: var(--vp-c-text-2); background: transparent; cursor: pointer; }
.tree-expander svg { transition: transform 150ms ease; }
.tree-leaf { flex-shrink: 0; width: 22px; }
.node-label { display: flex; flex: 1; align-items: baseline; gap: 7px; min-width: 0; overflow: hidden; }
.node-name { flex-shrink: 0; font-size: 12px; font-weight: 500; line-height: 22px; }
.node-summary { min-width: 0; overflow: hidden; color: var(--vp-c-text-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.tree-row.selected .node-summary { color: var(--vp-c-text-2); }
.node-drag-handle { display: flex; align-items: center; justify-content: center; flex-shrink: 0; width: 22px; height: 30px; margin-left: auto; padding: 0; border: 0; color: var(--vp-c-text-3); background: transparent; opacity: .45; cursor: grab; }
.tree-row:hover .node-drag-handle, .tree-row.selected .node-drag-handle, .tree-row:focus-within .node-drag-handle { opacity: 1; }
.node-drag-handle:active { cursor: grabbing; }
.tree-row.drop-before::before, .tree-row.drop-after::after { position: absolute; z-index: 1; right: 0; left: calc(6px + var(--tree-depth) * 11px); height: 2px; border-radius: 2px; background: var(--vp-c-brand-1); content: ''; pointer-events: none; }
.tree-row.drop-before::before { top: -2px; }
.tree-row.drop-after::after { bottom: -2px; }
.tree-row.drop-inside { border-color: var(--vp-c-brand-1); background: var(--vp-c-brand-soft); }
.tree-row.drop-blocked { border-color: var(--vp-c-danger-1); }
.structure-move-actions { display: flex; flex-shrink: 0; gap: 6px; padding: 10px 12px; border-top: 1px solid var(--vp-c-divider); background: var(--vp-c-bg-alt); }
.structure-move-actions button { display: flex; flex: 1; align-items: center; justify-content: center; gap: 5px; min-height: 36px; padding: 6px 4px; border: 1px solid var(--vp-c-divider); border-radius: 4px; color: var(--vp-c-text-2); background: var(--vp-c-bg); font-size: 12px; white-space: nowrap; cursor: pointer; }
.structure-move-actions button:not(:disabled):hover { color: var(--vp-c-text-1); background: var(--vp-c-bg-soft); }
.structure-move-actions button:disabled { opacity: .4; cursor: default; }
.structure-error, .structure-empty { flex: 1; min-height: 140px; padding: 14px; color: var(--vp-c-text-2); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.structure-error p { margin: 0; }
.structure-error p + p { margin-top: 8px; color: var(--vp-c-danger-1); }
.structure-announcement { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.structure-announcement.has-feedback { position: static; width: auto; height: auto; margin: 0; padding: 8px 12px; color: var(--vp-c-text-2); font-size: 12px; line-height: 1.6; clip-path: none; white-space: normal; }
.structure-panel button:focus-visible, .tree-row:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: -2px; }
@media (pointer: coarse) {
  .palette-item, .structure-move-actions button { min-height: 44px; }
  .tree-row { min-height: 44px; }
  .tree-expander, .node-drag-handle { min-width: 28px; height: 38px; }
  .node-drag-handle { opacity: .7; }
}
@media (prefers-reduced-motion: reduce) {
  .palette-item, .tree-expander svg { transition: none; }
}
</style>
