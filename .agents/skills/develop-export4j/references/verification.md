# 构建、依赖与验证矩阵

按受影响契约选择下列验证，不将所有样例作为每次变更的固定前置条件。所有命令均从仓库根目录运行；Gradle 参数前按操作系统加 Wrapper。

## 构建与依赖

- 变宽递归格／组件网格及轮廓浮动使用新隔离调用方的 `complexFlows` 和 `scripts/verify_complex_flows.py`，核对完整 UTF-16 来源、递归 owner、最终字段、图文外链、实际曲线行带及全部新页独立渲染。
- 可选原生 OpenType 使用 `openType` 和 `scripts/verify_native_opentype.py`；平台 native classifier 必须显式配置。分别验证真实可变轮廓、彩色字形、方向塑形、分页、静态 CFF 原生 PDF、提取／查询／选中及新页双渲染，不能将绑定头版本当作实际原生版本。
- 语义长表／重放／磁盘索引 PDF 使用 `streamingReplay` 和 `scripts/verify_streaming_replay.py`；容量运行 `scripts/measure_streaming_replay.ps1` 后由 `scripts/verify_streaming_replay_capacity.py` 核对全部源行及首中末页。分录堆、进程工作集／私有字节、临时盘和退出清理，不把有限组解释为长期无泄漏。
- 高级颜色／渐变／图案／混合及 SVG 资源使用 `gap21Complete` 和 `scripts/verify_gap21_complete.py`；最终 PDF 经 Poppler／PDFium，最终 SVG 经 resvg，源 SVG 经显式 Chromium 对照，归档样例使用 veraPDF。保留颜色空间实际损失与默认拒绝，独立检查原生资源和可见像素。

- 工程建立后统一使用提交到仓库的 Gradle Wrapper，不要求全局 Gradle。
- Windows 使用 `.\gradlew.bat`，Linux/macOS 使用 `./gradlew`。
- 依赖版本集中管理并固定，不使用 `+`、动态版本或未固定的 SNAPSHOT。
- 新增直接或传递依赖时，更新[依赖记录](../../../../docs/dependencies.md)，验证许可证、Java 兼容性和分发义务。
- 字体、图片、样本文档也必须登记来源、许可和测试用途。
- 新建自研源文件采用 `SPDX-License-Identifier: Apache-2.0` 声明；保留第三方资源原有声明。

## 验证与交付

