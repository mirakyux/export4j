# 阶段一百零三：Word 原生语义、编辑与显式投影

本阶段针对 GAP-12.1／GAP-12.2 建立适配器有类型模型，覆盖浮动图片、真实文本框、图片变换、评论、插删／移动／格式修订、现代内容控件、legacy 表单和 TOC。API 见[原生语义](../docx-semantic-api.md)，架构选择见 [ADR-0103](../adr/0103-typed-native-docx-semantics.md)。新包由模型重建，不以原包保留代替语义编辑。

## 行为验证

`DocxSemanticTest` 与已有 DOCX 回归覆盖以下独立行为：

- 标准 `wp:anchor`、`wps:txbx`、裁剪／旋转／翻转、评论关系、`sdtContent`、`ffData` 和 TOC 字段的真实读写。
- 手写且无 Export4j 私有元数据的 OOXML 样本：评论跨段范围、六种修订、标准命名范围配对的移动、内容控件、legacy 字段和 TOC，随后执行实际编辑、重建及回读。
- 接纳删除、拒绝插入后检查所有输出 XML，原文字和审阅标记不残留；拒绝格式修订恢复实际旧字符／段落属性。
- 顺序文字编辑、文本框内容修改、控件值与约束校验、UTF-16 范围重定位、越界与代理项边界、三种动作的真实损失报告。
- 图像转换核对实际输出像素和尺寸；裁剪后的图像先按显示矩形缩放，再旋转，避免只调整宽高或按原始像素纵横比旋转。
- 评论端点进入富文本控件时保留单个控件身份；移动源使用普通 `w:t`，删除源使用 `w:delText`，审阅与评论日期包含合法秒字段；绘图身份不重复。
- 图片控件覆盖真实行内图片，拒绝与浮动图片的无效组合。组合样本同时包含独立的行内图片控件和浮动图片。
- 分组控件使用 `contentLocked` 并允许内嵌值控件编辑；普通或跨范围替换不能绕过分组锁。Word 保存的已支持 MC 分支与合法再生绘图身份可以读回，Fallback 不重复，安全检查仍覆盖所有分支。
- 制表布局按 `TAB_LAYOUT` 实际降级为空格；容不下行内图片的固定行高按 `INLINE_IMAGE_LINE_HEIGHT` 实际扩大。两项均覆盖拒绝／警告／忽略、正文／表格／文本框及实际格式，而不放宽字符或字体校验。
- legacy 当前值与默认值独立，真实下拉当前索引写入 `ddList/result`。原生输出不附加全局更新及普通字段脏标记，指定 TOC 的更新标志真实往返；实际页码缓存重建后清脏，普通编辑保留原标志和旧缓存，由调用方显式刷新。
- 旧 `read` 在未编辑专有对象上仍按严格模式拒绝或返回诊断；宏、ActiveX、嵌入对象、签名、保护和悬空评论不因损失策略而放行。

## 发布坐标调用方

`consumer.DocxSemanticCompleteConsumer` 只依赖发布坐标。它生成 `docx-semantic-complete/`，包含以下真实文件：

| 文件 | 验证目标 |
| --- | --- |
| `semantic-source.docx` | 从类型化模型创建原生对象与所有六种修订 |
| `semantic-edited.docx` | 顺序修改控件、表单、文本框和四层内表中的段落，并更新目录缓存 |
| `semantic-decided.docx` | 接纳／拒绝所有修订，实际删除源文字，保留其他原生对象 |
| `semantic-portable.docx`／`.html`／`.markdown`／`.pdf` | 显式投影后转换，收集与预览一致的真实损失 |

组合源包括 11 个现代控件和 3 个 legacy 表单，两个 section 共用默认／首页／偶数页页区，并包含真正的 PAGE 字段。最终样例共四页，前三页分别呈现首页／偶数页／默认页眉，第四页为第二节首页；目录的第二节页码为 4。表格含四层 `p → tbl → p`、前后普通段落和交替有／无边框，文本框内部也包含实际表格。既有 16 层边界、网格、页区和样式验收继续适用。

独立 `verification/verify_docx_semantics.py` 检查 ZIP CRC、唯一名称、所有内部关系目标、两个真正浮动对象、图片变换值、文本框表格、控件／表单、评论完整范围、TOC／PAGE、两节页区共享关系、四层表、实际编辑文字与全部 XML 的删除残留。另核对下拉当前索引／默认索引、文本默认值和当前值、无自动宽泛更新、合法日期及移动／删除源元素。该检查独立于 Java 读回，不把模型数量断言当作 OOXML 输出证据。

## Word 应用验收

`verification/verify_docx_semantics_word.ps1` 启动独立 Word 实例，在私有副本中记录原生对象数量，实际修改纯文本控件和文本框，定向更新 TOC／计算字段，保存 DOCX，再重新打开保存结果并核对原生对象与实际修改文字。打开和重开均核对三种 legacy 输入，包含与当前值不同的默认值。脚本有界等待并记录自有 PID；已有 Word 实例保持，不按进程名清理。

