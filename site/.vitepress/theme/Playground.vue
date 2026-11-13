<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'
import { useData, withBase } from 'vitepress'
import { renderPreview, MAX_SOURCE_LENGTH } from './playground-engine.mjs'
import { PLAYGROUND_SAMPLES } from './playground-samples.mjs'
import { insertTemplateNode, moveTemplateNode, parseTemplateTree } from './playground-tree.mjs'
import PlaygroundSelect from './PlaygroundSelect.vue'
import PlaygroundStructure from './PlaygroundStructure.vue'

const { lang } = useData()
const language = computed(() => lang.value.startsWith('zh') ? 'zh' : 'en')
const messages = {
  en: {
    title: 'Template playground', description: 'Edit an XML template, add your data, and see it take shape.',
    local: 'Runs entirely in your browser. Templates and data are never uploaded.',
    example: 'Example', load: 'Load example', undo: 'Undo replacement',
    xml: 'XML template', json: 'JSON data', import: 'Import', download: 'Download',
    preview: 'Preview', approximate: 'Browser layout', fit: 'Fit width', zoom: 'Preview zoom',
    pending: 'Updating…', ready: 'Up to date', invalid: 'Preview unavailable',
    loading: 'Preparing editor…', exampleReady: 'Example loaded', saved: 'Draft saved on this device', unsaved: 'Saving draft…',
    storage: 'Draft could not be saved. Download your files to keep them.',
    lines: 'lines', pages: 'explicit pages', page: 'page',
    notice: 'Fonts, line breaks and pagination may differ. Use the Java engine for final output.',
    support: 'Preview support', supported: 'Text and headings, inline styles, tables, loops, boolean conditions, page settings and explicit page breaks.',
    unsupported: 'Advanced template features are reported as unsupported. Automatic pagination, images, font resources and final PDF export require the Java engine.',
    guide: 'XML template guide', designer: 'Full template designer',
    importError: 'Could not read this file. Choose a UTF-8 XML or JSON file.',
    importSize: 'This file exceeds the 128 KiB import limit.',
    sourceSize: 'The draft exceeds the preview limit. Download it to keep the complete source.',
    replaced: 'Files replaced. You can undo this change.', downloaded: 'File downloaded.',
    imported: 'File imported.', input: 'Source editors', empty: 'Your document preview appears here.',
    sourceHelp: 'Edits preview automatically. Ctrl/⌘+S downloads the active file.',
    structure: 'Structure', undoEdit: 'Undo edit', redoEdit: 'Redo edit',
    inserted: 'Node inserted.', moved: 'Node moved.', staleTree: 'The structure is updating. Try again in a moment.'
  },
  zh: {
    title: '模板试验台', description: '编辑 XML 模板，填入数据，即时查看效果。',
    local: '所有操作都在浏览器完成，模板和数据不会上传。',
    example: '示例', load: '载入示例', undo: '撤销替换',
    xml: 'XML 模板', json: 'JSON 数据', import: '导入', download: '下载',
    preview: '预览', approximate: '浏览器近似排版', fit: '适应宽度', zoom: '预览缩放',
    pending: '更新中…', ready: '已更新', invalid: '暂时无法预览',
    loading: '正在准备编辑器…', exampleReady: '示例已载入', saved: '草稿已保存在此设备', unsaved: '正在保存草稿…',
    storage: '草稿未能保存，请下载文件以保留内容。',
    lines: '行', pages: '个显式分页', page: '页',
    notice: '字体、换行和分页可能有差异，正式输出请使用 Java 引擎。',
    support: '预览支持范围', supported: '文字与标题、行内样式、表格、循环、布尔条件、纸张设置和显式换页。',
    unsupported: '超出范围的模板语法会明确提示。自动分页、图片与字体资源、正式 PDF 导出仍由 Java 引擎完成。',
    guide: 'XML 模板指南', designer: '完整模板设计器',
    importError: '无法读取文件，请选择 UTF-8 编码的 XML 或 JSON 文件。',
    importSize: '文件超过 128 KiB 导入上限。',
    sourceSize: '草稿超出预览上限，请下载以保留完整源码。',
    replaced: '已替换文件，可以撤销此次操作。', downloaded: '文件已下载。',
    imported: '文件已导入。', input: '源码编辑区', empty: '文档预览会显示在这里。',
    sourceHelp: '编辑后自动预览。Ctrl/⌘+S 下载当前文件。',
    structure: '文档结构', undoEdit: '撤销编辑', redoEdit: '重做编辑',
    inserted: '已插入节点。', moved: '已移动节点。', staleTree: '结构正在更新，请稍后操作。'
  }
}
const t = computed(() => messages[language.value])
const id = useId()
const activeTab = ref<'xml' | 'json'>('xml')
const xml = ref('')
const json = ref('')
const example = ref(PLAYGROUND_SAMPLES[0].id)
const loadedExample = ref(PLAYGROUND_SAMPLES[0].id)
const previous = ref<{ xml: string; json: string; example: string } | null>(null)
const initialized = ref(false)
const error = ref('')
const notification = ref('')
const storageState = ref<'example' | 'saved' | 'pending' | 'unavailable' | 'oversize'>('example')
const hasDraft = ref(false)
const pending = ref(false)
const preview = ref<ReturnType<typeof renderPreview> | null>(null)
const zoom = ref('fit')
const viewport = ref<HTMLElement>()
const fileInput = ref<HTMLInputElement>()
const previewWidth = ref(600)
const busyImport = ref(false)
const xmlEditor = ref<HTMLTextAreaElement>()
const tree = shallowRef<ReturnType<typeof parseTemplateTree> | null>(null)
const treeError = ref('')
const treePending = ref(false)
const selectedId = ref<string | null>(null)
const structureOpen = ref(true)
let treeSource = ''
let treeTimer: ReturnType<typeof setTimeout> | undefined
type Snapshot = { xml: string; json: string; example: string }
const history = ref<Snapshot[]>([])
const historyIndex = ref(0)
let historyTimer: ReturnType<typeof setTimeout> | undefined
let restoringHistory = false
let resizeObserver: ResizeObserver | undefined
let renderTimer: ReturnType<typeof setTimeout> | undefined
let saveTimer: ReturnType<typeof setTimeout> | undefined
let notificationTimer: ReturnType<typeof setTimeout> | undefined
const downloadUrls = new Set<string>()
const DRAFT_KEY = 'export4j:playground:v1'
const activeSource = computed(() => activeTab.value === 'xml' ? xml.value : json.value)
const lines = computed(() => activeSource.value.split('\n').length)
const frameWidth = computed(() => (preview.value?.page.width ?? 595.28) * 4 / 3 + 48)
const frameHeight = computed(() => (preview.value?.page.height ?? 841.89) * 4 / 3 + 48)
const scale = computed(() => zoom.value === 'fit'
  ? Math.min(1, Math.max(.1, previewWidth.value / frameWidth.value))
  : Number(zoom.value) / 100)
