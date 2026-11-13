# 混合内容与双目标公式验收

本记录验证当前开发工作区新增的源内容编排、标题／列表混合对象、标准 DOCX 字体与对象行距、显式公式文字字体和短生命周期引擎入口。与已发布 RC3、此前字体补丁实验及旧消费侧输出分开登记；本批不测量性能。

DOCX 由 `DocxAdapter` 使用 Java 直接生成标准 OOXML 包；生产导出不调用 LibreOffice、Word 或打印服务。下文 LibreOffice 仅用于独立打开和渲染验收生成物。

## 验收契约

- Java 与 XML 源使用同一次编译会话，跨请求、模板、循环累计输入、语法树、工作、表格、公式和路径额度；未执行请求的命名空间也有上限。
- 标题、两类列表、段落与表格保留原子对象、真实 UTF-16 锚点和链接；相同公式的多次出现分别绑定。遗漏、重复或篡改出现身份明确拒绝。
- 视觉模型保留真实轮廓；DOCX 优先原生数学，显式候选逐目标对账，不用文本降级掩盖真实样本的对象丢失。预算和取消不能降级。
- Markdown 内 HTML 为独立开关；代码保留空白／换行并保护美元符号，HTML 活动内容与未登记资源继续拒绝。
- 表格检查真实物理网格、合并跨度和文字顺序。普通 `td` 不猜表头；仅表头表保持完整内容。原生重复表头公式不强制转换为块格。
- DOCX 使用标准字体槽及对象最小行距，无消费方重开 ZIP 修补；严格回读、OOXML 结构及独立阅读软件渲染分开验证。
- 所有公式文字字体来自显式静态文件快照，检查许可且不注册系统字体；字体缓存清理不使在用字体失效，provider 不永久持有已解析引擎。

## 可复现入口

从仓库根目录使用 Java 21 和 Gradle Wrapper：

```powershell
.\gradlew.bat build sbom publishLocalBundle apiCheck `
  :examples:math :examples:contentBlocks :examples:production :examples:capabilities `
  :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf `
  :examples:advancedPdf :examples:authoring :examples:xmlTemplates `
  :examples:markdownImport :examples:htmlImport
powershell -NoProfile -File scripts/verify_local_consumer.ps1
```

隔离消费者的 `richContent` 使用仓库已有许可字体、生成的小图和公开短源，通过本地 Maven 坐标调用公共 API。外部样本可向 `consumer.RichContentConsumer` 传入显式资源配置、Markdown 源文件和输出目录；资源配置格式见该类 Javadoc。私有文献、字体副本、工具程序和原始日志只保留在忽略的本地验收目录，不成为项目资源。

本地签名分发验证使用 `scripts/verify_signed_distribution.py` 的临时验证密钥与 `0.0.0-verification...` 坐标；不上传远端、不使用正式发布身份。

## 独立内容检查

真实源先由独立 CommonMark／HTML 解析清点非公式文字、物理表格网格、合并跨度、公式次序和原图，再与公开消费者产生的语义清单及标准 OOXML 分别对账。公式清单按出现位置核对，不按相同 TeX 去重；真实样本不允许 TEXT 候选掩盖公式对象丢失。

PDF 内容流依照 `ActualText` 和字体 `ToUnicode` 重建逻辑文字，并检查公式出现及图片绘制。第三方提取器可能忽略 `ActualText`、按几何排列合并表头或将行末连字符标记为特殊值；这些差异必须通过实际内容流、字形状态和字框解释，不能直接删除源字符或忽略内容顺序。独立渲染同时检查正文、公式和原图的真实位置。

DOCX 原生公式不仅检查 `m:oMath` 数量，还检查数学运行的有效字号及字体控制属性。对象段落使用 `atLeast` 不能保证相邻单元格中的内容不会重叠，必须再检查阅读软件实际输出的字框。原图分别核对源资源、标准包内容及渲染裁切；PDF 中原样嵌入的 JPEG 使用 DCT 原始字节核验，不以提取工具重新编码的 JPEG 作为像素不变证据。

## 本次本地结果

