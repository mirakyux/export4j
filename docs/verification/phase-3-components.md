# 阶段三组件与 PDF 交互验收

- 日期：2026-10-14
- 状态：PDF 首版本地验证通过，阶段三尚未全部完成
- 版本：`0.1.0-SNAPSHOT`，根包名与 group 为 `com.mirakyux.export4j`

## 交付范围

新增 `export4j-components`，总计 10 个项目模块。核心定义组件 SPI、不可拆分正文适配器、绝对／锚点／比例定位及页区合成；标准组件包括文本、图像、表格、矩形、椭圆、直线、水印和最终页码。

编排入口提前预留页眉页脚，分页正文，生成前置目录，再解析最终书签、目录链接、评论与字段。PDF 后端输出真实 outline、Text annotation 和 AcroForm，保留可编辑字段及外观。原有段落／表格分页继续有效，无新增外部 Maven 坐标。

公共契约和限制见[组件 API](../components-api.md)，设计见 [ADR-0006](../adr/0006-components-and-pdf-interactions.md)。

## 构建与测试

在 Windows、JDK 21、Gradle Wrapper 9.2.1 环境执行：

~~~powershell
.\gradlew.bat build :examples:run :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf --console=plain
.\gradlew.bat build :examples:componentPdf --console=plain
~~~

两个命令均返回 `BUILD SUCCESSFUL`；第二个命令是补充目录预算测试后的最终复核。编译采用 `-Xlint:all -Werror`，Javadoc、源码包、生产 JAR 和示例 ZIP／TAR 均完成。

| 模块 | 通过测试数 |
| --- | --- |
| model | 11 |
| font-core | 4 |
| text-core | 12 |
| graphics-api | 5 |
| graphics-core | 2 |
| layout-core | 32 |
| components | 14 |
| backend-pdf | 28 |
| 合计 | 108 |

新增 20 项测试，覆盖定位解析、页区预留与最终页数、图层顺序、保留字体回退诊断、越界与预算、不可拆分组件及来源、无效组件原因、图像适配裁剪、椭圆和直线、水印变换、多页目录及累计文字／行数／命令预算、错误目标、单视口表格，以及 PDF 层级／坐标／评论、规范字段与 widget、值／默认值／外观、填写保存重开和交互预算。

CI 工作流已加入第五个示例；托管平台执行尚未验证，本地通过不代表远程 CI 已运行。

另外检查了 115 个 Java 文件的包名、目录及 SPDX 声明，151 处 Markdown 相对链接及标题层级，10 个生产 JAR 的许可证／命名空间和核心包字体隔离。锁文件坐标并集与依赖 CSV 的 15 个坐标一致；`jdeps --api-only` 未发现公共 API 暴露 Apache 或 AWT 实现类型，模型仅依赖 JDK。示例发行 ZIP 包含新增组件模块，文档中的最小 API 示例通过 Java 21 和 `-Xlint:all -Werror` 编译。

## 综合示例

`ComponentPdfExample` 生成 `examples/build/export4j-components-example.pdf`。本次最终样本为 73,443 字节，PDF 1.6，4 页 A4，无加密、无 JavaScript，包含 AcroForm。

| 页面，从 1 开始 | 主要内容 |
| --- | --- |
| 1 | 目录、5 个内部链接、页眉页脚、旋转水印 |
| 2 | 图像、矩形、椭圆、线条、定位说明、小表格、评论及填写区 |
| 3 | 跨页记录 ITEM-01～ITEM-19 |
| 4 | 重复表头、ITEM-20～ITEM-24、结束章节 |

全页固定页区，无正文覆盖页眉页脚。所有页显示对应“第 n 页 / 共 4 页”。目录条目页数字为 2、2、2、3、4，嵌套书签和内部链接解析到同一实际页面及正文位置。

## 独立解析与渲染

工具为 pypdf 6.10.0 和 Poppler 26.07.0。使用 `pdfinfo` 检查页数、页面大小、AcroForm 与脚本信息；使用 pypdf 提取全部页面、遍历 outline 和 annotations；使用以下命令渲染后逐页查看：

~~~powershell
pdfinfo examples/build/export4j-components-example.pdf
pdftoppm -scale-to 1100 -png examples/build/export4j-components-example.pdf examples/build/components-page
~~~

4 页均检查了文字、图片、图形、表格、浅色水印、图标、字段边框与页区，没有发现裁切、重叠或缺字。霞鹜文楷 TC 的提取文本可能在汉字间包含空格，语义比对先归一化空白；不将提取空格视为原始正文空格。

独立断言确认：24 个 ITEM 标记严格按顺序各出现一次，表头在第 4 页重复；5 个书签和 5 个目录链接使用真实页引用，目标 X 为 48 pt；中文评论作者及内容保留。布局测试另外覆盖多页目录及页预算耗尽。

当前 Poppler 在初始化含表单的文件时向 stderr 输出 `No display font for 'Symbol'` 和 `No display font for 'ArialUnicode'`。检查实际表单资源只有标准 Helvetica，正文使用嵌入字体，评论图标是路径；相关页面正常渲染。保留此工具环境限制，不将无警告作为本次独立渲染结论。

## 可填写字段验证

检查 `/AcroForm/Fields` 和全部页面 `/Widget`：

| 字段名 | 类型 | 初始当前值 |
| --- | --- | --- |
| `name` | 单行文本 | `Ada Lovelace` |
| `notes` | 多行文本 | `Review the component report.` + LF + `Editable PDF form.` |
| `accepted` | 复选框 | `/Yes` |

三个字段均保留 `/V`、`/DV` 和非空 `/AP /N`，`NeedAppearances=false`。每个单 widget 终端字段在字段树和页面注释中引用同一个规范字典，没有重复同名字段。复选框包含 `/Off`、`/Yes` 两个外观和一致的 `/AS`。

用 pypdf 克隆原文档并调用 `update_page_form_field_values(..., auto_regenerate=False)`，将姓名改为 `Grace Hopper`、备注改为 `Saved independently with pypdf.`、复选框改为 `/Off`。写入临时验证副本后重开：字段树、widget 有效值和复选框状态均一致，外观流非空。随后重新渲染第 2 页，文字和未选中状态均更新。

pypdf 重建文字外观时没有重画文本框边框，原字段属性仍保留；这个临时副本仅用于验证可编辑性。最终交付使用未被外部工具改写的原示例，已逐页确认其可见字段边框。

## 支持边界

正文组件整块分页，定位表格必须在一个视口内。自定义 SPI 属于可信代码，其执行没有沙箱；命令、文字、页数和交互预算只约束相应结构。

首版字段初始文字仅支持 ASCII，多行可含 LF；没有中文表单字体、下拉框、签名或脚本。水印是淡色不透明 RGB 文字，未实现透明度。没有分节、不同首页／奇偶页模板、孤行控制、保持相邻、任意嵌套流、图文绕排或 DOCX 交互映射。

未验证所有浏览器／PDF 阅读器、Linux 实机、大文档性能、跨 JDK 字节一致性、PDF/A、PDF/UA 和无障碍标签。本地构建、独立工具检查、提交、推送与发布是不同状态。
