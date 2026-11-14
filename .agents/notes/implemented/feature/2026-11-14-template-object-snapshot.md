# 工程笔记：模板对象在绑定边界快照

状态：已实现

## 先说结论

DTO／record 与 Map 使用同一条有界模板绑定路径。反射只读取 record 组件或公开 Bean getter，属性计划仅在本次绑定内保存，不保留跨请求的对象或 Class 引用。

## 问题

让业务层先把 DTO 转成 Map 会重复实现嵌套转换；另建序列化入口又容易绕开现有类型、文字预算和取消规则。读取 getter 还会执行调用方代码，必须明确与纯表达式路径的区别。

## 决策

- [DocumentTemplate](../../../../export4j-model/src/main/java/com/mirakyux/export4j/model/DocumentTemplate.java)保留 Map 方法描述符，Object 重载复用既有快照和展开。非法根类型在遍历前拒绝。
- [属性读取器](../../../../export4j-model/src/main/java/com/mirakyux/export4j/model/TemplateProperties.java)只读取公开访问器，不读取字段；属性排序确定，布尔 is 优先。对非公开声明类型仅尝试放行公开访问器，尊重模块封装。
- 全部可读属性提前快照，未引用属性同样校验。对象、Map、List 共用预算；递归栈检测循环，共享引用每次出现分别计费。下一值已超预算时不执行对应 getter。
- getter 的普通异常不保留原消息与 cause；执行取消和 Error 原样传播。getter 是可信同步应用代码，合作式检查不能中断卡住的 getter。

## 考虑过的备选方案

- JSON 序列化往返：增加序列化依赖，可能改变数值／日期／空值语义，且无法沿用读取前预算检查。
- 全局 Class 计划缓存：减少重复反射，但会扩大类加载器和模块引用生命周期；当前采用绑定内缓存。
- 私有字段遍历：突破对象公开接口及模块封装，不采用。

## 后果

调用方减少数据转换，Map 二进制入口保留。代价是每次绑定读取所有公开属性，getter 必须快速、稳定，并且所有值都满足[有限类型契约](../../../../docs/xml-template-api.md#java-对象绑定)。不自动转换 null、日期、枚举、数组或 Set；快照完成前不得并发修改输入。

## 验证

[模型回归](../../../../export4j-model/src/test/java/com/mirakyux/export4j/model/DocumentTemplateObjectBindingTest.java)覆盖属性读取、混合结构、预算、循环、快照和异常；[统一入口回归](../../../../export4j-starter/src/test/java/com/mirakyux/export4j/Export4jObjectBindingTest.java)覆盖逐目标快照及原子失败。实际制品与独立输出结果登记在[统一入口验收](../../../../docs/verification/easy-export.md)。

## 适用范围

适用于返回 DocumentTemplate 的 Java 模板入口及 starter 正文 XML；不扩展模板表达式为方法调用，不使静态文档站的 JSON 编辑器接收 Java 对象，也不改变后端布局或字体策略。
