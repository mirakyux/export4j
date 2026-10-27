# 标准文件与 XML 伴随包恢复

`XmlModelRecovery` 将已有 `DocumentConversion.Prepared` 的当前可编辑模型、注释和显式资源保存为普通 ZIP，绑定到本次标准输出的准确字节。文件仍使用 PDF、DOCX、HTML、Markdown、SVG 或 PNG 页包；恢复包使用普通 `.zip`，内部沿用 `template.xml`、图片、字体和许可，加一个 `recovery.xml` 清单。没有新的专用文档扩展名，也不改变标准文件结构。

模块为 `export4j-adapter-template`，仅依赖模型模块。布局后端及各适配器仍各自提供标准输出；恢复层不反向依赖它们、不执行脚本、不自动查找伴随文件或系统字体。

## 保存、恢复和编辑

```java
var prepared = DocumentConversion.from(document, notes)
        .prepare(docxAdapter.conversionTarget());
var saved = XmlModelRecovery.capture(prepared)
        .resources(explicitResourceBundle)
        .prepare();
saved.writeDocument(Path.of("report.docx"));
saved.writeCompanion(Path.of("report.docx.recovery.zip"));

var opened = XmlModelRecovery.open("DOCX", Files.readAllBytes(Path.of("report.docx")))
        .companion(Files.readAllBytes(Path.of("report.docx.recovery.zip")))
        .prepare();
// opened.document()/notes(): 已验证的一致模型；resources(): 显式字体和许可。

var edited = XmlModelRecovery.open("DOCX", saved.documentBytes())
        .companion(saved.companionBytes().orElseThrow())
        .conversion()
        .edit(editor -> editor.replaceText("草稿", "已复核"))
        .prepare(docxAdapter.conversionTarget());
var next = XmlModelRecovery.capture(edited)
        .resources(opened.resources().orElseThrow())
        .prepare();
```

`capture` 读取预检已固定的编码和编辑后的 candidate，不重新执行导入、编辑或目标编码。完整语义子集沿用 [XML 模型保存](xml-model-saving-api.md)：正文、分节、页区／变体／图层、表格、列表、字段、ID／引用、注释、题注与图片。没有编辑时模型和资源可以保持一致；重新布局外观仍要求相同字体、字体角色映射、引擎和配置。未注册的角色、缺少字体或不支持的布局不会由恢复入口补造。

`resources(bundle)` 仅复制显式字体字节及许可；保存图片重新遍历当前模型。旧模板、片段和已删除图片不会进入新的恢复包。`XmlTemplateWriter.from(...).resources(bundle)` 与 `conversionTarget(bundle)` 提供同样的资源传递方式。模板编写意图应直接用 `XmlTemplateBundle` 保存原 XML 和片段；展开后的模型不能推回主题、原变量、条件或循环。运行时组件代码、字体句柄和配置回调不持久化；自定义块继续按已验证的 ERROR 或 WARN／IGNORE + DISCARD 处理。

## 一致性与三动作策略

清单 version 1 保存格式键、文件长度、SHA-256，以及模型／资源条目的独立摘要。格式键为至多 64 位的大写字母、数字和下划线，以字母开头。相同模型而文件元数据不同，也视为过期；这项保守规则避免猜测外部修改是否影响可见内容。摘要只检测不一致，不认证提供者；应用仍须决定是否信任所提供的文件及伴随包。

| 情况 | 默认 ERROR | WARN／IGNORE 的实际处理 |
| --- | --- | --- |
| 当前文件与受支持清单完全匹配 | 恢复完整已保存模型 | 同样恢复；保留历史实际损失 |
| 缺少伴随包、格式冲突、文件已修改、未知恢复版本 | 拒绝恢复 | 仅允许 `MODEL_RECOVERY` + DEGRADE，且须显式提供当前文件的 `FallbackReader`；返回 `recovered=false`，资源为空并报告受限导入损失 |
| 本次目标导出发生降级、丢弃或跳过 | 拒绝保存恢复信息 | 仅允许 `MODEL_RECOVERY` + DISCARD，保留标准输出，`companionBytes()` 为空；不把更丰富的源模型藏进恢复包 |
| 清单／ZIP 损坏、模型／资源摘要冲突、非法语法、资源损坏、超限、取消或 I/O 失败 | 直接失败 | 同样失败；不调用 fallback、不被策略放行 |

