# 阶段三十：编写时主题快照与命名完整样式

日期：2026-10-16。对应 GAP-04，设计见 [ADR-0037](../adr/0037-authoring-theme-snapshots.md)，调用见[主题指南](../themes-api.md)。实现、构建、调用方与独立输出均已完成本地验收。

## 实现范围

模型层 `DocumentTheme` 集中保存完整正文、六级标题、便利语义表格的格／表格式，以及有界段落／行内命名样式。`Document.theme` 与 `Documents.theme` 在内容加入时取值快照，最终 Document 不保存主题或名称引用，布局与后端算法不变。具名段落可通过自动 ID 格式重载编写；组件入口同步转发现有富文本段落、标题和列表。

语义表格先应用主题默认值，再执行调用方回调；传入已构建块、格及 InlineStyle 完整保留。标题保留完整分页策略；组件只继承原有六项文字属性，局部显式值和逐项恢复仍有效。`bodyFormat` 保留 Java 旧范围，随后正文／列表／便利格会变化，标题和表格式保持；再次 theme 仅影响后来加入的内容。

主题 Builder 未设置格格式时从最终正文派生，显式设置后固定；`toBuilder` 保留这一意图，不按 12 pt／黑色／零值猜测省略。两类名称分别最多 128 个，名称区分大小写且有界；未知、非法或超额名称立即失败，回调同步取快照。

## 行为及首轮问题

新增 **21 项行为测试**已通过模型／组件针对性验证，覆盖默认模型／最终几何、加入顺序、Builder 快照、显式完整值、六级标题的跨页保持、双表入口与拆行、命名边界、真实字体角色／UTF-16 诊断、固定行框和短页合并格续排。全量构建共 **753 项测试，失败／错误／跳过均为 0**；生产与测试使用 `-Xlint:all -Werror`，Javadoc 通过，只有进阶样例预期的两条缺字替换警告。

最终完整命令执行 205 项任务，134 项执行，耗时 3 分 18 秒；重新生成全部受影响历史样例、本地 bundle 和 SBOM。两套主题及默认语义／组件组共 8 页的 PDF／SVG／DOCX 结构已先行独立核对，全部通过。

首轮样例因 DOCX 原生往返的 `blocks.equals` 断言失败。精确差异为 7 个普通 Paragraph 的空 inlines 展开成单个默认黑色 InlineText；diagnostics 为空，14 个块的 ID、完整 ParagraphFormat、Heading 和 Table 均一致。这是既有 OOXML 表示展开，并非主题值丢失。样例只规范化无图片／字段／运行的普通段落表示，再对完整块比较；保留全部 ID、格式与富文本断言。原失败和精确差异日志保留，未修改 DOCX 生产代码。

## 验收命令

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:themes `
    :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf `
    :examples:capabilities :examples:production --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_themes.py
~~~

实际完整构建另重生成全部受影响历史样例。列合并 DOCX 在隔离工作目录生成，避免文件占用影响历史样例验证。独立验收使用本次成功调用方指针。

## 最终输出与回归

本轮隔离 Maven 调用方为 `verification/consumer/build/validation-556fb6ed2b59`，本地 bundle／SBOM 19 秒、独立编译运行 40 秒通过。主题调用方通过正式坐标导出 PDF／SVG／DOCX，并同时运行此前公共能力；没有使用旧成功目录。

| 新输出 | 页数 | 独立检查 |
| --- | ---: | --- |
| blue／compact 两套主题的语义报告及组件页 | 4 | 六级标题、正文／命名备注／真实粗体强调、表格文字／内边距、显式格／段、局部六项继承／恢复及加入时快照 |
| 未配置主题／默认主题的语义报告及组件对照 | 4 | 完整语义块相等；最终 PDF 文字几何／样式和 SVG 向量／来源精确一致 |
| 新隔离调用方 | 1 | 正式主题 API、真实粗体、字号／颜色／装饰、格内边距、显式12 pt和标准 OOXML |

**9 页**全部以 Poppler／PDFium 双渲染，逐张打开完整 Poppler 页面检查；最大通道均值差 **1.861095**、变化像素比例 **0.021595**，低于 4／0.06。全部来源和格边界正确，无可见裁切或重叠。4 份语义样例 DOCX 和调用方 DOCX 另由独立 XML 检查实际字号／行距、六级原生标题、keepNext／keepLines、真实 bold 样式、颜色／下划线及表格内边距。组件页没有导出 DOCX，不扩大适配器范围。历史基线未刷新。

**29 组历史独立验收全部通过**，含阶段二十九富文本长表、普通长表、模板、文字、图像、字段、跨度、块格及既有区域布局；均读取本轮输出和调用方。最终 archive.pdf 由 veraPDF 再验，144 条规则、10,629 项检查，失败 0。本轮不重跑容量、Word 保存或 OpenSSL 签名验签；阶段二十九容量属于对应批次的证据。

## 编写复杂度与证据

[ThemeExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ThemeExample.java)的主题定义为 18 个非空行，语义报告结构为 25 行；主题定义集中 21 次具体格式设置，报告内剩余 5 次用于显式格／段。组件示例的 23 行包含刻意展示局部覆盖的 15 次设置，不能用它代表普通报告成本。统计计入方法声明和括号、不计 getter／配置入口，脚本与结果保存在 `build/verification/measure_theme_authoring.py` 和 `phase30-theme-authoring-metrics.json`。没有相同报告的前版成本基线，因而不计算“减少百分比”。

验收覆盖生产实现、行为测试、样例、构建、隔离调用方、独立检查和全页复查。审查修正了容量手算、DOCX 表示假设及阶段间制品摘要混用风险，失败原记录均保留。

最终只读审查覆盖 30 个本轮文件及 Java／Python 导入，未发现未使用导入；另核对全仓文档中代码示例之外的 978 条本地链接，缺失 0。将本轮修改的 12 份文档统一恢复为要求的 LF；新增源码／测试／脚本的 UTF-8、LF、SPDX、末尾换行和无尾空格检查通过。该文档格式收尾不改变已验收的 Java 制品。

证据：`build/verification/phase30-full-build.log`、`phase30-targeted-tests.log`、`phase30-first-themes.log`、`phase30-theme-docx-diff.log`、`phase30-consumer.log`、`phase30-themes-independent.log`、`themes/report.json`、`themes/manual-review.md`、`phase30-regressions.json`、`phase30-verapdf.xml` 与 `phase30-probe-run.log`。独立回归复核脚本为 `build/verification/run_phase30_regressions.py`。

## 边界与下一项

命名行内样式是完整值，11 pt 强调进入 18 pt 标题仍为 11 pt；不提供部分行内级联、全局颜色／字体角色或自动扩大行距。字体显式注册，缺字策略和来源诊断仍由共享路径验证。组件 GridTable 不接受语义 Table 的外观默认值，组件 DOCX 映射也未新增。

GAP-04 保持部分支持；XML 名称、字体角色级联、页区／目录主题、可编辑字段与完整品牌主题均独立。下一单元见 [XML 主题设计](../adr/0038-xml-theme-styles.md)，本阶段未包含 XML 主题接入。无新增模块、依赖或资源。
