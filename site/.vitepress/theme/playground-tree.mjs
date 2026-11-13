// SPDX-License-Identifier: Apache-2.0
// Structural edits splice the original XML; existing content is never serialized.
import { MAX_SOURCE_LENGTH } from './playground-engine.mjs'

const BLOCKS = new Set(['heading', 'p', 'bullet', 'table', 'rows', 'each', 'if', 'page-break'])
const INLINE_PARENTS = new Set(['heading', 'p', 'bullet', 'cell'])
const PINNED = new Set(['document', 'page', 'columns', 'column', 'header', 'then', 'else'])
const POSITIONS = new Set(['before', 'after', 'inside'])
const MESSAGES = {
  en: {
    xml: 'Fix the XML syntax before editing the node tree.',
    root: 'Use <document version="1"> as the XML root.',
    declarations: 'The node tree does not accept DTDs, namespaces or processing instructions.',
    limit: 'The node tree limit was reached: 131,072 UTF-16 source units, 3,000 elements and 32 nesting levels.',
    target: 'This node is no longer available. Select it again.',
    place: 'This location would break the template structure or change a loop binding.',
    kind: 'Choose a supported node from the insert panel.',
    document: 'Document'
  },
  zh: {
    xml: '请先修正 XML 语法，再编辑节点树。',
    root: 'XML 根元素须为 <document version="1">。',
    declarations: '节点树不接受 DTD、命名空间或处理指令。',
    limit: '已达到节点树上限：源码 131,072 个 UTF-16 单元、3,000 个元素、32 层嵌套。',
    target: '此节点已发生变化，请重新选择。',
    place: '此位置会破坏模板结构，或改变循环变量的绑定范围。',
    kind: '请选择插入面板中支持的节点。',
    document: '文档'
  }
}

export const INSERTABLE_NODES = [
  { id: 'heading', tag: 'heading', name: { en: 'Heading', zh: '标题' } },
  { id: 'p', tag: 'p', name: { en: 'Paragraph', zh: '段落' } },
  { id: 'bullet', tag: 'bullet', name: { en: 'List item', zh: '列表项' } },
  { id: 'table', tag: 'table', name: { en: 'Table', zh: '表格' } },
  { id: 'page-break', tag: 'page-break', name: { en: 'Page break', zh: '换页' } }
]

function messages(language) { return MESSAGES[language.startsWith('zh') ? 'zh' : 'en'] }
function reject(language, code) { throw new Error(messages(language)[code]) }
function clipped(value, size = 48) { return [...value.replace(/\s+/g, ' ').trim()].slice(0, size).join('') }

// Scan delimiters before DOM parsing so pathological depth is bounded first.
function ranges(source, language) {
  const nodes = [], stack = []
  let cursor = 0
  while (cursor < source.length) {
    const start = source.indexOf('<', cursor)
    if (start < 0) break
    if (source.startsWith('<!--', start) || source.startsWith('<![CDATA[', start)) {
      const comment = source.startsWith('<!--', start)
      const end = source.indexOf(comment ? '-->' : ']]>', start + (comment ? 4 : 9))
      if (end < 0) reject(language, 'xml')
      cursor = end + 3
      continue
    }
    if (source.startsWith('<?', start)) {
      const end = source.indexOf('?>', start + 2)
      if (end < 0) reject(language, 'xml')
      if (!/^\uFEFF?$/.test(source.slice(0, start)) || !/^<\?xml\s/.test(source.slice(start, end))) reject(language, 'declarations')
      cursor = end + 2
      continue
    }
    if (source.startsWith('<!', start)) reject(language, 'declarations')
    let quote = '', end = start + 1
    for (; end < source.length; end++) {
      const character = source[end]
      if (quote) { if (character === quote) quote = '' }
      else if (character === '"' || character === "'") quote = character
      else if (character === '>') break
    }
    if (end === source.length) reject(language, 'xml')
    const token = source.slice(start, end + 1)
    const closing = /^<\/([A-Za-z_][A-Za-z_0-9.:-]*)\s*>$/.exec(token)
    if (closing) {
      const node = stack.pop()
      if (!node || node.tag !== closing[1]) reject(language, 'xml')
      node.closeStart = start
      node.end = end + 1
    } else {
      const opening = /^<([A-Za-z_][A-Za-z_0-9.:-]*)(?=[\s/>])/.exec(token)
      if (!opening) reject(language, 'xml')
      if (nodes.length >= 3000 || stack.length > 32) reject(language, 'limit')
      const parent = stack.at(-1)
      const node = {
        id: String(start), tag: opening[1], parentId: parent?.id ?? null,
        children: [], depth: stack.length, start, end: end + 1,
        openEnd: end + 1, closeStart: end + 1, selfClosing: /\/>$/.test(token)
      }
      parent?.children.push(node)
      nodes.push(node)
      if (!node.selfClosing) stack.push(node)
    }
    cursor = end + 1
  }
  if (stack.length) reject(language, 'xml')
  return nodes
}