2026-11-13 的修正后工作区通过构建、API 兼容性检查和本地签名分发验收。当前 JUnit 结果为 2,637 项通过，其中 DOCX 213 项；构建复用了仍有效的测试结果，不表示全部测试重新执行。17 个库 JAR、BOM、SBOM、87 个制品签名及签名篡改拒绝通过，Gradle 元数据和仅 Maven POM 两种隔离消费均通过。临时验证坐标为 `0.0.0-verification-rdx`，不属于正式版本或远端发布。

两篇真实源由本次消费分发包直接编译，结果如下；本批与此前 17 页消费侧布局使用不同编写配置。

| 项目 | 中文源 | 英文源 |
| --- | --- | --- |
| 直接 PDF／DOCX 独立渲染页数 | 5／5 | 7／7 |
| 表格／真实锚格／合并格 | 5／165／21 | 5／214／5 |
| 公式出现 | 29 | 0 |
| PDF 公式表示 | 29 个矢量对象 | 不适用 |
| DOCX 公式表示 | 27 个原生数学对象、2 个显式图片候选 | 不适用 |
| 原图 | 0 | 1，标准包字节和实际显示均核对 |

直接 PDF 的完整正文、物理格网、合并跨度、公式来源和原图通过独立检查，全部 12 页经 PDFium 与 Poppler 渲染并复查。POM 调用方与 Gradle 调用方的语义清单、DOCX 字节、解码后的 PDF 页内容流及全部 12 页 PDFium 像素一致；不要求含文件标识的 PDF 字节一致。

DOCX 使用 LibreOfficeDev `27.2.0.0.alpha0`、构建 `83d264f2f0a6f2944d524c9a55cb2a0764d12255` 独立打开并渲染，随后由 PDFium 和 Poppler 检查全部 12 页。27 个原生公式的 269 个运行均明确写出宿主 8 pt 字号，实际阅读器基准字号一致。标准表格结构与 MCID 将每个公式绑定到原始物理格，再用真实格线与独立字框核对；379 个锚格文字一致，未发现公式越格或与旁字正面积重叠。移入邻格、越格和中心点在外但边缘相交的负控制均被拒绝。

图片检查按实际绘制次数核对，不能用 PDF 资源清单代替出现次数。两幅公式图片保留透明度后的像素，原 JPEG 保留编码字节；所有图片矩形均经双引擎可见区域检查。缺失、重复及被白色覆盖的负控制均被拒绝。独立阅读器输出显式使用无损图片、不缩减分辨率及标准标签；这些是验收工具配置，不进入生产导出流程。

旧 LibreOffice `26.8.3.1` 会把本样本明确声明的公式字号按 12 pt 渲染，不能据此把标准输出改成错误字号。新版包含上游修复 `111a2ffc8be0faf4820fb8d1c2b7acb7ab5e1ba3`；其开发版身份和旧版失败证据均保留。这证明指定阅读器的兼容性，不证明所有 LibreOffice 或 Microsoft Word 版本都相同。

本地报告保存在 `build/verification/rdx/distribution-report.json`，以及 `build/verification/literature-content-20261113/` 下的 `formal-pdf-final/verification.json`、`formal-docx-tagged/verification.json`、`formal-docx-tagged/owner-geometry-verification.json` 与 `formal-pom-final/pixel-equality/verification.json`。文献和工具不进入共享仓库。

## 判断边界

每页 PDF 必须分别由 Poppler 与 PDFium 完整渲染。DOCX 独立解析标准 OOXML，并由 LibreOffice 渲染全部页；它不能代替 Microsoft Word 打开／编辑／保存验收。新直接编译路径允许按当前字体与格式重新分页，不能拿旧 17 页或旧 31 页冒充本批结果，也不要求新布局与旧消费侧像素相同。

自动结构检查、视觉复查、局部签名包验证、提交和远端发布是不同结果。公式语法仍为有界子集，Auto 公式列及 DOCX 尚不支持的布局容器继续明确拒绝，完整边界见[混合内容 API](../rich-content-api.md)及 [ADR-0117](../adr/0117-single-source-math-representations.md)。
