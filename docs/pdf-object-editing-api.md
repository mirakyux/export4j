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
| 文本框 | 读取值／只读状态；填写普通单行文本，显式完整 TrueType 字体与固定字号；不使用字体扫描或缺字替换 |
| 下拉框 | 读取选项及值，填写一个已声明 export value，按对应 display value 核对容量 |
| 复选框／单选组 | 读取值／选项，使用既有 on/off 外观状态；未知选项失败 |
| 字段扁平化 | 验证当前外观后将其绘入页面并删除表单编辑能力；保留其他对象 |
| 批注 | 读取 Text 批注、创建／更新普通评论、删除非 widget 批注；索引基于前序编辑后的当前数组 |
| 链接 | 读取被动 HTTP／HTTPS／mailto 与明确页面目标；新增外链／内链，删除相应批注 |
| 书签 | 读取标题、目标页和树；显式替换整棵树，新条目使用 page-fit 目标 |
| 附件 | 读取内嵌文件字节／媒体类型；按逻辑文件名新增、替换或删除；不自动保存／执行附件 |
| 元数据 | 读取／设置 Title、Author、Subject、Keywords、Creator、Producer；其他源属性保持 |

矩形及字段字号采用 **PDF 原生未旋转用户空间单位、左下角原点**；与[页级编辑](pdf-page-editing-api.md)的物理 pt 坐标不同。读取快照的页面／批注索引从零开始。读取投影不含全部 PDF 属性，未修改的对象仍在源结构中保存。

## 字段与损失策略

普通字段填写保留背景、边框、对齐和 DA 前景色。固定字体、字号由调用方显式提供，完整嵌入以继续编辑；当前值按真实字形宽度、行框及保守边框内缩先检查，放不下即失败。多行、密码、文件选择、comb、富文本、旋转 widget、其他 DA 文字状态及非单选下拉框没有本次填写路径。FIELD_FORMAT 默认 ERROR，仅允许 SKIP_MATCH 保留原字段并登记未应用；widget 独立删除为 FIELD_WIDGET，复杂文字为 TEXT_SHAPING，同样只能跳过。畸形 DA 在全输入预检中失败，不能以跳过字段绕过。

扁平化需要 FORMS／FLATTEN_FORMS／DISCARD，并返回 `FORM_EDITABILITY_REMOVED`；NeedAppearances 或缺少当前 normal 外观时失败。修改 Info 时已有 XMP 必须按 XMP_METADATA／SET_METADATA／DISCARD 删除，记录 `XMP_METADATA_REMOVED`；没有静默同步或伪装保留。WARN／IGNORE 执行相同处理，IGNORE 仍保留 issues／losses。加密编辑还须显式允许 owner 解密及 ENCRYPTION_REMOVED／DEGRADE。

未知选项、只读权限、非法结构／Unicode、重复字段、错误 widget 归属、树循环、预算、取消和 I/O 均不可忽略。只接受已验证的 Text／Link／Widget，拒绝脚本、活动动作、签名、XFA、图层、标签和外部资源；未知结构不能通过 PRESERVE 枚举进入该入口。

## 预算与 I/O

继承 PdfLimits 的对象、深度、页、解码、文字和输出上限；所有编辑文字与附件快照分别累计计费，最多 10,000 操作且不超过 maxObjects。附件读取返回防御性字节副本，名称是包内逻辑名，绝不按源名称写入文件系统。

prepare 完成完整预检、编辑、复检及确切编码，PreparedPdf 与[页级编辑](pdf-page-editing-api.md)共用输出契约。路径原子替换，借用流不关闭／刷新。签名没有本次修改路径，不承诺任意 PDF 编辑或内容安全删除。跨来源合并、选页后的交互重映射使用独立的[被动对象合并入口](pdf-object-composition-api.md)。

可运行示例见 [PdfObjectEditingExample](../examples/src/main/java/com/mirakyux/export4j/examples/PdfObjectEditingExample.java)，证据见[阶段五十](verification/phase-50-pdf-object-editing.md)。