/** Parse a bounded XML tree with UTF-16 source ranges and no dependency on JSON. */
export function parseTemplateTree(source, language = 'en') {
  if (typeof source !== 'string' || !source.trim()) reject(language, 'xml')
  if (source.length > MAX_SOURCE_LENGTH) reject(language, 'limit')
  const nodes = ranges(source, language)
  const xml = new DOMParser().parseFromString(source, 'application/xml')
  if (xml.getElementsByTagName('parsererror').length) reject(language, 'xml')
  const element = xml.documentElement
  if (element?.tagName !== 'document' || element.getAttribute('version') !== '1') reject(language, 'root')
  if (nodes.length === 0 || nodes[0].tag !== 'document') reject(language, 'xml')
  const elements = [element, ...element.getElementsByTagName('*')]
  if (elements.length !== nodes.length) reject(language, 'xml')
  const literal = element.getAttribute('literal-text') === 'true'
  for (let index = 0; index < elements.length; index++) {
    const current = elements[index], node = nodes[index]
    if (node.tag !== current.tagName) reject(language, 'xml')
    if (current.namespaceURI || current.prefix) reject(language, 'declarations')
    node.attributes = Object.create(null)
    for (const attribute of current.attributes) {
      if (attribute.namespaceURI || attribute.prefix || attribute.name === 'xmlns' || attribute.name.includes(':')) reject(language, 'declarations')
      node.attributes[attribute.name] = attribute.value
    }
    node.text = clipped(current.textContent, 64)
    node.label = clipped(node.attributes.id || (node.tag === 'document' ? node.attributes.title || messages(language).document
      : node.tag === 'each' || node.tag === 'rows' ? `${node.attributes.items ?? ''} → ${node.attributes.as ?? ''}`
        : node.tag === 'if' ? node.attributes.test ?? '' : node.tag === 'page' ? node.attributes.size ?? '' : node.text))
    node.draggable = !PINNED.has(node.tag) && (BLOCKS.has(node.tag) || ['row', 'cell', 'span'].includes(node.tag))
    const heads = new Set()
    if (!literal) {
      for (const child of current.childNodes) {
        if (child.nodeType !== 3 && child.nodeType !== 4) continue
        for (const binding of child.textContent.matchAll(/\$\{([A-Za-z_][A-Za-z_0-9]*)(?:\.[A-Za-z_][A-Za-z_0-9]*)*\}/g)) heads.add(binding[1])
      }
    }
    for (const attribute of node.tag === 'if' ? ['test'] : ['each', 'rows'].includes(node.tag) ? ['items'] : []) {
      const head = /^([A-Za-z_][A-Za-z_0-9]*)/.exec(node.attributes[attribute] ?? '')
      if (head) heads.add(head[1])
    }
    node.bindingHeads = [...heads]
  }
  return { root: nodes[0], nodes, source }
}

function cellWidth(node) {
  const value = node.attributes?.['column-span'] ?? '1'
  return /^[1-9]\d*$/.test(value) && Number(value) <= 12 ? Number(value) : NaN
}

