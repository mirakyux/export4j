# ADR-0022：语义表格区域续排与呈现身份

- 状态：采纳。
- 日期：2026-10-15。
- 关联：[ADR-0021](0021-component-semantic-insertions.md)、[语义组合 API](../semantic-components-api.md)、[缺口表](../capability-gaps.md)。

## 问题

固定视口 SemanticTable 已保留字段、字体策略和来源，但长表不能参与区域流。直接重复插槽又会把同一表的续排片段判成重复文档 ID。简单去重会掩盖完整表重复、缺片和乱序，不能作为分页实现。

## 决策

核心提供 `DocumentLayouter.layoutTableRegions(Table, ComponentContext, FlowRegions)`，返回逻辑区域索引和本地 TableFragment。复用顶层表格的列宽、塑形、行测量和完整行拆分；不复制表格算法，不提前绘制字段。区域专用 Flow 维护独立逻辑索引，仅为占用区域记录输出。过短初始区可以跳过，完整续区须推进。表格外部间距由父容器管理。

SemanticTable 实现 RegionFlow，同一 `.semanticTable(table)` 在正文列及连续分栏中自然续排。固定 `layout()` 仍限制一个占用片段。FontScope 保留完整区域序列，不能只传第一个短区后把其余区域当成原子完整页。

SemanticPlacement 增加 `fragmentIndex` 和 `fragmentCount`。同一测量流的片段共享原始不可变 Table 实例、宿主块、字体组和角色配置，索引必须从 0 到 count-1 连续出现。SemanticSourceRegistry 在本节及合并后的全文档分别检查流，拒绝重复、缺失、乱序、改变来源／字体和跨宿主复用。旧五参数构造表示 0／1 的完整单片段；复制插槽时须保留序号及总数。

源码结构、单元格、源文字和图片按唯一源计一次。实际插槽、重复表头、输出行、字段生成、绘制文字和链接分别按呈现计数。书签与引用锚点指向首个实际片段。表头 PAGE 按每个片段的最终物理页解析，同页多栏使用相同显示编号，不以逻辑区号代替页码。

## 边界

阶段二十通过 [ADR-0024](0024-grid-semantic-cell-flow.md) 扩展网格局部准备流与缓存语义表头重复。以下描述保留本阶段交付时的边界，当前调用以相应 API 为准。

语义表自身的表头可以重复；GridTable 表头复制一张完整 SemanticTable 仍是重复来源，明确拒绝。旋转、缩放、部分裁切、页区语义装饰及 DOCX 嵌套不在本批范围。长语义表不能据此成为长合并网格单元格；该组合仍需独立测量流与验收。

单表默认准备限额先执行，之后再验证调用方共享限额。输出 IR 仍驻留内存，未实现有界流式数据源。自定义组件是可信同步代码，序号验证用于发现组合错误，不代表可以运行任意不可信组件。

## 兼容与许可

没有新增依赖或外部素材，算法自主实现。SemanticPlacement 保留旧构造调用的源码兼容；项目未发布，不承诺 record 增加分量的二进制兼容。既有静态 TableComponent、普通顶层表格及默认整行策略保持。GAP-03 的区域流子目标按[阶段十八](../verification/phase-18-semantic-table-flow.md)验收，DOCX 嵌套和长合并格字段仍保留独立缺口。
