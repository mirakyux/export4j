# 阶段七十七：XML 源码编辑与普通 ZIP 资源包

## 范围

复用现有 `export4j-adapter-template`，提供 `XmlTemplateBundle` 的源码／资源编辑、保存与重开。编译继续交给 `XmlTemplate`，数据绑定继续使用 `DocumentTemplate`。不增加模块、第三方依赖或专用文件扩展名。

普通 ZIP 保存主 XML、片段、PNG／JPEG、显式字体原始字节及许可文本。独立编辑主源和片段后，未修改资源及其原始字节保持；重新绑定不同数据不会改写源码中的占位符。XML 草稿可保存，编译时按既有诊断拒绝非法语法、未知资源和危险 XML。

设计见 [ADR-0083](../adr/0083-editable-xml-template-bundles.md)，公共约定见 [API 指南](../xml-template-bundle-api.md)。

## 可复现步骤

使用 Java 21、Gradle Wrapper、Poppler 和 `requirements-verification.txt` 中的独立验证工具。以下命令在已有依赖缓存时离线运行，首次准备环境仍需按[发布指南](../releasing.md)获取依赖。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:xmlTemplateBundle :examples:production :examples:capabilities :examples:xmlIncludes :examples:recursiveIncludes --offline --no-daemon --console=plain
python scripts/create_xml_template_bundle_fixture.py
.\gradlew.bat -g build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/validation-template-bundle-phase77 clean build run resolvedLibraries xmlTemplateBundle --write-locks --offline --no-daemon --console=plain
python scripts/verify_xml_template_bundle.py --consumer-build-directory verification/consumer/build/validation-template-bundle-phase77
python scripts/verify_production.py --consumer-build-directory verification/consumer/build/validation-template-bundle-phase77
python scripts/verify_xml_includes.py --consumer-build-directory verification/consumer/build/validation-template-bundle-phase77
python scripts/verify_recursive_includes.py
```

若指定调用方目录已被占用，可以使用新的目录；调用方成功后才更新 `build/verification/consumer-build-directory.txt`，默认验证入口读取这个成功指针。样例输出位于 `examples/build/xml-template-bundle/`，独立 ZIP 输入、结构／双渲染报告和页面位于 `build/verification/xml-template-bundle/`，不纳入源码。

## 已完成证据

2026-10-26 最终验证：

| 检查 | 结果 |
| --- | --- |
| 全仓构建／测试／Javadoc | 1,398 项测试，失败、错误、跳过均为 0；新增模板包行为测试 11 项 |
| 本地制品及 SBOM | 15 个库及 BOM；23 个运行组件，即 15 个自研库和 8 个第三方制品；无新增坐标 |
| Maven 隔离调用方 | 仅解析本地 Maven 制品及 BOM，成功加载 Python 独立生成的标准 ZIP，再修改、保存、重开和实际输出 |
| 源码与资源 | 四个 ZIP 独立解析和 CRC 检查；主源／片段精确修改；占位符、CRLF、原图、原字体和许可文本保留 |
| PDF | 样例 3 页、独立调用方 2 页，共 5 页；文字、条件结果、循环、纸张、图片像素和双渲染差异检查通过；全部新页逐页查看，无遮挡、裁切或溢出 |
| DOCX | 5 份文件的标准 OOXML 文本、动态表格行／值、纸张及图片原字节通过独立检查；未使用应用私有模型恢复断言代替标准结构检查 |
| 资源与失败行为 | STORED／DEFLATE、目录／注释／额外字段、UTF-8、重复／非法名称、截断／CRC／目录冲突、编码／展开／条目／文本／像素预算、借用流、取消、输出失败、旧目标保护与临时文件清理通过 |
| 原有路径回归 | 生产验证、平面 XML include 和递归 include 的独立验证均通过 |

双渲染分别使用 Poppler 与 PDFium，未更新视觉基线。实际修改主源与片段均体现在新输出中；另用不同数据选择另一条件分支，并保留原模板不变。

## 限制

这是可读 XML 源码的模板编写闭环。任意 `Document` 自动转换为模板、标准输出内嵌恢复信息、任意 PDF／DOCX 的完整模型恢复和跨格式无损往返仍未由此实现，继续按 GAP-24 记录。

字体包内容只保存字节，使用前仍须由调用方显式加载、选择字体角色／集合成员并满足许可要求。ZIP 容器元数据不保留；不包含 Word 原生打开／保存验收、远程 CI、正式发布或跨系统兼容结论。