const draftStatus = computed(() => storageState.value === 'unavailable' ? t.value.storage
  : storageState.value === 'oversize' ? t.value.sourceSize
    : storageState.value === 'pending' ? t.value.unsaved
      : storageState.value === 'example' ? t.value.exampleReady : t.value.saved)
const guide = computed(() => withBase(`${language.value === 'zh' ? '/zh' : ''}/docs/xml-template-api`))
const designer = computed(() => withBase(`${language.value === 'zh' ? '/zh' : ''}/docs/designer`))
const exampleOptions = computed(() => PLAYGROUND_SAMPLES.map(sample => ({ value: sample.id, label: sample.name[language.value] })))
const zoomOptions = computed(() => [{ value: 'fit', label: t.value.fit }, ...['50', '75', '100'].map(value => ({ value, label: `${value}%` }))])
const historyDirty = computed(() => Boolean(history.value.length) &&
  (xml.value !== history.value[historyIndex.value].xml || json.value !== history.value[historyIndex.value].json))
const canUndo = computed(() => historyIndex.value > 0 || historyDirty.value)
const canRedo = computed(() => !historyDirty.value && historyIndex.value < history.value.length - 1)

function snapshot(): Snapshot { return { xml: xml.value, json: json.value, example: loadedExample.value } }
function setXmlEditor(element: unknown) { xmlEditor.value = element instanceof HTMLTextAreaElement ? element : undefined }

