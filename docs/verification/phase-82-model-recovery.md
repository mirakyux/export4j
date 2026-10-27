# 阶段八十二验收：标准文件伴随恢复与往返矩阵

本阶段对应 GAP-24.1、24.2、24.3、24.4，四项都有实际推进；父项仍为部分支持，不将有限语义、伴随恢复或文件库重写扩大为任意复杂模型及阅读器无损重存。

## 分项与实际变化

| 分项 | 本阶段落地 |
| --- | --- |
| GAP-24.1 模型／资源 | `XmlTemplateWriter.Request.resources(bundle)` 与 `conversionTarget(bundle)` 明确保留字体字节及许可；只从当前模型收集图片，不复制旧源码、片段或已删图片。模板编写主题意图继续保存原 XML，展开模型不反推；运行时组件／句柄不序列化 |
| GAP-24.2 标准输出恢复 | `XmlModelRecovery` 捕获 Prepared 的准确标准字节和当前 candidate／notes；普通 XML ZIP 用 version 1 清单绑定格式／长度／SHA-256 及模型／资源摘要。缺失、未知版本、过期及冲突默认拒绝，显式策略和当前文件 fallback 才受限继续；非法／损坏／超限／取消不能放行 |
| GAP-24.3 原文档编辑闭环 | DOCX 无编辑原包保留准确字节；局部改字保留其他条目字节。PDF 原页面改字保留未修改区域；脱敏另验无源文字／字体／批注。改字、脱敏后的旧恢复包均被拒绝；当前受限导入可以重新保存，不能返回已删除内容 |
| GAP-24.4 往返与累计损失 | 七种输出分别完成三轮无编辑恢复和实际改字，另有 49 组 A→B→A；检查模型／ID／资源与 PDF 像素控制。历史损失跨三轮保留，不重复累计；随后产生新的 Markdown 导出损失时与历史及恢复丢弃逐项相加 |

另修复自生成 HTML 的准确限制型 CSP 元标签无法再导入的问题：仅接受项目输出的固定声明，以 `HTML_METADATA` + DISCARD 明确移除；refresh、其他策略文本、报告端点、非 meta 容器及事件属性仍拒绝。严格模式继续拒绝无法保留的 head 元数据，不执行或放宽安全策略。

## 可复现入口

Java 21、Gradle Wrapper；Python 3.12.14、pypdf 6.10.0、PDFium／pypdfium2 5.13.0、Pillow 12.3.0、markdown-it-py 4.0.0、Poppler 26.07.0。资源沿用已登记的 Liberation Sans；没有新增模块、运行依赖、字体或外部素材。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:modelRecovery --no-daemon --console=plain
python scripts/verify_model_recovery.py --prepare-external
.\gradlew.bat :examples:modelRecoveryExternal --no-daemon --console=plain
```

按[本地调用方流程](../../scripts/verify_local_consumer.ps1)准备新的 Maven 调用方构建目录，在该目录运行 `modelRecovery`，再执行：

```powershell
python scripts/verify_model_recovery.py --consumer-build-directory <本次成功的调用方构建目录>
```

样例在 `examples/build/model-recovery/`，结构／双渲染报告在 `build/verification/model-recovery/`。样例见 [ModelRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ModelRecoveryExample.java)，调用方见 [ModelRecoveryConsumer](../../verification/consumer/src/main/java/consumer/ModelRecoveryConsumer.java)，独立验证器见 [verify_model_recovery.py](../../scripts/verify_model_recovery.py)。已缓存依赖时可按工程验证规约离线执行；不以旧构建目录代替新的成功调用方。

## 结果

| 层次 | 实际证据 |
| --- | --- |
| 构建与行为测试 | `build sbom publishLocalBundle` 通过；1,454 项测试，0 失败／错误／跳过；新增恢复测试覆盖准确模型、字节快照、缺失／过期／格式冲突／未知版本、损坏、资源删除、累计损失、预算、取消、借用流及失败时旧目标保护 |
| 七格式控制 | PDF、DOCX、HTML、Markdown、SVG、PNG 页 ZIP、XML 模型 ZIP；7 项三轮／真实编辑及 49 项 A→B→A，共 56 行矩阵。49 组使用同一有限双段落模型，不将其描述为复杂结构交叉矩阵 |
| 包与资源 | 73 个样例恢复 ZIP，另有 2 个 Maven 调用方恢复 ZIP；独立核对 CRC、清单、标准文件准确摘要、条目资源摘要、相同源码、字体及许可字节。新保存无旧源码和已删除图片；SVG／PNG 的模型来自匹配伴随包，无像素识别 |
| 预检／实际损失 | 有损 HTML／Markdown 严格拒绝；WARN／IGNORE 的实际损失一致，后者隐藏当前警告。两个动作均没有更丰富源模型的恢复包；历史损失三轮保持，新导出损失与恢复丢弃另加，不能被当成此次编码的历史重复 |
| DOCX 原包 | 无编辑字节不变；原包文字替换后只有 `word/document.xml` 展开字节改变，其他条目及清单保持。旧恢复包三动作按实际规则拒绝或受限读取新文字；重新保存的模型不含旧词 |
| PDF 原页面／脱敏 | 原页面替换使用显式字体并报告 TEXT_FONT；新词实际可提取，未修改内容及区域像素保持。脱敏产物没有原始文字层、字体、批注及源文字对象／解码流；旧恢复包不能重新带回模型 |
| 独立 PDF 检查 | 样例 14 页、调用方 2 页，共 16 页全部经 pypdf 内容／嵌入字体检查、Poppler／PDFium 双渲染及逐页查看；无编辑控制和 7 个回到 PDF 的桥接控制同引擎像素完全一致，实际改字产生像素变化且未修改区域保持 |
| 独立工具重写 | pypdf 重写 PDF、Python ZIP 重压缩 DOCX、ElementTree 重写 SVG／XML、Pillow 重编码 PNG 页包，以及文本工具规范化 HTML／Markdown；七种准确字节均改变，旧恢复全部拒绝。DOCX／PDF／HTML／Markdown／XML 显式受限导入当前内容，SVG／PNG 无可用通用语义 fallback |
| 隔离制品 | 新 Maven 调用方恢复、编辑为 CALLER、重导出和无编辑重存通过；23 个运行组件、15 个自研库及 15 项 BOM 约束对账，实际分发 ZIP 的 15 个 JAR SHA-256 与本次 SBOM 一致 |
| 回归 | 独立引用、注释／题注／外链、模型、页区、源码包、多级编号、include 及行内字段的 8 组独立验收通过；未刷新视觉基线 |

## 仍开放的范围

Word 自动化编辑／保存尝试在 45 秒内未完成，未生成可验收的保存文件；已关闭该生成样本。此结果不计通过，也不把文件库重写称为 Word／其他阅读器保存验收。更广真实应用矩阵继续归 GAP-24.4／GAP-14.2。

标准格式内嵌恢复、任意自定义运行时组件、一般复杂模型跨格式、PDF 更广源结构及 DOCX 更广原包编辑仍开放，具体与 GAP-10／12／18／20 对齐。图片／字体／正文在某次导出已丢弃，不能靠隐藏旧副本补回。伴随包是独立历史文件，应用管理旧版本及删除；当前入口只接受准确匹配的组合，不隐式查找或删除文件。

API 见[恢复指南](../model-recovery-api.md)，设计见 [ADR-0087](../adr/0087-bound-standard-output-recovery.md)。本阶段验证的是构建、本地制品及输出，没有执行远程 CI、Git 提交、推送或正式发布。
