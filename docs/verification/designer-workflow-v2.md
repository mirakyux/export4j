# 设计器表格、容器与分栏编辑验收

## 本次变化

本次补齐表格列宽、当前格结构操作、容器内部内容操作和光标处通栏插入，并修正示例数组数值精度、资源与正文撤销顺序、表格剪贴板分隔和预览状态。没有修改 Java 文档语义模型或后端分页契约，设计器仍编辑 XML 模板，调用既有引擎生成预览及导出文件。

| 操作 | 实际行为与回归断言 |
| --- | --- |
| 表格列宽 | 从引擎单元格外框定位列界；拖动松开后提交，Esc 取消；方向键调整 1 pt，Shift 调整 10 pt；支持精确输入。相邻两列保存为固定 pt，总宽保持，其他列及内容保留 |
| 当前格结构 | 可在当前格上下插行、左右插列、删除行列并打开合并／拆分；合并覆盖范围随增删扩展或收缩，删除纵向锚格行时保留仍覆盖下一行的原内容及引用 |
| 内部内容 | 显式复制／删除当前内部段落或表格，保持外层容器；编辑时先完成，删除保护外部引用及栅格项／正文分栏的最后内容块 |
| 光标处通栏 | 在分栏直属段落光标处插表格／图片，自动拆成前部分栏、通栏内容和后部分栏；保留格式、链接、变量及行内对象，图片选择取消不拆段，不改变草稿 |
| 示例数组 | 两个绑定入口新增数组时保留原 JSON 数值 token，包括超过安全整数范围的大整数、高精度小数和指数写法；取消不提交 |
| 混合撤销 | 资源操作与文字操作按实际顺序撤销／重做；拒绝的资源恢复不移动历史位置、不改变内容；服务端恢复在版本和保留预算内校验完整候选后原子提交 |
| 表格剪贴板 | 优先采用原纯文本；只有 HTML 时，按源行格保留 Tab／换行及空格子，避免相邻格文本拼接；不展开合并跨度 |
| 预览状态 | 明确区分正在校准、校验失败、待更新和已同步；成功只对应本次实际请求的源码、数据和资源身份，失败保留旧图但不显示同步成功 |
| 工具条与对话框 | 容器工具条预留稳定两行，选择前后不移动纸张，双击保持目标；从单元格打开布局对话框后，单选、多选和关闭均不访问尚未创建的侧栏控件 |

具体操作见[设计器指南](../designer.md)和[正文分栏指南](../column-flow-api.md)。此前阶段的数字和结论保留在[编辑流程补齐验收](designer-workflow-closure.md)中。

## 复现入口

使用 JDK 21、Node.js 24 和 Chrome，从仓库根目录构建设计器：

```powershell
.\gradlew.bat :export4j-designer:test :export4j-designer:installDist :export4j-designer:distZip --max-workers=1 --no-parallel
$env:EXPORT4J_STUDIO_OUTPUT='build/verification/designer-workflow-v2/browser-final'
Push-Location export4j-designer/frontend
node --test tests/*.test.mjs
Pop-Location
```

Linux／macOS 使用 `./gradlew`，并按对应 shell 设置环境变量。前端测试独立启动临时端口服务，结束时关闭测试浏览器与服务。`EXPORT4J_STUDIO_SCENARIOS` 可选定真实浏览器场景；完整回归应移除此变量，避免把筛选结果当作全量通过。

独立检查使用项目[固定验证依赖](../../scripts/requirements-verification.txt)及 Poppler：

```powershell
python scripts/verify_designer_studio.py --source-directory build/verification/designer-workflow-v2/browser-final --output-directory build/verification/designer-workflow-v2/independent-studio
```

本次附加检查脚本及原始报告位于忽略目录 `build/verification/designer-workflow-v2/`，不构成对外发布内容。

## 验证结果

最终构建完成后，60 项 Java 测试全部通过；完整前端回归的 158 个测试节点全部通过，其中包含 61 个真实浏览器场景。失败、取消、跳过均为 0，浏览器脚本错误为空。测试覆盖真实拖动、键盘、精确输入、取消、混合撤销／重做、图片选择取消、空数组模板、跨页编辑和窄屏面板。

