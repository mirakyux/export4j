---
name: develop-export4j
description: '开发、修复或重构 Export4j 的 Java 模型、字体、布局、组件、后端、适配器及 Gradle 模块时使用；规定模块边界、公共契约、引擎不变量和变更验证。'
---

# 开发 Export4j

## 开始工作

1. 阅读根 [AGENTS.md](../../../AGENTS.md) 与受影响目录的补充规则，检查工作区，保留无关改动。
2. 明确实际触发条件、当前行为、目标行为和可验收结果。先读相关实现、调用方、测试及权威文档，再确定修改位置。
3. 继续能力补齐时，先读[能力缺口表](../../../docs/capability-gaps.md)，选择可独立验收的未完成项；规划和局部样例不能算完整实现。
4. 涉及代码定位时先检查 `.codegraph/` 是否存在；存在则按根规则使用 CodeGraph，不自行建立索引。

## 按范围读取

| 变更范围 | 必读细则 |
| --- | --- |
| 所有生产代码 | [架构约束](references/architecture.md)、[Java 与 API](references/java-api.md) |
| 字体、文字、布局、模型、组件及输出语义 | [引擎不变量](references/engine-invariants.md) |
| 能力扩展或支持状态判断 | [能力边界及证据](references/capability-boundaries.md)与对应 API／ADR |
| 构建、依赖或行为变更 | [验证矩阵](references/verification.md)的相关条目 |

## 模块职责

| 模块 | 职责 |
| --- | --- |
| `export4j-model` | 后端中立的语义模型和自有值类型 |
| `export4j-graphics-api`／`export4j-graphics-core` | 绘制契约、Display List 及通用绘制实现 |
| `export4j-font-core`／`export4j-text-core` | 显式字体资源、测量、塑形及文字来源 |
| `export4j-layout-model`／`export4j-layout-core` | Layout IR、测量、区域分配及分页 |
| `export4j-components` | 编写入口、组合组件及 `DocumentEngine` |
| `export4j-backend-pdf`／`export4j-backend-svg`／`export4j-backend-raster` | 消费最终绘制结果，不重新布局 |
| `export4j-adapter-docx` | 语义 DOCX 导入／导出，使用 JDK ZIP 与安全 XML |
| `export4j-adapter-template` | 离线 XML／Markdown 到既有模板模型 |
| `export4j-adapter-web` | 离线 HTML/CSS／Markdown 子集导入、语义导出、逐功能损失及有界原子输出 |
| `export4j-adapter-pdf` | 有界 PDF 页／被动对象编辑、组合、渲染提取、优化、脱敏及语义恢复；内部封装 PDFBox |
| `export4j-bom`／`examples` | 制品版本对齐／可运行示例，核心不反向依赖它们 |

具体依赖以各模块 `build.gradle.kts` 为准；该表不允许跨越既有依赖边界。

## 实现与交付

- 沿 `Document Model → Layout Engine → Layout IR → Display List → Backend` 定位职责；DOCX 与 HTML／Markdown 适配器直接映射语义模型。
- 只建立真实需求所需的模块和实现。保持模型不可变、配置边界快照、显式错误、有限预算与清晰资源所有权。
- 对公共 API 同时检查调用方、兼容性、诊断、取消、关闭顺序和失败时旧目标是否保留。不得以吞异常、静默丢弃或隐式字体回退完成扩展。
- 修复根因，避免无关重构、全仓格式化和顺手升级依赖。新增依赖及资源须更新许可记录；不复制竞争项目实现、注释或测试。
- 改动核心边界时补充 [ADR](../../../docs/adr)，更新受影响文档与示例。其他长期工程取舍按 [agent-notes](../agent-notes/SKILL.md)维护。
- 按验证矩阵运行最小有效验证，公共契约或跨模块变化扩大覆盖。测试设计使用 [write-stable-tests](../write-stable-tests/SKILL.md)，输出变化使用 [verify-export4j-output](../verify-export4j-output/SKILL.md)。
- 最终说明具体行为变化、验证及限制。遵循任务对提交、推送和发布的授权范围。
