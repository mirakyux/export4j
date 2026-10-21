# 能力缺口与推进表

更新日期：2026-10-21。依据当前源码和独立验收记录。目标是易用、可控的 Java 文档生产引擎；不以功能名称数量计算完成率。

状态含义：**待实现**尚无完整实现；**推进中**已有实现但未完成验收；**部分支持**只有明确子集；**已验收**所列目标及边界均有证据。每项独立关闭，不能以 Builder 或同名方法代替行为验收。

能力补齐顺序见[剩余计划](remaining-capabilities-plan.md)。OCR 不在当前范围内。

[阶段三十六](verification/phase-36-xml-page-settings.md)已补齐 GAP-07 的 XML 静态纸张／方向／边距及绝对单位子目标，937 项测试、6 页独立输出与 13 组回归通过。[阶段三十七](verification/phase-37-page-region-fonts.md)按[固定页区角色设计](adr/0044-page-region-font-roles.md)修复四类固定区域的字体角色上下文：全仓 948 项测试、4 页双渲染／逐页复查、新调用方及 17 组独立检查最终通过；一次原生异常退出与单独重跑证据保留。后续[阶段三十八](verification/phase-38-component-text.md)已交付 FontRoleScope／固定富文本，975 项测试及 3 页独立输出通过；[阶段三十九](verification/phase-39-list-markers.md)与[四十](verification/phase-40-xml-text-defaults.md)进一步交付独立列表标记与 XML 分域默认角色／颜色。最终全仓 1,029 项测试、8 页双渲染／逐页复查、新隔离调用方、18 组历史独立检查及两组新检查通过。当前达到受支持报告／静态表单的本地试用门槛，见[就绪评估](readiness-assessment.md)。[阶段四十一](verification/phase-41-xml-page-regions.md)进一步补齐固定 XML 页眉页脚、独立主题及最终页码；1,041 项测试、5 页双渲染／逐页复查、新隔离调用方和 14 组历史独立回归通过。[阶段四十二](verification/phase-42-sections-and-docx-regions.md)进一步补齐页区图片／表格、XML 静态分节及 DOCX 默认页区导入导出。完整级联及更广页区变体保持独立边界。

## 推进顺序