- 运行与变更相关的最小有效验证；修改公共契约或跨模块行为时扩大到受影响模块。
- 测试应证明可观察行为、边界或回归。纯文档变更校验链接、结构和事实，无须添加 Java 测试。
- 绘制或布局变更结合几何断言、输出结构验证与独立渲染；视觉基线更新须解释差异。
- 不以同一后端自行读回成功作为全部正确性证据，不以 PDF 文件字节相同作为通用断言。
- 不假设构建任务存在。缺少 Wrapper、依赖或运行环境时说明限制，不宣称构建或测试通过。
- 独立 Python 验证先在项目隔离环境安装 `scripts/requirements-verification.txt` 并核对实际版本，不依赖会升级的共享运行时。已观察到 PDFium 5.14 与锁定的 5.13 在右到左文字提取顺序上不同；不得放宽原文断言掩盖工具版本变化。
- 完成前自查编译和 IDE 警告；不以宽泛抑制规则消除警告。
- 更新受影响的文档、示例、兼容性说明和 ADR。
- 字体与文本变更运行 `.\gradlew.bat build :examples:textPdf`，结合[文本 API](../../../../docs/text-api.md)与[阶段二记录](../../../../docs/verification/phase-2.md)核对边界；新增字体同步 `assets/fonts` 的来源与许可。
- 文档模型或分页变更运行 `.\gradlew.bat build :examples:flowPdf`，结合[布局 API](../../../../docs/layout-api.md)与[段落分页验收](../../../../docs/verification/phase-3-paragraphs.md)验证来源顺序、页边距、推进和预算。
- 表格变更同时运行 `:examples:tablePdf`，结合[表格 API](../../../../docs/table-api.md)与[表格验收](../../../../docs/verification/phase-3-tables.md)，验证自动行高、正文行唯一性、重复表头及超高行失败。
- 组件与交互变更同时运行 `:examples:componentPdf`，结合[组件 API](../../../../docs/components-api.md)与[组件验收](../../../../docs/verification/phase-3-components.md)，核对最终页码、目录跳转、区域边界和表单填写保存；用独立工具检查全部页面。
- 进阶布局、字体策略、分节或表单变更运行 `build :examples:advancedPdf`，再执行 `python scripts/verify_advanced_pdf.py`。Python 工具版本见 `scripts/requirements-verification.txt`，须安装 Poppler。基线更新只能在全部页面人工检查后显式使用 `--update-baseline`，不能为通过测试自动刷新。契约及证据见[进阶 API](../../../../docs/advanced-api.md)与[验收记录](../../../../docs/verification/phase-3-advanced.md)。
- 性能基线使用 `:examples:benchmark`，记录环境与测量边界；堆内存池峰值之和不是进程 RSS，也不是精确同时峰值。
- 生产能力变更运行 `build sbom publishLocalBundle :examples:production`、`scripts/verify_local_consumer.ps1` 与 `python scripts/verify_production.py`；独立检查全部新页面。`--reader-copy` 只检查真实阅读器保存证据，不能把自动字段写回称为阅读器人工验证。不得为通过验证自动更新基线。
- 模板、公共塑形、受控导出和 PDF 安全／归档／签名变更运行 `build sbom publishLocalBundle :examples:capabilities :examples:production`，执行 `python scripts/verify_capabilities.py` 与隔离调用方验证；归档文件使用 veraPDF，签名使用独立 OpenSSL 验签及篡改拒绝。全部新页须独立渲染并人工复查，详见[能力验收](../../../../docs/verification/phase-5-capabilities.md)。
- 交付说明包含：变更内容、验证命令与结果、未验证范围。区分本地验证、提交、推送和发布。
- 行内图像变更运行 `build :examples:inlineImages` 和 `python scripts/verify_inline_images.py`，检查原文顺序、固定行框、分页与表头重复、图片／链接矩形、标准 OOXML 媒体和全部独立渲染页面。隔离调用方使用独立构建目录；生产验证读取成功目录指针或显式 `--consumer-build-directory`，不能检查被占用的旧输出冒充本次结果。
- 浮动绕排变更运行 `build :examples:multiFloats :examples:production :examples:advancedPdf` 和 `python scripts/verify_multi_floats.py`，验证左右错位、实际碰撞拒绝、封闭行带、整组移页、浮动仅一次及正文续页。检查最终 PDF 的矩形／原文锚点及全部独立渲染页；旧 `FloatText` 通过生产示例回归。公共 API 同时验证隔离 Maven 调用方。
- 连续分栏变更运行 `build :examples:continuousColumns :examples:production :examples:advancedPdf` 和 `python scripts/verify_continuous_columns.py`，验证剩余高度、完整续栏、最低行数移页、三栏、复杂文字及后续正文位置。检查最终 PDF 坐标、SVG 来源顺序及全部独立渲染页；保留相同栏宽未分页结果、首行缩进、缺字 UTF-16 诊断与取消恢复。公共 API 同时验证隔离 Maven 调用方；不得自动刷新视觉基线。
- 变宽最低行数变更另运行 `:examples:minimumLines :examples:variableColumns` 与 `scripts/verify_minimum_lines.py`／`scripts/verify_variable_columns.py`。验证短区跳过、3／2 断点、实际字形推进、完整来源及最终试排诊断；回归普通／富文本／复杂文字与有界平衡，全部新增页双渲染并逐页复查。
- 变宽语义表格另运行 `:examples:variableTables`、新隔离调用方和 `scripts/verify_variable_tables.py`；核对实际列宽／换行、原始锚点、合并连通组、原子内表、最终逐栏字段和图片／链接，来源与候选／呈现预算分别验证。回归语义区域、递归表格、自动列合并、混合栏和最低行数；全部新增页双渲染并逐页复查。
- PDF 已有签名校验另运行 `:examples:verifyPdfSignatures`、新隔离调用方和 `scripts/verify_pdf_signatures.py`；由独立解析器重建准确 ByteRange／DER 并用真实离线 CMS 验证完整性、未签追加及最新版本篡改。校验器回调不得冒充证书信任／修改符合性，不能把单元协议夹具称为真实 CMS；全部新增页双渲染并逐页复查。
- 单向字段另运行 `:examples:streamingFields`、新隔离调用方及 `scripts/verify_streaming_fields.py`；检查重复表头、移页正文和拆行字段的实际页码、准确声明总数、源／生成预算和失败原子性。共享字段绘制变更回归完整文档字段、变宽表格、图文、生产及进阶路径，不刷新视觉基线；全部新增页双渲染并逐页复查。
- 行内内表 Auto 另运行 `:examples:nestedAutomaticTables`、新隔离调用方及 `scripts/verify_nested_automatic_tables.py`；核对父格实际宽度、最小值收缩、合并锚点、递归字段／链接和探测异常祖先路径。回归块格、递归表格、普通 Auto、变宽区域和双跨度；DOCX 单独检查真实网格、Auto 声明及原生字段，不扩大为 Word 通用布局一致。
- 变宽行内格拆行另运行 `:examples:variableSplitTables`、新隔离调用方和 `scripts/verify_variable_split_tables.py`；核对完整源跨宽度换行、实际行容量、不同格行高、横向合并及空续接格、短区、UTF-16、最终字段、图片像素与链接。分别验证工作／呈现预算和取消恢复，回归原子变宽表格、递归／自动列、语义区域、混合栏及最低行数；全部新增页双渲染并逐页查看。
- 混合分栏／表格区域变更额外运行 `:examples:mixedColumns :examples:tablePdf` 和 `python scripts/verify_mixed_columns.py`，核对每栏剩余高度、内边距／间距、图片仅一次、逐栏表头、合并行组不拆开、正文唯一性和原子片段回退。全部新页须独立渲染并人工复查；验证隔离 Maven 调用方的混合 API、重复表头及图片。
- 行内字段／注释锚点变更运行 `build sbom publishLocalBundle :examples:inlineFields :examples:production :examples:advancedPdf` 与 `python scripts/verify_inline_fields.py`，核对最终目录偏移、分节页码、引用链接、原文来源、宽度失败及脚注实际归属。全部新页须独立渲染并人工复查；同时验证隔离 Maven 调用方、原脚注续页和图文／表格回归，不自动刷新基线。

