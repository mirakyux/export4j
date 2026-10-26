# 能力缺口与推进表

更新日期：2026-10-26。依据当前源码和独立验收记录。目标是易用、可控的 Java 文档生产引擎；不以功能名称数量计算完成率。

状态含义：**待实现**尚无完整实现；**推进中**已有实现但未完成验收；**部分支持**只有明确子集；**已验收**所列目标及边界均有证据。每项独立关闭，不能以 Builder 或同名方法代替行为验收。

能力补齐顺序见[剩余计划](remaining-capabilities-plan.md)。OCR 不在当前范围内。

[阶段七十五](verification/phase-75-variable-table-continuation.md)补齐行内格在不同栏宽间的完整行续排，保留完整原文、实际区域容量、不同格行高、横向合并、图片／字段／外链及 UTF-16。全仓 1,371 项测试、新隔离调用方、10 页独立双渲染／逐页查看和 7 组历史独立回归通过；变宽纵向合并拆分／递归子树及组件网格仍独立。

阶段六十五至七十四已补齐单向图像／外链、普通表格自动列合并、单向长行续页、DocMDP 认证、有限并发失败恢复、单向加密／归档、原子语义表格变宽区域、已有签名字节完整性校验、单向长表实际页码／准确总数和行内内表自动列。全仓 1,362 项测试、本地制品和新隔离调用方通过；各项独立证据分别登记。长期容量、变宽纵向合并拆分／递归子树、更多流式语义、证书信任及完整标准不由这些子目标关闭。

[阶段六十三](verification/phase-63-minimum-lines.md)已补齐变宽文字／富文本的最低行数：有界全源重测、短区跳过、实际分配与 UTF-16 诊断保持；1,312 项测试、12 页双渲染及新隔离调用方通过。[原子语义行及合并行组](variable-tables-api.md)已进一步接入变宽区域；[行内格完整行续排](variable-table-continuation-api.md)已由阶段七十五补齐；纵向合并拆分／递归子树及组件网格的变宽续排继续独立推进。

[阶段三十六](verification/phase-36-xml-page-settings.md)已补齐 GAP-07 的 XML 静态纸张／方向／边距及绝对单位子目标，937 项测试、6 页独立输出与 13 组回归通过。[阶段三十七](verification/phase-37-page-region-fonts.md)按[固定页区角色设计](adr/0044-page-region-font-roles.md)修复四类固定区域的字体角色上下文：全仓 948 项测试、4 页双渲染／逐页复查、新调用方及 17 组独立检查最终通过；一次原生异常退出与单独重跑证据保留。后续[阶段三十八](verification/phase-38-component-text.md)已交付 FontRoleScope／固定富文本，975 项测试及 3 页独立输出通过；[阶段三十九](verification/phase-39-list-markers.md)与[四十](verification/phase-40-xml-text-defaults.md)进一步交付独立列表标记与 XML 分域默认角色／颜色。最终全仓 1,029 项测试、8 页双渲染／逐页复查、新隔离调用方、18 组历史独立检查及两组新检查通过。当前达到受支持报告／静态表单的本地试用门槛，见[就绪评估](readiness-assessment.md)。[阶段四十一](verification/phase-41-xml-page-regions.md)进一步补齐固定 XML 页眉页脚、独立主题及最终页码；1,041 项测试、5 页双渲染／逐页复查、新隔离调用方和 14 组历史独立回归通过。[阶段四十二](verification/phase-42-sections-and-docx-regions.md)进一步补齐页区图片／表格、XML 静态分节及 DOCX 默认页区导入导出。主题角色／颜色已由[阶段六十](verification/phase-60-theme-appearance.md)验收；首页／奇偶语义页区已由[阶段六十一](verification/phase-61-page-variants.md)验收，XML 背景／前景及独立主题已由[阶段六十二](verification/phase-62-page-overlays.md)验收。

## 推进顺序

