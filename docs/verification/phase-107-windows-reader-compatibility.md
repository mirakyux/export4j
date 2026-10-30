# 阶段一百零七：Windows 应用兼容与保存回读

本阶段完成 GAP-14.2 在本轮 Windows 环境可执行的 Word／PDF 阅读器交互验收，并修复验收中复现的两处兼容问题。环境为 Windows x64、Microsoft OpenJDK 21.0.10+7 LTS、Word 16.0.20527.20088 和 WPS Office 12.1.0.29158。桌面应用只用于独立验收，生成、导入、编辑和转换不依赖 Office、浏览器、打印机驱动或打印服务。

## 兼容问题与修复

### DOCX 的旧版布局模式

此前生成的 DOCX 没有在设置部件声明现代布局模式。Word 将其按旧版规则打开，标题显示“兼容性模式”，另存时提示升级文件格式。这是布局兼容设置问题，不是包损坏。

普通导出和原生语义重建现在均生成 `word/settings.xml`、唯一部件关系及内容类型，声明 `compatibilityMode=15`，对应 Word 2013 及后续版本的现代布局模式。保留可选奇偶页区及字段设置的标准顺序；原生写入仍不强制打开时全局重算。`editPackage` 保留源包原有模式，不因局部文字编辑升级旧文档。

回归覆盖普通／原生无字段无页区的文件、设置顺序、部件关系唯一性及旧模式原包编辑后设置字节不变。独立 ZIP／XML 检查确认模式值，Word 实际打开及另存均不再出现格式升级提示。契约见 [DOCX 原生语义](../docx-semantic-api.md)。

### PDF 阅读器添加的应用元数据

WPS 保存填写后的 PDF 时添加标准 `PieceInfo` 应用元数据，原对象入口将该目录键作为未知结构拒绝。对象读取与原文档编辑现在接受并保留该被动元数据；完整对象图及流仍执行活动内容拒绝、对象数量、深度和解码预算。

新增三项行为回归：保留私有流准确字节；拒绝元数据内嵌脚本及未知目录键；拒绝超过解码预算的压缩私有流。修复前两项失败，修复后三项通过。严格静态复制入口没有扩大，库也不解释或更新应用私有缓存，见 [PDF 对象编辑](../pdf-object-editing-api.md)。

## Word 实际操作与独立回读

前台操作使用本轮生成的 `verification/consumer/build/gap142-windows-modern/output/docx-semantic-complete/semantic-edited.docx`，另存为 `build/verification/gap142-windows/word-ui-modern.docx`。

| 动作 | 实际结果 |
| --- | --- |
| 打开与搜索 | 标题无兼容性模式；搜索 `READY-NAME` 精确命中一次，辅助接口所选文字准确 |
| 编辑 | 在普通内容控件实际输入前缀，得到 `WINDOWS-UI-READY-NAME` |
| 保存与重开 | 本地另存无格式升级提示；关闭后重新打开，修改值和搜索结果保持 |
| 第 1 页 | 完整页内可见控件、变换图片、锚定文本框、评论和修订 |
| 第 2 页 | 偶页页眉、四层嵌套表及 `DEEP-READY` 可见 |
| 第 3 页 | 默认页眉、`DEFAULT-BAND-READY` 及页码 3 可见 |
| 第 4 页 | 第二节标题、`SECTION-TWO-READY`、首页页眉及页码 4 可见 |

保存包使用本轮最终隔离消费者的实际 JAR 回读，核对 16 个项目 JAR 和 31 个运行 JAR 的摘要；在 headless 且拒绝加载打印类的 JVM 中通过，打印类加载为 0。结果为 2 节、2 绘图、14 控件、1 目录、1 评论；深表文字、评论正文及旧式表单当前值保持。

本轮没有前台编辑文本框，只验证其保留和显示。Word 保存后仍产生 334 项未映射标记／属性诊断，完整保留在回读报告中；语义回读不是任意 OOXML 的无损转换。结构验收使用 OpenXML SDK 3.3.0、Office 2019 规则，四份最终重建包及 Word 保存包均为 0 错误。保存包因应用共享锁使用逐字节相同的快照验证。

## PDF 实际操作、独立结构及渲染

前台首先打开本轮互转样例 `docx-to-pdf-warn.pdf`，确认 `CORPUS-EDITED` 与 `CORPUS-KEEP`；搜索前者精确命中一次。实际拖选第一行出现高亮及选择工具条。应用辅助接口没有提供选中文字，所以只登记视觉选择成功，不将其记为复制文字准确性验收。

随后打开同批 `consumer-object-source.pdf`，在真实 AcroForm 文本字段 `caller` 中将 `OLD` 改为 `WINDOWS-UI-PDF`，本地另存至 `build/verification/gap142-windows/pdf-ui.pdf`。关闭后按保存路径重新打开，字段值仍可见。

独立 pypdf 检查确认 1 页、1 字段、1 widget、正确的文本字段类型及当前值，以及非空的 156 字节 Form 外观流。Poppler 与启用表单渲染的 PDFium 均显示正确值，两张图均已复查；平均通道差为 0.149284，达到差异阈值的像素比例为 0。PDFium 先初始化表单再渲染，避免把未绘制交互外观误判为值丢失。