- 语义表格字段须额外检查重复表头的逐页值、拆行字段来源、单元格行列诊断、自动列宽与图片／外链共存，执行 `:examples:tablePdf :examples:inlineImages :examples:mixedColumns` 和既有独立验证。PDF 字距保持相对推进，不得先量化绝对游标再相减；核对历史基线并保留调用方显式字距。
- DOCX 字段变更运行 `build sbom publishLocalBundle :examples:docxFields`、隔离调用方及 `python scripts/verify_docx_fields.py`。本机安装 Word 时，可用 `scripts/verify_docx_word.ps1` 在私有实例中更新生成样例，再以 `--word` 独立验收；不操作用户文档。重复表头 PAGE 无法保持逐次呈现语义，导出须拒绝；Word 保存可能移除预留宽度，导入须显式配置，不推测几何或执行未知指令。
- 块格／内表变更额外运行 `:examples:blockCells` 和 `python scripts/verify_block_cells.py`，使用隔离调用方。核对父子非对称 padding／独立宽度、双跨度／全覆盖行、短区原子移页、格内顺序、最终字段／目标坐标／图片链接、全树预算与宿主来源；独立检查标准／去元数据 DOCX 的双网格、书签 owner、结构尾 p 和真实空 p。全部新页双渲染并逐页复查，不刷新旧基线。
- 语义列合并变更额外运行 `:examples:columnSpans`、新隔离调用方和 `python scripts/verify_column_spans.py`，核对真实锚点／物理列、交替跨度、重复表头字段、拆行／短区、图片／外链及标准 `gridSpan`。全部新页须双渲染并逐页复查；Word 私有实例验收使用生成样例，阻塞或未保存时明确登记，不能宣称保存回读成功。
- 语义跨行合并变更额外运行 `:examples:rowSpans :examples:columnSpans :examples:streamingTable :examples:semanticTableFlow :examples:gridSemanticFlow`、新隔离调用方和 `python scripts/verify_row_spans.py --consumer-build-directory <成功构建目录>`。核对二维 owner／anchor、incoming 空位、全覆盖行、确定行高、传递连通组与短区纯跳过、重复表头字段、图片／链接唯一性及原始 UTF-16。独立检查标准 restart／continue／gridSpan、去私有元数据导入与未知样式诊断；全部新页双渲染并逐页查看。Word 对象模型可能折叠全覆盖物理行，不能据其索引改写源；读取、字段更新、工作流收尾及保存回读分别登记，不自动刷新基线。

