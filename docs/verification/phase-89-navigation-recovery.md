# 阶段八十九验收：注释、引用与显式目录重建

本阶段推进 **GAP-24.4 的注释／引用组合**：两节、三处标题、脚注／尾注、题注、行内与独立交叉引用，恢复后编辑文字、注释及目标，再重新导出。PDF／DOCX／HTML／Markdown 完成三轮目标恢复、三轮 XML 控制及 16 组 A→B→A；目录由调用方从当前标题显式重建，运行时目录配置没有自动持久化。

## DOCX 转换修复

原统一转换对 Caption／独立 CrossReference 没有策略映射，允许降级也会在底层编码失败；显式丢弃 Note 文字后，正文 NOTE_MARKER 仍导致 DOCX 导出失败。两项回归先复现旧故障，再修复共同转换入口：

| 功能 | 显式处理 | 实际结果 |
| --- | --- | --- |
| `CAPTION` | WARN／IGNORE + DEGRADE | 保留 ID、文字与段落格式；去掉题注类别及自动编号，记录 `CAPTION_REPLACED_WITH_UNNUMBERED_TEXT` |
| `CROSS_REFERENCE` | WARN／IGNORE + DEGRADE | 保留 ID、前缀及格式，输出 `[unresolved reference]`，记录 `REFERENCE_REPLACED_WITH_UNRESOLVED_TEXT` |
| `NOTES` | WARN／IGNORE + DISCARD | 原有注释文字丢弃继续报告；显式 NOTE_MARKER 另记 `NOTE_MARKER_REMOVED`，保留前后正文及原文 UTF-16 单字符范围 |

默认 ERROR 在准备阶段拒绝；PRESERVE 等无映射处理仍拒绝。直接 `DocxAdapter.write` 的严格边界保持。这里没有增加原生 DOCX 注释、题注自动编号或独立动态引用；原生行内 REFERENCE_NUMBER 仍只接受已支持的编号项目标，不能因题注降为文字而声称该编号引用受支持。

回归检查 WARN／IGNORE 的实际损失、真实 DOCX 严格读回、源候选及注释集合不变、标记原文范围 6～7、周围文字 `BEFOREAFTER`、不可用保留处理及缺失注释绑定拒绝。非法绑定不会被丢弃策略绕过。

## 组合语料及模型边界

[NavigationRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/NavigationRecoveryExample.java) 使用 420×480 pt、28 pt 边距及显式 Liberation Sans Regular／许可。第一节两页从显示页码 3 开始，第二节一页从 12 开始；显式前置目录后共四个物理页。三处标题为第一节章／节标题及第二节章标题，层级为 1／2／1。注释包括正文显式标记脚注、题注锚点尾注及另一段的旧式段落锚点脚注。

行内字段引用第二节标题页码 12、列表编号 4 和最终总页数；独立引用解析到第二节页码及 Figure 1。编辑后正文／标题／题注与注释文字均替换 `ORIGINAL`；PDF 独立页码引用另改到第一节标题，显示值由 12 变为 3，物理目标由第四页变为第二页。正文 `${literal}` 保持字面值，故意传入的未使用源 XML 不得进入恢复包。

| 目标 | 此组合的恢复结果 |
| --- | --- |
| PDF | 完整两节模型、稳定 ID／目标、三条注释、显式标记、题注与行内／独立引用保持；从匹配包恢复模型后重新布局 |
| DOCX | 实际文件严格重导入保留分节、显示页码、标题／编号及原生行内 PAGEREF／REF／NUMPAGES；注释及标记已丢弃，题注为无编号正文，独立引用为未解析文字 |
| HTML／Markdown | 从准确输出重导入为一个流式节，原动态字段变为明确的未解析文字，注释内容已丢弃；恢复模型没有原生字段，Markdown 的分页降为文字分隔 |
| SVG／PNG 页 ZIP | 本语料会生成交互引用链接，静态后端明确拒绝；不伪造支持方向 |

每种支持目标保存普通、伴随 ZIP 及可选内嵌文件，三轮实际目标循环与三轮 XML 控制分别核对。16 组矩阵中只有 PDF→PDF→PDF 保持本语料完整模型，其余 15 组保存实际降级模型及累计损失。Web 输出仍是有损路径；匹配恢复信息不补回当前文件中已经丢弃的内容。

## 显式重建目录

`Navigation`／`TableOfContents` 没有进入语义模型或恢复 XML。本例在每次 PDF 编码回调中调用 `Navigation.fromHeadings(current)`，再明确传入标题、样式和目录选项。这样标题改名后目录及书签使用当前文字，最终页码与跳转由布局解析。此证据只证明调用方重建路径，不能称为原目录配置自动恢复。

