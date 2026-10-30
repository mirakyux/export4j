# 阶段一百零六：安全修复与固定兼容语料

本阶段推进 GAP-14.2 的可复现兼容／拒绝语料，以及 GAP-14.3 的候选 API 和制品检查。修复已复现的 DOCX 包校验缺陷，升级受已知公告影响的依赖；没有执行 Maven Central 上传、正式版本创建或远端仓库消费。

## DOCX 完整输入校验

原读取只遍历 ZIP 局部条目，没有验证完整中央目录：截断至中央目录前的包、中央／局部记录不一致或追加数据可能被接受，ZIP 注释也可能绕过完整压缩输入预算。修复前新增的四项回归中三项失败，修复后全部通过。

`DocxPackageReader` 在解压前计量全部输入，并核对 EOCD、中央／局部名称、数量、偏移、大小、CRC、压缩方法及数据描述符。重复、重叠、未引用记录、截断及尾随数据失败；展开后的实际大小和 CRC 再与目录核对。普通导入、原生语义、转换和原包编辑共用该入口，损失动作不能放宽非法包检查。取消、借用流所有权和失败时旧目标保护保持。

支持经典单磁盘 STORED／DEFLATED ZIP、普通目录、UTF-8 名称、标准注释及数据描述符；ZIP64、加密和多磁盘明确拒绝。没有向文件系统解压。契约见[原包编辑](../docx-editing-api.md#安全预算和输出)与[原生语义](../docx-semantic-api.md#明确边界)。

## 依赖公告与升级

对实际运行 SBOM 的 13 项第三方 Maven 坐标查询 OSV，旧 jsoup 1.21.2 命中 `GHSA-65r4-943x-97jj`／`CVE-2026-75140` 和 `GHSA-pmhh-3w7g-xqp8`。当前入口没有调用公告对应的 XML builder 或自定义 Safelist 清洗；没有据此认定现有 HTML 路径可同样利用。jsoup 已升级至 1.23.2，锁、严格校验、运行 SBOM、原始 MIT 许可及 NOTICE 同步更新，没有增加运行依赖。

新 JAR、POM 与上游发布摘要独立对照，实际 SHA-256 为：

| 制品 | SHA-256 |
| --- | --- |
| jsoup 1.23.2 JAR | `e9d8c856856680427f096d156f02a414289985c930998fcb519ce5be39b42003` |
| jsoup 1.23.2 POM | `3ff28beafd484e9c345e0749d697d2aa31f29ace54b5d4da4e8375e6d8fe1802` |

`scripts/audit_dependencies.py` 查询 SBOM 的确切 Maven 版本并绑定 SBOM SHA-256。公告命中、网络／TLS／超时、响应不完整或坐标不一致均非零退出，错误报告覆盖旧成功报告。四项离线回归验证确切查询、公告失败、不完整结果拒绝及服务失败不沿用成功状态。2026-10-29 升级后 13 项查询没有已知公告命中。

这个结果只覆盖查询时数据库已收录的 Java 运行依赖公告，不覆盖自研代码、原生载荷、JDK、字体和构建／验证工具，也不构成无漏洞保证。公告适用性和其他 POM 校验记录见[依赖管理](../dependencies.md#已知漏洞检查)。

## 固定兼容与拒绝语料

独立 Python ZIP／XML 生成器创建 10 份小型 OOXML 输入，内容由项目编写；固定 ZIP 元数据，并验证两次生成的全部文件及清单字节相同。两份 STORED／DEFLATED 正控制可读取，八份负控制覆盖截断、目录名称／CRC／偏移／数量冲突、尾随数据、路径穿越及外部实体。每次输入清单记录确切 SHA-256，不引入外部样本或新素材。

隔离 Maven 调用方使用实际发布到本地仓库的坐标，执行 HTML／Markdown／DOCX／PDF 四输入×四目标，并分别选择 WARN 和 IGNORE，共 16 条路径、32 份输出。检查实际文字替换、未命中文字及颜色，预检和 ACTUAL 损失逐项一致；IGNORE 保留实际损失，只改变警告。需要丢弃的功能显式声明 DISCARD，不用默认忽略掩盖损失。

15 组控制共 73 次检查，包括非法 DOCX 的普通／原生导入、三种动作转换及原包编辑、严格损失拒绝、输出编码预算、同步取消、离线 CSS 和 XML 外部实体拒绝。失败保持旧目标并清理临时输出。成功索引在全部检查完成后写入，独立验证器同时检查清单、内容和损失，不能把残留成功文件当作本轮通过。

独立工具使用 pypdf、OOXML ZIP／XML、HTMLParser 与 markdown-it 检查 32 份输出；全部 8 页 PDF 经 Poppler 与 PDFium 双渲染及逐页复查。具体工具版本为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、markdown-it-py 4.0.0。有限合成语料不证明任意外部文档兼容。

最终调用方目录为 `verification/consumer/build/gap14-final`，独立报告为 `build/verification/gap14-final-independent/report.json`；输入清单 SHA-256 为 `194027f466f9f3e3e6be0078d28c022f00624e1ed15beee1a10bebeb70d15566`。最终八页的三张联系表与此前已逐页复查的图像像素完全相同。

在已安装[固定验证依赖](../../scripts/requirements-verification.txt)、Poppler 和 Java 21 的环境中，从仓库根目录执行：

```powershell
.\gradlew.bat build sbom publishLocalBundle
python scripts/test_dependency_audit.py
python scripts/audit_dependencies.py
python scripts/create_compatibility_corpus.py
.\gradlew.bat -p verification/consumer -PconsumerBuildDirectory=build/compatibility-check build run compatibilityCorpus resolvedLibraries --refresh-dependencies
python scripts/verify_compatibility_corpus.py --consumer-build-directory verification/consumer/build/compatibility-check --output build/verification/compatibility-check
```

生成器默认使用 `build/verification/compatibility-fixtures`，要求新目录；重复验收先保存旧证据，并在干净的生成目录执行。消费者输出目录也应使用本轮专用路径。Linux／macOS 使用 `./gradlew`。结果和原始日志保存在忽略的 `build/`，不随源码提交。

## API 与最终制品

候选 `0.1.0-rc.1` 的 16 库／670 公开类型基线由提交 `b9c8f77b0872566614984a76bdcd1edaf2f39db9` 的独立源码 `assemble` 生成，保存在 `verification/api/baseline-0.1.0-rc.1.json`。历史 preview 基线保留。根 `apiCheck` 和 `check` 使用新候选基线，本批比较为 0 破坏、0 增加、0 第三方类型泄漏；声明相同不替代行为验收。候选约束见[兼容规则](../api-compatibility.md)。

完整 `build sbom publishLocalBundle` 通过：1,787 项 Java 测试，0 失败、0 错误、0 跳过。启用签名的 `publishSignedBundle` 也先执行 `check`、本地发布和 SBOM，签名输入清单绑定候选基线及 API 检查脚本。

依赖门禁四项 Python 回归、API 门禁三项真实 Java 编译回归均通过；后者继续验证不兼容声明和第三方类型泄漏拒绝，以及保留旧入口的 record 扩展可接受。

临时版本 `0.0.0-verification-gap14` 的最终本地签名演练完成：17 个模块、82 项真实签名，独立 GnuPG 2.4.9 验签及篡改拒绝通过；BOM 对齐 16 库，SBOM 与实际签名 JAR 摘要一致；复制的隔离坐标消费者实际编译运行，16 个解析 JAR 的 SHA-256 与本批仓库逐一相同，解析图另外包含同版 BOM。临时私钥目录已清理，没有上传制品，也没有使用正式签名身份。

| 最终签名批次证据 | SHA-256 |
| --- | --- |
| 构建输入清单 | `d218b30c8e108b39a21391d7a02100d15c6e63d0d025dd39488a3f0a0f6ad187` |
| 独立 SBOM | `432c9168a3c28ad6d234aa5becf9c44a70ae86e4a2ce07a87fd3f412541f5c21` |
| 本地 Maven 目录 ZIP | `9221827baa4d4f069f214b5cc4dc4d43c08020ffc0aa3918eb3f1466bb625a0f` |

该批记录在 `build/verification/gap14-current-signed-final/distribution-report.json`。构建输入清单描述工作树的确切文件，HEAD 仅是基准提交，不能将未提交修改当作该提交原始字节。后续代码或许可字节变化须重新验证，不沿用签名结论。

## 升级后的专项回归与边界

| 检查 | 本地实际结果 | 边界 |
| --- | --- | --- |
| DOCX 原生结构 | 两份输出的 2 绘图、11 现代控件、3 旧式表单、4 层表格、2 节；修订决策前 6 类、之后 0，独立验证通过 | 不扩大原生 OOXML 子集 |
| HTML/CSS | 14 页、8 几何场景、6 损失、8 拒绝、2 原子输出和 4 几何负控制通过；源几何与 Chromium headless 对照，独立渲染及全部页面复查通过 | 浏览器只作验收，不进入运行路径；不代表完整浏览器 CSS |
| 已有 Word 保存包 | 新坐标 JAR 成功读取 GAP-12 已实际保存的文件 | 本轮没有新增 Word 交互、保存或人工阅读器操作 |
| 无打印依赖 | 16 项目 JAR、31 实际运行依赖摘要、0 打印引用、3 负控制；三个消费者在拒绝加载打印类时生成／转换通过 | 不依赖打印服务、Office 或浏览器，有限验收不扩展为全部环境保证 |
| CI 配置 | 三平台增加固定语料／门禁自身回归；Linux 增加实际 SBOM 公告查询、语料结构与双渲染 | 本批新增步骤尚未在远端运行；提交 `cc132e7` 的历史成功不代表这些修改已通过远端 CI |

GAP-14.2 的更广平台／桌面阅读器操作，以及 GAP-14.3 的新 CI 结果、正式身份、命名空间权限、上传和真实远端消费仍未验收。候选基线、本地签名、依赖无公告命中均不关闭正式发行，也不表示生产 SLA。