| 编号 | 优先级 | 缺口及当前边界 | 本项目目标与验收条件 | 状态 |
| --- | --- | --- | --- | --- |
| GAP-01 | P0 | 原嵌套 `.text()` 默认整块，需另选 `FlowText` | 普通文字按完整行自然续页；显式固定文字；短首区、嵌套、取消和缺字诊断保持 | 已验收：[阶段十五](verification/phase-15-continuation.md) |
| GAP-02 | P0 | 原合并格不能容纳跨页长文字 | 显式拆分合并行组；逐页表头、边框和内边距；文字及图片唯一；超高原子内容失败；独立 PDF／SVG 几何及调用方验证 | 已验收：[阶段十五](verification/phase-15-continuation.md)；富文本字段另见 GAP-06 |
| GAP-03 | P0 | `.table()` 在顶层和列内代表不同模型 | 明确 `.grid()` 与语义表格路径，保留兼容入口；共享语义表格区域流，字段、来源和 DOCX 行为保持 | 部分支持：语义长表区域流、逐栏表头字段及拆行见[阶段十八](verification/phase-18-semantic-table-flow.md)；两层原子块格及标准 DOCX 嵌套见[阶段二十七](verification/phase-27-block-cells.md)；完整源重复、递归子树续排仍拒绝 |
| GAP-04 | P1 | 原正文默认格式不进入嵌套组件 | 可复用默认样式贯穿嵌套；显式覆盖优先；真实字体变体、固定行框和诊断不变 | 部分支持：[阶段十九](verification/phase-19-style-defaults.md)的完整继承及[阶段二十三](verification/phase-23-property-styles.md)的六项具名覆盖／恢复、网格／作用域快照、短区／续页与旧调用兼容已验收；[阶段三十](verification/phase-30-authoring-themes.md)的 Java 编写时主题／命名完整样式已验收；[阶段三十一](verification/phase-31-xml-themes.md)的 XML 主题及 p／span 静态名称已验收；[可编辑字段样式](verification/phase-32-editable-form-styles.md)、[目录独立样式](verification/phase-34-contents-styles.md)和[固定页区角色传递](verification/phase-37-page-region-fonts.md)已验收；[组件字体作用域与固定富文本](verification/phase-38-component-text.md)、[独立列表标记](verification/phase-39-list-markers.md)及[XML 分域字体／颜色默认值](verification/phase-40-xml-text-defaults.md)已验收；[XML 页眉页脚主题](verification/phase-41-xml-page-regions.md)已验收；模型全局颜色／角色级联、背景／前景主题及连续富文本组件仍开放 |
| GAP-05 | P1 | 原 ADR 静态勾选依赖示例私有绘制 | 通用静态勾选／选项组／标签值／填写线，矢量标记不依赖字体缺字；密集表单替换样例辅助代码 | 已验收：[阶段十六](verification/phase-16-static-forms.md)；静态组件不含 DOCX／PDF/UA 语义 |
| GAP-06 | P1 | 组件合并格原缺富文本、字段和单元格来源 | 长合并格支持行内样式、图片、最终字段；重复表头按实际页解析；行列及 UTF-16 来源可追踪 | 已验收：[阶段二十](verification/phase-20-grid-semantic-flow.md)；标准 SemanticTable／FlowColumn 格内续排、两层表头逐页字段、内 Table 行列／UTF-16 来源；任意嵌套网格拆分仍不支持；当批未包含的合并语义 Table 已由阶段二十五／二十六另行补齐 |
| GAP-07 | P1 | 模板原只通过 Java 节点接入 | 安全的 Markdown／声明式模板子集，变量、条件、循环、资源访问策略、预算和损失诊断；HTML/CSS 单独定义子集 | 部分支持：XML／严格 Markdown、四类字段／1～9级十进制编号／静态外链，以及[阶段二十八](verification/phase-28-xml-block-cells.md)的可读两层块格与复制引用已验收；[XML 主题及静态名称](verification/phase-31-xml-themes.md)、[平面离线 include](verification/phase-33-offline-xml-includes.md)、[XML 多级编号](verification/phase-35-xml-numbering.md)和[静态页面配置](verification/phase-36-xml-page-settings.md)已验收；[分域文字默认值](verification/phase-40-xml-text-defaults.md)已验收，包含局部恢复、链接／字段／列表标记及嵌套格范围；[固定 XML 页眉页脚](verification/phase-41-xml-page-regions.md)及[图文／表格页区与静态分节](verification/phase-42-sections-and-docx-regions.md)已验收；XML 背景／前景、首页／奇偶变体、完整级联、HTML/CSS、递归 include 和设计器仍开放 |
| GAP-08 | P1 | 复杂文字、搜索与选择兼容有限 | 扩充脚本、组合簇、断行和阅读器语料；记录字体、搜索／选择与视觉结果；不声称完整 OpenType | 部分支持：[阶段二十二](verification/phase-22-text-and-adapters.md)验收原文／实际字形簇、双向续行及连字一致性；六脚本两路径共 12 页；PDFium 查询精确，11 项 pypdf 提取与轮廓选区几何仍有限 |
| GAP-09 | P1 | 完整文档仍保留全量模型／布局；单向长表覆盖固定列普通／纯富文本，PDFBox 页树仍累积 | 有界数据源、取消和临时资源生命周期；分别测量布局与实际 PDF 的内存、吞吐和上限；持续容量独立验收 | 部分支持：[阶段二十四](verification/phase-24-streaming-tables.md)验收单向源／逐页布局绘制／同步 PDF／扁平摘要；7 组独立 JVM 含布局和 PDF 各 1k／10k／100k 行、64 MiB 堆布局控制及退出清理。[阶段二十九](verification/phase-29-streaming-rich-text.md)补齐纯富文本／显式角色、空运行额度、6 页独立验收与 9 组普通／富文本 JVM 控制。图片、合并、未来语义和持续容量仍开放；不宣称 PDF 恒内存 |
| GAP-10 | P2 | PDF 读改／合并／拆分／提取原缺公共入口 | 独立适配器；第三方类型隔离；显式密码、交互与签名处理边界；受损／恶意输入预算及独立验证 | 部分支持：[阶段二十二](verification/phase-22-text-and-adapters.md)验收静态页检查、普通字形提取、有序选页／拆分／合并、显式 owner 解密复制、预算和原子输出；42 项测试、11 页独立验证。交互编辑、优化、OCR 和任意 PDF 兼容仍不支持 |
| GAP-11 | P2 | PDF/UA、PAdES/LTV、PDF/A 范围有限 | 按标准逐项实现并用独立验证器验收；不能由标签或一次输出推断合规 | 部分支持：静态 RGB PDF/A-2b、CMS 外部签名 |
| GAP-12 | P2 | DOCX 合并表格、复杂页区及布局互转有限 | 标准 OOXML 合并与受支持样式；Word 更新／保存回读；严格损失边界；不承诺 PDF 同分页 | 部分支持：[阶段二十五](verification/phase-25-column-spans.md)／[二十六](verification/phase-26-row-spans.md)的双跨度和[二十七](verification/phase-27-block-cells.md)的两层原子块格已贯通标准 gridSpan／vMerge／p-tbl-p、字段／图文链接及全树来源；[标准 DOCX 默认页眉页脚](verification/phase-42-sections-and-docx-regions.md)含图像／表格／页码及分节继承／清空已验收；纵向拆组、递归续排及更广 Word 兼容仍开放；保存回读单列 |
| GAP-13 | P2 | 缺可视化设计器及完整多场景语料 | 先提供可运行报告、密集表单、长表及引用样例，记录完整调用成本和失败边界；设计器后续独立模块 | 部分支持：ADR 与报告探测已有 |
| GAP-14 | P1 | 发布前 API、许可和兼容检查需要持续固化 | 公共 API 隔离、隔离坐标调用方、SBOM、依赖与资源声明；正式发布前核对命名空间权限 | 部分支持：已有本地制品与隔离验证；尚未正式发布 |

