# ManuWeave 学术模板示例

本目录将 [ManuWeave](https://github.com/jiassia/manuweave/) README 中的全部 286 个 Profile ID 接入 Export4j 示例。目录与排版事实固定于提交 [`e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b`](https://github.com/jiassia/manuweave/tree/e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b)，对应 277 个期刊／出版社 profile 和 9 个通用 preset。后续上游增减模板不会自动改变本目录。

## 运行

从仓库根目录运行，需要 JDK 21；Linux/macOS 将 `.\gradlew.bat` 换成 `bash ./gradlew`。

```powershell
# 全部模板：
.\gradlew.bat :examples:manuWeave
# 查看全部 ID、名称与分组：
.\gradlew.bat :examples:manuWeave --args="--list"
# 单独运行或组合选择：
.\gradlew.bat :examples:manuWeave --args="acm-sigconf"
.\gradlew.bat :examples:manuWeave --args="cn-aroc-official-word ieee-conference en-report zh-master"
```

输出写入忽略目录 `examples/build/manuweave/`：

| 文件 | 内容 |
| --- | --- |
| `<id>.pdf` | 对应配置的学术文档排版样例 |
| `<id>-report.xml` | 源配置、实际页面／文字样式／字体，以及缺省、替代和未映射要求 |
| `<id>-source.txt` | 本次样例的原文，供独立核对文字顺序与完整性 |

运行时只读取已打包资源，不下载字体、Word 模板、LaTeX 类文件或其他远程内容。共享的 Java 渲染器与虚构稿件内容由 Export4j 编写，入口为 [ManuWeaveExample](../../../java/com/mirakyux/export4j/examples/ManuWeaveExample.java)。

## 模板目录

[index.tsv](index.tsv) 列出全部 ID；每行依次为分组、ID、配置名称、来源 URL、上游相对路径，以制表符分隔，无表头。各 ID 对应 `profiles/<id>.xml`。

| README 分组 | 数量 |
| --- | ---: |
| Journals | 16 |
| Publishers | 57 |
| ACM | 41 |
| IEEE | 52 |
| Chinese journals | 10 |
| Elsevier | 5 |
| Wiley | 16 |
| More journals | 80 |
| General templates | 9 |
| 合计 | 286 |

配置文件使用 JDK `Properties.loadFromXML` 的标准 Properties XML，供 Java 示例加载，不是 Export4j `<document>` 模板语法。页面以 `page.*` 保存，文字规则以 `role.<角色>.*` 保存；`sections.*`、`running.*`、`tables.*` 保留分区、页区及表格要求。数值沿用上游单位：页面 `*_mm` 为毫米，文字 `*_pt` 为 point；渲染器转换为引擎使用的 point。嵌套对象展开为点分键，数组保留为 JSON 字符串。

每个配置包含 `upstream.commit`、`upstream-path` 和原 JSON 的 `upstream.sha256`；[provenance.xml](provenance.xml) 记录总数、分组及上游 README 摘要。要修改示例样式，可修改相应配置后重新执行任务；修改共享稿件内容或排版映射时，调整 Java 示例并重新验证。

维护源快照时，可用 `scripts/sync_manuweave_catalog.py --source <本地上游目录> --check` 只读核对全部资源；不加 `--check` 可重新生成受管理的配置、索引、来源及许可文件，保留本说明。脚本固定校验上述提交的 README、286 个源 JSON 和许可摘要，不联网，也不执行上游 Python 代码。传入新的上游版本会被拒绝；升级时应先审核新的目录和格式事实，再明确更新快照常量。

## 实际范围

示例按配置应用纸张、边距、单栏／连续双栏、前置内容转正文的栏数，以及可表达的角色字号、对齐、缩进、行距和段间距。内容包括标题、作者、摘要、关键词、正文、图表、矢量公式和参考文献；报告及学位文档使用对应的示例结构。

模板配置覆盖全部 286 个 ID，不代表全部上游格式要求均已实现：

- 字体显式选用 Liberation Serif 或 Liberation Sans 的正常／粗体／斜体／粗斜体，以及霞鹜文楷 TC 正常体；不合成粗斜体。宋体、Times New Roman、Linux Libertine 等源字体不会自动下载，替代情况逐角色记录。可使用下述字体映射提供已授权的真实字体。
- 固定基线行距与 Word／TeX 的自动、最小行距语义不同；上游缺省或无法直接表达的数值由示例显式选择并报告。`cn-cims-initial-word` 的上游文字规则为空，其样例使用明确记录的默认样式。
- 标题与后续内容、表题与表格、图形与图题、参考文献标题与三条示例文献整组保留；普通段落启用 2／2 最低行数，连续栏有界平衡。整组容不下完整页／栏时明确失败，不缩小内容。
- 明确声明行号的两项审稿模板输出连续正文行号；117 项源配置声明的镜像边距按实际显示页码交换左右边距。页区按源字体、距离、首页及奇偶设置排版，含最终 PAGE／PAGES；源资料未提供真实刊物页眉文字时使用虚构标题和作者，保留为示例内容。
- 参考文献根据源配置的数字制／作者年份及明确格式事实生成三条虚构文献；报告给出实际模式、来源及局限。没有执行完整 CSL、BibTeX／Biber 或出版社类文件。
- 示例公式使用受限 TeX 排成矢量路径并保留原文，不是公式文本或图片。另有 `:examples:manuWeaveMath` 的 8 类公式与原生可编辑 Word 专项，详见[数学说明](../../../../../../docs/docx-math-api.md)。
- 这 286 个模板仍输出 PDF 格式示例；原生 Word 公式专项不代表全部模板的 Word 输出或 LaTeX 类文件兼容，也不承诺期刊、出版社或院校投稿合规。ManuWeave README 的独立中文公文能力不计入这 286 个内置模板。

具体样例以 `<id>-report.xml` 的实际参数和差异条目为准。配置中保留的上游 `template.engine_support.*` 是上游自己的声明，不能解释为 Export4j 的支持矩阵。

## 显式字体映射

```powershell
.\gradlew.bat :examples:manuWeave --args="--font-map build/academic-fonts.xml acm-sigconf"
```

映射文件为标准 Properties XML，键是 `family.<源字体族>.<regular|bold|italic|boldItalic>`，值为相对映射文件或绝对的 TrueType 路径。例如：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
  <entry key="family.Times New Roman.regular">fonts/times-regular.ttf</entry>
  <entry key="family.Times New Roman.bold">fonts/times-bold.ttf</entry>
  <entry key="family.Times New Roman.italic">fonts/times-italic.ttf</entry>
  <entry key="family.Times New Roman.boldItalic">fonts/times-bold-italic.ttf</entry>
</properties>
```

文件和字体由调用方准备并确认许可；示例不复制到输出目录或分发包。已声明的字体族必须提供实际请求的全部变体，文件物理粗斜体标记必须对应，缺失时失败。没有声明的字体族继续使用显式内置替代并报告；中文源字体可用同样的键映射。映射最多 1 MiB、128 项，外部字体累计最多 128 MiB，单字体还受字体加载器预算限制。独立全量模板验证默认核对内置字体配置，外部映射需要另行验收。

## 验证

使用项目隔离 Python 环境及 `scripts/requirements-verification.txt` 中的固定依赖，并准备 Poppler；命令从仓库根目录运行：

```powershell
python scripts/verify_manuweave.py
# 使用四个独立进程检查全部模板：
python scripts/verify_manuweave.py --jobs 4
# 只检查指定模板：
python scripts/verify_manuweave.py --samples acm-sigconf ieee-conference zh-master
```

验证器核对目录覆盖、源配置与报告、PDF 几何和实际文字样式，并以 Poppler 和 PDFium 渲染所有选中页面。渲染、联系表和机器报告写入 `build/verification/manuweave/`；独立结构与渲染检查不等同于投稿认证或 Word 阅读器验收。

本批覆盖范围、独立工具版本及实测结果见[验收记录](../../../../../../docs/verification/manuweave-templates.md)。

## 来源与许可

模板目录与格式事实派生自 ManuWeave，版权属于 ManuWeave contributors，采用 [MIT 许可](MIT.txt)，许可原文随资源保留。未引入上游执行代码、注释、测试、官方稿件、字体或品牌素材。Java 渲染器、合成稿件和图形适用 Export4j 的 Apache-2.0 许可；已有字体保留各自原始许可，见仓库 `assets/fonts/README.md`。
