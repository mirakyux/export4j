# 构建、依赖与验证矩阵

按受影响契约选择下列验证，不将所有样例作为每次变更的固定前置条件。所有命令均从仓库根目录运行；Gradle 参数前按操作系统加 Wrapper。

## 构建与依赖

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

- 编写主题变更运行 `build sbom publishLocalBundle :examples:themes`、隔离调用方和 `scripts/verify_themes.py`，核对完整默认模型／最终绘制兼容、双主题全部页、六级标题分页策略、命名额度和快照、语义表格回调优先、组件局部覆盖／恢复、标准 OOXML。普通段落在 DOCX 中展开为默认运行是表示差异，规范化时不得删除完整格式／ID／内容断言。

- XML主题变更额外运行 `:examples:xmlThemes` 与 `scripts/verify_xml_themes.py`，核对p／span全部分支静态名称、完整值／属性优先、body／cell配置顺序、原子格分页拒绝、六级标题及真实字体角色、UTF-16深层来源。新样例与隔离调用方全部独立双渲染、逐页复查，标准DOCX往返只归一精确默认运行表示，不删除完整格式断言。

- XML多级编号变更运行 `build sbom publishLocalBundle :examples:xmlNumbering :examples:xmlReferences :examples:xmlIncludes` 与隔离调用方，执行 `scripts/verify_xml_numbering.py`；核对最终完整点分值、真实字段／XYZ、循环与prefix列表身份、标准ilvl／九级lvlText／有效祖先startOverride和实际书签位置。九级DOCX结构验证不冒充新增PDF或Word阅读器验证；全部新PDF页双渲染并逐页查看。

- 组件富文本／列表标记／XML 默认外观变更分别运行 `:examples:componentText :examples:listMarkers :examples:xmlTextDefaults` 及对应 `verify_component_text.py`／`verify_list_markers.py`／`verify_xml_text_defaults.py`，使用隔离调用方。检查真实物理字体、标记与正文独立推进和装饰、四类最终字段及链接框／XYZ、标准 DOCX 所选级别／marker-only 角色，保持全编号树有界验证和严格损失。全部新页双渲染并逐页查看；有限 Latin 样例不推广为一般塑形／Word 兼容。

- HTML／Markdown 导出变更运行 `build sbom publishLocalBundle :examples:webExports`、新隔离调用方及 `scripts/verify_web_exports.py`。核对真实锚点／覆盖槽、嵌套顺序、编号重启、强调边界、链接、原图字节、源码诊断及 ERROR／WARN／IGNORE 差异，并检查浏览器宽／窄屏输出；HTML/CSS 输入不在该验收内。
