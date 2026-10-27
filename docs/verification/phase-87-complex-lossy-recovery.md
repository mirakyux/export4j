# 阶段八十七验收：复杂文档往返与有损输出恢复

本阶段推进 **GAP-24.4 的复杂语义子集**：标题、普通段落、一级项目符号／编号、重复表头、横向合并格、图片及独立编号引用。新增 `XmlModelRecovery.SaveRequest.reimportOnLoss`，允许调用方接受目标损失后，从本次实际标准输出重建恢复模型，再编辑并导出。恢复包不保存导出前较丰富的候选，不恢复已删除图片或旧源码。

## 实现与策略

只有本次目标导出发生损失时才检查重导入路径；历史导入／编辑损失不会让后续无损编码再次调用读取器。`MODEL_RECOVERY` 为 ERROR 时读取前拒绝；WARN／IGNORE + DISCARD 只输出普通文件；WARN／IGNORE + DEGRADE 调用一次显式可信读取器。读取器收到准确目标字节的防御副本，受本次保存策略与执行作用域约束，返回当前模型、注释和 ACTUAL 报告。没有读取器、XML 无法保存返回模型、解析／I/O／权限／预算／取消失败均明确终止。

HTML 真实重导入同时修复了三处互操作问题：

1. 表格 `colgroup`／`col` 在有限正整数声明范围内接受，列组几何按 `TABLE_COLUMNS` 显式降为等份；实际格网及跨度继续验证。
2. 重复表头中的单个段落按 `TABLE_HEADER_PARAGRAPH` 显式展平为行内内容；DISCARD 清除该内容，多块／内表表头仍拒绝。
3. 显式列表标记使用版本 `1` 的被动 data 属性与层级，一级简单标记从正文分离，保留编号重启及独立样式。普通同名 CSS class 不自动删除；复杂标记按 `LIST_MARKER` 明确降为普通段落或丢弃，非法标记拒绝。

列表问题由最初的目视复查发现：旧导入把导出的标记文字再次算入正文，多轮后前缀增长。修复后加入每轮完整正文断言和 PDF 完整标签行断言，避免只检查正文 token 出现一次而遗漏重复标记。API 与契约见[恢复指南](../model-recovery-api.md)、[HTML 导入](../html-import-api.md)及 [ADR-0092](../adr/0092-reimport-lossy-output-for-recovery.md)。

## 语料与矩阵

[ComplexRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ComplexRecoveryExample.java) 使用显式 A4／48 pt 边距、标题、字面量 `${literal}`、项目符号与十进制列表、两列表格／重复表头／跨两列合并格，以及代码生成的 8×8 蓝橙 PNG。字体使用仓库内 Liberation Sans Regular／Bold 及对应许可，不扫描系统字体。资源控制中故意提供未使用旧源码，恢复 ZIP 必须排除它。

| 检查 | 本轮结果 |
| --- | --- |
| 七种标准目标 | PDF、DOCX、HTML、Markdown、SVG、单页 PNG、PNG 页 ZIP；分别保存普通文件、伴随 ZIP 和内嵌文件 |
| 三轮控制 | 每个目标三轮实际导出／恢复，另有三轮无损 XML 控制；逐轮检查内容、历史损失与列表正文 |
| 实际编辑 | 标题与表格中的 `ORIGINAL` 改为 `EDITED`，重开后旧文字不返回 |
| 49 组 A→B→A | 25 组不涉及 Web 的组合精确保留模型；24 组涉及 HTML／Markdown，保留实际累计损失及降级后的模型 |
| 原生编号引用 | PDF／DOCX 保留真实引用模型，DOCX 独立检查原生 REF 字段，PDF 检查目标注释；HTML／Markdown 明确降级，静态后端继续拒绝引用产生的交互信息 |
| 字节与资源 | version 1 清单准确绑定标准字节、模型／资源 SHA-256；七种封装去除恢复信息后还原准确原字节，字体与许可准确一致，无旧源码或未使用图片 |
| 隔离 Maven 调用方 | 从本轮公开坐标读取 DOCX 恢复，显式重导入有损 Markdown，改字后保存／重开内嵌 DOCX 并导出 PDF；WARN／IGNORE 都保留实际历史 |

