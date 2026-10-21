# 阶段十九：默认样式贯通

日期：2026-10-15。范围为 GAP-04 的完整文字样式／颜色继承子集；当前项目仍未正式发布。设计见 [ADR-0023](../adr/0023-component-style-defaults.md)，调用见[样式 API](../style-defaults-api.md)。

## 实现结果

`ComponentContext` 保存默认文字样式和颜色，派生视口及字体作用域保留它们。`TextInheritance` 显式区分未设置与已设置，受支持文字 Builder 默认继承；旧构造器固定传入值。`toBuilder`、恢复继承和嵌套局部覆盖保持声明意图。

`Documents.bodyFormat` 为随后加入的组件保存排版快照；网格标准文字、自定义格和工厂输出共享局部默认值。拆行网格及浮动绕排在测量前解析，维持原行距／分页算法和缺字诊断。审查发现作用域的普通子组件回退曾扩张宽度，已通过共享原子区域分配修复；StyleScope／FontScope 的固定和区域输出均保留实际子宽度。

## 构建与行为验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
 :examples:styleDefaults :examples:textPdf :examples:flowPdf :examples:tablePdf `
 :examples:componentPdf :examples:advancedPdf :examples:production :examples:capabilities `
 :examples:staticForms :examples:gridContinuation :examples:semanticTableFlow `
 :examples:mixedColumns :examples:multiFloats :examples:continuousColumns `
 :examples:inlineFields :examples:inlineImages :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_style_defaults.py
~~~

全部成功。52 个 JUnit 测试类、329 项测试，失败／错误／跳过均为 0。包含 16 项新行为测试：嵌套文字与静态表单、显式 12 pt、独立颜色、完整样式回调、快照、`toBuilder`、格工厂、合并格拆行、短区域、字体作用域、页码组件、浮动行距、缺字 UTF-16、行框失败及窄组件对齐。构建完成 Javadoc、源码包、BOM／SBOM 与本地隔离发布；日志未出现 Java 编译警告。

隔离调用方输出读取 `build/verification/consumer-build-directory.txt`，本次为 `verification/consumer/build/validation-73838d30a901`。未用旧构建目录代替本次结果。

交付自查通过 12 份相关文档、271 个本地链接与标题层级；18 个变更 Java 源文件的 LF／SPDX 检查通过，记录在 `build/verification/style-doc-check.json`。

## 独立 PDF／SVG 验收

| 输出 | 页数 | 核对内容 |
| --- | --- | --- |
| `examples/build/style-defaults/style-defaults.pdf` | 3 | 正文、嵌套列、固定文字、静态表单及网格使用 10 pt／20 pt；局部红色、蓝色显式覆盖、显式 12 pt、局部 14 pt 和后续 11 pt 快照；长合并格续页 |
| 调用方 `output/consumer-style-defaults.pdf` | 1 | Maven 公共入口、10 pt／20 pt 继承、显式 12 pt、局部红色和矢量表单 |

Python 直接检查最终 PDF 的实际 `Tf`、颜色、文字显示状态和变换矩阵，共核对 51 次字号／颜色呈现。26 行长格原文唯一、有序，按 14／12 行续页；两页重复表头和 12 个边框矩形符合独立常量。6 个矢量控件状态与逻辑文字一致，均为继承字号对应的 7.2 pt；SVG 页数与原文顺序一致。

使用 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和 Poppler。全部 4 页以 Poppler／PDFium 独立渲染，逐页检查无裁切、重叠、丢字或控件缺失。192 dpi、2 px 模糊后最大均值误差为 1.311845，最大变化像素比例为 0.020161，低于原阈值 4／0.06。未创建或更新视觉基线。

证据位于 `build/verification/style-defaults/report.json`、`contact-1.png` 及各页 PNG。日志为 `style-full-build.log`、`style-consumer.log`。

## 历史回归

14 组既有独立验证全部成功：`verify_advanced_pdf`、`verify_production`、`verify_capabilities`、`verify_semantic_table_flow`、`verify_static_forms`、`verify_grid_continuation`、`verify_mixed_columns`、`verify_multi_floats`、`verify_continuous_columns`、`verify_inline_fields`、`verify_inline_images`、`verify_adr_form`、`verify_authoring`、`verify_semantic_tables`。日志位于 `build/verification/style-regression-*.log`，未自动刷新基线。

## 复评与未验证范围

GAP-04 的完整文字样式／颜色继承子集通过验收；逐属性级联、语义主题、可编辑字段默认外观仍未实现。语义 Table 保持已构建格式，目录和独立页模板不自动继承正文配置；DOCX 嵌套不在本批范围。

没有新增依赖、第三方资源、系统字体回退、remote、提交、推送或正式发布。下一优先项为 GAP-06 长合并网格的语义字段续排，其后为 GAP-07 安全声明式模板。
