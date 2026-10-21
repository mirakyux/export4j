# ADR-0040：平面离线 XML 片段复用

状态：已实施并通过[阶段三十三本地验收](../verification/phase-33-offline-xml-includes.md)。

日期：2026-10-16。

## 背景

现有 `XmlTemplate` 已将条件、循环、动态表格、块格、静态字段和外链编译为 `DocumentTemplate`，但重复报告区块仍需在 Java 中拼接源字符串或复制 XML。加入片段复用应提高结构可读性，并保留现有绑定、来源和预算契约。资源访问仍由调用方决定；本项不引入文件加载、远程抓取或第二套布局算法。

## 决策

`XmlTemplate.Builder.fragment(String name, String source)` 登记不可变源字符串，`build()` 对登记表取快照。名称大小写敏感，匹配 `[A-Za-z_][A-Za-z_0-9-]{0,63}`；最多 128 项，重复、非法名称和超额登记均抛 `IllegalArgumentException`。空值仍在入口拒绝。登记时不解析源，全部登记源仍受编译时总输入额度约束。

主模板使用空元素 `<include ref="summary" prefix="left"/>`。两个属性必填且遵守相同名称规则；插值、路径、URL 或多余属性均不接受。`ref` 只查当前编译器的显式登记表。源必须使用 `<fragment version="1">...</fragment>` 根，根只允许 `version`。空片段合法。

首批为平面复用：include 只接受于主模板 document、then、else、each 的合法块列表，不能置于文字、链接、表、格或未知容器。片段可包含现有合法块、条件、循环及表格，但其任意位置出现 include 都拒绝，包括未选择的分支和运行期零次循环。全部主模板分支及循环原型在编译时视为可达；不根据绑定数据选择是否加载片段。

## 展开与静态名称空间

编译先解析并展开有序内部树，再统一预留全文档显式 ID，最后生成自动 ID 和现有绑定节点。没有新模型类型；片段边界不进入布局、IR 或后端。源节点保留原片段名和一基行列，不重序列化 XML。

主模板中的 prefix 按静态出现次数全文档唯一，即使分处 then／else，或片段为空也不能重复。一个 each 原型中的 include 只声明一次 prefix；绑定产生的运行期副本仍由原有 `DocumentTemplate` 增加 `@i` 后缀。

| 名称或属性 | 静态展开规则 |
| --- | --- |
| 片段显式块 `id` | 验证原值后改为 `prefix-original`；包含原子内表及块格后代 |
| `numbered` 的 `list-id` | 验证原值后改为 `prefix-original`；同片段实例内保持列表共享 |
| 自动块 ID | 全树显式预留完成后生成 `prefix-xml-N`，避让主源与所有片段声明；主源仍用 `xml-N` |
| page／number-reference 的 target | 仅原值匹配该片段全部分支中的显式块 ID 时加 prefix；其他静态外部目标保持 |
| 数据路径、变量、样式、字体角色、图片 ref、URI 和文字 | 原样保留，继续由已有语法与绑定边界处理 |

改写后的 ID 和 list-id 仍必须满足既有 128 字符上限，超长明确失败。自动 ID 不属于可引用的局部显式名称集合；不推测哪一个自动编号是调用方的目标。全文档重复显式 ID 继续拒绝，包括主源主动声明了相同的已限定名称。

连字符拼接可能使不同来源得到相同 list-id，例如 prefix `a`／原值 `b-c` 与 prefix `a-b`／原值 `c` 都得到 `a-b-c`。最终树预扫同时记录列表的所属 prefix，主源所属值为空；同最终 list-id 只允许同 owner 共享，不同 include 或 main／include 的派生碰撞以 INVALID_VALUE 拒绝，定位第二个编号叶。主源多个编号共享 list-id、同片段实例内连续编号均保持；不增加默认／自动 list-id，既有 XML numbered 仍要求显式 list-id。

