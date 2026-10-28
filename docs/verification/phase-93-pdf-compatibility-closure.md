# 阶段九十三：外部 PDF 兼容路径完整验收

本阶段完成 GAP-10.6 既有剩余清单：CID／字典编码源字体映射、传统 Type1／静态 Type3、Form 原对象文字编辑、显式裁切／透明组合、更多 ExtGState，以及被动图层／标签。每类结构分别验证读取、保留、编辑、渲染和语义恢复的实际结果，不以新增 Builder 或不支持时拒绝作为完成证据。设计见 [ADR-0096](../adr/0096-pdf-compatibility-paths.md)，完整入口矩阵见[图层、标签与兼容路径](../pdf-passive-structures-api.md)。

## 既有清单与实际交付

| 原剩余项 | 实际交付 | 明确边界 |
| --- | --- | --- |
| CID／字典编码映射 | 简单字体的 BaseEncoding／Differences；水平 Type0 的 Identity-H 或内嵌一至二字节 CMap，结合 ToUnicode 和调用方 TrueType 构建 CIDToGIDMap；保持原字宽、编码和内容流 | 渲染及光栅脱敏显式登记准确 BaseFont，外观替代报损；不隐式进入文字编辑或语义恢复。竖排、多码点、歧义及不合法映射拒绝 |
| 传统 Type1／Type3 | PDF FontFile 的 Length1／Length2／Length3 段检查、CharString／Subr／seac 执行保护；静态 Type3 CharProcs、资源、字形和实际绘制检查 | Type3 不伪造为可编辑正文，可显式整页恢复为图片；损坏程序、缺字、循环及预算失败不能靠策略放行 |
| Form 原对象编辑 | 按真实 Do 调用匹配文字，只为命中调用链克隆流和资源；共享、重复、嵌套、本地及继承资源均验证；保留源 Matrix／BBox／裁切和未命中实例 | 普通不透明白名单状态内修改；透明状态、标记内部及带结构所有者的 Form 按既有编辑策略保留原页，不克隆破坏标签引用 |
| 显式裁切／透明组合 | 可直接渲染的裁切、文字裁切、alpha／blend、透明 Form 和软蒙版页，可按 PDF_STRUCTURE／DEGRADE 整页恢复为流图片并转换四种格式 | 图片保留可见外观，文字、矢量和结构不再可编辑；SKIP_BLOCK 仍可整页跳过；不提供任意复杂页面的可编辑原版重建 |
| 更多 ExtGState | 保留普通不透明语义白名单；渲染验证 Font、透明度、混合、软蒙版及受支持绘制状态，不能表达的有效页面走显式图片降级 | 软蒙版须有合法透明组；未知状态、不可确定外观或损坏资源不能伪支持；不是新增通用图形模型的透明合成能力 |
| 图层 | 显式 opt-in 后读取 OCG／OCMD 默认 VIEW，处理四种成员策略、可见性表达式、嵌套标记及 Form／图片 OC；完整源文档的无关编辑保留配置和引用 | OFF 层文字不作为可见正文，隐藏绘制仍执行状态；渲染／导入输出固定视图并报告图层损失，不是图层配置编辑器 |
| 标签 | 验证 PDF 1.7 静态结构树、MCID／MCR／OBJR、页／流／对象所有者、ParentTree 及名称／角色／类映射；读取及无关编辑保留原树 | 语义恢复丢弃标签并报告，Alt／ActualText 不直接当正文；不声称 PDF/UA、标签阅读顺序重建或任意标签内部编辑 |

图层／标签通过 `PdfAdapter.builder().allowPassiveStructures(true)` 接受，默认关闭。完整源文档保留编辑和创建新页树的复制／组合具有不同条件；`copy()`、`merge()`、`composeObjects()` 仍拒绝图层／标签的新页树重映射，避免悬空引用或静默丢弃。带被动批注的标签输入使用支持该批注的 `readObjects`／渲染／对象编辑／导入入口，不扩大静态 `read()` 的交互边界。

