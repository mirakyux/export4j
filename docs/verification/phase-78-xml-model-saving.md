# 阶段七十八：语义模型保存为既有 XML

## 范围

`XmlTemplateWriter` 把支持的 Java 语义文档保存为现有版本 1 XML，再用普通 ZIP 保存图片和显式字体／许可。重开使用同一 XML 编译器及 DocumentTemplate，模型可继续语义编辑并重导出。API 见[指南](../xml-model-saving-api.md)，设计见 [ADR-0084](../adr/0084-semantic-model-to-existing-xml.md)。

扩充已有语法的缩进／分页、基线位移、独立列表标记、表格 padding／间距、有界 Auto 和单元格断行属性。保存正文显式使用 literal-text，避免 `${...}` 被误解释为变量。没有新增模块、第三方依赖或文件扩展名。

## 可复现步骤

使用 Java 21、Gradle Wrapper、Poppler 与 `scripts/requirements-verification.txt` 的隔离 Python 环境。本次使用 Poppler／PDFium 独立双渲染；Python 工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0。以下命令适用于依赖已缓存的离线环境：

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:xmlModelSaving :examples:xmlTemplateBundle :examples:xmlTextDefaults :examples:xmlNumbering :examples:xmlIncludes :examples:recursiveIncludes :examples:production :examples:capabilities --offline --no-daemon --console=plain
python scripts/create_xml_template_bundle_fixture.py
.\gradlew.bat -g build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/validation-xml-model-phase78 clean build run resolvedLibraries xmlModelSaving xmlTemplateBundle --write-locks --offline --no-daemon --console=plain
python scripts/verify_xml_model_saving.py --consumer-build-directory verification/consumer/build/validation-xml-model-phase78
python scripts/verify_xml_template_bundle.py --consumer-build-directory verification/consumer/build/validation-xml-model-phase78
python scripts/verify_production.py --consumer-build-directory verification/consumer/build/validation-xml-model-phase78
```

每次可使用新的调用方目录；成功后才更新 `build/verification/consumer-build-directory.txt`。XML 历史回归使用该成功指针：

```powershell
python scripts/verify_xml_text_defaults.py
python scripts/verify_xml_numbering.py
python scripts/verify_xml_includes.py
python scripts/verify_recursive_includes.py
python scripts/verify_capabilities.py
python .agents/scripts/verify_rules.py
```

样例输出位于 `examples/build/xml-model-saving/`，独立结构报告、双渲染页面和联系图位于 `build/verification/xml-model-saving/`，均不纳入源码。

## 已完成证据

2026-10-27 最终验证：

| 检查 | 结果 |
| --- | --- |
| 全仓构建／测试／Javadoc | 1,414 项测试，失败、错误、跳过均为 0；writer 新增 16 项行为测试 |
| 制品与调用方 | 15 个库、BOM、源码／Javadoc／许可和 23 组件 SBOM 通过既有验证；新的隔离 Maven 调用方完成保存、重开、编辑及统一转换到 PDF／DOCX |
| 模型与 XML | 富模型完整值恢复；普通文本按内容与完整格式验收默认运行表示；三次重复保存的主源和资源字节一致；节／页码／纸张、递归块格、合并格、独立标记、四类字段及 ID／引用保持 |
| 资源 | 6 份普通 ZIP 独立解析及 CRC 检查；原图、Regular／Bold 字体及许可字节保持；两份策略输出保留支持的正文 |
| 实际编辑 | 样例与 Maven 调用方分别替换选定段落文字；未编辑结构／样式及原资源保持。只合并具有完全相同属性的相邻 span，允许编辑器拆分命中运行 |
| PDF | 四组样例共 8 页、调用方 2 页，共 10 页；纸张、文字唯一性、真实页码／总数／引用编号、内外表格、图片像素和外链／内部链接通过独立检查 |
| 外观控制 | 10 页均经 Poppler／PDFium 双渲染并逐页查看，无裁切、遮挡或溢出；重开和第三轮控制与原模型 PDF 像素完全一致；实际编辑只改变第一节目标页，第二页保持一致；未更新视觉基线 |
| DOCX | 5 份标准 OOXML 独立检查：两节纸张／页码重启、原生嵌套表格、gridSpan／vMerge、四类原生字段及有效书签、标记样式和原图字节；无编辑控制的主文档 XML 一致 |
| 动作与诊断 | ERROR 拒绝且没有最终输出；WARN／IGNORE 都保留实际损失，只有 WARN 显示警告；PREVIEW／ACTUAL 对账。页区、注释／标记、未映射块、标题／列表外链、ID 改名及非法角色的真实处理分别验证 |
| 失败与资源所有权 | XML 非法 Unicode、几何／语法、源／格／块／报告预算、取消、资源快照、借用流、字节限制和原子目标保护通过；小数页面长度不用科学计数法，保持既有页面语法 |
| 历史回归 | 模板源码包、XML 默认外观、多级编号、平面／递归 include、生产输出及能力样例的独立检查均通过；项目规约验证通过 |

## 边界

GAP-24.1 为部分支持。当前 writer 尚未保存页区、注释、Caption／自定义组件及标题／列表外链；默认拒绝，显式 treatment 才报损继续。已有 XML 页区编写能力不会因此减少，但尚不能经此 writer 自动映射。

字体资源不从引擎或转换源自动搜集，角色与字体文件的关系由调用方重建。保存的是模型已解析的完整值，动态编写源码、主题名称／继承意图、运行时组件及 ZIP 容器元数据不由模型推测。原始短构造器的内部默认运行表示可规范化。

这些证据不证明任意 PDF／DOCX 原版恢复、标准文件内嵌模型信息、一般跨格式无损往返、Word 打开／保存回读或跨系统字体一致。未执行远程 CI、提交、推送或正式发布。