| 编号 | 优先级 | 缺口及当前边界 | 本项目目标与验收条件 | 状态 |
| --- | --- | --- | --- | --- |
| GAP-01 | P0 | 原嵌套 `.text()` 默认整块，需另选 `FlowText` | 普通文字按完整行自然续页；显式固定文字；短首区、嵌套、取消和缺字诊断保持 | 已验收：[阶段十五](verification/phase-15-continuation.md) |
| GAP-02 | P0 | 原合并格不能容纳跨页长文字 | 显式拆分合并行组；逐页表头、边框和内边距；文字及图片唯一；超高原子内容失败；独立 PDF／SVG 几何及调用方验证 | 已验收：[阶段十五](verification/phase-15-continuation.md)；富文本字段另见 GAP-06 |
| GAP-03 | P0 | `.table()` 在顶层和列内代表不同模型 | 明确 `.grid()` 与语义表格路径，保留兼容入口；共享语义表格区域流，字段、来源和 DOCX 行为保持 | 部分支持：语义长表区域流、逐栏表头字段及拆行见[阶段十八](verification/phase-18-semantic-table-flow.md)；两层原子块格及标准 DOCX 嵌套见[阶段二十七](verification/phase-27-block-cells.md)；递归子树与纵向 owner 续排已由[阶段五十七](verification/phase-57-recursive-tables.md)接入；独立重复完整源仍拒绝 |
| GAP-04 | P1 | 原正文默认格式不进入嵌套组件 | 可复用默认样式贯穿嵌套；显式覆盖优先；真实字体变体、固定行框和诊断不变 | 部分支持：[阶段十九](verification/phase-19-style-defaults.md)的完整继承及[阶段二十三](verification/phase-23-property-styles.md)的六项具名覆盖／恢复、网格／作用域快照、短区／续页与旧调用兼容已验收；[阶段三十](verification/phase-30-authoring-themes.md)的 Java 编写时主题／命名完整样式已验收；[阶段三十一](verification/phase-31-xml-themes.md)的 XML 主题及 p／span 静态名称已验收；[可编辑字段样式](verification/phase-32-editable-form-styles.md)、[目录独立样式](verification/phase-34-contents-styles.md)和[固定页区角色传递](verification/phase-37-page-region-fonts.md)已验收；[组件字体作用域与固定富文本](verification/phase-38-component-text.md)、[独立列表标记](verification/phase-39-list-markers.md)及[XML 分域字体／颜色默认值](verification/phase-40-xml-text-defaults.md)已验收；[XML 页眉页脚主题](verification/phase-41-xml-page-regions.md)已验收；模型全局颜色／角色级联已由[阶段六十](verification/phase-60-theme-appearance.md)接入；背景／前景独立主题与固定框已由[阶段六十二](verification/phase-62-page-overlays.md)验收；连续富文本组件已由[阶段五十七](verification/phase-57-recursive-tables.md)接入 |
| GAP-05 | P1 | 原 ADR 静态勾选依赖示例私有绘制 | 通用静态勾选／选项组／标签值／填写线，矢量标记不依赖字体缺字；密集表单替换样例辅助代码 | 已验收：[阶段十六](verification/phase-16-static-forms.md)；静态组件不含 DOCX／PDF/UA 语义 |
| GAP-06 | P1 | 组件合并格原缺富文本、字段和单元格来源 | 长合并格支持行内样式、图片、最终字段；重复表头按实际页解析；行列及 UTF-16 来源可追踪 | 已验收：[阶段二十](verification/phase-20-grid-semantic-flow.md)；标准 SemanticTable／FlowColumn 格内续排、两层表头逐页字段、内 Table 行列／UTF-16 来源；任意嵌套网格拆分仍不支持；当批未包含的合并语义 Table 已由阶段二十五／二十六另行补齐 |
| GAP-07 | P1 | 模板原只通过 Java 节点接入 | 安全的 Markdown／声明式模板子集，变量、条件、循环、资源访问策略、预算和损失诊断；HTML/CSS 单独定义子集 | 部分支持：XML／严格 Markdown、四类字段／1～9级十进制编号／静态外链，以及[阶段二十八](verification/phase-28-xml-block-cells.md)的可读两层块格与复制引用已验收；[XML 主题及静态名称](verification/phase-31-xml-themes.md)、[平面离线 include](verification/phase-33-offline-xml-includes.md)、[XML 多级编号](verification/phase-35-xml-numbering.md)和[静态页面配置](verification/phase-36-xml-page-settings.md)已验收；[分域文字默认值](verification/phase-40-xml-text-defaults.md)已验收，包含局部恢复、链接／字段／列表标记及嵌套格范围；[固定 XML 页眉页脚](verification/phase-41-xml-page-regions.md)及[图文／表格页区与静态分节](verification/phase-42-sections-and-docx-regions.md)已验收；有界递归 include、父到子引用及动态目标／URL 已由[阶段五十八](verification/phase-58-recursive-includes.md)验收；首页／奇偶变体已由[阶段六十一](verification/phase-61-page-variants.md)验收；XML 背景／前景已由[阶段六十二](verification/phase-62-page-overlays.md)验收；完整浏览器 CSS 和设计器仍开放；离线 HTML/CSS 输入子集见 GAP-18 |
| GAP-08 | P1 | 复杂文字、搜索与选择兼容有限 | 扩充脚本、组合簇、断行和阅读器语料；记录字体、搜索／选择与视觉结果；不声称完整 OpenType | 部分支持：[阶段二十二](verification/phase-22-text-and-adapters.md)验收原文／实际字形簇、双向续行及连字一致性；六脚本两路径共 12 页；PDFium 查询精确，11 项 pypdf 提取与轮廓选区几何仍有限 |
| GAP-09 | P1 | 完整文档仍保留全量模型／布局；单向长表覆盖固定列普通／富文本、图像外链及横向合并，PDFBox 页树仍累积 | 有界数据源、取消和临时资源生命周期；分别测量布局与实际 PDF 的内存、吞吐和上限；持续容量独立验收 | 部分支持：[阶段二十四](verification/phase-24-streaming-tables.md)验收单向源／逐页布局绘制／同步 PDF／扁平摘要；7 组独立 JVM 含布局和 PDF 各 1k／10k／100k 行、64 MiB 堆布局控制及退出清理。[阶段二十九](verification/phase-29-streaming-rich-text.md)补齐纯富文本／显式角色、空运行额度、6 页独立验收与 9 组普通／富文本 JVM 控制。[阶段六十四](verification/phase-64-streaming-column-spans.md)验收固定列横向合并、锚点／覆盖槽计数、物理列／UTF-16 和单行预读。图像及被动链接已由[阶段六十五](verification/phase-65-streaming-media.md)验收；完整行续排由[阶段六十七](verification/phase-67-streaming-row-split.md)验收，加密／静态归档见[阶段七十](verification/phase-70-streaming-protection.md)，重复／并发失败恢复见[阶段六十九](verification/phase-69-streaming-stability.md)；[实际页码及准确总数](verification/phase-73-streaming-fields.md)已验收；纵向合并、未来语义和长期容量仍开放；不宣称 PDF 恒内存 |
| GAP-10 | P2 | PDF 读取、编辑、组合及提取的明确子集 | 独立适配器；第三方类型隔离；显式密码、交互与签名处理边界；受损输入预算及独立验证；按下文 GAP-10.1～10.5 分别验收编辑、渲染、优化和脱敏 | 部分支持：静态读取／复制已有；[阶段四十九至五十五](pdf-adapter-api.md)完成页级／被动对象编辑、关系重映射合并、PNG／JPEG 渲染、图片提取、优化、真实文字替换和新建图片 PDF 脱敏的明确子集；各入口边界独立，签名／标签／未知活动结构不放宽，OCR 排除 |
| GAP-11 | P2 | PDF/UA、PAdES/LTV、PDF/A 范围有限 | 按标准逐项实现并用独立验证器验收；不能由标签或一次输出推断合规 | 部分支持：静态 RGB PDF/A-2b、CMS 外部签名、[三档 DocMDP 认证](verification/phase-68-pdf-certification.md)及[已有签名字节校验](verification/phase-72-pdf-signature-verification.md)；信任、修改符合性与完整标准独立 |
| GAP-12 | P2 | DOCX 合并表格、复杂页区及布局互转有限 | 标准 OOXML 合并与受支持样式；Word 更新／保存回读；严格损失边界；不承诺 PDF 同分页 | 部分支持：[阶段二十五](verification/phase-25-column-spans.md)／[二十六](verification/phase-26-row-spans.md)的双跨度和[二十七](verification/phase-27-block-cells.md)的两层原子块格已贯通标准 gridSpan／vMerge／p-tbl-p、字段／图文链接及全树来源；[标准 DOCX 默认页眉页脚](verification/phase-42-sections-and-docx-regions.md)含图像／表格／页码及分节继承／清空已验收；纵向拆组和递归续排见[阶段五十七](verification/phase-57-recursive-tables.md)，更广 Word 兼容仍开放；保存回读单列 |
| GAP-13 | P2 | 缺可视化设计器及完整多场景语料 | 先提供可运行报告、密集表单、长表及引用样例，记录完整调用成本和失败边界；设计器后续独立模块 | 部分支持：ADR 与报告探测已有 |
| GAP-14 | P1 | 发布前 API、许可和兼容检查需要持续固化 | 公共 API 隔离、隔离坐标调用方、SBOM、依赖与资源声明；正式发布前核对命名空间权限 | 部分支持：已有本地制品与隔离验证；尚未正式发布 |
| GAP-15 | P1 | 原缺统一的程序化编辑入口 | 按块／单元格定位、插入／删除／移动、图片替换和批量样式修改；保留输入快照，校验全树 ID、字段、列表和引用；操作适用 GAP-19 策略并报告实际变化 | 已验收：[阶段四十四](verification/phase-44-semantic-editing.md)；不可变候选、物理锚点、图文／样式、页区共享及完整性校验。只编辑已有模型，未建模源结构不在本项 |
| GAP-16 | P1 | 原缺跨富文本运行的安全查找替换 | 明确匹配与样式继承规则，保留 Unicode／UTF-16、链接、图片和字段边界；跨对象或不适用目标按策略处理，验证未匹配内容及引用不变 | 已验收：[阶段四十四](verification/phase-44-semantic-editing.md)；字面匹配、同／异样式规则、对象与字素边界、范围回滚及预检／实际报告；PDF 内容流仍归 GAP-10.5 |
| GAP-17 | P1 | Markdown 输入仅为严格轻量模板，通用导入缺失 | 单独定义 CommonMark 与 GFM 导入子集，逐项覆盖表格、链接、代码、引用、有序／嵌套列表、删除线及硬换行；资源显式解析，保留旧模板语法和变量行为；损失按 GAP-19 报告 | 已验收：[阶段四十五](verification/phase-45-markdown-import.md)；通用 CommonMark／GFM 子集、显式图片、列表／表格、源位置及损失报告；严格模板保持独立，不承诺任意 Markdown 无损往返 |
| GAP-18 | P1 | HTML／CSS 只有语义导出，缺导入 | 离线元素／属性／CSS 子集映射到语义模型，明确级联和资源入口；不执行脚本或隐式外联；未知样式、浮动和布局损失可定位，并受预算、取消及 GAP-19 策略控制 | 已验收：[阶段四十六](verification/phase-46-html-import.md)；离线元素／属性与有限 CSS 级联、显式资源、双跨度／两层块格和实际损失；完整浏览器布局与任意网页往返不在本项 |
| GAP-19 | P1 | 导入、编辑和转换缺统一动作策略、损失预检及结果报告 | 全局及逐格式／功能／编辑操作控制 ERROR／WARN／IGNORE；诊断动作与跳过／降级／丢弃分开声明；编辑前可预检损失，执行后返回实际损失与位置；统一支持检查和转换编排，不放宽非法输入或执行失败 | 已验收：[阶段四十七](verification/phase-47-document-conversion.md)统一导入→编辑→导出、确切输出预检和三阶段实际损失；覆盖优先级明确，IGNORE 保留损失，未编辑内容也检查；旧入口契约保持，只有真实通道才能保留 |
| GAP-20 | P3 | PDF 可编辑语义恢复及明确推断边界 | 在明确静态子集内恢复阅读顺序、段落、标题、表格和图像位置，再转换为 DOCX／HTML／Markdown；分别验证内容、结构和视觉差异，报告无法恢复项 | 明确子集已验收：[阶段五十六](verification/phase-56-pdf-semantic-import.md)；逐行段落、字号推断标题、显式表格网格、流图片及三格式编辑转换／重新布局 PDF；源框为近似度量，原绝对布局不保留；自动多栏／合并嵌套恢复和 OCR 不在子集 |

