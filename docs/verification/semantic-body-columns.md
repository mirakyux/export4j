# 可编辑正文分栏与中间通栏图表验收

本批验证 ColumnFlow／XML columns、设计器编辑和保存链路，以及正文多栏与通栏图表的交替布局。接口及限制见[正文分栏指南](../column-flow-api.md)，架构见 [ADR-0109](../adr/0109-semantic-body-columns.md)。

## 场景

- 1～3 栏、等宽／显式比例、pt 栏距、有界平衡、当前页剩余高度及跨页。
- 多栏正文→通栏语义表格→通栏图片→继续多栏，同页容纳时不强制分页。
- 原文 UTF-16、来源 ID、最终字段和外链，表头重复、短区、首行缩进与图片、固定宽表跳过过窄栏。
- XML 条件／循环／片段、空动态容器、非法位置和参数、模型保存及普通 ZIP 重开、编辑后再保存。
- 设计器修改栏设置、树内编辑／插入／排序、两次中途通栏插入，以及从菜单打开双栏和三栏样例。
- DOCX／Web 默认拒绝；统一转换显式 COLUMN_FLOW／DEGRADE 后保留顺序正文并报告布局损失，失败前不写入旧流。

## 可复现入口

从仓库根目录运行，Windows 使用以下 Wrapper；Linux／macOS 换为 `./gradlew`。需要 Java 21、已安装的项目验证 Python 依赖及 Poppler；构建设计器需要 Node.js 22 或更高版本。

```powershell
.\gradlew.bat --max-workers=1 --no-parallel build sbom publishLocalBundle `
  :examples:flowPdf :examples:tablePdf :examples:continuousColumns :examples:mixedColumns `
  :examples:production :examples:advancedPdf :examples:xmlTemplates `
  "-Pexport4jVerificationPython=build/verification/venv/Scripts/python.exe"
.\gradlew.bat --max-workers=1 --no-parallel :export4j-designer:installDist :export4j-designer:distZip
.\gradlew.bat -g build/gradle-validation --max-workers=1 --no-parallel -p verification/consumer `
  -PconsumerBuildDirectory=build/validation-columns clean build run columnFlow resolvedLibraries `
  --refresh-dependencies --write-locks --no-daemon
python scripts/verify_column_flow.py --consumer-build-directory verification/consumer/build/validation-columns `
  --designer-output-directory build/verification/designer-studio/browser
```

设计器浏览器测试在 `export4j-designer/frontend` 运行；测试自行启动已构建的本地应用及无界面 Chrome，操作实际界面并保存下载内容：

```powershell
$env:EXPORT4J_STUDIO_SCENARIOS='body columns'
node --test tests/studio.test.mjs
```

新增隔离调用方[ColumnFlowConsumer](../../verification/consumer/src/main/java/consumer/ColumnFlowConsumer.java)仅使用发布制品，无项目依赖。生成四组 XML／ZIP 及其编辑后的版本、八份 PDF；两栏、三栏、1:2 比例及多页场景分别检查。固定字体为 Liberation Sans，图像为自编三色几何图。独立 Python 从最终 PDF 解码原生字形来源及实际推进，核对完整段落、标签唯一顺序、所有续行栏界、通栏表边框与图像尺寸、全部打印字符边界，以及 ZIP 图片原字节保留。

## 结果与证据边界

完整 `build sbom publishLocalBundle` 及上述示例任务通过，1,966 项 Java 测试，失败／错误／跳过均为 0。公共 API 检查为 0 个不兼容声明、0 个外部类型泄漏；API 检查器的 3 项独立测试通过。设计器完整 Java 测试、安装包／ZIP 构建与两项实际浏览器场景通过。

本次成功隔离目录为 `verification/consumer/build/validation-columns-final/`。四组原始／编辑后共八份 PDF、12 页，所有来源与几何检查、双渲染均通过，最大平均通道差 1.571439、最大变化比例 0.004821。循环生成 ID 的静态保存只为 IDENTIFIER 显式允许重命名，并核对准确损失条数；其他损失仍严格拒绝。首次模型保存和编辑 ZIP 都验证原始图片编码字节完整保留。

上述 12 页均已逐页目视检查，无文字溢出、裁切、重叠或栏间距异常；多页原版和编辑版各 3 页，第二页在前段正文之后容纳通栏表／图及后续分栏。末页有限尾段存在正常留白，没有接近空白的额外追加页。该结果不扩大为全部 286 个 ManuWeave 模板的重新视觉验收。

连续分栏、混合分栏及进阶 PDF 的独立历史回归共 24 页通过，原视觉基线未更新；进阶样例的链接、书签及 Unicode 表单往返保持。日志汇总位于 `build/verification/column-flow-regressions/`。

XML 模板另外 4 页、生产输出另外 26 页独立回归通过；五项脚本合计 54 页。生产验证覆盖 29 个运行组件、16 个库和 16 项 BOM 约束；本批未提供人工阅读器填写保存证据。临时签名之后已恢复默认 SNAPSHOT 的 SBOM 和本地 Maven 制品，并逐一对比当前 16 个库 JAR 与本次调用方实际解析摘要，一致性通过，记录位于 `build/verification/columns-restored-jars.json`。

临时本地版本 `0.0.0-verification-columns-v1` 的签名分发校验通过：17 个组件、82 份签名制品、16 个 BOM 库约束及 SBOM／实际 JAR 摘要一致；独立复制的调用方解析 16 个库及 BOM，篡改签名拒绝，临时私有 keyring 已清理。源文件清单和摘要位于 `build/verification/columns-signed-final/`。这是临时本地测试身份，没有提交、推送、上传或正式发布。

验收输出位于忽略目录 `build/verification/column-flow/` 与 `build/verification/designer-studio/browser/`。PDF 独立渲染使用 Poppler 26.07.0、pypdfium2 5.13.0，结构工具使用 pypdf 6.10.0、Pillow 12.3.0。全部新页以 192 DPI 双渲染，固定 blur 2 px、平均通道差 <4、变化像素比例 <0.06；不更新历史视觉基线。

本批没有提供 Word 原生多栏、HTML／Markdown 原生分栏、任意跨部分栏的栅格容器、Java DocumentEditor 列内编辑，或列内脚注／尾注锚点。XML／设计器编辑、原生 Word 数学和 Word 原生分栏分别验收，不能互相替代。阅读器中的任意手工编辑、完整投稿合规及正式发布不属于这些自动化结果。

设计器三份下载 PDF 的实际分页为自定义编辑场景 3 页、双栏内置样例 2 页、三栏内置样例 1 页，六页独立渲染并逐页目视检查。最大平均通道差 3.169366、最大变化比例 0.034422，字形均在各自真实页边距内，无裁切或重叠。自定义场景保留用户操作后的 `balanced=false` 和 `1 2 1` 比例，尾页仅左栏；双栏样例第二页只占顶部约 141 pt，仍有稀疏尾页。列内小标题可能留在前栏末尾，当前不支持 keep-with-next；不将本批结果称为完善的孤题控制。
