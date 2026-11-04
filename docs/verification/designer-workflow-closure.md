# 设计器编辑流程补齐验收

本文保留本阶段的测试与输出记录。表格列宽、合并格增删、内部内容操作、光标处通栏以及精度、历史和预览状态的后续修正，见[后续编辑流程验收](designer-workflow-v2.md)。

## 本次变化

容器内粘贴图片保留当前单元格、栅格项、正文分栏及数据作用域，图片资源与包含图片的完整源码作为一次修改提交，可一起撤销／重做。顶部编辑入口使用当前格；打开 XML／ZIP 时清空旧画布并自动预览新文档；顶部与画布的表格插入入口共用包含真实表头和正文行的默认结构。

表格内支持 Tab／Shift+Tab 按真实单元格锚点导航，选中变量标记仍可切格，没有输入的导航保持原 XML。块模式单元格中的相邻段落可连续编辑，拆分的新段落在换格、返回后保留。进入表格或栅格后，单击同一容器内另一格可继续输入，完成编辑或单击外部后退出。画布统一提供内容编辑、添加及布局操作；检查器保留属性、数据说明、当前行比例图和可连续操作的“表格结构”。

窗口宽度不超过 1100 px 时，侧边工具面板及检查器按需覆盖展开，支持 Esc、关闭、焦点约束与返回，不改写桌面面板偏好。纸张显示宽度变化时，文字和编辑选框同步更新缩放比例。测试数据面板明确区分实际记录与模板；下载提示区分服务会话与浏览器保存结果。空数组无法推断记录类型时，绑定对话框提示填写实际字段路径。

这些变化不修改表格、连续分栏、独立栅格或数组绑定的 Java 模型契约。使用方式见[设计器指南](../designer.md)。

## 验证入口

Windows、JDK 21、Node.js 24 和 Chrome 环境，从仓库根目录构建：

```powershell
.\gradlew.bat :export4j-designer:test :export4j-designer:installDist :export4j-designer:distZip --max-workers=1 --no-parallel
```

Linux／macOS 使用 `./gradlew`。定向浏览器验收使用新安装分发，并独立启动临时端口服务：

```powershell
$env:EXPORT4J_STUDIO_OUTPUT='build/verification/designer-workflows/focused-final'
$env:EXPORT4J_STUDIO_SCENARIOS='workflow|container ux'
Push-Location export4j-designer/frontend
node --test tests/studio.test.mjs
Pop-Location
Remove-Item Env:EXPORT4J_STUDIO_SCENARIOS
```

完整前端回归入口：

```powershell
$env:EXPORT4J_STUDIO_OUTPUT='build/verification/designer-workflows/delivery-browser'
Push-Location export4j-designer/frontend
node --test tests/studio.test.mjs tests/grid.test.mjs tests/bindings.test.mjs tests/containers.test.mjs
Pop-Location
```

浏览器测试使用独立临时服务，结束时关闭测试服务及浏览器。原有工作区不作为写入测试目标。

## 检查范围

| 场景 | 检查内容 |
| --- | --- |
| XML／ZIP 重开 | 新预览身份及命中区域只对应新文档；ZIP 保留模板源码，旧预览不再显示 |
| 容器内粘贴图片 | 栅格、正文分栏和正文单元格中的源归属、原变量及尾段保持；一次撤销／重做同时恢复资源与图片节点 |
| 当前格和单击续编 | 从第二格进入编辑仍定位第二格；单击同容器另一格后可继续编辑，退出时提交有效草稿 |
| 连续段落及键盘 | 块格相邻段落拆分、合并、换格返回及原始 ID 保留；选中变量标记后 Tab 导航；输入法组合期间不拦截 Tab |
| 导航保真与边界 | 未输入直接导航时 XML 完全不变；跨列／跨行合并只访问真实锚格；动态表头及行模板各访问一次，数据不变；首末格向外导航退出，不增加行 |
| 表格插入一致性 | 顶部与画布入口创建相同表头、正文行及列定义 |
| 窄屏面板 | 768、980、1024 px 下画布宽度、横向溢出、覆盖层关闭与焦点返回，以及纸张和文字显示比例一致；恢复桌面时保留布局偏好 |
| 正文点击 | 原位编辑区上方浮动状态条的提示文字与背景不阻挡后方正文点击；“插入变量”和“完成”按钮保持可操作 |
| 相邻段落显示 | 选择外框不以白色光晕覆盖下一段文字上沿，正文在选择及编辑时保持可见 |
| 服务端原子性 | 图片与源码同次提交，单次撤销／重做；非法源码不改变版本、资源及原内容 |

## 本轮结果

浮动状态条点击与选择外框修正完成后，重新构建设计器，56 项 Java 测试无失败或跳过，安装分发及 ZIP 分发构建成功。使用该安装分发执行完整四文件前端回归，104 个测试节点全部通过，包含 53 个真实浏览器场景，无失败、取消或跳过，浏览器脚本错误为空。最终场景及下载产物位于 `build/verification/designer-workflows/delivery-browser/`。

