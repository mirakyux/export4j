# 阶段四十六：离线 HTML／CSS 导入

## 实现

依据 [ADR-0053](../adr/0053-offline-html-css-import.md)和[导入指南](../html-import-api.md)，增加 HTML 元素／属性与静态 CSS 子集的语义映射。使用内部 jsoup 解析、有限选择器及源范围；输入完全离线，图片和字体角色显式登记。段落、强调、链接、嵌套列表、双跨度与两层块格进入已有模型，不增加浏览器或第二套排版引擎。

## 验证

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:htmlImport :examples:markdownImport
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_html_import.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_markdown_import.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
```

全仓 1,144 项 JUnit 通过，无失败、错误或跳过；新增 22 项覆盖级联优先级、important、继承、字体变体、缩进／间距损失、图片比例、实体／空白、合并表格／块格、源 ID 防碰撞、UTF-16 与格路径、结构／Unicode、隐藏内容、解析修复、危险输入、零外联、预算和取消。不可用处理方式始终失败，不通过其他样式警告吞掉。

本次新隔离 Maven 调用方通过。运行 SBOM 为 15 个自研库与 8 个第三方组件，共 23 项；新增 jsoup JAR／POM 与上游 SHA-1 及 Gradle SHA-256 一致，MIT 原始许可与归属保留。

独立 HTML 树检查输入跨度与嵌套；pypdf 6.10.0 检查 2 个 PDF 页的文字、唯一性和链接；标准 OOXML 检查 2 个 DOCX 的 gridSpan／vMerge、嵌套表、图片、装饰及 CSS important 颜色；4 个 HTML／Markdown 输出的文字和显式图片字节一致。引用及浮动属性共两项 WARN 损失与原始输入范围相符，调用方的一项 IGNORE 损失仍可查询。Markdown 导入和生产制品回归通过。

全部新页采用 Poppler／PDFium 5.13.0 双渲染并复查，无裁切、遗漏或重叠。样例的原始像素均差约 1.545，差异集中在文字与表线抗锯齿；按既有表格验收的双像素模糊口径约 0.749，变化像素占比约 0.00473，满足既定均差小于 4、占比小于 0.06 的检查。原始与模糊度量均保留，未刷新视觉基线。

## 边界

有限 CSS 语义映射不等于浏览器盒布局或任意网页无损往返。margin 折叠、容器／原始锚点、未支持属性及隐藏内容均有明确损失路径；危险构造不能靠 IGNORE 绕过。表格需完整覆盖及正文，递归格深度仍为两层。第三方解析不是硬中断或硬内存沙箱，未进行跨平台人工 Word 保存回读或长期容量验收。
