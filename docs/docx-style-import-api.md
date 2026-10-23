# DOCX 样式级联导入

语义导入支持有限 Word 样式图：docDefaults、默认段落样式、段落／字符 basedOn 链以及直接格式。标准段落、页眉与页脚采用相同级联；具名图展开后不再保有 Word 样式身份，必须明确接受损失。

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = DocxAdapter.builder()
        .fontFamily("Explicit Sans", new DocxAdapter.FontRoles(
                "", "bold", "italic", "boldItalic"))
        .build();
var prepared = adapter.conversion(Files.readAllBytes(Path.of("source.docx")))
        .policy(ProcessingPolicy.builder()
                .feature("OOXML_MARKUP", ProcessingPolicy.Action.WARN,
                        ProcessingPolicy.Treatment.DEGRADE)
                .build())
        .edit(editor -> editor.replaceText("Old title", "New title"))
        .prepare(adapter.conversionTarget());
prepared.to(Path.of("edited.docx"));
```

映射值是调用方配置的真实字体角色键；空 regular 代表默认字体组。布局到 PDF 时须在 DocumentEngine 注册对应真实字体，详见[统一转换](document-conversion-api.md)。不扫描系统字体、不合成粗斜体，不猜测 Word 主题字体。

## 支持边界

字号、颜色、下划线、上下偏移、粗体／斜体／删除线、有限段落几何与编号沿既有语义读取器处理。spacing／ind 逐属性合并；样式 b／i／strike 使用切换规则，直接 on／off 绝对覆盖。outlineLvl 0～5 对应 1～6 级标题。

单一显式 rFonts 字体族可映射到四个物理角色；未知主题、多脚本不同族或未注册族明确报损。所有定义均检查，未使用样式中的非法值也不能忽略。缺失基样式、循环、超过 64 层、重复、错误引用类型和非法格式值均失败。

旧 `read(..., STRICT)` 拒绝样式图损失；ALLOW_LOSS 的 Diagnostic 中为 `styleGraph`。统一入口对应 IMPORT／OOXML_MARKUP／DEGRADE。输出得到具体格式，不承诺保存原样式图；需要保留未编辑结构时选择[原包编辑](docx-editing-api.md)。

`maxExpandedBytes` 同时限制累计格式复制工作和规范化包编码量，前者是保守 XML 工作估计，不是堆内存测量。`maxEntryBytes` 限制单条目编码；段落／样式数及执行检查点继续生效。

首页／奇偶页区、浮动文本框、更广图片变换及更深内表的语义映射仍按[缺口表](capability-gaps.md)单独验收。完整例见 [DocxStyleImportExample](../examples/src/main/java/com/mirakyux/export4j/examples/DocxStyleImportExample.java)。
