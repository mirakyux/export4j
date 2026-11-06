# 常用 API 简化验收

本轮为常用文件、资源、字体、页面、长表和转换任务补充短入口，沿用原有模型、布局及输出契约。新增 API 属于当前开发源码，已发布的 `0.1.0-rc.1` 不包含这些入口。用法见[链式 API](../fluent-api.md#常用任务的短入口)，可执行调用方见 [`ErgonomicsConsumer`](../../verification/consumer/src/main/java/consumer/ErgonomicsConsumer.java)。

## 验收范围

| 入口 | 本轮核对的行为与边界 |
| --- | --- |
| DOCX 直接读写 | 支持本地 `Path`、`File`、字符串路径和借用流；默认严格读取，允许损失时保留诊断。文件输出采用同目录原子替换，父目录须存在；取消、预算和失败保护旧目标，借用流不关闭 |
| HTML／Markdown 输入 | 文件及流使用严格 UTF-8，有界读取并拒绝非法编码；`read(String)`、`conversion(String)` 继续表示正文，字符串路径使用 `readFile`、`conversionFile`。直接读取立即发生，转换来源在准备时读取；文件自有流关闭，借用流保持打开 |
| 图片加载与尺寸 | `Images` 在组件层有界读取文件或流，再交由既有资源解码验证；`InlineImage.atWidth`、`fit` 按像素比例计算 pt 尺寸，核对尺寸与媒体字节。缩放由调用方显式选择，不改变排版阶段的行框与区域校验 |
| 字体组合 | `FontGroup` 支持可变参数和显式字体文件；`FontSet` 复用默认字体及命名角色。样例核对普通、粗体、斜体、粗斜体四个真实字体变体，未提供的变体不自动合成 |
| 页面与单位 | `Units.pt/mm/cm/inches` 换算为 pt，拒绝非有限值及溢出；有限负值可用于偏移。`PageSize` 的毫米／英寸工厂和幂等横纵向转换继续接受既有尺寸校验 |
| 长表行源 | `TableRow.text` 简化文本格构造。`TableRowSources` 区分可重开的迭代工厂、借用迭代器和自有流；导出释放工厂交出的源，不缓存全部数据。重放仍要求调用方提供相同业务快照，不隐式创建事务或数据快照 |
| 统一转换 | `convertTo(target, destination)` 委托 `prepare(target).to(destination)`，保留实际损失与结果；准备和输出使用原有分阶段执行范围。需要预检或候选模型时仍可单独调用 `prepare` |

## 已执行验证

本轮在 Windows、Java 21、Gradle 9.4.1 本地环境完成 `build sbom publishLocalBundle`。模块 Java 测试报告汇总为 **2,104 项，0 失败、0 错误、0 跳过**；公共 API 比较未发现不兼容声明或外部实现类型泄漏。该结果不代表远端 CI 或其他平台已经执行。

隔离 Maven 调用方通过实际 JAR 运行 `ergonomics` 任务，生成并核对以下输出：

- 8 份单页 PDF 完成 Poppler／PDFium 双渲染及逐页查看；单次、语义和重放长表输出对应像素一致。
- 5 份 DOCX 完成 OOXML 包、正文及图片媒体字节检查，HTML 完成正文检查。
- 字体样例确认四个真实变体；图片样例核对编码字节及等比几何。混排文本分别核对 PDF `ActualText` 与 PDFium 提取结果，未将 pypdf 返回的内部占位值当作逻辑文字证据。

临时签名分发验证覆盖 17 个模块、82 个签名制品、16 个库的 BOM／SBOM 对齐及隔离调用方 JAR 摘要，并验证篡改签名被拒绝。验证使用临时身份，私钥环已删除，未上传制品，也未进行远端 Maven 调用方验收。

## 复现入口与限制

从仓库根目录构建；Windows 使用 Wrapper：

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities --console=plain
.\gradlew.bat -p verification/consumer clean build run ioConvenience ergonomics resolvedLibraries --write-locks --refresh-dependencies --console=plain
```

调用方只通过发布坐标解析本地制品。将本次成功的构建目录交给独立验证器；在项目隔离 Python 环境中安装 `scripts/requirements-verification.txt`，并准备 Poppler：

```text
python scripts/verify_api_ergonomics.py --consumer-build-directory verification/consumer/build --output-directory build/verification/api-ergonomics-output --pdftoppm "<工具路径>"
```

原始日志、输出、渲染图及机器相关工具配置保留在被忽略的 `build/` 下。以上证据限于本轮样例和声明契约，未完成 Word 原生打开、编辑、保存和回读验收，不据此承诺任意文档无损转换、跨平台兼容或正式发布。
