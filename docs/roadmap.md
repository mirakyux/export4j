# 研发路线与验收

本文维护项目的阶段目标与验收原则。能力范围见[支持矩阵](capabilities-api.md)，逐项状态见[缺口表](capability-gaps.md)，具体执行顺序见[剩余计划](remaining-capabilities-plan.md#后续扩展推进顺序)。已发布 RC3 与当前开发版的区别、开发补充及已知接入限制统一见[当前状态](current-status.md)。

## 当前推进方向

| 方向 | 对应条目 | 当前目标 |
| --- | --- | --- |
| 兼容性证据 | GAP-14.2 | 从真实使用组合扩充固定语料、系统及阅读器矩阵，区分自动检查与实际应用动作 |
| 稳定版准备 | GAP-14.3 | 在已有 RC 发布基础上明确 API 冻结、兼容策略和稳定版验收，不把候选发布视为稳定性保证 |
| 新格式 | GAP-22 | 按格式及输入／输出方向选择独立单元，先定义语义、资源、损失和验收 |

已有验收子集继续保持明确预算、资源生命周期、原子输出和失败诊断。真实缺陷优先复现和修复；新组合重新验收。OCR 暂在范围外，不计入当前交付队列。

## 阶段总览

| 阶段 | 目标 | 核心交付物 |
| --- | --- | --- |
| 一 | 验证项目基础与 PDF 最小闭环 | 可构建核心库、PDF 示例和基础自动化测试 |
| 二 | 建立可靠的字体与文本排版 | 字体契约、测量、换行和混排测试集 |
| 三 | 实现自动布局与分页 | 多页报告、跨页表格和布局诊断 |
| 四 | 建立多格式适配体系 | DOCX 基础导入导出、SVG 输出和兼容性矩阵 |
| 五 | 完善性能、稳定性与正式发布 | 性能基线、稳定 API、SBOM 和发布制品 |

阶段按能力推进，不预设日期、人力或未经测量的性能指标。下文概括各阶段的原始目标与主要证据；早期交付限制不作为当前缺失清单，后续补齐结果以支持矩阵和缺口表为准。

## 阶段一：项目基础与 PDF 最小闭环

范围：建立 Gradle 多模块项目、最小文档模型、Graphics API、Display List 和 PDFBox 后端。先实现显式定位的文字、路径、图片与页面设置。

验收要求：

1. 提交完整 Gradle Wrapper，Java 21 环境能够构建并运行基础自动化测试。
2. 公共 API 不暴露 PDFBox 类型，核心模块不反向依赖 PDF 后端。
3. 通过 Document Model 或 Graphics API 生成包含文字、图片、图形和基本页面设置的 PDF。
4. 示例可按文档命令执行，资源来源和输出位置明确。
5. 输出可由独立 PDF 工具解析、渲染，并保存验证记录。
6. 自研源文件有 Apache-2.0 声明；直接、传递依赖与示例资源都有许可证审查记录。
7. 基础测试覆盖页面尺寸、坐标、资源输出和失败路径的释放行为。
8. README、架构文档与真实构建、能力范围一致。

完成最小闭环不代表支持自动换行、中文字体回退、复杂塑形或自动分页。

阶段一交付包含 5 个实际模块，当时语义模型提供页面尺寸与元数据。段落语义与分页在阶段三首批实现，当前绘制能力与限制见[绘制 API](graphics-api.md)。

构建、测试和独立 PDF 工具证据见[阶段一验收记录](verification/phase-1.md)。

## 阶段二：字体与文本排版

范围：字体加载、字形映射、度量、普通换行、对齐、缩进、行距、中文及中英文混排。

验收要求：

1. 测量与绘制使用一致的字体及字形数据。
2. 样本覆盖中文、混排、Unicode 边界和缺字；明确尚不支持的复杂脚本。
3. 字体嵌入、文本提取与 Unicode 映射经过实际输出验证。
4. 字体回退顺序固定，缺字和回退行为可诊断。
5. 固定字体与配置时，行布局结果可复现。

本阶段已实现静态 TrueType 加载、Unicode 字形映射、显式回退、嵌入与子集化，以及普通水平文字的测量、换行和行布局。原有 5 个模块之外新增字体与文本模块，未提前创建分页模块。

字体、混排、Unicode 边界、几何、预算和 PDF 一致性的证据见[阶段二验收记录](verification/phase-2.md)。复杂塑形、完整中文禁则、富文本行内样式与自动分页不包含在本阶段结果中。

## 阶段三：自动布局与分页

首批已完成段落语义节点、页面内容区域、段前段后间距、保持整块与按完整行跨页。新增布局模型和布局核心，`DocumentLayout` 保存页面与来源映射，`ParagraphLayout` 保持段落内部结果的职责。

证据见[段落分页验收](verification/phase-3-paragraphs.md)、[表格验收](verification/phase-3-tables.md)和[组件验收](verification/phase-3-components.md)。基础表格支持固定／比例列宽、单元格换行、自动行高、不可分割行和重复表头。标准组件支持页眉页脚、最终页码、图像、形状、线条和水印；目录、书签、评论与基础可填写表单已进入 PDF 首版。定位支持绝对、锚点和比例模式；普通正文组件整块流动，PageableComponent 按有序片段续页。

进阶批次已实现最低片段行数、保持相邻、分节模板、富文本、合并图文表格、嵌套续页、编号／引用、注释、透明图像和中文表单，见[进阶验收](verification/phase-3-advanced.md)。生产批次进一步提供多栏、矩形绕排、文本表格拆行、脚注续页与换行引用；后续复杂脚本已进入公共段落路径，并扩展富文本单元格、外链、自动列宽与末页平衡，详见[能力指南](capabilities-api.md)。

多浮动组件支持首片段多个矩形的逐行避让、封闭行带跳过和整体移页，见[调用指南](multi-floats-api.md)。[任意轮廓与跨页浮动锚点](contour-floats-api.md)进一步按实际曲线行带布局，保留原文位置及真实子流续排，见[阶段九十九](verification/phase-99-layout-fonts-streaming-graphics-closure.md)。

当前页连续文字分栏已支持剩余高度、完整续栏、两栏／三栏、最低行数移页和后续正文接续，见[调用指南](continuous-columns-api.md)及[独立验收](verification/phase-8-continuous-columns.md)。混合组件、嵌套流与表格后续已接入区域 SPI，支持图片整块移动、逐栏表头和完整合并行组，见[混合分栏指南](mixed-columns-api.md)及[验收](verification/phase-9-mixed-columns.md)。文字／混合流变栏宽与平衡见[阶段五十九](verification/phase-59-variable-columns.md)，变宽最低多行策略见[阶段六十三](verification/phase-63-minimum-lines.md)；[原子语义表格](variable-tables-api.md)及[完整行续排](variable-table-continuation-api.md)已接入变宽区域；纵向 owner、递归子树与组件网格的变宽续排已由[阶段九十九](verification/phase-99-layout-fonts-streaming-graphics-closure.md)完成。

正文行内动态字段、页码／编号引用和注释锚点已实现。字段固定宽度，最终分页后解析；脚注跟随标记实际所在行，见[字段指南](inline-fields-api.md)及[验收](verification/phase-10-inline-fields.md)。语义表格单元格进一步支持页码和引用字段、逐页重复表头及拆行来源，见[表格字段验收](verification/phase-11-table-fields.md)。DOCX 原生四类字段及显式宽度导入已补齐，见[DOCX 字段验收](verification/phase-12-docx-fields.md)。这些记录只覆盖当时字段路径；长表、组件字段及可访问性等后续进展按各自指南和缺口表判断。

范围：约束与尺寸计算、段落流式布局、表格列宽与行高、跨页处理、页眉页脚、页码和重复表头。

验收要求：

1. 生成多页报告，内容顺序正确，无非预期丢失或重复。
2. 表格跨页与重复表头正确，声明单元格和不可分割行的支持范围。
3. 空页面、超长段落、超大图片和超高行具有明确行为。
4. 分页保证推进；不可容纳内容不会导致无限循环。
5. 布局诊断能够定位相关节点和页面。
6. 建立固定环境下的视觉回归与几何断言，能解释基线差异。

## 阶段四：多格式支持

首批完成 DOCX 语义子集、SVG 和 PNG ZIP。后续已扩展原生字段、合并／递归表格、页区与有类型编辑；当前用法见[DOCX 原生语义](docx-semantic-api.md)，历史首版与完整扩展分别见[阶段四](verification/phase-4-production.md)和[阶段一百零三](verification/phase-103-native-docx-semantic-editing.md)。PDF 的页面、对象、内容编辑及恢复使用[专用入口](pdf-adapter-api.md)，每条路径分别声明支持子集和实际损失，不能用早期静态复制能力概括当前范围。

范围：DOCX 基础导入导出、SVG 输出，以及按实际需求选定的 PDF 导入、分析或编辑能力。

验收要求：

1. DOCX 通过文档模型导入导出，不以 PDF 坐标指令代替语义结构。
2. 对段落、表格、样式和资源建立支持矩阵与往返样本。
3. SVG 在独立工具中解析和显示，覆盖文字、图形与资源处理。
4. 不支持、降级和无法无损转换的特性均有明确诊断。
5. PDF 扩展声明具体能力，不宣称任意 PDF 可无损恢复语义模型。

## 阶段五：性能、稳定性与正式发布

首批稳定性验证通过 4 线程 24 次 PDF 导出和 2,500 段落实际导出，并完成真实 Edge 表单保存重开、隔离 Maven 调用方、BOM 与 SBOM。这些历史样例不等同于长期压力容量、RSS 测量或发布完成。后续 RC1、RC2、RC3 的远端发布及消费已有独立记录，稳定 API／稳定版和更广应用兼容仍按缺口表推进。

后续补充两端对齐、可选严格中文禁则、富文本标题／列表和常用编写入口。新增 2 线程、6 份各 26 页混合报告，采样独立 JVM 的 Windows 工作集、私有字节和句柄，并验证取消／输出超限恢复。该证据仍属于有界工作负载，不代表长期泄漏或容量保证，见[阶段十三](verification/phase-13-authoring.md)。

取消／超时／进度／原子文件写入已有实现；AES-256、选定 PDF/A-1b／2b／3b／4f、显式 PDF/UA-1 和离线 PAdES B／T／LT／LTA 使用各自独立入口及验收，见[标准指南](pdf-standards-api.md)。[语义长表、重放及磁盘索引 PDF](streaming-replay-api.md)和本轮有限持续压力已完成；跨系统／阅读器矩阵继续归 GAP-14.2。具体范围见[能力矩阵](capabilities-api.md#docx-与剩余范围)。

固定列普通文字已接入独立单向源和逐页 PDF，见[阶段二十四](verification/phase-24-streaming-tables.md)。布局丢弃页／真实 PDF 各 1k／10k／100k 行及 64 MiB 堆布局控制共 7 组独立 JVM 通过；100k PDF 为 1,786 页、325.281 秒。结果同时记录观察堆、Windows 工作集／私有字节／句柄、吞吐、临时盘及退出文件清理。PDFBox 页树仍累计；这不完成一般文档恒内存或持续容量目标。

范围：性能基线、大文档内存、资源缓存、并发隔离、API 稳定和开源治理。已记录单任务 5,000 段落基线及堆内存池峰值之和；尚不等于并发容量、RSS 或发布性能保证。

验收要求：

1. 性能记录包含样本、硬件、JDK、预热、并发与测量方法。
2. 大文档与并发样本验证峰值内存、资源释放和任务隔离。
3. 异常输入、预算超限和资源不足时行为可预测。
4. 公共 API、版本兼容策略、迁移说明和用户文档完整。
5. 许可证清单、必要的 NOTICE、SBOM 和发布检查通过。
6. 私密安全报告渠道可用，自动化构建、测试和发布流程已实际运行。
7. 发布制品可以在干净的调用方项目中解析依赖并运行示例。

## 验收记录

阶段细节、工具版本、样本、测试数量和当时限制保存在各自验收记录，不在路线图重复累计。以下按主题定位主要演进；完整记录见[验收索引](verification)。

| 主题 | 主要证据 |
| --- | --- |
| 基础闭环、字体与分页 | [阶段一](verification/phase-1.md)、[阶段二](verification/phase-2.md)、[段落](verification/phase-3-paragraphs.md)、[表格](verification/phase-3-tables.md)及[组件](verification/phase-3-components.md) |
| 编写、组件、语义续排及样式 | [编写入口](verification/phase-13-authoring.md)、[语义组合](verification/phase-17-semantic-components.md)、[表格区域流](verification/phase-18-semantic-table-flow.md)、[默认样式](verification/phase-19-style-defaults.md)、[逐属性样式](verification/phase-23-property-styles.md) |
| XML、表格与主题 | [XML 首版](verification/phase-21-xml-templates.md)、[列合并](verification/phase-25-column-spans.md)、[行合并](verification/phase-26-row-spans.md)、[块格](verification/phase-27-block-cells.md)、[可读 XML 格](verification/phase-28-xml-block-cells.md)、[Java 主题](verification/phase-30-authoring-themes.md)、[XML 主题](verification/phase-31-xml-themes.md) |
| 编辑、导入及转换 | [Web 导出](verification/phase-43-web-exports.md)、[语义编辑](verification/phase-44-semantic-editing.md)、[Markdown 导入](verification/phase-45-markdown-import.md)、[HTML 导入](verification/phase-46-html-import.md)、[统一转换](verification/phase-47-document-conversion.md) |
| 复杂布局、文字、长表及图形 | [阶段九十九](verification/phase-99-layout-fonts-streaming-graphics-closure.md)汇总 GAP-03／08／09／21 的原剩余清单及有限容量 |
| PDF 编辑、标准及自动恢复 | [扩展表单](verification/phase-94-pdf-extended-forms.md)、[自动恢复](verification/phase-95-pdf-automatic-recovery.md)、[标准](verification/phase-96-pdf-standards.md)、[编辑闭合](verification/phase-97-pdf-editing-standards-recovery-closure.md) |
| 原生 DOCX、CSS 与兼容门禁 | [阶段一百零三](verification/phase-103-native-docx-semantic-editing.md)、[一百零四](verification/phase-104-html-css-layout.md)、[一百零五](verification/phase-105-publication-and-compatibility.md)、[固定语料](verification/phase-106-security-and-compatibility-corpus.md)、[Windows 应用](verification/phase-107-windows-reader-compatibility.md) |
| 设计器与业务样例 | [阶段一百零八](verification/phase-108-designer-and-business-scenarios.md)及[当前设计器指南](designer.md)列出的后续交互验收 |
| 开发版易用性补充 | [字体缓存](verification/font-cache.md)、[源内容](verification/content-blocks.md)、[富内容](verification/rich-content.md)、[统一入口及对象绑定](verification/easy-export.md) |

每次新增验收都应明确对应源码或制品、输入资源、命令、独立工具及限制。构建、文件结构、渲染、实际阅读器动作和正式发布分别记录，不用同名入口或任务勾选代替证据。

## GAP-03／08／09／21：原剩余清单已完成

本轮按原剩余清单统一推进，未增加编号或删减关闭条件：变宽递归格／组件网格与轮廓锚点；可变／CFF2／彩色字体、方向塑形与静态 CFF 原生 PDF；单次语义长表、显式重放与磁盘索引 PDF；CMYK／ICC／专色、混合／透明组、渐变／图案和 SVG 本地资源。当前接口见[变宽续排](variable-table-continuation-api.md)、[轮廓浮动](contour-floats-api.md)、[原生文字](native-opentype-api.md)、[语义重放](streaming-replay-api.md)及[高级图形](advanced-graphics-api.md)。最终构建、独立输出及容量结果统一登记在[阶段九十九](verification/phase-99-layout-fonts-streaming-graphics-closure.md)。

历史阶段中的“下一项”及“仍开放”描述保留当时范围，当前状态以缺口表和最新验收为准；不把已经完成的既定内容重复加入后续队列。HTML/CSS 的文档布局扩展已由[阶段一百零四](verification/phase-104-html-css-layout.md)验收，完整浏览器功能不属于该明确子集。

## GAP-12／14／18：原生语义、CSS 布局与分发检查

[阶段一百零三](verification/phase-103-native-docx-semantic-editing.md)完成 DOCX 有类型浮动图／真实文本框、图片变换、评论／六类修订／控件／旧式表单／目录，GAP-12.1／12.2 明确子集均已验收。真实 Word 字段更新、编辑、保存重开与发布 JAR 回读通过，4 页原生页面、4 页 Word PDF 和 4 页语义投影 PDF 完成独立渲染及逐页复查；五包标准校验零错误。验收脚本改为强类型 Office 互操作后解决导出超时，生成／转换链路在无桌面且禁止加载打印类的 JVM 下通过，不依赖打印驱动、打印服务或 Word。既有目录缓存由调用方显式刷新，不以强制全局重算破坏表单当前值。

[阶段一百零四](verification/phase-104-html-css-layout.md)验收 GAP-18.1 的静态选择器、媒体／分页、Flex／Grid、尺寸及定位：14 页 PDF、8 份 SVG、Chromium 源几何、四项负控制和真实损失通过。[阶段一百零五](verification/phase-105-publication-and-compatibility.md)完成 API 固定基线和兼容门禁、本地完整签名分发及实际坐标 JAR 摘要对账。提交 `cc132e7` 的 Java 21 三平台构建和临时密钥签名分发远端 CI 均已通过；当时正式版本／身份／命名空间、Central 上传及远端消费尚未验收；后续 RC 发布结果以发布记录为准。本地统一构建 1,783 项测试通过，跨平台应用／阅读器行为与正式发布分别验收。

[阶段一百零六](verification/phase-106-security-and-compatibility-corpus.md)固定 `0.1.0-rc.1` 候选 API，新增依赖公告门禁与四格式 16 条路径／32 份输出的互转语料；DOCX 包完整性和完整输入预算缺陷已修复，jsoup 升至 1.23.2。1,787 项 Java 测试、73 次控制检查、互转 8 页及 CSS 14 页独立渲染、本地临时签名与实际坐标消费通过。该阶段新增 CI 当时尚无远端证据，正式发布未执行；已知公告查询无命中不等于无漏洞。

[阶段一百零七](verification/phase-107-windows-reader-compatibility.md)完成 Windows Word 四页复查、控件实际编辑保存重开，以及 PDF 搜索、视觉选择、表单填写保存重开和最终 JAR 回读；修复 DOCX 现代布局模式声明及 PDF 应用元数据兼容。1,792 项测试、固定语料与无打印依赖验证通过；该阶段字节当时未重新签名，浏览器前台交互、其他平台、新远端 CI 和正式发布分别保留未验；后续版本发布不补作未执行的应用兼容证据。

[阶段一百零八](verification/phase-108-designer-and-business-scenarios.md)完成 GAP-13：独立 HTML 应用直接编辑既有 XML／ZIP，保留变量／条件／循环与显式资源，提供真实分页预览、8 步撤销、保存重开和六格式损失预检。四套业务样例共 24 份输出、11 页独立渲染及 11 项失败控制通过；构建 1,804 项 Java 测试、库 API 对比和隔离调用方通过。该阶段未完成独立项为 GAP-14.2、GAP-14.3、GAP-22，未执行正式发布。

2026-11-05，候选版 `0.1.0-rc.1` 使用正式身份签名并发布到 Maven Central，17 个模块的 82 个制品远端字节、签名和校验一致，16 个库与 BOM 的 Central 隔离消费及文档输出验证通过。当时新增的手动 Maven／GitHub Release 工作流尚无 GitHub 实际运行证据。

2026-11-07（北京时间），`0.1.0-rc.2` 的[手动发布工作流](https://github.com/mirakyux/export4j/actions/runs/42262570964)与 [GitHub Release](https://github.com/mirakyux/export4j/releases/tag/v0.1.0-rc.2)完成，16 个库与 BOM 已发布到 Maven Central，构建、签名、上传与远端隔离消费均有该运行的独立记录。普通文档逐页导出从 RC2 起提供，当前 API 门禁改用从 Central RC2 库 JAR 提取的[已发布基线](api-compatibility.md)。GAP-14.3 继续保留稳定 API 与稳定版验收；详见[发布记录与流程](releasing.md#已发布候选记录)。
