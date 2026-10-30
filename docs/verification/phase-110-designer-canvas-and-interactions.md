# 阶段一百一十：设计器连续画布与交互

## 范围

在可选 [HTML 设计器](../designer.md)中提供连续分页画布、实际元素命中与排序、正文直接富文本编辑、常驻资源栏、元素快捷键和右键菜单。界面继续编辑已有 XML 模板／普通 ZIP 与独立 JSON；没有新增模板格式或库 API。

## 行为

- 所有页面沿纵向排列。页面选择器只滚动定位；显式分页符显示可选择标记，自动分页显示边界。预览图片按可见范围加载和释放。
- 组件、图片资源和元素手柄使用指针拖动；命中前／后插入线后，静态同级节点可排序，跨容器及未应用草稿会拒绝。无 ID 节点预览使用临时映射，保存源码不带临时 ID。循环／条件内容的手柄指向整个源控制块，绑定数组内项目顺序仍由 JSON 决定。
- 画布／结构树右键和聚焦元素快捷键支持编辑正文、插段落、复制、移动、删除及撤销。无 ID 列表移动后继续选中被移动项；正文输入中的 Backspace 等只编辑文字。
- 正文中的粗体、颜色、字号、下划线及删除线映射到既有行内 XML；链接、图片、字段和表达式保持对象。图片引用在属性中显示预览，字体角色按当前元素内文字片段列出；资源栏持续显示。
- 单图片段落按引擎返回的真实图片几何命中与等比缩放，尺寸和行高同步写回。XML／JSON 格式化、高亮及精确大数在连续画布视图仍可用。

## 验收

Windows Chromium 内嵌浏览器中实际验证：静态无 ID 列表拖动后按源顺序显示，结构树拖动可再次排序；编号列表通过 Alt+↑ 移动并撤销恢复。循环块整体移动后，三个绑定项目顺序保持。右键复制、Delete、撤销／重做和直接正文输入分别触发对应动作，正文中的 Backspace 未删除元素。图片从常驻资源栏拖入，缩略图选择和 40 × 24 至 80 × 48 pt 的画布缩放可用；固定列宽选择互斥属性。页边界、显式分页符、XML／JSON 高亮和格式化在真实页面可见。

保存两份浏览器实际输出并独立验证：两页画布样例由 `scripts/verify_designer_canvas.py` 检查插入顺序、固定列宽、图片几何、显式分页和两页 PDF；直接正文／列表样例由 `scripts/verify_designer_interactions.py` 检查模板变量、资源、列表与数组顺序、精确 JSON 大数、链接、16 × 10 pt 行内图片和一页 PDF。后一份 PDF 预检报告为零项损失、零项警告；PDFium 提取正文顺序与唯一性，Poppler／PDFium 对全部三页进行独立双渲染并逐页查看。没有更新视觉基线。

画布随文档流排版，不支持任意绝对坐标；循环展开的单个绑定项目不能独立拖动。此浏览器证据不代表其他浏览器或任意外部文档编辑能力。

## 复现

```powershell
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-validation :export4j-designer:test :export4j-designer:installDist :export4j-designer:distZip
node --check export4j-designer/src/main/resources/designer/web/app.js
node --check export4j-designer/src/main/resources/designer/web/editing.js
node --check export4j-designer/src/main/resources/designer/web/canvas.js
node --check export4j-designer/src/main/resources/designer/web/interactions.js
python scripts/verify_designer_canvas.py --template build/verification/designer-canvas/source/template.zip --data build/verification/designer-canvas/source/data.json --pdf build/verification/designer-canvas/source/document.pdf
python scripts/verify_designer_interactions.py --template build/verification/designer-interactions/source/template.zip --data build/verification/designer-interactions/source/data.json --pdf build/verification/designer-interactions/source/document.pdf
```

两份实际浏览器下载、截图、双渲染图片及报告保存在忽略的 `build/verification/`。Python 使用[固定验证依赖](../../scripts/requirements-verification.txt)与 Poppler。未执行提交、推送或正式发布。
