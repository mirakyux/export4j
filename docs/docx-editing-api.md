# 保留源包的 DOCX 文字编辑

`DocxAdapter.editPackage` 直接编辑 DOCX 包中的普通文字，保留语义模型不能表达的未编辑结构。它与[语义转换](document-conversion-api.md)是不同通道。

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var policy = ProcessingPolicy.builder()
        .feature("OOXML_TEXT_STRUCTURE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.PRESERVE)
        .build();
var prepared = new DocxAdapter().editPackage(Files.readAllBytes(Path.of("source.docx")))
        .part("word/header1.xml")
        .policy(policy)
        .replaceText("Old title", "New title")
        .prepare();
var preview = prepared.preview();
var result = prepared.to(Path.of("edited.docx"));
assert preview.issues().equals(result.report().issues());
```

默认只选 `word/document.xml`，可显式添加现有 headerN／footerN 条目。字面、区分大小写、按请求顺序替换；不跨段，不拆代理对。位置是该操作执行时段落视图的 UTF-16。不同运行的同样式普通文字可跨运行替换，保留首运行之外的未命中文字。

## 策略与保留

默认 ERROR；共享覆盖顺序为操作、功能、格式、默认值。操作键为 `REPLACE_TEXT`，格式键 DOCX。

| 功能 | 支持的明确处理 |
| --- | --- |
| `MIXED_RUN_STYLE` | DEGRADE 使用首运行的样式／链接；或 SKIP_MATCH、SKIP_BLOCK、SKIP_REQUEST、PRESERVE |
| `OOXML_TEXT_STRUCTURE` | 复杂边界不修改；支持上述跳过或 PRESERVE，不能 DEGRADE |
| `GRAPHEME_BOUNDARY` | 不拆字素；明确跳过或保留，不能强制修改 |

WARN 与 IGNORE 的处理一致，IGNORE 只隐藏警告，仍返回实际 issues；PRESERVE 表示整段保持原内容。SKIP_REQUEST 回滚整个请求。字段、跨段目录缓存、评论边界、修订、内容控件和文本框内文字不作为普通文字编辑。目录、评论、修订、表单、页区等未编辑结构保留，不更新目录／字段缓存、不接受或拒绝修订。

未改条目的解压字节完全一致，整个请求没有实际改动时 ZIP 字节也一致。修改条目保持 DOM 内容与结构，仅选中文字及 `xml:space` 改变；XML 序列化与 ZIP 压缩、时间戳等可以不同。

## 安全、预算和输出

输入立即复制，整个包安全解析并验证关系目标、重复及缺失引用。宏、ActiveX、OLE／嵌入包、外部资源、危险字段指令、签名、保护文档无条件拒绝。仅允许不会主动加载资源的合法 HTTP／mailto 超链接；不联网。

`DocxLimits` 控制 ZIP 输入／条目／展开量，`EditLimits` 控制节点、累计文字、命中、工作和诊断；默认输出上限 64 MiB。准备保存确切输出，发布不重做替换。路径要求已有父目录和原子移动支持；最终取消检查先于替换，失败保护旧文件并清理临时文件。借用流不关闭、不刷新，物理写失败可能留下部分字节。

完整示例见 [DocxPackageEditExample](../examples/src/main/java/com/mirakyux/export4j/examples/DocxPackageEditExample.java)，证据见[阶段四十八](verification/phase-48-docx-editing.md)。
