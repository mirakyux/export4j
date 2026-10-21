# Export4j Agent 工作规范

本文件适用于整个仓库。用户当前任务指令优先；修改子目录前读取从仓库根目录到该目录的补充 `AGENTS.md`，子目录规则在其范围内细化本规则。

## 项目基线

- Export4j 是 Java 文档排版与生成引擎，核心是语义模型、布局算法与可替换后端。
- 使用 Java 21 LTS、Gradle Wrapper 与 Kotlin DSL；自研代码采用 Apache-2.0。工具及依赖版本以版本目录、Wrapper 配置和锁文件为准。
- Java 根包和 Gradle group 为 `com.mirakyux.export4j`，模块及制品使用 `export4j-*`。
- 当前能力以[支持矩阵](docs/capabilities-api.md)、[能力缺口表](docs/capability-gaps.md)、对应 API、源码和验收为准。规划、局部样例与完整支持必须区分。
- 阅读入口：[README](README.md)、[立项文档](docs/project-proposal.md)、[架构](docs/architecture.md)、[开发规约](docs/development.md)、[路线图](docs/roadmap.md)。

## 任务导航

按任务读取对应技能；技能引用的架构、API 和引擎细则具有本规则的约束效力，只需载入与当前任务相关的部分。

| 任务 | 必读入口 |
| --- | --- |
| 开发、修复、重构、构建和依赖调整 | [develop-export4j](.agents/skills/develop-export4j/SKILL.md) |
| 新增／修改测试、排查不稳定测试 | [write-stable-tests](.agents/skills/write-stable-tests/SKILL.md) |
| 布局、绘制或输出变化的验收 | [verify-export4j-output](.agents/skills/verify-export4j-output/SKILL.md) |
| 用户要求评审／review／指定变更自查 | [export4j-code-review](.agents/skills/export4j-code-review/SKILL.md) |
| 版本、Maven 制品、BOM／SBOM 与发布 | [release-export4j](.agents/skills/release-export4j/SKILL.md) |
| README、指南、ADR、验收和支持状态文档 | [write-export4j-docs](.agents/skills/write-export4j-docs/SKILL.md) |
| 检索、记录或调整长期工程取舍 | [agent-notes](.agents/skills/agent-notes/SKILL.md) |

结构与维护入口见 [.agents/README.md](.agents/README.md)。不为普通小修创建笔记；重大架构决定继续使用 [docs/adr](docs/adr)。

## 共同工作原则

1. 先定位受影响模块、现有行为、契约和验收条件，检查工作区并保留用户及其他协作者的无关改动。
2. 明确任务直接完成必要的可逆工作。需求确实缺失时再澄清，同时继续不依赖答案的工作。
3. 按真实需求创建模块，避免空接口、占位实现、无关重构、全仓格式化和顺手升级依赖。
4. 延续能力补齐任务先读能力缺口表，按优先级选择可独立验收的项，完成后登记实际边界及证据。
5. 发现设计与实现冲突时说明证据并同步文档；核心边界变化记录 ADR。不复制竞争项目的实现、注释或测试。
6. 共享文件只写项目契约、规约、使用方法、工程决策和可复现证据。使用仓库相对路径或明确占位符，不写个人目录、机器标识、凭据、会话授权、代理分工和原始日志。
7. `.agents` 与根 `AGENTS.md` 属于可共享的项目内容。私有配置、缓存、临时产物及原始日志放入忽略目录，新增文件核对 Git 实际收录范围。
8. 提交、推送、发布和对外操作按任务授权执行，不因本规则或技能自动获得授权，也不重复请求已经明确给予的授权。

## 核心边界

- 保持 `Document Model → Layout Engine → Layout IR → Display List → Backend` 单向职责。模型不含后端句柄，后端不再次换行／分页；DOCX 直接映射语义模型。
- 公共 API、异常与 SPI 不泄漏第三方实现类型。核心不反向依赖后端、适配器、组件实现、示例或测试工具。
- 显式字体与资源、确定性测量／绘制、有限预算、同步取消、正确资源所有权和原子输出是共同契约。不得通过隐式系统字体、吞异常、静默丢弃或无上限重试扩展能力。
- 几何单位为 pt，左上角原点；文字来源使用原文 UTF-16。原始锚点、片段顺序、字段、图文外链及源／呈现预算必须跨层保留。
- 详细约束见[架构](.agents/skills/develop-export4j/references/architecture.md)、[Java API](.agents/skills/develop-export4j/references/java-api.md)和[引擎不变量](.agents/skills/develop-export4j/references/engine-invariants.md)。

## 验证与交付

- 按[验证矩阵](.agents/skills/develop-export4j/references/verification.md)执行最小有效验证；公共契约及跨模块行为扩大覆盖，输出变化结合结构与独立渲染。
- 纯文档修改检查链接、结构和事实，无须添加 Java 测试。修改项目规约后执行 `python .agents/scripts/verify_rules.py`。
- 不自动刷新视觉基线，不以相同后端读回或有限样例代替完整证据。不宣称未执行、缺工具或被阻塞的检查通过。
- 交付说明具体变化、验证结果及限制，区分构建、输出验收、提交、推送和正式发布。

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
