# PDF 内容流文字替换

`PdfAdapter.editText(input)` 修改已有静态页的 Tj／TJ 真实字符串，重新编码为显式嵌入字体。按零基页选择、字面匹配，支持连续显示指令，以及同一行跨字体、字号、颜色、间距与明确定位变化的匹配。不重新分页、不自动缩字。

```java
var policy = ProcessingPolicy.builder()
        .feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("TEXT_STYLE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var candidate = adapter.editText(input)
        .replace(0, "OLD", "NEW", PdfFont.of(fontBytes))
        .policy(policy).prepare();
candidate.to(target);
```

显式字体只替换命中字形范围，字号和颜色保持；范围外保留原编码、字体、字距和位置。TEXT_FONT／DEGRADE 必须明确接受。匹配范围内 TJ 的非零字距被替换时另需 TEXT_SPACING／DEGRADE；末尾补偿 advance，后续原文字的位置保持。替换后宽度不能超过原范围的 advance，不依据页面剩余空白推测容量。短替换留下的间隙可能被文字提取器推断为空格。

例如 `(SEC) Tj [(RET)] TJ` 可命中 `SECRET`。连续序列按各自字体的原始编码字形组合，不拼接原字节后重新解码，也不在指令之间自动插入空格；同一序列的多处命中分别计数。Tf、颜色、Tc／Tw／Tz／Tr／Ts 和明确的同一行 Td／Tm 可保留在序列中。跨段匹配用首个命中字形的样式和基线绘制替换文字，需接受 `TEXT_STYLE / DEGRADE`；源段操作符保留，每个显示段分别补偿推进量，使未命中字形保持原坐标。

同一行判定要求文字矩阵的基向量一致、基线共线，后段不反向重叠，间隙不超过相邻字号较大者的一半。不同基线、过大间隙、q／Q、BT／ET、页和 Form 调用边界结束序列；不根据提取器拼出的字符串跨行猜词。跨状态范围内的非默认间距或 TJ 调整另外报告 `TEXT_SPACING / DEGRADE`，编码字形／塑形边界和容量仍须满足检查。

一次请求的多次 `.replace(...)` 按登记顺序执行。每次替换后先完成新字体子集嵌入，后续替换才读取更新后的内容；重复嵌入仍累计输入字体字节预算。没有实际命中或所有匹配被跳过时保留原内容流，不把整段文字重新编码。

默认 ERROR；以下只有真实跳过路径，WARN 和 IGNORE 的处理一致，IGNORE 仍记录 issues：

| 功能 | 动作 | 结果 |
| --- | --- | --- |
| TEXT_CAPACITY | SKIP_MATCH | 保留超宽的原匹配范围 |
| TEXT_SHAPING | SKIP_MATCH | 复杂塑形、竖排或非默认文字间距／渲染状态保留原操作 |
| TEXT_BOUNDARY | SKIP_MATCH | 匹配切开一个编码字形的 Unicode 映射时保留该匹配 |
| MARKED_TEXT | SKIP_MATCH | 匹配位于保留的 Span 标记范围或其 ActualText 中，保留该范围，其他普通匹配继续编辑 |
| TEXT_STRUCTURE | SKIP_BLOCK | 非支持的标记／属性、透明 Form、非普通 ExtGState 或引号 show 指令使该页不编辑 |
| TEXT_RESOURCE | SKIP_BLOCK | 无受支持显式嵌入字体等资源时该页不编辑 |

源字体接受[静态 TrueType、嵌入 CFF 和传统 Type1 字体子集](pdf-font-compatibility-api.md)；替换字体仍为调用方显式 TrueType，没有系统字体扫描或隐式替代。非法 Unicode、指令、字体映射、实际缺字、预算、权限、取消及 I/O 均不能忽略。输入使用原静态适配器边界；对象编辑的批注／字段不是本入口的文字对象。

允许原样保留有界、平衡的 `/Span BMC` 和仅含字符串 ActualText 的 `/Span ... BDC`，包括命名 Properties 和嵌套标记。它们不再使标记外普通文字的替换整页失败；标记内部的显示文字和逻辑文字均不修改。命中内部时默认 ERROR，显式 MARKED_TEXT／SKIP_MATCH 才跳过；ActualText 与未命中图像、页码、表格和页区保持。未知标签、MCID／其他属性仍按 TEXT_STRUCTURE 拒绝或整页跳过，错误嵌套、非法属性和预算无条件失败。启用被动图层／标签后仍不编辑标记内的逻辑值与结构归属，完整文档保留边界见[被动结构](pdf-passive-structures-api.md)；原组合保留证据见[阶段九十](verification/phase-90-recovery-closure.md)。

`prepare()` 完成确切编码，`to` 返回实际替换次数和 ACTUAL 报告。目标路径原子替换，借用输出不 close／flush。未命中可返回零修改。其他对象、元数据或独立副本不因此清理，此 API 不提供安全脱敏；脱敏见[独立入口](pdf-redaction-api.md)。

设计见 [ADR-0062](adr/0062-bounded-pdf-text-replacement.md)，原始子集证据见[阶段五十四](verification/phase-54-pdf-text-editing.md)，CFF 及连续显示指令扩展见[阶段七十六](verification/phase-76-pdf-font-compatibility.md)。

## 共享与嵌套 Form

普通 Form XObject 可在实际调用位置进行文字替换，支持局部／继承资源、嵌套、重复和跨页共享。每个发生修改的调用实例单独克隆流与所需资源层，只重绑定对应的 `Do` 名称；原共享流、未命中调用及未选择页保持。多次替换继续在更新后的实例上执行，返回的修改次数按实际成功命中累计。

Matrix、BBox 和显式裁切保留在原 PDF，不经语义重建。当前字体对象从调用时状态继承，不因 Form 内同名字体资源而切换。没有实际替换时不重写页面和 Form 的内容流；短替换后保留原 advance，提取器可能把余量表现为空格。

普通不透明 ExtGState 可以编辑。透明组、需要改写标记内容或带 `StructParents`／`StructParent` 的 Form 使用 `TEXT_STRUCTURE / SKIP_BLOCK` 整页策略，防止克隆后破坏标签所有权；嵌套调用和未选择页不借此丢失原图层／标签。无效引用、隐式递归、执行深度／操作符／字形和总输入字体预算仍硬失败。

原 Form 扩展证据见[兼容路径](adr/0096-pdf-compatibility-paths.md)和[阶段九十三](verification/phase-93-pdf-compatibility-closure.md)。跨定位／样式扩展另由 `pdfInstanceEditing` 调用方及 `scripts/verify_pdf_instance_editing.py` 验证。
