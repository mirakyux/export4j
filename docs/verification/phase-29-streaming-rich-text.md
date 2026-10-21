# 阶段二十九：单向长表富文本与显式字体角色

日期：2026-10-16。对应 GAP-09，设计见 [ADR-0036](../adr/0036-streaming-rich-text.md)，调用见[富文本长表指南](../streaming-rich-text-api.md)。复用已有 TableCell、共享文字布局和同步逐页输出，无新增模块、生产依赖或资源。

## 已实现范围

固定列、整行分页的单向源接受有界纯 InlineText；保留字号、RGB、基线偏移、下划线、删除线及显式真实字体角色。各字体组保留自身缺字策略。链接包括空文字链接、图片、字段、块格、合并、拆行、未来引用和总页数仍明确拒绝。

每行先检查全部格的内容、角色和预算，再创建运行列表及塑形；空运行计费，普通无运行文字按一个有效运行计费。新增默认逐格／逐行运行额度为 4,096／16,384。旧 layout 重载与 StreamingTableLimits 四参数构造器保留；record 分量、反射、解构及二进制边界见指南。普通文字继续使用旧度量路径。

## 构建与行为

全量 `build sbom publishLocalBundle` 与受影响样例成功，**732 项测试，失败／错误／跳过均为 0**，比上阶段新增 21 项。生产、测试使用 `-Xlint:all -Werror`，Javadoc 参与构建。仅出现进阶样例预期的两条缺字替换警告；无编译警告。

测试覆盖运行样式／字体角色、全行预检、空运行格／行额度、角色快照与未知角色的 UTF-16 来源、复杂文字及固定行框、表头复用与逐页诊断、至多一行预读、源／回调失败、取消与恢复。保留原子目标、关闭顺序和异常上下文。

本轮隔离调用方为 `verification/consumer/build/validation-8edde98a32f9`，`scripts/verify_local_consumer.ps1` 的本地 bundle／SBOM 及独立编译运行全部通过。调用方使用发布坐标直接调用真实粗体富文本长表，不使用源码依赖或旧成功目录。消费者工作目录为 `verification/consumer`，显式字体路径 `../../assets/fonts/...` 已实际读取成功。

## 独立输出

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_streaming_rich_text.py `
    --consumer-build-directory verification/consumer/build/validation-8edde98a32f9
~~~

固定验证环境为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 与显式 Poppler。普通样式 3 页、复杂文字 1 页、调用方 2 页，共 **6 页**，全部双渲染并逐张查看完整 Poppler 页。检查正文有序唯一、逐页表头、格边界、真实字体变体、颜色、偏移、装饰及 SVG 来源；复杂页另验证 14 条轮廓的颜色和控制点均在各自固定行框／格内。缺字占位和结构化诊断保持。

最大通道均值差 2.922525、变化像素比例 0.023498，均低于 4／0.06 门槛。独立检查没有要求 PDFBox 子集字体保留 OS/2；真实变体改以嵌入 head.macStyle、FontWeight、实际字体名称和最终绘制交叉验证。历史视觉基线未刷新。

## 容量测量及审查修正

布局丢弃页和真实 PDF 分别采样普通／富文本 1,000、10,000 行独立 JVM，另运行富文本 10,000 行、64 MiB 堆布局控制，共 **9 组**。记录显式字体字节／SHA、每 PID 工作集／私有字节／句柄、观察堆、吞吐、临时盘及实际退出清理。

审查发现容量例初版按 `6 * rows + 3` 手算源运行数。已改为从实际构造的表头／正文格累计，并以新 ID 重采全部 5 组富文本；旧手算样本只保留历史，不作为最终结果。普通分支未变，4 组控制样本保留。独立校验实际累计为 6,003／60,003，fixture、stdout 与进程报告一致，9 组退出临时文件数／字节均为 0，实际目录亦为空。

| 10,000 行结果 | 页数 | 用时（秒） | 观察堆峰（MiB） | 工作集峰（MiB） | 私有字节峰（MiB） |
| --- | ---: | ---: | ---: | ---: | ---: |
| 普通／布局／512 MiB | 179 | 1.531 | 39.597 | 167.105 | 240.992 |
| 普通／PDF／512 MiB | 179 | 72.663 | 60.493 | 197.828 | 270.230 |
| 富文本／布局／512 MiB | 179 | 1.800 | 40.910 | 150.336 | 223.230 |
| 富文本／PDF／512 MiB | 179 | 82.309 | 67.348 | 241.266 | 308.992 |
| 富文本／布局／64 MiB | 179 | 1.402 | 41.214 | 163.203 | 235.148 |

计时从 main 内参数检查／目录创建后开始，包含字体初始化、生成源、布局、PDF 完成保存和原子移动；不含 JVM 启动及最终报告写入。进程采样从启动到观察退出，每 100 ms 一次。观察堆不是精确峰，工作集不是堆上限；有限样本不能用于性能排名、长期容量、无泄漏或 PDF 恒内存结论。PDFBox 页树仍累计。本次未重新采样 100,000 行。

最终容量目录为 `build/verification/streaming-table/capacity-b3649a4592d0484f8e625a64b8b27514`（普通）和 `capacity-b1a32bdcecb0483cbca5a8b10c046867`（富文本），一致性报告为 `build/verification/phase29-capacity-integrity.json`。

## 回归与后续

**28 组历史独立回归全部通过**，包含 XML 块格、普通长表、表格跨度、模板、字段、图片、复杂文字和既有区域布局；使用本轮调用方及隔离列合并工作目录。当前最终 archive.pdf 已由 veraPDF 验证，144 条规则、10,629 项检查，失败 0；不以此扩大归档范围。未重跑 Word 保存或 OpenSSL 签名验签。

下一单元依据[编写时主题设计](../adr/0037-authoring-theme-snapshots.md)推进 GAP-04 的编写时主题快照及命名样式；字体角色级联、可编辑字段样式与 XML 样式名仍分别开放。

证据：`build/verification/phase29-full-build.log`、`phase29-consumer.log`、`phase29-capacity-recompile.log`、`phase29-capacity-rich-retry.log`、`phase29-rich-independent.log`、`streaming-rich-text/report.json` 与 `phase29-verapdf.xml`。