## 自主实现与许可边界

- 自主编写语义模型、测量、分页和绘制组合算法，采用 Apache-2.0 声明。
- 优先复用项目现有代码和 JDK。字体解析、PDF 编码等基础设施继续使用已审查依赖，不为“自主实现”重写成熟解析器。
- 新增依赖、字体、图片或样本文档须进入[依赖记录](dependencies.md)，核对具体版本、传递依赖、原始许可和分发义务。

## 执行记录

| 批次 | 范围 | 证据 |
| --- | --- | --- |
| 第一批 | GAP-01 普通嵌套文字续页、GAP-02 合并格完整行拆分、GAP-03 命名区分 | [决策](adr/0019-grid-cell-continuation.md)、[API](grid-continuation-api.md)、[验收](verification/phase-15-continuation.md)；266 项 JUnit、5 组 16 页及隔离调用方 4 页独立检查 |
| 第二批 | GAP-05 静态表单、GAP-03 定位表格损失边界与前置条件评估 | [决策](adr/0020-static-form-components.md)、[API](static-forms-api.md)、[验收](verification/phase-16-static-forms.md)；277 项 JUnit、静态组件 7 页、调用方 1 页和迁移后 ADR 7 页独立检查 |
| 第三批 | GAP-03 语义插槽与固定视口语义表格 | [决策](adr/0021-component-semantic-insertions.md)、[API](semantic-components-api.md)、[验收](verification/phase-17-semantic-components.md)；293 项 JUnit |
| 第四批 | GAP-03 长语义表格区域流、重复表头字段与呈现身份 | [决策](adr/0022-semantic-table-region-flow.md)、[API](semantic-components-api.md)、[验收](verification/phase-18-semantic-table-flow.md)；313 项 JUnit，嵌套列／连续栏／拆行图文 11 页与隔离调用方 6 页独立检查 |
| 第五批 | GAP-04 文字默认值贯通 | [决策](adr/0023-component-style-defaults.md)、[API](style-defaults-api.md)、[验收](verification/phase-19-style-defaults.md)；329 项 JUnit，报告 3 页与调用方 1 页独立检查 |
| 第六批 | GAP-06 长合并格语义续排与合法表头呈现 | [决策](adr/0024-grid-semantic-cell-flow.md)、[API](grid-semantic-flow-api.md)、[验收](verification/phase-20-grid-semantic-flow.md)；345 项 JUnit，报告 5 页与调用方 3 页独立检查；15 组历史回归通过 |
| 第七批 | GAP-07 离线声明式 XML 及既有绑定安全加固 | [决策](adr/0025-safe-declarative-templates.md)、[API](xml-template-api.md)、[验收](verification/phase-21-xml-templates.md)；378 项 JUnit，报告 3 页与调用方 1 页独立检查，DOCX／SVG、三组历史回归与新模块坐标通过 |
| 第八批 | GAP-08 文字一致性、GAP-07 Markdown／XML 字段与链接、GAP-10 静态 PDF 处理、DOCX 损失修正 | [验收](verification/phase-22-text-and-adapters.md)；472 项 JUnit、35 页新增输出独立渲染与逐页检查、18 组历史回归、本地 SBOM／坐标及新隔离调用方通过 |
| 第九批 | GAP-04 六项显式属性覆盖与逐项恢复 | [设计](adr/0030-property-text-inheritance.md)、[样式 API](style-defaults-api.md)、[验收](verification/phase-23-property-styles.md)；490 项 JUnit、6 页新输出、22 组历史回归及新隔离调用方通过；旧完整样式和字体／固定行框保持 |
| 第十批 | GAP-09 固定列普通文字单向长表与逐页 PDF | [设计](adr/0031-single-pass-table-output.md)、[API](streaming-table-api.md)、[验收](verification/phase-24-streaming-tables.md)；552 项 JUnit、8 页新输出、23 组历史回归、新隔离调用方及 7 组独立 JVM 容量／退出清理通过 |
| 第十一批 | GAP-12 语义列合并与标准 DOCX gridSpan | [设计](adr/0032-semantic-column-spans.md)、[API](column-spans-api.md)、[验收](verification/phase-25-column-spans.md)；592 项 JUnit、8 页新输出、24 组历史回归与新隔离调用方通过；Word 保存回读按实际结果登记 |
| 第十二批 | GAP-12 语义跨行合并、二维覆盖与标准 DOCX vMerge | [设计](adr/0033-semantic-row-spans.md)、[API](row-spans-api.md)、[验收](verification/phase-26-row-spans.md)；641 项 JUnit、7 页新输出、25 组历史回归、新隔离调用方及独立 PDF/A 验证通过；全覆盖行、原子连通组、字段／图文链接及标准去元数据输入分别检查；Word 工作流收尾失败与读取成功证据分列 |
| 第十三批 | GAP-03／12 多段格、两层原子内表及标准 DOCX | [设计](adr/0034-atomic-block-cells.md)、[API](block-cells-api.md)、[验收](verification/phase-27-block-cells.md)；694 项 JUnit、6 页新输出、26 组历史回归、新隔离调用方及独立 PDF/A 通过；全树 ID／预算、层级来源、最终字段／链接和标准双网格／尾段分别验证；Word／容量未重跑 |
| 第十四批 | GAP-07 XML 可读多段格与原子内表 | [设计](adr/0035-xml-block-cells.md)、[API](xml-block-cells-api.md)、[验收](verification/phase-28-xml-block-cells.md)；711 项测试、7 页新输出、27 组历史回归、新调用方与独立 PDF/A 通过；全部分支、复制引用与标准 DOCX 分别检查 |

