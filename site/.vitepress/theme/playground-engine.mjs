// SPDX-License-Identifier: Apache-2.0
// A bounded browser preview of an explicit XML subset, not the Java layout engine.
export const MAX_SOURCE_LENGTH = 128 * 1024
const PATH = /^[A-Za-z_][A-Za-z_0-9]*(\.[A-Za-z_][A-Za-z_0-9]*)*$/
const IDENTIFIER = /^[A-Za-z_][A-Za-z_0-9]*$/
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor'])
const BLOCKS = new Set(['p', 'heading', 'bullet', 'each', 'if', 'table', 'rows', 'page-break'])
const TEXT = ['id', 'size', 'line-height', 'align', 'space-before', 'space-after']
const TABLE = ['id', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left', 'space-before', 'space-after', 'draw-borders']
const INLINE = ['role', 'color', 'size', 'underline', 'strike']
const MESSAGES = {
  en: {
    limit: 'The preview limit was reached: ',
    xml: 'XML is not well formed.',
    json: 'JSON is not valid near character ',
    root: 'Use <document version="1"> as the XML root.',
    dataRoot: 'JSON data must be an object with non-null values.',
    declarations: 'The preview does not accept DTDs, entities, namespaces or processing instructions.',
    unsupported: 'Not supported by this browser preview: ',
    structure: 'Invalid structure in ',
    attribute: 'Invalid attribute ',
    path: 'Use an ASCII dot path without prototype properties: ',
    missing: 'Missing data value: ',
    scalar: 'A text binding requires a string, boolean or number: ',
    list: 'A loop requires an array: ',
    boolean: 'A condition requires a boolean: ',
    emptyRows: 'A dynamic table must produce at least one body row.',
    columnWidth: 'Fixed columns must leave positive space for weighted columns.',
    duplicate: 'Duplicate static id: ',
    number: 'A JSON number is too long for this preview.',
    empty: 'Add a document element to preview.'
  },
  zh: {
    limit: '已达到预览上限：',
    xml: 'XML 格式不正确。',
    json: 'JSON 格式不正确，位置约为第 ',
    root: 'XML 根元素须为 <document version="1">。',
    dataRoot: 'JSON 数据须为对象，且不能包含 null。',
    declarations: '预览不接受 DTD、实体声明、命名空间或处理指令。',
    unsupported: '浏览器预览暂不支持：',
    structure: '元素结构不正确：',
    attribute: '属性值不正确：',
    path: '请使用 ASCII 点路径，且不能访问原型属性：',
    missing: '缺少数据：',
    scalar: '文字绑定需要字符串、布尔值或数字：',
    list: '循环需要数组：',
    boolean: '条件需要布尔值：',
    emptyRows: '动态表格至少需要一行正文数据。',
    columnWidth: '固定列宽必须为比例列留下正数宽度。',
    duplicate: '静态 id 重复：',
    number: 'JSON 数字过长，超出预览范围。',
    empty: '请先输入 document 元素。'
  }
}

// Keep numeric lexemes intact, including values beyond JavaScript's safe integer range.
// They are displayed as text only; the preview implements no numeric conditions.
class JsonNumber {
  constructor(source) { this.source = source }
}

function parseData(source, fail) {
  let cursor = 0
  let nodes = 0
  const bad = () => fail('json', String(cursor + 1))
  const whitespace = () => { while (/[\t\n\r ]/.test(source[cursor] ?? '\0')) cursor++ }
  function string() {
    const start = cursor++
    while (cursor < source.length) {
      const character = source[cursor++]
      if (character === '"') {
        try { return JSON.parse(source.slice(start, cursor)) } catch { bad() }
      }
      if (character === '\\') cursor++
      else if (character.charCodeAt(0) < 32) bad()
    }
    bad()
  }
  function value(depth) {
    if (++nodes > 8000 || depth > 32) fail('limit', 'JSON')
    whitespace()
    const character = source[cursor]
    if (character === '"') return string()
    if (character === '{' || character === '[') {
      const array = character === '['
      const result = array ? [] : Object.create(null)
      cursor++
      whitespace()
      const end = array ? ']' : '}'
      if (source[cursor] === end) { cursor++; return result }
      for (;;) {
        whitespace()
        let key
        if (!array) {
          if (source[cursor] !== '"') bad()
          key = string()
          if (FORBIDDEN_KEYS.has(key) || Object.hasOwn(result, key)) bad()
          whitespace()
          if (source[cursor++] !== ':') bad()
        }
        const child = value(depth + 1)
        if (array) result.push(child)
        else result[key] = child
        whitespace()
        const separator = source[cursor++]
        if (separator === end) return result
        if (separator !== ',') bad()
      }
    }
    if (source.startsWith('true', cursor)) { cursor += 4; return true }
    if (source.startsWith('false', cursor)) { cursor += 5; return false }
    const number = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(source.slice(cursor))
    if (number) {
      if (number[0].length > 128) fail('number')
      cursor += number[0].length
      return new JsonNumber(number[0])
    }
    // null is outside the documented template data contract.
    bad()
  }
  const result = value(0)
  whitespace()
  if (cursor !== source.length) bad()
  if (!result || Array.isArray(result) || result instanceof JsonNumber || typeof result !== 'object') fail('dataRoot')
  return result
}

export function renderPreview(xmlSource, jsonSource, language = 'en') {
  const lang = language.startsWith('zh') ? 'zh' : 'en'
  const messages = MESSAGES[lang]
  function fail(code, detail = '') { throw new Error(messages[code] + detail) }
  if (typeof xmlSource !== 'string' || typeof jsonSource !== 'string') fail('empty')
  if (xmlSource.length > MAX_SOURCE_LENGTH || jsonSource.length > MAX_SOURCE_LENGTH) fail('limit', lang === 'zh' ? '每份源码 131,072 个 UTF-16 单元' : '131,072 UTF-16 units per source')
  if (!xmlSource.trim()) fail('empty')
  if (/<!DOCTYPE|<!ENTITY/i.test(xmlSource)) fail('declarations')
  const data = parseData(jsonSource, fail)
  const xml = new DOMParser().parseFromString(xmlSource, 'application/xml')
  if (xml.getElementsByTagName('parsererror').length) fail('xml')
  const root = xml.documentElement
  if (!root || root.tagName !== 'document' || root.getAttribute('version') !== '1') fail('root')
  const literal = root.getAttribute('literal-text') === 'true'
  const ids = new Set()
  let staticNodes = 0
  const budget = { nodes: 0, characters: 0, iterations: 0, bindings: 0 }
  const page = { width: 595.2755905512, height: 841.8897637795, top: 54, right: 54, bottom: 54, left: 54 }

  function path(value) {
    if (!value || value.length > 256 || !PATH.test(value) || value.split('.').some(part => FORBIDDEN_KEYS.has(part))) fail('path', value ?? '')
    return value
  }
  function attribute(element, name, fallback) { return element.hasAttribute(name) ? element.getAttribute(name) : fallback }
  function attrs(element, allowed) {
    if (element.namespaceURI || element.prefix) fail('declarations')
    for (const item of element.attributes) {
      if (item.namespaceURI || item.prefix || item.name.includes(':') || item.name === 'xmlns') fail('declarations')
      if (!allowed.includes(item.name)) fail('unsupported', '<' + element.tagName + '> @' + item.name)
    }
    if (element.hasAttribute('id')) {
      const id = element.getAttribute('id')
      if (!/^[A-Za-z_][A-Za-z_0-9-]{0,127}$/.test(id)) fail('attribute', 'id')
      if (ids.has(id)) fail('duplicate', id)
      ids.add(id)
    }
  }
  function children(element, inline = false) {
    const result = []
    for (const child of element.childNodes) {
      if (child.nodeType === 8) continue
      if (child.nodeType === 1) result.push(child)
      else if ((child.nodeType === 3 || child.nodeType === 4) && (inline || !child.textContent.trim())) {
        if (inline) result.push(child)
      } else fail('structure', '<' + element.tagName + '>')
    }
    return result
  }
  function empty(element) {
    if ([...element.childNodes].some(child => child.nodeType !== 8 && (child.nodeType !== 3 || child.textContent.length))) fail('structure', '<' + element.tagName + '>')
  }
  function numeric(element, name, fallback, minimum = 0, maximum = 1200) {
    const raw = attribute(element, name, String(fallback))
    if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw)) fail('attribute', '<' + element.tagName + '> @' + name)
    const number = Number(raw)
    if (!Number.isFinite(number) || number < minimum || number > maximum) fail('attribute', '<' + element.tagName + '> @' + name)
    return number
  }
  function integer(element, name, fallback, maximum) {
    const raw = attribute(element, name, String(fallback))
    if (!/^[1-9]\d*$/.test(raw) || Number(raw) > maximum) fail('attribute', '<' + element.tagName + '> @' + name)
    return Number(raw)
  }
  function flag(element, name, fallback) {
    const value = attribute(element, name, String(fallback))
    if (value !== 'true' && value !== 'false') fail('attribute', '<' + element.tagName + '> @' + name)
    return value === 'true'
  }
  function interpolation(source, scopes, bind) {
    function append(text) {
      // Charge before concatenation, including metadata and literal-text mode.
      // Otherwise one text node can expand far beyond the document budget first.
      if (bind && (budget.characters += text.length) > 250000) fail('limit', lang === 'zh' ? '展开内容' : 'expanded content')
      result += text
    }
    let result = '', position = 0
    if (literal) { append(source); return result }
    for (;;) {
      const start = source.indexOf('$' + '{', position)
      if (start < 0) { append(source.slice(position)); return result }
      const end = source.indexOf('}', start + 2)
      if (end < 0) fail('path', source.slice(start))
      const key = path(source.slice(start + 2, end))
      append(source.slice(position, start))
      if (bind) {
        const value = lookup(key, scopes)
        if (value instanceof JsonNumber) append(value.source)
        else if (typeof value === 'string' || typeof value === 'boolean') append(String(value))
        else fail('scalar', key)
      }
      position = end + 1
    }
  }
  function lookup(key, scopes) {
    if (++budget.bindings > 10000) fail('limit', 'bindings / 绑定')
    const parts = key.split('.')
    let value
    let found = false
    for (let index = scopes.length - 1; index >= 0; index--) {
      if (Object.hasOwn(scopes[index], parts[0])) { value = scopes[index][parts[0]]; found = true; break }
    }
    if (!found) fail('missing', key)
    for (const part of parts.slice(1)) {
      if (!value || Array.isArray(value) || value instanceof JsonNumber || typeof value !== 'object' || !Object.hasOwn(value, part)) fail('missing', key)
      value = value[part]
    }
    return value
  }
  function basicStyle(element, target, defaults = {}) {
    if (element.hasAttribute('size') || defaults.size) target.style.fontSize = numeric(element, 'size', defaults.size ?? 11, 1, 96) + 'pt'
    if (element.hasAttribute('line-height') || defaults.lineHeight) target.style.lineHeight = numeric(element, 'line-height', defaults.lineHeight ?? 18, 1, 240) + 'pt'
    if (element.hasAttribute('align')) {
      const align = element.getAttribute('align')
      if (!['LEFT', 'CENTER', 'RIGHT', 'JUSTIFY'].includes(align)) fail('attribute', 'align')
      target.style.textAlign = align.toLowerCase()
    }
    for (const [name, css] of [['space-before', 'marginTop'], ['space-after', 'marginBottom']]) {
      if (element.hasAttribute(name)) target.style[css] = numeric(element, name, 0, 0, 240) + 'pt'
    }
    if (element.hasAttribute('color')) {
      const color = element.getAttribute('color')
      if (!/^#[0-9a-fA-F]{6}$/.test(color)) fail('attribute', 'color')
      target.style.color = color
    }
    if (element.hasAttribute('role')) {
      const role = element.getAttribute('role')
      if (!['regular', 'bold', 'italic', 'boldItalic'].includes(role)) fail('unsupported', 'role="' + role + '"')
      target.style.fontWeight = role.toLowerCase().includes('bold') ? '700' : '400'
      target.style.fontStyle = role.toLowerCase().includes('italic') ? 'italic' : 'normal'
    }
    const decorations = []
    if (element.hasAttribute('underline') && flag(element, 'underline', false)) decorations.push('underline')
    if (element.hasAttribute('strike') && flag(element, 'strike', false)) decorations.push('line-through')
    if (decorations.length) target.style.textDecoration = decorations.join(' ')
  }
  // Validate every static branch before looking up any binding data.
  function validate(element, context, depth = 0) {
    if (++staticNodes > 3000 || depth > 32) fail('limit', 'XML')
    const name = element.tagName
    if (context === 'blocks' && !BLOCKS.has(name)) fail('unsupported', '<' + name + '>')
    const elements = () => children(element)
    const validateBlocks = () => elements().forEach(child => validate(child, 'blocks', depth + 1))
    if (name === 'document') {
      attrs(element, ['version', 'title', 'author', 'literal-text'])
      flag(element, 'literal-text', false)
      for (const field of ['title', 'author']) interpolation(attribute(element, field, ''), [], false)
      elements().forEach((child, index) => {
        if (child.tagName === 'page') {
          if (index !== 0) fail('structure', '<page>')
          validate(child, 'page', depth + 1)
        } else validate(child, 'blocks', depth + 1)
      })
    } else if (name === 'page') {
      attrs(element, ['size', 'width', 'height', 'orientation', 'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left'])
      empty(element)
      if (element.hasAttribute('width') !== element.hasAttribute('height') || element.hasAttribute('size') && element.hasAttribute('width')) fail('structure', '<page>')
      function length(name, fallback) {
        const match = /^(\d+(?:\.\d*)?|\.\d+)(pt|mm|cm|in)?$/.exec(attribute(element, name, String(fallback)))
        if (!match) fail('attribute', '<page> @' + name)
        const value = Number(match[1]) * ({ pt: 1, mm: 72 / 25.4, cm: 720 / 25.4, in: 72 }[match[2] ?? 'pt'])
        if (!Number.isFinite(value) || value < 0 || value > 1440) fail('attribute', '<page> @' + name)
        return value
      }
      if (element.hasAttribute('size')) {
        const size = element.getAttribute('size')
        if (!['A4', 'LETTER'].includes(size)) fail('attribute', 'size')
        if (size === 'LETTER') { page.width = 612; page.height = 792 }
      }
      page.width = length('width', page.width)
      page.height = length('height', page.height)
      if (element.hasAttribute('orientation')) {
        const orientation = element.getAttribute('orientation')
        if (!['PORTRAIT', 'LANDSCAPE'].includes(orientation)) fail('attribute', 'orientation')
        const sizes = [page.width, page.height].sort((a, b) => a - b)
        ;[page.width, page.height] = orientation === 'PORTRAIT' ? sizes : sizes.reverse()
      }
      const margin = length('margin', 54)
      for (const side of ['top', 'right', 'bottom', 'left']) page[side] = length('margin-' + side, margin)
      if (Math.min(page.width, page.height) < 144 || page.width - page.left - page.right < 24 || page.height - page.top - page.bottom < 24) fail('attribute', '<page> dimensions')
    } else if (['p', 'heading', 'bullet', 'span', 'cell'].includes(name)) {
      const allowed = name === 'span' ? INLINE : name === 'cell' ? ['size', 'line-height', 'align', 'column-span'] : [...TEXT, ...(name === 'heading' ? ['level'] : name === 'bullet' ? ['level', 'marker'] : [])]
      attrs(element, allowed)
      basicStyle(element, document.createElement('span'))
      if (name === 'heading') integer(element, 'level', 0, 6)
      if (name === 'bullet') {
        integer(element, 'level', 1, 9)
        const marker = attribute(element, 'marker', '•')
        if (!marker.trim() || [...marker].length > 8 || /[\u0000-\u001f\u007f]/.test(marker)) fail('attribute', 'marker')
      }
      if (name === 'cell') integer(element, 'column-span', 1, 12)
      for (const child of children(element, true)) {
        if (child.nodeType !== 1) interpolation(child.textContent, [], false)
        else if (child.tagName !== 'span' || name === 'span') fail('unsupported', '<' + child.tagName + '> in <' + name + '>')
        else validate(child, 'inline', depth + 1)
      }
    } else if (name === 'each') {
      attrs(element, ['items', 'as'])
      path(element.getAttribute('items'))
      const alias = element.getAttribute('as')
      if (!alias || !IDENTIFIER.test(alias) || FORBIDDEN_KEYS.has(alias)) fail('attribute', 'as')
      validateBlocks()
    } else if (name === 'if') {
      attrs(element, ['test', 'operator', 'value'])
      path(element.getAttribute('test'))
      if (!['TRUE', 'FALSE'].includes(attribute(element, 'operator', 'TRUE')) || attribute(element, 'value', '') !== '') fail('unsupported', '<if> operator: TRUE / FALSE')
      const branches = elements()
      if (branches.length < 1 || branches.length > 2 || branches[0].tagName !== 'then' || branches[1] && branches[1].tagName !== 'else') fail('structure', '<if>')
      for (const branch of branches) {
        attrs(branch, [])
        children(branch).forEach(child => validate(child, 'blocks', depth + 1))
      }
    } else if (name === 'table' || name === 'rows') {
      attrs(element, [...TABLE, ...(name === 'rows' ? ['items', 'as'] : [])])
      if (name === 'rows') {
        path(element.getAttribute('items'))
        const alias = element.getAttribute('as')
        if (!alias || !IDENTIFIER.test(alias) || FORBIDDEN_KEYS.has(alias)) fail('attribute', 'as')
      }
      for (const side of ['top', 'right', 'bottom', 'left']) numeric(element, 'padding-' + side, 5, 0, 72)
      for (const spacing of ['space-before', 'space-after']) numeric(element, spacing, 0, 0, 240)
      flag(element, 'draw-borders', true)
      const parts = elements()
      if (!parts.length || parts[0].tagName !== 'columns') fail('structure', '<' + name + '>')
      attrs(parts[0], [])
      const columns = children(parts[0])
      if (!columns.length || columns.length > 12) fail('structure', '<columns>')
      for (const column of columns) {
        if (column.tagName !== 'column') fail('unsupported', '<' + column.tagName + '>')
        attrs(column, ['width', 'weight'])
        empty(column)
        if (column.hasAttribute('width') === column.hasAttribute('weight')) fail('structure', '<column>')
        numeric(column, column.hasAttribute('width') ? 'width' : 'weight', 1, .001, 1440)
      }
      let bodyRows = 0
      for (const row of parts.slice(1)) {
        if (!['header', 'row'].includes(row.tagName) || row.tagName === 'header' && bodyRows) fail('structure', '<' + name + '>')
        if (row.tagName === 'row') bodyRows++
        attrs(row, [])
        const cells = children(row)
        let spans = 0
        for (const cell of cells) {
          if (cell.tagName !== 'cell') fail('unsupported', '<' + cell.tagName + '>')
          validate(cell, 'inline', depth + 1)
          spans += integer(cell, 'column-span', 1, 12)
        }
        if (spans !== columns.length) fail('structure', '<' + row.tagName + '> column-span')
      }
      if (!bodyRows || name === 'rows' && bodyRows !== 1) fail('structure', '<' + name + '>')
    } else if (name === 'page-break') {
      attrs(element, ['id'])
      empty(element)
    } else fail('unsupported', '<' + name + '>')
  }
  for (const node of xml.childNodes) {
    if (![1, 8].includes(node.nodeType) && (node.nodeType !== 3 || node.textContent.trim())) fail('declarations')
  }
  validate(root, 'root')
  const output = document.implementation.createHTMLDocument('')
  const title = interpolation(attribute(root, 'title', lang === 'zh' ? '文档预览' : 'Document preview'), [data], true)
  // Bind author as well so invalid metadata does not pass preview validation.
  interpolation(attribute(root, 'author', ''), [data], true)
  output.title = title
  output.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en'
  const meta = output.createElement('meta')
  meta.httpEquiv = 'Content-Security-Policy'
  meta.content = "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; script-src 'none'; font-src 'none'; base-uri 'none'; form-action 'none'"
  output.head.prepend(meta)
  const style = output.createElement('style')
  style.textContent = 'html{background:transparent;color:#202127;color-scheme:light}*{box-sizing:border-box}body{margin:0;padding:24px;font-family:Arial,"Microsoft YaHei",sans-serif;font-size:11pt;line-height:1.5}.page{background:white;margin:0 auto 24px;box-shadow:0 2px 12px #191c2814;overflow-wrap:anywhere}.page:last-child{margin-bottom:0}p,h1,h2,h3,h4,h5,h6{margin:0 0 9pt;font-weight:400;white-space:pre-wrap}h1,h2,h3,h4,h5,h6{font-weight:700}table{width:100%;border-collapse:collapse;table-layout:fixed;margin:8pt 0 12pt}td,th{border:.6pt solid #d8d9df;vertical-align:top;font-weight:400;white-space:pre-wrap;overflow-wrap:anywhere}th{background:#f7f7f8;font-weight:700}.no-borders td,.no-borders th{border:0}.bullet{display:flex;gap:7pt}.bullet-text{white-space:pre-wrap;min-width:0}.bullet-marker{flex:none}@media print{html{background:white}body{padding:0}.page{box-shadow:none;margin:0;break-after:page}.page:last-child{break-after:auto}}'
  output.head.append(style)
  let currentPage
  let pages = 0
  function addPage() {
    if (++pages > 20) fail('limit', lang === 'zh' ? '20 页' : '20 pages')
    currentPage = output.createElement('article')
    currentPage.className = 'page'
    currentPage.setAttribute('aria-label', (lang === 'zh' ? '显式页面 ' : 'Explicit page ') + pages)
    currentPage.style.width = page.width + 'pt'
    currentPage.style.minHeight = page.height + 'pt'
    currentPage.style.padding = [page.top, page.right, page.bottom, page.left].map(value => value + 'pt').join(' ')
    output.body.append(currentPage)
  }
  function charge() {
    if (++budget.nodes > 10000) fail('limit', lang === 'zh' ? '展开节点' : 'expanded nodes')
  }
  function inline(element, target, scopes) {
    for (const child of children(element, true)) {
      if (child.nodeType !== 1) {
        const text = interpolation(child.textContent, scopes, true)
        charge()
        target.append(output.createTextNode(text))
      } else {
        charge()
        const span = output.createElement('span')
        basicStyle(child, span)
        inline(child, span, scopes)
        target.append(span)
      }
    }
  }
  function iterate(element, scopes, visit) {
    const key = element.getAttribute('items')
    const items = lookup(key, scopes)
    if (!Array.isArray(items)) fail('list', key)
    if (items.length > 500 || (budget.iterations += items.length) > 2000) fail('limit', lang === 'zh' ? '循环条目' : 'loop items')
    for (const item of items) {
      const scope = Object.create(null)
      scope[element.getAttribute('as')] = item
      visit([...scopes, scope])
    }
    return items.length
  }
  function table(element, scopes) {
    const target = output.createElement('table')
    if (!flag(element, 'draw-borders', true)) target.className = 'no-borders'
    for (const [name, property] of [['space-before', 'marginTop'], ['space-after', 'marginBottom']]) {
      if (element.hasAttribute(name)) target.style[property] = numeric(element, name, 0) + 'pt'
    }
    const parts = children(element)
    const columns = children(parts[0])
    const available = page.width - page.left - page.right
    const fixed = columns.reduce((sum, column) => sum + (column.hasAttribute('width') ? numeric(column, 'width', 0) : 0), 0)
    const weight = columns.reduce((sum, column) => sum + (column.hasAttribute('weight') ? numeric(column, 'weight', 0) : 0), 0)
    if (fixed > available || weight > 0 && fixed >= available) fail('columnWidth')
    target.style.width = (weight > 0 ? available : fixed) + 'pt'
    const colgroup = output.createElement('colgroup')
    for (const column of columns) {
      const col = output.createElement('col')
      const width = column.hasAttribute('width') ? numeric(column, 'width', 0) : (available - fixed) * numeric(column, 'weight', 0) / weight
      col.style.width = width + 'pt'
      colgroup.append(col)
    }
    target.append(colgroup)
    const head = output.createElement('thead')
    const body = output.createElement('tbody')
    function row(source, rowScopes, header) {
      charge()
      const tr = output.createElement('tr')
      for (const cell of children(source)) {
        charge()
        const td = output.createElement(header ? 'th' : 'td')
        td.colSpan = integer(cell, 'column-span', 1, 12)
        basicStyle(cell, td)
        td.style.padding = ['top', 'right', 'bottom', 'left'].map(side => numeric(element, 'padding-' + side, 5, 0, 72) + 'pt').join(' ')
        inline(cell, td, rowScopes)
        tr.append(td)
      }
      ;(header ? head : body).append(tr)
    }
    for (const source of parts.slice(1).filter(item => item.tagName === 'header')) row(source, scopes, true)
    const rows = parts.slice(1).filter(item => item.tagName === 'row')
    if (element.tagName === 'rows') {
      if (!iterate(element, scopes, local => row(rows[0], local, false))) fail('emptyRows')
    } else for (const source of rows) row(source, scopes, false)
    if (head.childNodes.length) target.append(head)
    target.append(body)
    currentPage.append(target)
  }
  function blocks(elements, scopes) {
    for (const element of elements) {
      const name = element.tagName
      if (name === 'page') continue
      charge()
      if (name === 'each') iterate(element, scopes, local => blocks(children(element), local))
      else if (name === 'if') {
        const key = element.getAttribute('test')
        const value = lookup(key, scopes)
        if (typeof value !== 'boolean') fail('boolean', key)
        const selected = value === (attribute(element, 'operator', 'TRUE') === 'TRUE') ? 0 : 1
        const branch = children(element)[selected]
        if (branch) blocks(children(branch), scopes)
      } else if (name === 'page-break') addPage()
      else if (name === 'table' || name === 'rows') table(element, scopes)
      else {
        const level = name === 'heading' ? integer(element, 'level', 0, 6) : 0
        const target = output.createElement(level ? 'h' + level : 'p')
        const size = numeric(element, 'size', level ? [0, 24, 20, 16, 14, 12, 11][level] : 11, 1, 96)
        basicStyle(element, target, { size, lineHeight: size * 1.5 })
        if (name === 'bullet') {
          target.className = 'bullet'
          target.style.paddingLeft = (integer(element, 'level', 1, 9) - 1) * 14 + 'pt'
          const marker = output.createElement('span')
          marker.className = 'bullet-marker'
          marker.textContent = attribute(element, 'marker', '•')
          const content = output.createElement('span')
          content.className = 'bullet-text'
          inline(element, content, scopes)
          target.append(marker, content)
        } else inline(element, target, scopes)
        currentPage.append(target)
      }
    }
  }
  addPage()
  blocks(children(root), [data])
  return { html: '<!doctype html>\n' + output.documentElement.outerHTML, title, page: { width: page.width, height: page.height }, pages, bindings: budget.bindings }
}
