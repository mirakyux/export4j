# 阶段八十一：独立交叉引用块的 XML 保存与恢复

## 推进项

本阶段完成 **GAP-24.1 中独立 CrossReference 的持久化／恢复单元**，并补充 GAP-24.4 的往返证据。已有布局支持独立页码／编号引用，但此前 XML 编译、模板绑定与保存没有该映射。本阶段贯通三处，复用现有 XML／普通 ZIP、编辑器和分页解析，不增加文件扩展名、模块或依赖。

`cross-reference` 保留 PAGE／NUMBER、目标 ID、原始前缀和完整 ParagraphFormat，支持分节、条件、循环及片段。静态目标跟随片段及当前循环副本重定位；动态目标明确给出完整最终 ID。最终页码、题注／列表标签与内部链接在分页后生成，不保存过期缓存值。语法和约束见 [XML 指南](../xml-template-api.md#独立交叉引用块)与[模型保存 API](../xml-model-saving-api.md)。

## 可复现步骤

使用 Java 21、Gradle Wrapper、Poppler 和 `scripts/requirements-verification.txt` 的隔离 Python 环境。独立工具固定为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、markdown-it-py 4.0.0；Poppler 需位于 PATH，python 指隔离环境解释器。源码包回归需要阶段七十七的独立 ZIP 夹具，可用 `python scripts/create_xml_template_bundle_fixture.py` 重建。以下离线构建需要已有依赖缓存；调用方刷新 SNAPSHOT 需可访问已配置的依赖仓库。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:xmlCrossReferenceSaving :examples:xmlSemanticSaving :examples:xmlModelSaving :examples:xmlRegionSaving :examples:xmlTemplateBundle :examples:xmlIncludes :examples:xmlNumbering :examples:xmlReferences --offline --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/validation-xml-cross-reference build run resolvedLibraries xmlCrossReferenceSaving xmlSemanticSaving xmlModelSaving xmlRegionSaving xmlTemplateBundle --refresh-dependencies --no-daemon --console=plain
python scripts/verify_xml_cross_reference_saving.py --consumer-build-directory verification/consumer/build/validation-xml-cross-reference
python scripts/verify_xml_semantic_saving.py --consumer-build-directory verification/consumer/build/validation-xml-cross-reference
python scripts/verify_xml_model_saving.py --consumer-build-directory verification/consumer/build/validation-xml-cross-reference
python scripts/verify_xml_region_saving.py --consumer-build-directory verification/consumer/build/validation-xml-cross-reference
python scripts/verify_xml_template_bundle.py --consumer-build-directory verification/consumer/build/validation-xml-cross-reference
python scripts/verify_xml_numbering.py --consumer-build-directory verification/consumer/build/validation-xml-cross-reference
python scripts/verify_xml_references.py
python scripts/verify_xml_includes.py
python .agents/scripts/verify_rules.py
```

每次选择新的隔离 consumer 构建目录，成功验收后登记到 `build/verification/consumer-build-directory.txt`。样例位于 `examples/build/xml-cross-reference-saving/`，独立报告和全部渲染页位于 `build/verification/xml-cross-reference-saving/`。日志和生成物保持忽略。

## 已完成证据

2026-10-27 验证：

| 检查 | 结果 |
| --- | --- |
| 构建／测试 | 全仓 1,442 项测试，失败／错误／跳过均为 0；新增 9 项行为测试，Javadoc 成功 |
| 结构与绑定 | PAGE／NUMBER、空／多行前缀、XML 特殊字符、补充字符、字面值、完整格式、分节顺序和显示页码起点保持；Java Each／条件及递归 include 同副本目标、外部目标和动态完整 ID 均有覆盖 |
| 非法输入 | 缺失属性、非法静态种类／目标、嵌套内容、未知属性及隐藏分支均在编译时拒绝；表格／页区／行内容器不放宽；统一转换对缺失及编号种类不兼容目标在三动作下均拒绝 |
| 预算与策略 | 目标／前缀共享文字及访问预算，块额度与同步取消保持；无法表达的 ID 仅按 IDENTIFIER／DEGRADE 显式改名，前向目标使用同一稳定映射，WARN／IGNORE 实际损失一致 |
| ZIP 与往返 | 5 份 ZIP 独立 CRC／目录／XML 检查；三轮完整模型及 XML／字体／许可字节一致；实际改写只改变指定引用的前缀和 target |
| PDF 内容／跳转 | 5 组各 3 页及 1 页动态模板，共 16 页；跨节 PAGE 显示 12、目标改写后显示 3，实际物理跳转从第 3 页切到第 1 页；Figure 2、列表 4、反向引用及循环 Figure 1／Figure 2 均独立检查 |
| 几何与外观 | 长引用保留两行 40 pt 预留，文字和链接位于正文范围；16 页全部由 Poppler／PDFium 双渲染并逐页查看，无裁切或意外遮挡；重开／第三轮与原文档同引擎像素完全一致，两次编辑均仅改变第一页 |
| Web 三动作 | HTML／Markdown 严格模式预检拒绝 CROSS_REFERENCE；WARN 各记录 1 条警告／损失，IGNORE 警告为 0 但仍保留 1 条损失；实际输出保留前缀并加入未解析标记，WARN／IGNORE 编码一致 |
| 本地 Maven 制品 | 新隔离调用方仅从 Maven 坐标消费，改写目标、重开并生成 PDF；23 个运行组件、15 个库及 15 项 BOM 约束对账，分发 ZIP 内实际 15 个 JAR 的 SHA-256 与本次 SBOM 一致 |
| 回归 | 注释／题注／文字外链保存、模型保存、页区保存、源码 ZIP、多级编号、行内字段／外链和 include 的 7 组独立验收全部通过；没有刷新视觉基线 |

## 边界与下一入口

本阶段完成独立 CrossReference 的 XML 保存单元，GAP-24.1 整体仍为部分支持：自定义运行时组件没有通用声明式恢复，主题编写意图、动态模板原始意图和运行时配置不会从渲染值推断。源模板仍应使用普通源码 ZIP 保存。

编译／绑定不替代完整目标关系检查，编辑／统一转换及最终布局继续验证目标；不能通过 WARN／IGNORE 绕过无效关系。仅允许正文独立块，不扩展页区／表格内引用或既有多行布局限制。旧编译器不支持新增语法；当前 Java 公共签名和依赖未因本阶段变化。

DOCX 仍不支持独立 CrossReference／Caption 导出；Web 的未解析标记属于明确降级，不能描述为保留页码或目标关系。没有据此关闭一般跨格式无损往返或阅读器打开／保存回读。

下一单元是 **GAP-24.2：标准文件内的恢复信息**，需先定义可见内容的一致性、缺失／过期信息、外部修改、删除／脱敏后的清理以及三动作行为，再选择标准包内机制。本阶段没有实现该机制，也没有执行远程 CI、提交、推送或正式发布。
