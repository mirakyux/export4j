# 阶段九十一：显式 PDF 源字体与不透明图形状态

本阶段推进 GAP-10.6 的两个独立交付单元，并为 GAP-14.2 增加可复现外部语料。分别扩展渲染／光栅脱敏与语义恢复，不修改通用输入拒绝规则。决策见 [ADR-0094](../adr/0094-explicit-pdf-source-fonts-and-passive-graphics-state.md)。

## 显式源字体

`PdfRasterRequest.sourceFont(exactBaseFontName, PdfFont)` 与 `PdfRedactor.sourceFont(...)` 使用调用方静态 TrueType 替代无嵌入程序的简单 Type1／TrueType。只接受 StandardEncoding、WinAnsiEncoding、MacRomanEncoding 三种名称型编码和可确定的原字宽；非标准字体须有非符号描述符及明确字宽。

缺省编码与固定 AFM 字宽只用于十二个确切的拉丁标准字体名称。Arial 等别名不享有该特例，须提供显式编码、字宽与描述符。Symbol／ZapfDingbats、CID、字典编码、传统 Type1 程序及其他未支持组合保持各自拒绝边界。

映射在构造源 PDFont 前执行，不注册全局字体映射器，不扫描系统字体。原内容流、编码、ToUnicode 和字宽保持；字形轮廓可能变化，通过 `RENDER_FONT_MAPPING / DEGRADE / EXPLICIT_SOURCE_FONT_SUBSTITUTION` 报告。默认 ERROR，WARN／IGNORE 同处理且均保留实际损失，源输入不变。

源快照与实际加载程序共同限制总输入，单字体限制解码流字节；映射后用全新预检累计完整文档的解码、对象和资源额度。实际缺字、范围外字码、损坏或矛盾声明仍失败。字体字节和配置快照、跨请求隔离、取消及失败旧目标保护均单独验证。

三种编码还使用高位字码控制：Standard 的 `0xE1 → Æ`、WinAnsi 的 `0x80 → €`、MacRoman 的 `0x80 → Ä`。核对原声明和源字宽，再将实际渲染与显式嵌入 Unicode 字体、按原字宽定位的控制图比较，防止 ASCII 样例掩盖编码误用。

## 不透明 ExtGState 语义恢复

恢复器接受 `Type`、`LW/LC/LJ/ML/D/RI/SA`、`CA/ca=1`、直接名称 `BM=Normal/Compatible` 及 `SMask=None`。实际引擎执行图形状态，q／Q、文字颜色和位置保持；路径仍通过原 `PDF_VECTOR_GRAPHICS / DISCARD` 报损。

字典使用操作内身份缓存，属性、dash 数组及引用检查受工作预算限制。扫描完整内容流；即使已遇到不支持状态，后续缺失资源或畸形 `gs` 也必须失败。透明度、其他混合模式、软蒙版、Font 和未知属性继续按 `PDF_STRUCTURE / SKIP_BLOCK` 拒绝或跳过整页；Form 与裁剪限制保持。

回归先证明旧实现将正常状态页全部跳过，再验证恢复后的 `BEFORE/RED/AFTER`、原始来源、跨内容流、三动作、状态恢复、畸形属性、资源缺失、取消，以及共享大字典重复三百次的有限检查。

## 独立输入与输出检查

[create_pdf_compatibility_fixtures.py](../../scripts/create_pdf_compatibility_fixtures.py) 以 pypdf 独立生成十二份、十八页输入；不使用 Export4j 或 PDFBox 造源。包含未嵌入 Helvetica、非标准字体及自定义字宽、相同编码／位置的嵌入控制、两种普通图形状态，以及透明／混合／Font／未知属性／缺失资源／畸形状态。

嵌入控制保存调用方的确切 TrueType 字节。标准字体控制采用独立声明的已用 AFM 字宽，非标准控制故意使用不同于字体原生度量的字宽。未嵌入源的本机阅读器替代图不作为正确性基准。

[PdfCompatibilityConsumer](../../verification/consumer/src/main/java/consumer/PdfCompatibilityConsumer.java) 只从本地发布的 Maven 坐标运行：

- 四张映射 PNG 及准确图像 ZIP，四份真实光栅脱敏 PDF；
- 普通图形状态经实际编辑输出 PDF／DOCX／HTML／Markdown，WARN／IGNORE 共十六次转换；
- 四种不支持状态各以两种动作跳页，共八次，只有控制页进入恢复模型；
- 十二次预期拒绝，覆盖默认 ERROR、错误准确字体名、缺失资源和畸形状态，失败保留旧目标。

[verify_pdf_compatibility.py](../../scripts/verify_pdf_compatibility.py) 独立核对字体程序／声明／源摘要、来源框、颜色、正文唯一性、预检／实际损失以及 IGNORE 保留损失。脱敏检查新文件无源文字／字体／历史，选区像素实际清除，未选文字带像素不变。

映射图逐行检查墨迹数量、边界和双向字形覆盖：允许一像素抗锯齿差异，边界误差至多两像素（144 DPI 下为 1 pt），未匹配墨迹比例小于 5%。空白图、水平移动 12 px 及误用目标原生字宽的控制均被拒绝；不能只靠白底页面的全局平均误差放行。

## 验证结果

- 全仓 **1,533 项测试，零失败／错误／跳过**；新增十项字体映射及七项图形状态回归。
- 最终 `build sbom publishLocalBundle` 与相关示例通过；全新隔离调用方通过标准输出、`pdfCompatibility` 和原 CFF `pdfFontCompatibility`。十五个自研库、十五条 BOM 约束及二十三项运行 SBOM 的制品和哈希核对通过。
- 新组合完成四张映射 PNG、四份脱敏 PDF、十六次四格式转换、八次跳页、十二次拒绝；十二页 PDF 双渲染得到二十四张页面图，连同四张映射图组成二十八项、五张联系图，均已逐页查看。
- 嵌入字体兼容、渲染、语义恢复、内容编辑、优化、生产及能力七组独立回归通过，没有更新视觉基线。

## 工具与复现

Java 21、Gradle 9.2.1，Windows；Python 工具由 `scripts/requirements-verification.txt` 固定：pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0、markdown-it-py 4.0.0。PDF 使用 Poppler 26.07.0 与 PDFium 以 144 DPI 独立渲染。

命令从仓库根运行，其他平台将 `.\gradlew.bat` 替换为 `./gradlew`。Python 使用已安装固定依赖的隔离环境，`<consumer-build-directory>` 是本轮新隔离目录。

```powershell
python scripts/create_pdf_compatibility_fixtures.py
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities :examples:pdfRaster :examples:pdfSemanticImport :examples:pdfContentEditing :examples:pdfOptimization --no-daemon
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build run resolvedLibraries pdfCompatibility pdfFontCompatibility --refresh-dependencies --no-daemon
python scripts/verify_pdf_compatibility.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```

旧验证器从忽略的 `build/verification/consumer-build-directory.txt` 读取本轮成功目录；仅在隔离调用方成功后更新该指针，再运行字体、渲染、语义恢复、文字编辑、优化、生产和能力回归。生成文件、页面、报告及原始日志保留在 `build/`，不提交个人路径或机器信息。

## 完成边界

源字体映射只用于渲染／光栅脱敏，不扩大文字编辑与语义恢复的字体范围。普通不透明 ExtGState 不等于 Form 内编辑、透明混合或标签恢复。更广字体、Form、图层／标签仍归 GAP-10.6；跨平台及更多阅读器证据继续归 GAP-14.2，两项大缺口保持部分支持状态。
