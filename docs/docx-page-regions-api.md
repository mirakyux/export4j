# DOCX 页眉页脚

`DocxAdapter` 直接读写语义页区，支持 `PageDocument` 和带 `Section.pageRegions` 的 `SectionedDocument`。内容为有序段落／语义表格、嵌入 PNG／JPEG，以及原生 PAGE／NUMPAGES 字段。不会先转成 PDF 或绘制坐标。

## 写入与读取

```java
var adapter = new DocxAdapter();
try (var output = Files.newOutputStream(docxPath)) {
    adapter.write(document, output);
}
try (var input = Files.newInputStream(docxPath)) {
    var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
    engine.export(imported.document()).backend(new PdfBackend()).to(pdfPath);
}
```

流由调用方关闭。适配器在开始写 ZIP 前验证语义和输出字节预算；没有新的后台线程或外部资源读取。

| 项目 | 映射 |
| --- | --- |
| 页区 | 标准 word/headerN.xml、footerN.xml 及各节 default headerReference/footerReference |
| 图像 | 标准 DrawingML；图片关系在当前页区 part 内解析；媒体按资源摘要去重 |
| 表格 | 复用现有矩形跨度、两层原子块格、样式和字段；固定区仍须完整放入 |
| 页码 | PAGE／NUMPAGES 原生字段、dirty 标记和 updateFields；固定宽度保存在可忽略扩展中 |
| 继承 | 未声明默认引用时按 OOXML 继承前节；导出会显式写清空 part，避免已清空区域又继承回来 |
| 几何 | 标准 pgSz／pgMar 保留纸张、正文边距及页区距离，固定 height/gap 保存在可忽略扩展中 |

相等的不可变页区可被多节共享。比例表格按各节实际正文宽度生成网格，不同宽度使用独立 part；导入后仍恢复相同语义。所有节与页区共享源节点／表格槽／文字、图片资源及 ZIP 预算，重复来源不绕过限额。标准整数 twip 会量化页面尺寸；不保证与 PDF 同分页。

## 普通 Word 页区

OOXML 页眉页脚没有固定高度。没有 Export4j height/gap 元数据时，必须显式配置各类页区的预留几何，不根据字号或内容猜高度：

```java
var adapter = DocxAdapter.builder()
        .regionGeometry(new DocxAdapter.RegionGeometry(24, 6, 24, 6))
        .build();
var imported = adapter.read(input, DocxAdapter.Policy.STRICT, 24);
```

四个值依次为页眉高度／间距、页脚高度／间距，单位 pt；最后的 read 参数为无宽度元数据的字段预留宽度。缺少必要配置会失败，不能由 ALLOW_LOSS 静默移除页区。显式 Word header/footer 距离必须与预留关系一致；负尺寸、非有限值和冲突几何失败。

## 损失与验证范围

当前页区只接受默认重复区域，不包含 Word 首页／奇偶变体、浮动图片／文本框、链接、目标引用或完整样式继承。未知 markup 按 STRICT 拒绝或 ALLOW_LOSS 返回实际 part 诊断；畸形或外部页区关系、重复引用、错误 part 类型、ID 碰撞、无效几何和预算超限在两种策略下均失败。宏始终拒绝，外链图片永不获取。

[综合样例](../examples/src/main/java/com/mirakyux/export4j/examples/XmlSectionsExample.java)与[隔离调用方](../verification/consumer/src/main/java/consumer/XmlSectionsConsumer.java)均验证严格读回及 PDF 可见输出一致；Python 使用 ZIP/XML 和 python-docx 独立检查标准 part／关系／图像／字段。Word 验证脚本仅操作生成样例：

```powershell
.\gradlew.bat :examples:xmlSections
powershell -NoProfile -File scripts/verify_docx_page_regions_word.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_sections.py --word
```

Word 打开、字段更新、保存、PDF 导出和保存回读是不同证据。当前具体结果见[阶段四十二](verification/phase-42-sections-and-docx-regions.md)，不把结构验证称作一般 Word 无损往返。
