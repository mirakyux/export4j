# 已有 PDF 的表单与交互对象

`PdfAdapter.readObjects` 返回不可变快照，`editObjects` 在受支持的被动结构中修改源文档。它们独立于原有严格静态复制入口，不通过语义模型重建整个文件。

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("form.pdf"), "");
var before = adapter.readObjects(input);
var font = PdfFont.of(Files.readAllBytes(Path.of("explicit-font.ttf")));
var prepared = adapter.editObjects(input)
        .fillText("name", "Ready", font, 12)
        .setChecked("approved", true)
        .metadata("Title", "Reviewed document")
        .prepare();
prepared.to(Path.of("edited.pdf"));

var flatten = ProcessingPolicy.builder()
        .feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
adapter.editObjects(adapter.input(Path.of("edited.pdf"), ""))
        .policy(flatten).flattenForms().prepare().to(Path.of("flattened.pdf"));
```

## 支持范围

| 对象 | 读取与修改 |
| --- | --- |
| 文本框 | 读取值、只读、多行、comb、富文本及 MaxLen；填写普通单行／多行、comb、显式富文本；固定字号或指定范围内自动字号，完整嵌入调用方 TrueType |
| 下拉框 | 读取选项及值，填写一个已声明 export value，按对应 display value 核对容量 |
| 多选列表 | 读取多个 export value，填写已声明的选项集合；每个 display value 独占一行并预检查宽度，显示全部选项，标亮所选项并保存 `/I` 索引；空集合清除选择 |
| 复选框／单选组 | 读取值／选项，使用既有 on/off 外观状态；未知选项失败 |
| 字段扁平化 | 验证当前外观后将其绘入页面并删除表单编辑能力；保留其他对象 |
| 批注 | 读取 Text／Highlight／Underline／StrikeOut／Ink／Square／Circle，创建带具体外观的这六类标记和普通评论，更新普通评论、删除非 widget 批注；索引基于前序编辑后的当前数组 |
| 链接 | 读取被动 HTTP／HTTPS／mailto 与明确页面目标；新增外链／内链，删除相应批注 |
| 书签 | 读取标题、目标页和树；显式替换整棵树，新条目使用 page-fit 目标 |
| 附件 | 读取内嵌文件字节／媒体类型；按逻辑文件名新增、替换或删除；不自动保存／执行附件 |
| 元数据 | 读取／设置 Title、Author、Subject、Keywords、Creator、Producer；其他源属性保持 |

矩形及字段字号采用 **PDF 原生未旋转用户空间单位、左下角原点**；与[页级编辑](pdf-page-editing-api.md)的物理 pt 坐标不同。读取快照的页面／批注索引从零开始。读取投影不含全部 PDF 属性，未修改的对象仍在源结构中保存。

对象读取与原文档编辑接受阅读器保存时添加的 `PieceInfo` 应用元数据，并保留未修改的私有数据。其嵌套对象和流仍执行活动内容拒绝及对象／深度／解码预算；它不授予脚本执行权限，也不表示库解释或更新应用私有缓存。严格静态复制入口的范围不因此扩大。实际阅读器填写、保存及回读证据见[阶段一百零七](verification/phase-107-windows-reader-compatibility.md)。

## 字段与损失策略

普通字段填写保留背景、实线边框、对齐和 DA 前景色。固定字体、字号由调用方显式提供，完整嵌入以继续编辑；当前值按真实字形宽度、行框及保守边框内缩先检查，放不下即失败。多行按明确换行及可用宽度排版；comb 按 Unicode 码点放入 MaxLen 个单元。`fillTextAuto` 在调用方给出的最小／最大字号内有界求解，将最终固定字号写入 DA，不依赖阅读器自动修复或无限缩字。

```java
adapter.editObjects(input)
        .fillText("address", "FIRST LINE\nSECOND LINE", font, 12)
        .fillTextAuto("code", "AB1234", font, 6, 18)
        .fillRichText("description", java.util.List.of(
                new com.mirakyux.export4j.adapter.pdf.PdfFieldText.Run(
                        "Emphasized", font, 12, 0xcc2200, true)))
        .fillChoices("categories", java.util.List.of("A", "C"), font, 12)
        .prepare().to(Path.of("filled.pdf"));
