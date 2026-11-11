// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitepress';
import { thirdPartyNotices } from './third-party-notices.mjs';
import { sidebar, translationRoutes } from './navigation.mjs';

const repository = 'https://github.com/mirakyux/export4j';
const siteUrl = (process.env.DOCS_SITE_URL || 'https://export4j.mirakyux.com').replace(/\/$/, '');
if (siteUrl) {
  const parsed = new URL(siteUrl);
  if (parsed.protocol !== 'https:' || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('DOCS_SITE_URL must be an HTTPS origin without a path, query or fragment.');
  }
}

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
    ['meta', { name: 'theme-color', content: '#ffffff', media: '(prefers-color-scheme: light)' }],
    ['meta', { name: 'theme-color', content: '#151a17', media: '(prefers-color-scheme: dark)' }],
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
          { text: 'Guides', link: '/docs/README' },
          { text: 'FAQ', link: '/docs/faq' },
          { text: 'Examples', link: '/#gallery-title' }
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
          { text: '示例', link: '/zh/#gallery-title' }
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
      '/docs/': sidebar('en'),
      '/getting-started': sidebar('en'),
      '/zh/docs/': sidebar('zh'),
      '/zh/getting-started': sidebar('zh')
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
    const translations = translationRoutes(relative);
    pageData.frontmatter.translations = translations;
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
      for (const [language, translatedRoute] of Object.entries(translations)) {
        head.push(['link', { rel: 'alternate', hreflang: language === 'zh' ? 'zh-CN' : 'en',
          href: `${siteUrl}${translatedRoute}` }]);
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
