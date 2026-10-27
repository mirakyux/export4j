# 阶段八十八验收：分节、页区与嵌套表组合恢复

本阶段推进 **GAP-24.4 的多节复杂组合**：两节四页、共享页眉页脚、FIRST／EVEN 变体、显式空首页、显示页码重启、背景／前景重叠图层，以及带纵向合并格的两层表格。六种多页标准目标完成三轮恢复、实际编辑及 36 组 A→B→A；同时修复 HTML 导出内表无法直接重导入的问题。

## HTML 格内容器修复

HTML 导出会在内表外生成 `div.table-scroll`。原导入器只接受格内直接 p／table，因此即使显式允许降级也无法读取自己的这一输出。现在只对准确 class、单个直接 table 子元素、空白直接文字且可见的容器提供明确处理：

| 策略 | 行为 |
| --- | --- |
| 默认 ERROR | 以 `TABLE_CELL_CONTAINER` 拒绝 |
| WARN／IGNORE + DEGRADE | 去除滚动容器几何，保留 p／table／p 顺序、表格层级与真实格路径；记录 `CELL_TABLE_WRAPPER_REMOVED` |
| WARN／IGNORE + DISCARD | 去除该容器和内表，保留其余块；空格转为合法空行内格 |
| PRESERVE | 无原样保留通道，拒绝 |

其他 class、混入直接文字、多子元素、隐藏容器和超深表格继续拒绝。全源安全检查先于丢弃执行，内表中的脚本不能通过 DISCARD 绕过。新增两项行为测试覆盖真实导出／重导入、三动作、丢弃空格、恶意子树及 17 层拒绝；这些故障无法由编译或格式检查发现。契约见 [HTML 导入](../html-import-api.md)。

## 语料与保留边界

[SectionRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/SectionRecoveryExample.java) 使用 420×420 pt 纸张、24 pt 边距；第一节三页从显示页码 2 开始，第二节一页从 8 开始。FIRST 优先于偶数页，第二节显式空 FIRST 对不继承默认内容；页字段使用最终总页数 4。两处共享蓝色背景与紫色前景相交，以真实像素检查定位和覆盖顺序。内表位于 p／table／p 块格，另一格跨两行。

字体与许可显式使用仓库内 Liberation Sans Regular／Bold，不扫描系统字体。源资源故意携带未使用旧 XML 文本，恢复 ZIP 必须排除它；已丢弃图层的图片也不得返回。

| 目标 | 此组合的实际结果 |
| --- | --- |
| PDF／SVG／PNG 页 ZIP | 恢复完整两节模型、共享来源、变体、字段、三处图层放置及两张去重图片；SVG 在一个文件中堆叠四个页面视口，PNG ZIP 保留四张 420×420 像素页 |
| DOCX | `PAGE_OVERLAYS` 显式 DISCARD；从实际 DOCX 重导入后保留分节、页区选择、显示页码、嵌套表和纵向合并；恢复包无图层和图片 |
| HTML | 从实际 HTML 重导入为一个默认页面配置的流式节；保留两层表格，图像降为 alt 文字；分节、固定定位、页区选择和动态字段损失逐项记录 |
| Markdown | 同样按实际文件重建流式模型；外表保留，内表内容展平为文字，纵向合并及版面信息降级并报损 |
| 单 PNG | 多页输入明确拒绝，本轮不纳入六格式矩阵 |

每种目标保存普通文件、伴随恢复 ZIP 和可选内嵌文件，完成三轮目标恢复及独立三轮无损 XML 控制。实际编辑将共享页眉和内表中的 `ORIGINAL` 改为 `EDITED`；较丰富源模型、旧文本和未使用图片不能从历史资源中恢复。

36 组 A→B→A 中，9 组仅涉及 PDF／SVG／PNG 页 ZIP，模型精确一致；其余 27 组保留降级后的模型和逐次累计损失。恢复不从 SVG／PNG 的像素推断语义；它依赖与当前准确标准字节绑定的匹配信息。