function commitHistory() {
  clearTimeout(historyTimer)
  if (!initialized.value || restoringHistory) return
  const state = snapshot()
  if (state.xml.length + state.json.length > 2 * 1024 * 1024) {
    history.value = []
    historyIndex.value = 0
    return
  }
  const current = history.value[historyIndex.value]
  if (current?.xml === state.xml && current?.json === state.json && current?.example === state.example) return
  const states = history.value.slice(0, historyIndex.value + 1)
  states.push(state)
  let size = states.reduce((total, item) => total + item.xml.length + item.json.length, 0)
  while (states.length > 1 && (states.length > 50 || size > 2 * 1024 * 1024)) {
    const removed = states.shift()!
    size -= removed.xml.length + removed.json.length
  }
  history.value = states
  historyIndex.value = states.length - 1
}

function travelHistory(direction: -1 | 1) {
  commitHistory()
  const target = historyIndex.value + direction
  if (target < 0 || target >= history.value.length) return
  const state = history.value[target]
  restoringHistory = true
  xml.value = state.xml
  json.value = state.json
  loadedExample.value = state.example
  example.value = state.example
  restoringHistory = false
  historyIndex.value = target
  previous.value = null
  updateTree()
}

function updateTree() {
  clearTimeout(treeTimer)
  try {
    tree.value = parseTemplateTree(xml.value, language.value)
    treeSource = xml.value
    treeError.value = ''
    if (!tree.value.nodes.some(node => node.id === selectedId.value)) selectedId.value = tree.value.root.id
  } catch (cause) {
    tree.value = null
    selectedId.value = null
    treeError.value = cause instanceof Error ? cause.message : t.value.invalid
  } finally {
    treePending.value = false
  }
}

async function selectNode(nodeId: string) {
  if (treePending.value) return
  selectedId.value = nodeId
  const node = tree.value?.nodes.find(item => item.id === nodeId)
  if (!node) return
  activeTab.value = 'xml'
  await nextTick()
  const editor = xmlEditor.value
  if (editor) {
    editor.setSelectionRange(node.start, node.end)
    const lineHeight = parseFloat(getComputedStyle(editor).lineHeight)
    editor.scrollTop = Math.max(0, (xml.value.slice(0, node.start).split('\n').length - 2) * lineHeight)
  }
}

type Placement = { targetId: string; position: 'before' | 'after' | 'inside' }
function editStructure(action: (source: string) => { source: string; selectedId: string }, message: string) {
  if (treePending.value || treeSource !== xml.value) { notify(t.value.staleTree); return }
  try {
    const result = action(xml.value)
    if (result.source === xml.value) return
    commitHistory()
    xml.value = result.source
    selectedId.value = result.selectedId
    commitHistory()
    updateTree()
    selectNode(result.selectedId)
    notify(message)
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : t.value.invalid)
  }
}
function insertNode(event: Placement & { kind: string }) {
  editStructure(source => insertTemplateNode(source, event.kind, event.targetId, event.position, language.value), t.value.inserted)
}
function moveNode(event: Placement & { nodeId: string }) {
  editStructure(source => moveTemplateNode(source, event.nodeId, event.targetId, event.position, language.value), t.value.moved)
}

function notify(message: string) {
  notification.value = message
  clearTimeout(notificationTimer)
  notificationTimer = setTimeout(() => { notification.value = '' }, 4000)
}

function saveDraft() {
  clearTimeout(saveTimer)
  if (!initialized.value || !hasDraft.value) return
  if (xml.value.length > MAX_SOURCE_LENGTH || json.value.length > MAX_SOURCE_LENGTH) {
    storageState.value = 'oversize'
    return
  }
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ version: 1, xml: xml.value, json: json.value, example: loadedExample.value }))
    storageState.value = 'saved'
  } catch {
    storageState.value = 'unavailable'
  }
}

function updatePreview() {
  clearTimeout(renderTimer)
  try {
    preview.value = renderPreview(xml.value, json.value, language.value)
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : t.value.invalid
  } finally {
    pending.value = false
  }
}

function schedule() {
  if (!initialized.value) return
  hasDraft.value = true
  pending.value = true
  storageState.value = 'pending'
  clearTimeout(renderTimer)
  clearTimeout(saveTimer)
  renderTimer = setTimeout(updatePreview, 300)
  saveTimer = setTimeout(saveDraft, 600)
  if (!restoringHistory) {
    clearTimeout(historyTimer)
    historyTimer = setTimeout(commitHistory, 400)
  }
}

function saveWhenHidden() {
  if (document.visibilityState === 'hidden') saveDraft()
}