- 静态表单／行内矢量变更运行 `build sbom publishLocalBundle :examples:staticForms :examples:adrForm`，验证隔离调用方并执行 `scripts/verify_static_forms.py`、`scripts/verify_adr_form.py`。检查最终矢量环／勾选多边形、逻辑状态、合并格完整行与连续栏、短标签唯一、填写线及全部独立渲染页面；共享文字变更还须回归字段、图片和进阶基线。不得自动更新基线。

- 合并格续页／普通嵌套文字变更运行 `build sbom publishLocalBundle :examples:gridContinuation :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:mixedColumns :examples:production :examples:adrForm`、隔离调用方和 `python scripts/verify_grid_continuation.py`；执行进阶、混合栏、生产及 ADR 独立回归，检查全部新页。默认整组、固定文本失败、末行唯一、复杂轮廓、图片原子性、字体策略和取消恢复必须保留，不自动更新基线。
- 组件语义插槽变更运行 `build sbom publishLocalBundle :examples:semanticTables` 与隔离调用方，执行 `python scripts/verify_semantic_tables.py`，核对目录偏移、分节页码、双向引用、原图／文字外链、语义源、字段失败及全部新页。回归受影响的网格、分栏、绕排、字段、图像和生产样例；重复语义源、缩放、旋转、部分裁切及页区路径须明确拒绝，不得自动丢弃或刷新基线。
- 语义区域续排额外运行 `:examples:semanticTableFlow` 和 `python scripts/verify_semantic_table_flow.py`，独立检查短区、逐栏表头页码、目录／分节偏移、原文行序及 UTF-16、拆行图片／外链、首片段锚点和全部新页。隔离调用方输出读取本次成功指针；重复／缺片／乱序／跨节来源、源与呈现预算和 FontScope 多短区须有行为回归。
- 组件默认样式变更运行 `build sbom publishLocalBundle :examples:styleDefaults`、隔离调用方及 `python scripts/verify_style_defaults.py`，核对最终 PDF 实际字号／颜色、显式 12 pt、快照、静态控件和合并格完整行续页；全部新页独立渲染并人工复查。回归字体／诊断、短区域、浮动网格、字段及语义来源；包装器须保留窄组件宽度及对齐，不自动刷新基线。
- 长合并格语义变更额外运行 `:examples:gridSemanticFlow` 和 `python scripts/verify_grid_semantic_flow.py`，核对两层表头逐页字段、短区／连续栏、完整行与内 Table 行列／UTF-16、图文外链矩形及全部独立渲染页。隔离调用方读取本次成功指针；误插重复源、内外拆行独立、源与呈现预算及取消恢复须回归；不刷新视觉基线。
- 编写入口、富文本标题／列表或中文两端对齐变更运行 `build sbom publishLocalBundle :examples:authoring`、隔离 Maven 调用方及 `python scripts/verify_authoring.py`，并回归受影响的文字／表格／分栏／字段样例。核对最终 CID／TJ advance、轮廓锚点、外链矩形、严格 DOCX 往返与全部新页。压力复现用 `scripts/measure_report_stress.ps1` 后执行 `python scripts/verify_report_stress.py`；只采样指定 JVM，记录运行 ID，区分工作集、私有字节与堆，禁止据有限样例宣称长期无泄漏。

- 声明式模板变更额外运行 `:examples:xmlTemplates` 和 `python scripts/verify_xml_templates.py`，检查严格语法、所有分支的表达式、零外联、资源快照、解析／绑定预算、取消恢复、动态长表／重复表头及全部新PDF页。新增适配器仍只单向依赖模型；不把JDK解析异常原文放入诊断。模型语义校验与BindingException的边界须明确。

