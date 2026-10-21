# 行内图像与 DOCX 媒体

行内 PNG／JPEG 与文字共享换行和分页结果，适用于普通段落及语义表格单元格。PDF、SVG、PNG 执行相同图片矩形；DOCX 直接读写语义内容及标准 DrawingML。验收见[行内图像记录](verification/phase-6-inline-images.md)。

## 创建混排内容

~~~java
var resource = ImageResource.load(Files.readAllBytes(imagePath));
var image = new InlineImage(resource, 24, 24, 2,
        "审核状态图标", "https://example.com/help");
var format = new ParagraphFormat(12, 36, 0, 0,
        ParagraphAlignment.LEFT, 0, 6, false);
var paragraph = Paragraph.mixed("status", List.of(
        new InlineText("状态：", InlineStyle.of(12)), image,
        new InlineText(" 已确认", InlineStyle.of(12))), format);
var cell = TableCell.mixed(paragraph.content(),
        new CellFormat(12, 36, ParagraphAlignment.LEFT));
~~~

`InlineContent` 是 `InlineText` 与 `InlineImage` 的公共接口。图像宽高和基线偏移单位为 pt；正偏移将图像底边移到基线下方。指定矩形决定图片缩放，若需保持原始比例，由调用方计算宽高。尺寸须为正且不超过 14,400 pt；替代文字不超过 4,096 个 UTF-16 单元，不接受控制字符或非法 Unicode。URI 使用与文字外链相同的 HTTP／HTTPS／mailto 白名单。

`ImageResource.load(byte[])` 复制并验证编码字节；`bytes()` 返回副本，资源以 SHA-256 标识且不保留打开句柄。只接受静态 PNG／JPEG，拒绝 APNG、多图资源、其他格式、损坏输入和超限声明尺寸。单资源限制为 32 MiB 编码字节、2,000 万像素；单个 `Document` 布局输入中的不同资源合计不超过 64 MiB、5,000 万像素，DOCX 也对整个包的图片实施此合计限制。分节渲染的布局检查按节进行，不等于整个进程的内存上限。使用 JDK ImageIO／java.desktop，可 headless 运行；不读取文件、URL 或系统资源。

## 来源与固定行框

每张图片在 `Paragraph.text()` 中占一个 U+FFFC，`Paragraph.images()` 保存对应原文 UTF-16 位置；`content()` 返回有序的文字／图像。`TableCell` 使用单元格局部位置。优先使用 `mixed(...)`，旧文本构造器保持源代码兼容；手动构造的图片标记必须唯一、与原文匹配。若提供文字运行，图片标记须使用段落默认 `InlineStyle.of(format.fontSize())` 和图片自身链接；图片偏移由 `InlineImage` 表达，不能再叠加标记样式。单段最多 1,024 张图片。

图片无需字体提供 U+FFFC 字形，不触发正文缺字替换。默认自适应 `ParagraphLayouter` 使用物理字体与 `GraphicAttribute` 共同测量图片和文字，保存 `ImageBox`；显式注入的文本塑形器拒绝图像输入。整个段落沿用固定基线间距，统一行框必须容纳字体栈和所有图片。行距不足、图片宽于扣除缩进后的行宽时明确失败，不自动缩小、裁切或拆开图片。

分页只移动完整行，`TextLine.atBaseline()` 同时保留轮廓和图片矩形。可拆表格行按单元格完整行续页；正文图片只出现一次，重复表头的图片及链接随表头重复。自动列宽的测量包含图片。图片链接使用最终图片矩形；后端不重新换行或分页。

模板替换文字后重新计算图片来源位置，并绑定替代文字和 URI，保留显式资源及尺寸。给混排段落附加脚注标记不会丢失图片。替代文字目前在模型与 DOCX 保留；PDF 尚无 PDF/UA 图片标签与替代文字树，不能据此声称无障碍合规。

## DOCX 范围

| 内容 | 行为 |
| --- | --- |
| 正文与矩形单元格 | 标准 `w:drawing/wp:inline`、`pic:pic`、EMU 尺寸、`docPr` 替代文字 |
| 媒体 | 显式嵌入 `word/media/*.png/.jpg`，内容哈希去重，关系和 Content Types 一致 |
| 图片链接 | `a:hlinkClick` 配合被动外链关系；导入也识别外层 `w:hyperlink` |
| 基线 | 标准 `w:position`，半 pt 精度；尺寸量化至 1 EMU，即 1/12,700 pt，过小而量化为零时拒绝导出 |
| 预算 | 媒体计入 ZIP 条目／展开限制；图片标记展开为 XML 时也有预算；输出前完成检查 |
| 未支持绘制 | 浮动锚点、裁剪、旋转、偏移变换、效果、非矩形几何、链接图片、未知绘制属性或子节点 |

`STRICT` 拒绝不支持或损坏的图片，`ALLOW_LOSS` 省略对应图片并返回诊断。外部图片关系永不加载；越界／缺失内部媒体路径和重复关系 ID 拒绝。图片标题或列表无法由当前标题／列表模型表达时，严格模式拒绝，损失模式保留混排并明确降为普通段落。

尺寸采用项目统一矩形；导入的图片必须具有一致的 DrawingML 显示和变换范围。当前不读取任意媒体目录、不处理 VML、SVG 图像或 Word 默认样式级联。一般 Word 文档可能包含额外结构，需要审查损失诊断。独立 python-docx 可读取这些标准图像，但 Word／LibreOffice 的外观与点击行为尚未实机验证。

SVG／PNG 仍拒绝非空交互 extras。调用方可明确构造无交互的静态预览；不自动丢弃链接。示例中的 `preview` 就是这样创建的。

## 运行与兼容

~~~powershell
.\gradlew.bat -g build/gradle-validation build :examples:inlineImages --no-daemon --console=plain
python scripts/verify_inline_images.py
~~~

输出位于 `examples/build/inline-images`，包含 PDF、DOCX、DOCX 往返 PDF、SVG、144 DPI PNG ZIP 及几何清单。图片由示例代码生成，适用项目 Apache-2.0；未引入额外 Maven 依赖或外部图像资源。

`Paragraph`、`TableCell`、`TextLine` 增加记录分量，保留旧构造器的源代码调用方式；消费者需重新编译，不能把这次开发版本变化称为二进制 API 冻结。动态行内域、行内交叉引用、行内脚注锚点、完整 DOCX 资源／页区／合并表格／交互映射仍属后续范围。
