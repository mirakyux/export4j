// SPDX-License-Identifier: Apache-2.0
// Prepare tracked project documentation for the public VitePress site.
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pagesDirectory = path.join(root, 'site', 'pages');
const repository = 'https://github.com/mirakyux/export4j';
const tracked = new Set(execFileSync('git', ['ls-files', '-z'], {
  cwd: root, encoding: 'utf8',
}).split('\0').filter(Boolean));
const documents = new Set([...tracked].filter(name => name.startsWith('docs/') && name.endsWith('.md')));
const quickstarts = new Map([
  ['README.md', '/getting-started.md'],
  ['README.zh-CN.md', '/zh/getting-started.md'],
]);

// These public guides and assets are included before their first commit as well.
for (const name of ['docs/faq.md', 'docs/faq.zh-CN.md', 'docs/documentation-site.md']) {
  await stat(path.join(root, name));
  documents.add(name);
}
const showcase = 'site/pages/public/showcase/README.md';
await stat(path.join(root, showcase));
const publicPages = new Map([
  [showcase, '/zh/docs/showcase.md'],
  ['third-party/docs-site-licenses/README.md', '/zh/docs/site-licenses.md'],
]);
const documentRoutes = new Map([...documents].map(source => [source,
  source === 'docs/faq.md' ? '/docs/faq.md'
    : source === 'docs/faq.zh-CN.md' ? '/zh/docs/faq.md' : `/zh/${source}`,
]));
const routes = new Map([...documentRoutes, ...quickstarts, ...publicPages]);
const publicNotices = new Set([
  'third-party/docs-site-licenses/docsearch-3.8.2-LICENSE',
  'third-party/docs-site-licenses/simple-icons-16.37.0-LICENSE.md',
]);

const { createMarkdownRenderer, disposeMdItInstance } = await import(pathToFileURL(path.join(
  root, 'site', 'node_modules', 'vitepress', 'dist', 'node', 'index.js',
)).href);
const markdownRenderer = await createMarkdownRenderer(path.join(root, 'docs'), {
  highlight: text => text,
});
const markdownSources = new Map();
const anchorsBySource = new Map();
const anchorKey = value => value.normalize('NFKD').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
for (const source of new Set([...documents, ...quickstarts.keys(), ...publicPages.keys()])) {
  const markdown = (await readFile(path.join(root, source), 'utf8')).replaceAll('\r\n', '\n');
  markdownSources.set(source, markdown);
  const anchors = new Set();
  const tokens = markdownRenderer.parse(markdown, {});
  for (const token of tokens) {
    const id = token.attrGet('id');
    if (id) anchors.add(id);
    if (token.type === 'html_block' || token.type === 'inline') {
      const html = token.type === 'html_block' ? token.content
        : (token.children ?? []).filter(child => child.type === 'html_inline').map(child => child.content).join('');
      for (const match of html.matchAll(/\bid\s*=\s*["']([^"']+)["']/g)) anchors.add(match[1]);
    }
  }
  anchorsBySource.set(source, anchors);
}
disposeMdItInstance();

function siteSuffix(query, fragment, target, source) {
  if (!fragment) return query ?? '';
  const anchor = decodeURIComponent(fragment.slice(1));
  const anchors = anchorsBySource.get(target);
  if (anchors.has(anchor)) return `${query ?? ''}${fragment}`;
  const candidates = [...anchors].filter(id => anchorKey(id) === anchorKey(anchor));
  if (candidates.length !== 1) {
    throw new Error(`Missing or ambiguous heading anchor in ${source}: ${target}${fragment}`);
  }
  return `${query ?? ''}#${candidates[0]}`;
}

function within(parent, candidate) {
  const relative = path.relative(parent, candidate);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

function repositoryUrl(name, fragment) {
  const isDirectory = [...tracked].some(entry => entry.startsWith(`${name}/`));
  if (!tracked.has(name) && !isDirectory) {
    throw new Error(`Documentation references an untracked repository path: ${name}`);
  }
  const encoded = name.split('/').map(encodeURIComponent).join('/');
  return `${repository}/${isDirectory ? 'tree' : 'blob'}/main/${encoded}${fragment}`;
}

function rewriteTarget(target, source) {
  const enclosed = target.startsWith('<') && target.endsWith('>');
  const value = enclosed ? target.slice(1, -1) : target;
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(value)) return target;
  const match = /^([^?#]*)(\?[^#]*)?(#.*)?$/.exec(value);
  if (!match) return target;
  if (!match[1]) return siteSuffix(match[2], match[3], source, source);
  let pathname;
  try {
    pathname = decodeURIComponent(match[1]);
  } catch {
    throw new Error(`Invalid URL encoding in ${source}: ${target}`);
  }
  const absolute = path.resolve(root, path.dirname(source), pathname);
  if (!within(root, absolute)) throw new Error(`Link escapes the repository in ${source}: ${target}`);
  const relative = path.relative(root, absolute).split(path.sep).join('/');
  const suffix = `${match[2] ?? ''}${match[3] ?? ''}`;
  if (publicNotices.has(relative)) return '/THIRD-PARTY-NOTICES.txt';
  if (routes.has(relative)) {
    const rewritten = `${routes.get(relative)}${siteSuffix(match[2], match[3], relative, source)}`;
    return enclosed ? `<${rewritten}>` : rewritten;
  }
  if (relative === 'docs/verification') {
    return `/zh/docs/verification/index.md${suffix}`;
  }
  if (relative === 'docs/adr') {
    return `/zh/docs/adr/README.md${suffix}`;
  }
  const url = repositoryUrl(relative, suffix);
  return enclosed ? `<${url}>` : url;
}

function rewriteProse(prose, source) {
  // Read complete links before inline code so code-formatted labels stay links.
  const tokens = /(!?\[[^\]\n]*\]\()([^\s)]+)(\))|(`+)([\s\S]*?)\4(?!`)/g;
  const escapeGenerics = text => text.replace(/\b([A-Z][A-Za-z\d]*)<([A-Z][A-Za-z\d., ]*)>/g, '`$1<$2>`');
  let result = '';
  let cursor = 0;
  for (const match of prose.matchAll(tokens)) {
    result += escapeGenerics(prose.slice(cursor, match.index));
    result += match[1]
      ? `${match[1]}${rewriteTarget(match[2], source)}${match[3]}`
      : match[0];
    cursor = match.index + match[0].length;
  }
  return result + escapeGenerics(prose.slice(cursor));
}

