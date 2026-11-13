# 行内矢量样例验收

- 日期：2026-11-13。
- 状态：本地结构、独立渲染与全部页面复查通过；不代表远程发布或任意公式兼容。
- 范围：显式 `InlineVector` 在段落、语义表格及 HTML 导入中的路径、基线、来源与阅读文字。

## 样例与复现

[InlineVectorsExample](../../examples/src/main/java/com/mirakyux/export4j/examples/InlineVectorsExample.java)生成 1 页 600 × 900 pt 文档，包含 3 个公式资源、8 次呈现、8 枚实际 U+FFFC 模型标记，HTML 导入损失为 0。公式分别包含分式与根号、上下标、求和；复用同一资源的出现次数为 3、3、2。它调用既有示例内的 `ManuWeaveMath` 转换器，生产库仅接收不可变有界轮廓，没有增加 TeX 解析依赖。

使用 JDK 21 和 Gradle Wrapper。独立工具依赖安装到隔离 Python 环境，版本以 `scripts/requirements-verification.txt` 为准，另安装 Poppler。以下命令从仓库根目录运行，`python` 指向该隔离环境：

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:inlineVectors --console=plain
python scripts/verify_inline_vectors.py `
    --source-directory examples/build/inline-vectors `
    --output-directory build/verification/inline-vectors
```

Linux／macOS 使用 `./gradlew`，将 Python 命令写在同一行或用 shell 续行符。样例输出为 `mixed.pdf`、`mixed.svg`、144 DPI 的 `page-00001.png` 和 `geometry.json`。[独立验证脚本](../../scripts/verify_inline_vectors.py)在输出目录保存结构／几何报告、原 TeX 提取文字、全部独立渲染页及联系表，不更新视觉基线。

本次工具为 Python 3.12.14、pypdf 6.10.0、pypdfium2 5.13.0／PDFium 153.0.7999.0、Pillow 12.3.0、resvg-py 0.5.0 和 Poppler 26.07.0。

## 结构、坐标与提取

独立解析器读取实际 PDF 内容流和 SVG 路径，逐段核对资源身份、坐标及顺序，从已绘制路径推导基线和预留框；不只比较生成器宣称的计数。3 个资源分别为 640、531、587 段，所有 8 次呈现均匹配。坐标允许误差为 0.002 pt，PDF 最大实际误差为 0.0000352 pt，SVG 最大误差约为 5.68 × 10⁻¹⁴ pt。

PDF 不含 Image XObject。公式使用真实非零绕组填充路径，并通过 8 个不可见逻辑锚点的 `ActualText` 保留原 TeX；未把公式当作普通原生字形。PDFium 全文提取、搜索与选区均恢复对应原 TeX，出现次数为 3、3、2。SVG 的逻辑标签也保留相同顺序及次数。

普通文字中有 1 个嵌入的 LiberationSans 字体和 7 个原生文字显示操作。该数字只描述样例中符合条件的文字，不能推导公式旁的文字、复杂脚本或所有混排行都使用原生字体。忽略 `ActualText` 的提取器仍可能只返回公式锚点，逐字坐标、可编辑公式、OMML 和 PDF/UA 合规不在本次结论内。

## 独立渲染与负控制

完整页面以 144 DPI 输出为 1,200 × 1,800 像素。Poppler 与 PDFium 分别渲染 PDF，resvg 渲染实际 SVG，并与实际导出 PNG 一同对照。跨引擎抗锯齿差异保留原始指标，再按既定协议使用 1 像素模糊、平均通道绝对误差小于 4、变化像素比例小于 6% 判断；没有放宽阈值或刷新基线。

| 对照 PDFium | 模糊后平均通道误差 | 模糊后变化像素比例 |
| --- | --- | --- |
| Poppler PDF | 0.46105 | 0.5463% |
| 导出 PNG | 0.16599 | 0.1230% |
| resvg SVG | 0.21337 | 0.1367% |

针对源清单和实际文件解析出的路径构造 5 项负控制，均被验证器拒绝：修改资源坐标／哈希、声明基线改变 2 pt、移除模型原子标记、移除第一式的全部解析路径、额外加入未声明的平移公式。坐标／来源检查和像素对照相互补充，避免把计数正确或阅读文字存在当作视觉完整。

完整四种渲染联系表及 PDFium 整页已复查，检查了公式基线、根号、分式、求和、相邻文字和表格位置，未见新增截断或错位。该复查与自动检查分别记录，不将自动生成图片称为已经看过。

## 验收边界

此记录验证一个明确样例，不涵盖任意 LaTeX、通用 SVG 导入、复杂塑形的原生字形输出或跨格式无损恢复。高层矢量的默认拒绝／明确降级、工作预算及模型原子契约见[矢量 API](../inline-vectors-api.md)。真实文献和仅图片的安全原生混排另按[文献接入说明](../literature-pdf-integration.md)验收；官方 FontBox 回退的历史像素相等结果不能替代这些新路径。