## 评估与推进循环

2026-10-16 最新循环：阶段三十六复用 PageSettings 补齐模板内静态纸张配置；随后[固定页区角色设计](adr/0044-page-region-font-roles.md)定位角色丢失，阶段三十七修复上下文并保持旧默认与拒绝边界。两单元均完成实现、独立检查及逐页复查。随后阶段三十八至四十自主补齐标准组件角色作用域、固定富文本、独立标记及 XML 分域默认角色／颜色，完成源码交叉复查、行为测试、隔离制品及全部新页检查。最后复查还修复原生 DOCX bullet 外部 override 绕过损失策略的问题。首版试用判断与剩余工作按[就绪评估](readiness-assessment.md)分开登记，不将这些子目标扩大为完整主题或 XML 页区。

每批先定位现状与缺口，再实现可验证行为，完成独立验收后更新状态，最后复评未关闭项。优先级不替代前置条件；因依赖调整顺序须记录源码证据及下一入口，不能把绕过缺口当作完成。

| 循环 | 评估证据 | 推进与复核 | 结果与下一项 |
| --- | --- | --- | --- |
| 2026-10-15，语义组合评估 | `ComponentLayout` 只持有绘制与普通诊断；字段处理识别顶层语义片段，外链处理需要源表格 | 没有把语义 Table 转成网格；先实现独立的静态表单，并删除 ADR 私有绘制器；测试、PDF／SVG 和全部渲染页验证 | GAP-05 关闭于明确子集；GAP-03 保持开放 |
| 2026-10-15，交付后回查 | 定位 `TableComponent` 接受单元格外链，但最终 extras 中没有外链；字段和字体角色也缺少最终上下文 | 在构造时明确拒绝相关内容，补充文字外链、图片外链及字段／角色 3 项回归；普通富文本与顶层语义表格回归通过 | 修正已关闭；GAP-03 的区域流尚未完成 |
| 2026-10-15，语义组件组合 | 普通长格、合并长格和双层嵌套需要完整保留字段、来源与字体上下文 | 自主实现有序语义插槽，传播实际字体／来源，终态解析字段、引用、图文外链；固定视口组件与隔离调用方验证 | 固定组合子集完成，GAP-03 保持部分支持；默认样式仍是已证实的易用性缺口 |
| 2026-10-15，语义区域流复评 | 固定视口已有字段语义；核心测量与最终字段解析可复用，但逐插槽登记会把续排误判为重复源 | 复用核心表格分配区域，独立验证片段序列与唯一源；逐栏表头值、拆行图文、链接和 FontScope 多短区通过行为及输出检查 | 区域流子目标完成；不扩大为长合并网格或 DOCX 嵌套支持。下一项默认样式贯通 |
| 2026-10-15，默认值与易用性复评 | 正文格式未进入组件；直接测量网格／绕排会绕过普通入口；包装器可能改变窄组件宽度 | 显式记录未设置状态，测量前解析，保留字体／语义上下文和原始宽度；16 项新行为测试、4 页样式输出及 14 组既有独立回归通过 | 文字默认值子目标完成；三处重复 style 回调改为一处 bodyFormat，完整代码 41→40 非空行，仍不构成总体易用性排名 |
| 2026-10-15，长合并格语义复评 | 固定视口无法适应每次格内可用高度；缓存网格表头会被视为重复完整源 | 复用核心一次测量游标；网格局部校验准备片段／合法缓存呈现并确定最终序号，全局来源校验保持严格；16 项新行为测试、8 页独立检查、15 组历史回归通过 | GAP-06 关闭于标准语义格组合；来源是内 Table 行列，不冒充外网格坐标。下一项 GAP-07 安全声明式模板 |
| 2026-10-16，声明式模板复评 | Java 模板已有严格节点绑定；数据大数子类、追加后计费及空循环有安全与预算缺口 | 新适配器只编译到既有模型，资源全部显式；加固数据快照、追加前预算、词法父链与工作检查点；33 项新增行为测试、4 页独立输出及三组历史回归通过 | XML 声明式子集完成；不扩大为 Markdown／HTML/CSS 或任意模板沙箱。新坐标、BOM／SBOM 与隔离调用方通过，下一项 GAP-08 文字与阅读器语料 |
| 2026-10-16，文字及适配器复评 | 缺字替换后的簇边界、双向续行及塑形上下文存在不一致；模板字段／链接和 PDF 静态处理缺公共入口；DOCX 已知嵌套容器会静默丢失 | 修复共享文字路径；严格 Markdown、XML 字段／链接及静态 PDF 适配器自主实现；94 项新增测试、35 页输出、18 组历史回归及隔离调用方通过 | GAP-07／08／10 均按实际子集保持部分支持；记录提取与选区差异，不宣称阅读器通用兼容；下一项 GAP-04 显式逐属性继承 |
| 2026-10-16，局部样式与长表复评 | 完整 style 回调无法表达只改字号；默认数值不能表示设置意图；长表 Cursor 仍保留全部模型／测量／页 | 不可变属性声明在测量前合成完整样式，保留旧回调及单项恢复；18 项新增行为测试、6 页独立输出、22 组历史回归通过；逐页源／后端四层下一入口已明确 | GAP-04 属性子目标完成，主题／可编辑字段仍开放；GAP-09 保持待实现，不把源迭代器或 TEMP_FILE 当作全链路流式 |
| 2026-10-16，单向长表与容量复评 | 完整 Table／测量／IR／Display List 均全量驻留，源包装或 TEMP_FILE 无法单独关闭缺口；PDFBox 仍保留累计对象 | 自主实现单向固定列规格、逐行测量／逐页绘制、同步后端及扁平摘要；62 项新增测试、8 页独立检查、23 组历史回归与 7 组独立 JVM 通过；修正采样脚本 PS5.1 退出码并新 ID 重跑，退出文件数／字节均 0 | GAP-09 按固定列普通文字子集改为部分支持；64 MiB 堆完成 100k 行布局，100k PDF 1,786 页／325.281 秒，不能解释为恒内存。下一项 GAP-12 columnSpan |