Word API 直接枚举出 9 种现代控件；重复节中的 2 个项通过 `RepeatingSectionItems` 单独验证，对应源模型的 11 个现代控件。它们与另外 3 个 legacy 表单分别计数，不通过降低阈值替代类型检查。专项应用动作已验证 2 节、2 个浮动对象、1 条评论、6 类修订、1 个目录；实际更新 11 个计算字段并保留 3 个输入字段，控件／文本框／深表编辑与 legacy 当前值、独立默认值在保存重开后均保持。

Word 输出的 `word-saved.docx` 再由调用方的 `read-back` 模式读取，除有类型绘图、控件、目录和评论数量外，还递归核对 Word 实际修改后的控件和文本框文字、旧值消失、两节、深表文字、评论正文及三种 legacy 表单当前值。报告记录完整诊断及特性分类，不依赖 Word 另存时可能重写的身份值。Word 应用的打开、编辑、字段更新、保存、重开和 PDF 导出均独立记录；程序化应用验收与人工界面复核区分。

回读 Word 保存结果时，固定页区几何通过明确的 `RegionGeometry` 配置提供，因为 Word 可能移除 Export4j 私有高度元数据。复选框展示字体、日期存储／日历、样式图及未映射展示属性在 `ALLOW_LOSS` 下保留逐项诊断。当前值、原生对象和编辑结果通过，不代表这些属性无损。

## 字段更新与 PDF 导出控制

Word 16.0 的最小成对控制组证明，带自动脏标记的 PAGE 会触发宽泛重算，将 legacy 文本当前值还原为默认值；移除该非模型标志后，原始当前值与独立默认值都保留。单独移除全局 `updateFields` 或仅给用户字段声明非脏都不足以解决。完整组合随后通过定向 TOC／PAGE 更新、保存重开和语义回读；没有加字段锁，也没有把当前值改成默认值。

Word PDF 验收现使用强类型 Office 互操作调用 `Document.ExportAsFixedFormat`，显式传递扩展参数。原 PowerShell 后期绑定调用在复杂样本及 Word 自建最简控制文档上均超时；相同打印设置和导出选项下，强类型调用分别成功导出控制文档及最终四页组合源。因此修复位于验收脚本的 COM 调用方式，不以安装打印机驱动作为解决条件，也不把此前超时解释为 DOCX 损坏。

