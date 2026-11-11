# 公开文档站

文档站地址为 <https://export4j.mirakyux.com>，使用 VitePress 生成静态 HTML，通过 Cloudflare Workers Static Assets 托管。Java 引擎、设计器应用与 Maven 制品不依赖这个站点。

## 内容与本地预览

需要 Node.js 22.12 或更高版本。以下命令从仓库根目录运行，适用于 PowerShell 和常见 POSIX shell。

```shell
npm --prefix site ci
npm --prefix site run dev
```

打开终端显示的本地地址。英文首页为 `/`，中文首页为 `/zh/`；英文指南位于 `/docs/`，中文指南位于 `/zh/docs/`。导航、侧栏、搜索与主题文案跟随页面语言。语言菜单在首页、快速开始、FAQ 和主侧栏指南间切换对应页面；仅有中文的历史记录或进阶参考切换英文时进入英文文档导航，菜单明确标注这一目的地。正文不重复语言入口。

- `docs/*.md` 维护中文指南，`docs/en/` 维护英文译文；`site/.vitepress/navigation.mjs` 按主题定义侧栏与页面配对。使用 `page(...)` 的指南必须同时提供两种语言，构建缺少译文时失败；仅有中文的进阶参考使用 `reference(...)`，英文侧栏明确标注中文。准备脚本检查所有顶层 `docs/*-api.md` 均已进入侧栏，避免页面只能通过搜索找到；只复制 Git 已跟踪的文档和明确列出的新增公共指南，排除本地研究资料。
- 中英文快速开始由两份根 README 生成；源码、脚本和字体记录链接指向 GitHub，指南链接保留在站内。
- `site/pages/index.md`、`site/pages/zh/index.md` 和 `site/.vitepress/theme/` 维护首页与主题。
- 生成内容位于 `site/pages/docs/`、`site/pages/zh/docs/` 和两份 `getting-started.md`，不提交。修改原始文档后重新执行 `npm --prefix site run prepare:content`。已有英文译文使用 `/docs/*`；仅有中文的旧地址通过生成的 `_redirects` 逐页 301 跳转。英文正文中指向未翻译资料的链接明确标注中文。
- 首页提供周报、报价单、学术论文、履历、合同、使用说明书和会议纪要七种真实输出，配套完整 PDF 与模板／示例入口；来源及边界见[样张说明](../site/pages/public/showcase/README.md)。本地输出与页面截图不能替代 Java 输出验收。

构建保留断链检查；不得以关闭 `ignoreDeadLinks` 掩盖缺失页面。

构建同时从实际浏览器模块、样式和图标收集第三方原始许可，生成站点根路径下的 `THIRD-PARTY-NOTICES.txt`。缺失许可、版本偏离锁文件或补充原文摘要不符时构建失败。主题从 VitePress 随附资源打包 Inter 拉丁字体和 OFL 许可，由本站提供；中文使用本地系统字体。依赖升级须检查生成声明及[补充许可记录](../third-party/docs-site-licenses/README.md)，不能仅保留许可证名称。

## 构建与 Cloudflare 部署

`DOCS_SITE_URL` 为站点的 HTTPS origin，不含路径、查询或片段，默认使用 `https://export4j.mirakyux.com`。构建会据此生成 canonical、中英文互链、站点地图与 `robots.txt`。独立预览域名可通过该环境变量覆盖；正式部署保持默认 origin。

PowerShell：

```powershell
$env:DOCS_SITE_URL = 'https://export4j.mirakyux.com'
npm --prefix site run build
npm --prefix site run deploy:check
```

Linux/macOS：

```sh
DOCS_SITE_URL=https://export4j.mirakyux.com npm --prefix site run build
npm --prefix site run deploy:check
```

登录 Cloudflare 后部署：

```shell
npm --prefix site exec -- wrangler login
npm --prefix site run deploy
```

部署目标为 `site/wrangler.jsonc` 中的 `export4j-docs`，静态目录为 `site/.vitepress/dist/`，无需 Worker 运行脚本、数据库或服务端密钥。未知路径使用真实 404；不配置 SPA 首页回退。`export4j.mirakyux.com` 通过 Worker Custom Domains 配置，部署时由 Cloudflare 管理对应 DNS 与证书。绑定后重新核对 DNS、TLS 与实际 HTTP 响应，不在仓库中保存访问令牌。

## 发布验证

1. 执行锁文件安装和生产构建，检查构建中的断链与编译错误。
2. 使用 `npm --prefix site run preview` 在浏览器实际操作：英文首页进入指南，核对正文及侧栏仍为英文；同一篇指南切换中文再切回英文；展开语言菜单并检查外观、点击外部关闭、Escape、方向键、Tab 和焦点；再检查移动端菜单、溢出、搜索和深色模式。静态链接检查不能替代这些交互。
3. 执行 `deploy:check`，确认只上传静态站点。
4. 部署后检查首页、深层指南、样张 PDF、未知路径的 404、canonical、`sitemap.xml`、`robots.txt` 和 `THIRD-PARTY-NOTICES.txt`。
5. 正式域名确认后更新 GitHub About 中的 Website，以及两份 README 的文档站入口；站点公开与搜索引擎收录分别验证。

静态构建会把正文写入 HTML，搜索与抓取不依赖客户端执行 JavaScript。元数据与实际内容保持一致，不承诺搜索排名或 AI 引用。站点不默认加载分析服务或远程字体。

站点根路径下的 `llms.txt` 汇总入门、API、格式边界和版本资料，供支持该约定的工具读取；它不替代正文、站点地图或搜索引擎收录验证。

配置依据见 [VitePress](https://vuejs.github.io/vitepress/v1/)、[Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/)、[静态站路由](https://developers.cloudflare.com/workers/static-assets/routing/static-site-generation/)及 [Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)。