另有去除图层的原生 DOCX 严格对照，不靠恢复包回读。独立 OOXML 检查两节、首页／偶数页引用、共享页区、空 FIRST、PAGE／NUMPAGES、页码重启、内表格网与 vMerge。原生读取会把默认样式单元格运行规范化为普通文字；XML 展开后的模型与源模型一致，原生读回 PDF 的内容与版面另行核对，不宣称 Java 构造形式逐字段相同。

## 策略与资源对账

首次保存的实际损失如下。总数包含一次 `MODEL_RECOVERY / CURRENT_FILE_REIMPORT`；三种无损目标为零损失、保留两张图片。

| 目标 | 导出损失 | 导入损失 | 总数 | WARN 警告数 | IGNORE 警告数 | 恢复图片数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DOCX | 6 | 0 | 7 | 7 | 0 | 0 |
| HTML | 39 | 381 | 421 | 421 | 0 | 0 |
| Markdown | 80 | 8 | 89 | 89 | 0 | 0 |

WARN／IGNORE 的标准字节和恢复模型一致，IGNORE 只隐藏警告呈现；ERROR 拒绝本轮有损转换。诊断数量属于本语料，不是格式支持程度评分。历史损失在后续无损 XML 中原样保留；后续实际目标转换可能增加新损失。

独立验证器核对 version 1 清单、准确标准字节长度／SHA-256、模型／资源摘要、ZIP CRC、字体许可字节及图片清单。去除六种内嵌封装后均得到准确普通文件及伴随包。新隔离 Maven 调用方从内嵌 PDF 恢复、按 ID 编辑共享页眉及内表文字，写出／重开内嵌 PNG 页 ZIP，再显式丢弃图层、重导入实际 DOCX 并保存；IGNORE 历史在后续严格 XML 捕获中继续存在。

## 验证入口与结果

Java 21、Gradle Wrapper 9.2.1；独立 Python 环境按 `scripts/requirements-verification.txt` 安装。本轮使用 Python 3.12、Pillow 12.3.0、pypdf 6.10.0、pypdfium2 5.13.0、python-docx 1.2.0、markdown-it-py 4.0.0，以及 Poppler／PDFium 双渲染。以下命令从仓库根目录执行；替换调用方目录占位符，Python 指向已安装依赖的解释器。

```powershell
.\gradlew.bat -g build/gradle-fresh build sbom publishLocalBundle :examples:sectionRecovery :examples:complexRecovery :examples:htmlImport --no-daemon --console=plain
.\gradlew.bat -g build/gradle-fresh -p verification/consumer -PconsumerBuildDirectory=build/<fresh-consumer-directory> clean build run sectionRecovery lossyRecovery resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
python scripts/verify_section_recovery.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
python scripts/verify_complex_recovery.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
# 将本次成功调用方目录写入 build/verification/consumer-build-directory.txt 后运行：
python scripts/verify_html_import.py
```

本轮全仓 **1,499 项测试，0 失败／错误／跳过**；构建、SBOM、本地制品及新隔离调用方通过。独立制品检查确认 23 个运行组件、15 个自研库及 15 项 BOM 约束与本次调用方一致。

新增 48 页 PDF 全部经 192 DPI 双渲染并逐页目视复查：六目标控制／编辑 36 页，加源文档、原生 DOCX 读回和调用方各 4 页。最大平均通道误差 1.755143、最大变化像素比例 0.022535，沿用复杂表格的双像素模糊与 4／0.06 阈值；没有刷新视觉基线。完整字段值、正文唯一性、内表顺序及图片几何独立断言。三种无损目标的控制像素与源文档一致；编辑只改变内表所在第一页和共享默认页眉实际出现的第二页，第三／四页像素不变。

既有复杂七格式 49 组往返及 20 页双渲染、HTML 导入 2 页双渲染均通过，并复查全部回归页面。所有样例及报告保留在忽略的 `examples/build/section-recovery/`、调用方构建目录与 `build/verification/section-recovery/`，原始日志及截图不纳入仓库。

## 后续边界

本轮完成上述多节组合单元，不关闭一般复杂往返 GAP-24.4。注释、引用、目录与这些语义的更广组合，以及真实外部应用编辑／另存仍需独立验收。Web 是有明确损失的流式导入，DOCX 不保留本轮图层；匹配恢复信息也不能使不支持的目标格式无损。内嵌继续是显式可选项，普通文件可独立输出。
