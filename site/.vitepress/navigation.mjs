// SPDX-License-Identifier: Apache-2.0
// Sidebar coverage and translation coverage are independent: untranslated guides
// remain discoverable, with their language explicit in the English navigation.
const group = (en, zh, items) => ({ en, zh, items });
const page = (name, en, zh) => ({ name, en, zh });
const reference = (name, en, zh) => ({ name, en, zh, translated: false });

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
    reference('native-opentype-api', 'Native OpenType', '原生 OpenType'),
    reference('text-corpus-api', 'Text and reader corpus', '文字与阅读器语料'),
    reference('vertical-text-api', 'Vertical text', '竖排文字'),
    reference('list-markers-api', 'List markers', '列表编号与项目符号'),
    reference('production-api', 'Production API', '生产能力 API')
  ]),
  group('Components API', '组件 API', [
    page('components-api', 'Components overview', '组件总览'),
    page('component-text-api', 'Component fonts and rich text', '组件字体与富文本'),
    page('semantic-components-api', 'Semantic composition', '语义组件组合'),
    page('style-defaults-api', 'Inherited component styles', '组件默认样式'),
    page('static-forms-api', 'Static forms', '静态表单'),
    page('form-styles-api', 'Editable form styles', '可编辑表单样式')
  ]),
  group('Layout and pages', '布局与页面', [
    reference('layout-api', 'Document layout', '文档布局'),
    reference('advanced-api', 'Advanced layout and semantics', '进阶布局与语义'),
    page('column-flow-api', 'Body columns', '正文分栏'),
    page('grid-layout-api', 'Editable grids', '可编辑栅格'),
    reference('continuous-columns-api', 'Continuous columns', '连续分栏'),
    reference('mixed-columns-api', 'Mixed component columns', '混合组件分栏'),
    reference('grid-continuation-api', 'Grid continuation', '栅格与合并格续页'),
    reference('grid-semantic-flow-api', 'Semantic flow in merged cells', '合并格语义续排'),
    reference('multi-floats-api', 'Multiple floating components', '多浮动组件'),
    reference('contour-floats-api', 'Contour floats', '轮廓浮动'),
    page('page-variants-api', 'Page headers and footers', '页眉页脚'),
    reference('page-overlays-api', 'Page backgrounds and foregrounds', '页面背景与前景'),
    reference('page-region-fonts-api', 'Page-region font roles', '固定页区字体角色')
  ]),
  group('Fields and navigation', '字段与导航', [
    reference('inline-fields-api', 'Inline fields and references', '行内字段与引用'),
    reference('portable-navigation-api', 'Contents and bookmarks', '目录与书签'),
    reference('contents-styles-api', 'Contents styles', '目录样式')
  ]),
  group('Tables', '表格', [
    page('table-api', 'Semantic tables', '语义表格'),
    page('column-spans-api', 'Column spans', '合并列'),
    page('row-spans-api', 'Row spans', '合并行'),
    reference('block-cells-api', 'Block cells and nested tables', '块格与嵌套表格'),
    reference('automatic-merged-columns-api', 'Automatic widths in merged cells', '合并格自动列宽'),
    reference('nested-automatic-tables-api', 'Automatic widths in nested tables', '内表自动列宽'),
    reference('variable-tables-api', 'Tables in variable-width regions', '变宽区域表格'),
    reference('variable-table-continuation-api', 'Variable-width table continuation', '变宽表格续排')
  ]),
  group('Streaming and pagination', '流式与分页', [
    page('paged-document-api', 'Page-at-a-time export', '逐页导出'),
    page('streaming-table-api', 'Streaming tables', '流式长表'),
    page('streaming-replay-api', 'Replay and PDF spooling', '重放与磁盘索引 PDF'),
    reference('streaming-row-split-api', 'Streaming row splitting', '流式拆行分页'),
    reference('streaming-row-spans-api', 'Streaming row spans', '流式跨行合并'),
    reference('streaming-rich-text-api', 'Streaming rich text', '流式富文本'),
    reference('streaming-fields-api', 'Streaming page-number fields', '流式页码字段'),
    reference('streaming-media-api', 'Streaming images and links', '流式图片与链接'),
    reference('streaming-protection-api', 'Streaming encryption and archival', '流式加密与归档')
  ]),
  group('Templates', '模板', [
    page('xml-template-api', 'XML templates', 'XML 模板'),
    page('xml-page-settings-api', 'Page settings', '页面设置'),
    page('xml-template-bundle-api', 'Template bundles', '模板资源包'),
    page('markdown-template-api', 'Markdown templates', 'Markdown 模板'),
    reference('xml-themes-api', 'XML themes', 'XML 主题'),
    reference('xml-text-defaults-api', 'XML default fonts and colors', 'XML 默认字体与颜色'),
    reference('xml-page-regions-api', 'XML headers and footers', 'XML 页眉页脚'),
    reference('xml-sections-api', 'XML sections', 'XML 分节'),
    reference('xml-numbering-api', 'XML numbering', 'XML 多级编号'),
    reference('xml-includes-api', 'XML fragments and includes', 'XML 片段与 include'),
    reference('xml-block-cells-api', 'XML block cells and nested tables', 'XML 块格与嵌套表格')
  ]),
  group('Designer and model recovery', '设计器与模型恢复', [
    page('designer', 'Visual template designer', '可视化设计器'),
    page('model-recovery-api', 'Document model recovery', '文档模型恢复'),
    reference('xml-model-saving-api', 'Save models as XML', '语义模型保存为 XML')
  ]),
  group('Formats and conversion', '格式与转换', [
    page('document-conversion-api', 'Document conversion', '统一转换'),
    reference('semantic-editing-api', 'Semantic editing and loss reports', '语义编辑与损失报告'),
    page('html-import-api', 'HTML/CSS import', 'HTML/CSS 导入'),
    page('markdown-import-api', 'Markdown import', 'Markdown 导入'),
    page('web-export-api', 'HTML and Markdown export', 'HTML 与 Markdown 导出')
  ]),
  group('DOCX processing', 'DOCX 处理', [
    page('docx-semantic-api', 'DOCX semantics and editing', 'DOCX 语义与编辑'),
    page('docx-math-api', 'Word math', 'Word 公式'),
    reference('docx-editing-api', 'Original-package text editing', '原包文字编辑'),
    reference('docx-page-regions-api', 'DOCX headers and footers', 'DOCX 页眉页脚'),
    reference('docx-fields-api', 'DOCX fields', 'DOCX 字段'),
    reference('docx-style-import-api', 'DOCX style import', 'DOCX 样式导入')
  ]),
  group('PDF processing', 'PDF 处理', [
    page('pdf-adapter-api', 'PDF processing API', '处理入口'),
    page('pdf-page-editing-api', 'Page editing', '页面编辑'),
    reference('pdf-object-editing-api', 'Forms and interactive objects', '表单与交互对象'),
    reference('pdf-object-composition-api', 'Object-preserving composition', '保留对象的选页与合并'),
    page('pdf-text-editing-api', 'Text replacement', '文字替换'),
    page('pdf-image-editing-api', 'Image replacement', '图片替换'),
    page('pdf-semantic-import-api', 'Semantic recovery', '语义恢复'),
    page('pdf-redaction-api', 'Redaction', '脱敏'),
    reference('pdf-raster-api', 'Page rendering and image extraction', '页面渲染与图片提取'),
    reference('pdf-optimization-api', 'PDF optimization', 'PDF 优化'),
    reference('pdf-font-compatibility-api', 'Source-font compatibility', '源字体兼容'),
    reference('pdf-passive-structures-api', 'Layers, tags and passive structures', '图层、标签与被动结构')
  ]),
  group('PDF standards and forms', 'PDF 标准与表单', [
    page('pdf-standards-api', 'PDF standards and signing', 'PDF/A、PDF/UA 与签名'),
    reference('pdf-certification-api', 'Certification and DocMDP', '认证与 DocMDP'),
    reference('pdf-signature-verification-api', 'Signature integrity verification', '签名完整性校验'),
    reference('pdf-form-data-api', 'FDF and XFDF form data', 'FDF 与 XFDF 表单数据'),
    reference('pdf-xfa-api', 'XFA reading and flattening', 'XFA 读取与扁平化')
  ]),
  group('Graphics', '图形', [
    reference('graphics-api', 'Drawing API', '绘制 API'),
    reference('graphics-opacity-api', 'Opacity and SVG transforms', '透明度与 SVG 变换'),
    reference('advanced-graphics-api', 'Advanced graphics', '高级图形')
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

export const translatedGuides = guideGroups.flatMap(group => group.items
  .filter(item => item.translated !== false).map(item => item.name));

export function sidebar(language) {
  const prefix = language === 'zh' ? '/zh/docs/' : '/docs/';
  return guideGroups.map((group, index) => ({
    text: group[language],
    ...(index !== 0 && index !== 2 ? { collapsed: true } : {}),
    items: [
      ...(index === 0 ? [
        { text: language === 'zh' ? '安装与第一个 PDF' : 'Install and generate a PDF',
          link: language === 'zh' ? '/zh/getting-started' : '/getting-started' },
        { text: language === 'zh' ? '常见问题' : 'FAQ', link: `${prefix}faq` }
      ] : []),
      ...group.items.map(item => ({
        text: language === 'en' && item.translated === false ? `${item.en} (Chinese)` : item[language],
        link: `${item.translated === false ? '/zh/docs/' : prefix}${item.name}`
      }))
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