- 多脚本／Markdown／PDF 处理／XML 字段变更额外运行 `build sbom publishLocalBundle :examples:textCorpus :examples:markdownTemplates :examples:pdfOperations :examples:xmlReferences`、新隔离调用方及四个同名 `verify_*.py`。核对原文簇、取消恢复、真实字体角色、纯文本绑定、静态 URI、最终页码／表头／目标、PDF 选页源像素和第三方类型隔离；全部新页独立渲染并逐张查看。DOCX 原生字段与重复表头 PAGE 拒绝分别验收；普通提取、ActualText、查询和精确选区不能混称。不自动更新基线。

- 逐属性样式变更额外运行 `:examples:propertyStyles`、新隔离调用方及 `python scripts/verify_property_styles.py`，核对实际字号／颜色、继承基线／缩进、显式零／枚举、完整旧回调、单项恢复、嵌套隔离、格工厂／快照、合并续页和短首栏；全部新页双渲染并逐张查看。回归字体、静态表单、语义来源、字段及图像；FormText 正文用 OutlineText 行框验证，网格填充高度不等同文字行高。不得刷新基线。

- 单向长表／逐页 SPI 变更运行 `build sbom publishLocalBundle :examples:streamingTable :examples:writeStreamingTableClasspath`、新隔离调用方和 `python scripts/verify_streaming_table.py --consumer-build-directory <成功构建目录>`。核对单次源／一行预读、重复表头、真实页行列诊断、普通小数高度的一 ULP 边界、所有预算、未知总量、回调快照、关闭顺序／suppressed／旧目标保持及全部新页双渲染。回归现有完整文档、交互、安全选项与最终 PDF/A。容量用 `scripts/measure_streaming_table.ps1 -SkipBuild -IncludeSmallHeapControl`，只采样独立 JVM；分别记录 layout-discard／PDF 的堆、工作集、私有字节、句柄、吞吐和临时盘，不宣称完整 PDF 恒内存或长期无泄漏。

- 单向富文本变更额外运行 `:examples:streamingRichText` 和 `scripts/verify_streaming_rich_text.py`，使用隔离调用方，独立核对实际字体变体／颜色／装饰、格边界和全部小样页。容量例的 sourceInlineRuns 必须从实际构造的行／格累计，不手算；富文本与普通控制分别采样并检查字体摘要、退出码及真实临时目录。
- 单向固定列横向合并变更额外运行 `:examples:streamingColumnSpans :examples:columnSpans` 与 `scripts/verify_streaming_column_spans.py`；核对完整覆盖、真实物理列、宽度只扣一次 padding、覆盖槽预算及锚点计数、重复表头、单行预读和输出后失败的原子保护。新样例／调用方全部页双渲染并逐页查看，原普通及富文本单向路径独立回归。

- 编写主题变更运行 `build sbom publishLocalBundle :examples:themes`、隔离调用方和 `scripts/verify_themes.py`，核对完整默认模型／最终绘制兼容、双主题全部页、六级标题分页策略、命名额度和快照、语义表格回调优先、组件局部覆盖／恢复、标准 OOXML。普通段落在 DOCX 中展开为默认运行是表示差异，规范化时不得删除完整格式／ID／内容断言。

- XML主题变更额外运行 `:examples:xmlThemes` 与 `scripts/verify_xml_themes.py`，核对p／span全部分支静态名称、完整值／属性优先、body／cell配置顺序、原子格分页拒绝、六级标题及真实字体角色、UTF-16深层来源。新样例与隔离调用方全部独立双渲染、逐页复查，标准DOCX往返只归一精确默认运行表示，不删除完整格式断言。

- XML多级编号变更运行 `build sbom publishLocalBundle :examples:xmlNumbering :examples:xmlReferences :examples:xmlIncludes` 与隔离调用方，执行 `scripts/verify_xml_numbering.py`；核对最终完整点分值、真实字段／XYZ、循环与prefix列表身份、标准ilvl／九级lvlText／有效祖先startOverride和实际书签位置。九级DOCX结构验证不冒充新增PDF或Word阅读器验证；全部新PDF页双渲染并逐页查看。

- 组件富文本／列表标记／XML 默认外观变更分别运行 `:examples:componentText :examples:listMarkers :examples:xmlTextDefaults` 及对应 `verify_component_text.py`／`verify_list_markers.py`／`verify_xml_text_defaults.py`，使用隔离调用方。检查真实物理字体、标记与正文独立推进和装饰、四类最终字段及链接框／XYZ、标准 DOCX 所选级别／marker-only 角色，保持全编号树有界验证和严格损失。全部新页双渲染并逐页查看；有限 Latin 样例不推广为一般塑形／Word 兼容。