同一保存文件经本轮最终纯 JAR 的 `PdfAdapter.readObjects` 回读成功，字段值准确，打印类加载为 0。不把这一个样例推广为任意阅读器保存结果均可编辑。

## 自动回归与最终制品

| 检查 | 本轮结果 |
| --- | --- |
| 完整构建 | `build sbom publishLocalBundle` 通过，1,792 项 Java 测试，0 失败、0 错误、0 跳过 |
| 候选 API | 16 库／670 公开类型，0 不兼容声明、0 新增、0 第三方类型泄漏 |
| 最终隔离消费者 | `verification/consumer/build/gap142-windows-final` 的构建、基础样例、原生 DOCX、HTML/CSS、固定语料及运行依赖解析通过 |
| 固定互转／拒绝语料 | 16 条路径、32 份输出、15 组共 73 次控制检查；独立结构及 8 页 PDF 双渲染通过，全部页面已复查 |
| 无打印依赖 | 16 个项目 JAR、31 个运行 JAR 摘要匹配；生产打印 API 引用为 0；3 个负控制真实拒绝；三个消费者分别生成 148／8／30 个文件，打印类加载均为 0 |
| 原生字体专项 | 本轮较早的 `verification/consumer/build/gap142-windows` 生成 45 份 PDF／49 页、26 个塑形运行／643 字形及 41 页独立 SVG；独立塑形、轮廓、查询、双渲染和选定归档验证通过 |

字体专项在兼容修复前执行，相关字体实现未变化；没有将其记为最终消费者目录重新执行的结果。查询及选择证据来自 PDFium API，不等于桌面阅读器逐项交互。

最终固定语料输入清单 SHA-256 为 `194027f466f9f3e3e6be0078d28c022f00624e1ed15beee1a10bebeb70d15566`。

| 桌面应用保存文件 | SHA-256 |
| --- | --- |
| Word 保存包 | `14ecd3e4b5f5784467a63d5842b973b4e63b06c2edb6672bbb35830da8c44952` |
| PDF 保存文件 | `6eef66b26b74beb10ef8d6517de3445fbdc35641e8a22a9b3af7af3d91f62952` |

## 复现入口与证据

安装[固定验证依赖](../../scripts/requirements-verification.txt)、Poppler、Java 21 及对应独立验证工具后，从仓库根目录执行。Linux／macOS 使用 `./gradlew`；固定输入生成器的默认目录 `build/verification/compatibility-fixtures` 及以下专用输出目录应未存在，重复运行前先保存旧证据，并使用干净的生成目录和新的输出目录名。

```powershell
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-validation build sbom publishLocalBundle
python scripts/create_compatibility_corpus.py
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/windows-reader-check build run docxSemanticComplete htmlCssLayout compatibilityCorpus resolvedLibraries --refresh-dependencies
python verification/verify_docx_semantics.py verification/consumer/build/windows-reader-check/output/docx-semantic-complete
python scripts/verify_compatibility_corpus.py --consumer-build-directory verification/consumer/build/windows-reader-check --output build/verification/windows-reader-corpus
python scripts/verify_driverless.py --consumer-build-directory verification/consumer/build/windows-reader-check --output-directory build/verification/windows-reader-driverless
```

桌面应用操作需要独立执行，并使用真实保存文件回读；仅运行上述自动命令不能复现前台交互结论。Word 保存包可用消费者 `DocxSemanticCompleteConsumer` 的 `windows-ui-read-back <saved-docx-path>` 入口回读；该模式检查本节声明的修改值。PDF 保存后用对象读取入口检查字段值，调用及预算配置见 [PDF 对象编辑](../pdf-object-editing-api.md)。

本轮完整构建与消费者日志分别为 `build/verification/gap142-final-build.log`、`build/verification/gap142-final-consumer.log`。独立报告集中在 `build/verification/gap142-windows/`：`corpus-final/report.json`、`driverless-final/report.json`、`opentype/report.json`、`published-read-back-final.json`、`word-ui-read-back.json`、`pdf-ui-read-back.json`；实际应用观察及文件摘要单列在 `ui-evidence.json`。原始日志和输出保留在忽略目录，不随源码提交。

## 未执行边界与缺口状态

- 本轮浏览器工具不允许访问本地文件，因此 Edge PDF 以及 HTML／SVG 的浏览器前台交互没有执行；独立结构、渲染或 PDFium API 结果不能替代这些操作。
- 本轮未执行其他操作系统或新增远端 CI。提交 `cc132e7` 的三平台历史成功只对应[阶段一百零五](phase-105-publication-and-compatibility.md)的提交及工具版本。
- 本轮代码变化后的字节没有重新执行签名演练；[阶段一百零六](phase-106-security-and-compatibility-corpus.md)的签名摘要仍只描述其历史批次。当前通过的是构建、本地 Maven 制品及独立消费。
- 没有正式签名身份、Central 上传或真实远端 Maven 消费结论，也没有执行正式发布。

Windows 本轮可执行的应用子集已验收；更广平台及阅读器矩阵仍归 GAP-14.2，正式分发仍归 GAP-14.3。未完成独立项仍为 GAP-13、GAP-14.2、GAP-14.3、GAP-22，不新增编号，不由有限样例关闭完整兼容范围。