WARN 与 IGNORE 的处理相同；IGNORE 隐藏当前警告，实际 issue／loss 不消失。历史 issue 保留原来的 action，不因下一轮换为严格策略而被删除或重复累计。`DocumentConversion.Prepared.exportReport()` 只返回本次目标编码的诊断，便于把历史损失与本次导出损失分开判断；编码成功不等于最终路径出版成功。

```java
var policy = ProcessingPolicy.builder()
        .feature("MODEL_RECOVERY", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var current = XmlModelRecovery.open("DOCX", currentDocxBytes)
        .companion(previousCompanionBytes)
        .policy(policy)
        .fallback((bytes, sharedPolicy) -> {
            // 此处选用既有严格 DOCX 导入；若改用允许损失导入，须完整转换并返回诊断。
            var result = docxAdapter.read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
            return new DocumentConversion.Input(result.document(), List.of(),
                    new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        })
        .prepare();
```

fallback 必须解析传入的当前字节快照，遵守所选适配器的权限、安全及损失契约。没有 fallback 时，WARN／IGNORE 也不能凭空返回文档。SVG 和 PNG 页包可从匹配伴随包恢复模型；缺失或过期时，没有通用矢量／像素语义导入，须明确拒绝，不能把原图或 OCR 当成等价模型。

## 原包／原页面与删除

DOCX 原包无编辑重存的既有路径保留原始字节，原绑定仍有效。原包文字修改、PDF 原页面文字替换、选页、裁剪、脱敏、优化或外部重存只要改变文件字节，原绑定就失效。旧模型不会覆盖当前可见内容。恢复后用语义编辑再保存时，新包只包含编辑后的模型和当前图片。

伴随包是独立文件；库不会搜索或删除用户保留的旧版本。替换标准文件后，应用应同时保存新的伴随包，或清理旧伴随包；即使旧包仍在，摘要不匹配也不会被恢复入口接受。两次路径出版各自原子，不承诺两个文件构成跨路径事务。第二次写入失败造成的错配可检测并拒绝。

伴随包以明文 XML 和资源保存内容，不继承标准文件的加密、签名或访问权限。应用须单独管理相同内容与资源的分发及访问控制；恢复入口读取明确提供的模型包，不解密或解析标准文件。

恢复不扩大原包／原页面编辑范围，不使 PDF 文字替换成为脱敏，也不保证标准阅读器打开后重存仍保留准确模型。外部重存后可显式受限导入当前内容，再导出并生成新绑定；已丢失的信息不会从旧包补回。

## 预算和验收

默认当前标准文件不超过 64 MiB、清单不超过 1 MiB、累计诊断不超过 10,000 条；普通 ZIP 沿用 `XmlTemplateBundle.Limits` 的编码／展开／条目／文字／像素预算。清单使用安全 XML，拒绝 DTD、外部实体、未知结构及过深树；源 XML 继续编译并完整绑定。SHA-256 按块检查取消；`execution` 支持同步取消及超时，借用流不关闭或刷新。路径使用同目录临时文件和原子替换，失败保留旧目标，无非原子回退。

可运行样例见 [ModelRecoveryExample](../examples/src/main/java/com/mirakyux/export4j/examples/ModelRecoveryExample.java)，设计见 [ADR-0087](adr/0087-bound-standard-output-recovery.md)，证据见[阶段八十二](verification/phase-82-model-recovery.md)。七种输出的三轮、49 组 A→B→A、实际编辑及独立重写只证明登记的有限语料；一般复杂模型、任意应用重存和无伴随包的 SVG／PNG 语义识别仍按 [GAP-24](capability-gaps.md#自生成文档的恢复与往返编辑)及各输入缺口登记。
