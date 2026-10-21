# 阶段三段落分页验收

- 日期：2026-10-14
- 对象：`0.1.0-SNAPSHOT`。
- 结论：语义段落与基础流式分页通过本地验证；阶段三整体尚未完成。
- 关联：[布局 API](../layout-api.md)、[ADR-0004](../adr/0004-paragraph-pagination.md)

本记录保存段落首批交付时的范围、68 项测试和原始示例证据。后续基础表格、当前测试数及根包迁移见[表格验收](phase-3-tables.md)；当前源码及 API 示例统一使用 `com.mirakyux.export4j`。段落示例的结论已移除“表格将在后续实现”的旧说明，原始文件大小不作为当前制品断言。

## 实现范围

模型层新增语义文档、普通文本段落、格式、页面设置与页边距。新增布局模型和布局核心两个模块，当前共 9 个实际模块。

支持每段一次换行、按完整行跨页、首行和后续行缩进、左中右对齐、段前段后间距、保持整块、空段落和空文档。分页结果保留段落标识、顺序、原始行号及 UTF-16 来源范围，绘制转换不重新测量或分页。

## 环境与构建

使用 Windows / PowerShell、Microsoft OpenJDK 21.0.10+7-LTS、Gradle Wrapper 9.2.1、PDFBox / FontBox 3.0.8、JUnit 6.1.3。

~~~powershell
.\gradlew.bat build :examples:run :examples:textPdf :examples:flowPdf --console=plain
~~~

普通构建结果为 `BUILD SUCCESSFUL`，无需更新锁文件。生产及测试编译启用 `-Xlint:all -Werror`，Javadoc、源码包、JAR 和示例发行包构建通过。

新增模块的依赖配置通过 `:export4j-layout-core:dependencies :export4j-layout-model:dependencies --write-locks` 解析并写入锁文件。未新增 Maven 坐标或外部插件，既有 15 项依赖的校验元数据继续使用。

CI 已加入三个示例任务，但托管平台 Windows / Linux 执行尚未验证。

## 自动化验证

| 模块 | 通过用例数 | 内容 |
| --- | --- | --- |
| model | 8 | 原基础校验及语义快照、唯一 ID、页边距与格式校验 |
| font-core | 4 | 字体所有权、有界读取、许可标志与无效输入 |
| text-core | 12 | 换行、回退、Unicode、行框及文本预算 |
| graphics-api | 5 | 绘制数据与输入契约 |
| graphics-core | 2 | 指令构建与路径 |
| layout-core | 16 | 分页、来源、缩进、对齐、间距、保持整块、空内容、舍入边界、预算、失败上下文、绘制与并发 |
| backend-pdf | 21 | 原输出验证及新增五页流式文档和空文档集成测试 |
| 合计 | 68 | 0 失败、0 错误、0 跳过 |

几何测试确认片段位于内容区域内，按 0.000000001 pt 绝对误差核对浮点包含关系。另用 `0.3 / 0.1` 与比该高度低 `0.00000001 pt` 的区域验证舍入边界和真实不足高度的区分。

跨页测试检查行顺序、连续来源范围、起始行号、基线重定位与三种对齐。保持整块无法放入新页时返回 `CONTENT_TOO_TALL`，段落及页数等预算返回 `BUDGET_EXCEEDED`，文本失败保留原因及节点／页面／源位置。

五页 PDF 集成测试使用两份真实字体，提取 21 行编号中英文并与原始行列表逐项比较，确认无丢失、重复或顺序变化；检查页数、尺寸、元数据、字体嵌入和调用方输出流所有权。

## 独立输出检查

使用 Poppler 26.07.0、pypdf 6.10.0 检查自动分页报告：

~~~powershell
pdfinfo examples/build/export4j-flow-example.pdf
pdftoppm -scale-to 1200 -png examples/build/export4j-flow-example.pdf examples/build/export4j-flow-example
~~~

输出为 3 页 A4，595.276 × 841.89 pt，PDF 1.6，68,984 字节，未加密。长正文跨 3 页，连同标题、介绍与结论共 6 个段落片段。

逐页检查长边 1,200 px 的 PNG，确认标题、正文、页眉和页码无缺字、重叠或裁切；跨页处只在完整行之间拆分，末页结论与正文同页。

pypdf 的普通提取会依据位置在中文字符之间推断额外空格，本次独立语义核查先归一化空白。检查确认“片段 01”至“片段 22”各出现一次且顺序正确，每段的中英文固定正文各出现 22 次，结论和三页页码完整。原始提取结果保存在 `examples/build/export4j-flow-example.txt`。此项不宣称所有阅读器可原样复制空白。

每页字体字典均包含嵌入字体与 ToUnicode。全文复用同一组子集字体：

| 字体 | 解压后的嵌入字体流 |
| --- | --- |
| LiberationSans | 11,980 字节 |
| LXGWWenKaiTC-Regular | 55,184 字节 |

更新后的单页文本示例同样经过独立渲染及提取检查。原有精确绘制示例继续可运行。

## 字体与分发

新增固定 Google Fonts 提交中的 `LXGWWenKaiTC-Regular.ttf`，原始大小 13,110,528 字节，采用 SIL OFL 1.1，许可、版权及 SHA-256 见[字体资源](../../assets/fonts/README.md)。

站酷小薇体的“回”字在独立 FreeType 直接渲染时也呈实心轮廓，因此当前混排示例与测试改用霞鹜文楷 TC。原字体字节及阶段二记录保留，未修改字体本身。

核心 JAR 不打包示例字体；示例 JAR 包含已登记的字体、版权及许可文件。新增布局模块没有 PDF 后端生产依赖，模型层继续只依赖 JDK。

发行检查确认字体与许可字节同源文件一致、核心 JAR 保留 LICENSE / NOTICE；15 项锁定 Maven 坐标及缓存制品 SHA-256 与审查清单匹配。`jdeps --api-only` 未发现 Apache 类型暴露在公共签名中，文档链接、标题层级与自研 Java 的 SPDX 声明检查通过。

## 限制与后续工作

尚未实现表格、跨页重复表头、孤行控制、与下段保持相邻、分节、手动分页节点、图片语义节点、富文本或正式页眉页脚组件。页眉和页码目前来自示例中的额外绘制，不属于分页模型。

复杂塑形、双向文字、完整中文禁则、DOCX 和其他后端仍未实现。本次未进行大文档性能基准、跨 JDK 一致性测试、PDF/A、PDF/UA 或无障碍标签验证。

测试预算是结构限制，不等于运行时间、全部内存或输出字节上限。下一步推进基础表格模型、列宽与行高、不可分割行及重复表头。
