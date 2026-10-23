# PDF 报告首版就绪评估

初始评估日期：2026-10-16；能力补充更新：2026-10-23。判断依据为当前源码、[能力缺口表](capability-gaps.md)和各阶段验收。下文阶段三十八至四十的数据保留为该批历史证据。OCR 不在当前评估范围内。

## 当前结论

**当前可以进入受支持 PDF 报告和静态密集表单的真实业务试用。** 普通编写、组件组合、XML 结构、默认样式、最终字段与隔离制品已有连贯路径。先用真实结构和数据检验易用性、异常诊断和容量，比继续无边界追求商业产品全部功能更能检验这个首版。

这是一项有明确范围的工程判断。项目未正式发布，API 未冻结；不承诺任意 PDF 编辑、一般 Word 无损互转、商业产品全功能等价或生产 SLA。下列开放项没有被标成已完成。

## 场景与入口

| 需求 | 建议入口 | 当前范围及证据 |
| --- | --- | --- |
| 普通图文报告、标题、列表、分页表格 | Document／Documents 链式编写与 DocumentEngine | 自动 ID、正文默认值、主题／命名样式、富文本／真实字体及显式标记样式；[编写指南](authoring-api.md)、[链式入口](fluent-api.md)、[列表标记](list-markers-api.md) |
| 精确位置、固定页区、密集静态表单 | GridTable、静态表单组件及 PageTemplate | 合并格、长格续排、矢量选择标记、标签值与填写线；角色作用域和固定富文本无需私有绘制器；[静态表单](static-forms-api.md)、[组件文字](component-text-api.md) |
| 可编辑 PDF 字段 | 后端中立交互模型与 FormStyle | 四类基础字段、真实字体／当前值／外观检查；固定字号不保证长值完整放入，人工阅读器结果单列；[字段样式](form-styles-api.md) |
| 业务模板复用 | XmlTemplate 编译到既有 DocumentTemplate | 变量、条件、循环、动态行、静态纸张、主题、平面 include、字段、编号和分域外观；Java 管数据和显式资源；[XML 指南](xml-template-api.md)、[文字默认值](xml-text-defaults-api.md) |
| 大量固定列文字记录 | StreamingTable／TableRowSource | 普通／富文本／图片及被动链接、横向合并、完整行续页、同步交付与资源生命周期；PDFBox 页树仍增长；[长表](streaming-table-api.md) |
| Word／SVG／PNG 交付 | 各独立适配器或静态后端 | DOCX 为语义子集，SVG／Raster 明确拒绝交互；[支持矩阵](capabilities-api.md) |

普通报告优先从语义入口开始，需要精确固定布局时才进入组件模型。语义 Table 的字段／来源和 DOCX 能力不能由定位 GridTable 名称替代；固定富文本也不能冒充自动分页文本。选择入口的这部分复杂度仍存在，但调用方已经能够用链式结构或 XML 看出段落、表格与父子关系，无需普遍手写底层绘制指令。

## 本轮补齐与验证

[阶段三十八](verification/phase-38-component-text.md)新增 FontRoleScope／RichTextComponent，保持真实字体组、缺字策略和完整运行样式。阶段三十九新增独立 markerStyle、Builder／快照／恢复及标准 DOCX 编号运行属性；阶段四十新增 XML TextDefaults，明确正文／标题／格范围和局部恢复，不以默认数值猜设置意图。未引入新依赖或第三方实现代码。

验证覆盖全仓构建、行为测试与独立输出检查。累计新增 **81 项测试**，最终全仓 **1,029 项通过，失败／错误／跳过均为 0**。完整编译、Javadoc、SBOM、本地 bundle 和全新隔离 Maven 调用方通过；18 组历史独立检查及列表／XML 两组新检查通过，PDF/A 样例另由 veraPDF 验证。没有刷新视觉基线。

