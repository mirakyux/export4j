# 阶段四十三：HTML／Markdown 导出与动作策略

日期：2026-10-21。范围为独立语义 Web 适配器及 ERROR／WARN／IGNORE 动作。设计见 [ADR-0050](../adr/0050-semantic-web-exports.md)，调用、完整降级表及资源契约见[API](../web-export-api.md)。

## 实现

- 新增 `export4j-adapter-web`，生产仅依赖模型和 JDK，支持 Document／PageDocument／SectionedDocument。工程共 17 模块，本地发布 15 个库与 BOM；运行 SBOM 为 15 个自研库加 4 个既有第三方制品。
- HTML5 保留标题／段落／列表、样式提示、图文链接、原字节嵌入图片、真实合并锚点及两层块格内表；元字符、属性与字体族分上下文转义，CSP 禁止外部资源和脚本。
- Markdown 使用 CommonMark／GFM 表格、链接、图片和强调；生成的固定行内标签保留相邻富文本，空注释保留编号重启。源码 HTML 始终作为普通文字转义。块格降级保留每个真实锚点内容一次，覆盖槽留空，内表按来源顺序展开。
- 全局默认 ERROR，可按功能覆盖。WARN 返回带区域／节／原始块／物理格路径／UTF-16 的结构化诊断；IGNORE 使用同一降级且不生成该警告。字段明确标记未解析；非法 Unicode、编号、预算、取消和 I/O 不能被忽略。
- 所有目标共享有界源／图片／输出计费与同步执行上下文；先完成语义校验及 UTF-8 拼装，再写借用流。路径使用同目录临时文件原子替换，无复制回退。

## 构建与行为验证

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:webExports :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_web_exports.py
```

Java 21、Gradle 9.2.1、JUnit 6.1.3。全仓 **1,078 项测试通过**，无失败、错误或跳过；其中 Web 适配器 20 项。覆盖输出前 ERROR、WARN／IGNORE 字节一致及逐功能覆盖、真实锚点与嵌套顺序、UTF-16 与物理格路径、角色映射快照、Unicode／编号错误、源／图片／输出／诊断预算、原子目标保持、取消与作用域恢复、借用流、I/O 传播和并发复用。公共 Javadoc、源码 JAR、锁文件、BOM／SBOM 及许可打包通过。

隔离调用方仅通过本地 Maven 坐标与 BOM 编译生成 HTML／Markdown，验证 PAGE_BREAK 的 WARN／IGNORE 结果一致且诊断不同。本次成功目录由 `build/verification/consumer-build-directory.txt` 指向；输出不进入源码树或制品。没有远程发布结果。

## 独立解析与浏览器

`scripts/verify_web_exports.py` 使用固定的 markdown-it-py 4.0.0／mdurl 0.1.2 和 Python HTMLParser，检查 **6 个文件**：富报告、严格普通内容与隔离调用方，各含 HTML／Markdown。

检查包含：原文及 literal HTML／实体、标题与列表语义、编号 3→8 重启、相邻粗体边界、嵌套顺序、合并槽覆盖、原 PNG 字节及 alt、已有 `%20` 与查询参数、链接、字段标记及 UTF-16 位置。富样例返回 HTML 4 条、Markdown 11 条诊断；严格普通输出没有警告。检查脚本将 Markdown 独立解析为忽略目录内的 HTML 预览，未把原始输出自行读回作为唯一证据。

浏览器实际打开 HTML 原文件和独立解析后的 Markdown，检查宽屏及 **375 CSS px** 内容视口的首尾与完整表格。图片、标题、链接、重启标记、合并／展开格及文末内容均可见；原文 script 标记按文字显示。截图和报告保存在 `build/verification/web-exports/`。该证据仅覆盖当前浏览器及这些固定样例，不代表所有 Markdown 平台或打印分页兼容。

17 组既有独立检查通过：XML 分节／页区／模板／页面／默认文字／主题／片段／引用、页区角色、组件文字、主题、行内字段、目录样式、进阶 PDF、生产制品、能力及 DOCX 字段。既有 Word 打开／字段更新记录独立保留，本阶段不新增 Word 保存兼容结论。

## 边界

HTML／Markdown 为语义内容出口，阅读器负责重排；不与 PDF 同分页，不嵌入或扫描字体。Markdown 的 data URI 图片及生成行内 HTML 受阅读器策略约束。HTML/CSS 输入、设计器、绘制组件反向语义转换、流式长表源及通用格式往返不在本次范围。默认 A4 使用流默认值，非默认物理纸张／边距、固定重复页区和页码采用显式策略；全部降级见[矩阵](../web-export-api.md)。