```

富文本每段声明真实字体、字号、RGB 和下划线，生成标准 XHTML `/RV` 及与值一致的具体外观；粗体／斜体通过调用方提供的相应字体程序表达。普通填写覆盖富文本须接受 `FIELD_RICH_TEXT / DISCARD`。既有 `/AP/R` 和 `/AP/D` 以及其中命名状态全部用更新后的值、显式字体和 widget 样式重新生成，须接受 `FIELD_APPEARANCE_STATES / DEGRADE`，不保留过期的 rollover／down 值或伪装保留其原样式。

密码、文件选择、旋转 widget、非实线边框、含换行的列表选项及不支持的 DA 文字状态使用 `FIELD_FORMAT / SKIP_MATCH`，默认 ERROR；显式 WARN／IGNORE 才保留原字段并登记未应用。widget 独立删除为 FIELD_WIDGET，复杂文字为 TEXT_SHAPING，同样只能跳过；列表框检查全部显示选项，不仅检查所选 export value。畸形 DA、非法 MaxLen／标志类型及活动富文本在全输入预检中失败，不能以跳过字段绕过。

## 标记批注

`addAnnotation(pageIndex, PdfAnnotationSpec)` 接受 Highlight、Underline、StrikeOut、Ink、Square 和 Circle。矩形、quad 和墨迹点均使用上述原生 PDF 坐标；quad 顺序为左上、右上、左下、右下。每个标记均生成独立 normal appearance，颜色、线宽、透明度和几何与字典一致；Highlight 使用 Multiply 混合。`fillRgb == -1` 表示无填充，线宽 0 保留 PDF hairline 语义。读取快照返回这些几何、颜色和透明度的不可变投影。

这六类批注不执行动作，不根据正文自动推断选择范围。调用方须提供明确几何；非法点、退化 quad、超出矩形的几何、非有限数值或类型不匹配直接失败。任意批注类型、回复链和批注数据交换不在该入口范围。FDF／XFDF 的字段数据交换使用[独立 API](pdf-form-data-api.md)。

扁平化需要 FORMS／FLATTEN_FORMS／DISCARD，并返回 `FORM_EDITABILITY_REMOVED`；NeedAppearances 或缺少当前 normal 外观时失败。修改 Info 时已有 XMP 必须按 XMP_METADATA／SET_METADATA／DISCARD 删除，记录 `XMP_METADATA_REMOVED`；没有静默同步或伪装保留。WARN／IGNORE 执行相同处理，IGNORE 仍保留 issues／losses。加密编辑还须显式允许 owner 解密及 ENCRYPTION_REMOVED／DEGRADE。

未知选项、只读权限、非法结构／Unicode、重复字段、错误 widget 归属、树循环、预算、取消和 I/O 均不可忽略。批注只接受已验证的上述类型及 Link／Widget，脚本、活动动作、签名、XFA 和外部资源继续拒绝；未知结构不能通过 PRESERVE 枚举进入该入口。XFA 仅由[专用离线读取／扁平化 API](pdf-xfa-api.md)接收其声明子集。

图层／静态标签默认拒绝，可由 `PdfAdapter.builder().allowPassiveStructures(true)` 显式开启验证及完整原文档保留；元数据等无关编辑不重建源结构树或图层配置。编辑后再次预检，若删除对象导致标签所有者或结构引用悬空，整个候选失败，不能用损失策略放行。接受结构和新页树组合边界见[被动结构指南](pdf-passive-structures-api.md)。

## 预算与 I/O

继承 PdfLimits 的对象、深度、页、解码、文字和输出上限；所有编辑文字与附件快照分别累计计费，最多 10,000 操作且不超过 maxObjects。附件读取返回防御性字节副本，名称是包内逻辑名，绝不按源名称写入文件系统。

prepare 完成完整预检、编辑、复检及确切编码，PreparedPdf 与[页级编辑](pdf-page-editing-api.md)共用输出契约。路径原子替换，借用流不关闭／刷新。签名没有本次修改路径，不承诺任意 PDF 编辑或内容安全删除。跨来源合并、选页后的交互重映射使用独立的[被动对象合并入口](pdf-object-composition-api.md)。

可运行示例见 [PdfObjectEditingExample](../examples/src/main/java/com/mirakyux/export4j/examples/PdfObjectEditingExample.java)，基础证据见[阶段五十](verification/phase-50-pdf-object-editing.md)，扩展表单／标记／数据／XFA 验证入口见[阶段九十四](verification/phase-94-pdf-extended-forms.md)。
