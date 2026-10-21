# 工程决策笔记

本目录保存未被 API、ADR 和验收文档覆盖、值得长期保留的工程取舍。规则见 [AGENTS.md](AGENTS.md)，操作见 [agent-notes](../skills/agent-notes/SKILL.md)。

## 生命周期

| 目录 | 含义 |
| --- | --- |
| [implemented](implemented/AGENTS.md) | 已采用并具备对应依据 |
| [proposed](proposed/AGENTS.md) | 待决定／待验证，不表示能力已存在 |
| [rejected](rejected/AGENTS.md) | 在指定前提下否决，保留原因与重评条件 |
| [archived](archived/AGENTS.md) | 已过时或被替代，仅供追溯 |

类别为 `architecture`、`bug-fix`、`feature`、`process`、`simplification`、`testing`，按需创建。重大架构决定直接写入 [docs/adr](../../docs/adr)；`architecture` 类别仅补充未被 ADR 覆盖的工程背景并引用 ADR。

## 当前入口

- [共享项目规约的组织与维护](implemented/process/2026-10-21-shared-project-rules.md)

本页可列重要入口，不要求维护逐条笔记索引。检索按主题与状态进行；[manifest.json](manifest.json) 只定义目录契约。
