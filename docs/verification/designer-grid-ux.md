# 设计器栅格创作与交互验收

## 范围

本次调整 `export4j-designer` 的栅格交互，不改变 Java 栅格模型、轨道测量、整行分页或跨格式转换契约。引擎证据继续见[可编辑栅格验收](editable-grid-layout.md)，操作说明见[栅格指南](../grid-layout-api.md)。

插入栅格先选择可视布局，每格只有真实空段落。原有相邻正文可直接转为并排布局；分隔线同时调整两侧，拆分、合并和比例预设保留内容。行号和轨道数收进默认关闭的高级设置。主要操作位于画布上方的上下文工具栏，页面只显示当前行的分隔线、活动格边框及格外拖柄。空格提示属于非打印编辑层，不写入模板。

## 可复现检查

从仓库根目录构建设计器，需要 JDK 21、Node.js 22 或更高版本及 Chrome：

```powershell
.\gradlew.bat --max-workers=1 --no-parallel `
  :export4j-designer:test :export4j-designer:installDist :export4j-designer:distZip
```

从 `export4j-designer/frontend` 运行全部浏览器场景及独立 XML 操作测试：

```powershell
$env:EXPORT4J_STUDIO_OUTPUT='build/verification/designer-grid-ux/browser'
node --test tests/studio.test.mjs tests/grid.test.mjs
```

需要只定位栅格交互时，设置 `EXPORT4J_STUDIO_SCENARIOS='grid ux|grid editor|built in grid'`；完整回归前移除该变量。测试自行启动安装分发中的本地服务，使用无界面 Chrome，并在结束时关闭浏览器和服务。输出目录相对于仓库根目录。

| 场景 | 检查内容 |
| --- | --- |
| 空白创作 | 从“空白文档”选择两等分，验证原文为空，真实双击输入两个独立区域及多段正文；高级设置始终关闭 |
| 调宽、拆分及交换 | 实际鼠标把半宽调为三分之二／三分之一，单次撤销与重做；拆分新格为空，合并和拖柄交换保留原文 |
| 已有内容并排 | 多选原有段落转为两格，保留原 ID、完整文字和颜色样式；撤销恢复原容器 |
| 键盘和取消 | 分隔线左右键、更宽及顺序按钮；Esc、pointercancel 和取消布局选择不修改源码 |
| 本行布局与添加行 | 从格内段落进入上方“本行布局”，三格改两格时按源顺序并入末格；新增行通过相同可视选择器创建空格 |
| 密集布局和窄屏 | 既有多行栅格及偏移格可选；操作条位于页面上方，980 px 视口无横向溢出 |
| 保存与导出 | ZIP 下载、重开后源码相同；真实 PDF 下载，编辑边框、按钮和空格提示不进入输出 |
| XML 安全边界 | 真实 Chrome DOM 下检查引用重定位、匿名项、动态作用域、表内脚注锚点、不安全声明和无效几何的拒绝 |

实际截图、PDF、ZIP、几何和成功场景清单保存在 `build/verification/designer-grid-ux/browser/`。空白创作的 `grid-ux.pdf` 包含三段实际输入的非空正文；拆分后再合并产生的一个真实空段落保留，不为缩短页面而删除。最终布局为 12 轨、8+4 分配、12 pt 列距和 42 pt 页边距。

## 实测结果

最终 `test`、`installDist` 和 `distZip` 通过，55 项设计器 Java 测试无失败、错误或跳过。完整前端回归包含 38 个实际浏览器操作场景和 18 个 XML 行为场景；Node 测试器连同两个父测试报告 58 pass、0 fail、0 skipped。普通正文编辑、富文本、表格、资源撤销、连续分栏、固定组件和窄屏也在本次回归内。

`grid-ux.pdf`、`grid-editor.pdf` 和 `grid-editor-sample.pdf` 共三页，均用 Poppler 26.07.0 和 PDFium（pypdfium2 5.13.0）以 192 DPI 独立渲染，并逐页目视检查。固定 blur 2 px 后，最大平均通道差为 1.549654、最大变化像素比例为 0.009776，低于既有阈值 4／0.06；没有刷新历史视觉基线。结构工具为 pypdf 6.10.0，图像工具为 Pillow 12.3.0。

空白创作输出的三段非空文字各出现一次，源阅读顺序与最终 PDF 字形顺序一致；实际 8+4 区域、12 pt 沟槽和 42 pt 边距通过几何检查。ZIP CRC、原始 ID、三段非空正文及一个真实空段落通过独立检查，没有示例说明或编辑提示进入正文。页面上的正文、表格及图片没有裁切、重叠或越轨。界面截图检查了布局选择器、空格、输入后内容、调宽预览、密集多行样例及 980 px 窄屏。

本次独立结果与输入摘要位于 `build/verification/designer-grid-ux/independent-20261102T101407159041Z/report.json`，最终应用和分发字节摘要见 `build/verification/designer-grid-ux/final-artifacts.json`。旧的引擎验收记录保持不变。

## 能力边界

独立栅格内各格分别编写，正文不会流入相邻格；连续正文使用 `ColumnFlow`。图表只跨部分栏并让同一正文自动绕排仍不在本次范围。项级条件、循环和片段不能通过可视操作跨作用域重排；无法安全重定位的被合并项引用明确拒绝。

本次不重新验收全部 ManuWeave 模板，不扩大 Word 原生栅格、任意 CSS Grid 或投稿合规承诺。构建和本地分发不等于提交、推送或正式发布。