最终浏览器下载的两份新增 PDF 和五份容器回归 PDF 均独立检查并双渲染：

| 产物 | 文件与几何结果 | 双渲染平均通道误差／变化像素比例 |
| --- | --- | --- |
| `workflow-v2-table-widths.pdf` | 1 页；每个值恰好一次；表格总宽 360 pt，实际列宽 200／160 pt，格内文字原点分别为 x=39／239 pt | 0.167001／0.002732 |
| `workflow-v2-caret-full-width.pdf` | 1 页；通栏表格位于 x=42..553.27557 pt，宽 511.27557 pt；前段、表格、后段顺序正确，后段恢复双栏，粗体保留，变量绑定结果唯一 | 0.154940／0.002417 |
| `workflow-paste-grid.pdf` | 1 页；草稿、变量、图片、邻格与尾段归属正确 | 0.071380／0.000731 |
| `workflow-paste-columns.pdf` | 1 页；图片保留在连续分栏内，尾段在容器之后 | 0.067338／0.000728 |
| `workflow-paste-cell.pdf` | 1 页；图片保留在当前单元格，邻格及尾段不变 | 0.180065／0.001958 |
| `container-ux.pdf` | 1 页；空数组时修改的列表及正文行模板按真实数组绑定，原模板不被展开结果覆盖 | 0.251927／0.001134 |
| `container-ux-mixed.pdf` | 1 页；同一栅格项内文字、图片及内部表格顺序保持，邻项不变 | 0.176980／0.001669 |

工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和 Poppler 26.07.0。新增两份 PDF 使用 144 DPI，其他五份使用 192 DPI；比较均采用 blur 2 px，低于既有阈值平均通道误差 `<4`、变化像素比例 `<0.06`，没有刷新视觉基线。两份新增 PDF 的全部 Poppler／PDFium 页面及四张新增 UI 截图已目视检查，未见裁切、重叠或残留列宽手柄；768 px 窄屏截图中内容工具条仍可用。截图仅证明所示状态，交互由浏览器场景验证。

另通过既有 `verify_designer_studio.py` 回归固定定位、正文避让、连续编辑、循环副本内部引用、图片资源及 PDF／SVG／PNG 内容。实际下载的容器模板 ZIP 含 8 个成员，CRC、成员名唯一性、XML 模板和绑定结构检查通过。

分发 ZIP 的 CRC 检查通过，其中设计器 JAR 与构建产物、安装目录逐字节一致；本地运行服务的 11 项 HTML／JS／CSS 资源与该 JAR 一致，HTML 只归一运行时注入的会话标记。设计器页面已实际打开，显示预览完成和版式已同步。此处仅核对运行构建，不将默认工作区当作此前用户草稿的恢复结果。

证据位于 `browser-final/`、`frontend-delivery.log`、`gradle-delivery.log`、`independent-v2/report.json`、`independent-workflows/verification.json`、`independent-workflows/container-ux/verification.json`、`independent-studio/report.json` 及 `package-live-verification.json`，均相对上述忽略目录。

## 能力边界

- 列宽交互要求预览已同步且几何唯一。动态列声明、旋转表格、同页同源多实例或格边界不足时不提供拖柄，可继续配置属性或 XML。
- 资源快照受服务端 8 步历史预算约束，本地文字历史最多 100 步。到达已淘汰资源边界时提示并停止，不假装恢复成功。服务仍以内存保存工作区，没有增加持久草稿恢复。
- HTML 表格粘贴成为当前正文中的分隔文本，没有增加二维批量填格。正文分栏的光标通栏入口拒绝非空选区、输入法组合中、过期光标和动态内部作用域，避免破坏源语义。
- 独立栅格各项仍分别编写；连续正文使用正文分栏。复杂局部绕排和一般跨栏浮动定位不在本次范围。
- 本次验证设计器及所列输出，不新增 Word 阅读器、全部期刊模板或投稿合规验收；构建、分发打包和本地运行不等于提交、推送或正式发布。
