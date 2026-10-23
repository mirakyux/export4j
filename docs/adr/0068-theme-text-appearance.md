# ADR-0068：编写时字体角色与颜色级联

状态：已接受。

## 决定

DocumentTheme 增加全局 TextAppearance，正文、六级标题、单元格、页眉和页脚按属性继承。字体角色和 RGB 的声明状态分别保存；空角色及黑色均为有效显式值。分域覆盖不向其他域传播，各属性的 inherit 方法仅清除自身声明。toBuilder、equals 和 hashCode 保留声明意图。

解析仍发生在编写边界。Document 的字符串便利入口和 Table.Builder 的字符串行生成完整样式；完整 InlineContent、TableCell 及 add(Block) 保留传入值。便利列表的标记采用正文域，而非首个正文运行。主题不保留在最终语义模型中，不在后端执行级联。

Documents 的组件入口用既有 StyleScope／FontRoleScope 固定正文默认值。显式颜色、内层字体作用域和具名富文本角色优先；富文本的空角色继续按原契约选择当前作用域默认字体。几何修改不清除已选外观。未知角色、缺字、固定行框、取消和预算沿既有路径处理。

XmlTemplate 的未声明域采用主题外观。原 TextDefaults 是完整覆盖，无论在 theme 前后设置均优先；回调从当前解析值开始并完成快照。inheritBodyText／inheritCellText／inheritHeadingText 恢复主题。XML 局部属性、完整命名 span、格内范围和字段规则保持。

## 页区与兼容

默认页眉／页脚的几何仍为独立 10／15 pt，仅未声明外观继承全局值。完整 headerStyle／footerStyle 及其回调把两个外观属性固定为显式快照；单项恢复不改变另一属性或几何。没有按空角色／黑色猜测未声明状态。

无全局配置时，字符串模型、绘制和原 XML 默认值保持兼容。本项提供字体角色及颜色的主题级联，未定义完整 CSS、可编辑字段主题、背景／前景图层或 XML 页区变体。

## 验证

模型及跨模块测试覆盖声明意图、单项恢复、快照、局部完整样式、真实字体、列表标记、XML 字段与页区。独立 PDF／SVG／OOXML、新隔离调用方及全部新页双渲染见[阶段六十](../verification/phase-60-theme-appearance.md)。