运行期 each 的词法引用重定位保持：循环内本片段引用可指向当前副本，外部目标保持。`list-id` 不添加运行期后缀，保持现有循环编号连续契约；不同静态 include 实例因 prefix 不同而分离列表。片段外引用循环内部目标仍不自动选择某次迭代。

## 预算、执行与缓存

保留 `Limits` 的四个公共字段，不增加无界缓存或可选关闭限制。每次 compile 独立持有解析状态、已解析引用源、已用 prefix 及展开计数；编译器自身不保存跨调用可变缓存，仍可共享。

| 限制 | 计数范围 |
| --- | --- |
| maxInputCharacters | 主源加全部登记源的 UTF-16 长度，使用 long 累计；未引用源同样计入 |
| 解析 maxElements／maxTextCodePoints | 主源及每个被引用登记源各解析一次的累计元素／解码文字，包含根和结构空白 |
| 展开 maxElements／maxTextCodePoints | 独立使用同额度，主树及每个片段实例累计；include 元素也计工作额度，fragment 包装不进入最终树 |
| maxDepth | 最终展开树，根为 1；include 和 fragment 包装不构成最终层级，空 include 可完全移除 |
| 输入树硬深度 | 独立最多 65 层，覆盖合法最终 64 层加一个可移除 include 叶；不允许输入树无限加深 |
| 绑定额度 | 沿用 DocumentTemplate 的运行期块、行、格及文字限制，不因静态展开而放宽 |

主源非 include 元素必然保留在最终树，解析时仍按配置 maxDepth 立即拒绝，保留旧首次超深错误的位置与优先级。片段在硬上界内解析，再按实际插入深度检查配置值。include 本身只计展开工作，不占最终深度。

借用 Reader 只读取扣除全部登记源后的 UTF-16 余额加一单位；登记源已经超额时读前失败。Reader 不关闭，I/O 原样传播。解析、原型扫描、展开和编译节点均保留同步 `ExecutionContext.checkpoint()`；不产生后台线程。内部静态树有界，不意味着最终 PDF 恒内存或恶意输入沙箱。

## 诊断与兼容

沿用既有 `TemplateException.Code`、一基位置及原三参数构造路径。新增 `fragmentName(): Optional<String>`，主源错误返回空，片段叶语法错误返回已登记名称。原异常 message 保持只有 code 和行列，不加入名称、路径、源文本或数据。XML 解析器的原始异常文字仍不透出。

未知片段、重复 prefix、include 语法和由引用引起的解析／展开预算错误定位主模板 include；片段内部解析、属性、表达式及模型校验错误保留片段名与叶位置。无新错误码：未知 ref 用 UNKNOWN_RESOURCE；非法静态名称（包括插值）用 INVALID_VALUE；多余属性用 UNSUPPORTED_ATTRIBUTE；缺属性或非空 include 用 INVALID_STRUCTURE；片段嵌套 include 用 UNSUPPORTED_ELEMENT。

无 include 的现有模板保持资源范围、默认主题、ID 顺序、完整模型、绑定及深度拒绝行为。XML 原有两层块格、原子内表、字段固定宽、静态外链及主题拆行冲突继续拒绝，不能通过 include 绕过它们。没有新增依赖；实现和测试独立编写。

## 不属于本项的能力

递归片段、文件／URL 加载、相对路径、片段参数覆盖、父片段名称导出、动态 ref／prefix、XML 内主题定义、HTML／CSS、多级列表、设计器、任意 Word 无损互转仍开放。递归复用需要另行规定循环检测、层级名称空间、父子目标、源追踪与独立工作预算，不能仅删掉嵌套 include 拒绝分支。

## 验收要求

行为测试覆盖资源快照／额度、全部分支和空循环检查、两实例 ID／列表隔离、自动 ID 全树避让、局部／外部字段、each 当前副本、深层块格、静态主题与图片链接、脱敏来源、拒绝白名单、取消恢复和双重预算。独立样例核对最终 PDF 字段、链接、原文顺序、重复表头和全部新页，并验证隔离调用方。正式支持矩阵只在完整集成验收后登记。
