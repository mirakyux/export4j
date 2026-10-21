# 字体资源

本目录提供示例与测试使用的固定字体。核心库不携带示例字体；示例 JAR 携带字体、版权声明与许可原文。运行时不扫描系统字体，也不自动访问网络。

## 来源与用途

| 文件 | 来源与固定版本 | 权利人 | 用途 |
| --- | --- | --- | --- |
| `LiberationSans-Regular.ttf` | [PDFBox 3.0.8 JAR](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox/3.0.8/pdfbox-3.0.8.jar) 中的 `org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf`，原样提取 | Google Corporation（2010）、Red Hat, Inc.（2012） | 拉丁文字度量、主字体、嵌入与回退测试 |
| `LiberationSans-Bold.ttf`、`LiberationSans-Italic.ttf`、`LiberationSans-BoldItalic.ttf` | [Liberation Fonts 2.1.5 官方发布包](https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz)，原样提取 | Google Corporation（2010）、Red Hat, Inc.（2012） | 综合示例的真实粗体、斜体与粗斜体 |
| `LXGWWenKaiTC-Regular.ttf` | [Google Fonts 固定提交](https://raw.githubusercontent.com/google/fonts/d427686494a89f0b7ecb9833273b6aabb83a6350/ofl/lxgwwenkaitc/LXGWWenKaiTC-Regular.ttf)，提交 `d427686494a89f0b7ecb9833273b6aabb83a6350` | The LXGW WenKai Project Authors（2024） | 当前中文回退、混排、分页及 PDF 输出样本 |
| `ZCOOLXiaoWei-Regular.ttf` | [Google Fonts 固定提交](https://raw.githubusercontent.com/google/fonts/d427686494a89f0b7ecb9833273b6aabb83a6350/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf)，提交 `d427686494a89f0b7ecb9833273b6aabb83a6350` | The ZCOOL XiaoWei Project Authors（2018） | 中文字体回退、中英文混排与 PDF 提取验证 |
| `NotoNaskhArabic-Regular.ttf` | [Noto Fonts 固定提交](https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-Regular.ttf)，提交 `ffebf8c1ee449e544955a7e813c54f9b73848eac` | The Noto Project Authors（2018） | OpenType 阿拉伯语连接字形、组合字符及双向混排 |
| `NotoSansDevanagari-Regular.ttf` | [Noto Fonts 同一固定提交](https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/NotoSansDevanagari/NotoSansDevanagari-Regular.ttf)，原样保存 | The Noto Project Authors（2018） | 天城文连接簇、前置元音、断行与阅读器语料 |
| `NotoSansThai-Regular.ttf` | [Noto Fonts 同一固定提交](https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/NotoSansThai/NotoSansThai-Regular.ttf)，原样保存 | The Noto Project Authors（2018） | 泰文组合标记、断行与阅读器语料 |
| `SourceSans3-Regular.otf` | [Adobe Source Sans 3.052R](https://raw.githubusercontent.com/adobe-fonts/source-sans/3.052R/OTF/SourceSans3-Regular.otf)，原样保存 | Adobe（2010～2022），保留字体名 Source | 静态 OTF/CFF 加载、集合成员提取及轮廓输出 |

审查日期：2026-10-16。十个字体文件均采用 SIL Open Font License 1.1，原文件未经修改或重命名。中文样例使用 Liberation Sans 与霞鹜文楷 TC；复杂文字语料另显式传入 Noto Naskh Arabic、Noto Sans Devanagari 和 Noto Sans Thai。三个变体不替换原 Regular；Regular 仍按 PDFBox 来源复现。

站酷小薇体保留用于阶段二历史样本。其“回”字在直接 FreeType 渲染时也显示为实心轮廓；阶段三新增霞鹜文楷 TC 作为当前中文字体，避免修改原资源。

Liberation 的版权与 OFL 原文从已保存的 [PDFBox 完整 LICENSE](../../third-party/licenses/pdfbox-3.0.8-LICENSE.txt) 中按完整字体条目原样提取，保存为 [LiberationSans-OFL.txt](LiberationSans-OFL.txt)。其保留名称包括 Arimo、Tinos、Cousine 和 Liberation。

2.1.5 变体的版权和许可来自同一官方发布包的 `LICENSE`，原样保存为 [LiberationSans-2.1.5-OFL.txt](LiberationSans-2.1.5-OFL.txt)。遵守相同 OFL 嵌入与再分发条件，保留原字体名和许可文件。

站酷小薇体使用同一固定提交中的 [OFL.txt](https://raw.githubusercontent.com/google/fonts/d427686494a89f0b7ecb9833273b6aabb83a6350/ofl/zcoolxiaowei/OFL.txt)，原文保存为 [ZCOOLXiaoWei-OFL.txt](ZCOOLXiaoWei-OFL.txt)。

霞鹜文楷 TC 使用同一固定提交中的 [OFL.txt](https://raw.githubusercontent.com/google/fonts/d427686494a89f0b7ecb9833273b6aabb83a6350/ofl/lxgwwenkaitc/OFL.txt)，原文保存为 [LXGWWenKaiTC-OFL.txt](LXGWWenKaiTC-OFL.txt)。

Noto Naskh Arabic 使用同一固定提交中的 [LICENSE](https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac/LICENSE)，原文保存为 [NotoNaskhArabic-OFL.txt](NotoNaskhArabic-OFL.txt)。

Noto Sans Devanagari 和 Noto Sans Thai 来自相同提交，使用相同的上游 `LICENSE`，共用原样保存的 [NotoNaskhArabic-OFL.txt](NotoNaskhArabic-OFL.txt)。新增字体只进入示例／测试资源，不打包进核心库；运行时不下载字体。

Source Sans 3 使用同一固定标签中的 [LICENSE.md](https://raw.githubusercontent.com/adobe-fonts/source-sans/3.052R/LICENSE.md)，原文保存为 [SourceSans3-OFL.txt](SourceSans3-OFL.txt)。测试在内存中构造字体集合，不分发重新打包的字体文件。

## 字节与校验

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| `LiberationSans-Regular.ttf` | 410,712 | `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8` |
| `LiberationSans-Bold.ttf` | 414,456 | `788abee4c806d660e8aee46689dd8540cd4bb98da03dcc9d171ce3efd99a9173` |
| `LiberationSans-Italic.ttf` | 415,816 | `e5bae5c4cde31f22142753855f4f8fb86da6ff39955ed3c0a11248b0d16948b0` |
| `LiberationSans-BoldItalic.ttf` | 408,996 | `698da70fc191cc5f33ad4d6d3fe830fe4624b898ea2e3169955928b7c491f1ee` |
| `LiberationSans-2.1.5-OFL.txt` | 4,414 | `93fed46019c38bbe566b479d22148e2e8a1e85ada614accb0211c37b2c61c19b` |
| `ZCOOLXiaoWei-Regular.ttf` | 6,313,808 | `a42b620140f493db42f741351dfbf343c0936d58588ee8004b8b2a218d997ff1` |
| `LXGWWenKaiTC-Regular.ttf` | 13,110,528 | `4fcc5aec11cbbf737b0cfab7b63796f7f280087a7a656b2f13342cd5e5318d95` |
| `LiberationSans-OFL.txt` | 4,694 | `95aec8e37f46eae22a0798ada8e545beacd7b98856667693053777d173d1e8ea` |
| `ZCOOLXiaoWei-OFL.txt` | 4,400 | `a094514ca57cf8f9c5e8d8d1adab5d8cd3a377297ff016f9df2c05b3ecd77f0a` |
| `LXGWWenKaiTC-OFL.txt` | 4,390 | `4fff27d35db0e22cd81d58da6f20e09f415cf354a3338e3cf1fc0eb9222c7174` |
| `NotoNaskhArabic-Regular.ttf` | 178,388 | `2f4b88e6ee50fa82c617e2d1d4ba18281cb1c6cd71c3af3ec64970c23995db4b` |
| `NotoSansDevanagari-Regular.ttf` | 219,212 | `385e78e6359a9d88a0f243d53b1209d7548361ba2194e2b9ec779bcaa7e8949d` |
| `NotoSansThai-Regular.ttf` | 37,752 | `404ddfb5ed0aaa6b6ec8a85700d682978992062d67da93903967b56cbd9a4acc` |
| `NotoNaskhArabic-OFL.txt` | 4,377 | `0dab92d0544f7b233403f14b84a663bdbfa746982eda629e7f4f9ffe1b036feb` |
| `SourceSans3-Regular.otf` | 334,924 | `08df266400933d3178d081a45f94a08814c3e55b4b7dd2e0ff69cb1329f13ab6` |
| `SourceSans3-OFL.txt` | 4,579 | `89ad2c4f66dd29127527493e729c31e731f111cf10faf5774c3db9275ed0c22c` |

## 使用与分发

字体可按 OFL 条件随软件打包、嵌入与再分发，必须保留版权和许可证，不得单独销售字体。修改版涉及保留字体名时须遵守原许可。OFL 对字体的约束不使使用字体创建的文档自动采用 OFL。

加载器还检查字体 OS/2 嵌入标志，拒绝受限或仅位图嵌入，禁止子集化时使用完整嵌入。标志检查不能替代调用方对实际字体许可证的审查。

测试对内存字节中的嵌入权限进行局部修改，以验证拒绝和完整嵌入路径；这些修改不写回字体文件，也不作为修改字体资产分发。
