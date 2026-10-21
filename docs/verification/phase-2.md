# 阶段二验收记录

- 日期：2026-10-14
- 验收对象：当前本地工作区，`0.1.0-SNAPSHOT`。
- 结论：基础字体与普通水平文字排版闭环通过本地验证。
- 关联：[阶段一](phase-1.md)、[字体和文本 API](../text-api.md)、[ADR-0003](../adr/0003-font-text-layout.md)

本记录保留阶段二验收时的字体、输出和测试数量。阶段三将当前混排示例及测试改用霞鹜文楷 TC，并修改页脚文案；当前产物与测试结果以[段落分页验收](phase-3-paragraphs.md)为准。

## 范围与实现

新增 `export4j-font-core` 和 `export4j-text-core`，目前共有 7 个实际模块。字体显式加载、Unicode 映射、度量、回退、段落换行与行布局进入核心，PDF 后端消费同一份字体和已有字形位置。

已实现静态独立 TrueType、字体嵌入与子集化、禁止子集化时的完整嵌入、中英文混排、首行及后续行缩进、左中右对齐、固定基线间距、显式换行与空行。

混排基线使用整个字体栈的统一最大 ascent / descent，避免每行选中字体现不同时基线间距变化。简单塑形明确拒绝需要复杂处理的输入。

## 环境与命令

| 项目 | 实际环境 |
| --- | --- |
| 系统 | Windows，PowerShell |
| Java | Microsoft OpenJDK 21.0.10+7-LTS |
| Gradle | Wrapper 9.2.1 |
| PDFBox / FontBox | 3.0.8 |
| JUnit | 6.1.3 |
| 独立渲染 | Poppler 26.07.0 |
| 独立结构与文本提取 | pypdf 6.10.0 |

根目录执行：

~~~powershell
.\gradlew.bat build :examples:run :examples:textPdf --console=plain
~~~

结果：`BUILD SUCCESSFUL`。生产与测试代码以 `-Xlint:all -Werror` 编译，Javadoc、源码包、运行包和示例发行包构建通过。字体及文本模块的依赖锁与 SHA-256 校验元数据已生成，普通构建无需更新锁文件。

CI 工作流已加入两个示例任务；托管平台 Windows / Linux 执行尚未验证。

## 自动化验证

| 模块 | 通过用例数 | 主要证据 |
| --- | --- | --- |
| model | 5 | 阶段一页面与元数据约束 |
| graphics-api | 5 | 不可变指令与基础输入校验 |
| graphics-core | 2 | 指令记录、图形状态与路径 |
| font-core | 4 | 字节快照、借用流、有界读取、无效及受限字体、禁止子集化标志 |
| text-core | 12 | 真实字体回退、词与中文标点换行、长词、代理对、硬换行、缩进与对齐、基线、缺字、塑形边界、预算及确定性 |
| backend-pdf | 19 | 原绘制测试与 6 项新增字体集成测试 |
| 合计 | 47 | 0 失败、0 错误、0 跳过 |

新增 PDF 集成测试逐字比较测量宽度、X 位置及 Y 基线，绝对误差上限为 0.001 pt。另以人为指定的 40 pt advance 验证后端使用已有位置，未自行重测或换行。

验证字体嵌入、子集名称、ToUnicode、中文提取、跨页字体复用、借用输出流所有权、字形映射不一致失败、每文档字体字节与文字预算，以及同一后端的独立文档并发隔离。

禁止子集化的测试验证 PDF 内完整字体字节长度与输入一致。字体权限测试只在内存中修改测试输入，不改动仓库字体文件。

## 独立 PDF 检查

输出：`examples/build/export4j-text-example.pdf`。

~~~powershell
pdfinfo examples/build/export4j-text-example.pdf
pdftoppm -singlefile -scale-to 1400 -png examples/build/export4j-text-example.pdf examples/build/export4j-text-example
~~~

Poppler 解析及渲染成功。输出为 1 页 A4，595.276 × 841.89 pt，PDF 1.6，46,355 字节，未加密。PNG 长边为 1,400 px，人工检查标题、中英文正文、缩进、三种对齐和页脚，未发现缺字、重叠、裁切或方向问题。

pypdf 独立提取验证包含“中文与 English”“站酷小薇体”及页脚文字，提取结果保存在 `examples/build/export4j-text-example.txt`。检查页面字体字典确认：

| 字体 | 嵌入的字体流大小 | ToUnicode |
| --- | --- | --- |
| LiberationSans，带子集前缀 | 12,528 字节 | 存在 |
| ZCOOLXiaoWei-Regular，带子集前缀 | 51,816 字节 | 存在 |

当前环境没有 `pdffonts` 命令，字体结构由 pypdf 独立核查。此记录没有将缺少的命令记为通过。

## 资源与分发

已登记两份字体的来源、固定版本、字节哈希、版权与 OFL 原文，见[字体资源](../../assets/fonts/README.md)。示例 JAR 携带字体及同目录许可；核心库不携带两份示例字体。

Maven 坐标仍为 15 项，FontBox 与 pdfbox-io 从既有传递依赖增加为字体模块的直接内部依赖。未新增外部 Gradle 插件、复杂塑形组件或运行时网络获取。

发行材料检查确认示例 JAR 中的字体及同目录许可与源文件字节一致，核心 JAR 均包含 LICENSE / NOTICE，且未打包示例字体。15 项锁定 Maven 坐标与审查 CSV 一致，实际缓存制品的 SHA-256 全部匹配。

通过 `jdeps --api-only --ignore-missing-deps -verbose:class` 检查公共字节码签名，未暴露 Apache 实现类型。文档相对链接、标题层级和自研 Java 的 SPDX 声明检查通过。

## 验证边界

本次验证不包含自动分页、表格、富文本行内样式、DOCX、SVG、完整中文禁则、复杂塑形、双向文字、竖排、彩色字体、PDF/A 或 PDF/UA。输出未加标签，不宣称无障碍结构合规。

固定字体及本次 JDK 配置下的重复布局结果经过断言；未承诺跨任意 JDK 的断行一致性或 PDF 字节一致。结构预算不等于执行时间限制、总输出大小限制或不可信输入沙箱。

已提供 CI 工作流，托管执行尚未验证。下一阶段先实现段落语义节点与页面内容区域，再验证段落跨页与不可容纳内容的推进规则。