## 编辑、导入与转换的分项边界

GAP-15～20 为新增跟踪项；GAP-17／18 细化 GAP-07 的输入方向，分别跟踪和验收。GAP-10／12／14 的既有工作按下表细分，避免因一个子项完成而关闭整个能力。优先级表示推进顺序，表内目标均须有实现及独立验收后才可更新为已验收。

现状依据：[支持矩阵](capabilities-api.md)、[DOCX 页区](docx-page-regions-api.md)、[通用 Markdown 导入](markdown-import-api.md)、[HTML/CSS 导入](html-import-api.md)、[PDF 各处理入口](pdf-adapter-api.md)和[PDF 签名与归档](production-api.md)。DOCX／Web 的语义子集可编辑后重导出；已有 PDF 的渲染使用独立 render 入口，SVG／PNG 后端继续消费本项目布局结果。PDF 语义恢复使用另一条显式损失通道。

| 分项 | 优先级 | 当前缺口 | 独立验收目标 | 状态 |
| --- | --- | --- | --- | --- |
| GAP-10.1 | P2 | 静态 PDF 页级编辑 | 明确页面框、旋转、UserUnit、坐标与图层顺序；原内容不重排，输出独立结构及渲染检查；选页／重排／删页可由已有页面选择组合完成，不重复计为缺口 | 已验收静态子集：[阶段四十九](verification/phase-49-pdf-page-editing.md)含旋转／裁剪／等比缩放、显式图文前后叠加、3 页独立双渲染和新调用方；裁剪隐藏未删除，交互输入另行验收 |
| GAP-10.2 | P2 | 已有 PDF 的被动交互对象与跨来源关系处理 | 逐项支持读取／修改及合并重映射，表单另验填写／扁平化与外观；未知结构按明确策略处理，不因支持一种对象就宣称任意 PDF 可编辑 | 明确子集已验收：[阶段五十](verification/phase-50-pdf-object-editing.md)的四类字段／被动对象及[阶段五十一](verification/phase-51-pdf-object-composition.md)的选页／跨来源合并、命名空间及关系重映射；活动／未知结构和重复页仍拒绝 |
| GAP-10.3 | P2 | 已有 PDF 整页转 PNG／JPEG 及嵌入图片提取 | 显式页选择／DPI／背景与色彩范围，限制解码像素及输出；独立核对页面可见内容和提取资源，不把项目 PNG 后端当作 PDF 渲染器 | 明确子集已验收：[阶段五十二](verification/phase-52-pdf-raster.md)；CropBox／旋转／UserUnit、RGB／alpha、当前字段外观、嵌入 TrueType、确切 ZIP 与去重资源像素；未知字体／图案可明确跳页，OCR 不在范围 |
| GAP-10.4 | P2 | PDF 压缩优化、资源去重和图片重采样 | 分别定义无损／有损目标，报告资源变化及质量损失；检查输出大小、可见差异、结构、预算和失败原子性 | 已验收明确子集：[阶段五十三](verification/phase-53-pdf-optimization.md)；流压缩、相同简单图片／程序流去重、显式 RGB／Gray 降采样／JPEG；复杂蒙版等保留并报损，不保证输出更小 |
| GAP-10.5 | P3 | PDF 内容流文字替换与区域脱敏 | 限定可处理字体编码和内容结构；替换验证定位与版面差异，脱敏验证目标文字／图片及受支持可达副本的实际清除；覆盖矩形不能作为删除证据 | 明确子集分别验收：[阶段五十四](verification/phase-54-pdf-text-editing.md)仅修改 Tj／TJ 命中字形范围并保持未命中位置；[阶段五十五](verification/phase-55-pdf-redaction.md)清除区域像素后新建纯图片 PDF，不复制源对象／文字／附件／历史。后者损失全文搜索和矢量编辑，不支持保留其他可编辑文字的选择性矢量脱敏 |
| GAP-11.1 | P2 | 认证签名、现有签名验证、可信时间戳和长期验证资料不完整 | 按独立标准目标核对权限、证书链、时间戳和撤销资料；编辑造成签名失效须预先识别，缺少受支持处理路径时失败 | 部分支持：CMS 及[认证签名](verification/phase-68-pdf-certification.md)的结构、权限声明、独立完整性验签和篡改拒绝已验收；[已有签名字节完整性](verification/phase-72-pdf-signature-verification.md)已验收；信任／撤销／时间戳／长期验证、修改符合性及阅读器执行仍开放 |
| GAP-12.1 | P2 | 首页／奇偶页区的语义映射、浮动图片／文本框、复杂图片变换及更深内表有限 | 逐结构保留语义、关系和声明意图；源页区缺少固定高度时仍显式配置；Word 的读取／字段更新／保存回读／视觉结果分别登记 | 部分支持：[阶段四十八](verification/phase-48-docx-editing.md)验收样式级联／显式物理字体映射；原包编辑保留三类页区；[阶段六十一](verification/phase-61-page-variants.md)进一步完成首页／奇偶固定页区语义映射；Word 读取通过，保存／导出控制组也超时 |
| GAP-12.2 | P2 | DOCX 目录、评论、修订和表单缺完整模型映射 | 分别确定模型与损失规则；改动普通正文时也检查未编辑部分是否被丢弃；不把输出可打开视为完整保留 | 部分支持：[原包编辑](docx-editing-api.md)已独立验证普通文字编辑时保留未编辑结构及字节；复杂边界不能编辑、字段不更新，完整语义编辑继续开放 |
| GAP-14.1 | P1 | 编辑与转换的跨格式语料和损失对账 | 覆盖导入→编辑→导出链路、嵌套格／页区／跨运行替换、ID／引用及未编辑内容；预检与实际报告逐项核对，独立验证输出、取消／失败及旧目标保护；同时保持 API 文档事实一致 | 已验收本次明确子集：[阶段四十四至五十六](remaining-capabilities-plan.md)包含语义／源包编辑、四种输入、实际输出编码、PDF 对象／文字／像素处理、独立双渲染和隔离调用方；未来扩大格式范围仍须添加对应语料 |

