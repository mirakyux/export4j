# 阶段二十：长合并格语义续排

日期：2026-10-15。范围为 GAP-06 的标准 SemanticTable／FlowColumn 格内续排和合法缓存语义表头重复。项目仍未正式发布。设计见 [ADR-0024](../adr/0024-grid-semantic-cell-flow.md)，编写见[语义格 API](../grid-semantic-flow-api.md)。

## 实现结果

核心 `prepareTable` 一次测量，布局局部 TableCursor 按每次实际高度取完整行或显式拆行片段。空取片不消费来源、片段或输出预算；剩余行高使用后缀缓存。已有区域表格分配和顶层拆行复用相同测量与纯行分配器。

PreparedCellFlow 接入语义游标，递归保持字体／样式作用域和嵌套列。GridSemanticStreams 为当前网格登记准备来源，校验原始片段引用、顺序、字体和配置，再确定最终完整序号。只允许缓存表头重复其自身完整序列，独立重复源仍拒绝；全局来源注册不放宽。原子与拆行网格采用同一呈现校验。

网格行增加 `.semanticTable(table, cell -> cell.rowSpan(2))`，调用方无需手动包装标准语义组件。内 Table 与外网格拆行仍分别显式开启。

## 构建与行为验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
 :examples:gridSemanticFlow :examples:textPdf :examples:flowPdf :examples:tablePdf `
 :examples:componentPdf :examples:advancedPdf :examples:production :examples:capabilities `
 :examples:staticForms :examples:gridContinuation :examples:semanticTableFlow `
 :examples:semanticTables :examples:styleDefaults :examples:mixedColumns `
 :examples:multiFloats :examples:continuousColumns :examples:inlineFields `
 :examples:inlineImages :examples:adrForm :examples:authoring --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_grid_semantic_flow.py
~~~

完整构建成功。54 个 JUnit 测试类、345 项测试，失败／错误／跳过均为 0。新增 8 项核心游标和 8 项组件集成测试；既有网格表头拒绝测试改为逐页字段正向覆盖，独立来源误用继续拒绝。

覆盖不同短区、一次塑形、UTF-16／firstLineIndex、字段／图片、真实粗体角色、缺字警告、两层表头、连续栏／目录偏移／分节编号、内外拆行独立、空取片、源与呈现预算以及取消恢复。同源双格、同列两次、两处表头、表头与正文、不同实例同 ID 及跨网格复用均拒绝。预算测试区分一次来源计数与最终绘制码点限额，重复输出不绕过任一限制。

构建完成 Javadoc、源码包、BOM／SBOM 与本地隔离发布。未出现 Java 编译警告；进阶样例仍有预期的两个缺字替换运行诊断。隔离调用方成功构建目录为 `verification/consumer/build/validation-51ebf2ed744f`，通过成功指针读取，无旧目录替代。

交付自查通过 14 份相关文档、284 个本地链接和标题层级；12 个变更 Java 文件的 LF／SPDX 检查通过，记录在 `build/verification/grid-semantic-doc-check.json`。

独立验收初次发现的是样例 manifest 混用逻辑锚点与固定行框的假设，已纠正样例／脚本；没有修改库输出或放宽阈值。删除多余 manifest 字段后再次运行 `build sbom publishLocalBundle :examples:gridSemanticFlow` 与新独立脚本，均成功。日志见 `build/verification/grid-semantic-full-build.log`、`grid-semantic-final-build.log`、`grid-semantic-consumer.log` 和 `grid-semantic-verify.log`。

## 独立 PDF／SVG 验收

| 输出 | 页数 | 独立检查 |
| --- | --- | --- |
| `examples/build/grid-semantic-flow/regions.pdf` | 3 | 短首区、跨两行合并格、28 行原文按 7／13／8 行续排；两层逐页表头、富文本粗体／颜色、字段与引用、两张图片及四条外链 |
| `examples/build/grid-semantic-flow/columns.pdf` | 2 | 首页两个短栏及续页单栏，28 行按 9／10／9 行续排；同页左右表头 PAGE 均为 1，续页为 2；后续正文接在最高栏后 |
| 调用方 `output/consumer-grid-semantic-flow.pdf` | 3 | 公共 Maven API、跨两行合并格、24 行每页 8 行；24 个 PAGE 和三次表头 PAGE／PAGES 与实际页一致 |

Python 用独立声明的原文、几何和行分配常量核对最终 PDF；Java manifest 仅用于交叉检查。源行列、UTF-16、完整行索引及呈现序号保持，图片外链与实际绘制图像矩形相同，普通外链对应实际文字／下划线，引用指向首块的实际位置。各源文字唯一、有序，首尾标签和相邻短格未重复。两份静态 SVG 保留完整来源顺序与图片，并显式不携带 PDF 交互。

工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和 Poppler。全部 8 页独立双渲染并逐页视觉复查，无裁切、重叠、缺行或图片损坏。192 dpi、2 px 模糊后最大均值误差为 1.857875，最大变化像素比例为 0.018199，低于既有 4／0.06 阈值；没有创建或更新视觉基线。证据见 `build/verification/grid-semantic-flow/report.json`、两张 contact 图及逐页 PNG。

## 历史回归

15 组既有独立验收全部成功：advanced_pdf、production、capabilities、semantic_table_flow、static_forms、grid_continuation、mixed_columns、multi_floats、continuous_columns、inline_fields、inline_images、adr_form、authoring、semantic_tables 和 style_defaults。记录在 `build/verification/grid-semantic-regressions.json` 及对应 `grid-semantic-regression-*.log`。

## 边界与下一项

GAP-06 关闭于标准语义格组合。来源坐标属于内 Table，尚不提供外 GridTable 格坐标诊断。任意递归网格拆分、合并语义 Table 模型、DOCX 嵌套、页区语义、旋转／缩放／部分裁切、端到端流式和逐属性样式级联仍不支持。固定视口和默认整行／整组契约保持；字段宽度不足、原子内容超高及完整续区不能推进仍失败。

本批没有新增依赖或第三方素材，也未新做 PDF/UA、PAdES/LTV、一般 Word 兼容或性能排名验收。没有配置 remote、提交、推送、启用远程 CI 或正式发布。下一优先项为 GAP-07 安全声明式模板子集，继续按评估、实现、独立验收和复评推进。