function prepareMarkdown(markdown, source, metadata = {}) {
  let fence = null;
  const hideLanguageLine = quickstarts.has(source) || source === 'docs/faq.md' || source === 'docs/faq.zh-CN.md';
  const rewritten = markdown.split('\n').map(line => {
    const marker = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
    if (marker) {
      if (!fence) fence = { character: marker[1][0], length: marker[1].length };
      else if (marker[1][0] === fence.character && marker[1].length >= fence.length && !marker[2].trim()) fence = null;
      return line;
    }
    if (fence) return line;
    if (hideLanguageLine && /^(?:\*\*English\*\*\s*\|\s*\[简体中文\]|\[English\]\([^)]*\)\s*\|\s*\*\*简体中文\*\*)/.test(line)) return null;
    return rewriteProse(line, source);
  }).filter(line => line !== null).join('\n');
  if (rewritten.startsWith('---\n')) return rewritten;
  const frontmatter = Object.entries({
    lang: source === 'docs/faq.md' ? 'en-US' : 'zh-CN', ...metadata,
  }).map(([key, value]) => `${key}: ${JSON.stringify(value)}`).join('\n');
  return `---\n${frontmatter}\n---\n\n${rewritten}`;
}

// Only generated documentation directories are replaced; authored home pages stay intact.
for (const relative of ['docs', 'zh/docs']) {
  const destination = path.join(pagesDirectory, relative);
  if (!within(pagesDirectory, destination)) throw new Error('Invalid output directory');
  await rm(destination, { recursive: true, force: true });
  await mkdir(destination, { recursive: true });
}

for (const source of [...documents].sort()) {
  const target = path.join(pagesDirectory, documentRoutes.get(source).slice(1));
  await mkdir(path.dirname(target), { recursive: true });
  const markdown = markdownSources.get(source);
  await writeFile(target, prepareMarkdown(markdown, source), 'utf8');
}

const verification = [...documents].filter(name => name.startsWith('docs/verification/')).sort();
const verificationIndex = ['---', 'lang: zh-CN', '---', '', '# 验收记录', '',
  '各记录保留执行时的版本、样例、检查方法与限制。当前支持状态以[能力矩阵](/zh/docs/capabilities-api.md)和[能力缺口表](/zh/docs/capability-gaps.md)为准。', ''];
for (const source of verification) {
  const markdown = markdownSources.get(source);
  const title = /^#\s+(.+)$/m.exec(markdown)?.[1] ?? path.basename(source, '.md');
  verificationIndex.push(`- [${title}](${documentRoutes.get(source)})`);
}
await writeFile(path.join(pagesDirectory, 'zh/docs/verification/index.md'), `${verificationIndex.join('\n')}\n`, 'utf8');

for (const [source, route] of quickstarts) {
  const target = path.join(root, 'site', 'pages', route.slice(1));
  await mkdir(path.dirname(target), { recursive: true });
  const chinese = source === 'README.zh-CN.md';
  const markdown = markdownSources.get(source);
  await writeFile(target, prepareMarkdown(markdown, source, {
    title: chinese ? '快速开始' : 'Getting started',
    lang: chinese ? 'zh-CN' : 'en-US',
    description: chinese
      ? '使用 Maven 或 Gradle 接入 Export4j，在 Java 21 中生成 PDF 和导出文档，包含显式字体与完整示例。'
      : 'Install Export4j with Maven or Gradle and generate PDF documents in Java 21, with explicit fonts and a complete example.',
  }), 'utf8');
}

for (const [source, route] of publicPages) {
  const target = path.join(root, 'site', 'pages', route.slice(1));
  await writeFile(target, prepareMarkdown(markdownSources.get(source), source, {
    lang: 'zh-CN',
  }), 'utf8');
}

const redirects = new Map([
  ['/docs', '/zh/docs/README'],
  ['/docs/', '/zh/docs/README'],
  ['/docs/verification/', '/zh/docs/verification/'],
  ['/docs/verification/index', '/zh/docs/verification/'],
  ['/docs/verification/index.html', '/zh/docs/verification/'],
]);
for (const [source, route] of [...documentRoutes, ...publicPages]) {
  const oldRoute = source.startsWith('docs/') ? `/${source}`
    : source === showcase ? '/docs/showcase.md' : '/docs/site-licenses.md';
  if (oldRoute === route) continue;
  const oldClean = oldRoute.replace(/\.md$/, '');
  const newClean = route.replace(/\.md$/, '');
  redirects.set(oldClean, newClean);
  redirects.set(`${oldClean}.html`, newClean);
}
await writeFile(path.join(pagesDirectory, 'public', '_redirects'),
  `${[...redirects].map(([from, to]) => `${from} ${to} 301`).join('\n')}\n`, 'utf8');

console.log(`Prepared ${documents.size} public documentation pages, the verification index, two getting-started pages and ${publicPages.size} public resource guides.`);