阶段二十五复评：columnSpan 已通过模型／核心／组件／Java／XML／DOCX 的 40 项新行为测试，8 页新输出、24 组历史验收与新隔离调用方通过。Word 读取与字段更新成功，保存／导出在有界私有实例内阻塞，不能登记保存回读通过。

阶段二十六复评：新增 49 项行为测试打通 rowSpan／vMerge，二维网格按真实锚点准备，覆盖行不复制内容；确定行高与传递连通组用于正文、连续栏及网格语义路径。7 页独立几何／双渲染／逐页检查、25 组历史验收与新隔离调用方通过。交叉复核修正稠密空行视图、DOCX 历史逆向查询和续接格未知样式的静默损失；保留原失败日志。Word 能读取标准输入并更新原生页码，探针的行数假设和 COM 收尾失败仍记录为工作流失败，私有进程已精确清理，不据此关闭保存兼容。

阶段二十七复评：两层原子 Paragraph／Table 块格已完成模型／IR、Java 深绑定、全树来源／预算、最终字段／链接和标准 DOCX 双向映射。交叉审查修复组件源预算路径／宿主索引和 DOCX 续接格隐藏语义／字段缓存计数，6 页独立检查、26 组历史回归与新调用方通过。下一优先项为 **GAP-07 XML 可读嵌套格语法**，复用现有树与原子布局，编译阶段检查全部分支，不扩大 Markdown 或递归续排。跨页拆分纵向组、完整递归续排及 GAP-09 更多语义／持续容量继续独立。依赖、验收及完整剩余范围见[执行计划](remaining-capabilities-plan.md)。GAP-08 的工具兼容限制仍开放；逻辑文字、查询和精确选区不等价。

