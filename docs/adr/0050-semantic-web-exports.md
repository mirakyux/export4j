# ADR-0050：语义 HTML／Markdown 导出与逐功能动作策略

- 状态：已采纳
- 日期：2026-10-21

## 背景

PDF／SVG／PNG 保留既定页面绘制，DOCX 使用语义映射。网页与内容分发需要可搜索、可复制、由阅读器重排的 HTML 和 Markdown；复用绘制坐标无法可靠恢复标题、列表、表格和链接。不同目标对分页、合并格及运行样式的表达能力也不同。

## 决定

新增 `export4j-adapter-web`，生产仅依赖 `export4j-model` 与 JDK。`HtmlAdapter` 和 `MarkdownAdapter` 直接消费 Document／PageDocument／SectionedDocument，分别输出静态自包含 HTML5 与 CommonMark/GFM 内容，不引入浏览器运行依赖，不改变核心布局或具体后端依赖方向。

`ExportPolicy` 使用默认动作及 ExportFeature 覆盖表。ERROR 在输出前拒绝；WARN 应用已登记降级并返回带节、区域、原始块、物理格路径和 UTF-16 的结构化诊断；IGNORE 使用同样降级且不生成该诊断。动作不能压制非法输入、编号错误、预算、取消或 I/O 失败。字段使用显式未解析标记，不伪造页码或引用。

两种目标共享真实锚点遍历、源预算、图片摘要与输出计费。HTML 保留原生合并／嵌套表格；Markdown 按明确策略展开内容并保留原始顺序。Markdown 使用固定安全行内标签处理相邻强调边界，空注释隔开重启编号；原文标记始终转义。

适配器与请求不可变，状态限于单次调用。先完成有界 UTF-8 拼装及策略校验，再写借用流；路径通过同目录临时文件原子替换。PNG／JPEG 直接嵌入显式资源快照。HTML CSP、上下文转义及模型链接白名单共同限制执行与外联。

## 后果

浏览器／Markdown 阅读器决定换行、字体及最终分页，不承诺与 PDF 一致。流式表源、绘制组件、导航配置和 PDF 交互对象不会被反向解析为语义。元数据、物理纸张、固定页区、字段、表格与字体的具体损失见[API 矩阵](../web-export-api.md)。默认 A4 是网页流默认语义；非默认纸张与边距须按 PAGE_GEOMETRY 处理。

Markdown 输出与严格 Markdown 模板输入的语法不同，不承诺往返。HTML/CSS 输入及可视化设计器保持独立范围。无生产第三方依赖；独立验证器固定版本并单独记录许可证，不进入 JAR 或运行 SBOM。

## 验证

行为测试覆盖动作差异、输出前失败、Unicode／编号／预算、源码位置、真实锚点、原子性、借用流及并发。独立解析与浏览器验证见[阶段四十三](../verification/phase-43-web-exports.md)。
