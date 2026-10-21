# 行内图像与 DOCX 媒体验收

## 范围和环境

本次补齐行内 PNG／JPEG、图片和文字共享换行／双向定位／分页、语义单元格图片续页、图片外链、模板和脚注锚点中的图片保留，以及标准 DOCX 媒体读写。调用方式见[图像 API](../inline-images-api.md)，架构决定见 [ADR-0010](../adr/0010-inline-image-layout-and-docx-media.md)。

环境为 Windows、JDK 21、Gradle 9.2.1；独立工具沿用[上一批记录](phase-5-capabilities.md)中的 Python、pypdf、python-docx、PDFium、Pillow、Poppler、veraPDF 和 OpenSSL。未新增 Maven 依赖、模块或外部图片。示例图片由项目代码生成，资源与许可见[依赖记录](../dependencies.md)。

## 构建和回归

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:production :examples:capabilities :examples:inlineImages --no-daemon --console=plain
powershell -File scripts/verify_local_consumer.ps1
python scripts/verify_inline_images.py
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
python scripts/verify_capabilities.py
~~~

可选签名样例和 veraPDF／OpenSSL 的显式参数沿用[上批命令](phase-5-capabilities.md#独立样本检查)。本机本轮实际使用这些参数重新生成签名、验签，并重新验证最终归档文件；开发私钥与证书仅保留于忽略的构建目录。

完整构建及本地 SBOM／隔离 Maven 发布通过，178 项测试，0 失败、0 错误、0 跳过。初次综合运行日志为 `build/verification/inline-build.log`；随后增加合计图片预算回归，最终全量构建为 `inline-build-final.log`。生产和测试源码继续采用 `-Xlint:all -Werror`，源码包与 Javadoc 均生成。

新增 15 项测试覆盖资源字节防御性复制、静态格式和声明像素预算、UTF-16 标记、链接一致性、模板替换后的索引、双向位置、相邻图片断行、固定行框／宽度失败、分页时图片与链接矩形、重复表头、脚注保留、标准 DrawingML 往返、包媒体去重、外链／路径／变换拒绝、流所有权及写流前预算失败。合计资源超限在允许损失模式下也必须终止，不能由缓存绕过。

隔离调用方仅使用 Maven 坐标和 BOM，编译运行图像 API，并导出和严格读回图片 DOCX；依赖仍为 12 个库和 4 个第三方运行制品。每次使用新的构建目录，成功后记录 `build/verification/consumer-build-directory.txt`；生产脚本读取此目录或显式 `--consumer-build-directory`，避免误读旧结果或删除被阅读器占用的文件。最终日志为 `inline-consumer-completed.log`。

## 独立图片检查

`scripts/verify_inline_images.py` 使用独立 PDF／OOXML 工具，不调用 Java 后端读回证明正确性。

| 输出（`examples/build/inline-images/`） | 结果 |
| --- | --- |
| `mixed.pdf` | 4 页、37 次图片绘制、20 个图片链接；包含透明 PNG、JPEG、阿拉伯语与组合字符 |
| `mixed.docx` | python-docx 识别 36 个标准行内图像；2 份去重媒体；36 份替代文字、19 个标准图片链接 |
| `roundtrip.pdf` | 4 页、37 次图片绘制、20 个图片链接；全部页面的 Poppler 像素与原 PDF 一致 |
| `preview.svg` | 37 个嵌入图片节点，全部页面复用相同绘制几何 |
| `preview.zip` | 4 页 PNG，每页 880 × 960，显式 144 DPI；与 PDF 独立渲染比较通过 |

DOCX 保存原始语义图片 36 张，PDF 额外重复一次表头图片，因此为 37 次绘制；同理图片链接从 19 个增加为 20 个。FLOW-01～18、CELL-01～12 顺序正确且正文不重复。PDF 的 ActualText 保留 U+FFFC 图片位置与原逻辑文字，不代表 PDF/UA。

脚本解析 PDF 内容流的 CTM 与图片资源，逐张核对物理页、宽高、坐标和源像素，误差小于 0.002 pt；每个 URI 注释矩形匹配对应图片。透明图片具备 soft mask。独立 ZIP/XML 检查媒体格式、绘制 ID、替代文字和图片链接关系。

原 PDF 和往返 PDF 共 8 页均由 Poppler／PDFium 渲染。双渲染器均值通道误差为 0.944～1.209，差异像素比例为 0.446%～1.080%（模糊半径 1，96 DPI），低于既定阈值；144 DPI 静态 PNG 缩放后与 PDF 的均值通道误差为 1.048～1.717。全部原 PDF 页面拼图及混排／表格页完整图已人工复查，未见图片丢失、越界或与文字重叠；往返页面逐像素一致。

最新独立日志为 `build/verification/inline-independent-completed.log`，结构与渲染摘要为 `build/verification/inline-images/report.json`，拼图为同目录 `contact-*.png`。脚本不更新视觉基线。

## 原有能力与基线

生产样例的 16 页、既有 Edge 实际保存副本的 7 页、能力样例的 7 页均重新通过独立结构和双渲染器检查。Edge 副本复用既有人工证据，本轮没有重新操作阅读器。归档 PDF 再次通过 veraPDF PDF/A-2b：144 条规则、10,635 项检查，0 失败；OpenSSL detached CMS 验签和篡改拒绝通过。对应日志为 `inline-production-completed.log`、`inline-capabilities.log` 与 `capabilities-verapdf.xml`。

原综合样例 7 页继续通过结构／表单与基线阈值检查。5 页像素差异为 0，第 4、5 页的表格编号字形存在少量差异：均值通道误差分别为 0.007064、0.009889，差异像素比例为 0.0146%、0.0204%。已复查局部图并另一次生成，当前两次输出逐像素一致；尚未确定与旧基线差异的原因，不能称为全部页面零差异。未更新持久基线。日志为 `inline-advanced.log` 与 `inline-repeat-comparison.log`。

## 边界

图片与文字共享固定行框，尺寸过大明确失败；不提供动态行高、自动缩小、行内 SVG 或远程资源获取。模型与 DOCX 保留替代文字，PDF 尚无图片标签树；Word／LibreOffice 实际外观和点击行为仍待验证。记录分量变化保留旧构造器的源代码调用方式，调用方应重新编译，尚未冻结二进制 API。

动态行内域／交叉引用／脚注锚点、当前页连续分栏、多浮动区、合并单元格拆页、DOCX 页区／合并表格／完整交互映射、竖排、可变字体、PDF/UA 和 PAdES 长期验证继续独立推进。长时间压力、其他系统／JDK、进程 RSS 与释放测量也没有完整证据。本轮没有创建 remote、Git 提交／推送、远程 CI 或正式发布。
