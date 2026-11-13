# 文档站样张

本目录保存首页公开样张。PDF 来自 Export4j 既有示例输出，WebP 为 PDF 首页的 Poppler 渲染，保留页面内容与比例，不替换文档文字或绘制内容。业务信息和学术稿件均为虚构样例。

中文首页优先展示中文项目周报及业务样张；英文首页优先展示英文学术论文。两种语言的样张均可打开，跨语言样张在标题中标明正文语言。切换页面语言不会翻译或改写 PDF 内容。

| 文件 | 正文语言 | 生成入口与来源 | 范围 |
| --- | --- | --- | --- |
| `report.pdf`／`report.webp` | 中文 | `:examples:commonDocuments`，`weekly-report.pdf` | 项目周报、中文字体、表格和分页 |
| `invoice.pdf`／`invoice.webp` | 中文 | `:examples:commonDocuments`，`quotation.pdf` | 服务报价单；文件名用于固定资源地址，不表示税务发票功能 |
| `resume.pdf`／`resume.webp` | 中文 | `:examples:commonDocuments --args="resume"`，`templates/common/resume.xml` 与同名 JSON | 两页工程师履历、图片与分栏；身份、联系方式和经历均为虚构，照片人物并非履历中的虚构人物 |
| `service-contract.pdf`／`service-contract.webp` | 中文 | `:examples:commonDocuments --args="service-contract"`，`templates/common/service-contract.xml` 与同名 JSON | 七页项目服务合同、长表、页眉页脚与签字区；内容仅作排版演示 |
| `user-manual.pdf`／`user-manual.webp` | 中文 | `:examples:commonDocuments --args="user-manual"`，`templates/common/user-manual.xml` 与同名 JSON | 八页虚构产品使用说明书、富文本、长表和续页；并非 Export4j 使用手册 |
| `meeting-minutes.pdf`／`meeting-minutes.webp` | 中文 | `:examples:commonDocuments --args="meeting-minutes"`，`templates/common/meeting-minutes.xml` 与同名 JSON | 三页会议纪要、讨论、决策与行动清单 |
| `academic.pdf`／`academic.webp` | 英文 | `:examples:manuWeave --args="acm-sigconf"` | 双栏学术排版样例，不承诺投稿合规 |

PDF 保留完整样例，预览图仅展示首页。重新生成时从仓库根目录运行对应 Gradle 任务（Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`），再复制 `examples/build/common-documents/` 或 `examples/build/manuweave/` 下对应 PDF。`templates/common/` 指 `examples/src/main/resources/templates/common/`。用 `pdftoppm -f 1 -singlefile -scale-to-x 800 -scale-to-y -1 -png` 渲染首页，转换为质量 90、宽 800 px 的 WebP。发布前检查实际图片及 PDF 内容，并同步本说明。

项目自编样例内容与合同中的虚构树形标志采用 Apache-2.0。履历照片由 Joon Tae Kim 拍摄，适用 Pexels License，来源、裁剪说明与使用边界见[照片许可](resume-portrait-license.txt)；不表示照片人物对 Export4j 或虚构雇主的认可。字体来源和许可见[字体记录](https://github.com/mirakyux/export4j/blob/main/assets/fonts/README.md)；学术配置事实来自 MIT 许可的 ManuWeave，来源、替代字体和格式限制见[学术示例说明](https://github.com/mirakyux/export4j/blob/main/examples/src/main/resources/templates/manuweave/README.md)。这些固定样张复用已有许可资源，不增加远程字体或图片请求。
