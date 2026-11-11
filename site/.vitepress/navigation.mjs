// SPDX-License-Identifier: Apache-2.0
// A navigation entry is published in both languages only with a maintained source page.
const group = (en, zh, items) => ({ en, zh, items });
const page = (name, en, zh) => ({ name, en, zh });

export const guideGroups = [
  group('Start here', '开始使用', [
    page('README', 'Documentation', '文档导航'),
    page('capabilities-api', 'Capabilities', '能力矩阵'),
    page('examples', 'Examples', '示例')
  ]),
  group('Authoring and style', '编写与样式', [
    page('authoring-api', 'Author a document', '常用编写'),
    page('fluent-api', 'Fluent API', '链式 API'),
    page('text-api', 'Fonts and text', '字体与文字'),
    page('themes-api', 'Themes', '主题'),
    page('inline-images-api', 'Images and inline content', '图片与行内内容'),
    page('page-variants-api', 'Page headers and footers', '页眉页脚')
  ]),
  group('Tables and pagination', '表格与分页', [
    page('table-api', 'Semantic tables', '语义表格'),
    page('column-spans-api', 'Column spans', '合并列'),
    page('row-spans-api', 'Row spans', '合并行'),
    page('column-flow-api', 'Body columns', '正文分栏'),
    page('grid-layout-api', 'Editable grids', '可编辑栅格'),
    page('paged-document-api', 'Page-at-a-time export', '逐页导出'),
    page('streaming-table-api', 'Streaming tables', '流式长表'),
    page('streaming-replay-api', 'Replay and PDF spooling', '重放与磁盘索引 PDF')
  ]),
  group('Templates and designer', '模板与设计器', [
    page('xml-template-api', 'XML templates', 'XML 模板'),
    page('xml-page-settings-api', 'Page settings', '页面设置'),
    page('xml-template-bundle-api', 'Template bundles', '模板资源包'),
    page('markdown-template-api', 'Markdown templates', 'Markdown 模板'),
    page('designer', 'Visual template designer', '可视化设计器'),
    page('model-recovery-api', 'Document model recovery', '文档模型恢复')
  ]),
  group('Formats and conversion', '格式与转换', [
    page('document-conversion-api', 'Document conversion', '统一转换'),
    page('docx-semantic-api', 'DOCX semantics and editing', 'DOCX 语义与编辑'),
    page('docx-math-api', 'Word math', 'Word 公式'),
    page('html-import-api', 'HTML/CSS import', 'HTML/CSS 导入'),
    page('markdown-import-api', 'Markdown import', 'Markdown 导入'),
    page('web-export-api', 'HTML and Markdown export', 'HTML 与 Markdown 导出')
  ]),
  group('PDF processing', 'PDF 处理', [
    page('pdf-adapter-api', 'PDF processing API', '处理入口'),
    page('pdf-page-editing-api', 'Page editing', '页面编辑'),
    page('pdf-text-editing-api', 'Text replacement', '文字替换'),
    page('pdf-image-editing-api', 'Image replacement', '图片替换'),
    page('pdf-semantic-import-api', 'Semantic recovery', '语义恢复'),
    page('pdf-redaction-api', 'Redaction', '脱敏'),
    page('pdf-standards-api', 'PDF standards and signing', 'PDF/A、PDF/UA 与签名')
  ]),
  group('Project', '项目与验证', [
    page('architecture', 'Architecture', '架构'),
    page('development', 'Development', '开发规约'),
    page('dependencies', 'Dependencies and licenses', '依赖与许可证'),
    page('releasing', 'Releases', '发布'),
    page('releases/0.1.0-rc.2', 'RC2 release notes', 'RC2 说明'),
    page('capability-gaps', 'Remaining capabilities', '剩余能力'),
    page('documentation-site', 'Maintain this website', '文档站维护')
  ])
];

export const translatedGuides = guideGroups.flatMap(group => group.items.map(item => item.name));

export function sidebar(language) {
  const prefix = language === 'zh' ? '/zh/docs/' : '/docs/';
  return guideGroups.map((group, index) => ({
    text: group[language],
    ...(index > 1 ? { collapsed: true } : {}),
    items: [
      ...(index === 0 ? [
        { text: language === 'zh' ? '安装与第一个 PDF' : 'Install and generate a PDF',
          link: language === 'zh' ? '/zh/getting-started' : '/getting-started' },
        { text: language === 'zh' ? '常见问题' : 'FAQ', link: `${prefix}faq` }
      ] : []),
      ...group.items.map(item => ({ text: item[language], link: `${prefix}${item.name}` }))
    ]
  }));
}

export function translationRoutes(relative) {
  if (relative === 'index.md' || relative === 'zh/index.md') return { en: '/', zh: '/zh/' };
  if (relative === 'getting-started.md' || relative === 'zh/getting-started.md') {
    return { en: '/getting-started', zh: '/zh/getting-started' };
  }
  const name = relative.replace(/^zh\//, '').replace(/^docs\//, '').replace(/\.md$/, '');
  if (name === 'faq' || translatedGuides.includes(name)) {
    return { en: `/docs/${name}`, zh: `/zh/docs/${name}` };
  }
  return {};
}
