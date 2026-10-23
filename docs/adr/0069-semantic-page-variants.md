# ADR-0069：语义首页与奇偶页区

日期：2026-10-22。状态：已接受。扩展 [ADR-0049](0049-semantic-page-regions-and-sections.md) 的默认重复页区。

## 背景

组件已有首页／奇偶模板，但语义 PageRegions 只有默认对，XML 与 DOCX 无法保留同样的选择规则。按物理页索引判断奇偶会在页码重置、目录偏移及偶数首页时选错；把空首页当成缺省又会错误显示默认内容。

## 决定

PageRegions 增加不可变 FIRST／EVEN 完整页区对。FIRST 存在时优先，否则按最终显示页码选择 EVEN 或默认。缺省和显式空声明分开保存。各节分页前按全部变体的最大页眉／页脚 extent 预留，不逐页重排。所有源均参与绑定、ID 与预算，未选中源不能绕过检查。

模型到组件使用 SectionTemplate 的独立 firstEven。保留旧三参数构造和 first Builder 的两种首页语义。XML 使用静态 variant 属性，按 kind + variant 查重，节从根逐声明继承。源共享、编辑位置和转换覆盖全部变体。

DOCX 映射标准 default／first／even 引用、titlePg 和 evenAndOddHeaders。偶数页开关是包级开关，故无 EVEN 的节也写显式默认引用；可忽略 evenDefault 标记仅恢复模型声明意图，读取必须验证源确实等于默认对。缺少固定几何时仍要求调用方配置；损失策略不放宽关系、几何或预算校验。

## 备选与影响

不把 FIRST 自动复制到默认对，不按数值空白猜继承，也不按物理页奇偶选择。仅增加 XML 特例会让模型编辑和 DOCX 转换继续丢失，故将声明放在模型层。HTML／Markdown 的流输出明确报告选择规则损失，不能冒充分页页区。

旧短构造器保留源码兼容，record 分量变化要求重编译。固定区仍不支持任意浮动布局、目标引用或一般 Word 无损互转；本项没有增加 XML 背景／前景。

## 验证

覆盖偶数首页、FIRST 优先、显式空对、最大预留、共享源和全分支绑定；标准／去私有元数据 DOCX、开关和关系失败；Web 损失及原子失败。独立 OOXML、最终 PDF／SVG、全部新页双渲染及隔离调用方见[阶段六十一](../verification/phase-61-page-variants.md)。
