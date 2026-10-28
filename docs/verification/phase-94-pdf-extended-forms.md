# 阶段九十四：扩展表单、标记、数据交换与 XFA

本阶段对应 GAP-10.10／GAP-10.11／GAP-10.12。交付真实字段填写与具体外观、六类被动标记、标准 FDF／XFDF 文件交换，以及声明子集的静态／动态 XFA 数据读取和分页扁平化。

## 能力与边界

| 项 | 实际行为 | 明确边界 |
| --- | --- | --- |
| 扩展 AcroForm | 多行、comb、显式富文本、多选列表、有界自动字号；真实值、索引与具体 AP 一致 | 调用方 TrueType 完整嵌入；不扫描系统字体、不截断、不隐式缩字；旋转／非实线 widget 按 FIELD_FORMAT 跳过 |
| 外观状态 | 更新 normal 以及既有 rollover／down 和命名状态 | 统一使用显式值及 widget 样式，须接受 FIELD_APPEARANCE_STATES／DEGRADE |
| 标记批注 | Highlight、Underline、StrikeOut、Ink、Square、Circle 的读取与创建；几何、颜色、透明度及具体 AP | 明确原生 PDF 用户空间坐标；不自动推断正文范围、不执行动作 |
| FDF／XFDF | 导出、独立文件解析、按精确字段名实际应用；Unicode、换行、多选、标准 XHTML 富文本值 | 字段数据与页面／外观分离；应用富文本样式显式丢弃；活动内容、外部引用、FDF 流拒绝 |
| 静态 XFA | packet 数组与单流 XDP，读取实际 datasets 绑定值及物理几何，创建静态页 | 声明 position 文本子集；统一调用方字体与字号，不复制源 PDF 外壳 |
| 动态 XFA | 根 tb 流、occur 和重复数据节点，按声明行高分页 | 一个 pageArea／contentArea，不执行脚本／任意 SOM，不支持嵌套 tb 或完整阅读器布局引擎 |

契约见[对象编辑](../pdf-object-editing-api.md)、[FDF／XFDF](../pdf-form-data-api.md)与[XFA](../pdf-xfa-api.md)。默认 ERROR；WARN／IGNORE 执行相同处理，IGNORE 仍返回全部实际损失。无效结构、活动内容、权限、缺字、容量、预算、取消和 I/O 不能借损失策略放行。

## 独立验证

源输入由 [pypdf 生成器](../../scripts/create_pdf_extended_forms_fixtures.py)独立构造，不使用 Export4j／PDFBox 生成。11 份文件包含 6 份 PDF：普通表单、静态 packet XFA、XDP、动态 XFA，以及脚本／外部引用负例；另有真实 FDF／XFDF 和非法交换文件。字体使用已登记验证资源，无新增依赖。

[调用方](../../verification/consumer/src/main/java/consumer/PdfExtendedFormsConsumer.java)按公开 API 完成填写、扁平化、导出／解析／应用、XFA 分页及拒绝矩阵。[独立验证器](../../scripts/verify_pdf_extended_forms.py)使用 pypdf、XML、Poppler 与 PDFium 检查：

- 21 份 PDF 输出、4 份标准数据输出、15 个拒绝场景和 11 次失败旧目标保护；源文件摘要保持。
- 字段真实 V、Ff、MaxLen、选项索引、XHTML 样式与 AP 内独立提取文字一致，显式 TrueType 嵌入；R／D 命名状态均包含新值。
- 六类批注的精确几何、所属页、颜色／透明度和具体外观；填写与扁平化在两个引擎中逐页像素一致。
- 独立解析导出 FDF／XFDF 的精确名称、单值和数组；真实外部数据应用成功；WARN／IGNORE 实际损失完整一致。
- 静态 XFA／XDP 的数据值与尺寸，动态 8 行按 5＋3 分为两页；输出不含 XFA／AcroForm。
- 40 个 PDF 页面以 144 DPI 完成双引擎渲染，80 张独立页面图、80 个图块、14 张联络图全部检查。

PDFium 表单层使用 LCD 文本抗锯齿，验证器将普通页面文本显式设为相同模式，并打开标记绘制；由此比较真实扁平化差异，不把两种渲染模式的字形边缘误差当作输出变化。Poppler 同时独立检查；两者均要求填写／扁平化像素一致，没有放宽误差阈值或刷新视觉基线。

## 本轮验证与复现

定向 25 项测试通过：`PdfExtendedFormsTest` 8 项、`PdfFormDataTest` 3 项、`PdfObjectEditorTest` 11 项、`PdfXfaTest` 3 项，零失败／错误。列表选项超宽提前明确失败，含换行或复杂塑形的全部显示选项按真实策略保留原字段，避免错误换行、选择行串位或未选择文本绕过检查。全新隔离 Maven 调用方已从公开坐标解析本轮制品并执行上述矩阵，独立验证全部通过。最终制品生成的 14 张联络图与已逐张阅读的图像 SHA-256 完全一致。

从仓库根执行，Python 使用 `scripts/requirements-verification.txt` 固定依赖并安装 Poppler。其他平台将 Wrapper 改为 `./gradlew`。`<consumer-build-directory>` 是相对 `verification/consumer` 的新构建目录。

```powershell
python scripts/create_pdf_extended_forms_fixtures.py
.\gradlew.bat :export4j-adapter-pdf:test --tests '*PdfExtendedFormsTest' --tests '*PdfFormDataTest' --tests '*PdfObjectEditorTest' --tests '*PdfXfaTest'
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build resolvedLibraries pdfExtendedForms --refresh-dependencies
python scripts/verify_pdf_extended_forms.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```

原始报告、生成物、联络图、测试 XML 和本地构建指针留在忽略目录。以上支持范围不外推为任意 PDF 表单、任意 XFA 或任意阅读器的兼容保证。
