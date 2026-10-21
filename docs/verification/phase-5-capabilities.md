# 公共文本、模板与受控导出验收

日期：2026-10-14。范围：公共文本、模板、受控导出与隔离制品验证。

## 实现范围

| 方向 | 本批结果 |
| --- | --- |
| 公共文本 | 默认段落按需使用 JDK OpenType；语义段落、富文本单元格、页区及脚注复用中立轮廓，分页保留逻辑文字与来源 |
| 字体 | 静态 OTF/CFF、有界 TTC／OTC 成员选择；显式字体与整簇回退，保持缺字策略 |
| 业务模板 | 点路径变量、布尔条件、词法作用域循环、集合表格，以及快照／展开预算 |
| 行内与表格 | 富文本单元格、被动 HTTP／HTTPS／mailto 外链、有界自动列宽、末页平衡 |
| 执行控制 | 协作取消、截止时间、阶段进度、输出字节预算、原子文件替换、PDF 后端临时缓存 |
| PDF | AES-256、受限静态 PDF/A-2b、增量 detached CMS 签名入口 |
| DOCX | 富文本单元格与标准外部超链接关系；自动列宽通过自有扩展属性往返 |

调用方式及完整限制见[能力指南](../capabilities-api.md)，架构依据见 [ADR-0009](../adr/0009-shared-text-templates-and-controlled-export.md)。本批未新增 Java 运行依赖或项目模块。

## 构建与回归

环境：Windows、Microsoft OpenJDK 21.0.10、Gradle 9.2.1。继续使用隔离的 `build/gradle-validation`，没有更改全局 Gradle 缓存。

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:capabilities :examples:production :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf --no-daemon --console=plain
powershell -File scripts/verify_local_consumer.ps1
~~~

完整构建通过，163 项测试，0 失败、0 错误、0 跳过。生产与测试源码继续采用 `-Xlint:all -Werror`；Javadoc、源码包和本地 Maven 制品均生成。后续收紧归档元数据的非法 XML 字符校验后，PDF 测试、能力样例、SBOM 和本地发布重新通过。

测试覆盖组合字符／双向文字的逻辑来源、分页轮廓保留、CFF 与集合成员边界、占位警告范围、模板条件／作用域／预算、自动列宽与平衡栏，以及取消、超时、输出超限和后端失败后的目标文件保留及临时文件清理。PDF 另验证加密权限、归档不兼容输入的写流前拒绝，以及签名回调失败时的源文件和借用流所有权。

原有综合 PDF 的 7 页继续与持久 Poppler 基线比较，像素差异全部为 0，未更新基线。生产批次 16 页和此前实际 Edge 保存的 7 页副本重新通过结构与双渲染器检查。本次复用既有真实阅读器证据，没有把脚本写回称为新的人工操作。

构建日志位于忽略的 `build/verification/gaps-final-build.log`，归档元数据复检为 `gaps-final-pdf.log`，独立回归为 `gaps-final-advanced.log` 和 `gaps-final-production.log`。

## 独立样本检查

工具：Python 3.12.14、pypdf 6.10.0、PDFium／pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0、cryptography 50.0.2、Poppler 26.07.0、veraPDF 1.30.3、OpenSSL 3.5.9。工具及字体许可见[依赖记录](../dependencies.md)和[字体资源](../../assets/fonts/README.md)。

执行基础样例任务会生成 6 份 PDF 和 1 份 DOCX。签名样例额外需要调用方显式配置 OpenSSL、开发证书和私钥路径：

~~~powershell
.\gradlew.bat -g build/gradle-validation :examples:capabilities -PsignerOpenSsl=openssl -PsignerCertificate=build/verification/signing/dev-cert.pem -PsignerKey=build/verification/signing/dev-key.pem --no-daemon --console=plain
& .\build\verification\verapdf\verapdf.bat --format xml --flavour 2b examples/build/capabilities/archive.pdf > build/verification/capabilities-verapdf.xml
python scripts/verify_capabilities.py --openssl openssl --verapdf-report build/verification/capabilities-verapdf.xml
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
~~~