- HTML／Markdown 导出变更运行 `build sbom publishLocalBundle :examples:webExports`、新隔离调用方及 `scripts/verify_web_exports.py`。核对真实锚点／覆盖槽、嵌套顺序、编号重启、强调边界、链接、原图字节、源码诊断及 ERROR／WARN／IGNORE 差异，并检查浏览器宽／窄屏输出；HTML/CSS 输入不在该验收内。

## 编辑、导入与转换

HTML 内嵌恢复变更另运行 `:examples:embeddedHtmlRecovery`、新 Maven 调用方的 `embeddedHtmlRecovery` 与 `scripts/verify_embedded_html_recovery.py --consumer-build-directory <本次成功目录>`。核对单文件三轮、HTML 注释外准确字节／模型及资源摘要、实际编辑、缺失／未知版本／外部改字、三动作和不保留已丢弃内容；重复／损坏封装不得进入 fallback。核对总编码及解码预算、取消／借用流／原子失败，全部新 PDF 页双渲染并查看，浏览器检查宽窄显示及注释不可见。回归既有伴随包矩阵，不将浏览器打开称为编辑器保存回读。

Markdown／SVG 内嵌及通用入口变更再运行 `:examples:embeddedCommentRecovery`、新 Maven 调用方的 `embeddedCommentRecovery` 及 `scripts/verify_embedded_comment_recovery.py --consumer-build-directory <本次成功目录>`。核对原标准字节、CommonMark 注释块／SVG 根后 XML 注释、三轮／真实编辑、当前 Markdown 回退及 SVG 无读取器拒绝、格式冲突、有损源清除和预算／原子输出；全部新 PDF 页双渲染并查看，浏览器分别检查实际宽窄显示。回归 HTML 便利入口与原伴随包矩阵，不把内嵌模型恢复说成任意 SVG 语义识别。

标准文件恢复变更运行 `:examples:modelRecovery`，再执行 `scripts/verify_model_recovery.py --prepare-external` 和 `:examples:modelRecoveryExternal`，最后用新 Maven 调用方的 `modelRecovery` 及验证器的 `--consumer-build-directory` 检查本次结果。分别核对七种输出的三轮、49 组 A→B→A、真实编辑、资源摘要、旧包失效、目标损失不隐藏源模型、历史／新增损失累计和借用流／失败原子性。DOCX 无编辑准确字节及未修改包条目、PDF 原页面改字的未修改区域、脱敏无源对象与伴随包拒绝分别验收；外部文件库重写与 Word 等应用保存不能混称。全部新 PDF 页双渲染并逐页查看，不以简单语料关闭一般复杂往返。

XML 模板源码／资源包变更先运行 `:examples:xmlTemplateBundle` 和 `scripts/create_xml_template_bundle_fixture.py`，再以新的 Maven 调用方构建目录运行 `xmlTemplateBundle`，执行 `scripts/verify_xml_template_bundle.py`。核对原源码、占位符、CRLF、图片／字体／许可字节、普通 ZIP 独立输入、编辑后重新绑定、包预算及取消／失败原子性；全部新 PDF 页双渲染并查看，DOCX 检查标准结构。回归平面及递归 XML include，不将模板包保存算作任意文档模型恢复。

语义模型保存为既有 XML 的变更另运行 `:examples:xmlModelSaving`、新 Maven 调用方的 `xmlModelSaving` 及 `scripts/verify_xml_model_saving.py`。核对完整格式、分节／页码、原文文字模式、表格／列表／字段、图片及显式字体／许可，三轮源码／模型控制、实际定点编辑、PREVIEW／ACTUAL 和三动作损失。未映射块必须明确拒绝或按真实处理报损；仅规范化精确相同样式的相邻运行，不删几何／ID／内容断言。全部新 PDF 页双渲染并逐页查看，控制组同引擎像素一致、DOCX 独立检查标准结构；回归 XML 默认外观、多级编号、片段和既有源码包。