已验收项仍保留限制；新增组合或回归时重新打开对应项。静态表单的选项整组保持和 DOCX 语义仍未实现；文字样式继承以阶段十九及二十三的明确范围为准。

阶段二十九复评：单向长表已复用纯 InlineText 与真实字体角色，21 项新行为测试、6 页独立检查、28 组历史回归和新隔离调用方通过；9 组普通／富文本独立 JVM 含 64 MiB 布局控制。审查修正容量例的手算运行数，并从新 ID 重采全部富文本组，退出清理与字体摘要通过。GAP-09 保持部分支持；下一单元为 GAP-04 编写时主题快照与具名完整样式，字体角色级联／可编辑字段／XML 名称另行设计。

阶段三十复评：Java DocumentTheme 集中完整正文／六级标题／便利语义表格与命名完整样式，加入时解析并保留显式值、六项组件继承和格格式意图。21 项新增行为及总计 753 项测试通过，9 页最终 PDF／SVG／DOCX 独立检查与逐页复查完成，新调用方通过。首轮 DOCX 断言已精确定位为普通段落默认运行展开，规范化表示后仍完整比较。下一优先项为[XML 主题设计](adr/0038-xml-theme-styles.md)，保持 XML body／cell 配置独立；GAP-04 不因 Java 子集而关闭全部样式目标。

