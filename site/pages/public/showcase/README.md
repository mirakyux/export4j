# 文档站样张

本目录保存首页公开样张。PDF 来自 Export4j 既有示例输出，WebP 为 PDF 首页的 Poppler 渲染，保留页面内容与比例，不替换文档文字或绘制内容。业务信息和学术稿件均为虚构样例。

| 文件 | 生成入口与来源 | 范围 |
| --- | --- | --- |
| `report.pdf`／`report.webp` | `:examples:commonDocuments`，`weekly-report.pdf` | 项目周报、中文字体、表格和分页 |
| `invoice.pdf`／`invoice.webp` | `:examples:commonDocuments`，`quotation.pdf` | 服务报价单；文件名用于固定资源地址，不表示税务发票功能 |
| `academic.pdf`／`academic.webp` | `:examples:manuWeave --args="acm-sigconf"` | 双栏学术排版样例，不承诺投稿合规 |

PDF 保留完整样例，预览图仅展示首页。重新生成时从仓库根目录运行对应 Gradle 任务，再复制对应 PDF。用 `pdftoppm -f 1 -singlefile -scale-to 1300 -png` 渲染首页，转换为质量 90 的 WebP。发布前检查实际图片及 PDF 内容，并同步本说明。

项目自编样例内容采用 Apache-2.0。字体来源和许可见[字体记录](https://github.com/mirakyux/export4j/blob/main/assets/fonts/README.md)；学术配置事实来自 MIT 许可的 ManuWeave，来源、替代字体和格式限制见[学术示例说明](https://github.com/mirakyux/export4j/blob/main/examples/src/main/resources/templates/manuweave/README.md)。这些固定样张复用已有许可资源，不增加远程字体或图片请求。
