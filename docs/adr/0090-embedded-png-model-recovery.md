# ADR-0090：单页 PNG 使用私有辅助块恢复模型

状态：已采纳。扩展 [ADR-0087](0087-bound-standard-output-recovery.md) 的准确字节绑定及 [ADR-0089](0089-markdown-svg-embedded-recovery.md) 的单文件 API，不更改 version 1 清单。

## 背景

GAP-24.2 已有 HTML／Markdown／SVG 单文件恢复。PNG 是二进制图像，不能使用文本注释封装；同时原 Raster 后端默认输出页 ZIP，需要明确单张图像与多页容器的区别。

## 决定

在标准单页 PNG 的 `IEND` 前写入唯一的 `exJM` 块，直接携带既有 XML／资源恢复 ZIP。四个字母标记辅助、私有、合法保留位及 unsafe-to-copy；像素修改后不应复制该块。原 PNG 字节保持顺序及内容不变，移除准确块后即可核对清单长度／SHA-256，避免摘要包含自身的循环。模型、字体和许可继续使用原独立摘要；摘要不是作者认证。

`XmlModelRecovery.openEmbedded("PNG", bytes)` 和 `Saved.embeddedBytes()`／`writeEmbedded` 使用该封装。内部 `PngRecoveryEnvelope` 只依赖模型及 JDK，不引入图像后端依赖，也不解压像素。签名、头信息、关键块顺序、CRC、唯一恢复块和文件结束均有界检查；动画、未知关键块、尾随数据、截断或损坏拒绝。PNG 块总数及尺寸乘积复用 bundle 条目／像素预算，总文件包含新增块，ZIP 另受原有编码／展开限制。

`RasterBackend.singlePage()` 返回既有 `DocumentBackend`，要求已布局文档恰好一页；复用原 DPI、显式字体、extras 及像素预检。后端不重新布局、不静默选取第一页，借用流不关闭或刷新。原默认 PNG 页 ZIP 的行为保持；`PNG_ZIP` 不接受内嵌入口，仍使用显式伴随包。

## 一致性及验收边界

- 匹配内嵌信息恢复已保存的有限模型及资源，不从像素推断文字或表格。
- 外部改像素／元数据，即使保留旧块，也使绑定失效；图像重存可能直接删除私有块。缺失／过期默认拒绝，显式 WARN／IGNORE + DEGRADE 需要调用方提供当前文件读取器，否则仍失败。
- 损坏、CRC／摘要不一致、预算、取消及 I/O 不由策略放行；重复／错位恢复块不进入 fallback。fallback 只收到去除旧块后的当前 PNG。
- 有损捕获只写标准图像，不隐藏更丰富的源模型；真实编辑后新包不保留旧词或未使用的模板源码。
- 三轮准确文件／模型、字体／许可、真实编辑、外部像素修改／块移除、三动作、流所有权和失败原子性分别验收。Pillow 独立解码证明像素不变，结构及 ZIP 独立检查，恢复后 PDF 用两种引擎渲染。
- 验证样例的像素解码不等于封装入口完整验证任意 PNG 像素；有限样例不证明任意编辑器保存回读。PDF／DOCX／PNG 页 ZIP 内嵌及一般复杂跨格式往返继续按独立缺口推进。

没有新增扩展名、模块、运行依赖或素材。API 见[恢复指南](../model-recovery-api.md#单页-png-内嵌恢复)，证据见[阶段八十五](../verification/phase-85-embedded-png-recovery.md)。
