# 阶段八十五验收：单页 PNG 内嵌模型恢复

本阶段推进 **GAP-24.2 的 PNG 单文件恢复子目标**，同时补充 GAP-24.4 的单页往返证据。单页 PNG 现在可以在 `IEND` 前携带既有模型、显式字体及许可，重新打开、编辑并重新导出；原 PNG 像素和标准读取器显示保持不变。原 `PNG_ZIP` 多页输出仍使用伴随包。

## 契约与边界

`XmlModelRecovery.openEmbedded("PNG", bytes)`、`Saved.embeddedBytes()` 和 `writeEmbedded` 只接受标准单页 PNG。`RasterBackend.singlePage()` 要求布局结果恰好一页，沿用显式 DPI／字体、交互 extras 和像素预检；多页、交互数据或超出像素额度在写出前失败，不重新分页。

恢复包是一个唯一的私有辅助 `exJM` PNG 块，负载为既有 XML／资源 ZIP，不使用 Base64。恢复清单绑定移除该块后的完整 PNG 字节，块数量、文件大小、ZIP 编码／展开／条目／像素均有界。入口检查 PNG 签名、IHDR／PLTE／连续 IDAT／IEND、CRC、未知关键块、动画、尾随数据及恢复块位置；不解压验证任意像素数据。损坏、预算、取消、I/O 或摘要冲突不能由 WARN／IGNORE 放行。

外部改像素／元数据即使保留旧块也会使绑定失效；普通图像重存可能删除块。缺失／过期默认拒绝，WARN／IGNORE + DEGRADE 必须提供读取当前 PNG 的可信 fallback，否则仍失败。当前没有 PNG 到语义模型的内置读取器，也不宣称任意编辑器保存回读。有损捕获只写普通 PNG，不隐藏更丰富的模型。

## 可复现入口

Java 21、Gradle Wrapper；独立 Python 环境使用 Pillow 12.3.0、pypdf 6.10.0、pypdfium2 5.13.0、Poppler 26.07.0。沿用已登记的 Liberation Sans 及 OFL 许可，没有新增运行依赖或素材。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:embeddedPngRecovery --offline --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/validation-embedded-png-phase85 clean build run modelRecovery embeddedHtmlRecovery embeddedCommentRecovery embeddedPngRecovery resolvedLibraries --refresh-dependencies --offline --no-daemon --console=plain
python scripts/verify_embedded_png_recovery.py --prepare-external
.\gradlew.bat -g build/gradle-validation :examples:embeddedPngRecoveryExternal --offline --no-daemon --console=plain
python scripts/verify_embedded_png_recovery.py --consumer-build-directory verification/consumer/build/validation-embedded-png-phase85
```

同一隔离调用方还运行了 HTML、Markdown/SVG 和伴随恢复回归。独立检查 7 个 PNG、6 行外部三动作矩阵、3 页 PDF，确认三轮整个内嵌 PNG／模型／字体／许可一致，真实编辑删除旧文字，外部改像素和去块不恢复旧模型；Pillow 对照普通 PNG 与去块 PNG 的全部像素，Poppler／PDFium 双渲染 3 页。实际分发 ZIP 中 15 个自研 JAR 的 SHA-256 与当前 SBOM 一致。

## 实际结果

| 层次 | 结果 |
| --- | --- |
| 构建与测试 | 全仓 `build sbom publishLocalBundle` 通过，1,480 项 JUnit 测试，0 失败／错误／跳过；PNG 定向测试、单页后端边界及隔离 Maven 调用方通过 |
| 文件与像素 | `exJM` 唯一且紧邻 IEND；移除块得到 `plain.png` 全部字节；Pillow 解码普通／内嵌／去块图像像素一致；外部改像素实际变化 |
| 模型与资源 | 三轮模型、字体、许可和内嵌文件字节一致；EDITED／CALLER 模型不含 ORIGINAL 或未使用模板；资源仅登记的模板、字体和许可 |
| 失败策略 | CRC／长度／顺序／重复／损坏 ZIP／摘要／尾随／截断／动画／预算均拒绝且不调用 fallback；缺失／过期六行 ERROR／WARN／IGNORE 均不返回旧模型 |
| PDF 输出 | 控制／编辑／调用方共 3 页，pypdf 核对文字顺序，Poppler／PDFium 双渲染通过；编辑改变目标像素，未修改区域保持 |
| 回归与制品 | HTML、Markdown/SVG 独立验证通过；23 个运行组件、15 个自研库、15 项 BOM 约束和实际分发 JAR 校验通过 |

## 未覆盖范围

PNG 入口只验证块结构，不等于任意 PNG 像素流的完整安全解码；没有 PNG 像素到语义模型的自动识别。PDF／DOCX／PNG 页 ZIP 内嵌、自动发现恢复文件、真实编辑器保存回读和一般复杂跨格式往返继续按 GAP-24 推进。