注释／Caption／标题列表外链保存另运行 `:examples:xmlSemanticSaving`、新 Maven 调用方的 `xmlSemanticSaving` 及 `scripts/verify_xml_semantic_saving.py`。核对完整 document／notes 绑定、旧入口拒绝丢弃注释、静态锚点及条件／循环错误、ID 避让和字段关系、Caption 原始值／编号引用、文字链接的容器权限、共享预算及取消。三轮完整值／源码／资源控制、实际编辑及全部新增页双渲染／逐页查看；无编辑像素一致，编辑仅改变命中页。其他适配器按实际损失对账，不能把 XML 保留说成 DOCX／Web 无损支持。

独立 CrossReference 保存另运行 `:examples:xmlCrossReferenceSaving`、新 Maven 调用方的 `xmlCrossReferenceSaving` 和 `scripts/verify_xml_cross_reference_saving.py`。核对完整类型／目标／前缀／格式、跨节显示页码与实际物理跳转、题注／列表标签、多行预留、静态与动态副本目标、非法容器／关系拒绝、预算和取消。三轮源码／资源及同引擎像素控制、实际改写仅改变命中页；全部新页双渲染并逐页查看，Web 三动作按实际未解析标记对账。

页区模型保存另运行 `:examples:xmlRegionSaving`、新 Maven 调用方的 `xmlRegionSaving` 及 `scripts/verify_xml_region_saving.py`。核对同类静态来源／向后引用、跨节与同源多位置、默认／FIRST／EVEN／显式空对、正高度空区、整层清空、唯一源预算及真实区域诊断。三轮保存、共享编辑、全部新页双渲染及逐页查看；无编辑像素一致，编辑只改变命中页。DOCX 保留标准页区，图层按显式损失处理；回归既有 XML 保存／分节／变体／图层及 include，不据此关闭一般复杂模型恢复。

各批均运行 `build sbom publishLocalBundle` 和新的隔离 Maven 调用方，再执行下表中的实际样例与独立验证器。依赖已缓存且刷新网络不可用时，可以离线新建隔离构建目录；成功后才更新调用方指针，保留是否联网的真实边界。

| 变更 | 示例任务（`:examples:` 前缀） | 独立验证器（`scripts/verify_` 前缀） |
| --- | --- | --- |
| 语义编辑／字面替换／共享策略 | `semanticEditing` | `semantic_editing.py` |
| 通用 Markdown／HTML/CSS 导入 | `markdownImport`／`htmlImport` | `markdown_import.py`／`html_import.py` |
| 统一转换／编码预检 | `documentConversion` | `document_conversion.py` |
| DOCX 原包／样式级联 | `docxPackageEdit`／`docxStyleImport` | `docx_package_edit.py`／`docx_style_import.py` |
| PDF 页级编辑 | `pdfPageEditing` | `pdf_page_editing.py` |
| PDF 被动对象编辑／合并 | `pdfObjectEditing` | `pdf_object_editing.py`／`pdf_object_composition.py` |
| PDF 渲染／图片提取 | `pdfRaster` | `pdf_raster.py` |
| PDF 优化 | `pdfOptimization` | `pdf_optimization.py` |
| PDF 文字替换／区域脱敏 | `pdfContentEditing` | `pdf_content_editing.py` |
| PDF 语义恢复与转换 | `pdfSemanticImport` | `pdf_semantic_import.py` |

扩展 PDF 能力的独立夹具、Maven 调用方任务和验证脚本如下。夹具先用对应 `scripts/create_pdf_*.py` 生成；调用方任务在 `verification/consumer` 执行，不使用当前 classes 预验替代制品验收。

| 变更 | 调用方任务 | 独立验证器 |
| --- | --- | --- |
| 跨字体／同线定位文字及图片绘制实例 | `pdfInstanceEditing` | `scripts/verify_pdf_instance_editing.py` |
| 保留选区外字形／矢量的选择性脱敏 | `pdfSelectiveRedaction` | `scripts/verify_pdf_selective_redaction.py` |
| 扩展字段、六类标记、FDF／XFDF、XFA | `pdfExtendedForms` | `scripts/verify_pdf_extended_forms.py` |
| 自动栏／段落／页区及表格恢复 | `pdfAutomaticRecovery` | `scripts/verify_pdf_automatic_recovery.py` |
| PDF/UA-1、选定 PDF/A 及离线 PAdES | `pdfStandards` | `scripts/verify_pdf_standards.py` |

