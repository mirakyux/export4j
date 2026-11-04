# 可编辑的栅格布局

`GridLayout` 按显式轨道放置文字、表格和图片；`GridItem` 保存每项的行号、起始轨和跨轨数。设计器可以插入、选择并修改这些区域，XML／ZIP 保存后保留结构。普通正文连续跨栏使用 [ColumnFlow](column-flow-api.md)，两类容器可以在正文中前后组合。

## 在设计器中使用

从空白文档选择“插入 → 独立图文栅格”，先在“选择栅格布局”中选择通栏、两等分、三等分、左宽右窄或左窄右宽。设计器创建 12 轨的对应布局，每格包含真实空段落，不插入示例正文。插入后选中第一格；双击该格或点击“编辑此格”即可编写，也可在格内添加段落、表格或图片。

已有内容可以直接并排：多选同一文档或分节下至少两个相邻的直属段落／表格，再选择“独立图文栅格”。布局选择器默认勾选“将所选内容并排”，按源阅读顺序搬入原内容，保留段落／表格 ID、图片、字段和外链。内容块多于格数时，多余块依次进入末格；少于格数时，剩余格包含空段落。取消勾选则插入新的空白栅格，原选区保留在原处。跨容器、不相邻、动态作用域及固定框等选区会显示限制原因，不展开或拍平内容。

选中栅格或某一格后，检查器先显示当前行的比例图和五种布局预设。点击比例图选择某格，使用“添加行”“拆分此格”“与右侧合并”“更窄”“更宽”调整布局。添加行先选择新行布局，放在当前行后；拆分保留原内容于左格，右格为空段落。减少格数或合并时，原有正文、图片和表格按原顺序接入保留的末格，不删除内容；一次操作写回完整同行坐标，可整体撤销。

画布上的操作与检查器共用同一份 XML：

| 操作 | 行为与替代入口 |
| --- | --- |
| 拖动当前行相邻两格的分隔线 | 分隔线只显示在当前行内；按轨道吸附，同时改变两侧宽度，各保留至少一轨。聚焦分隔线后也可按左／右方向键，或使用“更窄／更宽”按钮 |
| 拖动格边界外的柄 | 拖到同一栅格另一格后交换位置，内容随格子一起移动；“更多”中的“向前交换／向后交换”提供按钮入口 |
| 画布上方的上下文操作条 | 选中格子后显示当前格宽度，提供编辑、拆分、与右格合并及添加行。“本行布局”打开同一布局选择器；“更多”提供宽度和顺序操作，工具条不覆盖正文 |
| 按 Esc 或取消指针操作 | 放弃本次拖动；只有释放到有效位置才写回 XML |

完整行号、起始轨、跨轨数、轨道数及 pt 间距收在“高级轨道设置”中，普通创作无须填写坐标。画布显示真实项边界，结构树保留容器、项及原始子节点。第 14 个内置样例 `grid-editor` 展示跨部分列、偏移留空和正文连续分栏的组合。

行高由最高项的内容决定。改变跨度会按实际宽度重新换行，其他项保持各自位置；修改文字后重新计算行高和分页。修改导致重叠、越界或内容过高时显示错误，不能通过缩小文字、裁切图片或自动覆盖相邻区域来消除错误。

可视布局操作要求栅格项直接写在同一静态栅格中；项级 if／each／include 显示布局只读原因，继续保留 XML 编辑入口。项内的动态正文保持原有作用域。合并会把指向被移除项的静态 `target` 重定位到该项首个有稳定 ID 的直属段落／表格；无法安全重定位或存在动态目标时明确拒绝，保留操作前源码。所选正文带旋转、脚注／尾注锚点、显式分页或 keep-with-next 时，也会拒绝转为栅格。

## XML 和 Java

```xml
<document version="1">
  <grid id="feature" tracks="12" column-gap="12" row-gap="16">
    <item id="article" row="1" column-start="1" column-span="8">
      <p id="summary">横跨八条轨道的说明文字。</p>
    </item>
    <item id="figure" row="1" column-start="9" column-span="4">
      <p id="picture" line-height="84"><image ref="chart" width="120" height="80" alt="示意图"/></p>
    </item>
    <item id="full-width" row="2" column-start="1" column-span="12">
      <table id="details">
        <columns><column weight="1"/><column weight="2"/></columns>
        <row><cell>项目</cell><cell>通栏说明</cell></row>
      </table>
    </item>
  </grid>
</document>
```

字体、`chart` 图片和目标输出由调用方显式提供。轨道间距为纯数字 pt；不接受 `12pt` 后缀或数据插值。`row`、`column-start`、`column-span` 均为必填静态整数。

