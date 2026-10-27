# 阶段八十四验收：Markdown 与 SVG 单文件恢复

本阶段推进 **GAP-24.2 的 Markdown／SVG 内嵌恢复子目标**，同步补充 GAP-24.4 的往返证据。此前两个格式都需额外传递恢复 ZIP；现在一个标准 `.md` 或 `.svg` 即可携带既有模型、字体及许可，重新打开、编辑并导出。原 HTML 便利入口保持，通用 API 复用相同 version 1 封装与准确字节绑定。

## 契约与边界

`XmlModelRecovery.openEmbedded(format, bytes)`、`Saved.embeddedBytes()` 和两个 `writeEmbedded` 重载仅接受 HTML、MARKDOWN、SVG。末尾 Base64 注释在 CommonMark 和标准 SVG 中均不显示；注释以外字节保持不变。外部改字或 SVG 改色会使旧绑定失效，损坏封装／摘要、预算、取消及 I/O 不受损失策略放行。有损捕获不隐藏较丰富的源模型。

当前 Markdown 可显式设置 WARN／IGNORE + DEGRADE 并经 `MarkdownImporter` 导入，重存只包含当前文字。SVG 没有内置矢量→段落／表格读取器；缺少有效恢复信息且未显式提供可信 fallback 时，三动作均不能恢复旧模型。设计见 [ADR-0089](../adr/0089-markdown-svg-embedded-recovery.md)，用法见[恢复指南](../model-recovery-api.md#markdown-与-svg-单文件恢复)。

## 可复现入口

Java 21 与项目 Gradle Wrapper；Python 3.12.14、markdown-it-py 4.0.0、pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、Poppler 26.07.0。沿用已登记的 Liberation Sans 及许可，没有新增模块、Java 运行依赖或素材。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:modelRecovery :examples:embeddedHtmlRecovery :examples:embeddedCommentRecovery --no-daemon --console=plain
```

按[隔离调用方流程](../../scripts/verify_local_consumer.ps1)生成新的成功构建目录；在同一调用方运行 `modelRecovery embeddedHtmlRecovery embeddedCommentRecovery resolvedLibraries` 后执行：

```powershell
python scripts/verify_embedded_comment_recovery.py --consumer-build-directory <本次成功目录>
python scripts/verify_embedded_html_recovery.py --consumer-build-directory <本次成功目录>
python scripts/verify_model_recovery.py --prepare-external
.\gradlew.bat :examples:modelRecoveryExternal --no-daemon --console=plain
python scripts/verify_model_recovery.py --consumer-build-directory <本次成功目录>
```

样例见 `examples/build/embedded-comment-recovery/`，独立报告和渲染见 `build/verification/embedded-comment-recovery/`。CommonMark 渲染为 HTML 后与 SVG 分别在浏览器查看；这是显示验收，不是任意编辑器保存回读。

## 实际结果

| 层次 | 结果 |
| --- | --- |
| 构建与测试 | 全仓 `build sbom publishLocalBundle` 通过，1,471 项测试，0 失败／错误／跳过。11 个新增行为实例验证两格式三轮／资源及实际改字、外部修改／格式冲突、缺失与无读取器拒绝、损坏／重复封装、有损隐藏禁止、预算、取消、借用流和原子目标；原 HTML 行为测试通过 |
| 单文件结构 | 独立检查 12 个文件，包括两格式原始／三轮／编辑／外部修改／隔离调用方，以及 Markdown 的两种非阻塞回退。CommonMark 识别一个注释块；SVG 根后注释不改变元素树；ZIP CRC、清单、模型／字体／许可摘要一致 |
| 三轮与实际编辑 | 两格式三轮后整个内嵌文件字节一致；ORIGINAL 改为 EDITED，调用方改为 CALLER，重存模型不含旧词或未使用源模板。外部 Markdown 改为 EXTERNAL，SVG 改为蓝色轮廓，两者旧绑定均准确失效 |
| 三动作 | 六行策略矩阵：ERROR 拒绝旧绑定；Markdown WARN／IGNORE 导入当前内容，损失数量一致，IGNORE 当前警告为零；SVG 无 fallback 时两种非阻塞动作仍失败，不返回旧模型 |
| PDF | 两格式控制／编辑及调用方共 6 页，pypdf 核对文字和顺序，Poppler／PDFium 双渲染并查看全部页面；改字产生像素变化，未修改的第二行及后续区域像素保持 |
| 浏览器 | 实际查看 Markdown 经 CommonMark 渲染的 HTML 与原 SVG 图像，在 1000px／375px 框架中对照普通、内嵌及编辑文件；正文／轮廓一致，恢复注释不可见，EDITED 显示正确 |
| 本地制品 | 新隔离 Maven 调用方构建、通用调用、伴随恢复及三种格式单文件恢复通过；23 个运行组件、15 个自研库与 15 项 BOM 约束一致，实际分发的 15 个自研 JAR 的 SHA-256 全部匹配当前 SBOM |
| 原恢复回归 | HTML 独立验证器的 7 个单文件及 3 页双渲染通过；伴随包独立验证器的 73 个包、56 行矩阵、49 组 A→B→A、七方向外部重写及 16 页双渲染通过 |

## 后续独立项

PDF、DOCX、PNG 内嵌、一般复杂跨格式模型及真实编辑器重存继续登记。没有有效恢复信息的任意 SVG 语义识别仍未提供；有限语义和浏览器显示证据不关闭整个 GAP-24。
