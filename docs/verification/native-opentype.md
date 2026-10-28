# 原生 OpenType、分页竖排与 CFF 字体嵌入验收

## 范围

本轮按 GAP-08.1 原清单验证一般 TTB 竖排、两种列序、换列／分页、纵中横、13 个变量轴、CFF2、COLRv0／COLRv1／sbix、更多连接脚本及静态 CFF 原生 PDF。实现和资源契约见[原生 OpenType API](../native-opentype-api.md)，不将上游 API 的存在作为输出验收结果。

固定字体来源、字节与许可见[字体资源](../../assets/fonts/README.md)。Windows x64 使用 LWJGL 3.4.3 原生包，实际 HarfBuzz 14.3.0；独立 uharfbuzz 0.55.0 使用 HarfBuzz 14.2.1，fontTools 4.62.1 对照原字体表及插值轮廓。文件结构使用 pypdf 6.10.0，搜索／选区及独立渲染使用 PDFium（pypdfium2 5.13.0），另使用 Poppler 96 DPI、Pillow 12.3.0 和 resvg-py 0.5.0。

## 可复现入口

从仓库根目录运行，Linux／macOS 使用 `./gradlew` 并显式选择相应 native classifier；该平台运行结果须另行登记。

~~~powershell
.\gradlew.bat :export4j-opentype:test :export4j-backend-pdf:test :export4j-text-core:test
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<unique-validation-directory>" clean build openType resolvedLibraries --refresh-dependencies --write-locks
python scripts/verify_native_opentype.py `
    --consumer-build-directory verification/consumer/build/<unique-validation-directory> `
    --verapdf build/verification/verapdf/verapdf.bat
~~~

先按[隔离调用方脚本](../../scripts/verify_local_consumer.ps1)配置本轮 Maven 仓库，每次以新的唯一名称替换 `<unique-validation-directory>`；构建和独立脚本必须使用同一个本次成功目录。独立脚本也支持成功调用方指针 `build/verification/consumer-build-directory.txt`。验证环境安装 `scripts/requirements-verification.txt`；Poppler 与 veraPDF 使用各自显式安装。

## 实测结果

| 验收项 | 结果及证据范围 |
| --- | --- |
| Java 回归 | 22 个原生 OpenType 测试、2 个 CFF 嵌入测试通过；共享文字回归保留 CFF native GID、显式轮廓及补充平面缺字来源断言 |
| 独立塑形 | 26 个 run、643 个 glyph 的 GID、UTF-16 簇、offset 和 advance 与独立 HarfBuzz 一致 |
| 真实轮廓 | fontTools 按所选轴插值并计算 glyph bounds，与导出轮廓逐 glyph 对照；13 个 Roboto Flex 轴逐一改变实际轮廓，CFF2 的 200／900 权重改变轮廓和度量 |
| 脚本 | 阿拉伯、天城、泰、孟加拉、泰米尔、缅甸、高棉、僧伽罗与希伯来语；另有阿拉伯／希伯来／拉丁／数字双向混排 |
| 竖排 | 真正 TTB 度量和标点替换；右至左、左至右各 3 页，直立 1 页；纵中横首组 `12` 保持水平并纳入原文连续覆盖 |
| 彩色字形 | COLRv0 双 palette、COLRv1 实色与三种渐变、28 种合成模式、自建几何和真实 Noto COLRv1／sbix；透明字形保持透明，palette 修改实际输出像素 |
| 来源与选择 | 37 组普通字体输出：PDF ActualText 和逐页来源原样一致，PDFium 逐页提取去除布局空白后与原文一致；所有固定查询的次数、所选文字、非空且页内的选区通过 |
| CFF 原生嵌入 | name-keyed Type1C 与 CID-keyed CIDFontType0C 的完整嵌入程序等于原 CFF 字节，多编码字典共享同一程序；pypdf 与 PDFium 均提取真实文字，CID 语料包含补充平面字符 |
| PDF/A 回归 | name-keyed 与 CID-keyed 两种 CFF 各 1b／2b／3b／4f，合计 8 份最终文件通过独立 veraPDF；结构、查询及双渲染另行通过 |
| 输出与视觉 | 合计 45 份 PDF、49 页，全部经 PDFium／Poppler 双渲染并检查；普通输出另与独立 PNG 后端对照。逐页查看无缺字方框、裁切、行间覆盖或黑色 emoji 回退 |
| SVG 独立输出 | 37 份 SVG 的 41 个内页逐页经 resvg 渲染，与对应 PDF 页对照；原文标题标签也逐页核对，系统字体加载关闭 |
| 预算／取消 | 超量输入、工作次数、glyph／轮廓、最终彩色像素和真实复杂 emoji 的中间画面超限明确失败；取消与缺字原文范围保持 |
| 缺少 native | 独立 JVM 移除两个 native classifier 后，真实字体加载抛 `NATIVE_UNAVAILABLE`，没有自动下载或字体替代 |

普通字体双渲染经一像素模糊后的最大平均通道误差为 2.020717，独立 PNG 与 PDFium 的最大值为 1.969096，resvg 与 PDFium 的最大值为 2.107785；脚本同时检查有意义的变化像素比例。误差容限覆盖 96 DPI 下抗锯齿差异，不以像素逐字节相等定义跨渲染器正确性。

最终隔离 Maven 调用方重新生成全部文件，独立脚本以最终文件重新核对结构、原文、塑形、查询、选区、双渲染和八档合规；其 49 页渲染还逐页与本轮已逐页查看的图像作像素对账，全部相同。最终报告保存在本次 `--output` 目录，未把直接编译结果冒充 Maven 制品验收。

原生着色接入曾因绑定与二进制 convenience API 不同而忽略 palette，并因缺少显式基础变换产生透明输出。回归同时检查真实 emoji 可见像素和 palette 差异。逐簇 RTL 锚点还曾被 PDFium 再排序；现使用真实行／run 范围的逻辑锚点，独立查询验证不反转查询词。CID CFF 的 Encoding CMap 流字典也保留 Registry／Ordering，与后代字体一致，防止普通阅读器能打开却不符合 PDF/A。

## 边界

PDFium 的查询和选区证据是实际引擎 API 操作，未冒充 Edge／Acrobat 的人工界面验收。轮廓正文使用行／run 逻辑锚点，支持原文查询和非空选区；其矩形不是逐字形完整轮廓。pypdf 未实现 ActualText 的一般提取，轮廓文件可能提取出不可见 `x` 锚点；脚本记录真实结果，不将它与 ActualText 或 PDFium 结果混为一谈。原生静态 CFF 已具有真实字体映射，独立于该轮廓限制。

本轮证明固定语料、显式字体、Windows x64 和所列工具的结果。没有声明任意字体／脚本／阅读器、未来 Unicode 版本、全平台二进制或全部 OpenType 扩展都通过；字体内 SVG 图像仍明确拒绝，彩色图像具有显式 DPI，CFF 可编辑字段继续要求其他可用字体。语义 DOCX／HTML 导入导出不自动获得这些原生排版特性。

最终全仓构建、SBOM、Maven 制品和本轮隔离消费调用方的综合结果见[阶段九十九](phase-99-layout-fonts-streaming-graphics-closure.md)。
