# 阶段二十三验收：逐属性文字样式

日期：2026-10-16。对应 GAP-04，[设计](../adr/0030-property-text-inheritance.md)与[使用指南](../style-defaults-api.md)。本批只扩展已有组件排版属性，不新增模块、第三方依赖或字体。

## 实现范围

`TextInheritance` 以不可变属性集合保存六项本地声明。`FlowText`、`TextComponent`、`OpenTypeText`、`FormText`、`ChoiceGroup`、`LabelValue`、`PageNumberComponent`、`StyleScope` 及 `GridTable.CellBuilder` 统一支持字号、固定行距、首行／后续行缩进、对齐和断行策略的具名设置与逐项恢复。包内 `ComponentTextStyle` 共享构建状态，不新增公共 Builder 继承框架。

只设 `.fontSize(14)` 时保留父级其他属性；显式零、12 pt、LEFT／STANDARD 和黑色保持显式。旧 `.style(value/callback)`、格 `.textStyle(value)` 和直接构造器仍完整覆盖；在完整样式之后恢复单项时，其他五项继续显式。`toBuilder()`、默认格及回调结果保持快照。解析发生在测量前，原布局算法、字体策略、来源、语义插槽和取消恢复不变。字号不暗中扩大父级行框。

## 构建与调用方

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:propertyStyles :examples:textCorpus :examples:markdownTemplates `
    :examples:pdfOperations :examples:xmlReferences :examples:textPdf :examples:flowPdf `
    :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:capabilities `
    :examples:production :examples:authoring :examples:inlineImages :examples:continuousColumns `
    :examples:mixedColumns :examples:inlineFields :examples:docxFields :examples:xmlTemplates `
    :examples:semanticTables :examples:semanticTableFlow :examples:gridSemanticFlow `
    :examples:styleDefaults :examples:gridContinuation :examples:multiFloats `
    :examples:staticForms :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

全量构建成功，用时 1 分 40 秒；61 个测试类、490 项测试，失败／错误／跳过均为 0。新增 `TextCascadeTest` 的 18 项检查实际字号／基线／缩进／颜色、逐项恢复、旧调用、嵌套与兄弟隔离、跨上下文复用、网格工厂／快照、合并格短区、OpenType 浮动、缺字 UTF-16、固定行框失败、原子宽度、语义来源和取消。

最初续页测试误把网格补足区域的高度等同完整行高，随后又误从 GlyphText 统计实际为 OutlineText 的表单正文。已仅修测试，追踪 Save／Restore 和格平移，精确核对区域 1／2／3／4、高度 60／100／100／72、正文完整行 1／3／3／2、24 pt 行框与唯一来源。生产代码未为测试改变。18 项定向测试通过后，全量复验通过。

Java 生产／测试／隔离调用方均以 `-Xlint:all -Werror` 编译；Javadoc、源码包、BOM／SBOM、本地坐标成功。SBOM 与新隔离运行集合仍为 14 个自研库和 4 个既有外部制品，摘要／BOM 由生产验证器核对。隔离成功目录为 `verification/consumer/build/validation-faa3db968ed2`，以后必须读取新的成功指针，不能用此目录替代后续批次证据。

## 独立输出

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_property_styles.py
~~~

固定工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 及本机 Poppler。检查最终 PDF 实际字号／颜色、文本显示矩阵、基线、边框和 PDFium 选区；不读取 Java 几何清单作为期望值。

| 样例 | 页数 | 实际检查 |
| --- | --- | --- |
| properties | 1 | 19 处字号／颜色；只改字号保留 24 pt 行距及 18／6 pt 缩进；零缩进、RIGHT、完整回调、逐项恢复、嵌套作用域、兄弟隔离；6 个格边框与两个静态标记 |
| continuation | 2 | 22 行在两页分配为 12／10；11 pt 局部字号配父级 20 pt 行距；首行缩进只一次；逐页表头、标签唯一、8 个边框及尾部 |
| regions | 2 | 当前页两个 120 pt 短栏各 5 行，下一页剩 2 行；局部 11 pt／作用域 24 pt；跨栏和跨页不重复首行缩进；来源与尾部 |
| consumer | 1 | 新公开坐标的 10 处字号／颜色；完整样式只释放行距；格默认值／工厂／快照与局部作用域 |

共 6 张新增页全部用 Poppler／PDFium 渲染并逐张查看，未见裁切、重叠或内容缺失。交叉渲染阈值保持均值误差 < 4、变化像素比例 < 0.06，未刷新视觉基线。SVG 额外检查页数及 22／12 条来源顺序。

22 组既有独立回归通过：advanced_pdf、production、capabilities、authoring、inline_images、multi_floats、continuous_columns、mixed_columns、inline_fields、docx_fields、static_forms、adr_form、grid_continuation、semantic_tables、semantic_table_flow、grid_semantic_flow、style_defaults、xml_templates、text_corpus、markdown_templates、pdf_operations、xml_references。最新 archive.pdf 经 veraPDF 独立验收 PDF/A-2b，144 规则／10,629 检查，零失败。

## 边界与复评

本批完成 GAP-04 的六项组件属性继承子目标，完整语义主题、字体角色级联、可编辑字段样式、自动行高和 CSS 仍开放。语义 Paragraph／Table 保留自身格式；正文格式不改变独立页模板，DOCX 不新增组件语义。`style()` 返回本地配置，有效样式仍依赖布局上下文。

保留旧构造源码兼容；TextInheritance 的 record 分量、反射／解构／结构字符串和哈希已变化，未发布阶段不承诺二进制兼容。无新增真实 Word／真人阅读器／其他操作系统或 JDK 验收，不扩大阶段二十二的文字提取／选区兼容范围。

下一项为 GAP-09 的固定列单向长表源，必须同时打通源、逐页测量／绘制、后端同步接收与有界摘要；当前全量 TableCursor 不满足。具体源码保留链与验收见[执行计划](../remaining-capabilities-plan.md)。PDFBox 页树的增长须单独测量，不能由丢弃页接收器推断完整 PDF 恒定内存。

证据：`build/verification/phase23-final-build.log`、`phase23-tests.json`、`phase23-cascade-test.log`、`phase23-consumer.log`、`phase23-property_styles.log`、其他 `phase23-<验证器名>.log`、`phase23-verapdf.xml`；`property-styles/report.json` 与全部渲染页。早期失败日志保留作修正记录。

本批仅本地构建及隔离发布，没有提交、推送、创建 remote、远程 CI 或正式发布。