```java
var grid = GridLayout.builder("feature")
        .tracks(12).columnGap(12).rowGap(16)
        .add(GridItem.builder("article").row(1).columnStart(1).columnSpan(8)
                .add(new Paragraph("summary", "An editable feature.", ParagraphFormat.of(11)))
                .build())
        .add(GridItem.builder("sidebar").row(1).columnStart(9).columnSpan(4)
                .add(new Paragraph("note", "Supporting text.", ParagraphFormat.of(11)))
                .build())
        .build();
```

模型位于 `export4j-model`，布局由 `export4j-layout-core` 完成，模板适配器不依赖组件实现。容器、项和段落／表格有独立 ID；最终片段保留真实原文 UTF-16、字段、图片、外链及宿主根索引。画布使用最终 IR 中的实际区域，后端不重新计算轨道和分页。

## 几何和分页契约

| 项目 | 契约 |
| --- | --- |
| `tracks` | 1～12 条等宽轨道，默认 12 |
| `column-gap`／`row-gap` | 非负有限 pt，默认各 12；扣除列间距后每条轨道须有正宽度 |
| 行号／起始轨 | 从 1 开始；跨轨数是正整数，不能越过轨道数 |
| 顺序和空白 | 源项按行号、起始轨有序，同一行不能重叠；允许空轨道；跳过行号不产生空白物理行 |
| 项内内容 | Paragraph／Table，图片通过普通图片段落；XML 支持 p、table、rows 及其合法条件／循环／片段 |
| 静态和动态空内容 | 静态空栅格／没有子块的项拒绝；含真实空段落的编写格合法；动态全空时省略，仍计绑定访问预算 |
| 行高 | 同一行所有项实际内容高度的最大值，所有项顶部对齐；行距只作用于同一区域相邻实际行 |
| 分页 | 同一行整体放置；当前剩余区域不足时整行移动，过高整行明确失败，不缩小或裁切内容 |
| 组合 | 正文中可以 `ColumnFlow → GridLayout → ColumnFlow`，按实际占用高度同页继续 |

项宽为所跨轨道的总宽加内部轨道间距。例如正文宽 420 pt、12 轨、12 pt 间距时，单轨为 24 pt，6 轨区域宽 204 pt，8 轨区域宽 276 pt。空轨道仍保留真实几何，不生成假表格格子。

本批不支持跨行项、自动放置／回填、任意重叠、固定高度、栅格／连续分栏相互嵌套，以及格内／页区栅格。项内标题／列表／题注、显式分页、keep-with-next、旋转及脚注／尾注锚点也未提供；不支持的组合明确拒绝。`GridItem` 只能属于 `GridLayout`，不能作为独立正文块。

项内最少行数、keep-together 和表格 row-split 设置可以保存，但不会拆开栅格的原子行。共享来源预算计入栅格、项及全部子块；占用行数乘轨道数计入 `maxCells`，跳过的行号不计虚构行。栅格与连续分栏共享每文档 4,000,000 单元的测量预算，试排移页不重复计费。

## 保存、转换和边界

保存动态源码使用 `XmlTemplateBundle`，保留 if／each／include 与表达式。渲染后模型使用 `XmlTemplateWriter` 保存实际项及有序子内容；三个坐标保持静态，动态展开产生重叠或乱序时拒绝，不自动重排。循环生成的非 XML 标识符沿用 `IDENTIFIER` 显式重命名与目标重定位规则。

DOCX／HTML／Markdown 目前没有这个语义栅格的原生映射。直接导出明确拒绝；统一转换显式设置 `GRID_LAYOUT` 为 `DEGRADE` 时按源阅读顺序保留全部项内容，报告轨道、跨度、间距和并排布局损失。WARN／IGNORE 都记录实际损失；不以丢弃整段正文冒充降级。

DOCX 降级时，指向栅格或栅格项的 `REFERENCE_PAGE` 重定向到该容器按源阅读顺序的首个保留段落／表格，并逐字段记录 `PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK` 损失。真实空段落可以作为目标；没有子块的静态容器仍拒绝。

Java `DocumentEditor` 可移动或替换整个栅格，项内定位／查找／修改仍按不透明容器明确拒绝；验证路径会递归检查全部来源、引用和预算。XML／设计器中的项内编辑不扩大该 Java 编辑 API 的支持范围。

既有组件 `GridTable` 面向组件格子和合并续排，HTML/CSS Grid 属于另一个明确的导入子集；本 API 不冒用这两者的完整能力，也不声明 CSS Grid、任意 Word 表格或 PDF/UA 兼容。设计与验收见 [ADR-0110](adr/0110-editable-semantic-grid.md)、[引擎验收](verification/editable-grid-layout.md)及[栅格创作与交互验收](verification/designer-grid-ux.md)。
