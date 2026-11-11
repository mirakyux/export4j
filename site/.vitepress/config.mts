// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitepress';
import { thirdPartyNotices } from './third-party-notices.mjs';

const repository = 'https://github.com/mirakyux/export4j';
const siteUrl = (process.env.DOCS_SITE_URL || 'https://export4j.mirakyux.com').replace(/\/$/, '');
if (siteUrl) {
  const parsed = new URL(siteUrl);
  if (parsed.protocol !== 'https:' || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('DOCS_SITE_URL must be an HTTPS origin without a path, query or fragment.');
  }
}

const link = (text: string, page: string) => ({ text, link: `/zh/docs/${page}` });
const groups = [
  { text: '开始使用', items: [
    { text: '安装与第一个 PDF', link: '/zh/getting-started' },
    link('文档导航', 'README'), link('常见问题', 'faq'),
    link('能力矩阵', 'capabilities-api'), link('示例', 'examples')
  ] },
  { text: '编写与样式', collapsed: false, items: [
    link('常用编写', 'authoring-api'), link('链式 API', 'fluent-api'),
    link('字体与文字', 'text-api'), link('主题', 'themes-api'),
    link('图片与行内内容', 'inline-images-api'), link('页眉页脚', 'page-variants-api')
  ] },
  { text: '表格与分页', collapsed: true, items: [
    link('语义表格', 'table-api'), link('合并单元格', 'column-spans-api'),
    link('长表续页', 'row-spans-api'), link('正文分栏', 'column-flow-api'),
    link('可编辑栅格', 'grid-layout-api'), link('逐页导出', 'paged-document-api'),
    link('流式长表', 'streaming-table-api'), link('重放与磁盘索引 PDF', 'streaming-replay-api')
  ] },
  { text: '模板与设计器', collapsed: true, items: [
    link('XML 模板', 'xml-template-api'), link('页面设置', 'xml-page-settings-api'),
    link('模板资源包', 'xml-template-bundle-api'), link('Markdown 模板', 'markdown-template-api'),
    link('可视化设计器', 'designer'), link('文档模型恢复', 'model-recovery-api')
  ] },
  { text: '格式与转换', collapsed: true, items: [
    link('统一转换', 'document-conversion-api'), link('DOCX 语义与编辑', 'docx-semantic-api'),
    link('Word 公式', 'docx-math-api'), link('HTML/CSS 导入', 'html-import-api'),
    link('Markdown 导入', 'markdown-import-api'), link('HTML 与 Markdown 导出', 'web-export-api')
  ] },
  { text: 'PDF 处理', collapsed: true, items: [
    link('处理入口', 'pdf-adapter-api'), link('页面编辑', 'pdf-page-editing-api'),
    link('文字替换', 'pdf-text-editing-api'), link('图片替换', 'pdf-image-editing-api'),
    link('语义恢复', 'pdf-semantic-import-api'), link('脱敏', 'pdf-redaction-api'),
    link('PDF/A、PDF/UA 与签名', 'pdf-standards-api')
  ] },
  { text: '项目与验证', collapsed: true, items: [
    link('架构', 'architecture'), link('开发规约', 'development'),
    link('依赖与许可证', 'dependencies'), link('发布', 'releasing'),
    link('RC2 说明', 'releases/0.1.0-rc.2'), link('剩余能力', 'capability-gaps'),
    link('架构决策', 'adr/README'), link('验收记录', 'verification/'),
    link('文档站维护', 'documentation-site')
  ] }
];