各脚本传入本次成功 `--consumer-build-directory`。选择性脱敏须额外移除新遮挡后检查真实剩余几何／像素，检查子集字体／ToUnicode 和隐藏文本／附件／历史排除；裁切图像外不得覆盖未绘制背景。自动恢复须核对叶来源及物理格路径、完整正文和缺失／乱序负对照。标准输出逐份独立 veraPDF／pyHanko 验证，实际辅助阅读器结果与结构校验分别记录；未知标准和 DocMDP 修改不得按可信成功处理。

检查 PREVIEW／ACTUAL 对账、IGNORE 实际损失、配置快照、未编辑内容及取消／失败原子性。全部新 PDF 页双渲染并查看；DOCX／HTML／Markdown 验证真实标准结构、文字和图片字节／像素。PDF 文字替换验证未命中位置，脱敏检查所有对象／解码流和区域像素，不能以矩形覆盖代替删除。恢复模型允许已声明的重排，不能要求像素恒等或声称任意无损恢复。Word 打开、更新、保存和导出各自登记；控制组也超时后不无假设重试。

单页 PNG 内嵌恢复另运行 `:examples:embeddedPngRecovery`、本次新隔离调用方的 `embeddedPngRecovery`，再执行 `scripts/verify_embedded_png_recovery.py --prepare-external`、`:examples:embeddedPngRecoveryExternal` 和验证器主入口。独立检查 PNG 签名／块顺序／CRC、唯一 exJM、去块准确原始字节、ZIP／模型／字体／许可摘要及 Pillow 全像素解码；三轮准确文件、真实改字、外部改像素及移除恢复块的六行三动作不得返回旧模型。覆盖单页后端多页／extras／像素拒绝、总字节／新增块数／ZIP 预算、取消、借用流和失败原子性。3 页 PDF 双渲染并逐页查看；核对当前分发 ZIP 的 15 个自研 JAR 与 SBOM，回归 HTML、Markdown／SVG 及七格式伴随恢复。单 PNG 与 PNG 页 ZIP 分开，不将块 framing 校验说成完整像素解码，不宣称任意编辑器重存已验收。

PDF／DOCX／PNG 页 ZIP 可选内嵌恢复另运行 `:examples:embeddedPdfRecovery :examples:embeddedZipRecovery`、独立调用方的 `embeddedContainerRecovery`、`scripts/verify_embedded_containers.py --prepare-external`、两个 `embedded*RecoveryExternal` 样例和验证器主入口。核对 PDF 附件／原文件前缀、DOCX 与页 ZIP 成员／准确去包、三轮模型和字体／许可摘要、实际编辑、外部重写与移除恢复的三动作、预算／取消／原子失败；以 pypdf、python-docx、Pillow 和 Poppler／PDFium 检查页面。Windows 有 Word 时运行 `scripts/verify_embedded_containers_word.ps1` 核对本轮 DOCX 打开和文字；Word 另存须独立完成才能宣称保存回读兼容。记录七格式普通／内嵌文件的实际字节增量，不把样例比例当固定成本。回归旧内嵌和伴随恢复，见[阶段八十六](../../../../docs/verification/phase-86-standard-container-recovery.md)。

## 变宽 owner、竖排、有界合并与矢量透明度

新增单元需运行全仓 `build sbom publishLocalBundle`、`:examples:variableSplitTables` 及本次新 Maven 调用方的 `run verticalText streamingRowSpans gap21Graphics`，再分别执行 `scripts/verify_variable_vertical_spans.py`、`scripts/verify_gap08_vertical_text.py`、`scripts/verify_streaming_row_spans.py` 和 `scripts/verify_gap21_graphics.py`；均传入本次成功调用方目录。

核对原始 owner 行／列、覆盖行、实际宽度、UTF-16、字段和图片／外链；竖排检查逐字形方向与标点形式，不能只验文字顺序；单向组检查有限预读、预算、取消／失败关闭及旧目标，容量在独立 JVM 中记录堆上限和实际计数。透明度检查 fill／stroke 独立值、嵌套恢复、非交换变换及三后端可见像素，PDF/A-1 拒绝与 PDF/A-2b 独立 veraPDF 分开验收。全部新增 PDF 页双渲染并查看；SVG 结构检查不能冒充浏览器视觉验证。
