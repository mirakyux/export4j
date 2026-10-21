# ADR-0048：XML 固定页眉页脚与独立主题

## 状态

已采纳。

## 背景

XML 已支持页面尺寸和正文模板；页眉页脚仍需在 Java 中声明。XML 适配器只依赖语义模型，不能为页区反向依赖组件和布局模块。页区还必须在正文分页前预留高度，并在目录偏移、分节显示页码和总页数确定后解析字段。

## 决策

- 模型新增不可变 `PageRegion`、`PageRegions` 和 `PageDocument`。页区只保存 pt 尺寸、段落与固定宽度 PAGE/PAGES 字段，不保存组件、字体句柄或后端状态。
- `DocumentTheme.headerStyle/footerStyle` 保存独立的完整 `PageRegionStyle`：段落几何、默认角色和 RGB。编译时解析，正文／单元格默认值不向页区级联。p／span 的命名完整样式沿用既有规则，局部空角色和黑色按属性存在性覆盖。
- `DocumentTemplate.renderWithPageRegions` 用一次数据快照及共享绑定预算生成 `PageDocument`。既有四参构造器保留，默认没有页区；旧 `render` 遇到非空页区明确失败，避免返回一个丢失页区的 Document。record 组件数量改变，不承诺已编译二进制或 record 反射形状兼容。
- XML 主源可在可选 page 后、正文前各声明一次 header/footer，顺序不限。高度必填且为正，gap 可省略为零，支持页面绝对长度单位。区域内只有 p，行内只有文字、span、page-number 和 page-count；变量可用于文字，几何和结构静态。片段不能声明页区，但主源区域可使用展开后符合语法的离线段落片段。
- 组件模块以 `PageRegionTemplates.from` 转为既有 PageTemplate。单页局部布局复用 DocumentLayouter，限制为一页；复用 InlineFields 的保留框解析，再由 DocumentPainter 绘制。后端不参与换行或分页。
- `DocumentEngine.layout/export(PageDocument)` 使用随文档携带的页区。配置了 Java pageTemplate 时拒绝冲突。需要组合背景、前景或分节模板的调用方显式使用转换器和已有分节入口，不暗中覆盖配置。

## 边界与后果

每区最多 256 段、4,096 行内运行／字段和 100,000 原文码点；绑定沿用全模板预算，实际呈现另受单页局部布局及整文档组件输出预算控制。空间不足、未登记角色、页码宽度不足均失败，不缩小或裁切。布局遵循同步取消，无资源自动加载或新依赖。

页区诊断使用原段落按声明顺序直接拼接的 UTF-16 位置，不插入分隔符；最终字段缺字警告映射到对应 U+FFFC 标记。物理页和 HEADER/FOOTER 由最终页区组合补齐。

本子集不包含 XML 分节／首页奇偶变体、XML 背景前景、图片、表格、链接、跨文档引用和 DOCX 页眉页脚。DOCX 适配器不接受 PageDocument；显式取 document() 只选择正文。这些边界独立于 Java 已有的页区组合能力。

后续扩展：页区图片／语义表格、XML 分节和 DOCX 默认页眉页脚见 [ADR-0049](../adr/0049-semantic-page-regions-and-sections.md)及[阶段四十二](../verification/phase-42-sections-and-docx-regions.md)。上述原始子集边界为该阶段快照。