function remember() {
  previous.value = { xml: xml.value, json: json.value, example: loadedExample.value }
}

function loadExample() {
  commitHistory()
  const selected = PLAYGROUND_SAMPLES.find(sample => sample.id === example.value) ?? PLAYGROUND_SAMPLES[0]
  remember()
  xml.value = selected.xml[language.value]
  json.value = selected.data[language.value]
  loadedExample.value = selected.id
  commitHistory()
  updateTree()
  notify(t.value.replaced)
}

function undoReplacement() {
  if (!previous.value) return
  commitHistory()
  const saved = previous.value
  previous.value = null
  xml.value = saved.xml
  json.value = saved.json
  example.value = saved.example
  loadedExample.value = saved.example
  commitHistory()
  updateTree()
}

async function importSource(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const destination = activeTab.value
  input.value = ''
  if (!file) return
  if (file.size > MAX_SOURCE_LENGTH) { notify(t.value.importSize); return }
  busyImport.value = true
  try {
    const bytes = await file.arrayBuffer()
    const source = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    commitHistory()
    remember()
    if (destination === 'xml') xml.value = source
    else json.value = source
    commitHistory()
    updateTree()
    notify(t.value.imported)
  } catch {
    notify(t.value.importError)
  } finally {
    busyImport.value = false
  }
}

function downloadSource() {
  const type = activeTab.value
  const url = URL.createObjectURL(new Blob([activeSource.value], { type: type === 'xml' ? 'application/xml;charset=utf-8' : 'application/json;charset=utf-8' }))
  downloadUrls.add(url)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = type === 'xml' ? 'template.xml' : 'data.json'
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => { URL.revokeObjectURL(url); downloadUrls.delete(url) }, 1000)
  notify(t.value.downloaded)
}

function sourceKeys(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    downloadSource()
  }
}

function workspaceKeys(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return
  const key = event.key.toLowerCase()
  if (key === 'z' || key === 'y') {
    event.preventDefault()
    travelHistory(key === 'y' || event.shiftKey ? 1 : -1)
  }
}

async function tabKeys(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  activeTab.value = event.key === 'Home' ? 'xml' : event.key === 'End' ? 'json'
    : activeTab.value === 'xml' ? 'json' : 'xml'
  await nextTick()
  document.getElementById(`${id}-${activeTab.value}-tab`)?.focus()
}

watch([xml, json], schedule, { flush: 'sync' })
watch(xml, () => {
  if (!initialized.value) return
  selectedId.value = null
  treePending.value = true
  clearTimeout(treeTimer)
  treeTimer = setTimeout(updateTree, 180)
}, { flush: 'sync' })
watch(language, () => { if (initialized.value) { updatePreview(); updateTree() } })
onMounted(() => {
  const selected = PLAYGROUND_SAMPLES[0]
  xml.value = selected.xml[language.value]
  json.value = selected.data[language.value]
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (raw) {
      const draft = JSON.parse(raw)
      if (draft.version === 1 && typeof draft.xml === 'string' && typeof draft.json === 'string'
        && draft.xml.length <= MAX_SOURCE_LENGTH && draft.json.length <= MAX_SOURCE_LENGTH) {
        xml.value = draft.xml
        json.value = draft.json
        hasDraft.value = true
        storageState.value = 'saved'
        if (PLAYGROUND_SAMPLES.some(sample => sample.id === draft.example)) {
          example.value = draft.example
          loadedExample.value = draft.example
        }
      }
    }
  } catch {
    storageState.value = 'unavailable'
  }
  initialized.value = true
  history.value = [snapshot()]
  structureOpen.value = window.matchMedia('(min-width: 961px)').matches
  updateTree()
  updatePreview()
  window.addEventListener('pagehide', saveDraft)
  document.addEventListener('visibilitychange', saveWhenHidden)
  if (viewport.value) {
    resizeObserver = new ResizeObserver(entries => { previewWidth.value = entries[0].contentRect.width })
    resizeObserver.observe(viewport.value)
  }
})
onBeforeUnmount(() => {
  saveDraft()
  window.removeEventListener('pagehide', saveDraft)
  document.removeEventListener('visibilitychange', saveWhenHidden)
  clearTimeout(renderTimer)
  clearTimeout(saveTimer)
  clearTimeout(notificationTimer)
  clearTimeout(treeTimer)
  clearTimeout(historyTimer)
  resizeObserver?.disconnect()
  for (const url of downloadUrls) URL.revokeObjectURL(url)
})
</script>