export default defineConfig({
  vite: { plugins: [thirdPartyNotices()] },
  srcDir: 'pages',
  srcExclude: ['public/**'],
  title: 'Export4j',
  titleTemplate: ':title · Export4j',
  description: 'Java PDF generation and document export. Build reports with a fluent Java API or XML templates. PDF, DOCX, HTML, Markdown, SVG and PNG.',
  cleanUrls: true,
  lastUpdated: false,
  appearance: true,
  sitemap: siteUrl ? { hostname: siteUrl } : undefined,
  head: [
    ['meta', { name: 'theme-color', content: '#f7f7f4' }],
    ['meta', { name: 'color-scheme', content: 'light dark' }]
  ],
  markdown: {
    config(md) {
      // Template syntax in API documentation is literal, including inline code.
      md.renderer.rules.code_inline = (tokens, index) =>
        `<code v-pre>${md.utils.escapeHtml(tokens[index].content)}</code>`;
    }
  },
  locales: {
    root: {
      label: 'English', lang: 'en-US',
      themeConfig: {
        nav: [
          { text: 'Get started', link: '/getting-started' },
          { text: 'Guides', link: '/zh/docs/README' },
          { text: 'FAQ', link: '/docs/faq' },
          { text: 'RC2', link: `${repository}/releases/tag/v0.1.0-rc.2` }
        ]
      }
    },
    zh: {
      label: '简体中文', lang: 'zh-CN', link: '/zh/',
      description: 'Java PDF 生成与文档导出引擎。使用链式 Java API 或 XML 模板生成报告、表单与结构化文档。',
      themeConfig: {
        nav: [
          { text: '开始使用', link: '/zh/getting-started' },
          { text: '指南', link: '/zh/docs/README' },
          { text: '常见问题', link: '/zh/docs/faq' },
          { text: 'RC2', link: `${repository}/releases/tag/v0.1.0-rc.2` }
        ],
        outline: { level: [2, 3], label: '本页目录' },
        docFooter: { prev: '上一页', next: '下一页' },
        darkModeSwitchLabel: '外观',
        lightModeSwitchTitle: '切换为浅色模式',
        darkModeSwitchTitle: '切换为深色模式',
        sidebarMenuLabel: '目录',
        returnToTopLabel: '返回顶部',
        langMenuLabel: '切换语言',
        skipToContentLabel: '跳到正文',
        footer: {
          message: 'Apache-2.0 · Java 21 · 候选版本 · <a href="/THIRD-PARTY-NOTICES.txt">第三方许可</a>',
          copyright: 'Export4j'
        }
      }
    }
  },
  themeConfig: {
    siteTitle: 'Export4j',
    i18nRouting: false,
    nav: [],
    sidebar: {
      '/zh/docs/': groups,
      '/docs/': [{ text: 'Start here', items: [
        { text: 'Getting started', link: '/getting-started' },
        { text: 'Frequently asked questions', link: '/docs/faq' },
        link('Guides (中文)', 'README')
      ] }],
      '/getting-started': [
        { text: 'Start here', items: [
          { text: 'Getting started', link: '/getting-started' },
          { text: 'Frequently asked questions', link: '/docs/faq' }, link('Guides (中文)', 'README'),
          link('Examples', 'examples')
        ] }
      ],
      '/zh/getting-started': groups
    },
    search: { provider: 'local', options: {
      miniSearch: { searchOptions: { fuzzy: 0.2 } },
      locales: { zh: { translations: {
        button: { buttonText: '搜索', buttonAriaLabel: '搜索文档' },
        modal: {
          displayDetails: '显示详细结果', resetButtonTitle: '清空搜索',
          backButtonTitle: '返回', noResultsText: '未找到相关结果',
          footer: {
            selectText: '选择', selectKeyAriaLabel: '回车键',
            navigateText: '切换结果', navigateUpKeyAriaLabel: '向上键',
            navigateDownKeyAriaLabel: '向下键', closeText: '关闭', closeKeyAriaLabel: 'Esc 键'
          }
        }
      } } }
    } },
    socialLinks: [{ icon: 'github', link: repository }],
    outline: { level: [2, 3], label: 'On this page' },
    footer: {
      message: 'Apache-2.0 · Java 21 · Release candidate · <a href="/THIRD-PARTY-NOTICES.txt">Third-party notices</a>',
      copyright: 'Export4j'
    }
  },
  transformPageData(pageData) {
    const relative = pageData.relativePath;
    const chinese = relative.startsWith('zh/');
    pageData.frontmatter.lang = chinese ? 'zh-CN' : 'en-US';
    if (!pageData.description) {
      pageData.description = chinese
        ? `${pageData.title}：Export4j Java 文档生成引擎的用法、支持范围与示例。`
        : `${pageData.title}: Export4j Java document generation, supported features and examples.`;
    }
    const head = pageData.frontmatter.head ??= [];
    head.push(['meta', { property: 'og:title', content: `${pageData.title} · Export4j` }]);
    head.push(['meta', { property: 'og:description', content: pageData.description }]);
    head.push(['meta', { property: 'og:type', content: 'website' }]);
    if (siteUrl && relative !== '404.md') {
      const route = relative.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '');
      const url = `${siteUrl}/${route}`;
      head.push(['link', { rel: 'canonical', href: url }]);
      head.push(['meta', { property: 'og:url', content: url }]);
      const pairs: Record<string, string> = {
        'index.md': 'zh/index.md', 'zh/index.md': 'index.md',
        'getting-started.md': 'zh/getting-started.md', 'zh/getting-started.md': 'getting-started.md',
        'docs/faq.md': 'zh/docs/faq.md', 'zh/docs/faq.md': 'docs/faq.md'
      };
      if (pairs[relative]) {
        for (const file of [relative, pairs[relative]]) {
          const lang = file.startsWith('zh/') || file.endsWith('.zh-CN.md') ? 'zh-CN' : 'en';
          head.push(['link', { rel: 'alternate', hreflang: lang,
            href: `${siteUrl}/${file.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '')}` }]);
        }
      }
    }
  },
  async buildEnd(siteConfig) {
    if (siteUrl) {
      const { writeFile } = await import('node:fs/promises');
      await writeFile(`${siteConfig.outDir}/robots.txt`, `User-agent: *\nAllow: /\nSitemap: ${siteUrl}/sitemap.xml\n`);
    }
  }
});
