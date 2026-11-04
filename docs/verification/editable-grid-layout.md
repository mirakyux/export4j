# 可编辑语义栅格与设计器验收

本批补齐 GridLayout／GridItem、XML grid／item、模型保存和设计器编辑。接口见[栅格指南](../grid-layout-api.md)，架构决定见 [ADR-0110](../adr/0110-editable-semantic-grid.md)。连续正文分栏及通栏图表的既有验收见[分栏记录](semantic-body-columns.md)。

## 场景与判据

- 1～12 条等宽轨道，显式行号、起始轨、跨轨数，独立 pt 行列间距；6+6、8+4、4+4+4、零列距和偏移留空。
- 同一行顶部对齐、高度取最高项，短区域整行移页；跳过逻辑行号不产生空白物理行，超高行明确失败。
- 正文 ColumnFlow → GridLayout → ColumnFlow 同页接续；项内原始段落、表格和图片保留真实 ID、UTF-16、最终字段和图文链接。
- 解析、绑定、来源、测量和呈现预算共享，取消和失败原子性保留；独立 GridItem、互嵌、页区及格内栅格明确拒绝。
- XML 条件／循环／片段保留作用域；动态展开后重叠或乱序拒绝，动态全空省略。模型 ZIP 三轮保存重开保持坐标、内容、样式及原图字节。
- 设计器实际插入和编辑，预设生成唯一 ID，修改坐标后保持合法源顺序；树内编辑段落／表格／图片，画布使用实际项边界，支持撤销及 ZIP 重开。
- DOCX／HTML／Markdown 默认拒绝；显式 GRID_LAYOUT／DEGRADE 按源顺序保留全部项内容并记录布局损失，WARN／IGNORE 不删除实际损失记录。

## 可复现入口

命令从仓库根目录运行。需要 Java 21、Node.js 22 或更高版本、项目固定 Python 验证依赖及 Poppler。Linux／macOS 将 Windows Wrapper 换成 `./gradlew`，并替换 Python 可执行文件路径。

```powershell
.\gradlew.bat --max-workers=1 --no-parallel build sbom publishLocalBundle `
  :examples:flowPdf :examples:tablePdf :examples:continuousColumns :examples:mixedColumns `
  :examples:production :examples:advancedPdf :examples:xmlTemplates `
  "-Pexport4jVerificationPython=build/verification/venv/Scripts/python.exe"
.\gradlew.bat --max-workers=1 --no-parallel :export4j-designer:installDist :export4j-designer:distZip
.\gradlew.bat -g build/gradle-validation --max-workers=1 --no-parallel -p verification/consumer `
  -PconsumerBuildDirectory=build/validation-grid-final clean build run gridLayout columnFlow resolvedLibraries `
  --refresh-dependencies --write-locks --no-daemon
python scripts/verify_grid_layout.py --consumer-build-directory verification/consumer/build/validation-grid-final `
  --designer-output-directory build/verification/designer-studio/browser
python scripts/verify_column_flow.py --consumer-build-directory verification/consumer/build/validation-grid-final `
  --output-directory build/verification/grid-column-regression