function structurallyValid(node, getChildren, getParent) {
  if (node.newNode) return true // Only the five known, complete snippets use this marker.
  const children = getChildren(node), tags = children.map(child => child.tag)
  if (node.tag === 'document') return children.every((child, index) => child.tag === 'page' ? index === 0 : BLOCKS.has(child.tag))
  if (['each', 'then', 'else'].includes(node.tag)) return tags.every(tag => BLOCKS.has(tag))
  if (node.tag === 'if') return tags.length >= 1 && tags.length <= 2 && tags[0] === 'then' && (tags.length === 1 || tags[1] === 'else')
  if (INLINE_PARENTS.has(node.tag)) return tags.every(tag => tag === 'span')
  if (['page', 'page-break', 'span', 'column'].includes(node.tag)) return tags.length === 0
  if (node.tag === 'columns') return tags.length >= 1 && tags.length <= 12 && tags.every(tag => tag === 'column')
  if (node.tag === 'table' || node.tag === 'rows') {
    if (tags[0] !== 'columns' || tags.filter(tag => tag === 'columns').length !== 1) return false
    const body = tags.slice(1)
    const firstRow = body.indexOf('row')
    if (!body.every((tag, index) => tag === 'row' || tag === 'header' && (firstRow < 0 || index < firstRow))) return false
    const rows = body.filter(tag => tag === 'row').length
    return rows >= 1 && (node.tag !== 'rows' || rows === 1)
  }
  if (node.tag === 'row' || node.tag === 'header') {
    const table = getParent(node)
    const columns = table && getChildren(table)[0]
    return (table?.tag === 'table' || table?.tag === 'rows') && columns?.tag === 'columns'
      && tags.every(tag => tag === 'cell') && children.reduce((sum, cell) => sum + cellWidth(cell), 0) === getChildren(columns).length
  }
  return false
}

/** A location is allowed only if both affected parents and loop scopes stay valid. */
export function canPlaceNode(tree, tag, nodeIdOrNull, targetId, position) {
  if (!tree?.root || !Array.isArray(tree.nodes) || !POSITIONS.has(position)) return false
  const byId = new Map(tree.nodes.map(node => [node.id, node]))
  const target = byId.get(targetId), moving = nodeIdOrNull === null ? null : byId.get(nodeIdOrNull)
  if (!target || nodeIdOrNull !== null && (!moving || moving.tag !== tag || !moving.draggable)) return false
  if (!moving && !INSERTABLE_NODES.some(item => item.tag === tag)) return false
  if (moving && target.start >= moving.start && target.end <= moving.end) return false
  const destination = position === 'inside' ? target : byId.get(target.parentId)
  if (!destination) return false
  const parent = byId.get(moving?.parentId)
  const inserted = moving ?? { id: '@new', tag, newNode: true, children: [] }
  const children = new Map()
  if (parent) children.set(parent.id, parent.children.filter(child => child !== moving))
  const siblings = [...(children.get(destination.id) ?? destination.children)]
  const index = position === 'inside' ? siblings.length : siblings.findIndex(child => child === target) + (position === 'after' ? 1 : 0)
  if (index < 0) return false
  siblings.splice(index, 0, inserted)
  children.set(destination.id, siblings)
  const getChildren = node => children.get(node.id) ?? node.children
  const getParent = node => node === inserted ? destination : byId.get(node.parentId)
  if (!tree.nodes.filter(node => node !== moving).every(node => structurallyValid(node, getChildren, getParent))) return false
  if (moving && !structurallyValid(moving, getChildren, getParent)) return false
  if (!moving) return true
  function scope(node, head, moved) {
    let child = node
    for (let ancestor = moved ? getParent(child) : byId.get(child.parentId); ancestor; ancestor = moved ? getParent(child) : byId.get(child.parentId)) {
      if (ancestor.attributes?.as === head && (ancestor.tag === 'each' || ancestor.tag === 'rows' && child.tag === 'row')) return ancestor.id
      child = ancestor
    }
    return null
  }
  // Moving a binding across an alias boundary must not silently retarget its data.
  return tree.nodes.filter(node => node.start >= moving.start && node.end <= moving.end)
    .every(node => node.bindingHeads.every(head => scope(node, head, false) === scope(node, head, true)))
}

function newline(source) { return source.includes('\r\n') ? '\r\n' : '\n' }
function lineStart(source, offset) { return source.lastIndexOf('\n', offset - 1) + 1 }
function indentation(source, offset) {
  const prefix = source.slice(lineStart(source, offset), offset)
  return /^[\t ]*$/.test(prefix) ? prefix : null
}
function indentUnit(source) {
  const match = /(?:\r?\n)([\t ]+)</.exec(source)
  return match ? match[1] : '  '
}
function removeRange(source, node) {
  const indent = indentation(source, node.start)
  const tail = /^[\t ]*(?:\r?\n|$)/.exec(source.slice(node.end))
  return indent !== null && tail ? { start: node.start - indent.length, end: node.end + tail[0].length } : { start: node.start, end: node.end }
}
function snippet(kind, language) {
  const zh = language.startsWith('zh')
  if (kind === 'heading') return `<heading level="2" size="16" line-height="24">${zh ? '新的标题' : 'New heading'}</heading>`
  if (kind === 'p') return `<p>${zh ? '在这里填写正文。' : 'Write your text here.'}</p>`
  if (kind === 'bullet') return `<bullet>${zh ? '新的列表项' : 'New list item'}</bullet>`
  if (kind === 'page-break') return '<page-break/>'
  if (kind === 'table') return [
    '<table>',
    '  <columns><column weight="1"/><column weight="1"/></columns>',
    `  <header><cell>${zh ? '项目' : 'Item'}</cell><cell>${zh ? '说明' : 'Description'}</cell></header>`,
    `  <row><cell>${zh ? '新的项目' : 'New item'}</cell><cell>${zh ? '在这里填写说明。' : 'Add a description.'}</cell></row>`,
    '</table>'
  ].join('\n')
  reject(language, 'kind')
}

