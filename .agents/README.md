# Export4j 项目规约

本目录保存可随项目共享的 Agent 技能、工程约束和决策笔记。入口为根目录的 [AGENTS.md](../AGENTS.md)。

## 技能导航

| 工作 | 入口 | 主要内容 |
| --- | --- | --- |
| 开发与缺陷修复 | [develop-export4j](skills/develop-export4j/SKILL.md) | 模块职责、Java API、引擎不变量、能力边界 |
| 测试设计与稳定性 | [write-stable-tests](skills/write-stable-tests/SKILL.md) | 行为断言、确定性、超时与资源清理 |
| PDF／DOCX／SVG／PNG 验收 | [verify-export4j-output](skills/verify-export4j-output/SKILL.md) | 结构、独立渲染、阅读器与容量验证 |
| 代码评审 | [export4j-code-review](skills/export4j-code-review/SKILL.md) | 范围、触发条件、影响与证据 |
| 制品与发布 | [release-export4j](skills/release-export4j/SKILL.md) | BOM、SBOM、隔离调用方及发布边界 |
| 项目文档 | [write-export4j-docs](skills/write-export4j-docs/SKILL.md) | 公共契约、支持状态与可复现证据 |
| 工程决策笔记 | [agent-notes](skills/agent-notes/SKILL.md) | 决策、备选方案、后果与生命周期 |

按任务读取入口及相关引用，不需要每次载入全部技能和笔记。

## 信息归属

| 内容 | 位置 |
| --- | --- |
| 通用开发、测试和交付规则 | 本目录的 `skills/` |
| 架构边界及重大取舍 | [docs/adr](../docs/adr) |
| 公共 API、使用方法、支持状态 | [docs](../docs) |
| 可复现验收及结论 | [docs/verification](../docs/verification) |
| 其他值得长期保留的工程取舍 | [notes](notes/README.md) |

共享文件只写项目相关内容，路径相对于仓库。个人目录、机器标识、凭据、会话记录、临时执行计划和原始日志不进入此目录。生成报告使用被 Git 忽略的 `build/`。`.agents` 与根 `AGENTS.md` 应保持可被 Git 收录。

## 维护与检查

修改契约时更新其权威文档和相关技能引用。技能说明写清适用场景，较长的细则放入 `references/`；不复制其他项目的技术栈或建立空功能模块。

从仓库根目录运行：

```shell
python .agents/scripts/verify_rules.py
```

检查器使用 Python 标准库，验证目录结构、技能元数据、相对文件链接、笔记状态及常见机器路径／凭据模式。检查范围为根 `AGENTS.md` 和本目录；Git 收录范围与其他待提交文件仍需另外核对，模式检查不能证明不存在所有敏感内容。
