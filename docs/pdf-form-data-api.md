# PDF 表单数据：FDF 与 XFDF

`PdfAdapter.formData(input)` 将受支持 AcroForm 的真实字段值导出为标准 FDF 或 UTF-8 XFDF。`PdfFormData.parse` 解析独立数据文件，`editObjects(input).importFormData(...)` 按完整字段名应用到已有 PDF。数据文件不承载页面布局、字体、widget 几何或具体外观；这些对象保留在目标 PDF 中。

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfFormData;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("form.pdf"), "");
var policy = ProcessingPolicy.builder()
        .feature("FORM_DATA_APPEARANCE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DISCARD)
        .feature("FORM_DATA_RICH_TEXT", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DISCARD)
        .feature("FIELD_RICH_TEXT", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DISCARD)
        .feature("FIELD_APPEARANCE_STATES", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var exported = adapter.formData(input).format(PdfFormData.Format.XFDF)
        .policy(policy).prepare();
exported.to(Path.of("values.xfdf"));

var data = PdfFormData.parse(Files.readAllBytes(Path.of("values.xfdf")),
        PdfFormData.Format.XFDF);
var font = PdfFont.of(Files.readAllBytes(Path.of("explicit-font.ttf")));
adapter.editObjects(input).policy(policy).importFormData(data, font, 12)
        .prepare().to(Path.of("applied.pdf"));
```

## 数据契约

`FieldValue(name, values, richText)` 保存精确名称、不可变值列表及可选 XHTML 富文本；便利构造 `new FieldValue(name, value)` 表达单值，`PdfFormData.of(...)` 接收应用程序数据。嵌套 FDF／XFDF 字段投影为点分隔完整名称，不进行模糊匹配、大小写转换或字段创建。支持 Unicode、换行、XML 特殊字符、多选值及空列表清除多选。

导出保留富文本值：XFDF 的 `<value-richtext>` 内写入真实 XHTML 子树，FDF 写入标准字段富文本。接受的 XHTML 只有 body、p、span、b、i、u、br，以及有限的颜色、字号、字体名、粗斜体和下划线样式；脚本、图片、外部样式和活动属性拒绝。富文本数据应用时以普通值及调用方显式字体重新生成目标外观，明确报告样式丢失；不会把 XML 指定的字体名作为系统字体查找依据。

应用支持普通／多行／comb 文本、单选及多选选择框、复选框和单选组，沿用[对象编辑](pdf-object-editing-api.md)的选项、只读、容量、字体与外观契约。未知选项、实际缺字、非法值或超容量硬失败，不截断。未在数据文件列出的字段保持原对象和值。

## 策略与输入边界

默认 ERROR。WARN 和 IGNORE 执行相同实际处理，IGNORE 仍返回完整 issues／losses。解析产生的损失继续进入应用后的实际报告。

| feature | treatment | 实际结果 |
| --- | --- | --- |
| FORM_DATA_APPEARANCE | DISCARD | 导出值；几何、字体和外观不写入数据文件 |
| FORM_DATA_RICH_TEXT | DISCARD | 应用普通值，以显式调用方字体重建外观 |
| FIELD_RICH_TEXT | DISCARD | 普通数据覆盖目标原富文本，移除旧 RV／DS |
| FORM_DATA_PROPERTIES | DISCARD | 丢弃不支持的被动数据属性，保留已识别值 |
| FIELD_MAPPING | SKIP_MATCH | 未知完整字段名不应用，目标字段不变 |

带策略及预算的解析重载为 `parse(bytes, format, limits, policy, execution)`；默认重载使用严格策略、默认预算及同步执行。FDF 动作、JavaScript、文件引用、嵌入文件和所有 FDF 流拒绝；XFDF 外部引用、DTD、实体、脚本拒绝。解析器不联网，不导入批注或附件，不执行提交动作。

输入／解码字节、对象、深度、属性、UTF-16 文字、字段及输出全部受限，同步取消贯穿解析和应用。重复名称、畸形类型、权限、预算、取消及 I/O 均不可忽略。

## 输出与验证

`PreparedPdfFormData` 保存已完成编码的不可变数据，`bytes()` 返回防御副本，`preview()` 返回 PREVIEW 报告；`to` 返回字段数、确切字节数和 ACTUAL 报告。借用输出流不关闭或刷新，路径输出同目录临时文件原子替换，失败保持旧目标。

独立 FDF／XFDF 输入、公开坐标调用方、外部解析及双引擎 PDF 输出验证见[阶段九十四](verification/phase-94-pdf-extended-forms.md)。数据交换不构成 PDF 页面、字体或交互外观的无损往返。