### 不支持内容的编辑策略与损失报告

以下契约已在[统一转换](document-conversion-api.md)的既有适配器子集中实现并验收。旧入口保留默认行为；新增格式和原包编辑仍须逐项验证，不能据策略枚举推断支持。

| 维度 | 目标行为与验收条件 |
| --- | --- |
| 动作与覆盖 | 默认 ERROR；允许全局、逐格式、逐功能及逐操作配置，明确覆盖顺序。WARN 返回警告并执行明确的处理方式；IGNORE 执行相同处理但不发该警告。两者均保留可查询的实际损失报告，不能把 IGNORE 结果标记为无损 |
| 处理方式 | 分别声明拒绝操作、跳过该操作、降级为受支持表示、显式丢弃属性或内容。跳过须说明作用于单个命中、整块还是整项请求；保留原内容只有在完整源结构仍被保存且目标格式存在已验证保留路径时才可选，否则明确失败 |
| 预检与执行 | 写出前可检查编辑计划及目标格式，列出可能损失和不可执行项；预检结果绑定同一输入快照与配置。执行后区分预测损失、实际损失和已跳过操作，不能把失败或未执行当成成功；预检不等于 I/O 成功保证 |
| 报告位置 | 包含阶段（导入／编辑／导出）、源／目标格式、功能、操作、节／页区／块 ID、物理格路径及适用的 UTF-16 范围；保留可定位原因、所选动作、处理方式及影响，不默认回显正文、完整路径或凭据 |
| 损失清单 | 列明文字／图片是否删除，字体／样式是否降级，列宽／位置／分页是否改变，字段是否变成静态值，链接／书签／编号／交叉引用是否断开，以及评论／修订／表单／元数据是否丢弃；未编辑部分同样纳入检查 |
| 不可忽略失败 | 非法 Unicode、畸形结构、无效引用、权限不足、预算、取消／超时及 I/O 不得由 WARN／IGNORE 放行；宏、脚本执行及隐式外联继续拒绝。签名相关改动必须遵守独立的已验证处理契约，不能靠忽略警告静默移除或声称签名仍有效 |
| 跨格式边界 | 无表达能力时报告具体降级，不宣称任意格式无损往返；不能保留的未知源结构禁止伪装成“原样保存”。从 PDF 页面恢复语义仍归 GAP-20，策略本身不能补出不存在的内容 |

