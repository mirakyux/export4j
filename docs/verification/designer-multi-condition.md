# 多值条件与实时测试值验收

## 范围

在同一检查器配置 `status = 1`、`status = 2`、`status = 3` 和 `other`，并可在分支内继续添加下级条件。条件的字段、比较方式、比较值及根数据测试值均直接编辑，内容沿检查器面包屑进入和返回。测试值修改后自动预览，没有“更新测试值”按钮。

语法和数据类型见[XML 比较条件](../xml-template-api.md#比较条件与多分支)，操作入口见[设计器指南](../designer.md#模板逻辑与检查器)。本记录补充[基础检查器验收](designer-template-inspector.md)。

## 实现契约

- 复用 `DocumentTemplate.When` 的 yes／no 分支；新增独立的 `TemplateCondition` 比较模型，保留旧布尔条件构造器及编写入口。
- XML 使用 `if test/operator/value`，多分支保存为 else 中的条件链。按顺序判断，仅展开首个命中分支；缺字段或类型错误明确报错，不作为 other。
- 支持等于、不等于、数字大小比较、文本包含及布尔判断。数字使用十进制精确比较，字符串比较值是原文，不是带引号的 JSON。
- 仅将 else 内唯一的 if 平铺为后续分支；then 中的 if 保留为下一层。复杂 else 的其他内容保持原结构。
- 添加、删除、下钻和返回均保留内容及未提交的合法草稿。删除后续分支保留其后的条件与 other；首分支保留。结构操作一次撤销。
- 新增最多 16 个平铺分支，并继续校验整个候选文档的 XML 深度、元素数及源码预算；没有提高默认 32 层深度。

## 自动预览与输入

根数据的文字、数字和布尔测试值直接写入数据草稿，沿既有 380 ms 防抖请求 Java 引擎预览。输入期间不重新安装检查器，保留焦点、光标及输入法组合；过期结果不会覆盖当前预览。导航、导出和撤销时将源码及数据草稿一起提交。

输入 `1e+` 等尚未完成的数字时保留输入并显示原因，预览使用最后一个有效值；不会把无效 token 写入 JSON。未修改的多行字符串保留原始 JSON token，避免 textarea 对 CRLF 的规范化造成无操作写回。编辑目标或当前值已变化时，拒绝旧控件覆盖新值。

循环别名的值来自多条记录，仍在测试数据中编辑对应记录；缺字段或非标量值显示原因，不伪造数据。便捷编辑模式中，测试值仍自动请求引擎预览；画布正文的逐字精确分页沿用“精确排版”模式契约。

## 验证入口

新增 Java 测试检查精确数值、类型错误、短路判断、全分支编译及原有布尔行为。浏览器测试检查多分支的真实操作、草稿合并、撤销、嵌套和实时输入；这些状态交互不能由编译或静态检查证明。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:xmlTemplates
.\gradlew.bat -p verification/consumer -PconsumerBuildDirectory=build/validation-multi-condition --refresh-dependencies build run resolvedLibraries
.\gradlew.bat :export4j-designer:distZip
Expand-Archive export4j-designer/build/distributions/export4j-designer-0.1.0-SNAPSHOT.zip build/verification/designer-condition-dist
$env:EXPORT4J_DESIGNER_DIST=(Resolve-Path build/verification/designer-condition-dist/export4j-designer-0.1.0-SNAPSHOT).Path
cd export4j-designer/frontend
node --test tests/multi-condition.test.mjs tests/template-inspector.test.mjs
npm test
```

使用 JDK 21、Node.js 22 或更高版本及 Playwright 可用的 Chrome。本次使用 Node.js 24.15.0、Playwright 1.64.1、pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和 Poppler 26.07.0。`EXPORT4J_DESIGNER_DIST` 指定解压后的完整分发；测试自行启动随机端口并关闭，仅对专用实例操作。输出目录可通过 `EXPORT4J_CONDITION_OUTPUT`、`EXPORT4J_TEMPLATE_OUTPUT` 和 `EXPORT4J_STUDIO_OUTPUT` 分别指定。重新复现时使用新的解压及输出目录。

## 本地结果与边界

全仓构建、SBOM、本地制品发布、XML 样例和隔离 Maven 调用方构建运行成功。新增模型测试 5 项、XML 测试 4 项通过；API 检查无不兼容声明及第三方类型泄漏。另以本次本地发布 JAR 编译、运行独立调用方，确认旧构造器、新比较入口及超过 JavaScript 安全整数范围的数字比较。

测试值为 1、2、3 和 99 时，实际导出的四份 PDF 各一页，均仅含独立正文和对应分支，其余分支不出现。文字提取及 Poppler／PDFium 全页渲染通过，首分支粗体和颜色保留；值为 2 的 DOCX 经标准 `word/document.xml` 检查，仅有对应分支。既有 XML 模板输出的独立结构及双引擎渲染回归通过。

浏览器定向验收中，多分支 7 项、检查器 17 项通过。真实服务确认新会话无编辑历史时，第一次修改测试值即可直接撤销、重做；多行比较值经过导航、追加分支后仍保留换行。已查看 1600×1000 桌面和 980×760 窄屏截图，长嵌套内容预览使用自然高度，不覆盖操作按钮。

最终分发上的完整前端回归为 **205 项通过**，无失败、跳过或取消。日志为 `frontend-final.log`，浏览器输出位于 `frontend-final/`；最终分发的网页源资源及生成的前端包均与当前构建产物逐字节一致。

完整回归还发现通栏插入可能读取尚未同步的旧光标。命令现先同步浏览器原生选区，不增加撤销步骤。确定性探针将原生光标移到第 5 个字符后立即触发命令：修复前错误读取第 3 个字符，修复后正确读取第 5 个字符，正文、行内格式及变量完整保留。

最终通栏 PDF 的三路独立文字检查及 Poppler／PDFium 全页渲染通过；`LEFT ` 尾空格、绑定变量及实际粗体字体保留，通栏表格宽 511.276 pt，其后恢复双栏。独立证据位于 `independent-caret-final/`。最终多分支 PDF 的双渲染图与已查看的定向验收图一致。

原始日志、输出及截图放在忽略的 `build/verification/designer-multi-condition-20261104/`。这是本地源码、分发及输出验收，不代表正式发布。没有增加任意表达式执行器；DOCX 导出的是绑定后的内容，不含在 Word 内重新求值的模板条件。
