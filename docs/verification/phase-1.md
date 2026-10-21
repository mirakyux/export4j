# 阶段一验收记录

- 日期：2026-10-14
- 本地版本：0.1.0-SNAPSHOT
- 范围：Graphics API → Display List → PDF 精确绘制闭环。

## 环境

| 项目 | 本次环境 |
| --- | --- |
| 系统 | Windows |
| JDK | Microsoft OpenJDK 21.0.10+7-LTS |
| Gradle | Wrapper 9.2.1 |
| PDFBox | 3.0.8 |
| JUnit | 6.1.3 |
| 独立工具 | Poppler 26.07.0（pdfinfo、pdftoppm），pypdf 文本提取 |

## 构建与自动化测试

已执行：

~~~powershell
.\gradlew.bat clean build :examples:run --write-verification-metadata sha256 --console=plain
.\gradlew.bat build --console=plain
~~~

两次命令均成功。首个命令生成校验元数据，后续正常构建启用该校验配置。首次建立锁文件时使用了 `--write-locks`；普通构建无需该参数。

| 模块 | 测试数 | 失败／错误／跳过 |
| --- | --- | --- |
| export4j-model | 5 | 0／0／0 |
| export4j-graphics-api | 5 | 0／0／0 |
| export4j-graphics-core | 2 | 0／0／0 |
| export4j-backend-pdf | 13 | 0／0／0 |
| 合计 | 25 | 0／0／0 |

测试覆盖页面校验、不可变快照、RGB 数据所有权、非法状态与路径、文字提取、图片资源、坐标方向、图片方向、文字基线、三次曲线、矩阵组合、嵌套恢复、裁剪、预算、不可表示文字、数值边界、输出失败与并发隔离。

Java 编译使用 `-Xlint:all -Werror`，未产生编译警告。`build` 同时生成 JAR、源码包、Javadoc 包和示例分发包。

## 独立 PDF 检查

输出为 `examples/build/export4j-example.pdf`，共 2 页：

- 第 1 页：595.276 × 841.89 pt，A4。
- 第 2 页：612 × 792 pt，US Letter。

独立解析和渲染命令：

~~~powershell
pdfinfo -f 1 -l 2 examples/build/export4j-example.pdf
pdftoppm -f 1 -singlefile -scale-to 1100 -png examples/build/export4j-example.pdf examples/build/export4j-example-page-1
pdftoppm -f 2 -singlefile -scale-to 1100 -png examples/build/export4j-example.pdf examples/build/export4j-example-page-2
~~~

两页渲染退出码均为 0，并检查了最终 PNG：文字方向、图片方向、路径、页面边界和裁剪区域正确，无非预期遮挡。pypdf 独立提取到两页中的标题、说明和页尺寸文本。

当前 Poppler 输出 `No display font for 'Symbol'` 和 `No display font for 'ArialUnicode'` 提示。示例不使用这两种字体；已检查实际使用的 Helvetica、Times Roman 和 Courier 的渲染。该提示保留为工具限制，不代表已验证所有字体或阅读器。

## 依赖与边界检查

- 15 个锁定的 Maven 坐标均有许可证证据与 SHA-256 记录。
- 核对 Wrapper JAR 官方 SHA-256，并固定分发 ZIP 的校验值。
- 模型、绘制 API 和绘制核心无 PDFBox 引用。
- 公共后端签名使用项目类型与 JDK I/O 类型。
- 自研 Java 文件均含 Apache-2.0 SPDX 声明。
- 构建出的项目 JAR 包含 LICENSE、NOTICE 和第三方许可原文。
- 实际模块依赖单向，PDFBox 为 PDF 后端内部实现依赖。

## 未验证与后续范围

已配置 Windows / Linux 的 GitHub Actions 工作流，但尚未在远端执行。Linux/macOS 本机运行、正式发布、性能基准和广泛阅读器兼容性不属于本次验证结论。

尚未实现段落、表格等内容语义、字体嵌入、中文回退、塑形、自动换行、自动分页、DOCX 和 SVG 后端。

标准字体不嵌入，不能宣称跨阅读器字形完全一致、PDF/A 或字节级确定性。当前预算不提供墙钟超时或总输出字节上限。