阶段三十一复评：XML 主题保留 body／cell 独立配置和既有 size 行高规则，p／span 静态名称在全部分支编译检查。27 项新模板测试及全仓 780 项测试通过，4 页 PDF／SVG／DOCX 独立检查、逐页目视、新调用方及 12 组检查通过。实际补齐未知字体角色的 UTF-16 叶位置，深层来源保持；DOCX 单默认运行只按精确等价表示归一。下一单元为四类可编辑表单显式样式，仍不视为完整品牌主题。OCR 排除。

阶段三十二复评：[可编辑 FormStyle](verification/phase-32-editable-form-styles.md)已完成四类字段显式外观、旧默认和保存回读；807 项测试、8 页双渲染及人工目视、5 组独立检查和新调用方通过。独立检查发现并修复 PDFBox 零框仍描边的问题；真实 Unicode 同字形提取别名及固定字号裁剪边界保留。页区／目录主题仍开放，下一单元为显式 String 的平面离线 XML include。

阶段三十三复评：[平面离线 XML 片段](verification/phase-33-offline-xml-includes.md)已完成全分支编译、局部名称空间、循环副本引用、连续编号、来源与双重预算；853 项测试、4 页双渲染／根目视、12 组独立检查及新调用方通过。原生 DOCX 编号验证改为逐项 num／startOverride，9 项变异拒绝保留。递归 include 等仍开放，下一单元为目录三类文字独立样式与颜色。

阶段三十四复评：[目录独立样式](verification/phase-34-contents-styles.md)完成标题／页码完整排版、三色及设置意图；20 项新增与全仓 873 项测试、4 页双渲染／逐页复查、新调用方及 10 组独立检查通过。预留较大页码行高后统一最终偏移，不以虚构字形探测；固定 48 pt 溢出明确失败。页区默认与完整品牌主题仍开放，下一单元为[XML 编号设计](adr/0042-xml-multilevel-numbering.md)。

阶段三十五复评：[XML 多级十进制编号](verification/phase-35-xml-numbering.md)复用现有NumberedItem／SemanticBlocks／DOCX，只扩静态level到1..9。26项新增与全仓899项测试、4页双渲染／逐页复查、12组独立检查及新调用方通过；九级int边界另核标准OOXML，9项变异全部拒绝。实际父级／溢出／固定字段失败阶段准确保留；编号内link等白名单不扩大。GAP-07保持部分支持；页区默认、完整主题、递归续排、持续容量及HTML/CSS／设计器仍待独立推进，OCR排除。

HTML／Markdown 导出已由[阶段四十三](verification/phase-43-web-exports.md)完成独立语义适配器、逐功能报错／警告／忽略策略及内容验收，详见[支持矩阵](web-export-api.md)。这不关闭 GAP-07 的 HTML/CSS 输入或 GAP-13 的设计器，也不扩大绘制组件的语义反向转换范围。
