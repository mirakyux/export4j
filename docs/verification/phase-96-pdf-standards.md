# 阶段九十六：PDF 标准与离线验证

本阶段推进 GAP-11.1／11.2／11.3：离线 PAdES 信任及长期资料、明确语义的 PDF/UA-1、四个选定 PDF/A 等级及已有文件转换。接口和限制见[PDF 标准 API](../pdf-standards-api.md)。

## 可复现入口

从仓库根运行，先生成原始离线 PKI 夹具，再构建与运行隔离调用方。夹具私钥、证据、样例、原始日志和图片全部位于忽略的 `build/`，不加入分发内容。

```powershell
build/verification/venv/Scripts/python.exe scripts/create_pdf_standards_fixtures.py
.\gradlew.bat build sbom publishLocalBundle
pwsh -File scripts/verify_local_consumer.ps1
$standardsConsumerBuildDirectory = (Get-Content build/verification/consumer-build-directory.txt -Raw).Trim()
.\gradlew.bat -g build/gradle-validation -p verification/consumer "-PconsumerBuildDirectory=$standardsConsumerBuildDirectory" pdfStandards --no-daemon --console=plain
build/verification/venv/Scripts/python.exe scripts/verify_pdf_standards.py --consumer-build-directory $standardsConsumerBuildDirectory
```

`verify_local_consumer.ps1` 验证基础调用方；随后显式运行 `pdfStandards`，才会生成本阶段的标准样例。该任务运行 `PdfStandardsConsumer` 与真实私钥的 `PdfPadesConsumer`。独立 Python 环境使用 `scripts/requirements-verification.txt`；veraPDF 由 `--verapdf` 指定，默认相对路径为 `build/verification/verapdf/verapdf.bat`。Linux/macOS 使用对应 Wrapper、Python 和 veraPDF 可执行文件，并通过 `-Pconsumer.verificationPython=<Python 可执行文件>` 指定回调进程所用 Python。

## 自动验收内容

| 范围 | 样例和断言 |
| --- | --- |
| PDF/A | A1b／A2b／A3b／A4f 各一份原始生成及一份已有文件转换；A2b 额外检查 alpha 正样例。每份最终文件独立 veraPDF，核对版本、元数据、字体、附件和转换损失 |
| PDF/UA-1 | 显式语义树、已有标签文件标题／语言修改、真实模型到布局再到标签的跨页表格。逐文件 veraPDF，独立解析阅读顺序、列表、图像替代文本、表头 ID 和内容所有者 |
| PAdES | B／T／LT／LTA、仅 CRL 的 LT、LTA 证据续期，P1／P2／P3 认证 LTA；使用原始离线 CA、签名者和 TSA，pyHanko 验证 CMS、链、RFC3161、DSS、文档时间戳及允许的证据追加 |
| 失败边界 | 撤销、未知、过期证据、不可信链、过期签名证书、篡改字节、缺少 LT 证据、错误时间戳摘要及未来时间戳；失败时旧目标保留 |
| 修改符合性 | 定向测试覆盖三种认证权限的页面修改、表单填写、批注与未知追加；默认资源新增和已有资源重定义分别判断，签名完整性与 DocMDP 语义不混用 |
| 输出验收 | 所有新增最终文件使用 Poppler／PDFium 独立渲染，比较页面数、可见墨迹及联系表；原始哈希、合规 XML 和结果 JSON 保留于本地验证目录 |

后端定向测试 `PdfStandardsTest` 防止错误允许可见页面修改、把合法字段填写当成字段定义修改、静默丢标签及生成禁止的归档透明度／附件配置。证书夹具和时间显式固定，不依赖在线 CA、真实网络时间或机器信任库。

模型样例的独立检查逐项比较完整阅读序列，包括列表标记、图像替代文本、表头和 12 行数据；续页重复表头不能再次进入逻辑顺序。实际辅助阅读器曾暴露图片选择锚点额外朗读为 `x` 的问题，无障碍模型桥现已排除该空占位绘制，并由完整顺序断言阻止回归。

本轮使用重新构建、发布的本地 Maven 制品运行隔离调用方，再执行独立验证：12 份最终 PDF/A／PDF/UA 文件全部通过 veraPDF 1.30.3；9 份签名文件的 CMS、离线链、时间戳及对应 LT／LTA 资料通过 pyHanko 0.33.0。P1／P2／P3 认证 LTA 的证据追加均得到允许的修改结论，篡改及无效撤销证据被拒绝，失败输出保持旧目标。

21 份最终文件共 43 页均完成 Poppler／PDFium 双渲染，并查看全部 15 张联系表；正文、表格、续页、图像及透明度正样例没有发现缺失或裁切。新模型文件有 4 页、33 个 MCID 和 24 个表头关联，完整逻辑顺序通过且不含额外的 `x`。每份文件的 SHA-256、合规 XML、签名及渲染结果保存于忽略的 `build/verification/pdf-standards/`。

## 无障碍阅读器

已实际使用 NVDA 2026.3 与 Microsoft Edge Beta 打开 `ua1.pdf`：H1／H2 标题、列表、表格行列、`Item`／`Quantity` 对应值、图像替代文本 `Blue inventory marker` 及两页阅读顺序均可读取。此步骤读取真实阅读器暴露给辅助技术的内容，不以文本提取冒充。

`ua1-retitled.pdf` 已实际读回两页：H1／H2、列表、表头和值、第二页 `Paper`／`8`、图像替代文本 `Blue inventory marker` 和结束段全部可读取，修订后的默认语言生效。修复后的新 `ua1-model.pdf` 已实际读回第一页的标题、两类列表、`Blue model marker`、表头和首行值；此前额外的 `x` 已消失。模型完整四页的结构、独立标准及逻辑顺序检查通过；模型续页和末行 `Item 11`／`Count 11` 的 NVDA 记录未捕获，不能宣称该文件全部四页的实际读屏通过。生成样例及已有标签文件均已完成两页实际读屏，模型入口的标题／列表／替代文本／表格和占位修复也有实际记录，已覆盖 GAP-11.2 的生成与已有标签处理验收目标。四页模型的全部逐页读屏属于本组证据的覆盖限制，不把缺失记录当作通过。

## 已验证边界

- PDF/UA 选择 UA-1，复杂组件、混排图片及块单元格需要明确绑定；交互 OBJR、结构拥有的 Form 和 UA-2 不由这些样例推断支持。
- PDF/A 分别选择 A1b／A2b／A3b／A4f。A4f 必须有 EmbeddedFiles，PDF 2.0 头和旧 Info 字典按该版本处理。已有文件转换明确光栅化，禁止以“保留”策略掩盖全文搜索、矢量和标签损失。
- PAdES 使用显式离线信任和证据。仅完整直接 CRL、受支持 OCSP 和 SHA-256 时间戳进入长期验证；不发现远程撤销资料，不证明操作系统／阅读器信任库已信任某张测试根证书。
- DSS 续期保留既有 VRI 和证据流引用；文档时间戳移除 PDFBox 默认空外观，避免 P1 下将证据更新误判为外观修改。验证包含这些问题的独立回归断言。
- P3 认证文件的证据追加已用 pyHanko 检查；该工具会提示其一般差异分析不完整支持所有 P3 批注修改。因此一般 P3 批注修改的断言仍以明确的本地分类子集为界，不声明任意外部文件均可判断。
- 构建、制品、独立工具、视觉检查、辅助阅读器和正式发布是不同结论。本记录只描述实际执行的对应层次，正式发布另行授权。
