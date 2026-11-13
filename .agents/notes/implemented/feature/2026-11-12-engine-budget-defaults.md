# 工程笔记：引擎级预算默认值与请求覆盖

状态：已实现

## 先说结论

在 `DocumentEngine` 提供显式预算配置，让复用引擎携带不可变默认值；保留有限预算和既有计数语义。单次请求可以覆盖默认值，不能反向修改引擎或其他请求。

## 问题

普通文档也需要按业务容量设置布局和组件预算。只有底层布局或个别导出请求能配置时，调用方必须绕过常用入口或在每次请求重复设置，容易出现不同导出路径的限制不一致。

表格源行预算包含表头，因此“10,000 行正文加一行表头”需要允许至少 10,001 源行；不能把触发既有保护直接解释为只能支持 10,000 行正文，也不能只改报错或绕过预算。

## 决策

[DocumentEngine](../../../../export4j-components/src/main/java/com/mirakyux/export4j/components/DocumentEngine.java) 持有不可变的 [DocumentLayoutLimits](../../../../export4j-layout-core/src/main/java/com/mirakyux/export4j/layout/core/DocumentLayoutLimits.java) 与 [ComponentLimits](../../../../export4j-layout-core/src/main/java/com/mirakyux/export4j/layout/core/ComponentLimits.java)。`toBuilder` 和预算回调从当前值开始，回调结束立即构建快照；修改单项不重置之前的配置，调用方保留的 Builder 也不能再改变已构建引擎。

普通文档、分页请求、普通块流、重放与目标转换继承引擎默认值。请求显式指定的预算仅覆盖该请求。长表专用流式入口使用其独立预算，不把普通布局限制强行映射为长表容量。

公共配置方法与计数边界以[组件 API](../../../../docs/components-api.md)和[流畅编写 API](../../../../docs/fluent-api.md)为准，笔记只记录默认值归属和隔离取舍。

## 考虑过的备选方案

- 提高全局默认上限或取消保护：无法表达不同业务的资源约束，也改变既有调用方行为。
- 要求每个请求重复配置：增加遗漏风险，普通编写与转换仍缺少统一默认值。
- 将可变 Builder 保存在引擎中：后续回调或外部修改可能污染复用引擎，不能保持配置快照。

## 后果

调用方可以复用一个已配置引擎，并为个别导出设置不同上限。预算仍是实际工作的保护边界；增大源行上限不代表其他工作量、呈现量或输出资源限制同时扩大，也不代表已经验证任意规模的容量。

## 验证

- [FluentAuthoringTest](../../../../export4j-components/src/test/java/com/mirakyux/export4j/components/FluentAuthoringTest.java) 的 `engineBudgetCallbacksPreservePriorSettingsAndSnapshotBuildersAndReusableEngines` 验证单项保留、Builder 快照及引擎复用。
- [PagedExportBoundaryTest](../../../../export4j-components/src/test/java/com/mirakyux/export4j/components/PagedExportBoundaryTest.java) 的 `everyPagedFactoryInheritsEngineBudgetsAndRequestOverridesStayLocal` 验证工厂继承及局部覆盖。
- [StreamingDocumentExportTest](../../../../export4j-components/src/test/java/com/mirakyux/export4j/components/StreamingDocumentExportTest.java) 的 `blockStreamsInheritEngineBudgetsAndExplicitOverridesDoNotChangeTheEngine` 验证块流继承及独立请求。
- [DocumentConversionTest](../../../../export4j-components/src/test/java/com/mirakyux/export4j/components/DocumentConversionTest.java) 的 `conversionTargetRetainsEngineStructuralAndInteractionBudgets` 验证转换目标保留两类预算。

## 适用范围

适用于 `DocumentEngine` 的默认预算、派生引擎及请求配置传播。各后端的资源预算和专用长表流预算仍由各自契约管理；本决定不改变表头、源内容与呈现内容的计数含义。
