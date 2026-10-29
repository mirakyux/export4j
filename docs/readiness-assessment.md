# PDF 报告首版就绪评估

初始评估日期：2026-10-16；能力补充更新：2026-10-29。判断依据为当前源码、[能力缺口表](capability-gaps.md)和各阶段验收。下文阶段三十八至四十的数据保留为早期试用基线；后续能力按对应 API 和专项证据判断。OCR 不在当前评估范围内。

## 当前结论

**当前可以进入受支持 PDF 报告和静态密集表单的真实业务试用。** 普通编写、组件组合、XML 结构、默认样式、最终字段与隔离制品已有连贯路径；递归布局、语义长表、DOCX 编辑、PDF 标准及 HTML/CSS 布局已补充专项验收。接入时仍应以实际数据验证字体、损失策略、容量和目标应用行为。

项目未正式发布，API 未冻结。该评估不承诺任意 PDF 编辑、一般 Word 无损互转或生产 SLA；当前剩余工作见下文及缺口表。

## 场景与入口

| 需求 | 建议入口 | 当前范围及证据 |
| --- | --- | --- |
| 普通图文报告、标题、列表、分页表格 | Document／Documents 链式编写与 DocumentEngine | 自动 ID、正文默认值、主题／命名样式、富文本／真实字体及显式标记样式；[编写指南](authoring-api.md)、[链式入口](fluent-api.md)、[列表标记](list-markers-api.md) |
| 精确位置、固定页区、密集静态表单 | GridTable、静态表单组件及 PageTemplate | 合并格、长格续排、矢量选择标记、标签值与填写线；角色作用域和固定富文本无需私有绘制器；[静态表单](static-forms-api.md)、[组件文字](component-text-api.md) |
| 可编辑 PDF 字段 | 后端中立交互模型与 FormStyle | 四类基础字段、真实字体／当前值／外观检查；固定字号不保证长值完整放入，人工阅读器结果单列；[字段样式](form-styles-api.md) |
| 业务模板复用 | XmlTemplate 编译到既有 DocumentTemplate | 变量、条件、循环、动态行、主题、有界递归 include、页区／分节、字段、编号及 XML／资源保存；[XML 指南](xml-template-api.md)、[模板资源包](xml-template-bundle-api.md) |
| 长表与大批记录 | StreamingTable／TableRowSource、显式重放及 PdfSpoolBackend | 递归语义格、合并／拆行、总数及未来引用；普通 PDF 页树与磁盘索引后端分开选择；[长表](streaming-table-api.md)、[语义流与重放](streaming-replay-api.md) |
| Word／SVG／PNG 交付 | DOCX 适配器及静态后端 | DOCX 原生语义编辑、Word 保存回读及四页原生／PDF 视觉已验收；SVG／Raster 明确拒绝交互，见[DOCX 指南](docx-semantic-api.md)和[支持矩阵](capabilities-api.md) |
| 已有文件导入与互转 | 统一转换、PDF／DOCX／Web 适配器 | 对明确子集执行导入、不可变编辑、损失预检及导出；[转换指南](document-conversion-api.md) |
| PDF 编辑与标准处理 | PDF 专用处理入口及标准输出通道 | 页／对象／内容编辑、两种脱敏、恢复、PDF/A、PDF/UA-1 及离线 PAdES；[PDF 指南](pdf-adapter-api.md)、[标准指南](pdf-standards-api.md) |

普通报告优先从语义入口开始，需要精确固定布局时才进入组件模型。语义 Table 的字段／来源和 DOCX 能力不能由定位 GridTable 名称替代；固定富文本也不能冒充自动分页文本。选择入口的这部分复杂度仍存在，但调用方已经能够用链式结构或 XML 看出段落、表格与父子关系，无需普遍手写底层绘制指令。

## 早期试用基线（阶段三十八至四十）

[阶段三十八](verification/phase-38-component-text.md)新增 FontRoleScope／RichTextComponent，保持真实字体组、缺字策略和完整运行样式。阶段三十九新增独立 markerStyle、Builder／快照／恢复及标准 DOCX 编号运行属性；阶段四十新增 XML TextDefaults，明确正文／标题／格范围和局部恢复，不以默认数值猜设置意图。未引入新依赖或第三方实现代码。