## 策略、预算与失败

默认 ERROR。WARN 与 IGNORE 对同一输入执行相同处理，IGNORE 仍返回全部实际损失。`PDF_STRUCTURE / DEGRADE` 将整页恢复为一个流图片，报告正文、矢量和结构不可编辑；图层另报 `PDF_LAYERS / DEGRADE`，标签另报 `PDF_TAGS / DISCARD`。源页尺寸保持，流图片按源宽高的 95% 显示以容纳段落行框，此布局变化明确报告 `PDF_LAYOUT`。`rasterDpi` 默认 144，最高 1200，受实际像素额度约束。

输入图、全部流解码、源与替代字体、CMap 展开、CharProc、实际 Form／透明组／字形调用、操作符、字符、图片像素及编码输出分别有界。源执行流与 q 栈共同受深度限制；重复调用仍计执行工作，不能依靠资源缓存绕过。尝试语义恢复失败后已经消耗的工作不退还，再降级只能使用剩余额度；丢弃的临时语义候选和损失不混入最终整页降级报告。

权限、签名、非法类型／引用、损坏字体、实际缺字、结构循环、预算、取消和 I/O 继续失败。字体映射不扫描系统字体，也不覆盖损坏的嵌入字体；被动结构回指例外仅在完整图预检及专用验证成功后适用于本输入。输出候选编码完成才原子替换，失败保留旧目标及源字节。

## 独立输入与输出证据

输入由 pypdf 和有界的静态字体程序生成，不使用 Export4j／PDFBox 生成源 PDF。真实字体字节来自已登记验证资源，生成器不扫描系统字体。调用方只从本地 Maven bundle 解析公开坐标；独立验证使用 pypdf、Poppler 和 PDFium，不以同一后端读回代替输出检查。

| 验收组 | 生成器／坐标调用方／独立验证器 | 核对内容 |
| --- | --- | --- |
| 字体 | [生成器](../../scripts/create_pdf_extended_font_fixtures.py)／[调用方](../../verification/consumer/src/main/java/consumer/PdfExtendedFontConsumer.java)／[验证器](../../scripts/verify_pdf_extended_fonts.py) | 源编码和字宽、共享 CID 子对象隔离、Type1／Type3 字形、显式替代与嵌入控制、脱敏像素、歧义及非法映射、实际损失和旧目标 |
| Form 编辑 | [生成器](../../scripts/create_pdf_form_edit_fixtures.py)／[调用方](../../verification/consumer/src/main/java/consumer/PdfFormEditConsumer.java)／[验证器](../../scripts/verify_pdf_form_edit.py) | 七个有效源、十五份输出、二十二处实际修改；源／资源／字体摘要、共享调用隔离、未选页、位置和颜色，另有十一项拒绝 |
| 图层／标签 | [生成器](../../scripts/create_pdf_passive_fixtures.py)／[调用方](../../verification/consumer/src/main/java/consumer/PdfPassiveConsumer.java)／[验证器](../../scripts/verify_pdf_passive.py) | 五个接受源、十次光栅输出、十次导入、二十次 PDF／DOCX 转换、五次无关编辑保持及二十七项拒绝；OFF 层排除、结构图和实际损失对账 |
| 复杂恢复 | [生成器](../../scripts/create_pdf_complex_fixtures.py)／[调用方](../../verification/consumer/src/main/java/consumer/PdfComplexRecoveryConsumer.java)／[验证器](../../scripts/verify_pdf_complex_recovery.py) | 六个有效源、四十八次 PDF／DOCX／HTML／Markdown 转换及十二项拒绝；精确图片像素、单页 PDF、无伪造可搜索源正文、实际损失和旧目标 |