本轮新增 **11 页**：阶段三十八 3 页，列表原文／DOCX 导入／调用方 5 页，XML 默认值／调用方 3 页。均经 Poppler／PDFium 双渲染，逐张打开完整页检查。独立检查涵盖真实字体、字号／颜色、标记与正文推进／装饰、最终字段值、链接框和 XYZ、原生 DOCX level 属性。有限 Latin 样例只证明这些固定输出，不能推广为一般复杂塑形或 Word 阅读器兼容。

交叉复核修正了检查脚本中的 SVG 数字格式／字段轮廓表示假设，并补齐链接坐标断言；受控变异证实错误链接框及目标坐标会被拒绝。最后还修复原生 DOCX bullet 的外部 override 提前返回漏洞，严格／允许损失两种策略已有回归。失败记录、最终命令和调用方路径分别保留在[阶段三十九](verification/phase-39-list-markers.md)及[阶段四十](verification/phase-40-xml-text-defaults.md)。

## 后续仍开放

后续[阶段四十一](verification/phase-41-xml-page-regions.md)新增 XML 固定页眉页脚、独立主题和最终页码，1,041 项测试、5 页双渲染／逐页检查、新隔离调用方及 14 组历史回归通过。下表继续保留超出该子集的边界。

| 能力 | 保留边界与后续依据 |
| --- | --- |
| 完整主题和更广 XML 页区 | [固定页眉页脚及独立主题](xml-page-regions-api.md)已支持；[图片／表格、XML 分节及 DOCX 默认页区](verification/phase-42-sections-and-docx-regions.md)已验收；全局／分域外观及 XML 背景／前景已由阶段六十至六十二验收 |
| 更复杂表格和连续富文本组件 | 16 层语义内表、纵向 owner 续排和连续富文本已有；自动列合并及单向图文／链接／完整行续页已验收；表格跨变宽区、递归组件网格及未来流式语义独立 |
| 字体／阅读器／Word 兼容 | 六脚本语料、[有限 DOCX 样式级联](docx-style-import-api.md)及[原包文字编辑](docx-editing-api.md)已有；一般 Word 无损语义往返、保存回读及跨阅读器提取／选区仍有已知限制 |
| 长期容量和更多平台 | [同进程并发失败恢复](verification/phase-69-streaming-stability.md)已完成有限运行；不证明长期无泄漏、任意并发或硬 RSS 上限 |
| PDF 工具与标准 | [页／对象编辑、组合、渲染提取、优化、文字替换、光栅脱敏及语义恢复](pdf-adapter-api.md)的明确子集已验收；任意 PDF 无损编辑、PDF/UA、完整 PAdES/LTV 仍开放 |
| 可视化设计器／完整浏览器 CSS／条码图表 | [离线 HTML/CSS 子集](html-import-api.md)已有；完整浏览器兼容和其余列举能力尚非现成功能 |

后续优先处理真实试用暴露的阻断和高频编写成本，再扩大独立能力。触发新组合、契约变化或缺陷时重开对应验收；持续保持原子输出、显式字体、损失诊断和开源许可边界。

图文页区／分节补充：见[阶段四十二](verification/phase-42-sections-and-docx-regions.md)。XML、模型页区、PDF 与 DOCX 默认页眉页脚已形成受支持语义闭环；原生 Word 打开及字段更新与保存／PDF 导出各自记录，不由前者推断后者。

内容分发补充：[HTML／Markdown 导出](web-export-api.md)支持明确损失策略，阅读器自行重排；data 图片／生成标签显示取决于阅读器。后续[通用 Markdown](markdown-import-api.md)和[离线 HTML/CSS](html-import-api.md)子集、[统一导入编辑转换](document-conversion-api.md)及 [PDF 语义恢复](pdf-semantic-import-api.md)已完成专项验收，仍不等于全部布局兼容。编辑／导入／转换批次的完整证据见[执行计划](remaining-capabilities-plan.md)。