证书、私钥与 veraPDF 安装只保存在忽略的 `build/verification` 内。其他环境需要自行准备；省略签名器参数时不生成签名样例。独立脚本的 `--openssl` 和 `--verapdf-report` 均为可选项，省略后不能声称完成对应验证。

| 文件（`examples/build/capabilities/`） | 页数 | 结果 |
| --- | --- | --- |
| `shared.pdf` | 1 | BODY／CELL／HEADER／NOTE 逻辑标签、组合字符与阿拉伯语正确；1 个外部 URI 注释 |
| `balanced.pdf` | 1 | BAL-01～11 有序且不重复；后续正文位于平衡双栏下方 |
| `invoice.pdf` | 1 | 客户、确认状态和两个 SKU 由模板生成 |
| `cff.pdf` | 1 | Source Sans 3 静态 CFF 轮廓与逻辑文字正确 |
| `archive.pdf` | 1 | veraPDF PDF/A-2b 合规：144 条规则、10,635 项检查通过，0 失败 |
| `encrypted.pdf` | 1 | AES-256、错误密码拒绝、读者密码解密、权限正确；解密绘制命令及渲染与原单一致 |
| `signed.pdf` | 1 | 原始 PDF 是增量文件的不变前缀；ByteRange 覆盖完整签名版本；OpenSSL 验签通过，篡改拒绝，渲染与原单一致 |
| `shared.docx` | — | python-docx 与 ZIP/XML 独立读取富文本单元格及标准外链关系 |

全部 7 页使用 Poppler 与 PDFium 渲染，拼图及公共混排页完整图已人工复查，未见裁切、重叠或缺字方块。5 份不同内容 PDF 的双渲染器均值通道误差为 0.087～0.609，差异像素比例为 0.040%～0.715%（高斯模糊半径 1，96 DPI），低于既定阈值。加密版与签名版分别与原单逐像素一致。脚本不更新视觉基线。

普通字形串改用一次 `TJ` 加定位修正后，PDFium 可以完整提取生产样例标题 `OpenType / Unicode`，不再被轮廓文字锚点交错；这只是当前样本证据，不代表通用阅读顺序、搜索、选择或无障碍兼容。

最终摘要、文件哈希、渲染图及签名范围保存在 `build/verification/capabilities/report.json`。veraPDF 原始报告为 `build/verification/capabilities-verapdf.xml`；脚本检查报告不早于当前归档样例，防止误用旧结果。

## 本地制品

本地发布继续限定在 `build/repository`。12 个库、BOM、源码／Javadoc、许可及实际运行制品 SBOM 均已生成。隔离消费者仅通过 Maven 坐标解析，新增使用模板、受控原子导出和静态 CFF，公共接口不暴露第三方类型。

旧消费者输出的 `consumer.docx` 被本机其他进程占用，首轮 `clean` 因无法删除文件而失败。本地验证脚本改为每次使用独立构建目录，保留已打开的旧文件；独立目录中的消费者构建、运行与制品解析已通过，日志为 `build/verification/gaps-final-consumer-success.log`。输出路径以该日志中的 `Consumer outputs` 为准。

## 尚未完成

本次未关闭所有长期能力缺口。行内图像／动态域／行内引用、连续分栏／多浮动区／合并单元格拆页、竖排／可变字体、DOCX 图像／页区／合并表格／交互映射、PDF/UA、认证签名与 PAdES 长期验证仍需独立实现。Word／LibreOffice 外观、其他操作系统和 JDK、多阅读器的新样本交互、持续压力及进程 RSS／资源释放也没有完整证据。

PDF 临时缓存只处理后端流，IR、Display List 与资源仍驻留内存；取消不能强制抢占自定义回调或原生调用。开发自签证书证明签名完整性，不证明证书信任或时间戳可信。不得据本批样例宣称完整流式引擎、完整 Word 兼容、PDF/UA 或生产容量保证。