<template>
  <div class="playground" @keydown="workspaceKeys">
    <header class="playground-heading">
      <h1>{{ t.title }}</h1>
      <p>{{ t.description }}</p>
    </header>

    <div class="playground-actions">
      <div class="example-actions">
        <PlaygroundSelect v-model="example" class="example-select" :options="exampleOptions" :label="t.example" :disabled="!initialized" />
        <button class="tool-button load-button" :disabled="!initialized" @click="loadExample">{{ t.load }}</button>
        <button v-if="previous" class="tool-button quiet-button" @click="undoReplacement">{{ t.undo }}</button>
        <div class="history-actions">
          <button class="history-button" :disabled="!canUndo" :aria-label="t.undoEdit" :title="`${t.undoEdit} · Ctrl/⌘+Z`" @click="travelHistory(-1)">
            <svg viewBox="0 0 20 20" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M7 4 3 8l4 4M3 8h8a5 5 0 0 1 0 10" transform="translate(0 -2)" /></svg>
          </button>
          <button class="history-button" :disabled="!canRedo" :aria-label="t.redoEdit" :title="`${t.redoEdit} · Ctrl/⌘+Shift+Z`" @click="travelHistory(1)">
            <svg viewBox="0 0 20 20" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m13 4 4 4-4 4m4-4H9a5 5 0 0 0 0 10" transform="translate(0 -2)" /></svg>
          </button>
        </div>
      </div>
      <p class="local-message"><span aria-hidden="true" class="local-dot" />{{ t.local }}</p>
    </div>

    <div class="playground-workspace" :class="{ 'tree-hidden': !structureOpen }" :aria-busy="!initialized">
      <aside class="structure-column" :class="{ 'structure-collapsed': !structureOpen }" :aria-label="t.structure">
        <button class="structure-toggle" :aria-label="t.structure" :aria-expanded="structureOpen" :aria-controls="`${id}-structure`" @click="structureOpen = !structureOpen">
          <span>{{ t.structure }}</span>
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true" :class="{ expanded: structureOpen }"><path d="m6 3 5 5-5 5" /></svg>
        </button>
        <div v-show="structureOpen" :id="`${id}-structure`" class="structure-host">
          <PlaygroundStructure :tree="tree" :selected-id="selectedId" :language="language" :error="treeError"
            :disabled="!initialized || treePending" @select="selectNode" @insert="insertNode" @move="moveNode" />
        </div>
      </aside>
      <section class="source-panel" :aria-label="t.input">
        <div class="panel-toolbar">
          <div class="source-tabs" role="tablist" :aria-label="t.input" @keydown="tabKeys">
            <button v-for="tab in (['xml', 'json'] as const)" :id="`${id}-${tab}-tab`" :key="tab"
              role="tab" :aria-selected="activeTab === tab" :aria-controls="`${id}-${tab}-panel`"
              :tabindex="activeTab === tab ? 0 : -1" @click="activeTab = tab">
              <span aria-hidden="true" class="file-symbol">{{ tab === 'xml' ? '&lt;/&gt;' : '{ }' }}</span>{{ t[tab] }}
            </button>
          </div>
          <div class="file-actions">
            <input ref="fileInput" type="file" hidden :accept="activeTab === 'xml' ? '.xml,application/xml,text/xml' : '.json,application/json'" @change="importSource">
            <button class="tool-button quiet-button" :disabled="!initialized || busyImport" @click="fileInput?.click()">{{ t.import }}</button>
            <button class="tool-button quiet-button" :disabled="!initialized" @click="downloadSource">{{ t.download }}</button>
          </div>
        </div>
        <div v-for="tab in (['xml', 'json'] as const)" v-show="activeTab === tab" :id="`${id}-${tab}-panel`"
          :key="tab" class="source-content" role="tabpanel" :aria-labelledby="`${id}-${tab}-tab`">
          <textarea v-if="tab === 'xml'" :ref="setXmlEditor" v-model="xml" :aria-label="t.xml" :aria-describedby="`${id}-source-help`"
            :disabled="!initialized" spellcheck="false" autocapitalize="off" autocomplete="off" autocorrect="off" wrap="off" @keydown="sourceKeys" />
          <textarea v-else v-model="json" :aria-label="t.json" :aria-describedby="`${id}-source-help`"
            :disabled="!initialized" spellcheck="false" autocapitalize="off" autocomplete="off" autocorrect="off" wrap="off" @keydown="sourceKeys" />
        </div>
        <div class="editor-status">
          <span>{{ lines }} {{ t.lines }} · UTF-8</span>
          <span :class="{ 'storage-warning': storageState === 'unavailable' || storageState === 'oversize' }">{{ initialized ? draftStatus : t.loading }}</span>
        </div>
      </section>

      <section class="preview-panel" :aria-label="t.preview">
        <div class="panel-toolbar preview-toolbar">
          <h2>{{ t.preview }}<span class="preview-mode">{{ t.approximate }}</span></h2>
          <PlaygroundSelect v-model="zoom" class="zoom-select" :options="zoomOptions" :label="t.zoom" />
        </div>
        <div ref="viewport" class="preview-viewport" :aria-busy="pending">
          <div v-if="error" class="preview-error" role="status">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v6m0 3v1" /></svg>
            <h3>{{ t.invalid }}</h3><p>{{ error }}</p><a :href="guide">{{ t.guide }} ↗</a>
          </div>
          <div v-else-if="preview" class="preview-frame-space" :style="{ width: `${frameWidth * scale}px`, height: `${frameHeight * scale}px` }">
            <iframe class="preview-frame" :title="`${t.preview}: ${preview.title}`" sandbox="" referrerpolicy="no-referrer"
              :srcdoc="preview.html" :style="{ width: `${frameWidth}px`, height: `${frameHeight}px`, transform: `scale(${scale})` }" />
          </div>
          <p v-else class="preview-empty">{{ initialized ? t.empty : t.loading }}</p>
        </div>
        <div class="preview-status" aria-live="polite">
          <span class="preview-state" :class="{ 'has-error': error }"><span aria-hidden="true" />{{ pending ? t.pending : error ? t.invalid : t.ready }}</span>
          <span v-if="preview && !error">{{ Math.round(preview.page.width) }} × {{ Math.round(preview.page.height) }} pt · {{ preview.pages }} {{ preview.pages > 1 ? t.pages : t.page }}</span>
        </div>
      </section>
    </div>

    <div class="playground-notes">
      <p :id="`${id}-source-help`">{{ t.sourceHelp }}</p>
      <p>{{ t.notice }}</p>
    </div>
    <details class="playground-support">
      <summary>{{ t.support }}</summary>
      <p>{{ t.supported }}</p><p>{{ t.unsupported }}</p>
      <nav :aria-label="t.support"><a :href="guide">{{ t.guide }} ↗</a><a :href="designer">{{ t.designer }} ↗</a></nav>
    </details>
    <p class="playground-notification" role="status" aria-live="polite">{{ notification }}</p>
  </div>