共同验收须覆盖 ERROR 写出前失败、WARN／IGNORE 的处理内容一致及报告可查询、逐项覆盖优先级、跳过范围、保留失败、未编辑内容损失、输入快照不变和失败时旧目标保护。

## 自主实现与许可边界

- 自主编写语义模型、测量、分页和绘制组合算法，采用 Apache-2.0 声明。
- 优先复用项目现有代码和 JDK。字体解析、PDF 编码等基础设施继续使用已审查依赖，不为“自主实现”重写成熟解析器。
- 新增依赖、字体、图片或样本文档须进入[依赖记录](dependencies.md)，核对具体版本、传递依赖、原始许可和分发义务。

## 执行记录

| 批次 | 范围 | 证据 |
| --- | --- | --- |
| 第一批 | GAP-01 普通嵌套文字续页、GAP-02 合并格完整行拆分、GAP-03 命名区分 | [决策](adr/0019-grid-cell-continuation.md)、[API](grid-continuation-api.md)、[验收](verification/phase-15-continuation.md)；266 项 JUnit、5 组 16 页及隔离调用方 4 页独立检查 |
| 第二批 | GAP-05 静态表单、GAP-03 定位表格损失边界与前置条件评估 | [决策](adr/0020-static-form-components.md)、[API](static-forms-api.md)、[验收](verification/phase-16-static-forms.md)；277 项 JUnit、静态组件 7 页、调用方 1 页和迁移后 ADR 7 页独立检查 |
| 第三批 | GAP-03 语义插槽与固定视口语义表格 | [决策](adr/0021-component-semantic-insertions.md)、[API](semantic-components-api.md)、[验收](verification/phase-17-semantic-components.md)；293 项 JUnit |
| 第四批 | GAP-03 长语义表格区域流、重复表头字段与呈现身份 | [决策](adr/0022-semantic-table-region-flow.md)、[API](semantic-components-api.md)、[验收](verification/phase-18-semantic-table-flow.md)；313 项 JUnit，嵌套列／连续栏／拆行图文 11 页与隔离调用方 6 页独立检查 |
| 第五批 | GAP-04 文字默认值贯通 | [决策](adr/0023-component-style-defaults.md)、[API](style-defaults-api.md)、[验收](verification/phase-19-style-defaults.md)；329 项 JUnit，报告 3 页与调用方 1 页独立检查 |
| 第六批 | GAP-06 长合并格语义续排与合法表头呈现 | [决策](adr/0024-grid-semantic-cell-flow.md)、[API](grid-semantic-flow-api.md)、[验收](verification/phase-20-grid-semantic-flow.md)；345 项 JUnit，报告 5 页与调用方 3 页独立检查；15 组历史回归通过 |
| 第七批 | GAP-07 离线声明式 XML 及既有绑定安全加固 | [决策](adr/0025-safe-declarative-templates.md)、[API](xml-template-api.md)、[验收](verification/phase-21-xml-templates.md)；378 项 JUnit，报告 3 页与调用方 1 页独立检查，DOCX／SVG、三组历史回归与新模块坐标通过 |
| 第八批 | GAP-08 文字一致性、GAP-07 Markdown／XML 字段与链接、GAP-10 静态 PDF 处理、DOCX 损失修正 | [验收](verification/phase-22-text-and-adapters.md)；472 项 JUnit、35 页新增输出独立渲染与逐页检查、18 组历史回归、本地 SBOM／坐标及新隔离调用方通过 |
| 第九批 | GAP-04 六项显式属性覆盖与逐项恢复 | [设计](adr/0030-property-text-inheritance.md)、[样式 API](style-defaults-api.md)、[验收](verification/phase-23-property-styles.md)；490 项 JUnit、6 页新输出、22 组历史回归及新隔离调用方通过；旧完整样式和字体／固定行框保持 |
| 第十批 | GAP-09 固定列普通文字单向长表与逐页 PDF | [设计](adr/0031-single-pass-table-output.md)、[API](streaming-table-api.md)、[验收](verification/phase-24-streaming-tables.md)；552 项 JUnit、8 页新输出、23 组历史回归、新隔离调用方及 7 组独立 JVM 容量／退出清理通过 |
| 第十一批 | GAP-12 语义列合并与标准 DOCX gridSpan | [设计](adr/0032-semantic-column-spans.md)、[API](column-spans-api.md)、[验收](verification/phase-25-column-spans.md)；592 项 JUnit、8 页新输出、24 组历史回归与新隔离调用方通过；Word 保存回读按实际结果登记 |
| 第十二批 | GAP-12 语义跨行合并、二维覆盖与标准 DOCX vMerge | [设计](adr/0033-semantic-row-spans.md)、[API](row-spans-api.md)、[验收](verification/phase-26-row-spans.md)；641 项 JUnit、7 页新输出、25 组历史回归、新隔离调用方及独立 PDF/A 验证通过；全覆盖行、原子连通组、字段／图文链接及标准去元数据输入分别检查；Word 工作流收尾失败与读取成功证据分列 |
| 第十三批 | GAP-03／12 多段格、两层原子内表及标准 DOCX | [设计](adr/0034-atomic-block-cells.md)、[API](block-cells-api.md)、[验收](verification/phase-27-block-cells.md)；694 项 JUnit、6 页新输出、26 组历史回归、新隔离调用方及独立 PDF/A 通过；全树 ID／预算、层级来源、最终字段／链接和标准双网格／尾段分别验证；Word／容量未重跑 |
| 第十四批 | GAP-07 XML 可读多段格与原子内表 | [设计](adr/0035-xml-block-cells.md)、[API](xml-block-cells-api.md)、[验收](verification/phase-28-xml-block-cells.md)；711 项测试、7 页新输出、27 组历史回归、新调用方与独立 PDF/A 通过；全部分支、复制引用与标准 DOCX 分别检查 |

