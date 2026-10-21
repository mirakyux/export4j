# 阶段二十五验收：语义列合并与 DOCX gridSpan

日期：2026-10-16。对应 GAP-12 的 columnSpan 子目标，设计见 [ADR-0032](../adr/0032-semantic-column-spans.md)，使用见[列合并 API](../column-spans-api.md)。不新增模块、依赖或外部资源；仍为 16 模块和现有包名／坐标。

## 实现与兼容边界

TableCell.columnSpan 为正整数，默认 1；保留旧 2／3／4／5 参数构造器与工厂，增加 toBuilder 和 headerCells。record component 数由 5 变为 6，解构模式和反射形状需要迁移，不能称完全兼容。

TableRow 保存真实锚点，columnCount 安全求和，cellAtColumn 按物理列找 owner；Table 完整验证每行覆盖。隐式列推断上限 4,096，显式列仍受布局预算。模型不引用字体、布局或后端类型。

固定／比例列合并按覆盖宽度一次测量和一次 padding；CellFragment 保留物理起始列、整格矩形、原文 UTF-16 与续排行号。整行、拆行、短区域、连续栏和网格内语义源复用核心算法；相邻行可有不同锚点数量。maxCells 计覆盖网格槽，原文只计真实锚点。覆盖 Auto 列的合并明确失败；无关 Auto 仍可用，按真实锚点及比例份额预留最小文字区。

最终字段和图文链接保持实际页与真实源格；Java／XML 绑定保留跨度。XML column-span 接受固定正十进制整数，空／非法值／溢出保持明确分类。DOCX 用标准 tcPr/gridSpan 和 tblGrid，拒绝错误容器、重复、非法值及覆盖关系；无网格按跨度有界推断。单向长表明确拒绝合并，重复表头 PAGE 的 DOCX 拒绝仍保持。

## 构建与行为验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:columnSpans :examples:streamingTable :examples:propertyStyles `
    :examples:textCorpus :examples:markdownTemplates :examples:pdfOperations :examples:xmlReferences `
    :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf `
    :examples:advancedPdf :examples:capabilities :examples:production :examples:authoring `
    :examples:inlineImages :examples:continuousColumns :examples:mixedColumns :examples:inlineFields `
    :examples:docxFields :examples:xmlTemplates :examples:semanticTables :examples:semanticTableFlow `
    :examples:gridSemanticFlow :examples:styleDefaults :examples:gridContinuation `
    :examples:multiFloats :examples:staticForms :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

全量构建成功，用时 2 分 19 秒，200 项任务、129 项执行。69 个测试类、592 项测试，失败／错误／跳过均为 0；新增 40 项：模型 10、核心 10、组件 2、DOCX 12、Java／XML 模板 6，另扩展既有混合图文／字段断言。Java 生产、测试与调用方使用 -Xlint:all -Werror，Javadoc／源码包通过。

交叉复核发现合并 Fraction 格旁的 Auto 列沿用每列 padding 预留，可能误拒绝合法输入。改为按真实锚点和比例份额计算最小余量，新增 100 pt 正文宽／Auto 84 pt／padding 4 pt、等权及 1:3 两种几何回归。首轮模板定向验证发现空 column-span 被 required 判为 INVALID_STRUCTURE，与公开数值契约不符；直接校验已存在属性原值后返回 INVALID_VALUE，严格拒绝不变，最终全量复验通过。原失败日志保留。

新隔离调用方 verification/consumer/build/validation-eb6079dbf110 编译／运行／依赖库存通过，包含 public columnSpan／headerCells／物理列定位、最终图文链接与标准 DOCX 往返。调用方自身 Gradle 23 秒；运行依赖仍为 14 个自研库与 4 个既有外部制品，BOM／SBOM 与摘要由生产验证器核对。

## 独立输出与 Word

`scripts/verify_column_spans.py` 已通过：整行 2 页、拆行 3 页、连续栏 2 页及新隔离调用方 1 页，共 8 页。独立检查内容唯一性与原顺序、物理列和合并矩形、逐次表头 PAGE、最终字段、图文链接、标准 OOXML 和 python-docx 读取；Poppler／PDFium 192 DPI 双渲染并逐页人工查看，无裁切、重叠或内容缺失。结果见 `build/verification/column-spans/report.json` 与该目录人工复查记录。最高双引擎均值差 2.304306、变化比例 0.025403，沿用 4／0.06 阈值。含图片段落按共享布局的整段 ascent 容纳图片，邻格不承诺自动基线对齐。

24 组历史独立验收全部通过，均使用隔离调用方，见 `build/verification/phase25-regressions.json`。新 veraPDF 报告验证 PDF/A-2b，144 项规则、10,629 项检查，失败为 0；本批未重新运行外部 OpenSSL 签名验证，后端签名代码没有改动。

本机 Word 16.0 已真实读取 16 行表、计算 2 页并更新 PAGE／NUMPAGES 为 2。私有实例两次阻塞于 SaveAs2；最后一次 90 秒有界尝试先调用 ExportAsFixedFormat，也发生阻塞，尚未执行格式 12 的 SaveAs2。因此不能将问题归结为已证明的格式 16 单点故障。只清理各次自建 PID，最终无遗留 WINWORD；保留 `word-retry.log`、`word-partial-evidence.json`、`word-before-save.json` 及超时／进程归属记录。这些证据只证明读取／字段更新，不证明已保存副本回读或 Word PDF 导出。标准 OOXML 结构及严格自有导入已通过，较广的 Word 保存兼容保持开放。

## 限制与复评

仅连续列合并，不含语义 rowSpan、递归格内容、跨 Auto 合并、横向分页及单向长表合并。未知 Word 表格／样式仍需显式损失诊断或拒绝，不保证一般 DOCX 无损或与 PDF 同分页。

下一切面是语义 rowSpan／标准 vMerge：需要全覆盖空锚点行、二维 owner、确定行高约束及连通行组分页；首批宜整组移动，超高立即失败。DOCX restart／continue 须严格匹配完整列区间，非空 continuation 不能静默丢弃。递归格内容与跨页拆分纵向合并组仍独立。

构建证据：build/verification/phase25-full-build.log、phase25-tests.json、phase25-consumer.log、phase25-core-targeted.log、phase25-model-template-targeted.log（含一次分类失败）、phase25-adapters-targeted.log。源码复核：phase25-cross-review.md、phase25-model-review.md；下一切面评估：phase25-next-rowspan-assessment.md。

本批仅本地构建、研究与隔离发布验证，没有提交、推送、远程 CI 或正式发布。
