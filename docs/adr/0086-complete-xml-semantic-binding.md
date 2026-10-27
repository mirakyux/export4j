# ADR-0086：XML 完整语义绑定与注释保存

状态：已采纳。

## 背景

注释以独立 List<Note> 传给现有编辑器和布局引擎，不属于 Document／SectionedDocument。仅扩展 XML 标签而继续返回文档会丢掉注释。Caption 需要保留原始种类及文字，由布局计算编号；标题／列表已经支持语义文字外链，但 XML 编译器尚未映射。

## 决策

复用 version 1 XML，在主根页配置／页区后、正文或分节前允许单个 notes。note 使用静态 id／paragraph／kind，纯文字正文在根数据范围绑定；顺序保持原编号含义。声明不进入 include、分节、条件或循环，不推测某个展开副本。显式 note-marker 映射既有 InlineField，仅用于普通顶层正文段落，保留位置、宽度和样式。

DocumentTemplate 增加不可变 notes component 及 renderComplete(data)，返回 Rendered(document, notes)。原 4～8 参数构造器保留，默认注释为空；旧 render 系列入口遇到非空注释声明拒绝。record 反射形状及值契约增加 notes，不承诺所有序列化消费者兼容。Builder／toBuilder 保留 literalText 及 notes。

Note.validate 共享注释关系校验：注释 ID 不与正文／页区块冲突；锚点仅为顶层正文段落、列表、Caption；显式标记唯一、同段并有对应注释。模板完整绑定、保存及布局都检查，非法关系不能由损失策略绕过。源码、展开访问、注释 ID／锚点／正文、取消及原子输出沿用既有预算与契约。

caption 保留 id／kind／原文／完整段落格式，不预先生成编号；include／each 的块 ID 及同副本引用复用现有重定位。标题／列表的 link 只接受文字和 span，递归解析时保持原容器权限，禁止图片、字段和嵌套 link。

XmlTemplateWriter 保存显式注释及这两类块语义，在 prepare 中编译并完整绑定静态 XML 后才编码。XmlTemplateBundle.conversion 传递完整文档和注释，避免转换路径再次丢失。未映射的 CrossReference 块／自定义组件仍使用既有真实损失策略。

## 结果

无需新增扩展名、模块、依赖或解释器。支持子集可严格保存、重开、编辑及 PDF 重导出；其他格式保留自身边界，DOCX 不支持注释／Caption，Web 仍报告注释丢弃及题注／字段降级。新语法需要支持该语法的编译器；不据有限样例声称一般无损恢复。API 见[XML 指南](../xml-template-api.md#注释与题注)及[模型保存](../xml-model-saving-api.md)，证据见[阶段八十](../verification/phase-80-xml-semantic-saving.md)。