脚本在独立后台 Word 实例中，通过 [`Page.EnhMetaFileBits`](https://learn.microsoft.com/en-us/office/vba/api/word.page.enhmetafilebits) 取得全部四页原生 EMF，再转为 PNG；保存包、EMF、PNG 和 PDF 的 SHA-256 记录在验收报告中。`verification/verify_docx_word_rendering.py` 校验这些绑定、四页实际页区、TOC 页码、修改后文字、深表和当前表单值，并用 Poppler／PDFium 分别渲染 Word PDF 与语义投影 PDF。四页原生图和两个 PDF 的全部八页已逐页复查；这是后台应用原生渲染及图像复核，不登记为前台人工界面操作。

默认验收同时请求原生页面和 Word PDF；`-SkipPdf` 是可选分支，仍执行原生页面渲染及应用编辑／保存／重开，明确记录 `pdfRequested=false`、`pdfExported=false`。该分支也已独立回归，不能用它替代默认模式的 PDF 验收。

## 无打印服务的运行验证

Export4j 的生成、导入、编辑及转换不依赖打印机驱动、打印服务或 Office／浏览器进程。上述 Word 自动化及 Office 互操作程序集仅用于独立兼容性验收，不进入生产依赖或调用链。

`scripts/verify_driverless.py` 从本次隔离调用方的 distZip 提取真实运行库，核对全部 31 个依赖 JAR 的解析摘要，包括 16 个项目库，不以生产源码类目录替代发布制品。生产字节码没有 `java.awt.print`、`javax.print` 或 `sun.print` 引用。验证用 Java agent 拒绝这些命名空间中所有类的加载；`PrinterJob`、`PrintServiceLookup` 和 `PrintJob2D` 三项负控制均实际被拒绝。

在 `-Djava.awt.headless=true` 且打印类禁止加载的条件下，基础调用方、完整 DOCX 原生语义调用方及 HTML/CSS 调用方均成功，分别产生 148／8／30 个文件，覆盖 PDF、DOCX、SVG、PNG、HTML、Markdown 及辅助证据，打印类加载尝试均为零。Word 保存包也在相同限制及同批发布 JAR 下回读成功。三平台 CI 已增加该门禁；当前结论来自本次实际运行，远端矩阵结果另行登记。

## 最终批次结果

原实现批次的隔离调用方目录为 `verification/consumer/build/validation-gap121418-4bd160c289/`，统一构建的 1,783 项测试通过，其中 DOCX 模块 145 项、无失败／错误／跳过；`build sbom publishLocalBundle` 及该批完整调用方构建与原生样例通过。2026-10-29 的最终补验目录为 `verification/consumer/build/validation-gap12-closure-20261029/`，重新构建调用方及四页组合样例，完成下表全部证据；补验未改动生产实现，未重复执行全仓测试。

| 证据 | 实际结果 |
| --- | --- |
| 独立 OOXML 检查 | edited／decided 的 ZIP、关系、真实对象、两节页区、四层表和删除残留通过；分别保留 6 类修订／0 条修订 |
| Word 应用编辑／保存／重开 | 9 类现代控件与 2 个重复项、3 个 legacy 输入、2 个浮动对象、1 条评论和 TOC 通过；实际文字、下拉当前值／默认项、文本当前值／默认值均保持 |
| Word 保存结果语义回读 | 2 节、2 个绘图、14 个控件／表单、1 个目录、1 条评论及所有实际修改文字通过；400 条未映射展示属性诊断完整保留 |
| 回读运行库来源 | 从同批调用方 `distZip` 提取 31 个实际运行 JAR，逐个匹配 `resolved-library-sha256.txt`，含 16 个项目库；只用这些 JAR 执行回读，无源码类目录覆盖 |
| OOXML 标准校验 | OpenXML SDK 3.3.0／Office2019 对 source／edited／decided／portable／Word 保存包五个文件校验均为零错误 |
| Word PDF 导出 | 强类型 Office 互操作成功导出四页，实际页码、目录第二节页码 4、三类页眉及修改文字通过 |
| Word 原生页面及视觉复查 | 四页原生 EMF／PNG 与保存包绑定，逐页复查通过；与 Word PDF 的独立渲染比对通过，不冒称前台人工界面操作 |
| PDF 独立渲染 | Word PDF 和语义投影 PDF 共八页分别经 Poppler／PDFium 渲染并逐页复查；沿用平均通道差 < 4／255、变化像素比例 < 0.06 的阈值 |
| 无打印服务运行 | 16 个生产 JAR 打印 API 引用为零；三项负控制拒绝；无桌面且禁用打印类加载的三入口生成／转换及 Word 保存包回读通过 |
| 不请求 Word PDF 的分支 | 独立执行 `-SkipPdf`，应用动作及四页原生渲染通过，PDF 请求／导出标志均为 false |

最终应用、回读与原生页面报告为该补验目录下的 `output/docx-semantic-complete/word-acceptance.json`、`word-read-back.json`、`word-page-rendering.json`；独立渲染及无打印服务报告位于 `build/verification/gap12-word-closure-20261029/independent-rendering/report.json` 和 `driverless-final/report.json`。它们与原始文件均留在忽略目录，公共文档只保留复现方法和结果边界。GAP-12.1／12.2 的上述明确子集均已验收。

## 复现

仓库根目录，Java 21 与 Gradle Wrapper。Word 应用验收另需 Windows、Windows PowerShell、已安装的 Word 及 Office 主互操作程序集；独立渲染需固定 Python 验证依赖及 Poppler。这些是验收工具条件，不是使用 Export4j 的条件。以下 `<unique-validation-directory>` 由每次验收创建，不复用旧制品。

```powershell
.\gradlew.bat :export4j-adapter-docx:test build sbom publishLocalBundle --no-daemon --console=plain
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<unique-validation-directory>" build docxSemanticComplete resolvedLibraries --refresh-dependencies --no-daemon --console=plain

$nativeOutput = "verification/consumer/build/<unique-validation-directory>/output/docx-semantic-complete"
python verification/verify_docx_semantics.py $nativeOutput
powershell -NoProfile -File verification/verify_docx_semantics_word.ps1 -InputPath "$nativeOutput/semantic-edited.docx" -OutputDirectory $nativeOutput -TimeoutSeconds 120
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<unique-validation-directory>" docxSemanticComplete --args=read-back --no-daemon --console=plain
python verification/verify_docx_word_rendering.py $nativeOutput --output-directory "build/verification/<unique-validation-directory>/rendering"
python scripts/verify_driverless.py --consumer-build-directory "verification/consumer/build/<unique-validation-directory>" --output-directory "build/verification/<unique-validation-directory>/driverless"
```

只运行不含 Word PDF 的分支时，在 Word 脚本命令后添加 `-SkipPdf` 并使用独立输出目录；该分支不执行要求 Word PDF 的后续渲染检查。不要把应用保存验收的成功标志解释为未请求的 PDF 已通过。

原始 JSON、日志、Word 保存结果、PDF 和渲染只保留在忽略的构建目录。新语义的安全拒绝、有限 OOXML 语法、跨格式实际损失和 Word 重排边界，以[API 说明](../docx-semantic-api.md)为准。正式远程分发、跨平台应用行为和任意外部 Word 文件兼容性不由这些固定样例推导。
