# 公开文档站

文档站地址为 <https://export4j.mirakyux.com>，使用 VitePress 生成静态 HTML，通过 Cloudflare Workers Static Assets 托管。Java 引擎、设计器应用与 Maven 制品不依赖这个站点。

## 内容与本地预览

需要 Node.js 22.12 或更高版本。以下命令从仓库根目录运行，适用于 PowerShell 和常见 POSIX shell。

```shell
npm --prefix site ci
npm --prefix site run dev
```

打开终端显示的本地地址。英文首页为 `/`，中文首页为 `/zh/`；英文指南位于 `/docs/`，中文指南位于 `/zh/docs/`。导航、侧栏、搜索与主题文案跟随页面语言。语言菜单在首页、快速开始、模板编辑、FAQ 和主侧栏指南间切换对应页面；仅有中文的历史记录或进阶参考切换英文时进入英文文档导航，菜单明确标注这一目的地。正文不重复语言入口。

- `docs/*.md` 维护中文指南，`docs/en/` 维护英文译文；`site/.vitepress/navigation.mjs` 按主题定义侧栏与页面配对。使用 `page(...)` 的指南必须同时提供两种语言，构建缺少译文时失败；仅有中文的进阶参考使用 `reference(...)`，英文侧栏明确标注中文。准备脚本检查所有顶层 `docs/*-api.md` 均已进入侧栏，避免页面只能通过搜索找到；只复制 Git 已跟踪的文档和明确列出的新增公共指南，排除本地研究资料。
- 中英文快速开始由两份根 README 生成；源码、脚本和字体记录链接指向 GitHub，指南链接保留在站内。
- `site/pages/index.md`、`site/pages/zh/index.md` 和 `site/.vitepress/theme/` 维护首页与主题。
- `site/pages/playground.md`、`site/pages/zh/playground.md` 维护独立的模板编辑入口，不放入生成的指南目录。
- 生成内容位于 `site/pages/docs/`、`site/pages/zh/docs/` 和两份 `getting-started.md`，不提交。修改原始文档后重新执行 `npm --prefix site run prepare:content`。已有英文译文使用 `/docs/*`；仅有中文的旧地址通过生成的 `_redirects` 逐页 301 跳转。英文正文中指向未翻译资料的链接明确标注中文。
- 首页提供周报、报价单、学术论文、履历、合同、使用说明书和会议纪要七种真实输出，配套完整 PDF 与模板／示例入口；来源及边界见[样张说明](../site/pages/public/showcase/README.md)。本地输出与页面截图不能替代 Java 输出验收。

构建保留断链检查；不得以关闭 `ignoreDeadLinks` 掩盖缺失页面。

构建同时从实际浏览器模块、样式和图标收集第三方原始许可，生成站点根路径下的 `THIRD-PARTY-NOTICES.txt`。缺失许可、版本偏离锁文件或补充原文摘要不符时构建失败。主题从 VitePress 随附资源打包 Inter 拉丁字体和 OFL 许可，由本站提供；中文使用本地系统字体。依赖升级须检查生成声明及[补充许可记录](../third-party/docs-site-licenses/README.md)，不能仅保留许可证名称。

## 浏览器模板编辑

顶部“模板编辑”／“Playground”与侧栏“模板”组均提供入口，英文地址为 `/playground`，中文地址为 `/zh/playground`。桌面界面从左到右为节点树、XML／JSON 源码和绑定后的 HTML 近似预览；手机上分区纵向排列，节点树可展开。内置示例展示变量、循环、条件和表格的写法。

可将标题、段落、项目符号、表格和换页从组件库拖入节点树，也可拖动已有节点调整顺序或放入合法容器。树中选择节点会定位到 XML 源码；点击组件和上移／下移按钮提供不依赖拖动的操作方式。根元素、页面设置和表格等结构约束会限制放置位置，禁止将节点移入自身的后代；XML 不合法时保留源码并暂停结构编辑。

结构操作直接修改对应源码范围，不整体重写 XML，因此未修改的属性、注释、文本和换行保持原样。撤销／重做覆盖源码、数据和结构操作，支持 `Ctrl/⌘+Z`、`Ctrl/⌘+Shift+Z`；历史只保留当前会话内有限数量和总字符预算的快照，刷新后恢复草稿但不恢复历史。

模板与数据在当前浏览器中解析，不发送到后端。可导入本地 XML／JSON、下载保留编辑内容的源文件，并在当前浏览器保存草稿。下载 JSON 保留原始源码，不通过重新序列化改写数值。页面没有 Java 编译、精确排版或正式 PDF 导出服务。

预览支持版本 1 XML 的常用子集，包括纸张与边距、标题、段落、span、项目符号、静态／动态表格、`${dot.path}` 插值、each、布尔 if 和显式换页，以及常用文字与间距属性。不支持的节点、属性或数据会明确报错，编辑内容仍保留。完整语法和 Java 用法见 [XML 模板指南](xml-template-api.md)；资源包、复杂排版及完整设计器见[设计器指南](designer.md)。

浏览器使用自身文字测量与系统字体；纸张显示、换行和表格只是编辑辅助，不等同于 Java 引擎的字体、自动分页、字段解析或最终输出。正式文档应将下载的模板和数据交给 Java 引擎，并按相应输出格式验收。

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
2. 使用 `npm --prefix site run preview` 在浏览器实际操作：英文首页进入指南，核对正文及侧栏仍为英文；同一篇指南切换中文再切回英文；进入模板编辑，检查示例、XML／JSON 修改、错误恢复、文件导入下载和草稿，并核对语言切换仍停留在对应工具页；展开语言菜单并检查外观、点击外部关闭、Escape、方向键、Tab 和焦点；再检查移动端菜单、溢出、搜索和深色模式。静态链接检查不能替代这些交互。
3. 执行 `deploy:check`，确认只上传静态站点。
4. 部署后检查首页、深层指南、样张 PDF、未知路径的 404、canonical、`sitemap.xml`、`robots.txt` 和 `THIRD-PARTY-NOTICES.txt`。
5. 正式域名确认后更新 GitHub About 中的 Website，以及两份 README 的文档站入口；站点公开与搜索引擎收录分别验证。

静态构建会把正文写入 HTML，搜索与抓取不依赖客户端执行 JavaScript。元数据与实际内容保持一致，不承诺搜索排名或 AI 引用。站点不默认加载分析服务或远程字体。

站点根路径下的 `llms.txt` 汇总入门、API、格式边界和版本资料，供支持该约定的工具读取；它不替代正文、站点地图或搜索引擎收录验证。

配置依据见 [VitePress](https://vuejs.github.io/vitepress/v1/)、[Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/)、[静态站路由](https://developers.cloudflare.com/workers/static-assets/routing/static-site-generation/)及 [Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)。
