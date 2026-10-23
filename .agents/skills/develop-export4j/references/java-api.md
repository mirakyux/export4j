# Java 与 API 规约

- 块格不得使用旧单段视图：模型先判 `TableCell.hasBlocks()`，IR 先判 `CellFragment.hasBlocks()`；子片段坐标相对格 textOrigin，统一累积平移。格段仅 Paragraph／Table，根表算第一层、最多 16 层；行内内表支持父格实际宽度下的有界 Auto，含块格父表仍拒绝 Auto，显式 allowRowSplit 支持递归续排，IR 保留原始格块序号，重复父表头拒绝块格。全树 ID／预算用 SemanticTree，真实锚点只访问一次，组件源错误必须保留宿主根索引及 CellLocation 路径。DOCX 尾段／书签 owner 只按验证过的自有标记归一，无元数据真实空 p 保留。

- 公共编写／配置入口优先使用具名 Builder 和同步嵌套回调，保留已有短构造器。回调返回后不得保留可变子 Builder；集合与默认值按边界取快照。不为 IR、绘制指令、异常或 SPI 机械添加 Builder。组件便利入口留在组件模块，见[链式 API](../../../../docs/fluent-api.md)及 [ADR-0018](../../../../docs/adr/0018-fluent-authoring.md)。

- 使用 Java 21 正式特性，默认不启用预览特性。使用 4 个空格缩进、UTF-8 和 LF。
- 类型使用 `UpperCamelCase`；方法、变量使用 `lowerCamelCase`；常量使用 `UPPER_SNAKE_CASE`；包名使用小写。
- 显式导入，禁止通配符导入。控制可见性，默认使用最小访问范围。
- 优先不可变值对象。`record` 中的集合与可变资源同样需要防御性处理。
- 不为数据模型默认引入 Lombok；不为核心引入 Spring、日志实现或其他应用框架。
- 不使用原始泛型、未经解释的 unchecked cast、无依据的 `@SuppressWarnings` 或未使用代码。
- 公共 API 通过 Javadoc 说明单位、坐标、空值、线程安全、异常和资源所有权。
- 必填参数在入口验证。集合返回空集合；可缺省返回值使用 `Optional`，不将它用于字段或参数。
- 不吞异常，不以 `null`、空文件或成功状态掩盖失败。包装异常保留原因及上下文。
- 不向标准输出写库内部日志，不在诊断中默认输出文档正文、凭证或资源完整路径。
