# XML 默认字体与颜色

模板可以分别配置正文／列表、六级标题及表格单元格的默认字体角色和 RGB。纸张、字号、行距仍由已有页面／段落／单元格格式控制。示例见 [XmlTextDefaultsExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlTextDefaultsExample.java)。

~~~java
var compiler = XmlTemplate.builder().theme(theme)
        .bodyText(text -> text.fontRole("regular").rgb(0x333333))
        .headingText(1, text -> text.fontRole("bold").rgb(0x224488))
        .cellText(text -> text.fontRole("regular").rgb(0x000000))
        .build();
~~~

配置值为适配器自有的 `XmlTemplate.TextDefaults(fontRole, rgb)`，也提供 Builder／toBuilder。它只包含两项外观属性，不接受完整 `InlineStyle` 后忽略字号或装饰。默认值为 `TextDefaults.DEFAULT`：空角色和黑色。标题级别为 1～6，未单独配置的标题保持默认；正文默认值不传播到标题或表格。

角色名称为空，或符合 `[A-Za-z_][A-Za-z_0-9]{0,255}`；RGB 为 `0x000000`～`0xFFFFFF`。名称不加载字体，渲染时须由引擎显式注册。未知角色在布局失败，不能因模板编译成功而视为字体可用。Builder／回调结果按边界取快照；失败回调不改已有配置。

## 模板中的覆盖规则

~~~xml
<document version="1">
  <heading level="1">报告标题</heading>
  <p>默认文字 <span color="#000000">只改颜色</span>
    <span role="" color="#000000">默认字体和黑色</span></p>
  <numbered list-id="checks">编号与普通正文共享块默认值</numbered>
  <p role="bold" color="#224488">当前块：
    <link href="https://example.com/report">链接文字</link>
    第 <page-number width="24"/> 页</p>
</document>
~~~

| 位置 | 默认与覆盖 |
| --- | --- |
| `p`、`bullet`、`numbered` | 取正文默认值；局部 `role`／`color` 仅改出现的属性 |
| `heading` | 取对应级别默认值，再应用局部属性 |
| `cell` | 取单元格默认值，再应用局部属性；传给其直接块格 `p` |
| 未命名 `span`、`link` 文字及四类字段 | 继承所在块／格的已解析外观；自身支持的属性再覆盖 |
| 命名 `span style="..."` | 完整命名 `InlineStyle` 优先，再应用局部属性；空角色和黑色仍为显式值 |
| 命名 `p style="..."` | 只选择完整 `ParagraphFormat`，不改变外观范围 |

`role=""` 明确恢复渲染器默认字体组，`color="#000000"` 明确设置黑色，不按空字符串或数值猜未声明。列表标记在读取子运行前取块默认值，首个 `span` 不能暗中改变编号。Java 模型的独立标记 API 见[列表标记](list-markers-api.md)。

块格 `p` 可再覆盖该格的默认值；内表重新使用全局单元格默认值，不继承外格局部颜色或字体。与原有内表几何范围一致。图片的 U+FFFC 专用占位运行沿用模型规则，不请求文字字形；图片前后文字按所在范围设置。

`theme`、`bodyFormat` 和 `cellFormat` 不重置 `TextDefaults`，这两类配置顺序独立。局部 `size` 和 `line-height` 仍按既有规则解析，外观不会自动扩大行框。全部分支及引用片段在编译时检查，绑定数据仅作文字；原有输入／展开／绑定预算和取消契约保持。

本单元的默认外观只作用于正文／标题／单元格，不提供完整 CSS 级联。后续[页区指南](xml-page-regions-api.md)提供 XML 页眉页脚及 `DocumentTheme.headerStyle/footerStyle`，两套默认值范围独立。Java 自定义页区也可通过[组件文字 API](component-text-api.md)配置。TextDefaults 设计见 [ADR-0047](adr/0047-xml-text-defaults.md)，证据见[阶段四十](verification/phase-40-xml-text-defaults.md)。
