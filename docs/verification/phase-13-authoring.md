# 阶段十三：编写入口、富文本语义与中文排版

- 日期：2026-10-15。
- 范围：两端对齐、可选严格中文禁则、富文本标题／列表、常用编写与输出重载、有界混合报告压力。
- 状态：本地验证通过。

## 结果

普通报告支持自动 ID、默认正文格式、标题、列表、等宽文本表格和原子路径输出。标题与列表的样式、模板变量和外链可穿过语义降级及 DOCX。`JUSTIFY` 在布局阶段分配空隙；`CJK_STRICT` 在原文中检查有限禁则，不改变默认策略或既有字体缺字契约。入口与兼容边界见[编写指南](../authoring-api.md)及 [ADR-0017](../adr/0017-authoring-and-justification.md)。

## 环境与工具

Java 21.0.10 LTS、Gradle 9.2.1、Windows 10.0.26300。独立工具使用项目隔离环境 `build/verification/python-phase13`，按 `scripts/requirements-verification.txt` 安装：pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0、cryptography 50.0.2，配合本机 Poppler、veraPDF 和 OpenSSL。

验证发现共享运行时已升级为 pypdfium2 5.14.0。同一个生产 PDF 在 5.14 中提取出视觉顺序的阿拉伯语／希伯来语，在锁定的 5.13 中保持既有逻辑顺序。PDF 的 ActualText 原文始终完整。最终验收使用隔离锁定版本，保留原文断言；没有通过放宽断言或刷新视觉基线处理这项差异。

## 构建与回归

完整构建、SBOM 和本地 Maven 制品验证通过。共 243 项测试，无失败、错误或跳过；Java 编译采用现有 `-Xlint:all -Werror`。新测试覆盖来源范围、末行与显式换行、无间隙文字、中文标点组合、缺字原文禁则、复杂文字轮廓、富文本模板／DOCX、列表前缀与链接，以及普通／分节原子输出。

执行的主要命令：

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:authoring :examples:textPdf :examples:flowPdf :examples:tablePdf `
    :examples:componentPdf :examples:advancedPdf :examples:production `
    :examples:capabilities :examples:inlineImages :examples:inlineFields `
    :examples:docxFields :examples:mixedColumns :examples:continuousColumns `
    :examples:multiFloats --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

签名样例另外通过 `signerOpenSsl`、`signerCertificate`、`signerKey` 显式提供本地开发签名器。私钥及证书未进入源代码或发布包。

独立验证执行 `verify_authoring.py`、`verify_advanced_pdf.py`、`verify_production.py`、`verify_capabilities.py`、`verify_inline_images.py`、`verify_multi_floats.py`、`verify_continuous_columns.py`、`verify_mixed_columns.py`、`verify_inline_fields.py` 及 `verify_docx_fields.py --word`。Word 样例由 `verify_docx_word.ps1` 在私有 Word 16.0 实例中更新，只操作生成文件。

历史进阶样例 7 页与既有 Poppler 基线零像素差异，未更新基线。PDF/A-2b 通过 veraPDF：144 项规则、10,629 项检查，无失败。新生成的开发签名通过 OpenSSL 完整性验证，篡改拒绝；这不证明证书受信任或长期验证。

## 新样例与最终文件

`examples/build/authoring` 包含一页快速上手 PDF、三页排版 PDF、三页 DOCX 导入 PDF 和一页 OpenType 组件 PDF，共 8 页。排版样例含普通中英文、组合字符与阿拉伯语、富文本标题／列表、外链、页码字段、图片和语义表格。

`verify_authoring.py` 独立解析最终 CID 字体宽度与 TJ 相对推进，核对两端对齐；本机最大 advance 误差低于 0.000001 pt。它还核对轮廓逻辑锚点、来源顺序、5 个外链和图片／字段。该固定样例的 DOCX 往返三页逐像素一致，不代表一般 Word 文档的外观保证。

隔离 Maven 调用方编译并运行新增 Builder、富文本语义块、严格策略及普通／分节路径输出，生成两页新增验收输出。最终 PDF 保留全部来源和 3 个外链，两个入口的固定样例逐像素一致。成功构建目录以 `build/verification/consumer-build-directory.txt` 为准，避免读取旧输出。

所有新布局样例页均经 Poppler 与 PDFium 独立渲染，并检查联系表及完整页面。证据保存在 `build/verification/authoring/report.json`、`contact-1.png`、`contact-2.png` 与各样例渲染目录。

## 压力与进程采样

~~~powershell
& scripts/measure_report_stress.ps1
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_report_stress.py
~~~

共享一个引擎及 PDF 后端，以 2 个工作线程分三轮导出 6 份报告。每份含 40 组正文、中文、拉丁文、列表、富文本标题、双向文字、图像、页码字段和表格。每份 26 页，共 156 页。取消和输出字节超限后，旧目标保持完整，同一调用线程随后成功导出；未残留临时文件。

最终运行 ID 为 `86da895da00f441b93c51006b6efbdb9`。JVM 最大堆为 512 MiB，夹具运行约 16.2 秒；这不包含 Gradle 启动和独立渲染。PowerShell 每 250 ms 采样指定 JVM，取得 59 个样本：工作集峰值约 715.4 MiB，私有字节峰值约 776.1 MiB，句柄采样峰值 485。工作集、私有字节与最大堆是不同指标，不相加，也不是精确连续峰值。

独立验证逐份检查请求标识与元数据隔离、40 个正文标记顺序／唯一性、10 次图片呈现和页码字段。156 页均由两个引擎渲染，6 份逐页概览已人工检查。双渲染使用生产样例已有的 96 DPI 抗锯齿容差；没有更新视觉基线。

证据见 `build/verification/report-stress/process-report.json`、`process.csv`、`verification.json`、`overview-0.png` 至 `overview-5.png`。当前结果是有限工作负载验证，不能证明长期无泄漏、服务并发容量、跨系统性能或完整流式布局。

## 剩余范围

严格禁则是有限子集，两端对齐没有自动最大伸展限制或整段最优断行。标题／列表尚不支持图片和字段。合并单元格续页、单元格注释、连续栏自动平衡／变宽、组件字段、DOCX 完整页区／目录／评论／表单、PDF/UA、可信时间戳及长期签名仍未完成。

复杂组件和页区组合仍需显式配置，公共 API 尚未冻结。本地 Maven 验证不等于外部发布，正式发布流程仍需独立验证。