共同七格式矩阵使用静态引用标签，原生编号引用由独立四方向语料验证。它不代表 SVG／PNG 已支持交互引用。Web 导入器未登记旧图片，图片按真实 `IMAGE_RESOURCE` 损失变成 alt 文字；首次标准输出仍含图片，新恢复模型与后续输出只含受限导入结果。

首次 Web 保存的实际诊断如下；总数包含一次 `MODEL_RECOVERY / CURRENT_FILE_REIMPORT`：

| 目标 | 导出损失 | 导入损失 | 总数 | WARN 警告数 | IGNORE 警告数 | 恢复图片数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| HTML | 1 | 161 | 163 | 163 | 0 | 0 |
| Markdown | 9 | 1 | 11 | 11 | 0 | 0 |

WARN／IGNORE 的普通输出字节及恢复模型一致；IGNORE 只隐藏警告呈现，实际损失仍可查询。后续目标轮次可能再产生新损失，按次累计；无损 XML 控制不得重复增加历史。其他五种目标在本语料下没有导出损失，保留一张图片。

## 可复现入口与结果

Java 21、Gradle Wrapper 9.2.1；Python 3.12 的独立环境按 `scripts/requirements-verification.txt` 安装。本轮使用 Pillow 12.3.0、pypdf 6.10.0、pypdfium2 5.13.0、python-docx 1.2.0 和 markdown-it-py 4.0.0；PDF 使用 Poppler／PDFium 双渲染。命令从仓库根目录执行，Python 指向已安装固定依赖的解释器。

```powershell
.\gradlew.bat -g build/gradle-fresh build sbom publishLocalBundle :examples:complexRecovery --no-daemon --console=plain
.\gradlew.bat -g build/gradle-fresh -p verification/consumer -PconsumerBuildDirectory=build/<fresh-consumer-directory> clean build run lossyRecovery resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
python scripts/verify_complex_recovery.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
.\gradlew.bat -g build/gradle-fresh :examples:webExports :examples:htmlImport --no-daemon --console=plain
python scripts/verify_web_exports.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
# verify_html_import.py 使用 build/verification/consumer-build-directory.txt 中本次成功调用方目录。
python scripts/verify_html_import.py
```

本轮全仓测试报告为 **1,497 项，0 失败／错误／跳过**；构建、SBOM、本地制品及新隔离调用方通过。独立制品检查确认 23 个运行组件、15 个自研库和 15 项 BOM 约束一致。新增行为回归涵盖读取器单次调用／防御副本、实际注释与资源、多轮历史、三动作、预算、取消作用域、XML 不支持结果及失败时旧目标保持；HTML 列组、表头和列表另有正常／拒绝回归。

独立验证器通过七格式结构、准确封装字节、49 组矩阵和 20 页 PDF 检查：七格式控制／编辑各一页、四个原生引用方向模型各一页、两个调用方策略各一页。20 页均以 192 DPI 双渲染，最大平均通道误差 0.577284、最大变化像素比例 0.006784；全部页面拼图已逐页目视复查，列表无重复标记，表格、文字与图片／替代文字符合声明结果。五种无损目标在同一渲染器下的控制／编辑像素分别一致。既有 Web 独立结构回归及 HTML 导入的 2 页双渲染通过，后者全部页面另行复查。浏览器打开本轮内嵌 HTML，文字、列表和表格可见，恢复注释不显示。

首次渲染检查误用了简单段落的 0.8／0.004 预设，在细表格边缘的抗锯齿差异处失败。检查两种渲染结果后，本验证器改用既有复杂 XML／表格验收的双像素模糊与 4／0.06 阈值；保留原始结果，没有修改既有视觉基线。结构、正文唯一性及完整列表标签断言独立执行，不由该视觉阈值代替。

生成物位于忽略的 `examples/build/complex-recovery/` 与调用方构建目录，报告及页面位于 `build/verification/complex-recovery/`。这些产物与原始日志不纳入仓库。

## 后续边界

本轮完成上述独立交付单元，**不关闭一般复杂往返 GAP-24.4**。多节页区／变体、注释、图层、嵌套表及更广组合仍需各自扩大标准恢复矩阵。无匹配恢复信息的 SVG／PNG 没有通用语义识别；受限重导入不是无损恢复。未新增外部应用另存验证，不推断 Word 或任意编辑器保存后会保留恢复信息。内嵌仍是显式可选项，兼容性和体积沿用[阶段八十六](phase-86-standard-container-recovery.md)的证据范围。
