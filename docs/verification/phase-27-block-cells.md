# 阶段二十七：多段单元格与原子内表

日期：2026-10-16。对应 GAP-03／GAP-12，设计见 [ADR-0034](../adr/0034-atomic-block-cells.md)，公共编写与迁移见[块格 API](../block-cells-api.md)。共 16 模块，无新增运行依赖、字体或外部资源；包名与制品坐标保持。

## 行为与兼容

语义格支持有序 Paragraph／Table，根表与内表最多两层。链式 paragraph／table 保留独立 ID、格式、固定行框与内容。父格按覆盖宽度扣一次 padding，内表按父内容宽度测量；父子双跨度各用自己的二维网格。格内边缘间距抑制、相邻间距相加，整个父连通组原子移页／移区，超高明确失败。

新增 SemanticTree／CellLocation 贯穿模型、Java 绑定、布局、组件与 DOCX。节点、覆盖槽、叶文字／图片及输出行共享预算；来源保持实际宿主根索引、外到内路径、叶物理行列和局部 UTF-16。最终字段、书签与图文链接递归解析同一棵 IR，不重开引擎或重置页号。

DOCX 保存标准 p/tbl/p、各层 tblGrid/gridSpan/vMerge、原生字段、媒体及书签。自有结构尾 p 只有标记通过完整验证后才剥除，无元数据真实空 p 保留；同一首段的多个目标有可验证 owner。多段原生格不再拼成一个换行串，调用方须改用 blocks 遍历。TableCell、CellFragment、DocxLimits 的 record 形状变化与旧构造器保留不等于完全二进制兼容。

普通父表头可以重复，重复父表头中的块格拒绝；含块格父表和原子内表的 Auto／拆行、第三层表、注释标记、组件块及单向源块格拒绝。XML 新嵌套语法按下一阶段独立验收；本批 Java Each／Rows 深绑定及静态内部目标重定位已包含。XML 既有 Each 同作用域静态目标随副本重定位，外部目标保持，原型外引用不猜测副本 ID。

## 构建与复核

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:blockCells :examples:rowSpans :examples:columnSpans `
    :examples:streamingTable :examples:propertyStyles :examples:textCorpus `
    :examples:markdownTemplates :examples:pdfOperations :examples:xmlReferences `
    :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf `
    :examples:advancedPdf :examples:capabilities :examples:production :examples:authoring `
    :examples:inlineImages :examples:continuousColumns :examples:mixedColumns `
    :examples:inlineFields :examples:docxFields :examples:xmlTemplates `
    :examples:semanticTables :examples:semanticTableFlow :examples:gridSemanticFlow `
    :examples:styleDefaults :examples:gridContinuation :examples:multiFloats `
    :examples:staticForms :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

全量构建成功：3 分 9 秒，202 项任务、137 项执行。82 个测试类、**694 项测试**，失败／错误／跳过均为 0，比阶段二十六新增 53 项。覆盖模型互斥／深度／ID、Each／Rows 后代引用、父子宽度与间距、双跨度、共享预算、短区纯空取、取消恢复、来源序列化、最终字段／链接和标准／原生 DOCX。生产／测试编译使用 -Xlint:all -Werror，源码包与 Javadoc 通过。

交叉审查找出并修复组件源累计文字超限时缺少层级路径、最终全树预检误用追加索引、DOCX 续接空段隐藏语义属性被跳过、字段缓存重复计源文字预算。新增两项宿主预算测试通过。纯行内 SemanticTree 不再无效重建网格，既有 2048 行 DOCX 线性检查点阈值保持。

首次定向回归还修复旧文档级预算归属及文字异常 cause 兼容，原失败日志保留。DOCX 旧多段扁平断言改为逐叶语义验证；未降低预算或视觉阈值。最后样例改用 paragraph().table().paragraph() 后，修正列宽 API 误用并重新生成；`phase28-first.log` 中该任务成功。新隔离调用方为 **verification/consumer/build/validation-38ef77105587**；本地发布 23 秒、调用方 37 秒通过。首次调用方误用数字列参数的失败与成功重跑分别保留，旧成功目录未冒充新结果。

## 独立输出验收

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_block_cells.py `
    --consumer-build-directory verification/consumer/build/validation-38ef77105587
& build/verification/verapdf/verapdf.bat --format xml --flavour 2b `
    examples/build/capabilities/archive.pdf > build/verification/phase27-verapdf.xml
& build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase27_regressions.py
~~~

当前进程 PATH 显式加入已固定 Poppler。Python pypdf 6.10.0、pypdfium2 5.13.0／PDFium 153.0.7999.0、Pillow 12.3.0、python-docx 1.2.0，Poppler 26.07.0。

| 输出 | 页数 | 检查 |
| --- | ---: | --- |
| atomic | 3 | 短剩余区整体移页、父子双跨度、普通头续页、PAGE/PAGES 与三个不同引用目标、图文链接及后续正文 |
| columns | 2 | 首组短栏跳过、父组原子分配、逐栏表头、内表独立宽度、叶来源和后续正文 |
| 新调用方 | 1 | 链式 P/T/P、双跨度、三个最终字段、五个真实边框、一个图片及三条链接、原生 DOCX |

**6 页**全部由 Poppler／PDFium 192 DPI 双渲染，逐张查看全部完整 Poppler 页。最大通道均值差 2.099611、变化像素比例 0.025642，低于既有 4／0.06 阈值，无基线创建或刷新。固定 PDF 几何、正文唯一性、叶 UTF-16、内外表来源、图片与链接矩形及 SVG 顺序通过。字段轮廓在普通 pypdf 提取中可能呈逻辑锚点 x；字段值另按 ActualText、最终矩形及独立渲染核验，不宣称通用提取等价。

标准包和移除全部私有属性包由 XML 与 python-docx 独立重建：4 张表、独立内外网格／合并、5 个原生字段、3 个书签、1 份去重媒体；真实空尾段与自有结构尾段分别检查。新调用方标准双网格及字段、媒体、书签亦通过。

**26 组历史独立验收全部通过**，含上一阶段跨行组及更早字体、字段、图像、浮动／连续／混合栏、语义表格、样式、模板、静态 PDF、长表与 ADR。veraPDF 本次 PDF/A-2b 为 144 规则、10,629 检查、失败 0。未重新运行容量、OpenSSL 或 Word；不把旧证据标成本次重跑，Word 保存回读与一般文档兼容仍开放。XML 编写子集继续在下一轮执行。

证据：build/verification/phase27-full-build.log、phase27-consumer-retry.log、phase27-independent.log、phase27-regressions.json、phase27-verapdf.xml，以及 block-cells/report.json／manual-review.md。
