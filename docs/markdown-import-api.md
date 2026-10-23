# 通用 Markdown 导入

`export4j-adapter-web` 的 `MarkdownImporter` 将 CommonMark／GFM 子集转换为 `Document`，可继续用 [DocumentEditor](semantic-editing-api.md) 编辑并导出。它与[严格 Markdown 模板](markdown-template-api.md)独立：`${name}` 在这里是普通文字，不执行变量替换。

```java
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;

var policy = ProcessingPolicy.builder()
        .feature("BLOCK_QUOTE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = MarkdownImporter.builder().policy(policy).build()
        .read("# Report\n\n> Pending\n\nOld status").prepare();
var predicted = prepared.preview();
var imported = prepared.execute();
var edited = DocumentEditor.edit(imported.document())
        .replaceText("Old status", "Ready").execute();
var losses = imported.report().losses();
```

`prepare()`／`execute()` 声明 `IOException`，配合 `.execution(ExecutionOptions)` 同步执行；本入口本身没有文件 I/O。Builder 构建时快照；PreparedImport 固定输入、配置、候选及预检报告，后续 Request 修改不影响它。

## 映射范围

| 输入 | 模型行为 |
| --- | --- |
| 段落、ATX／Setext 标题 | Paragraph／Heading；软换行变为空格，硬换行保留 |
| strong／emphasis／两者组合、删除线 | 完整行内样式；默认角色 `bold`／`italic`／`boldItalic`，删除线保留 |
| 链接、引用式链接 | 被动 HTTP／HTTPS／mailto；引用定义不进入正文，链接标题另报损失 |
| 有序／无序及混合嵌套列表 | NumberedItem／BulletItem，最多九层，保持起始编号和父列表关系；模型有序子级使用完整点分编号 |
| GFM 表格 | 语义 Table、表头、单元格对齐、转义管道及空格；列为等比例，空正文表需明确增加空行 |
| 显式登记图片 | Paragraph／格中的 InlineImage，保留源替代文字及所在链接；标题／列表只允许明确降级为替代文字 |
| 代码／引用／任务条目 | 需要明确损失处理；不宣称保留代码语言、引用容器或复选框语义 |

字体通过 `.fontRoles(strong,emphasis,combined,code)` 指定角色，导出 PDF 时仍须显式登记对应物理字体。默认代码角色为 `code`，不自动获取字体。元数据、纸张及默认正文格式可通过 Builder 提供。

## 资源与策略

`.image(destination, inlineImage)` 用精确源目标键登记编码资源和 pt 尺寸，绝不联网或读文件。缺图默认报错，可明确转成替代文字或丢弃图片。`.baseUri("https://example.org/docs/")` 只解析被动相对链接，不获取内容；无 base 的相对／内部目标默认报错，不推测模型锚点。

共享策略的格式键 `MARKDOWN`、操作键 `IMPORT`，优先级为 operation → feature → format → default。默认 ERROR；WARN／IGNORE 执行所选处理，IGNORE 只隐藏 `warnings()`，不删除 `issues()`／`losses()`。导入只提供真实可用的 DEGRADE／DISCARD，不支持伪保留或编辑跳过范围。

| 功能键 | DEGRADE | DISCARD |
| --- | --- | --- |
| `BLOCK_QUOTE` | 去容器，缩进正文 | 去整个引用子树 |
| `CODE_BLOCK`／`INLINE_CODE` | 带代码角色的文字，去代码语义及语言 | 去对应代码 |
| `THEMATIC_BREAK` | 五个连字符文字 | 去分隔线 |
| `RAW_HTML` | 安全 HTML 按字面文字输出；简单 br 直接为换行 | 去该原始 HTML 节点 |
| `TASK_LIST` | 保留任务标记字面文字 | 去该任务条目 |
| `LIST_ITEM_STRUCTURE` | 多块内容平铺并保留缩进 | 去该不支持的子块／条目 |
| `IMAGE_RESOURCE`／`HEADING_INLINE_OBJECT`／`LIST_INLINE_OBJECT` | 图片变替代文字 | 去图片 |
| `LINK_TITLE`／`IMAGE_TITLE`／`RELATIVE_LINK` | 去不支持的标题／目标，保留其余内容 | 同样仅去该属性／目标 |
| `TABLE_HEADER_ONLY` | 增加空正文行 | 去表格 |
| `NUMBERING_START` | 零起始编号改为一 | 去该列表 |

未知节点仅允许 DISCARD，不能虚构降级表示。脚本或危险链接即使在将被丢弃的子树内也拒绝；安全原始 HTML 不在这里解释布局。

## 位置与预算

报告使用原始输入的半开 UTF-16 范围、生成块 ID 和物理格路径，不回显正文或资源路径。生成 ID 为 `markdown-N`；由于容器也分配 ID，不保证连续。

`ImportLimits` 默认限制输入 1,000,000 UTF-16、单行 16,384、节点 100,000、深度 64、输出文字 2,000,000 UTF-16、表格槽 100,000、报告 10,000 和工作 20,000,000。非法 Unicode／NUL、超限、取消和超时始终失败。第三方解析前后与树遍历有检查点；这不是恶意输入沙箱或硬 RSS／硬中断承诺。

完整调用见 [MarkdownImportExample](../examples/src/main/java/com/mirakyux/export4j/examples/MarkdownImportExample.java)。运行 `:examples:markdownImport` 后用 `scripts/verify_markdown_import.py` 验证独立语法、多目标输出和实际损失；证据见[阶段四十五](verification/phase-45-markdown-import.md)。