## 评估与推进循环

2026-10-16 最新循环：阶段三十六复用 PageSettings 补齐模板内静态纸张配置；随后[固定页区角色设计](adr/0044-page-region-font-roles.md)定位角色丢失，阶段三十七修复上下文并保持旧默认与拒绝边界。两单元均完成实现、独立检查及逐页复查。随后阶段三十八至四十自主补齐标准组件角色作用域、固定富文本、独立标记及 XML 分域默认角色／颜色，完成源码交叉复查、行为测试、隔离制品及全部新页检查。最后复查还修复原生 DOCX bullet 外部 override 绕过损失策略的问题。首版试用判断与剩余工作按[就绪评估](readiness-assessment.md)分开登记，不将这些子目标扩大为完整主题或 XML 页区。

每批先定位现状与缺口，再实现可验证行为，完成独立验收后更新状态，最后复评未关闭项。优先级不替代前置条件；因依赖调整顺序须记录源码证据及下一入口，不能把绕过缺口当作完成。

| 循环 | 评估证据 | 推进与复核 | 结果与下一项 |
| --- | --- | --- | --- |
| 2026-10-15，语义组合评估 | `ComponentLayout` 只持有绘制与普通诊断；字段处理识别顶层语义片段，外链处理需要源表格 | 没有把语义 Table 转成网格；先实现独立的静态表单，并删除 ADR 私有绘制器；测试、PDF／SVG 和全部渲染页验证 | GAP-05 关闭于明确子集；GAP-03 保持开放 |
| 2026-10-15，交付后回查 | 定位 `TableComponent` 接受单元格外链，但最终 extras 中没有外链；字段和字体角色也缺少最终上下文 | 在构造时明确拒绝相关内容，补充文字外链、图片外链及字段／角色 3 项回归；普通富文本与顶层语义表格回归通过 | 修正已关闭；GAP-03 的区域流尚未完成 |
| 2026-10-15，语义组件组合 | 普通长格、合并长格和双层嵌套需要完整保留字段、来源与字体上下文 | 自主实现有序语义插槽，传播实际字体／来源，终态解析字段、引用、图文外链；固定视口组件与隔离调用方验证 | 固定组合子集完成，GAP-03 保持部分支持；默认样式仍是已证实的易用性缺口 |
| 2026-10-15，语义区域流复评 | 固定视口已有字段语义；核心测量与最终字段解析可复用，但逐插槽登记会把续排误判为重复源 | 复用核心表格分配区域，独立验证片段序列与唯一源；逐栏表头值、拆行图文、链接和 FontScope 多短区通过行为及输出检查 | 区域流子目标完成；不扩大为长合并网格或 DOCX 嵌套支持。下一项默认样式贯通 |
| 2026-10-15，默认值与易用性复评 | 正文格式未进入组件；直接测量网格／绕排会绕过普通入口；包装器可能改变窄组件宽度 | 显式记录未设置状态，测量前解析，保留字体／语义上下文和原始宽度；16 项新行为测试、4 页样式输出及 14 组既有独立回归通过 | 文字默认值子目标完成；三处重复 style 回调改为一处 bodyFormat，完整代码 41→40 非空行，仍不构成总体易用性排名 |
| 2026-10-15，长合并格语义复评 | 固定视口无法适应每次格内可用高度；缓存网格表头会被视为重复完整源 | 复用核心一次测量游标；网格局部校验准备片段／合法缓存呈现并确定最终序号，全局来源校验保持严格；16 项新行为测试、8 页独立检查、15 组历史回归通过 | GAP-06 关闭于标准语义格组合；来源是内 Table 行列，不冒充外网格坐标。下一项 GAP-07 安全声明式模板 |
| 2026-10-16，声明式模板复评 | Java 模板已有严格节点绑定；数据大数子类、追加后计费及空循环有安全与预算缺口 | 新适配器只编译到既有模型，资源全部显式；加固数据快照、追加前预算、词法父链与工作检查点；33 项新增行为测试、4 页独立输出及三组历史回归通过 | XML 声明式子集完成；不扩大为 Markdown／HTML/CSS 或任意模板沙箱。新坐标、BOM／SBOM 与隔离调用方通过，下一项 GAP-08 文字与阅读器语料 |
| 2026-10-16，文字及适配器复评 | 缺字替换后的簇边界、双向续行及塑形上下文存在不一致；模板字段／链接和 PDF 静态处理缺公共入口；DOCX 已知嵌套容器会静默丢失 | 修复共享文字路径；严格 Markdown、XML 字段／链接及静态 PDF 适配器自主实现；94 项新增测试、35 页输出、18 组历史回归及隔离调用方通过 | GAP-07／08／10 均按实际子集保持部分支持；记录提取与选区差异，不宣称阅读器通用兼容；下一项 GAP-04 显式逐属性继承 |
| 2026-10-16，局部样式与长表复评 | 完整 style 回调无法表达只改字号；默认数值不能表示设置意图；长表 Cursor 仍保留全部模型／测量／页 | 不可变属性声明在测量前合成完整样式，保留旧回调及单项恢复；18 项新增行为测试、6 页独立输出、22 组历史回归通过；逐页源／后端四层下一入口已明确 | GAP-04 属性子目标完成，主题／可编辑字段仍开放；GAP-09 保持待实现，不把源迭代器或 TEMP_FILE 当作全链路流式 |
| 2026-10-16，单向长表与容量复评 | 完整 Table／测量／IR／Display List 均全量驻留，源包装或 TEMP_FILE 无法单独关闭缺口；PDFBox 仍保留累计对象 | 自主实现单向固定列规格、逐行测量／逐页绘制、同步后端及扁平摘要；62 项新增测试、8 页独立检查、23 组历史回归与 7 组独立 JVM 通过；修正采样脚本 PS5.1 退出码并新 ID 重跑，退出文件数／字节均 0 | GAP-09 按固定列普通文字子集改为部分支持；64 MiB 堆完成 100k 行布局，100k PDF 1,786 页／325.281 秒，不能解释为恒内存。下一项 GAP-12 columnSpan |

