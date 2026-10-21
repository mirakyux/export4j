# 列表编号与项目符号样式

编号／项目符号与正文分别使用完整 `InlineStyle`。同一列表可以让编号使用真实粗体与品牌色，正文保持普通字体或自己的富文本。可运行代码见 [ListMarkerExample](../examples/src/main/java/com/mirakyux/export4j/examples/ListMarkerExample.java)。

~~~java
var item = NumberedItem.builder()
        .id("check").listId("checks").text("检查结果")
        .format(format).level(1).restartAt(3)
        .markerStyle(style -> style.fontRole("bold").rgb(0x224488))
        .build();
var bullet = BulletItem.builder()
        .id("note").text("说明").marker("•").format(format)
        .markerStyle(markerStyle).build();
~~~

类型位于 `com.mirakyux.export4j.model`。`format` 为 `ParagraphFormat`，`markerStyle` 为 `InlineStyle`。字体由 `DocumentEngine.fontRole` 显式注册；标记没有独立节点 ID、外链或资源所有权。长度使用 pt，基线偏移正值向下。固定行距须同时容纳标记和正文，不会自动放大、缩小或裁切。

Builder 的 `text` 和 `inlines` 相互替换，后调用者生效；运行列表立即复制。`format`、`markerStyle` 均接受完整值或同步嵌套回调。未声明标记样式时，`build()` 按最终段落字号产生旧默认：当前默认字体组、黑色、无装饰和零偏移。声明后保存完整值，后续修改 `format` 不改写标记。`defaultMarkerStyle()` 显式恢复按最终格式派生。

`toBuilder()` 保留已经解析的完整标记样式，不能按“等于默认值”猜设置意图。例如旧对象复制后只改段落字号，标记字号仍保持；需要一起恢复时调用 `defaultMarkerStyle()`。回调返回后再编辑所保留的子 Builder 不影响已加入值。

`paragraph()` 只返回正文。布局在编号计数确定后添加独立前缀运行，不根据正文首运行推测标记样式。原有 1～9 级计数、重启、跨节继续和编号引用不变。诊断索引仍对应降低后的段落 UTF-16，包括生成前缀；缺字策略由标记实际选择的字体组决定。

## 兼容与 DOCX

原有构造器和 `rich(...)` 工厂保留，新增工厂重载在末尾接受完整标记样式。旧纯文字默认保留原排版路径。两个公共 record 新增末尾分量 `markerStyle`，会改变反射、record pattern、equals／hashCode 和字符串形状；保留旧构造入口不等于 record 形状或正式二进制兼容承诺。

DOCX 使用标准 `w:lvl/w:rPr` 输出标记样式，收集仅出现在标记中的角色。导入读取实际选中的 level 的直接样式，不从正文首运行推导；缺席时采用该段字号的旧默认。字号／偏移沿用既有半 pt 量化，不能保证任意小数值精确互转。具名角色仍沿用 DOCX 字符样式映射边界，不把任意角色自动绑定到 Word 物理字体。

未知编号属性／样式须按 STRICT 拒绝或 ALLOW_LOSS 返回诊断，畸形结构、重复身份和非法值不能以允许损失掩盖。外部编号重启／override 等既有导入边界保持；本单元不承诺一般 Word 文件无损导入或与 PDF 同分页。

XML 默认字体、颜色及标记一致性见[XML 文字默认值](xml-text-defaults-api.md)。设计见 [ADR-0046](adr/0046-list-marker-styles.md)，本地证据见[阶段三十九](verification/phase-39-list-markers.md)。
