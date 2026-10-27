# 阶段七十九：XML 页区保存与共享来源

## 范围

补齐 `XmlTemplateWriter` 的页区映射：默认／FIRST／EVEN 页眉页脚、显式空对、正高度空区、背景／前景的固定位置和顺序。支持跨节、同类变体及同层多个框的相等来源复用；保存后重开、共享编辑和重新导出沿用同一模型／布局路径。新增 `from(PageDocument)`，分节入口保持独立页区声明。

现有 version 1 XML 增加静态 `source-id`／`source-ref`，只引用前文同类完整来源，不复制语义块或增加扩展名／模块。设计见 [ADR-0085](../adr/0085-shared-xml-page-region-sources.md)，契约见[模型保存](../xml-model-saving-api.md)和[共享来源](../xml-page-regions-api.md#共享来源)。

## 可复现步骤

使用 Java 21、Gradle Wrapper、Poppler 与 `scripts/requirements-verification.txt` 的隔离 Python 环境。验证使用 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0。下面的离线命令要求依赖已经缓存，Poppler 位于 PATH；`python` 指安装了验证依赖的隔离环境解释器。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:xmlRegionSaving :examples:xmlModelSaving :examples:xmlTemplateBundle :examples:pageVariants :examples:pageOverlays :examples:xmlSections :examples:xmlPageRegions :examples:xmlTemplates :examples:xmlIncludes :examples:recursiveIncludes :examples:production :examples:capabilities --offline --no-daemon --console=plain
python scripts/create_xml_template_bundle_fixture.py
.\gradlew.bat -g build/gradle-validation -p verification/consumer -PconsumerBuildDirectory=build/validation-xml-region-phase79 clean build run resolvedLibraries xmlRegionSaving xmlModelSaving xmlTemplateBundle --write-locks --offline --no-daemon --console=plain
python scripts/verify_xml_region_saving.py --consumer-build-directory verification/consumer/build/validation-xml-region-phase79
```

新的成功调用方目录应登记到 `build/verification/consumer-build-directory.txt`，历史检查读取该指针。独立检查：

```powershell
python scripts/verify_xml_model_saving.py
python scripts/verify_xml_template_bundle.py
python scripts/verify_page_variants.py
python scripts/verify_page_overlays.py
python scripts/verify_xml_sections.py
python scripts/verify_xml_page_regions.py
python scripts/verify_xml_templates.py
python scripts/verify_xml_includes.py
python scripts/verify_recursive_includes.py
python scripts/verify_production.py
python scripts/verify_capabilities.py
python .agents/scripts/verify_rules.py
```

每次使用独立 consumer 构建目录，成功后再更新指针。样例位于 `examples/build/xml-region-saving/`，报告和全部双渲染页位于 `build/verification/xml-region-saving/`，均为忽略产物。

## 已完成证据

2026-10-27 验证：

| 检查 | 结果 |
| --- | --- |
| 构建／测试／Javadoc | 全仓 1,421 项测试，失败／错误／跳过均为 0；新增 7 项页区行为测试，原 writer 的页区报损测试改为严格保留测试 |
| 制品与调用方 | 15 个库及 BOM、本地 Maven 隔离调用方通过；23 组件运行 SBOM 与许可检查通过，调用方分发包中 15 个自研 JAR 的 SHA-256 与最终 SBOM 一致 |
| 模型／源码 | 三轮完整模型及 XML／资源字节保持；默认、FIRST／EVEN、完整空对、正高度空区、缺省 selector、跨节复用、同源多位置、不同来源与 ID 避让通过 |
| XML／ZIP | 4 份 ZIP 独立 CRC／结构检查；来源按类定义／引用、块 ID 唯一、两个背景位置、整层清空和原文字面值保持；原图及显式字体／许可字节保持 |
| 实际编辑 | 样例和 Maven 调用方各只编辑一次共享默认页眉；未编辑源码／结构／样式／资源保持。编辑器拆分的相邻完全同属性 span 仅按精确等价表示归一 |
| PDF | 4 组共 20 页，独立验证正文顺序／唯一性、三节页码重启、FIRST 优先于偶数、按显示页码选择 EVEN、空首页、原生字段最终值、表格与图片位置／绘制顺序 |
| 外观 | 20 页均用 Poppler／PDFium 双渲染并逐页查看，无裁切或意外遮挡；重开全部页与原模型像素相同，编辑与调用方均只有命中页变化；重叠图层指定位置的像素符合前后层顺序 |
| DOCX | 4 份标准 OOXML 独立验证：三节纸张／页码、default／first／even 引用、titlePg／evenAndOddHeaders、原生 PAGE／NUMPAGES；图层按 PAGE_OVERLAYS + DISCARD 显式报损，PREVIEW／ACTUAL 对账；无编辑包内所有条目字节保持 |
| 安全与预算 | 拒绝缺失／前向／跨类／动态／重复来源、引用附带内容或几何、越界位置；源块预算按唯一来源计费且不足时失败；真实 FOREGROUND:index、格路径及 UTF-16 诊断保留，IGNORE 不删实际损失 |
| 回归与规则 | 上述 11 组既有独立检查全部通过；项目规约验证通过。没有刷新视觉基线 |

字段前后文字可能在编辑后拆成多个相同样式运行；验收检查完整文字、实际字段值、可见轮廓边界和控制组像素，不把运行或轮廓路径组数当成语义字段数。

## 边界

本项关闭页区自动保存的子缺口，GAP-24.1 仍按整体范围保留部分支持：注释／标记、Caption／自定义块和标题／列表外链尚无保留映射，继续默认拒绝、按明确策略报损。模型不推测主题编写意图、运行时配置或引擎字体句柄。

新增 XML 属性要求重开方使用支持该语法的编译器。旧语法、已有 ID 约束、显式字体、源／呈现预算、取消、借用流及原子出版约定保持。DOCX 图层保留、一般 PDF／DOCX 原样恢复、标准输出内嵌恢复、任意跨格式无损往返和 Word 打开／保存回读不属于本项证据。未执行远程 CI、提交、推送或正式发布。
