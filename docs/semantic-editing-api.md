# 语义编辑与损失预检

`export4j-model` 的 `com.mirakyux.export4j.model.edit.DocumentEditor` 编辑语义模型，再交给已有适配器或引擎导出。它不修改已有 PDF 内容流，也不自动保留源 DOCX 中尚未进入模型的结构。

## 调用

```java
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;

Document source = Document.builder()
        .paragraph("intro", "Order: OLD")
        .paragraph("tail", "Keep this paragraph")
        .build();

var policy = ProcessingPolicy.builder()
        .feature("MIXED_RUN_STYLE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = DocumentEditor.edit(source).policy(policy)
        .replaceText("intro", "OLD", "READY")
        .insertBefore("tail", new Paragraph("added", "Approved", ParagraphFormat.of(12)))
        .prepare();
var predicted = prepared.preview();
var result = prepared.execute();
Document edited = result.document();
var actualLosses = result.report().losses();
var warnings = result.report().warnings();
var intentionalChanges = result.changes();
var matches = DocumentEditor.edit(edited).find("READY").matches();
```

`prepare()` 和 `execute()` 声明 `IOException`，与同步执行上下文一致；本入口不读写文件。预检绑定输入、操作、策略和候选快照。后续修改 Request 不影响已准备候选；取消在预检和发布时分别检查。

## 已支持操作

| 方法 | 行为 |
| --- | --- |
| `append` | 向唯一节或指定节正文末尾增加块，支持空文档 |
| `insertBefore`／`insertAfter` | 在稳定 ID 对应块前后插入；可进入块格，继续遵守格内类型与深度限制 |
| `remove`／`replaceBlock` | 删除完整子树或以相同 ID 替换；最终引用不能悬空 |
| `moveBefore` | 移动完整子树，支持跨节；不能移入自身或使块格为空 |
| `cell(tableId,row,column,value)` | 用零基物理锚点替换格；跨度及完整覆盖重新验证，覆盖槽别名拒绝 |
| `paragraphFormat` | 修改段落／标题／列表／题注格式，保留图文对象及独立列表标记样式 |
| `inlineStyle` | 修改半开 UTF-16 范围的完整文字样式，保留原链接；不消费图片或字段，题注没有富文本通道 |
| `image` | 用当前 UTF-16 标记位置替换图片资源／尺寸／替代文字／链接 |
| `replaceText` | 全文或指定块字面替换；指定 Table ID 包含其直接内联格，块格后代使用自身 ID |
| `find` | 在全部已排队操作形成的候选中查找；返回位置及对象／样式／字素边界标志，不回显正文 |

Document、PageDocument 和 SectionedDocument 保持各自返回类型。表格只访问真实锚点；共享值相等的同类页区只编辑一次并继续共享。块格中最后一个块被删除时失败，调用方可以先插入替代块。现有源对象不被修改。

## 策略与报告

策略键区分大小写，模型格式键为 `MODEL`。优先级固定为 **operation → feature → format → default**；Builder 构建时快照，默认 `ERROR`。可用功能为 `MIXED_RUN_STYLE`、`INLINE_OBJECT_BOUNDARY`、`GRAPHEME_BOUNDARY`、`OPAQUE_BLOCK`、`NON_TEXT_TARGET` 和 `TARGET_NOT_FOUND`。操作键见 `Change.operation()`，例如 `REPLACE_TEXT`、`INLINE_STYLE`、`FIND_TEXT`。

| 处理 | 适用行为 |
| --- | --- |
| `SKIP_MATCH` | 保留这一匹配／样式范围，继续后续匹配；也可跳过缺失目标操作 |
| `SKIP_BLOCK` | 撤销本操作在拥有块中的修改；内联格对应整个拥有 Table，块格内段落对应自身 |
| `SKIP_REQUEST` | 撤销整次编辑请求，返回原始模型及明确的跳过报告 |
| `DEGRADE` | 仅用于跨不同样式／链接的文字替换；替换值采用首个命中文字运行的完整样式和链接 |
| `PRESERVE` | 仅用于自定义／不可搜索块：原对象留在模型内，当前操作不编辑它；不代表源文件保留或任意后端支持 |
| `DISCARD` | 本批编辑入口没有可用的隐式丢弃通道；明确删块请调用 `remove` |

动作 ERROR 会抛出携带结构化位置的 `ProcessingException`。WARN 与 IGNORE 的处理结果一致，`warnings()` 可见性不同；二者的 `issues()`、`losses()` 和 `skipped()` 均保留实际结果。请求不支持的处理方式明确失败，不能宣称已原样保存。显式样式修改、删块及替换本身列 `changes`；DEGRADE 导致的样式／链接继承另列损失。

位置包含节 ID、BODY／HEADER／FOOTER、块 ID、外到内物理格路径及半开 UTF-16 范围。范围属于该项顺序操作的输入；不适用时为 -1／-1。报告不含正文、替换值或本地路径。

## 完整性与预算

- 匹配不递归处理新插入文字，不跨段落、图片或字段。同样式跨运行支持；不同样式默认拒绝。部分字素簇默认拒绝，可明确跳过；非法 Unicode 始终失败。Java 21 的 `\X` 边界不替代布局塑形或任意脚本语义分析。
- 字段和图片由有序内容重新生成 UTF-16 位置；未命中的内容及链接保留。原子对象不能通过 DEGRADE 删除或用文字代替。
- 完整 Note 集合通过 `.notes(notes)` 提供；验证 ID、原锚点、显式标记唯一性。未提供但模型含注释标记时失败。编辑不自动删注释或重写字段目标。
- 最终重新验证节点 ID、物理网格、列表祖先／计数、页码及编号引用目标、注释和页区限制。无效结构／引用不受 WARN／IGNORE 控制。
- `EditLimits` 分别限制操作、节点、UTF-16 文字、匹配、报告和累计工作；重复操作、预检、输出膨胀均计费。`ExecutionOptions` 控制同步取消和超时。预算不是硬 RSS 限制，自定义 Block 不是不可信代码沙箱。

运行 `:examples:semanticEditing` 后，使用 `scripts/verify_semantic_editing.py` 检查标准 OOXML、独立 PDF、Web 内容及损失对账。设计见 [ADR-0051](adr/0051-semantic-editing-and-loss-reports.md)。
