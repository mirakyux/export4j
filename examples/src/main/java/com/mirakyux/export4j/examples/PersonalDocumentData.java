// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.util.List;
import java.util.Map;

/** Immutable fictional data for the extensive contract, two-column resume and operations manual. */
final class PersonalDocumentData {
    private PersonalDocumentData() { }

    static Map<String, Object> data(String id) {
        return switch (id) {
            case "service-contract" -> contract();
            case "resume" -> resume();
            case "user-manual" -> manual();
            default -> throw new IllegalArgumentException("unknown personal document example: " + id);
        };
    }

    private static Map<String, Object> contract() {
        return Map.<String, Object>ofEntries(
                Map.entry("title", "业务文档平台项目服务合同"),
                Map.entry("contract", Map.<String, Object>ofEntries(
                        Map.entry("number", "DEMO-CON-2026-001"),
                        Map.entry("date", "2026-04-15"),
                        Map.entry("version", "V1.0 / 签署示例"),
                        Map.entry("term", "2026-04-20 至 2026-08-14"),
                        Map.entry("warranty", "最终验收后 90 个自然日"),
                        Map.entry("overview", "本项目为虚构企业建设统一的业务文档平台，覆盖模板规范、字段映射、批量生成、权限审计及交付培训。合同正文与附件共同定义服务边界；双方以可复现的数据、可核对的成果及书面确认记录完成阶段验收。"),
                        Map.entry("scope", "乙方根据经双方确认的需求基线实施设计和开发。交付目标包括六类业务模板、字段与资源约定、一套离线验证样例、调用接口说明和运行手册。甲方保留对业务规则及数据准确性的最终确认责任。"),
                        Map.entry("exclusions", "本次不包含硬件采购、第三方许可、生产数据库迁移及全年驻场服务；支付通道、外部身份系统和历史资料清洗由甲方另行安排。任何新增文档类型、部署环境或集成对象均须经变更流程确认，不以演示样例默认为全部实施范围。"),
                        Map.entry("acceptance", "每项成果按附件中的输入、步骤和预期结果验收。甲方在收到完整材料后的五个工作日内集中反馈，区分阻断问题、一般问题和新增需求；乙方提供问题编号、修正计划及复验版本。未回复不自动视为验收通过，计划顺延由双方书面确认。"),
                        Map.entry("price", "本合同采用分项固定总价。未税服务费 120,000.00 元，演示税率 6%，税额 7,200.00 元，含税总额 127,200.00 元。税率及实际开票要求需在真实交易中重新确认，本示例金额仅用于展示完整计算与分期付款的排版。"),
                        Map.entry("billing", "乙方按约定节点提交付款申请和阶段证明，甲方核对无误后于十个工作日内付款。双方通过独立书面渠道核验收款账户，账户变更不得仅凭即时消息办理；争议部分应明确列出，不影响双方已经确认的其他应付款。"),
                        Map.entry("subtotal", "120000.00"),
                        Map.entry("taxRate", "6%"),
                        Map.entry("tax", "7200.00"),
                        Map.entry("total", "127200.00"),
                        Map.entry("change", "需求、接口、资源或排期的调整，应使用编号变更单记录原因、受影响成果、增减工作量、风险和验收方式。项目联系人只能提出和评估变更，涉及费用或合同日期的调整须由双方授权代表批准。未经确认的工作不直接计入交付基线。"),
                        Map.entry("confidentiality", "双方仅为履行合同使用对方的非公开资料，对参加项目的人员实施必要授权。对外演示优先使用虚构数据，确需使用实际材料时由甲方明确脱敏和访问要求。项目成员不得将材料存入公开仓库、个人网盘或未经同意的外部服务。"),
                        Map.entry("ownership", "甲方结清相应阶段费用后取得定制成果在约定业务内的使用权。双方原有工具、通用组件、方法和知识仍归原权利人；需要随成果分发的第三方资源应列明来源与许可，不将通用能力或第三方权利默认为定制成果的专有转让。"),
                        Map.entry("handover", "移交时提供源码或模板、构建与运行说明、版本清单、许可清单、已知限制和恢复步骤。甲方指定接收人现场核对材料可读性及样例可执行性；乙方应协助清除临时访问授权，归还或按约定删除项目数据，并保留双方确认的交接记录。"),
                        Map.entry("continuity", "一方发现关键人员、接口或实施条件发生变化，应在两个工作日内通知对方并提出影响评估。暂停期间先保存当前版本、任务状态与访问记录，明确继续执行的前提；不得通过删除既有成果或封锁已付款材料迫使对方接受新增安排。"),
                        Map.entry("termination", "存在持续未解决的重大违约时，守约方可书面要求补救并给予合理期限。终止前核对已完成、已付款及可移交的成果，结算经确认的费用；未完成工作、未消耗预付款和双方借用资源分别登记处理，不以项目终止自动免除保密与数据处理义务。"),
                        Map.entry("disputes", "对于工作量、验收或责任分歧，双方先由项目负责人核对事实和书面记录，再由授权代表协商。争议处理期间继续履行无争议且可以独立执行的工作。正式合同应由双方结合适用法律另行确定争议解决机构与具体程序。"),
                        Map.entry("signing", "本合同及列明附件经双方授权代表签署后生效。经批准的变更单应明确替代的条款或清单；未涉及的约定继续适用。电子文件应保留双方确认的最终版本和校验信息，不将未完成的讨论稿混入签署材料。"),
                        Map.entry("disclaimer", "本文件条文、机构、人物、项目、日期及金额全部为自编虚构内容，仅演示多页合同、复杂表格与绑定能力，不作为法律意见或实际签约文本。"))),
                Map.entry("parties", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "甲方 / 委托方"),
                                Map.entry("name", "星河示例科技有限公司（虚构）"),
                                Map.entry("contact", "林予安 · contract@example.invalid"),
                                Map.entry("address", "示例城市·星河园区 A 座（虚构）"),
                                Map.entry("code", "DEMO-CUSTOMER-001"),
                                Map.entry("representative", "林予安 / 示例授权代表")),
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "乙方 / 服务方"),
                                Map.entry("name", "青木示例技术服务有限公司（虚构）"),
                                Map.entry("contact", "周知远 · studio@example.invalid"),
                                Map.entry("address", "示例城市·青木创意园 B 座（虚构）"),
                                Map.entry("code", "DEMO-SUPPLIER-001"),
                                Map.entry("representative", "周知远 / 示例授权代表")))),
                Map.entry("workPackages", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "WP-01"),
                                Map.entry("name", "需求与标准"),
                                Map.entry("included", "六类模板清单、字段字典、版式规范及资源使用约定。"),
                                Map.entry("excluded", "新增业务系统的流程再造和历史数据清洗。"),
                                Map.entry("evidence", "确认的需求基线与样例数据。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "WP-02"),
                                Map.entry("name", "模板与引擎接入"),
                                Map.entry("included", "模板编写、接口封装、诊断映射及取消与失败处理。"),
                                Map.entry("excluded", "第三方软件采购及外部服务的费用。"),
                                Map.entry("evidence", "模板源码、接口说明和可运行样例。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "WP-03"),
                                Map.entry("name", "验证与试运行"),
                                Map.entry("included", "结构检查、视觉复核、权限测试及约定容量内的试运行。"),
                                Map.entry("excluded", "未经确认的环境和无限并发容量保证。"),
                                Map.entry("evidence", "测试记录、问题清单及复验结论。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "WP-04"),
                                Map.entry("name", "培训与移交"),
                                Map.entry("included", "两场远程培训、运维手册、恢复演练和交接清单。"),
                                Map.entry("excluded", "长期驻场和合同期外的新功能开发。"),
                                Map.entry("evidence", "培训材料、操作记录和签收单。")))),
                Map.entry("milestones", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "M-01"),
                                Map.entry("name", "项目启动"),
                                Map.entry("date", "2026-04-20"),
                                Map.entry("input", "指定联系人、测试环境和材料目录。"),
                                Map.entry("output", "项目计划、沟通机制及风险登记。"),
                                Map.entry("gate", "双方确认计划与人员。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "M-02"),
                                Map.entry("name", "需求基线"),
                                Map.entry("date", "2026-05-08"),
                                Map.entry("input", "完整字段、业务规则及虚构样例。"),
                                Map.entry("output", "六类模板草图与验收矩阵。"),
                                Map.entry("gate", "需求冻结并登记遗留项。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "M-03"),
                                Map.entry("name", "试用版本"),
                                Map.entry("date", "2026-06-19"),
                                Map.entry("input", "接口联调条件和测试账号就绪。"),
                                Map.entry("output", "模板、生成接口与诊断界面。"),
                                Map.entry("gate", "核心流程演示通过。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "M-04"),
                                Map.entry("name", "试运行"),
                                Map.entry("date", "2026-07-24"),
                                Map.entry("input", "业务代表参与，试运行清单确认。"),
                                Map.entry("output", "验证结果、问题修正与运行手册。"),
                                Map.entry("gate", "阻断问题清零并完成复验。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "M-05"),
                                Map.entry("name", "最终移交"),
                                Map.entry("date", "2026-08-14"),
                                Map.entry("input", "交付材料齐备且培训已完成。"),
                                Map.entry("output", "最终版本、清单及恢复演练记录。"),
                                Map.entry("gate", "双方签署最终验收单。")))),
                Map.entry("deliverables", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-01"),
                                Map.entry("item", "合同与报价模板"),
                                Map.entry("input", "多币种、长名称和零值样例。"),
                                Map.entry("test", "绑定数据后检查合计、分页与签署区。"),
                                Map.entry("expected", "金额一致，长文本完整，无字段残留。"),
                                Map.entry("evidence", "生成文件及核对表。"),
                                Map.entry("owner", "业务负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-02"),
                                Map.entry("item", "报告与纪要模板"),
                                Map.entry("input", "多级标题、长表和行动项。"),
                                Map.entry("test", "按短、中、长三组数据分别生成。"),
                                Map.entry("expected", "表头重复正确，末条记录不丢失。"),
                                Map.entry("evidence", "三组样例与截图。"),
                                Map.entry("owner", "业务负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-03"),
                                Map.entry("item", "字段与资源包"),
                                Map.entry("input", "字段说明、图片及字体清单。"),
                                Map.entry("test", "在隔离目录加载，不访问个人资源。"),
                                Map.entry("expected", "资源来源明确，缺少资源明确失败。"),
                                Map.entry("evidence", "清单、许可及错误样例。"),
                                Map.entry("owner", "技术负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-04"),
                                Map.entry("item", "生成接口"),
                                Map.entry("input", "有效、非法及超预算请求。"),
                                Map.entry("test", "检查结果、错误码、取消与目标文件。"),
                                Map.entry("expected", "输入有界，失败不破坏既有目标。"),
                                Map.entry("evidence", "调用记录与异常清单。"),
                                Map.entry("owner", "技术负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-05"),
                                Map.entry("item", "权限与审计"),
                                Map.entry("input", "管理员、编辑、查看三类账号。"),
                                Map.entry("test", "逐角色执行查看、修改、导出和授权。"),
                                Map.entry("expected", "越权操作拒绝，必要事件可追溯。"),
                                Map.entry("evidence", "权限矩阵与审计样例。"),
                                Map.entry("owner", "安全联系人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-06"),
                                Map.entry("item", "试运行报告"),
                                Map.entry("input", "约定并发和文档规模的样例集。"),
                                Map.entry("test", "测量用时、资源占用和失败恢复。"),
                                Map.entry("expected", "满足确认的阈值，边界与不足明确。"),
                                Map.entry("evidence", "配置、采样与结果说明。"),
                                Map.entry("owner", "测试负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-07"),
                                Map.entry("item", "操作与恢复手册"),
                                Map.entry("input", "新安装环境和备份副本。"),
                                Map.entry("test", "按手册安装并执行一次恢复演练。"),
                                Map.entry("expected", "步骤可复现，备份可读且结果一致。"),
                                Map.entry("evidence", "演练记录及手册版本。"),
                                Map.entry("owner", "运维负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "D-08"),
                                Map.entry("item", "培训与交接"),
                                Map.entry("input", "最终材料、参训人员及问题清单。"),
                                Map.entry("test", "完成培训，核对材料并移除临时权限。"),
                                Map.entry("expected", "接收人可独立操作，遗留项有责任人。"),
                                Map.entry("evidence", "签到、签收及遗留清单。"),
                                Map.entry("owner", "项目负责人")))),
                Map.entry("lineItems", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "F-01"),
                                Map.entry("name", "需求评估与标准制定"),
                                Map.entry("quantity", "10"),
                                Map.entry("unit", "人日"),
                                Map.entry("unitPrice", "1500.00"),
                                Map.entry("amount", "15000.00")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "F-02"),
                                Map.entry("name", "模板设计与实现"),
                                Map.entry("quantity", "30"),
                                Map.entry("unit", "人日"),
                                Map.entry("unitPrice", "1800.00"),
                                Map.entry("amount", "54000.00")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "F-03"),
                                Map.entry("name", "接口集成与验证"),
                                Map.entry("quantity", "20"),
                                Map.entry("unit", "人日"),
                                Map.entry("unitPrice", "1600.00"),
                                Map.entry("amount", "32000.00")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "F-04"),
                                Map.entry("name", "培训及交接演练"),
                                Map.entry("quantity", "5"),
                                Map.entry("unit", "人日"),
                                Map.entry("unitPrice", "2000.00"),
                                Map.entry("amount", "10000.00")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "F-05"),
                                Map.entry("name", "约定质保支持"),
                                Map.entry("quantity", "6"),
                                Map.entry("unit", "人日"),
                                Map.entry("unitPrice", "1500.00"),
                                Map.entry("amount", "9000.00")))),
                Map.entry("payments", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("stage", "P-01 启动"),
                                Map.entry("condition", "合同生效且启动条件确认。"),
                                Map.entry("percent", "20%"),
                                Map.entry("amount", "25440.00"),
                                Map.entry("evidence", "合同及启动确认单。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("stage", "P-02 基线"),
                                Map.entry("condition", "需求基线及模板草图验收。"),
                                Map.entry("percent", "30%"),
                                Map.entry("amount", "38160.00"),
                                Map.entry("evidence", "M-02 阶段确认单。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("stage", "P-03 试运行"),
                                Map.entry("condition", "试用版本交付并完成约定复验。"),
                                Map.entry("percent", "30%"),
                                Map.entry("amount", "38160.00"),
                                Map.entry("evidence", "M-04 试运行结论。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("stage", "P-04 最终"),
                                Map.entry("condition", "最终验收、培训和资料移交完成。"),
                                Map.entry("percent", "20%"),
                                Map.entry("amount", "25440.00"),
                                Map.entry("evidence", "M-05 最终验收单。")))),
                Map.entry("execution", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("title", "5.1 甲方配合"),
                                Map.entry("text", "甲方在计划时间内提供完整且有权使用的需求、接口和资源，安排业务人员确认规则，并为测试提供隔离环境。材料缺失时双方列出具体缺项与预计补齐日期，不以不明确的等待状态代替计划调整。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("title", "5.2 乙方实施"),
                                Map.entry("text", "乙方按照确认的架构与开发规范实施，保持代码、模板和文档版本可追溯。发现能力限制、兼容损失或安全问题时及时报告，不通过隐式降级、忽略失败或依赖个人环境完成交付。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("title", "5.3 问题与沟通"),
                                Map.entry("text", "每周同步已完成工作、阻塞、下周计划及需决策事项。问题记录包括复现输入、影响、负责人和复验结果；涉及敏感数据时仅引用受控材料编号，避免在公开讨论渠道散布原始业务内容。")))),
                Map.entry("sla", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "S1"),
                                Map.entry("level", "紧急"),
                                Map.entry("impact", "约定核心流程不可用，且没有可行替代方式。"),
                                Map.entry("response", "工作时段 1 小时内。"),
                                Map.entry("workaround", "4 个工作小时内给出缓解方案。"),
                                Map.entry("recovery", "先恢复可用性，再确认根因与永久修复计划。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "S2"),
                                Map.entry("level", "高"),
                                Map.entry("impact", "重要功能受限，有经确认的临时替代路径。"),
                                Map.entry("response", "工作时段 4 小时内。"),
                                Map.entry("workaround", "1 个工作日内给出处理安排。"),
                                Map.entry("recovery", "按影响确定修复版本并由甲方复验。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "S3"),
                                Map.entry("level", "一般"),
                                Map.entry("impact", "非核心功能或局部版式问题，不影响主要交付。"),
                                Map.entry("response", "1 个工作日内。"),
                                Map.entry("workaround", "2 个工作日内给出定位结果。"),
                                Map.entry("recovery", "纳入双方确认的维护版本。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "S4"),
                                Map.entry("level", "咨询"),
                                Map.entry("impact", "使用疑问、优化建议或新增能力咨询。"),
                                Map.entry("response", "2 个工作日内。"),
                                Map.entry("workaround", "说明现有方法及适用边界。"),
                                Map.entry("recovery", "新增需求按变更流程评估，不混同缺陷。")))),
                Map.entry("support", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "支持时间为工作日 09:00—18:00；节假日和非工作时段的额外保障须另行约定。上述时限是首次响应或方案安排，不等同于所有问题在同一期限内完成修复。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "发起支持时提供版本、时间、操作步骤和脱敏后的最小输入；乙方按实际影响确认等级。等级有分歧时先采取合理缓解措施，再由双方项目负责人核对。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "质保范围限于约定环境和已验收功能的缺陷。外部接口变动、未经确认的修改或新增容量要求应单独评估，但乙方仍应清楚说明判断依据。")))),
                Map.entry("dataClasses", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("level", "公开"),
                                Map.entry("examples", "已公开的产品介绍和演示材料。"),
                                Map.entry("access", "按项目需要共享。"),
                                Map.entry("retention", "按资料版本维护。"),
                                Map.entry("handling", "保留来源及许可，避免混入未公开附件。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("level", "内部"),
                                Map.entry("examples", "字段规范、接口说明及项目计划。"),
                                Map.entry("access", "项目参与人最小权限。"),
                                Map.entry("retention", "项目存续期及约定归档期。"),
                                Map.entry("handling", "使用受控目录，记录版本和接收人。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("level", "受限"),
                                Map.entry("examples", "业务记录、账号标识和非公开合同。"),
                                Map.entry("access", "经授权的指定人员。"),
                                Map.entry("retention", "按甲方确认的最短必要期限。"),
                                Map.entry("handling", "测试前脱敏，传输与导出需审批。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("level", "禁止提供"),
                                Map.entry("examples", "生产口令、私钥及无关个人敏感材料。"),
                                Map.entry("access", "不得进入实施样例。"),
                                Map.entry("retention", "不收集、不保留。"),
                                Map.entry("handling", "发现后暂停相关处理并按约定清除。")))),
                Map.entry("dataProcessing", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("title", "7.1 使用与隔离"),
                                Map.entry("text", "甲方确定处理目的和允许范围，乙方仅按书面指示访问必要资料。开发与测试环境分离，个人设备不保留生产副本；日志采用记录编号和状态，不默认输出完整正文、令牌或资源的私人路径。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("title", "7.2 事件处置"),
                                Map.entry("text", "发现误发、越权或疑似泄露时，先限制继续扩散，保存必要证据并及时通知指定联系人。双方核对受影响资料、访问时间和补救措施，未经确认不得自行将原始材料发送给第三方分析。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("title", "7.3 归还与删除"),
                                Map.entry("text", "项目结束或处理目的消失后，根据甲方指示归还或删除工作副本。备份中的资料按预定周期退出，清除结果列入交接记录；法律或双方约定必须保留的内容单独隔离并说明理由。")))),
                Map.entry("changeSteps", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("step", "C-01 提出"),
                                Map.entry("owner", "需求发起人"),
                                Map.entry("input", "说明现状、目标、原因及紧急程度。"),
                                Map.entry("result", "形成唯一编号，不直接进入实施。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("step", "C-02 评估"),
                                Map.entry("owner", "技术与业务负责人"),
                                Map.entry("input", "核对工作量、依赖、成本和兼容影响。"),
                                Map.entry("result", "给出可选方案、风险及验收方法。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("step", "C-03 批准"),
                                Map.entry("owner", "双方授权代表"),
                                Map.entry("input", "确认费用、时间与替代的基线条目。"),
                                Map.entry("result", "签署变更单并更新项目清单。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("step", "C-04 实施与关闭"),
                                Map.entry("owner", "交付及验收负责人"),
                                Map.entry("input", "按确认版本实施、测试并复验。"),
                                Map.entry("result", "关联交付证据，关闭变更记录。")))),
                Map.entry("attachments", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "A-01"),
                                Map.entry("name", "需求与字段基线"),
                                Map.entry("version", "1.0"),
                                Map.entry("purpose", "确定模板种类、字段、资源及明确排除项。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "A-02"),
                                Map.entry("name", "交付与验收矩阵"),
                                Map.entry("version", "1.0"),
                                Map.entry("purpose", "逐项记录输入、检查步骤、预期和责任人。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "A-03"),
                                Map.entry("name", "实施计划与联系人"),
                                Map.entry("version", "1.0"),
                                Map.entry("purpose", "记录节点、依赖、沟通频率及升级路径。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "A-04"),
                                Map.entry("name", "安全与数据处理约定"),
                                Map.entry("version", "1.0"),
                                Map.entry("purpose", "确定资料分级、访问、保留和清除要求。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "A-05"),
                                Map.entry("name", "变更、培训及移交记录"),
                                Map.entry("version", "按发生更新"),
                                Map.entry("purpose", "保留批准的变更单和最终交接证据。")))));
    }

    private static Map<String, Object> resume() {
        return Map.<String, Object>ofEntries(
                Map.entry("title", "林知远"),
                Map.entry("person", Map.<String, Object>ofEntries(
                        Map.entry("name", "林知远"),
                        Map.entry("role", "高级 Java 工程师 / 文档与数据平台"),
                        Map.entry("contact", "resume@example.invalid · 示例城市 · example.invalid/portfolio"),
                        Map.entry("summary", "六年业务平台与文档工程经验，擅长把不清晰的业务规则转为稳定的模型、接口和验收条件。关注数据准确性、异常可诊断性和复杂长文档的可复现交付，能够协调业务、设计、测试与运维完成完整迭代。"),
                        Map.entry("availability", "全职 · 到岗时间可协商"),
                        Map.entry("focus", "Java 后端 / 文档平台 / 数据服务"),
                        Map.entry("workingStyle", "先明确输入与责任边界，再设计可观察的执行过程；对重要输出保留结构检查和独立预览证据。"),
                        Map.entry("location", "示例城市（虚构）"),
                        Map.entry("languages", "中文：工作语言\n英语：技术文档阅读与邮件沟通"),
                        Map.entry("disclaimer", "人物、机构、履历、证书及项目指标均为虚构示例。照片仅为排版配图，不代表履历人物或产品背书。"))),
                Map.entry("highlights", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("metric", "平台演进"),
                                Map.entry("value", "6 年"),
                                Map.entry("explanation", "订单、知识资料与文档交付场景。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("metric", "完整交付"),
                                Map.entry("value", "3 类平台"),
                                Map.entry("explanation", "从需求基线推进到验收与移交。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("metric", "工程侧重"),
                                Map.entry("value", "可复现"),
                                Map.entry("explanation", "明确数据契约、预算、异常与验证。")))),
                Map.entry("skills", Map.<String, Object>ofEntries(
                        Map.entry("java", "Java 21、并发、集合与 I/O；关注资源所有权、取消和失败原子性。"),
                        Map.entry("data", "SQL、索引设计、分页、数据校验；能够定位口径和性能问题。"),
                        Map.entry("platform", "HTTP API、任务状态机、幂等、权限与审计。"),
                        Map.entry("delivery", "Gradle、Git、持续集成、制品清单与版本管理。"),
                        Map.entry("quality", "行为测试、独立工具核验、基准采样与问题复盘。"))),
                Map.entry("education", Map.<String, Object>ofEntries(
                        Map.entry("school", "示例理工大学（虚构）"),
                        Map.entry("major", "计算机科学与技术 · 本科"),
                        Map.entry("period", "2016.09 — 2020.06"),
                        Map.entry("detail", "课程方向：软件工程、数据库、计算机网络。毕业项目聚焦资料检索与结构化导出。"))),
                Map.entry("certifications", Map.<String, Object>ofEntries(
                        Map.entry("first", "示例软件工程能力认证 · 2024"),
                        Map.entry("second", "示例技术沟通课程 · 2023"),
                        Map.entry("note", "证书名称和颁发记录为演示内容。"))),
                Map.entry("community", Map.<String, Object>ofEntries(
                        Map.entry("sharing", "维护团队知识库，整理接口评审、长表分页和故障排查专题。"),
                        Map.entry("mentoring", "辅导新成员编写最小复现和验收清单，推动评审结论落实到代码与使用文档。"))),
                Map.entry("jobs", Map.<String, Object>ofEntries(
                        Map.entry("current", Map.<String, Object>ofEntries(
                                Map.entry("company", "青木示例科技（虚构）"),
                                Map.entry("role", "高级后端工程师"),
                                Map.entry("period", "2023.07 — 至今"),
                                Map.entry("scope", "负责业务文档平台、批处理服务与核心接口，参与架构评审和跨团队迭代计划。"),
                                Map.entry("delivery", "统一字段映射、模板版本和导出诊断，建立请求预算、取消、原子写入及权限检查，降低调用方对实现细节的依赖。"),
                                Map.entry("impact", "将三类业务输出纳入统一验收样例；故障定位由依赖人工口述改为输入、版本与错误码可复现。"))),
                        Map.entry("previous", Map.<String, Object>ofEntries(
                                Map.entry("company", "星河示例软件（虚构）"),
                                Map.entry("role", "Java 开发工程师"),
                                Map.entry("period", "2020.07 — 2023.06"),
                                Map.entry("scope", "参与订单与资料管理系统，负责查询、导入及状态流转接口，协同业务确认数据口径。"),
                                Map.entry("delivery", "优化多条件检索与批量处理，补齐超时、重复请求及异常数据的处理策略；推动数据库变更与发布步骤清单化。"),
                                Map.entry("impact", "建立接口字段对照和回归语料，减少前后端解释分歧；把常见重复核对工作转为可复用脚本。"))))),
                Map.entry("projects", Map.<String, Object>ofEntries(
                        Map.entry("document", Map.<String, Object>ofEntries(
                                Map.entry("name", "业务文档工作台"),
                                Map.entry("role", "核心设计与实现 · 2025—2026"),
                                Map.entry("problem", "业务模板分散，复杂长表与多种目标格式的结果难以核对，失败信息也无法直接定位。"),
                                Map.entry("solution", "分离语义模型、排版和输出，提供显式资源、字段诊断及受控的兼容损失策略，建立短表、长表和跨页组合语料。"),
                                Map.entry("result", "六类模板共用字段契约；每轮交付核对全部新增页和末条记录，保留可复现的生成入口。"))),
                        Map.entry("data", Map.<String, Object>ofEntries(
                                Map.entry("name", "资料检索与批处理服务"),
                                Map.entry("role", "后端主责 · 2023—2024"),
                                Map.entry("problem", "多来源资料字段不一致，重试可能重复生成记录，长任务失败后缺少可追踪的状态。"),
                                Map.entry("solution", "定义来源快照和幂等键，为任务增加有界重试、阶段状态及失败原因；对索引和查询计划进行实际采样。"),
                                Map.entry("result", "建立可追溯任务链路，明确部分成功与阻断失败；通过样例核对导入字段和导出数据一致性。"))),
                        Map.entry("release", Map.<String, Object>ofEntries(
                                Map.entry("name", "交付与运行标准化"),
                                Map.entry("role", "方案推动与协作 · 2022—2023"),
                                Map.entry("problem", "发布依赖人工经验，配置、数据库步骤与回归内容分散，接手人员难以独立排查。"),
                                Map.entry("solution", "整理制品、环境配置和回滚步骤，建设隔离验收入口，将关键运行条件和已知限制写入操作手册。"),
                                Map.entry("result", "交接材料覆盖安装、运行、恢复和异常处理；演练结论与未验证边界分别记录。"))))),
                Map.entry("project", Map.<String, Object>ofEntries(
                        Map.entry("name", "工程实践摘要"),
                        Map.entry("description", "将复杂需求拆成可交付的边界，以数据、结构和实际输出共同验收；指标只描述真实测量过的场景，不将局部样例推广为全部能力。"))));
    }

    private static Map<String, Object> manual() {
        return Map.<String, Object>ofEntries(
                Map.entry("title", "青木文档工作台 管理与使用说明书"),
                Map.entry("product", Map.<String, Object>ofEntries(
                        Map.entry("name", "青木文档工作台"),
                        Map.entry("code", "DEMO-DOC-STUDIO"),
                        Map.entry("version", "2.3 / 示例版"),
                        Map.entry("date", "2026-04-15"),
                        Map.entry("owner", "示例产品与交付团队（虚构）"),
                        Map.entry("introduction", "青木文档工作台是虚构的企业文档管理产品。使用者通过模板、结构化数据和显式资源创建业务文件，再按约定的检查流程交付。本手册将管理员、模板维护人员、业务操作人员与审阅人员的任务分开说明，覆盖从首次配置到故障恢复的完整工作路径。"),
                        Map.entry("audience", "首次使用者先完成安装与快速流程；模板维护者重点阅读字段、版本及发布步骤；管理员重点核对权限、预算、备份和审计。文中所有目录、账号、参数、业务信息与限制均为演示设定，不代表实际产品的兼容承诺。"),
                        Map.entry("boundary", "工作台不自动修正不明确的业务数据，也不将兼容降级视为无损转换。输入无效、资源缺失、权限不足及超出预算均应明确失败；允许降级的功能必须在报告中说明实际影响，由有权限的人员决定是否继续。"),
                        Map.entry("maintenance", "每周检查任务失败率、资源使用和备份完成情况；每月从备份副本执行一次隔离恢复。升级前保存配置、模板与数据快照，核对版本说明和回退条件。维护结束后使用固定验收清单确认生成、权限、页码及末条记录。"),
                        Map.entry("disclaimer", "产品、账号、参数、工作流程、案例及服务地址均为虚构。文档只展示复杂说明书的结构、长表续页、富文本与绑定能力，不对应实际软件的使用要求。"))),
                Map.entry("revisions", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("version", "2.3"),
                                Map.entry("date", "2026-04-15"),
                                Map.entry("owner", "示例文档组"),
                                Map.entry("change", "补充审批发布、资源检查、任务预算和恢复验收。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("version", "2.2"),
                                Map.entry("date", "2026-03-20"),
                                Map.entry("owner", "示例产品组"),
                                Map.entry("change", "整理批量任务、问题定位和权限矩阵。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("version", "2.1"),
                                Map.entry("date", "2026-02-10"),
                                Map.entry("owner", "示例交付组"),
                                Map.entry("change", "建立首次安装、模板维护及备份流程。")))),
                Map.entry("terms", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "项目空间"),
                                Map.entry("meaning", "隔离模板、资源、数据和成员授权的业务范围。"),
                                Map.entry("example", "采购文档 / 项目资料。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "模板版本"),
                                Map.entry("meaning", "可以被审核和发布的源码与资源快照。"),
                                Map.entry("example", "合同模板 v3。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "测试数据"),
                                Map.entry("meaning", "仅用于验证绑定与分页的虚构输入。"),
                                Map.entry("example", "长名称、空列表和多行记录。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "任务快照"),
                                Map.entry("meaning", "一次执行采用的模板、数据、资源和策略。"),
                                Map.entry("example", "任务 J-2026-001。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "兼容报告"),
                                Map.entry("meaning", "逐项描述目标格式中保留、降级或丢弃的内容。"),
                                Map.entry("example", "页区不适用、样式简化。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "发布"),
                                Map.entry("meaning", "将经确认的模板版本设为可供业务使用的版本。"),
                                Map.entry("example", "编辑稿通过审批后上线。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "原子输出"),
                                Map.entry("meaning", "成功前保留旧目标，失败不留下半成品替代旧文件。"),
                                Map.entry("example", "生成失败后原合同仍可用。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("term", "恢复点"),
                                Map.entry("meaning", "可用于还原配置、模板与业务资料的备份版本。"),
                                Map.entry("example", "周五验收后的完整快照。")))),
                Map.entry("requirements", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("item", "运行环境"),
                                Map.entry("requirement", "使用约定的运行时版本与独立服务账号。"),
                                Map.entry("check", "核对版本及账号读写范围。"),
                                Map.entry("owner", "管理员")),
                        Map.<String, Object>ofEntries(
                                Map.entry("item", "目录与磁盘"),
                                Map.entry("requirement", "配置、任务输出和临时文件使用可控目录。"),
                                Map.entry("check", "验证空间、权限和清理策略。"),
                                Map.entry("owner", "运维人员")),
                        Map.<String, Object>ofEntries(
                                Map.entry("item", "字体与图片"),
                                Map.entry("requirement", "由项目显式登记资源及许可来源。"),
                                Map.entry("check", "检查摘要、大小和可读取性。"),
                                Map.entry("owner", "模板维护者")),
                        Map.<String, Object>ofEntries(
                                Map.entry("item", "业务数据"),
                                Map.entry("requirement", "字段名、类型和列表结构有明确说明。"),
                                Map.entry("check", "用虚构数据验证必填及边界。"),
                                Map.entry("owner", "业务负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("item", "网络边界"),
                                Map.entry("requirement", "外部连接仅限经确认的业务接口。"),
                                Map.entry("check", "确认离线资源可正常生成。"),
                                Map.entry("owner", "技术负责人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("item", "备份条件"),
                                Map.entry("requirement", "具备独立备份目标和一次恢复演练安排。"),
                                Map.entry("check", "核对恢复点、保留期与接收人。"),
                                Map.entry("owner", "管理员")))),
                Map.entry("notices", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "试运行使用隔离的虚构数据；真实业务资料仅进入经授权的项目空间。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "不要将口令、私钥和个人目录写入模板或公开问题描述。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "预览、导出和正式交付是不同步骤，任务成功仍需核对最终文件内容。")))),
                Map.entry("installation", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-01"),
                                Map.entry("action", "核对安装材料"),
                                Map.entry("result", "清单包括程序版本、模板、字体、图片及许可说明。"),
                                Map.entry("note", "发现缺项时先补齐，不从个人目录隐式寻找资源。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-02"),
                                Map.entry("action", "建立服务目录"),
                                Map.entry("result", "配置、临时文件和输出目录分别登记且可写。"),
                                Map.entry("note", "只授予必需权限，避免多个项目共用无隔离的临时目录。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-03"),
                                Map.entry("action", "配置初始账号"),
                                Map.entry("result", "管理员账号可登录，初始口令完成更新。"),
                                Map.entry("note", "不在截图或日志中显示完整凭据。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-04"),
                                Map.entry("action", "创建项目空间"),
                                Map.entry("result", "设置名称、业务负责人、保留期及审批规则。"),
                                Map.entry("note", "先明确谁可编辑、谁可发布及谁可下载。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-05"),
                                Map.entry("action", "登记字体与资源"),
                                Map.entry("result", "资源列表显示名称、类型、摘要和许可来源。"),
                                Map.entry("note", "替换资源会产生新版本，旧任务保留原快照。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-06"),
                                Map.entry("action", "导入测试模板"),
                                Map.entry("result", "模板结构和绑定数据均能解析。"),
                                Map.entry("note", "编译错误应定位到元素或字段，不能静默跳过。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-07"),
                                Map.entry("action", "运行检查任务"),
                                Map.entry("result", "短数据和长数据均生成，页码与末条记录正确。"),
                                Map.entry("note", "分别验证资源缺失、权限不足和取消路径。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("number", "I-08"),
                                Map.entry("action", "建立首次恢复点"),
                                Map.entry("result", "配置、模板、测试数据和版本清单完成备份。"),
                                Map.entry("note", "在隔离目录恢复一次后再进入业务试运行。")))),
                Map.entry("workflow", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "W-01"),
                                Map.entry("stage", "准备"),
                                Map.entry("input", "业务字段、资源及模板草图。"),
                                Map.entry("action", "核对类型与必填字段，建立测试数据。"),
                                Map.entry("output", "经确认的模板草稿。"),
                                Map.entry("owner", "模板维护者")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "W-02"),
                                Map.entry("stage", "验证"),
                                Map.entry("input", "草稿和长短数据组合。"),
                                Map.entry("action", "检查结构、视觉、页码及错误策略。"),
                                Map.entry("output", "验证记录与已知边界。"),
                                Map.entry("owner", "维护者与审阅者")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "W-03"),
                                Map.entry("stage", "审批"),
                                Map.entry("input", "固定版本及对应验证证据。"),
                                Map.entry("action", "业务确认字段，授权人批准发布。"),
                                Map.entry("output", "可供业务调用的版本。"),
                                Map.entry("owner", "发布审批人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "W-04"),
                                Map.entry("stage", "执行"),
                                Map.entry("input", "已发布版本、业务数据及策略。"),
                                Map.entry("action", "生成、检查报告并保存任务快照。"),
                                Map.entry("output", "输出文件和结果记录。"),
                                Map.entry("owner", "业务操作员")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "W-05"),
                                Map.entry("stage", "交付"),
                                Map.entry("input", "已检查的文件和使用说明。"),
                                Map.entry("action", "确认目标接收人、格式和交付范围。"),
                                Map.entry("output", "签收或归档记录。"),
                                Map.entry("owner", "业务负责人")))),
                Map.entry("roles", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "查看者"),
                                Map.entry("read", "授权范围"),
                                Map.entry("edit", "禁止"),
                                Map.entry("execute", "禁止"),
                                Map.entry("publish", "禁止"),
                                Map.entry("manage", "禁止"),
                                Map.entry("audit", "本人记录")),
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "业务操作员"),
                                Map.entry("read", "授权范围"),
                                Map.entry("edit", "仅任务数据"),
                                Map.entry("execute", "允许"),
                                Map.entry("publish", "禁止"),
                                Map.entry("manage", "禁止"),
                                Map.entry("audit", "本人任务")),
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "模板维护者"),
                                Map.entry("read", "所属项目"),
                                Map.entry("edit", "草稿及资源"),
                                Map.entry("execute", "测试任务"),
                                Map.entry("publish", "提交审批"),
                                Map.entry("manage", "禁止"),
                                Map.entry("audit", "相关版本")),
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "发布审批人"),
                                Map.entry("read", "所属项目"),
                                Map.entry("edit", "审批意见"),
                                Map.entry("execute", "复验任务"),
                                Map.entry("publish", "允许"),
                                Map.entry("manage", "禁止"),
                                Map.entry("audit", "发布记录")),
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "项目管理员"),
                                Map.entry("read", "所属项目"),
                                Map.entry("edit", "项目配置"),
                                Map.entry("execute", "允许"),
                                Map.entry("publish", "按审批规则"),
                                Map.entry("manage", "项目成员"),
                                Map.entry("audit", "项目记录")),
                        Map.<String, Object>ofEntries(
                                Map.entry("role", "审计人员"),
                                Map.entry("read", "经授权范围"),
                                Map.entry("edit", "禁止"),
                                Map.entry("execute", "禁止"),
                                Map.entry("publish", "禁止"),
                                Map.entry("manage", "禁止"),
                                Map.entry("audit", "只读审计")))),
                Map.entry("parameters", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "任务超时"),
                                Map.entry("value", "60 秒"),
                                Map.entry("scope", "单次执行"),
                                Map.entry("description", "从任务开始到输出完成的最大允许时间。"),
                                Map.entry("validation", "超时中止并保留明确失败原因。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "最大页数"),
                                Map.entry("value", "200 页"),
                                Map.entry("scope", "单个文件"),
                                Map.entry("description", "约束预览和导出的输出规模。"),
                                Map.entry("validation", "超过预算拒绝，不截断为部分文档。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "最大记录数"),
                                Map.entry("value", "10000 条"),
                                Map.entry("scope", "列表绑定"),
                                Map.entry("description", "限制单次绑定的业务记录总数。"),
                                Map.entry("validation", "在生成前核对记录数与预算。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "单资源上限"),
                                Map.entry("value", "8 MiB"),
                                Map.entry("scope", "图片或字体"),
                                Map.entry("description", "控制显式资源的编码大小。"),
                                Map.entry("validation", "读取超过上限时停止并说明资源。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "输出上限"),
                                Map.entry("value", "32 MiB"),
                                Map.entry("scope", "单次结果"),
                                Map.entry("description", "限制最终文件和相关封装大小。"),
                                Map.entry("validation", "失败时不替换已存在的目标文件。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "并发任务数"),
                                Map.entry("value", "4"),
                                Map.entry("scope", "项目空间"),
                                Map.entry("description", "运行任务达到上限后排队等待。"),
                                Map.entry("validation", "同时显示运行数和排队数。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "临时文件期"),
                                Map.entry("value", "24 小时"),
                                Map.entry("scope", "失败或取消任务"),
                                Map.entry("description", "清理未被保留的中间结果。"),
                                Map.entry("validation", "只删除任务拥有的临时材料。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "结果保留期"),
                                Map.entry("value", "30 天"),
                                Map.entry("scope", "任务记录"),
                                Map.entry("description", "按项目制度保留结果与必要快照。"),
                                Map.entry("validation", "到期前提醒，不删除人工归档材料。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "兼容动作"),
                                Map.entry("value", "拒绝"),
                                Map.entry("scope", "导出操作"),
                                Map.entry("description", "不支持功能默认阻断，可经授权改为警告或忽略。"),
                                Map.entry("validation", "忽略仍保留实际损失记录。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "时间显示"),
                                Map.entry("value", "项目时区"),
                                Map.entry("scope", "界面及报告"),
                                Map.entry("description", "统一任务、审批和审计时间解释。"),
                                Map.entry("validation", "记录保留原始时间与显示时区。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "命名规则"),
                                Map.entry("value", "项目-任务-版本"),
                                Map.entry("scope", "输出文件"),
                                Map.entry("description", "使用受控字段构成文件名。"),
                                Map.entry("validation", "过滤非法字符并避免覆盖无关文件。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("name", "重试次数"),
                                Map.entry("value", "1 次人工重试"),
                                Map.entry("scope", "可重试失败"),
                                Map.entry("description", "先确认根因和快照，再重新执行。"),
                                Map.entry("validation", "新任务关联原任务，不覆盖原失败证据。")))),
                Map.entry("operations", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-01"),
                                Map.entry("name", "新建草稿"),
                                Map.entry("description", "从空白或已批准样例开始，填写业务用途和负责人，确定页面尺寸、页边距与基本层级。"),
                                Map.entry("expected", "草稿有独立版本号，未发布前不替换业务版本。"),
                                Map.entry("caution", "复制模板时重新检查引用资源与业务字段。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-02"),
                                Map.entry("name", "设置绑定数据"),
                                Map.entry("description", "按字段字典填写对象、字符串、数字与列表。先准备常规数据，再补充空列表、长名称、多行备注和缺少字段的场景。"),
                                Map.entry("expected", "每个表达式能定位到明确字段，非法输入得到具体错误。"),
                                Map.entry("caution", "不要把业务字段名翻译成未约定的显示别名。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-03"),
                                Map.entry("name", "调整复杂表格"),
                                Map.entry("description", "配置列宽、行列合并、内边距与重复表头。长行需要跨页时显式选择按完整行续排，并核对上下文与末条记录。"),
                                Map.entry("expected", "表头与数据列对应，续页没有丢格、重叠或重复正文。"),
                                Map.entry("caution", "不要用大量空行或固定分页符掩盖数据长度问题。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-04"),
                                Map.entry("name", "验证兼容策略"),
                                Map.entry("description", "选择目标格式和动作，检查每个不兼容功能的处理说明。拒绝会阻止导出，警告或忽略必须对应明确的降级或丢弃。"),
                                Map.entry("expected", "报告列出功能、影响、处理和定位信息。"),
                                Map.entry("caution", "策略不能绕过权限、资源安全或预算限制。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-05"),
                                Map.entry("name", "提交审批"),
                                Map.entry("description", "冻结模板与资源快照，附上测试数据、生成结果和已知限制。审批人对照字段、版式及错误路径复核后记录结论。"),
                                Map.entry("expected", "审批对应准确版本，驳回后继续产生新草稿。"),
                                Map.entry("caution", "不能用旧版本截图证明新版本已经验收。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-06"),
                                Map.entry("name", "执行批量任务"),
                                Map.entry("description", "选择已发布版本，核对数据来源、记录数、输出位置和任务预算，再提交执行。运行时可查看进度并同步取消。"),
                                Map.entry("expected", "每条结果关联任务快照，失败与部分成功状态区分清楚。"),
                                Map.entry("caution", "取消后确认任务已经停止，避免同时反复点击重试。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-07"),
                                Map.entry("name", "检查与下载"),
                                Map.entry("description", "先核对执行状态，再查看兼容报告及预览，检查标题、合计、分页和签署区域。确认后下载并按业务制度归档。"),
                                Map.entry("expected", "下载文件与任务版本一致，长表最后一条记录完整。"),
                                Map.entry("caution", "文件可打开不代表其内容、金额和页码全部正确。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "O-08"),
                                Map.entry("name", "回退发布版本"),
                                Map.entry("description", "在版本历史中选择已验证的恢复版本，说明回退原因并重新审批。现有任务保留原快照，新的业务任务采用回退后的发布版本。"),
                                Map.entry("expected", "发布指向改变有记录，旧任务仍可追溯。"),
                                Map.entry("caution", "涉及数据结构变化时先评估旧版本是否仍能绑定。")))),
                Map.entry("troubleshooting", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-01"),
                                Map.entry("symptom", "模板无法解析"),
                                Map.entry("cause", "XML 结构或属性不在支持范围。"),
                                Map.entry("checks", "核对行列位置、标签闭合和属性名称。"),
                                Map.entry("solution", "回到最小可解析版本，逐项恢复修改；保留失败位置，不直接删除未知内容。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-02"),
                                Map.entry("symptom", "字段值显示为空"),
                                Map.entry("cause", "输入为空或字段路径与数据层级不一致。"),
                                Map.entry("checks", "对照字段字典检查对象名称、大小写及空值。"),
                                Map.entry("solution", "补齐必要数据，或在模板中明确约定空值显示；不得把缺少字段默认为真实零值。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-03"),
                                Map.entry("symptom", "列表没有记录"),
                                Map.entry("cause", "列表为空、选择了错误数据集或过滤条件。"),
                                Map.entry("checks", "检查记录数和首末记录标识。"),
                                Map.entry("solution", "用两条虚构记录验证绑定，再检查业务查询；不要添加静态假记录掩盖空列表。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-04"),
                                Map.entry("symptom", "字体资源缺失"),
                                Map.entry("cause", "资源未登记或模板引用了旧名称。"),
                                Map.entry("checks", "核对资源清单、摘要、授权范围和当前版本。"),
                                Map.entry("solution", "显式补充允许使用的字体并重试，不扫描个人电脑上的字体作为隐式替代。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-05"),
                                Map.entry("symptom", "字符显示不完整"),
                                Map.entry("cause", "所选字体不覆盖输入字符。"),
                                Map.entry("checks", "定位字符、字体角色及具体页区。"),
                                Map.entry("solution", "选择覆盖所需字符的已授权字体；如允许占位替换，必须保留缺字诊断。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-06"),
                                Map.entry("symptom", "图片无法加载"),
                                Map.entry("cause", "资源缺失、编码无效或超过大小预算。"),
                                Map.entry("checks", "检查资源名称、类型、大小和可解码性。"),
                                Map.entry("solution", "重新登记正确图片并核对尺寸；不要把外部链接当作已经嵌入的图片。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-07"),
                                Map.entry("symptom", "表格列宽不符合预期"),
                                Map.entry("cause", "列比例、固定宽度或内边距影响有效宽度。"),
                                Map.entry("checks", "核对页面宽度、列总宽和跨列覆盖。"),
                                Map.entry("solution", "按实际可用区域调整宽度；合并单元格只扣除一次其自身内边距。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-08"),
                                Map.entry("symptom", "长行无法放入新页"),
                                Map.entry("cause", "整行或合并组高于正文区且没有允许续排。"),
                                Map.entry("checks", "检查行高、合并范围和拆行设置。"),
                                Map.entry("solution", "在支持的内容范围启用完整行续排，或拆分业务结构；不能通过裁掉正文解决。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-09"),
                                Map.entry("symptom", "页码或总页数不正确"),
                                Map.entry("cause", "查看了旧输出或字段预留宽度不足。"),
                                Map.entry("checks", "核对任务版本、页脚字段和显示页码规则。"),
                                Map.entry("solution", "重新生成并核对最终物理页与显示页码；为多位页码提供足够预留宽度。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-10"),
                                Map.entry("symptom", "导出被兼容策略拒绝"),
                                Map.entry("cause", "目标格式无法保留某项源功能。"),
                                Map.entry("checks", "打开报告，核对功能、处理和影响。"),
                                Map.entry("solution", "调整设计或经授权选择明确的降级方式；不把忽略报告当作无损导出。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-11"),
                                Map.entry("symptom", "任务超时"),
                                Map.entry("cause", "数据量、资源处理或任务预算不匹配。"),
                                Map.entry("checks", "检查输入规模、阶段耗时和取消状态。"),
                                Map.entry("solution", "先定位耗时阶段，再按业务范围分批处理或调整经确认的预算。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-12"),
                                Map.entry("symptom", "输出超出大小上限"),
                                Map.entry("cause", "原始图片过大或文档记录过多。"),
                                Map.entry("checks", "核对最终字节数、图片编码和记录数量。"),
                                Map.entry("solution", "压缩明确允许优化的资源或拆分任务，保留原数据并说明处理方式。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-13"),
                                Map.entry("symptom", "目标文件无法写入"),
                                Map.entry("cause", "目录无权限、同名文件被占用或磁盘不足。"),
                                Map.entry("checks", "检查目录、空间和文件占用状态。"),
                                Map.entry("solution", "释放占用或选择授权目录后重试；确认失败没有破坏原有目标文件。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-14"),
                                Map.entry("symptom", "按钮或操作被禁止"),
                                Map.entry("cause", "账号没有相应角色或项目授权。"),
                                Map.entry("checks", "查看当前项目、账号角色和审批状态。"),
                                Map.entry("solution", "向项目管理员申请必要权限，不借用其他人的账号执行受限操作。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-15"),
                                Map.entry("symptom", "取消后仍显示运行中"),
                                Map.entry("cause", "界面尚未刷新或引擎正在结束当前同步步骤。"),
                                Map.entry("checks", "查询任务最终状态和输出是否完成。"),
                                Map.entry("solution", "等待状态收敛并检查取消结果；若超过约定时间，记录任务编号交管理员定位。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-16"),
                                Map.entry("symptom", "新版本和旧任务结果不同"),
                                Map.entry("cause", "模板、资源、数据或策略发生了变化。"),
                                Map.entry("checks", "比较两次任务的完整快照及资源摘要。"),
                                Map.entry("solution", "使用相同快照进行对照，逐项定位预期变化，不能仅比较文件大小。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-17"),
                                Map.entry("symptom", "恢复后缺少材料"),
                                Map.entry("cause", "备份范围不完整或恢复点与配置版本不匹配。"),
                                Map.entry("checks", "核对模板、数据、资源和版本清单。"),
                                Map.entry("solution", "停止覆盖当前目录，在隔离环境补齐缺项后重新演练并记录结果。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "T-18"),
                                Map.entry("symptom", "导出记录与界面数量不一致"),
                                Map.entry("cause", "查询范围、分页或部分失败未被核对。"),
                                Map.entry("checks", "对照任务总数、首末标识和逐条结果。"),
                                Map.entry("solution", "明确成功、失败和跳过数量，修复范围后重新执行；不以 HTTP 成功状态代替内容验收。")))),
                Map.entry("maintenanceTasks", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("frequency", "每日"),
                                Map.entry("task", "任务与错误检查"),
                                Map.entry("procedure", "查看失败、取消、超时及排队异常，关联具体任务定位。"),
                                Map.entry("evidence", "异常清单和处理结论。"),
                                Map.entry("owner", "运维人员")),
                        Map.<String, Object>ofEntries(
                                Map.entry("frequency", "每周"),
                                Map.entry("task", "资源与权限复核"),
                                Map.entry("procedure", "核对新增资源来源、无用授权及临时目录清理。"),
                                Map.entry("evidence", "资源及授权变更记录。"),
                                Map.entry("owner", "项目管理员")),
                        Map.<String, Object>ofEntries(
                                Map.entry("frequency", "每周"),
                                Map.entry("task", "备份完整性检查"),
                                Map.entry("procedure", "确认备份任务完成且清单中的文件可读取。"),
                                Map.entry("evidence", "备份编号、摘要和保留期限。"),
                                Map.entry("owner", "运维人员")),
                        Map.<String, Object>ofEntries(
                                Map.entry("frequency", "每月"),
                                Map.entry("task", "隔离恢复演练"),
                                Map.entry("procedure", "从实际备份恢复并执行固定验收样例。"),
                                Map.entry("evidence", "恢复步骤、用时及检查结果。"),
                                Map.entry("owner", "运维与审阅者")),
                        Map.<String, Object>ofEntries(
                                Map.entry("frequency", "每次发布"),
                                Map.entry("task", "版本与回退检查"),
                                Map.entry("procedure", "检查模板、资源、配置和回退条件是否一致。"),
                                Map.entry("evidence", "发布单与回退计划。"),
                                Map.entry("owner", "发布审批人")),
                        Map.<String, Object>ofEntries(
                                Map.entry("frequency", "人员调整"),
                                Map.entry("task", "账号权限交接"),
                                Map.entry("procedure", "移除离任成员授权并转交任务及材料责任。"),
                                Map.entry("evidence", "权限清单和交接确认。"),
                                Map.entry("owner", "项目管理员")))),
                Map.entry("recovery", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "选择恢复点并核对清单、版本和摘要，明确需要恢复的范围；保留当前环境的必要快照。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "在独立目录或隔离环境恢复配置、模板、资源和测试数据，不先覆盖正常工作的实例。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "按依赖顺序验证资源、模板编译和数据绑定，检查缺项时回到备份清单补齐。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "运行短文档、长表、多页和错误策略样例，核对首末内容、页码与文件可读性。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "验证角色权限、审批状态、审计记录和取消路径，确认没有扩大访问范围。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("text", "由负责人确认恢复结果和切换窗口，保留失败时的回退路径，再执行经批准的切换。")))),
                Map.entry("acceptance", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-01"),
                                Map.entry("item", "版本与资源"),
                                Map.entry("expected", "程序、模板和资源版本可核对。"),
                                Map.entry("evidence", "版本清单及摘要。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-02"),
                                Map.entry("item", "基础生成"),
                                Map.entry("expected", "常规数据能生成且必填字段完整。"),
                                Map.entry("evidence", "基准输入及输出。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-03"),
                                Map.entry("item", "长表续排"),
                                Map.entry("expected", "重复表头正确，最后一条记录可见。"),
                                Map.entry("evidence", "全部页预览与记录计数。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-04"),
                                Map.entry("item", "字段与页码"),
                                Map.entry("expected", "最终页码、总页数及引用正确。"),
                                Map.entry("evidence", "逐页核对记录。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-05"),
                                Map.entry("item", "金额与数据"),
                                Map.entry("expected", "金额按既定口径计算并可复算。"),
                                Map.entry("evidence", "计算明细和来源数据。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-06"),
                                Map.entry("item", "兼容策略"),
                                Map.entry("expected", "拒绝、警告和忽略有明确实际影响。"),
                                Map.entry("evidence", "三类策略检查结果。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-07"),
                                Map.entry("item", "失败保护"),
                                Map.entry("expected", "失败不替换既有目标，不留下伪成功。"),
                                Map.entry("evidence", "故障输入和目标对照。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-08"),
                                Map.entry("item", "权限边界"),
                                Map.entry("expected", "越权查看、编辑和发布明确拒绝。"),
                                Map.entry("evidence", "角色操作矩阵。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-09"),
                                Map.entry("item", "取消与预算"),
                                Map.entry("expected", "取消及时收敛，超限停止并给出诊断。"),
                                Map.entry("evidence", "任务状态和预算配置。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-10"),
                                Map.entry("item", "备份恢复"),
                                Map.entry("expected", "实际备份可在隔离环境恢复。"),
                                Map.entry("evidence", "演练记录和验收结果。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-11"),
                                Map.entry("item", "使用培训"),
                                Map.entry("expected", "接收人能独立完成主要流程。"),
                                Map.entry("evidence", "培训签到及操作记录。"),
                                Map.entry("required", false),
                                Map.entry("priority", "建议")),
                        Map.<String, Object>ofEntries(
                                Map.entry("code", "V-12"),
                                Map.entry("item", "材料归档"),
                                Map.entry("expected", "已知限制、支持方式及责任明确。"),
                                Map.entry("evidence", "交接清单和归档编号。"),
                                Map.entry("required", true),
                                Map.entry("priority", "必需")))),
                Map.entry("files", List.of(
                        Map.<String, Object>ofEntries(
                                Map.entry("path", "config/"),
                                Map.entry("purpose", "项目设置与经过确认的运行参数。"),
                                Map.entry("retention", "按版本保留。"),
                                Map.entry("access", "管理员可写。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("path", "templates/"),
                                Map.entry("purpose", "XML 源码、资源引用和版本说明。"),
                                Map.entry("retention", "保留发布及必要历史。"),
                                Map.entry("access", "维护者按审批修改。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("path", "resources/"),
                                Map.entry("purpose", "字体、图片和许可清单。"),
                                Map.entry("retention", "跟随引用版本保留。"),
                                Map.entry("access", "受控登记。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("path", "jobs/"),
                                Map.entry("purpose", "任务快照、结果和兼容报告。"),
                                Map.entry("retention", "按项目保留期处理。"),
                                Map.entry("access", "按任务授权。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("path", "temporary/"),
                                Map.entry("purpose", "当前任务拥有的临时材料。"),
                                Map.entry("retention", "任务结束后受控清理。"),
                                Map.entry("access", "仅执行服务。")),
                        Map.<String, Object>ofEntries(
                                Map.entry("path", "backups/"),
                                Map.entry("purpose", "版本化备份及恢复清单。"),
                                Map.entry("retention", "按备份制度执行。"),
                                Map.entry("access", "管理员与审计人员。")))),
                Map.entry("support", Map.<String, Object>ofEntries(
                        Map.entry("contact", "support@example.invalid"),
                        Map.entry("report", "提交版本、任务编号、操作步骤、预期与实际结果及脱敏后的最小输入。不要附带凭据、完整业务资料或不必要的个人信息。"),
                        Map.entry("closure", "验收负责人：____________    验收日期：____________\n恢复点编号：____________    遗留问题清单：____________"))));
    }
}
