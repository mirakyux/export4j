# Markdown 模板

`MarkdownTemplate` 把轻量文本编译为既有 `DocumentTemplate`，适合结构简单的报告。它属于 `export4j-adapter-template`，仅依赖模型与 JDK。编译结果保留标题、列表、真实字体角色和行内图像，布局仍由引擎完成。这是严格子集，不是通用 CommonMark 或 GitHub Markdown 导入器。

## 最短调用

~~~java
var template = MarkdownTemplate.builder()
        .bodyFormat(ParagraphFormat.of(12))
        .build()
        .compile("""
                # ${title}

                客户：**${customer.name}**
                本行与上一行属于同一段落。

                - 已完成资料核对
                - 已完成结果复核
                """);
var document = template.render(Map.of(
        "title", "巡检报告",
        "customer", Map.of("name", "东门仓")));
engine.export(document).backend(new PdfBackend()).to(outputPath);
~~~

类型位于 `com.mirakyux.export4j.adapter.template`。调用方显式配置 engine 的字体、真实粗体／斜体角色、输出后端及 outputPath。编译器不扫描字体，不读取文件或 URL；文件输入由调用方打开 Reader，再交给 `compile(reader)`。Reader 所有权仍属于调用方。

## 严格语法

| 语法 | 语义与约束 |
| --- | --- |
| 普通文本 | 空行分段；相邻普通行以一个空格连接，不产生显式换行 |
| `# 标题`～`###### 标题` | 顶格 ATX 标题，井号后至少一个空格；可带空格分隔的尾部井号；内容须非空 |
| `- 条目`、`+ 条目`、`* 条目` | 顶格、单行、一级无序条目；统一标记为 `•` |
| `*文字*` | 真实 `italic` 字体角色 |
| `**文字**` | 真实 `bold` 字体角色 |
| `***文字***` | 真实 `boldItalic` 字体角色 |
| `${customer.name}` | 仅 ASCII 标识符与点组成的路径，最多 256 个 UTF-16 单元 |
| `![替代文字](logo)` | 正文行内图像，仅选择显式资源名；尺寸在 Java 中配置 |

标题使用 `Heading.defaultFormat(level)`；普通段落和条目使用配置的 bodyFormat，默认 12 pt 字号、18 pt 固定行距。强调内容不能嵌套、跨源行，也不能在标记内侧以空格开头或结尾。解析器不合成粗斜字形；使用强调时，调用方须为对应字体角色注册真实字体变体。

普通段落允许多行。条目只占一个源行；条目后的普通段落须先空一行，避免把 Markdown 的列表续行误当成新段落。标题、条目和段落都生成确定性 `markdown-N` ID；本语法不声明引用目标或显式 ID。空文档有效。

支持 CRLF、LF 和 CR。非空行必须顶格，制表符拒绝。一个尾空格按 Markdown 空白处理移除；两个以上尾空格会成为硬换行语义，因此拒绝。U+2028、U+2029、原始 U+FFFC 和控制字符也拒绝。非法代理对与 U+FFFE／U+FFFF 返回 Unicode 诊断。

反斜线可转义 `\`、`*`、`_`、`[`、`]`、`!`、`<`、`>`、反引号、`~`、`|`、`#`、`-`、`+`、`.`、`(`、`)`、`&`。例如 `\*普通星号\*` 显示普通星号。末尾反斜线、未知转义、美元符或花括号转义拒绝。`${...}` 始终表示变量；需要显示字面量表达式时，将其作为数据值插入。

变量不会执行方法、索引、运算、反射或脚本。数据值只展开一次，里面的 Markdown、HTML 和 `${other}` 都作为普通文字保留。日期、金额及本地化格式由调用方预先处理。

## 显式图像资源

~~~java
var compiler = MarkdownTemplate.builder()
        .bodyFormat(ParagraphFormat.builder().fontSize(12).lineHeight(20).build())
        .image("logo", imageResource, 12, 14)
        .build();
var template = compiler.compile("${name} ![标记 ${name}](logo)");
~~~

