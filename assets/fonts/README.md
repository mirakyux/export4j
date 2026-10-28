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

上表静态字体均采用 SIL Open Font License 1.1，原文件未经修改。中文样例使用 Liberation Sans 与霞鹜文楷 TC；复杂文字语料另显式传入相应脚本的 Noto 字体。三个变体不替换原 Regular；Regular 仍按 PDFBox 来源复现。可变、彩色及项目自产测试字体的独立来源和许可见下文，不能将本目录所有字体笼统归为 OFL。

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

采用 OFL 的字体可按其条件随软件打包、嵌入与再分发，必须保留版权和许可证，不得单独销售字体。修改版涉及保留字体名时须遵守原许可。OFL 对字体的约束不使使用字体创建的文档自动采用 OFL。彩色样本和项目自产几何分别遵守下述许可。

加载器还检查字体 OS/2 嵌入标志，拒绝受限或仅位图嵌入，禁止子集化时使用完整嵌入。标志检查不能替代调用方对实际字体许可证的审查。

测试对内存字节中的嵌入权限进行局部修改，以验证拒绝和完整嵌入路径；这些修改不写回字体文件，也不作为修改字体资产分发。

## 原生 OpenType 验收资源

| 文件 | 固定来源 | 许可与用途 |
| --- | --- | --- |
| `RobotoFlex.ttf` | Google Fonts 提交 `d427686494a89f0b7ecb9833273b6aabb83a6350` 的 `ofl/robotoflex/RobotoFlex[GRAD,XOPQ,XTRA,YOPQ,YTAS,YTDE,YTFI,YTLC,YTUC,opsz,slnt,wdth,wght].ttf` | `RobotoFlex-OFL.txt`；13 个轴的度量与真实轮廓变化。仅使用较短本地文件名，字节及字体内名称未修改 |
| `SourceSans3VF-Upright.otf` | Adobe Source Sans 标签 `3.052R` 的 `VF/SourceSans3VF-Upright.otf` | `SourceSans3-OFL.txt`；CFF2 权重插值 |
| `NotoSansBengali-Regular.ttf`、`NotoSansTamil-Regular.ttf`、`NotoSansMyanmar-Regular.ttf`、`NotoSansKhmer-Regular.ttf`、`NotoSansSinhala-Regular.ttf`、`NotoSansHebrew-Regular.ttf` | `notofonts/noto-fonts` 提交 `ffebf8c1ee449e544955a7e813c54f9b73848eac` 的 `hinted/ttf/<字体族>/<文件名>` | 共用 `NotoNaskhArabic-OFL.txt`；孟加拉、泰米尔、缅甸、高棉、僧伽罗与希伯来语簇和独立塑形对照 |
| `NotoColorEmoji-COLRv1.ttf`、`NotoColorEmoji-sbix.ttf` | `googlefonts/color-fonts` 提交 `0046ea4c3b69e9fbbe464c2594816894e3aa5e4b` 的 `fonts/noto-glyf_colr_1.ttf`、`fonts/noto-sbix.ttf` | `ColorFonts-LICENSE.txt`（Apache-2.0）；保留原字节与内部名称，验证真实 COLRv1 和 PNG/sbix 着色 |
| `Export4j-CID-Test.otf`、`Export4j-ColorV0-Test.ttf`、`Export4j-ColorV1-Test.ttf` | 项目自编 `scripts/generate_opentype_fixtures.py`，fontTools 4.62.1，固定字体时间戳 | 项目 Apache-2.0；自主矩形、圆形和渐变，无外部字形轮廓。覆盖 CID CFF、双 palette、COLRv0、COLRv1 三种渐变及 28 种合成模式 |

两份 Noto 彩色字体由上游配置从 SVG 图像重新生成，并非复制已发布的 OFL Noto 字体。上游固定子模块 `font-srcs/noto-emoji` 指向提交 `b960563a023fbd1337227bf2a8a2d5a91889a333`：其图像／工具的 Apache-2.0 原文保存在 `NotoEmoji-Artwork-LICENSE.txt`，旗帜来源及例外原文保存在 `NotoEmoji-RegionFlags-LICENSE.txt`。字体来源、图像来源和普通静态 Noto 字体的 OFL 不混用。

这些资源只用于示例和验证；`export4j-opentype` 普通 JAR 不嵌入它们，也不自动下载字体。原生二进制是另一个显式资源选择，见[原生 OpenType API](../../docs/native-opentype-api.md)。

### 新增资源字节与校验

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| `RobotoFlex.ttf` | 1,787,292 | `9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281` |
| `RobotoFlex-OFL.txt` | 4,488 | `9cbaed04b20c853f99840efe5dc96956f6f6120ed83a0ade35f9281a2b63e5d0` |
| `SourceSans3VF-Upright.otf` | 538,164 | `3d0dfd6a3a644ab3d462a737923ffac41fb0ae007ce9ba83c24e6bfa76aa56c7` |
| `NotoSansBengali-Regular.ttf` | 200,048 | `6300c5370cd688b0641343de4c786de6d412bb6c578d129dae75e93a0322dcab` |
| `NotoSansTamil-Regular.ttf` | 74,248 | `6532db33b8b264abe3a098a40619feb489b5ddf5ab1d2b46e72b51eeb548001b` |
| `NotoSansMyanmar-Regular.ttf` | 195,408 | `9dd2bc76a822a974efce803a4b07302d14d01d5c1fa9e33a258d15a9cb513a7d` |
| `NotoSansKhmer-Regular.ttf` | 114,096 | `5c1ec068352f1e1fe8e7a3218230360d054e104d377ef40c0423b17c34c3c259` |
| `NotoSansSinhala-Regular.ttf` | 310,232 | `a966549310b2d3046ba25bbeef11985428ff9356fa656fd6118bc90fb82df031` |
| `NotoSansHebrew-Regular.ttf` | 26,900 | `a7fa16fffb27bedb060a0866267c29e9859aeb9c21cc33f5b3aaf6eb062eca85` |
| `NotoColorEmoji-COLRv1.ttf` | 4,619,384 | `fc1c3b581d01fcaed3283b9a12a04f784db0a22710e1c196dfb3a52696063fdc` |
| `NotoColorEmoji-sbix.ttf` | 9,365,452 | `fd68ec5f75e244db879b1f3aaf453627ed764453343a2dbaa8b17da8641d75b5` |
| `ColorFonts-LICENSE.txt` | 11,358 | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| `NotoEmoji-Artwork-LICENSE.txt` | 11,357 | `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |
| `NotoEmoji-RegionFlags-LICENSE.txt` | 3,479 | `73af5be6e2ea006b75bebfd2f463f252c5ce8c94024c639dbdd97f0456272a5a` |
| `Export4j-CID-Test.otf` | 1,144 | `6bb6f3506ce4e04b6922546ae6ccc1689f14e2682156536bd965dc934af5fe36` |
| `Export4j-ColorV0-Test.ttf` | 1,996 | `a8f79a18311ff2f340c3bf0be0314fc90b870caaf1e0d244aa7ff86b6bd1c347` |
| `Export4j-ColorV1-Test.ttf` | 2,096 | `88056d20ed7d032028c1753f3ae679589d58b0cc119458f0cf46f758975d9e97` |