</template>

<style scoped>
.playground { max-width: 1800px; margin: 0 auto; padding: 36px 32px 56px; color: var(--vp-c-text-1); }
.playground-heading h1 { margin: 0; font-size: 34px; font-weight: 600; line-height: 1.2; letter-spacing: -.04em; }
.playground-heading p { margin: 12px 0 0; color: var(--vp-c-text-2); font-size: 16px; line-height: 1.6; }
.playground-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; margin: 28px 0 16px; }
.example-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.example-actions label { margin-right: 4px; color: var(--vp-c-text-2); font-size: 13px; }
.example-select { min-width: 150px; }
.zoom-select { min-width: 112px; }
.history-actions { display: flex; align-items: center; gap: 2px; margin-left: 4px; padding-left: 10px; border-left: 1px solid var(--vp-c-divider); }
.history-button { display: grid; place-items: center; width: 38px; height: 40px; color: var(--vp-c-text-2); border-radius: 5px; cursor: pointer; }
.history-button:hover:enabled { color: var(--vp-c-text-1); background: var(--pg-control-hover); }
.tool-button { min-height: 40px; padding: 8px 12px; border: 1px solid var(--vp-c-border); border-radius: 5px; font-size: 13px; line-height: 22px; white-space: nowrap; cursor: pointer; transition: background-color .15s, color .15s; }
.load-button { color: var(--vp-button-brand-text); background: var(--vp-button-brand-bg); border-color: var(--vp-button-brand-bg); }
.load-button:hover { background: var(--vp-button-brand-hover-bg); }
.quiet-button { border-color: transparent; color: var(--vp-c-text-2); }
.quiet-button:hover { color: var(--vp-c-text-1); background: var(--vp-c-bg-soft); }
.playground button:disabled, .playground select:disabled { cursor: default; opacity: .5; }
.playground button:focus-visible, .playground select:focus-visible, .playground a:focus-visible, .playground summary:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 3px; }
.local-message { display: flex; align-items: center; gap: 7px; margin: 0; color: var(--vp-c-text-2); font-size: 12px; line-height: 1.6; }
.local-dot { width: 6px; height: 6px; flex-shrink: 0; border-radius: 50%; background: var(--vp-c-brand-1); }
.playground-workspace { display: grid; grid-template-columns: 224px minmax(0, .92fr) minmax(0, 1.08fr); min-height: 720px; border: 1px solid var(--vp-c-border); border-radius: 8px; overflow: hidden; }
.structure-column { display: flex; flex-direction: column; min-width: 0; height: 720px; border-right: 1px solid var(--vp-c-divider); background: var(--pg-panel); }
.structure-toggle { display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; min-height: 54px; width: 100%; padding: 0 16px; border-bottom: 1px solid var(--vp-c-divider); color: var(--vp-c-text-1); font-size: 13px; font-weight: 600; text-align: left; cursor: pointer; }
.structure-toggle svg { flex-shrink: 0; color: var(--vp-c-text-3); transition: transform .15s; }
.structure-toggle svg.expanded { transform: rotate(90deg); }
.structure-host { flex: 1; min-height: 0; overflow: hidden; }
.source-panel, .preview-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; height: 720px; }
.source-panel { border-right: 1px solid var(--vp-c-border); background: var(--vp-c-bg); }
.panel-toolbar { display: flex; flex-shrink: 0; align-items: center; justify-content: space-between; min-height: 54px; padding: 0 12px; gap: 8px; border-bottom: 1px solid var(--vp-c-divider); background: var(--vp-c-bg-alt); }
.source-tabs { display: flex; align-self: stretch; gap: 12px; }
.source-tabs button { display: flex; align-items: center; gap: 6px; min-height: 44px; padding: 0 2px; border-bottom: 2px solid transparent; color: var(--vp-c-text-2); font-size: 13px; cursor: pointer; white-space: nowrap; }
.source-tabs button[aria-selected='true'] { border-color: var(--vp-c-brand-1); color: var(--vp-c-text-1); }
.file-symbol { font-family: var(--vp-font-family-mono); color: var(--vp-c-text-3); font-size: 12px; }
.file-actions { display: flex; gap: 2px; }
.file-actions .tool-button { padding-inline: 8px; }
.source-content { display: flex; flex: 1; min-height: 0; }
.source-content textarea { display: block; width: 100%; height: 100%; min-height: 0; margin: 0; padding: 18px 16px; resize: none; border: 0; border-radius: 0; outline: 0; color: var(--vp-c-text-1); background: var(--vp-c-bg); font-family: var(--vp-font-family-mono); font-size: 13px; line-height: 1.8; tab-size: 2; overflow: auto; }
.source-content textarea:focus-visible { box-shadow: inset 0 0 0 2px var(--vp-c-brand-1); }
.editor-status, .preview-status { display: flex; flex-shrink: 0; align-items: center; justify-content: space-between; gap: 12px; min-height: 34px; padding: 6px 14px; border-top: 1px solid var(--vp-c-divider); color: var(--vp-c-text-2); background: var(--vp-c-bg-alt); font-size: 12px; line-height: 1.5; }
.editor-status span { min-width: 0; }
.editor-status span:last-child { text-align: right; }
.storage-warning { color: var(--vp-c-warning-1); }
.preview-toolbar h2 { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 0; font-size: 13px; font-weight: 600; }
.preview-mode { padding: 3px 7px; border: 1px solid var(--vp-c-divider); border-radius: 4px; font-size: 12px; font-weight: 400; color: var(--vp-c-text-2); line-height: 1.4; }
.preview-viewport { flex: 1; min-width: 0; min-height: 0; overflow: auto; background: var(--pg-canvas); }
.preview-frame-space { position: relative; margin-inline: auto; }
.preview-frame { display: block; max-width: none; border: 0; transform-origin: top left; background: transparent; color-scheme: light; }
.preview-state { display: inline-flex; align-items: center; gap: 6px; }
.preview-state > span { width: 5px; height: 5px; border-radius: 50%; background: var(--vp-c-brand-1); }
.preview-state.has-error > span { background: var(--vp-c-danger-1); }
.preview-error { max-width: 390px; margin: 72px auto; padding: 24px; color: var(--vp-c-text-1); overflow-wrap: anywhere; }
.preview-error svg { color: var(--vp-c-danger-1); }
.preview-error h3 { margin: 20px 0 10px; font-size: 18px; font-weight: 600; }
.preview-error p { font-size: 14px; line-height: 1.7; }
.preview-error a { display: inline-block; margin-top: 20px; color: var(--vp-c-brand-1); font-size: 13px; text-decoration: underline; text-underline-offset: 3px; }
.preview-empty { padding: 60px 24px; text-align: center; color: var(--vp-c-text-2); }
.playground-notes { display: flex; justify-content: space-between; gap: 16px; margin-top: 12px; color: var(--vp-c-text-2); font-size: 12px; line-height: 1.7; }
.playground-notes p { margin: 0; }
.playground-notes p:last-child { text-align: right; }
.playground-support { margin-top: 28px; border-top: 1px solid var(--vp-c-divider); padding-top: 16px; font-size: 13px; }
.playground-support summary { width: fit-content; cursor: pointer; color: var(--vp-c-text-2); }
.playground-support p { max-width: 820px; color: var(--vp-c-text-2); line-height: 1.8; margin: 12px 0; }
.playground-support nav { display: flex; flex-wrap: wrap; gap: 24px; margin-top: 16px; }
.playground-support a { color: var(--vp-c-brand-1); text-decoration: underline; text-underline-offset: 3px; }
.playground-notification { min-height: 24px; margin: 12px 0 0; color: var(--vp-c-brand-1); font-size: 13px; line-height: 1.8; }
@media (min-width: 1600px) { .structure-column, .source-panel, .preview-panel { height: 780px; } }
@media (min-width: 961px) {
  .playground-workspace.tree-hidden { grid-template-columns: 44px minmax(0, .92fr) minmax(0, 1.08fr); }
  .tree-hidden .structure-toggle { justify-content: center; padding: 0; }
  .tree-hidden .structure-toggle span { display: none; }
}
@media (min-width: 961px) and (max-width: 1199px) {
  .playground-workspace { grid-template-columns: 224px minmax(0, 1fr); }
  .playground-workspace.tree-hidden { grid-template-columns: 44px minmax(0, 1fr); }
  .source-panel { border-right: 0; }
  .structure-column, .source-panel { height: 520px; }
  .preview-panel { grid-column: 1 / -1; height: 680px; border-top: 1px solid var(--vp-c-divider); }
}
@media (max-width: 960px) {
  .playground { padding: 32px 24px 40px; }
  .playground-workspace { grid-template-columns: minmax(0, 1fr); }
  .structure-column { height: auto; max-height: 494px; border-right: 0; border-bottom: 1px solid var(--vp-c-divider); }
  .structure-collapsed .structure-toggle { border-bottom: 0; }
  .structure-host { flex: none; height: 440px; }
  .source-panel { height: 480px; border-right: 0; border-bottom: 1px solid var(--vp-c-border); }
  .preview-panel { height: 660px; }
  .playground-actions { align-items: flex-start; }
  .playground-notes { display: block; }
  .playground-notes p:last-child { margin-top: 4px; text-align: left; }
}
@media (max-width: 480px) {
  .playground { padding: 28px 16px 36px; }
  .playground-heading h1 { font-size: 28px; }
  .playground-heading p { font-size: 15px; }
  .example-select { min-width: 128px; }
  .panel-toolbar { padding-inline: 8px; }
  .source-tabs { gap: 10px; }
  .file-symbol { display: none; }
  .source-content textarea { padding: 16px 12px; font-size: 12px; }
  .tool-button { min-height: 44px; }
  .history-button { min-height: 44px; }
  .editor-status, .preview-status { padding-inline: 10px; }
  .preview-panel { height: 540px; }
}
@media (prefers-reduced-motion: reduce) { .tool-button, .structure-toggle svg { transition: none; } }
</style>