验证覆盖全仓构建、行为测试与独立输出检查。累计新增 **81 项测试**，最终全仓 **1,029 项通过，失败／错误／跳过均为 0**。完整编译、Javadoc、SBOM、本地 bundle 和全新隔离 Maven 调用方通过；18 组历史独立检查及列表／XML 两组新检查通过，PDF/A 样例另由 veraPDF 验证。没有刷新视觉基线。

本轮新增 **11 页**：阶段三十八 3 页，列表原文／DOCX 导入／调用方 5 页，XML 默认值／调用方 3 页。均经 Poppler／PDFium 双渲染，逐张打开完整页检查。独立检查涵盖真实字体、字号／颜色、标记与正文推进／装饰、最终字段值、链接框和 XYZ、原生 DOCX level 属性。有限 Latin 样例只证明这些固定输出，不能推广为一般复杂塑形或 Word 阅读器兼容。

交叉复核修正了检查脚本中的 SVG 数字格式／字段轮廓表示假设，并补齐链接坐标断言；受控变异证实错误链接框及目标坐标会被拒绝。最后还修复原生 DOCX bullet 的外部 override 提前返回漏洞，严格／允许损失两种策略已有回归。失败记录、最终命令和调用方路径分别保留在[阶段三十九](verification/phase-39-list-markers.md)及[阶段四十](verification/phase-40-xml-text-defaults.md)。

## 当前限制与后续计划

后续[阶段四十一](verification/phase-41-xml-page-regions.md)新增 XML 固定页眉页脚、独立主题和最终页码，1,041 项测试、5 页双渲染／逐页检查、新隔离调用方及 14 组历史回归通过。下表继续保留超出该子集的边界。

| 能力 | 当前状态与后续依据 |
| --- | --- |
| 表格、文字、流式与图形 | GAP-03／08／09／21 的既定剩余目标均已由[阶段九十九](verification/phase-99-layout-fonts-streaming-graphics-closure.md)验收；适用于所列输入及有限容量，不提供任意规模或无限期无泄漏保证 |
| DOCX 编辑与应用验收 | GAP-12.1／12.2 已由[阶段一百零三](verification/phase-103-native-docx-semantic-editing.md)闭合；一般外部 Word 文件的无损往返及同分页不由固定样例推断 |
| PDF 工具与标准 | GAP-10／11／20 的明确子项均已验收，包含 PDF/UA-1 和离线 PAdES B／T／LT／LTA；其他标准版本、任意结构或未知活动内容仍按各指南的边界处理 |
| HTML/CSS | GAP-18.1 的明确文档布局子集已由[阶段一百零四](verification/phase-104-html-css-layout.md)验收；完整浏览器兼容不在该子集内 |
| 场景示例与设计器 | GAP-13 保持部分支持；完整多场景调用成本、失败边界及独立可视化设计器仍需推进 |
| 兼容性与正式分发 | GAP-14.2／14.3 保持部分支持。提交 cc132e7 的三平台构建和临时密钥签名远端 CI 已通过；更广语料／阅读器、正式 API 冻结、发布身份及远端分发仍待验收，见[阶段一百零五](verification/phase-105-publication-and-compatibility.md) |
| 新增文档格式 | GAP-22 尚未实现；候选方向为 XLSX／PPTX 输出、EPUB／XPS 双向及 PS／PCL 输入，各方向须独立定义目标和验收 |

后续优先处理真实试用暴露的阻断和高频编写成本，再扩大独立能力。触发新组合、契约变化或缺陷时重开对应验收；持续保持原子输出、显式字体、损失诊断和开源许可边界。

Word 编辑、保存回读及 PDF 导出的实际应用动作与独立渲染分开记录；HTML／Markdown 的 data 图片、生成标签和换行分页也受目标阅读器影响。跨格式损失及未支持组合应在预检与实际报告中检查，不能只凭生成成功判断适合交付。
