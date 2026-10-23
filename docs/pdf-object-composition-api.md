# 保留被动对象的 PDF 选页与合并

`PdfAdapter.composeObjects()` 与旧静态 `copy`／`merge` 独立。输入须符合[被动对象子集](pdf-object-editing-api.md)，每个来源显式指定不同的 ASCII 命名空间。字段、widget、页内链接、层级书签、内嵌附件及页面资源进入同一目标。

```java
var policy = ProcessingPolicy.builder()
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var prepared = adapter.composeObjects()
        .policy(policy)
        .add(first.allPages(), "report")
        .add(second.pages(2, 0, 1), "appendix")
        .prepare();
prepared.to(Path.of("combined.pdf"));
```

页面为零基物理索引。一个输入快照只能添加一次，选择内允许重排，不允许重复页。命名空间为 1～40 字符 ASCII 标识符，以字母开头，后续仅字母、数字及下划线；根字段名和附件逻辑名变为 `namespace_original`。子字段层级保持。不存在自动重名猜测或第三方类型泄漏。

## 真实处理与损失

| 功能键 | 实际处理 | 必需 treatment |
| --- | --- | --- |
| LINK_TARGET | 删除指向未选择页面的链接批注 | DISCARD |
| BOOKMARK_TARGET | 保留标题／子树，删除指向未选择页面的目标，使其成为分组 | DEGRADE |
| FIELD_WIDGET_SELECTION | 删除未选择页面的 widget 和无剩余 widget 的字段／分支 | DISCARD |
| FIELD_SELECTION_VALUE | 部分按钮组的当前值或默认值已无可选 widget 时重置为 Off | DEGRADE |
| DOCUMENT_METADATA | 保留第一个来源的 Info、XMP 和文档设置，删除后续来源对应内容 | DISCARD |
| ENCRYPTION_REMOVED | 经显式 owner 授权产生不加密派生文件 | DEGRADE |

默认 ERROR；WARN／IGNORE 执行相同处理，IGNORE 仍返回损失。操作键为 COMPOSE，遵循 operation → feature → format → default。PRESERVE 不允许制造指向缺失页的目标；SKIP_MATCH 不允许留下悬空关系。已排除页面上的普通内容随选页删除，不作为内容安全脱敏承诺。

全输入预检在复制前完成。字段外观及值保持；选页后的按钮状态另行核对。Info／XMP／设置没有静默冲突合并。输出再次按相同被动对象契约检查，prepare 返回与实际写出相同的候选／损失。PdfLimits 的输入、页、对象、资源、解码及输出额度累计生效，PreparedPdf 的原子路径／借用流契约保持。

样例包含 `pdfObjectEditing` 的 `composed.pdf` 和 `selected.pdf`，可分别运行 `scripts/verify_pdf_object_composition.py` 和对象编辑验证器检查。
