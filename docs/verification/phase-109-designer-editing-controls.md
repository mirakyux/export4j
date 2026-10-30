# 阶段一百零九：设计器的专用编辑控件

## 范围

补齐 [HTML 设计器](../designer.md)的页面／页区面板、上下文属性、表格样式预设、富文本片段，以及 XML／JSON 格式化和高亮。它们操作现有 XML 源码及独立 JSON，未新增模板类型、第三方依赖或库 API；核心布局、绘制和后端继续保持原职责。

## 行为与检查

- 页面设置覆盖根默认及已有分节，预设／自定义尺寸、方向和统一／四边页边距写回既有 `page`。页眉页脚按默认／首页／偶数页编辑完整区域；简单文字支持最终页码占位，复杂区域和共享来源保持源码编辑。
- 属性面板提供当前节点的类型化输入，高级属性原样保留。表格预设写入真实内边距、边框和直接单元格字号／行高，内表不被批量覆盖。
- 富文本以有序 XML 片段编辑，保留表达式、链接、图片和字段；应用后执行既有编译及布局，不使用 DOM 的纯正文替换混合内容。
- XML 格式化只调整结构容器空白，混合文字、注释和变量保持；JSON 使用原有有界解析器和精确数字，重复键等非法输入拒绝。格式化只返回草稿，不改变服务端版本；应用后才进入历史。高亮输出转义原文，不执行上传内容。

设计器 15 项 Java 测试通过，新增 3 项覆盖格式化语义／幂等、大数／歧义和页面＋页区＋富文本＋表格的实际布局；服务测试增加格式化不改变工作区版本。`-Xlint:all -Werror`、设计器构建／安装／ZIP 及两份脚本语法检查通过。没有因应用内控件修改重复执行无变化的核心库验收。

## 浏览器和最终文件

在 Windows 的 Chromium 内嵌浏览器实际完成：

1. 巡检报告设为 A5 横向，统一边距 `12mm`、左边距 `18mm`，添加默认页眉／页脚。
2. 在 `report-end` 添加 ` UI-RICH` 片段，设置真实 bold 角色、`#224488`、12 pt 与下划线并应用。
3. XML 格式化及高亮实际显示；JSON 增加 `precision` 值 `12345678901234567890.123456789`，格式化并重新预览，数值保持。
4. 插入表格并应用紧凑预设，再将上内边距改为 7 pt、表前外间距改为 11 pt、边框改为 false。选择偶数页变体，以简单文字输入添加 `UI-EVEN` 和 `EVEN {page} / {pages}`。
5. 实际预检／下载两页 PDF，保存原始模板 ZIP 和独立 JSON。源码保存变量、条件和循环，没有变为已绑定正文。

最终 `distZip` 解包后，仅使用分发包中的 JAR 和资源、在无桌面 JVM 模式启动新服务，重新打开上述 ZIP 与 JSON；两页预览、A5 横向及非对称边距、页区和富文本声明保持。没有使用源码资源覆盖包内页面。

另在浏览器加载两节样例，选择第二节段落后，页面面板自动定位该节。仅为第二节设置 A5 纵向、`10mm` 页边距和 `LOCAL HEADER`，第一节保持 A4、`20mm` 页边距和继承的 `ROOT HEADER`。下载的 PDF 共两页，pypdf 核对各页 MediaBox、正文横坐标和页眉归属，Poppler／PDFium 在 192 DPI 双渲染并逐页查看通过；第一节的纸张、边距和页眉未被第二节设置覆盖。

`scripts/verify_designer_editing.py` 使用标准 ZIP/XML、精确 JSON、pypdf、PDFium 及 Poppler 检查实际下载文件：纸张 MediaBox、非对称文字边界、真实粗体字体及 RGB、两种页区文本和最终页码、表格无边框绘制、数据／资源及样式声明均通过。含字段的绘制行使用标准 ActualText；pypdf 原始内容提取不识别该替代文字，另核 ActualText 和 PDFium 实际字段文字，不把占位字符当作最终字段。两页均在 192 DPI 双渲染并逐页查看，未更新视觉基线。

这一批浏览器证据不代表任意浏览器、任意文档或完整文字处理器兼容，也不关闭 GAP-14.2 的剩余平台矩阵。

## 复现

```powershell
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-validation :export4j-designer:test :export4j-designer:installDist :export4j-designer:distZip
node --check export4j-designer/src/main/resources/designer/web/app.js
node --check export4j-designer/src/main/resources/designer/web/editing.js
.\gradlew.bat :export4j-designer:run
```

按上述浏览器步骤保存样例后，使用固定[验证依赖](../../scripts/requirements-verification.txt)及 Poppler：

```powershell
python scripts/verify_designer_editing.py --template build/verification/designer-editing/source/template.zip --data build/verification/designer-editing/source/data.json --pdf build/verification/designer-editing/source/document.pdf
```

原始下载、截图、日志和双渲染图放入忽略的 `build/verification/`；共享文档只保留操作及可复现断言。没有执行提交、推送或正式发布。