阶段二十五复评：columnSpan 已通过模型／核心／组件／Java／XML／DOCX 的 40 项新行为测试，8 页新输出、24 组历史验收与新隔离调用方通过。Word 读取与字段更新成功，保存／导出在有界私有实例内阻塞，不能登记保存回读通过。

阶段二十六复评：新增 49 项行为测试打通 rowSpan／vMerge，二维网格按真实锚点准备，覆盖行不复制内容；确定行高与传递连通组用于正文、连续栏及网格语义路径。7 页独立几何／双渲染／逐页检查、25 组历史验收与新隔离调用方通过。交叉复核修正稠密空行视图、DOCX 历史逆向查询和续接格未知样式的静默损失；保留原失败日志。Word 能读取标准输入并更新原生页码，探针的行数假设和 COM 收尾失败仍记录为工作流失败，私有进程已精确清理，不据此关闭保存兼容。

阶段二十七复评：两层原子 Paragraph／Table 块格已完成模型／IR、Java 深绑定、全树来源／预算、最终字段／链接和标准 DOCX 双向映射。交叉审查修复组件源预算路径／宿主索引和 DOCX 续接格隐藏语义／字段缓存计数，6 页独立检查、26 组历史回归与新调用方通过。下一优先项为 **GAP-07 XML 可读嵌套格语法**，复用现有树与原子布局，编译阶段检查全部分支，不扩大 Markdown 或递归续排。跨页拆分纵向组、完整递归续排及 GAP-09 更多语义／持续容量继续独立。依赖、验收及完整剩余范围见[执行计划](remaining-capabilities-plan.md)。GAP-08 的工具兼容限制仍开放；逻辑文字、查询和精确选区不等价。