```

设计器浏览器场景从 `export4j-designer/frontend` 运行，使用真实安装分发，自行启动回环服务和无界面 Chrome：

```powershell
$env:EXPORT4J_STUDIO_SCENARIOS='grid editor'
node --test tests/studio.test.mjs
```

[隔离调用方](../../verification/consumer/src/main/java/consumer/GridLayoutConsumer.java)只使用本地 Maven 制品；固定字体为 Liberation Sans，自编三色几何图为 360×168 像素。四组原始／编辑件分别覆盖混合分栏和栅格、三轨、零列距／稀疏行号、三页原子行。编辑件必须出现 EDIT-A 且不再出现 GRID-A；混合样例还将首行 6+6 改成 8+4。

[独立验证器](../../scripts/verify_grid_layout.py)解码最终 PDF 的原生字形和推进，比较完整 XML 原文顺序及唯一性；检查所有续行轨道范围、整行同页、图片横纵位置和尺寸、偏移表格边界、实际行距、ZIP 原图字节和 SVG 阅读顺序。全部新 PDF 页经 Poppler 与 PDFium 以 192 DPI 渲染，固定 blur 2 px、平均通道差 <4、变化像素比例 <0.06；不刷新历史视觉基线。

## 实测结果

完整 `build sbom publishLocalBundle` 和上述七项示例任务通过。全仓共 2,004 项 Java 测试，失败／错误／跳过均为 0，包含共享测量预算负例；最终设计器边界修改后另跑全部 55 项设计器测试及 installDist／distZip，通过。公共 API 检查为 0 个不兼容声明、0 个第三方类型泄漏；API 检查器的 3 项独立测试通过。

本次新隔离调用方目录为 `verification/consumer/build/validation-grid-final/`。四组原始／编辑件共 8 份 PDF、12 页，完整原文、坐标、整行分页、图片、表格、SVG 来源及三轮 ZIP 保存重开检查通过。分页夹具的首页用 230 pt 行框留下不足容纳整行的剩余空间；后续三行实际占第二、三页，不把预期留白视为自动分页失败。同行标签与图片段落间包含保存的 6 pt 段后距，相邻两行的标签基线差为 120 pt。

最终安装分发的两个浏览器场景通过，实际覆盖插入栅格、文字／表格／图片编辑、坐标变更、全部三种预设、撤销／重做、ZIP 保存重开和 PDF 下载。修复过同次预设新增项的重复 ID，并核对每项及子段落的 ID 唯一性；排序也覆盖最大合法逻辑行号。创建场景和第 14 个内置样例各导出 1 页，均为 36 pt 页边距；ZIP 的字体、图片、许可及实际项几何检查通过。两份 PDF／ZIP、几何、样例数据、截图及最终应用 JAR 的摘要记录在 `build/verification/designer-grid-independent/final-artifacts-sha256.json`。

全部 14 页均经双渲染及逐页目视检查，无文字裁切、重叠、图片缺失或越轨。调用方 12 页的最大平均通道差为 0.862478、最大变化比例为 0.005443；设计器 2 页分别为 1.549654 和 0.009776。混合样例从 6+6 改为 8+4 后，右项增加换行，后续行按实际高度下移；导出的 PDF 不包含设计器选择框和边界标签。

既有独立回归共 66 页通过：连续分栏 9 页、混合图表 8 页、进阶 7 页、XML 4 页、生产及隔离调用方 26 页，以及语义正文分栏原始／编辑件 12 页。XML、生产及正文分栏验证均显式使用本次新调用方；进阶视觉基线没有更新。生产验证同时核对 29 个运行组件、16 个库及 16 项 BOM 约束。

临时本地版本 `0.0.0-verification-grid-v1` 的签名分发演练通过：17 个组件、82 份签名制品、16 项 BOM 约束、16 个库与 SBOM 摘要一致；独立复制调用方实际解析并消费相同字节，篡改签名拒绝，临时私有 keyring 已清理。输入及报告保存在 `build/verification/grid-signed-final/`。随后恢复默认 SNAPSHOT 的 SBOM 和本地 Maven 制品，并逐一核对当前 JAR、恢复后的仓库、SBOM 与本次调用方解析摘要，16 个库全部一致；记录见 `build/verification/grid-restored-jars.json`。本批未提交、推送、上传或正式发布。

证据位于忽略目录 `build/verification/grid-layout/`、`build/verification/grid-layout-regressions/`、`build/verification/grid-production-regression/`、`build/verification/grid-column-regression/` 和 `build/verification/designer-studio/browser/`。结构工具为 pypdf 6.10.0，独立渲染为 Poppler 26.07.0／pypdfium2 5.13.0，图像工具为 Pillow 12.3.0。本批没有重生成或重新目视验收全部 286 个 ManuWeave 模板。

## 支持边界

这是显式轨道与整行原子分页的子集，不包含跨行项、自动放置／回填、固定高度、任意重叠、项内跨页续排或布局容器互嵌。项内标题／列表／题注、keep-with-next、旋转及脚注／尾注锚点明确拒绝。Java DocumentEditor 只支持整个栅格的移动／替换，内部编辑仍由 XML 和设计器完成。

DOCX／Web 的显式展平不保留并排轨道。既有 Word 原生可编辑公式与本批栅格是独立能力，本批不宣称 Word 原生栅格、完整 CSS Grid、一般 Word 往返或投稿合规。自动浏览器操作、独立 PDF 渲染与任意人工阅读器编辑分别登记；本地构建不等于提交、推送或正式发布。