imageResource 是调用方已经有界解码的不可变 ImageResource。width／height 均为正 point，不能超过 14,400 pt。编译器不解码图像。资源名匹配 `[A-Za-z_][A-Za-z_0-9]{0,255}`；同名登记替换配置，build 后的编译器保留此前快照。

模板图片目标仅接受资源名，不接受路径、URL、变量、标题或尺寸语法。alt 只接受普通文字、转义和变量，不接受富文本。标题、条目和强调区间不能含图像。单个段落最多 1,024 张图像，与既有模型上限一致。图像的 U+FFFC 位置在绑定后重新计算，原字符串中的补充平面字符不会破坏索引。

固定行框必须容纳图像与字体，空间不足由布局明确失败。替代文字在编译时及绑定后都遵守 InlineImage 约束：最多 4,096 个 UTF-16 单元且不含控制字符。绑定后的非法 alt 仍由既有模型抛出 IllegalArgumentException，不映射回 Markdown 行列。

## 预算与诊断

`MarkdownTemplate.Limits(maxInputCharacters, maxLines, maxBlocks, maxInlineContents)` 默认分别为 1,000,000、20,000、20,000、100,000。输入总长度计 UTF-16 单元，包含行终止符。lines 计空白物理行，但不计末尾换行之后的空行；空输入为 0 行。inlines 计全部实际文字运行、图像及段落软换行插入的空格。

借用 Reader 最多读取输入限额加 1 个 UTF-16 单元，无论成功、编译失败或 I/O 失败都不关闭。Reader 的 IOException 原样传播。自定义 Reader 与容器属于调用方可信 Java 代码，须自行配合取消，不构成脚本沙箱。

`.bindingLimits(DocumentTemplate.Limits)` 独立限制数据快照、访问次数、生成块及码点等工作。源码解析不递归，扫描与读取有执行检查点；可放入 `ExecutionContext.execute(options, ...)` 控制同步取消与超时。模板和文档仍驻留内存，不承诺流式布局。

| 阶段 | 诊断 |
| --- | --- |
| Markdown 编译 | MarkdownTemplateException.code／line／column／offset |
| 数据绑定 | DocumentTemplate.BindingException.code／path |
| 布局、字体与输出 | 对应模块既有诊断 |

编译位置使用原始源码：line／column 一基，column 和零基 offset 均计 UTF-16 单元；CRLF 的两个单元都计入 offset。超出输入限额时定位第一个越界单元；末尾错误的 offset 可以等于源码长度。诊断只包含稳定错误码与位置，不回显正文、数据或资源路径。不返回部分模板；绑定错误不映射回源码行列。

## 拒绝边界

HTML／注释／尖括号自动链接、普通或引用链接、图片外链、HTML 实体、代码块、行内代码、引用块、表格、分隔线、Setext 标题、有序／任务／嵌套／多行列表、下划线强调、删除线及硬换行均拒绝。部分保留标点在普通文案中也须转义；解析器不猜测未知语法是否应按文字显示。裸 URL 只是普通文本，不会自动变成链接。

条件、循环、动态表格行、字段、分页和 include 没有 Markdown 语法；`{{...}}` 与 `{%...%}` 指令形式拒绝。较复杂模板使用[严格 XML 入口](xml-template-api.md)或既有 DocumentTemplate Java API。导出 DOCX、SVG 或 PDF 仍遵守对应支持矩阵，不承诺任意 Markdown 无损、Word 与 PDF 同分页或 PDF/UA 合规。

本实现自主设计，没有引入 Markdown 解析库或新资源。边界测试见 [MarkdownTemplateTest](../export4j-adapter-template/src/test/java/com/mirakyux/export4j/adapter/template/MarkdownTemplateTest.java)，设计依据见 [ADR-0028](adr/0028-markdown-templates.md)。

完整模板见 [summary.md](../examples/src/main/resources/templates/summary.md)，包含资源及真实字体配置的调用见 [MarkdownTemplateExample](../examples/src/main/java/com/mirakyux/export4j/examples/MarkdownTemplateExample.java)。运行 `:examples:markdownTemplates`，然后用本次成功的隔离调用方执行 `python scripts/verify_markdown_templates.py`；证据与限制见[阶段二十二](verification/phase-22-text-and-adapters.md)。