## 文件与独立渲染

最终浏览器回归实际下载的 5 份 PDF 共 5 页，使用 pypdf 6.10.0 独立核对文字、顺序、唯一性、图片和容器几何：

| 产物 | 内容及结构检查 |
| --- | --- |
| `workflow-paste-grid.pdf` | 绑定文字及草稿、图片留在指定栅格项，邻格不变，尾段唯一且位于栅格后 |
| `workflow-paste-columns.pdf` | 文字和图片按连续分栏规则流动，尾段位于分栏容器之后 |
| `workflow-paste-cell.pdf` | 绑定文字及图片保留在指定正文格，邻格及尾段不变，图片未越格 |
| `container-ux.pdf` | 空数组时编辑的项目模板及正文行模板在加载真实记录后正确绑定，各段出现一次 |
| `container-ux-mixed.pdf` | 指定栅格项内的文字、图片和表格按序保留，邻格不变，内容未互相覆盖 |

三份粘贴场景 XML 保留 `${name}`、原始 ID 和源顺序，图片仍属于原容器，尾段保持在最后。实际下载的模板 ZIP 含 8 个成员，CRC、成员名唯一性及 XML 解析通过；数据列表和数据表格分别保留唯一项目／正文行模板、数组路径及别名，绑定结果没有覆盖源模板。

Poppler 26.07.0 和 PDFium（pypdfium2 5.13.0）以 192 DPI 重新独立渲染全部 5 页，使用 Pillow 12.3.0 按固定 blur 2 px 比较：

| PDF | 平均通道误差 | 变化像素比例 |
| --- | ---: | ---: |
| `workflow-paste-grid.pdf` | 0.071380 | 0.000731 |
| `workflow-paste-columns.pdf` | 0.067338 | 0.000728 |
| `workflow-paste-cell.pdf` | 0.180065 | 0.001958 |
| `container-ux.pdf` | 0.251927 | 0.001134 |
| `container-ux-mixed.pdf` | 0.176980 | 0.001669 |

全部低于既有阈值 `4`／`0.06`，没有刷新视觉基线。最终重新渲染的 5 页与已经逐页完整目视的页面 SHA-256 一一相同，报告明确记录此复核方式；未发现裁切、重叠、串格或尾段错位。

最终回归的 12 张界面截图全部目视通过，包括块格连续段落、空数组编辑、图文栅格和 768／980／1024 px 窄屏。选择段落时，后续段落上沿完整可见；窄屏文字比例与纸张一致。截图只补充所列交互场景，不独立证明全部浏览器行为。

独立结构、双渲染及视觉复核报告位于 `build/verification/designer-workflows/delivery-independent/verification.json`、`container-ux/verification.json` 和 `manual-review.json`，范围为上述 5 份 PDF、模板 ZIP 及 12 张界面截图。

## 分发与工作区恢复

分发 ZIP 中的设计器 JAR 与构建产物、独立运行目录 JAR 的 SHA-256 一致，运行服务返回的 8 项静态资源与本次构建逐字节匹配。使用实际模板备份及会话测试数据恢复源模板、JSON 和资源后，源码及 JSON 字节相同，资源逐项摘要一致；恢复后的本地页面显示 4 页，浏览器脚本错误为空。核对报告位于 `build/verification/designer-workflows/package-verification.json`、`workspace-restore.json` 和 `live-8788-smoke.json`。

这些检查证明当前构建与本地运行内容一致，以及已有提交到服务的工作区恢复结果。恢复对象不包括浏览器尚未提交的编辑草稿；本轮没有新增持久草稿恢复，也不构成正式发布验收。

## 能力边界

- Tab 在首末格向外导航时提交并退出，不自动增加行或数据记录。动态数据表格编辑的是正文行模板，记录数量和顺序由 JSON 数组决定。
- 行内单元格中 Enter 仍为换行；需要多个段落时，通过“添加内容 → 文字段落”显式转换为块模式。带 `style-if` 的行内格须先明确移除规则才能转换，不静默丢弃条件样式。
- 粘贴块图片时，禁止块内容的表头格和其他不支持位置明确拒绝，不回退到文档末尾。缺少可用预览几何时，先完成预览再插入。
- 独立栅格各格分别编写，正文不会自动流入相邻格；连续正文使用正文分栏。已有容器嵌套限制继续生效。
- 服务工作区保存在进程内存中，本轮没有新增持久草稿恢复。模板 ZIP 与测试数据 JSON 分别下载，浏览器开始下载不代表文件已保存到磁盘。
- 本轮不新增 DOCX／Word 阅读器或全部 ManuWeave 模板验收；本地构建和运行不等于提交、推送或正式发布。
