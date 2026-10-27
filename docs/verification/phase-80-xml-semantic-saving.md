# 阶段八十：XML 注释、题注与文字外链保存

## 范围

完成既有 XML／普通资源 ZIP 的四项语义保留：显式传入的脚注／尾注、段落注释标记、Caption 原始种类／文字／格式、标题／列表文字外链。DocumentTemplate.renderComplete 返回文档和注释，旧 render 系列入口拒绝非空注释声明；统一转换保存、编辑及目标导出贯通完整注释快照。

根级静态 notes／note、正文 note-marker 和 caption 复用既有模型，不增加扩展名、模块、依赖或解释器。设计见 [ADR-0086](../adr/0086-complete-xml-semantic-binding.md)，语法及兼容边界见[XML 指南](../xml-template-api.md#注释与题注)和[模型保存 API](../xml-model-saving-api.md)。

## 可复现步骤

使用 Java 21、Gradle Wrapper、Poppler 和 `scripts/requirements-verification.txt` 的隔离 Python 环境。独立工具版本为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、markdown-it-py 4.0.0。以下离线命令需要已有依赖缓存和阶段七十七的独立 ZIP 夹具；可用 `python scripts/create_xml_template_bundle_fixture.py` 重建夹具。Poppler 需位于 PATH；python 指隔离环境解释器。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:xmlSemanticSaving :examples:xmlModelSaving :examples:xmlRegionSaving :examples:xmlTemplateBundle :examples:xmlIncludes :examples:xmlNumbering :examples:xmlReferences --offline --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/validation-xml-semantic clean build run resolvedLibraries xmlSemanticSaving xmlModelSaving xmlRegionSaving xmlTemplateBundle --offline --no-daemon --console=plain
python scripts/verify_xml_semantic_saving.py --consumer-build-directory verification/consumer/build/validation-xml-semantic
python scripts/verify_xml_model_saving.py --consumer-build-directory verification/consumer/build/validation-xml-semantic
python scripts/verify_xml_region_saving.py --consumer-build-directory verification/consumer/build/validation-xml-semantic
python scripts/verify_xml_template_bundle.py --consumer-build-directory verification/consumer/build/validation-xml-semantic
python scripts/verify_xml_numbering.py --consumer-build-directory verification/consumer/build/validation-xml-semantic
python scripts/verify_xml_references.py
python scripts/verify_xml_includes.py
python .agents/scripts/verify_rules.py
```

每次使用新的隔离 consumer 构建目录，成功后登记至 `build/verification/consumer-build-directory.txt`。样例位于 `examples/build/xml-semantic-saving/`，独立报告和全部渲染页位于 `build/verification/xml-semantic-saving/`；原始日志及产物保持忽略。

## 已完成证据

2026-10-27 验证：

| 检查 | 结果 |
| --- | --- |
| 构建／测试 | 全仓 1,433 项测试，失败／错误／跳过均为 0；新增 12 项完整语义行为测试；旧不支持语法测试按新容器边界更新，未放宽图片／字段／嵌套链接限制 |
| 制品／调用方 | 本地 Maven 隔离调用方完成完整绑定、题注编辑、再保存及 PDF 导出；15 个库、15 项 BOM 约束及 23 组件 SBOM 对账，分发包实际 JAR 与本次 SBOM 摘要核对 |
| XML／ZIP | 4 份 ZIP 独立 CRC／结构检查；注释顺序、种类、正文、ID／锚点、标记宽度／位移、三类文字链接、原始 Caption 与显式字体／许可保持 |
| 多轮控制 | 三轮完整 document／notes 及 XML／资源字节保持；单节／页区／分节、字面值、旧构造器和 Builder 模式均回归，旧 render 入口拒绝丢弃注释 |
| 关系／预算 | 缺失／重复／错位标记、注释 ID 冲突、未知锚点、条件锚点消失、循环副本不匹配均拒绝；非法注释／Caption 结构在编译时失败；ID 改名同步标记和锚点；共享访问／文字／writer 额度与取消保持 |
| 实际编辑 | 样例和 Maven 调用方各编辑一次首个 Caption；注释和其他完整结构／资源保持，PREVIEW／ACTUAL 对账 |
| PDF 结构 | 5 组共 10 页，独立检查显式／旧脚注在锚点页底带、尾注在文末、Figure 与 Table 独立编号、三处编号引用及目标页、三类标题／列表外链矩形及地址 |
| 外观 | 10 页均用 Poppler／PDFium 双渲染并逐页查看，无裁切或意外遮挡；重开及第三轮同引擎像素与原模型一致，样例及调用方编辑仅改变第一页 |
| Web 策略 | HTML／Markdown 分别解析实际编码，三类文字链接及题注文字保持；ERROR 在预检拒绝 NOTES；WARN 分别记录 12／33 条实际损失，IGNORE 隐藏警告但保留相同损失，二者编码一致；注释确实丢弃，题注／字段按各自降级规则处理 |
| 其他目标 | DOCX 严格预检拒绝 NOTES；没有把 XML 保留映射当成 DOCX 注释／Caption 导出支持 |

历史模型／页区／源码包及 XML 编号／字段外链／include 独立回归通过。没有刷新视觉基线。样例使用明确提供的 Liberation Sans 字节，不访问系统字体或链接地址。

## 边界

本项完成上述 XML 语义保存单元，GAP-24.1 整体仍是部分支持：独立 CrossReference 块、自定义运行时组件和主题编写意图未通用持久化。根级注释不是每次循环生成注释的模板功能；表格／页区／标题／列表内注释字段仍拒绝。旧编译器不支持新增语法，DocumentTemplate 的 record 形状增加 notes，原构造器保留不等于所有反射或序列化消费者兼容。

标准 DOCX／PDF 内嵌模型恢复、一般跨格式无损往返及阅读器打开／保存回读不属于本项验收。其他适配器继续按各自真实能力拒绝或报损；DOCX 的 Caption 没有映射，不能仅通过 NOTES + DISCARD 就完成该样例转换。本项没有执行远程 CI、提交、推送或正式发布。