function insertRaw(source, tree, raw, targetId, position, generated, language) {
  const target = tree.nodes.find(node => node.id === targetId)
  if (!target) reject(language, 'target')
  const parent = position === 'inside' ? target : tree.nodes.find(node => node.id === target.parentId)
  const inline = INLINE_PARENTS.has(parent.tag)
  const eol = newline(source)
  const baseIndent = indentation(source, target.start) ?? ''
  let content = generated ? raw.replace(/\n/g, eol) : raw
  let start, end, prefix = '', suffix = ''
  if (position === 'inside' && target.selfClosing) {
    start = target.openEnd - 2
    end = target.openEnd
    if (inline) { prefix = '>'; suffix = `</${target.tag}>` }
    else {
      const indent = baseIndent + indentUnit(source)
      prefix = '>' + eol + indent
      suffix = eol + baseIndent + `</${target.tag}>`
      if (generated) content = content.replaceAll(eol, eol + indent)
    }
  } else if (position === 'inside') {
    start = end = target.closeStart
    if (!inline && source.slice(target.openEnd, target.closeStart).includes('\n')) {
      const closeIndent = indentation(source, target.closeStart)
      if (closeIndent !== null) {
        start = end = target.closeStart - closeIndent.length
        const indent = baseIndent + indentUnit(source)
        prefix = indent
        suffix = eol
        if (generated) content = content.replaceAll(eol, eol + indent)
      }
    }
  } else {
    start = end = position === 'before' ? target.start : target.end
    const standalone = removeRange(source, target)
    if (!inline && (standalone.start < target.start || standalone.end > target.end)) {
      if (position === 'before') { start = end = standalone.start; prefix = baseIndent; suffix = eol }
      else { prefix = eol + baseIndent }
      if (generated) content = content.replaceAll(eol, eol + baseIndent)
    }
  }
  const next = source.slice(0, start) + prefix + content + suffix + source.slice(end)
  const selectedId = String(start + prefix.length)
  const result = parseTemplateTree(next, language)
  if (!result.nodes.some(node => node.id === selectedId)) reject(language, 'xml')
  return { source: next, selectedId }
}

/** Insert a complete, localized node without adding a JSON data dependency. */
export function insertTemplateNode(source, kind, targetId, position = 'after', language = 'en') {
  const tree = parseTemplateTree(source, language)
  const item = INSERTABLE_NODES.find(node => node.id === kind)
  if (!item) reject(language, 'kind')
  if (!tree.nodes.some(node => node.id === targetId)) reject(language, 'target')
  if (!canPlaceNode(tree, item.tag, null, targetId, position)) reject(language, 'place')
  return insertRaw(source, tree, snippet(kind, language), targetId, position, true, language)
}

/** Move an existing subtree while preserving all of its source content verbatim. */
export function moveTemplateNode(source, nodeId, targetId, position = 'before', language = 'en') {
  const tree = parseTemplateTree(source, language)
  const node = tree.nodes.find(item => item.id === nodeId), target = tree.nodes.find(item => item.id === targetId)
  if (!node || !target) reject(language, 'target')
  if (!canPlaceNode(tree, node.tag, nodeId, targetId, position)) reject(language, 'place')
  const parent = tree.nodes.find(item => item.id === node.parentId)
  // Whitespace surrounding a span belongs to the paragraph's text content.
  const removal = INLINE_PARENTS.has(parent?.tag) ? { start: node.start, end: node.end } : removeRange(source, node)
  const raw = source.slice(node.start, node.end)
  const next = source.slice(0, removal.start) + source.slice(removal.end)
  const nextTarget = target.start >= removal.end ? target.start - (removal.end - removal.start) : target.start
  return insertRaw(next, parseTemplateTree(next, language), raw, String(nextTarget), position, false, language)
}