独立无运行时导航对照只有三页正文，无目录及书签；总页数字段变为 3，分节显示页码仍为 3、4、12。显式重建时四页，目录条目依次为 3、4、12；页脚为 1/4、3/4、4/4、12/4。Web 恢复后的流式模型重建为目录一页加正文一页，三个目录目标均为第二物理页。

Note 文字使用 `editor.notes(revisedNotes)` 明确替换，普通正文替换不修改注释集合。调用方式见[恢复指南](../model-recovery-api.md#注释编辑与目录重建)。原目录样式／过滤／其他运行时设置的持久化继续独立登记。

## 损失与独立证据

首次保存对账如下，总数包含有损目标的一次 `MODEL_RECOVERY / CURRENT_FILE_REIMPORT`；此数值只属于固定语料。

| 目标 | 导出损失 | 导入损失 | 总数 | WARN 警告数 | IGNORE 警告数 | 恢复注释数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PDF | 0 | 0 | 0 | 0 | — | 3 |
| DOCX | 7 | 0 | 8 | 8 | 0 | 0 |
| HTML | 20 | 166 | 187 | 187 | 0 | 0 |
| Markdown | 35 | 1 | 37 | 37 | 0 | 0 |

WARN／IGNORE 的准确标准字节及恢复模型一致，IGNORE 保留历史损失但隐藏警告，ERROR 拒绝有损转换。真实 DOCX XML／python-docx 检查两节尺寸、页码重启、缺失脚注／尾注部件、正文标记移除、原生字段与书签所有者。HTMLParser／MarkdownIt 核对标准内容与三处标题层级，不依赖 Java 导入结果。

独立脚本检查 ZIP CRC、version 1 清单、准确普通字节长度／SHA-256、模型及资源摘要、字体／许可字节、稳定 ID／引用／注释集合、旧内容不返回，以及内嵌去包后的准确文件。PDF 核对脚注与尾注页、标记及题注、最终字段值、目录与书签层级、链接矩形及 XYZ 目标。PDF 重建目录时七条内部链接；DOCX 恢复后五条；Web 流重建只有三条目录链接。无导航 PDF 保留四条正文引用链接。

隔离 Maven 调用方从内嵌 PDF 恢复，修改标题／正文／题注及注释、改写独立页码目标，再写出并重开内嵌 PDF；随后显式转换为实际有损 DOCX 并内嵌当前模型。已丢注释不返回，IGNORE 历史在后续严格 XML 捕获中保持。

## 验证入口与结果

Java 21、Gradle Wrapper 9.2.1；独立环境使用 Python 3.12、Pillow 12.3.0、pypdf 6.10.0、pypdfium2 5.13.0、python-docx 1.2.0、markdown-it-py 4.0.0 及 Poppler。以下命令从仓库根目录执行，Python 依赖按 `scripts/requirements-verification.txt` 安装；调用方目录使用本次全新构建目录。

```powershell
.\gradlew.bat -g build/gradle-fresh build sbom publishLocalBundle :examples:navigationRecovery :examples:sectionRecovery --no-daemon --console=plain
.\gradlew.bat -g build/gradle-fresh -p verification/consumer -PconsumerBuildDirectory=build/<fresh-consumer-directory> clean build run navigationRecovery sectionRecovery resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
python scripts/verify_navigation_recovery.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
python scripts/verify_section_recovery.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
```

全仓 **1,501 项测试，0 失败／错误／跳过**；构建、SBOM、本地制品和隔离调用方通过，23 个运行组件、15 个自研库及 15 项 BOM 约束一致。

新增 42 页 PDF 全部使用 Poppler／PDFium 以 192 DPI 双渲染并逐页目视复查：四目标控制／编辑 24 页，普通原 PDF 4 页，无导航对照 3 页，调用方 11 页。最大平均通道误差 0.901595，最大变化像素比例 0.011932；沿用双像素模糊及 4／0.06 阈值，无视觉基线刷新。PDF 无编辑恢复像素与原文件一致；编辑后的四页分别因目录标题、正文／题注／脚注、独立引用目标、尾注而改变。

[阶段八十八](phase-88-section-recovery.md)的 36 组分节／共享页区／嵌套表矩阵及 48 页双渲染回归通过，全部页面复查。样例与独立报告位于忽略的 `examples/build/navigation-recovery/`、调用方构建目录及 `build/verification/navigation-recovery/`。

本轮完成上述注释／引用组合与显式目录重建单元。原运行时目录配置持久化、更广跨特性组合及真实外部应用编辑／另存仍按 GAP-24 独立推进；本轮没有新增外部应用保存回读证据。