Form 编辑对未选页面要求像素一致；选中页面因 PDF 浮点组合产生的极小栅格边缘差异，另外使用原字体／资源摘要、八 ULP 坐标、一个像素内的双向墨迹覆盖、墨量和颜色检查。删去未命中 KEEP 文字或平移十二像素的负控均拒绝，不能以白底平均误差掩盖丢字。字体共享 CID 样例分别使用 AB／BA 映射，确认每个 Type0 根具有独立映射子对象，不串改另一个根。

## 验证结果

- 全仓汇总 **1,589 项测试、166 个测试集，零失败／错误／跳过**；最终 `build sbom publishLocalBundle` 通过。
- 全新隔离 Maven 调用方的标准输出、依赖解析与七个相关 PDF 任务全部通过；十五个自研库、十五条 BOM 约束和二十三项运行 SBOM 的制品与哈希核对通过。
- 字体组十九份输入、八份显式映射 PNG、两份嵌入程序输出、十份脱敏 PDF、两份 Type3 图片恢复、两次歧义 CMap 策略跳页、二十九项拒绝及十六个失败旧目标保护通过。
- Form 编辑九份、图层／标签十份、复杂恢复九份输入，与字体组合计四十七份新输入文件。四组分别生成十六／四十四／二十二／十八页双渲染，共 **一百个 PDF 页、二百张独立页面图、二百三十八个联系图块、四十一张联系图**，全部变化页面已检查。最终重生成的对应联系图与已检查版本摘要一致。
- 原文档保持、关闭层不泄露、四格式图片像素、WARN／IGNORE 实际损失一致、默认拒绝、非法输入、预算、取消及旧目标保护均按各组验证器核对，没有修改源字节。

各组原始报告和联系图位于忽略的 `build/verification/`，不写入共享文档。外部 PDF 语料和本阶段支持路径有明确边界，以上数字不外推为任意文件兼容率。

历史回归同时运行[阶段九十一](phase-91-pdf-source-fonts-and-graphics-state.md)源字体／图形状态、[阶段九十二](phase-92-pdf-form-recovery.md)普通 Form 导入，以及字体兼容、渲染、语义恢复、内容编辑、优化、生产和能力七组既有独立验证。没有更新视觉基线。

## 复现

Java 21、Gradle 9.2.1；Python 使用 `scripts/requirements-verification.txt` 的固定依赖，pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0。Poppler 与 PDFium 双引擎输出按 144 DPI 检查。从仓库根运行，其他平台将 Wrapper 改为 `./gradlew`；`<consumer-build-directory>` 为本次新目录相对 `verification/consumer` 的位置。

```powershell
python scripts/create_pdf_compatibility_fixtures.py
python scripts/create_pdf_form_fixtures.py
python scripts/create_pdf_extended_font_fixtures.py
python scripts/create_pdf_form_edit_fixtures.py
python scripts/create_pdf_passive_fixtures.py
python scripts/create_pdf_complex_fixtures.py
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities :examples:pdfRaster :examples:pdfSemanticImport :examples:pdfContentEditing :examples:pdfOptimization
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build run resolvedLibraries pdfCompatibility pdfFontCompatibility pdfFormImport pdfExtendedFonts pdfFormEdit pdfPassive pdfComplexRecovery --refresh-dependencies
python scripts/verify_pdf_extended_fonts.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_form_edit.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_passive.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_complex_recovery.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_compatibility.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_form_import.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```

隔离调用方全部成功后才更新 `build/verification/consumer-build-directory.txt`，再运行其余既有独立回归。生成物、测试 XML、工具原始日志及本地目录指针均留在忽略目录。

GAP-10.6 按以上完整清单及入口矩阵验收；GAP-14.2 的跨平台／阅读器矩阵仍部分支持。本阶段没有新增人工 Word 保存或多系统阅读器验收，不代表任意 PDF 无损互转、完整标准合规、长期资源稳定或恶意输入硬沙箱。跨定位／样式的文字匹配、图片实例替换及选择性脱敏分别继续归 GAP-10.7／10.8／10.9。