已验收项仍保留限制；新增组合或回归时重新打开对应项。静态表单的选项整组保持和 DOCX 语义仍未实现；文字样式继承以阶段十九及二十三的明确范围为准。

阶段二十九复评：单向长表已复用纯 InlineText 与真实字体角色，21 项新行为测试、6 页独立检查、28 组历史回归和新隔离调用方通过；9 组普通／富文本独立 JVM 含 64 MiB 布局控制。审查修正容量例的手算运行数，并从新 ID 重采全部富文本组，退出清理与字体摘要通过。GAP-09 保持部分支持；下一单元为 GAP-04 编写时主题快照与具名完整样式，字体角色级联／可编辑字段／XML 名称另行设计。

阶段三十复评：Java DocumentTheme 集中完整正文／六级标题／便利语义表格与命名完整样式，加入时解析并保留显式值、六项组件继承和格格式意图。21 项新增行为及总计 753 项测试通过，9 页最终 PDF／SVG／DOCX 独立检查与逐页复查完成，新调用方通过。首轮 DOCX 断言已精确定位为普通段落默认运行展开，规范化表示后仍完整比较。下一优先项为[XML 主题设计](adr/0038-xml-theme-styles.md)，保持 XML body／cell 配置独立；GAP-04 不因 Java 子集而关闭全部样式目标。

阶段三十一复评：XML 主题保留 body／cell 独立配置和既有 size 行高规则，p／span 静态名称在全部分支编译检查。27 项新模板测试及全仓 780 项测试通过，4 页 PDF／SVG／DOCX 独立检查、逐页目视、新调用方及 12 组检查通过。实际补齐未知字体角色的 UTF-16 叶位置，深层来源保持；DOCX 单默认运行只按精确等价表示归一。下一单元为四类可编辑表单显式样式，仍不视为完整品牌主题。OCR 排除。

阶段三十二复评：[可编辑 FormStyle](verification/phase-32-editable-form-styles.md)已完成四类字段显式外观、旧默认和保存回读；807 项测试、8 页双渲染及人工目视、5 组独立检查和新调用方通过。独立检查发现并修复 PDFBox 零框仍描边的问题；真实 Unicode 同字形提取别名及固定字号裁剪边界保留。页区／目录主题仍开放，下一单元为显式 String 的平面离线 XML include。

阶段三十三复评：[平面离线 XML 片段](verification/phase-33-offline-xml-includes.md)已完成全分支编译、局部名称空间、循环副本引用、连续编号、来源与双重预算；853 项测试、4 页双渲染／根目视、12 组独立检查及新调用方通过。原生 DOCX 编号验证改为逐项 num／startOverride，9 项变异拒绝保留。递归 include 等仍开放，下一单元为目录三类文字独立样式与颜色。

阶段三十四复评：[目录独立样式](verification/phase-34-contents-styles.md)完成标题／页码完整排版、三色及设置意图；20 项新增与全仓 873 项测试、4 页双渲染／逐页复查、新调用方及 10 组独立检查通过。预留较大页码行高后统一最终偏移，不以虚构字形探测；固定 48 pt 溢出明确失败。页区默认与完整品牌主题仍开放，下一单元为[XML 编号设计](adr/0042-xml-multilevel-numbering.md)。

阶段三十五复评：[XML 多级十进制编号](verification/phase-35-xml-numbering.md)复用现有NumberedItem／SemanticBlocks／DOCX，只扩静态level到1..9。26项新增与全仓899项测试、4页双渲染／逐页复查、12组独立检查及新调用方通过；九级int边界另核标准OOXML，9项变异全部拒绝。实际父级／溢出／固定字段失败阶段准确保留；编号内link等白名单不扩大。GAP-07保持部分支持；页区默认、完整主题、递归续排、持续容量及HTML/CSS／设计器仍待独立推进，OCR排除。

HTML／Markdown 导出已由[阶段四十三](verification/phase-43-web-exports.md)完成独立语义适配器、逐功能报错／警告／忽略策略及内容验收，详见[支持矩阵](web-export-api.md)。这不关闭 GAP-07 的 HTML/CSS 输入或 GAP-13 的设计器，也不扩大绘制组件的语义反向转换范围。

阶段五十八／五十九补齐有界递归 include、动态链接／完整字段目标，以及普通／富文本／OpenType 和混合流的实际变栏宽。各项独立输出与隔离调用方通过；变宽表格／最低多行策略及完整主题仍按明确契约推进，见[阶段五十八](verification/phase-58-recursive-includes.md)和[五十九](verification/phase-59-variable-columns.md)。
