# ADR-0089：Markdown 与 SVG 共用有界恢复注释

状态：已采纳。扩展 [ADR-0088](0088-embedded-html-model-recovery.md) 的格式范围，保留其 HTML 入口和封装兼容性。

## 背景

GAP-24.2 的 HTML 单文件恢复已验收。Markdown 与 SVG 也需要无需额外 ZIP 的重开、编辑与重导出，同时保持标准阅读器可直接显示，并明确外部修改后的行为。

## 决定

在 `XmlModelRecovery` 增加 `openEmbedded(format, bytes)`、`Saved.embeddedBytes()` 及 `writeEmbedded` 两个重载，明确只接受 HTML、MARKDOWN、SVG。三者复用同一末尾 Base64 注释和 version 1 清单：CommonMark 的 HTML 注释块与 SVG 根后 XML 注释均不参与显示，清单继续准确绑定注释以外的全部字节，不引入 DOM／XML／Markdown 归一化。

内部注释编码集中在 `CommentRecoveryEnvelope`。原 HTML 便利方法委托通用实现，继续限制 HTML；不改变旧封装、清单版本、资源、预算或恢复策略。伴随包入口保持原职责，不允许与内嵌入口混用。没有新增扩展名、模块、运行依赖或素材。

匹配信息恢复当前保存的有限语义模型及显式资源。外部修改、删除注释、未知版本或格式冲突沿用默认拒绝，只有显式三动作中的非阻塞动作加 DEGRADE 与当前文件 fallback 才能进入受限导入。fallback 只能收到去除旧注释后的当前字节；有损导出不能携带更丰富的源数据。损坏、摘要冲突、预算、取消及 I/O 不能由策略放行。

## 格式与验收边界

- Markdown 可通过现有 `MarkdownImporter` 导入当前内容后重建绑定，核对新模型不含旧文字；源码可看到 Base64，但 CommonMark 显示不包含恢复注释。
- SVG 导出以文字轮廓为主。缺少有效恢复信息时，没有可从任意轮廓推回段落、表格和可编辑文字的内置读取器。WARN／IGNORE 不会补造这一能力；必须提供自己的可信当前文件读取器或拒绝。
- SVG 外部改色也使准确字节绑定失效，即使模型文字未变。标准 SVG 可继续显示，但旧语义模型不覆盖当前文件。
- 单文件三轮、真实编辑、资源／许可、外部改动、三动作、缺失／冲突、非法封装、原子失败和借用流分别验证；独立检查 CommonMark、SVG、ZIP／XML、双引擎 PDF 及真实浏览器显示。浏览器打开不等于编辑器保存回读。
- PDF、DOCX、PNG 内嵌、一般复杂模型和任意应用重存继续按独立缺口推进。

API 见[恢复指南](../model-recovery-api.md)，证据见[阶段八十四](../verification/phase-84-embedded-comment-recovery.md)。
