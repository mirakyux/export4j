// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fictional detailed commercial and project documents; monetary values are calculated before binding. */
final class OfficeDocumentData {
    private OfficeDocumentData() { }

    static Map<String, Object> data(String id) {
        return switch (id) {
            case "quotation" -> quotation();
            case "meeting-minutes" -> meetingMinutes();
            case "weekly-report" -> weeklyReport();
            default -> throw new IllegalArgumentException("Unknown office document example: " + id);
        };
    }

    private static Map<String, Object> quotation() {
        var items = List.of(
                line("01", "需求与治理", "业务访谈与现状调研", "3", "人日", "1600.00", "访谈业务、运营与技术代表，整理现状流程和约束。", "调研纪要、现状流程图", "关键参与方确认问题清单"),
                line("02", "需求与治理", "字段字典与数据分级", "2", "人日", "1600.00", "统一字段含义、必填规则和敏感信息处理要求。", "字段字典、数据分级表", "覆盖本轮全部输入输出字段"),
                line("03", "需求与治理", "方案与接口边界设计", "3", "人日", "1800.00", "定义模块职责、接口契约和失败处理边界。", "方案说明、接口清单", "评审问题关闭并留存结论"),
                line("04", "需求与治理", "项目计划与验收基线", "1", "人日", "1500.00", "拆分里程碑、责任人及可复现的验收场景。", "实施计划、验收清单", "双方确认范围及节点"),
                line("05", "核心实现", "模板目录与版本管理", "4", "人日", "1500.00", "管理模板标识、说明、版本和启停状态。", "管理页面、版本接口", "支持新增、修改及停用流程"),
                line("06", "核心实现", "字段映射与输入校验", "4", "人日", "1500.00", "实现字段映射、类型检查和缺省值处理。", "映射配置、校验规则", "错误返回含明确字段定位"),
                line("07", "核心实现", "文档生成任务管理", "5", "人日", "1600.00", "实现任务状态、有限重试与同步取消。", "任务接口、执行记录", "失败与取消不会伪造成功"),
                line("08", "核心实现", "批量处理与结果清单", "4", "人日", "1600.00", "支持受控批次及逐条结果追踪。", "批次入口、结果下载", "单条失败可独立诊断"),
                line("09", "核心实现", "权限与操作审计", "3", "人日", "1600.00", "按角色限制模板和结果访问并记录关键操作。", "权限配置、审计事件", "越权访问被明确拒绝"),
                line("10", "核心实现", "可观测性与问题定位", "2", "人日", "1600.00", "提供关联标识、错误分类和容量监测指标。", "运行指标、排查说明", "可定位一次失败的执行链路"),
                line("11", "接口与迁移", "统一身份接入", "2", "人日", "1700.00", "对接已确认的测试身份服务。", "身份适配、联调记录", "登录过期和权限变更可验证"),
                line("12", "接口与迁移", "业务数据查询接口", "3", "人日", "1700.00", "接入三类业务查询并隔离超时和分页。", "数据适配、契约样例", "返回字段与确认契约一致"),
                line("13", "接口与迁移", "文件存储与下载接入", "2", "人日", "1700.00", "对接客户提供的对象存储及临时下载授权。", "存储适配、下载校验", "临时授权到期后不可访问"),
                line("14", "接口与迁移", "历史模板迁移演练", "2", "人日", "1500.00", "迁移六份已确认模板并登记兼容损失。", "迁移记录、损失清单", "逐份核对字段与输出内容"),
                line("15", "质量与安全", "核心流程回归验证", "4", "人日", "1400.00", "覆盖正常、异常、取消及重复请求路径。", "回归记录、缺陷清单", "关键链路检查全部通过"),
                line("16", "质量与安全", "容量与资源验证", "2", "人日", "1600.00", "按约定批量及并发测量耗时和峰值资源。", "容量记录、推荐配置", "达到书面约定的容量基线"),
                line("17", "质量与安全", "输入与资源边界检查", "2", "人日", "1700.00", "验证非法输入、资源上限及失败时目标保护。", "边界检查、整改记录", "高优先问题完成修复复验"),
                line("18", "质量与安全", "备份与恢复演练", "1", "人日", "1500.00", "演练模板、配置和结果清单恢复步骤。", "恢复步骤、演练记录", "在测试环境完成恢复核对"),
                line("19", "交付与支持", "部署与回退准备", "2", "人日", "1500.00", "编制部署配置、检查清单及可执行回退步骤。", "部署包、回退说明", "测试环境实际演练通过"),
                line("20", "交付与支持", "操作培训", "2", "场", "1800.00", "分别面向模板维护人员和运行管理人员培训。", "培训材料、问题答复", "完成两场演示与操作练习"),
                line("21", "交付与支持", "试运行观察与问题关闭", "3", "人日", "1400.00", "跟踪试运行问题并按优先级关闭。", "问题台账、关闭证据", "无未处置的阻断问题"),
                line("22", "交付与支持", "成果移交与验收支持", "2", "人日", "1500.00", "整理源码、配置、文档和使用资源的许可清单。", "交付清单、验收纪要", "交付项有唯一版本及接收记录"));
        var subtotal = items.stream().map(item -> new BigDecimal(item.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var discount = subtotal.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
        var net = subtotal.subtract(discount);
        var tax = subtotal.multiply(new BigDecimal("0.06")).setScale(2, RoundingMode.HALF_UP);
        var discountedTax = net.multiply(new BigDecimal("0.06")).setScale(2, RoundingMode.HALF_UP);
        var grouped = new LinkedHashMap<String, BigDecimal>();
        var counts = new LinkedHashMap<String, Integer>();
        for (var item : items) {
            grouped.merge(item.get("group"), new BigDecimal(item.get("amount")), BigDecimal::add);
            counts.merge(item.get("group"), 1, Integer::sum);
        }
        var groups = new ArrayList<Map<String, String>>();
        grouped.forEach((name, total) -> groups.add(Map.of("name", name,
                "count", Integer.toString(counts.get(name)), "amount", money(total))));
        return Map.ofEntries(
            Map.entry("quote", Map.ofEntries(
                Map.entry("title", "业务文档工作台建设项目 · 商务报价书"),
                Map.entry("number", "DEMO-Q-2026-001"),
                Map.entry("issued", "2026-01-12"),
                Map.entry("validUntil", "2026-02-11"),
                Map.entry("currency", "人民币 / CNY"),
                Map.entry("subtotal", money(subtotal)),
                Map.entry("discountRate", "5%"),
                Map.entry("discount", money(discount)),
                Map.entry("net", money(net)),
                Map.entry("discountedTax", money(discountedTax)),
                Map.entry("payable", money(net.add(discountedTax))),
                Map.entry("taxRate", "6%"),
                Map.entry("tax", money(tax)),
                Map.entry("total", money(subtotal.add(tax))),
                Map.entry("summary", "本方案覆盖需求梳理、核心实现、接口适配、质量验证及交付支持。报价依据已确认的六类文档场景与三类业务接口，采用分阶段交付和可复现的验收清单控制范围。"),
                Map.entry("duration", "计划周期 10 周；客户侧资料、测试环境及接口条件按下列前提就绪后启动。")
            )),
            Map.entry("seller", Map.ofEntries(
                Map.entry("name", "示例技术服务有限公司（虚构）"),
                Map.entry("contact", "示例商务部"),
                Map.entry("email", "sales@example.com"),
                Map.entry("address", "示例市示例路 100 号（虚构）")
            )),
            Map.entry("buyer", Map.ofEntries(
                Map.entry("name", "示例采购单位（虚构）"),
                Map.entry("contact", "示例项目负责人"),
                Map.entry("email", "buyer@example.com"),
                Map.entry("project", "示例业务系统文档工作台建设")
            )),
            Map.entry("items", items),
            Map.entry("groupTotals", List.copyOf(groups)),
            Map.entry("deliveries", List.of(
                Map.ofEntries(
                    Map.entry("phase", "D1 / 范围基线"),
                    Map.entry("date", "第 1—2 周"),
                    Map.entry("output", "需求清单、字段字典、接口及验收基线"),
                    Map.entry("evidence", "双方评审纪要、范围确认版本")
                ),
                Map.ofEntries(
                    Map.entry("phase", "D2 / 可运行主链路"),
                    Map.entry("date", "第 3—5 周"),
                    Map.entry("output", "模板管理、字段绑定和单任务生成"),
                    Map.entry("evidence", "演示脚本、已知限制及检查记录")
                ),
                Map.ofEntries(
                    Map.entry("phase", "D3 / 接口及批处理"),
                    Map.entry("date", "第 6—7 周"),
                    Map.entry("output", "身份、存储及业务接口，批次结果清单"),
                    Map.entry("evidence", "联调记录、错误及取消路径验证")
                ),
                Map.ofEntries(
                    Map.entry("phase", "D4 / 质量与演练"),
                    Map.entry("date", "第 8—9 周"),
                    Map.entry("output", "回归、容量、输入边界及恢复演练"),
                    Map.entry("evidence", "缺陷关闭证据、资源测量及恢复记录")
                ),
                Map.ofEntries(
                    Map.entry("phase", "D5 / 验收移交"),
                    Map.entry("date", "第 10 周"),
                    Map.entry("output", "部署包、文档、培训与成果移交"),
                    Map.entry("evidence", "正式交付清单、验收及接收纪要")
                )
            )),
            Map.entry("sla", List.of(
                Map.ofEntries(
                    Map.entry("level", "P1 / 阻断"),
                    Map.entry("trigger", "核心生成流程持续不可用且无可用替代方案"),
                    Map.entry("response", "工作时段 1 小时内响应"),
                    Map.entry("target", "4 小时内给出处置或绕行方案"),
                    Map.entry("boundary", "修复时间按实际根因评估并持续同步")
                ),
                Map.ofEntries(
                    Map.entry("level", "P2 / 主要"),
                    Map.entry("trigger", "受支持流程异常，但存在可用绕行方式"),
                    Map.entry("response", "工作时段 4 小时内响应"),
                    Map.entry("target", "1 个工作日内给出处理计划"),
                    Map.entry("boundary", "不承诺第三方服务故障的恢复时限")
                ),
                Map.ofEntries(
                    Map.entry("level", "P3 / 一般"),
                    Map.entry("trigger", "界面、说明或局部体验问题"),
                    Map.entry("response", "1 个工作日内响应"),
                    Map.entry("target", "纳入双方确认的修订批次"),
                    Map.entry("boundary", "不影响已确认的核心流程验收")
                )
            )),
            Map.entry("payments", List.of(
                Map.ofEntries(
                    Map.entry("stage", "启动款"),
                    Map.entry("percent", "30%"),
                    Map.entry("condition", "范围基线确认、项目启动资料及付款通知齐备后支付。")
                ),
                Map.ofEntries(
                    Map.entry("stage", "阶段款"),
                    Map.entry("percent", "40%"),
                    Map.entry("condition", "完成 D3 演示，接口及批处理交付材料经确认后支付。")
                ),
                Map.ofEntries(
                    Map.entry("stage", "验收款"),
                    Map.entry("percent", "30%"),
                    Map.entry("condition", "D5 验收通过，资料移交与接收记录完成后支付。")
                )
            )),
            Map.entry("assumptions", List.of(
                Map.ofEntries(
                    Map.entry("text", "客户指定一名有决策权限的项目联系人，业务问题通常在两个工作日内集中答复；重要范围调整保留书面结论。")
                ),
                Map.ofEntries(
                    Map.entry("text", "客户提供可用测试环境、测试账号及已确认接口文档。全部演示数据使用虚构或经处理的数据，真实业务数据的合法提供与授权由客户负责。")
                ),
                Map.ofEntries(
                    Map.entry("text", "容量验证基于约定的文档复杂度、批量和机器配置；超出基线的高并发、超长表格或特殊字体需要另行评估。")
                )
            )),
            Map.entry("exclusions", List.of(
                Map.ofEntries(
                    Map.entry("text", "本报价不包含第三方软件许可、云资源费用、商用证书、硬件采购及长期生产运维。相关采购由客户单独安排。")
                ),
                Map.ofEntries(
                    Map.entry("text", "不包含未列明的历史数据清洗、扫描件识别、任意 Office 文档无损转换及法律或监管合规认证。")
                ),
                Map.ofEntries(
                    Map.entry("text", "新增外部接口、额外文档类型、重大流程变更及超出试运行期的持续支持，须另行确认工作量、费用和时间。")
                )
            )),
            Map.entry("terms", List.of(
                Map.ofEntries(
                    Map.entry("text", "明细单价为未税价，标准含税合计按演示税率 6% 计算。另列 5% 项目优惠测算供商务确认；未经书面确认不替代标准报价，实际税务处理以适用规则及合同为准。")
                ),
                Map.ofEntries(
                    Map.entry("text", "报价有效期至 2026-02-11。到期后或项目范围、前提条件发生实质变化时，双方重新确认；本报价不直接替代正式合同。")
                ),
                Map.ofEntries(
                    Map.entry("text", "交付物以清单中的版本和接收记录为准。涉及第三方资源时提供来源及许可说明，双方原有工具和通用知识按合同约定保留。")
                )
            )),
            Map.entry("confirmation", Map.ofEntries(
                Map.entry("scope", "确认所选交付范围、接口和文档数量；无未说明的关键依赖。"),
                Map.entry("commercial", "确认金额、折扣、税额、付款节点与报价有效期。"),
                Map.entry("signature", "报价方授权代表：________________    客户授权代表：________________"),
                Map.entry("date", "确认日期：________________        确认日期：________________")
            ))
        );
    }

    private static Map<String, String> line(String number, String group, String name, String quantity,
            String unit, String price, String description, String deliverable, String acceptance) {
        var total = new BigDecimal(quantity).multiply(new BigDecimal(price));
        return Map.of("number", number, "group", group, "name", name, "quantity", quantity, "unit", unit,
                "unitPrice", money(new BigDecimal(price)), "amount", money(total), "description", description,
                "deliverable", deliverable, "acceptance", acceptance);
    }

    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }

    private static Map<String, Object> meetingMinutes() {
        return Map.ofEntries(
            Map.entry("meeting", Map.ofEntries(
                Map.entry("title", "文档工作台交付评审 · 会议纪要"),
                Map.entry("number", "DEMO-M-2026-003"),
                Map.entry("date", "2026-01-12"),
                Map.entry("time", "09:30—11:30"),
                Map.entry("location", "示例协作会议室 / 线上同步（虚构）"),
                Map.entry("chair", "示例负责人甲"),
                Map.entry("recorder", "示例记录人乙"),
                Map.entry("participants", "项目管理、业务、研发、测试、运维及数据代表；完整角色与责任如下。"),
                Map.entry("purpose", "确认阶段交付范围、关键技术方案、验收门槛及跨组依赖，并形成可追踪的决策与行动台账。"),
                Map.entry("nextMeeting", "2026-01-19 09:30，复核联调、容量和验收准备结果。")
            )),
            Map.entry("attendees", List.of(
                Map.ofEntries(
                    Map.entry("name", "示例负责人甲"),
                    Map.entry("role", "项目负责人"),
                    Map.entry("responsibility", "范围、优先级及跨组升级"),
                    Map.entry("attendance", "全程")
                ),
                Map.ofEntries(
                    Map.entry("name", "示例记录人乙"),
                    Map.entry("role", "项目协调"),
                    Map.entry("responsibility", "纪要确认、行动跟踪及材料归档"),
                    Map.entry("attendance", "全程")
                ),
                Map.ofEntries(
                    Map.entry("name", "示例业务丙"),
                    Map.entry("role", "业务代表"),
                    Map.entry("responsibility", "字段口径、业务流程及验收确认"),
                    Map.entry("attendance", "全程")
                ),
                Map.ofEntries(
                    Map.entry("name", "示例研发丁"),
                    Map.entry("role", "研发负责人"),
                    Map.entry("responsibility", "方案边界、接口实现及修复计划"),
                    Map.entry("attendance", "全程")
                ),
                Map.ofEntries(
                    Map.entry("name", "示例测试戊"),
                    Map.entry("role", "测试负责人"),
                    Map.entry("responsibility", "检查清单、缺陷证据及回归"),
                    Map.entry("attendance", "全程")
                ),
                Map.ofEntries(
                    Map.entry("name", "示例运维己"),
                    Map.entry("role", "运维代表"),
                    Map.entry("responsibility", "部署、监控、恢复及容量基线"),
                    Map.entry("attendance", "专题")
                ),
                Map.ofEntries(
                    Map.entry("name", "示例数据庚"),
                    Map.entry("role", "数据代表"),
                    Map.entry("responsibility", "数据字典、虚构测试数据及授权边界"),
                    Map.entry("attendance", "专题")
                )
            )),
            Map.entry("agenda", List.of(
                Map.ofEntries(
                    Map.entry("number", "01"),
                    Map.entry("topic", "阶段成果与范围基线"),
                    Map.entry("owner", "示例负责人甲"),
                    Map.entry("summary", "主链路已可演示；补齐异常场景与交付证据。"),
                    Map.entry("discussion", "已完成模板目录、字段绑定和单任务生成。业务代表要求保留字段来源定位，测试代表指出仅正常路径通过不能作为整体完成依据。对新增扫描识别需求，会议确认其与本轮预算及技术边界不同。"),
                    Map.entry("conclusion", "本轮继续采用已签认的六类文档和三类接口；新增识别需求登记候选清单，不进入当前验收。")
                ),
                Map.ofEntries(
                    Map.entry("number", "02"),
                    Map.entry("topic", "批次失败与恢复策略"),
                    Map.entry("owner", "示例研发丁"),
                    Map.entry("summary", "单条失败独立记录，重试不得覆盖成功项。"),
                    Map.entry("discussion", "比较整批回滚与逐条结果两种方式。业务侧需要保留部分成功结果，运维侧关注取消后的资源释放，测试侧要求能够证明一次失败不会伪造整批成功。"),
                    Map.entry("conclusion", "采用逐条结果清单；每次重试有独立执行标识，旧结果保留且状态可解释。")
                ),
                Map.ofEntries(
                    Map.entry("number", "03"),
                    Map.entry("topic", "数据及访问边界"),
                    Map.entry("owner", "示例数据庚"),
                    Map.entry("summary", "测试使用虚构数据，下载授权有明确到期时间。"),
                    Map.entry("discussion", "当前接口样例中部分字段含义与页面名称不一致。会议逐项确认姓名、日期及附件来源的统一字段代码，避免通过展示名称推测协议；同时要求所有临时下载授权具备最短必要范围。"),
                    Map.entry("conclusion", "由数据代表冻结字段字典；越权、过期下载和非法资源路径列入阻断验收清单。")
                ),
                Map.ofEntries(
                    Map.entry("number", "04"),
                    Map.entry("topic", "容量与交付环境"),
                    Map.entry("owner", "示例运维己"),
                    Map.entry("summary", "按确认配置和文档复杂度测量，报告实际边界。"),
                    Map.entry("discussion", "讨论单任务耗时、批量吞吐及内存峰值的不同口径。现有小样本不能证明持续高负载能力，且生产配置尚有一项存储配额待确认。"),
                    Map.entry("conclusion", "固定验证输入、并发及机器配置，分别报告耗时、进程资源和临时磁盘，配置变更后复核。")
                ),
                Map.ofEntries(
                    Map.entry("number", "05"),
                    Map.entry("topic", "验收与移交安排"),
                    Map.entry("owner", "示例测试戊"),
                    Map.entry("summary", "先关闭阻断问题，再组织业务验收演示。"),
                    Map.entry("discussion", "逐项核对生成结果、接口错误、取消、权限与恢复证据。业务人员提出操作说明应覆盖失败恢复，项目协调提出将源码、配置、字体许可与已知限制归入同一版本清单。"),
                    Map.entry("conclusion", "周五预演、下周正式验收；未关闭的阻断问题须给出明确影响、负责人及复验计划。")
                )
            )),
            Map.entry("options", List.of(
                Map.ofEntries(
                    Map.entry("option", "A / 整批事务回滚"),
                    Map.entry("benefit", "结果状态简单，便于集中判断。"),
                    Map.entry("cost", "一条失败导致已成功项丢失，长批次恢复代价较大。"),
                    Map.entry("result", "本轮不采用")
                ),
                Map.ofEntries(
                    Map.entry("option", "B / 逐条结果清单"),
                    Map.entry("benefit", "保留有效结果，支持定位与受控重试。"),
                    Map.entry("cost", "需要更清晰的执行标识、状态及结果所有权。"),
                    Map.entry("result", "本轮采用")
                ),
                Map.ofEntries(
                    Map.entry("option", "C / 无界自动重试"),
                    Map.entry("benefit", "短时故障可能由系统自动恢复。"),
                    Map.entry("cost", "无法控制成本、重复输出及失败终止边界。"),
                    Map.entry("result", "明确拒绝")
                )
            )),
            Map.entry("decisions", List.of(
                Map.ofEntries(
                    Map.entry("id", "DEC-01"),
                    Map.entry("text", "本轮范围保持六类文档及三类接口，新增需求另行评估。"),
                    Map.entry("owner", "示例负责人甲"),
                    Map.entry("status", "已确认")
                ),
                Map.ofEntries(
                    Map.entry("id", "DEC-02"),
                    Map.entry("text", "批次采用逐条状态与结果清单，禁止无界自动重试。"),
                    Map.entry("owner", "示例研发丁"),
                    Map.entry("status", "已确认")
                ),
                Map.ofEntries(
                    Map.entry("id", "DEC-03"),
                    Map.entry("text", "虚构测试数据、字段字典和下载权限作为验收前置。"),
                    Map.entry("owner", "示例数据庚"),
                    Map.entry("status", "已确认")
                ),
                Map.ofEntries(
                    Map.entry("id", "DEC-04"),
                    Map.entry("text", "容量结论必须附配置和输入规模，不推广为无限容量。"),
                    Map.entry("owner", "示例运维己"),
                    Map.entry("status", "已确认")
                )
            )),
            Map.entry("actions", List.of(
                Map.ofEntries(
                    Map.entry("number", "A-01"),
                    Map.entry("task", "冻结本轮范围与接口清单"),
                    Map.entry("owner", "示例负责人甲"),
                    Map.entry("due", "2026-01-13"),
                    Map.entry("status", "进行中")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-02"),
                    Map.entry("task", "确认字段字典及缺省规则"),
                    Map.entry("owner", "示例数据庚"),
                    Map.entry("due", "2026-01-13"),
                    Map.entry("status", "进行中")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-03"),
                    Map.entry("task", "补齐逐条结果与执行标识"),
                    Map.entry("owner", "示例研发丁"),
                    Map.entry("due", "2026-01-14"),
                    Map.entry("status", "进行中")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-04"),
                    Map.entry("task", "验证取消后的资源释放"),
                    Map.entry("owner", "示例测试戊"),
                    Map.entry("due", "2026-01-14"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-05"),
                    Map.entry("task", "验证失败时旧结果保持"),
                    Map.entry("owner", "示例测试戊"),
                    Map.entry("due", "2026-01-14"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-06"),
                    Map.entry("task", "复核过期下载和越权拒绝"),
                    Map.entry("owner", "示例研发丁"),
                    Map.entry("due", "2026-01-15"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-07"),
                    Map.entry("task", "整理虚构接口联调数据"),
                    Map.entry("owner", "示例数据庚"),
                    Map.entry("due", "2026-01-14"),
                    Map.entry("status", "进行中")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-08"),
                    Map.entry("task", "确认存储配额与清理规则"),
                    Map.entry("owner", "示例运维己"),
                    Map.entry("due", "2026-01-14"),
                    Map.entry("status", "待确认")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-09"),
                    Map.entry("task", "执行约定并发容量验证"),
                    Map.entry("owner", "示例运维己"),
                    Map.entry("due", "2026-01-15"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-10"),
                    Map.entry("task", "复验本轮高优先缺陷"),
                    Map.entry("owner", "示例测试戊"),
                    Map.entry("due", "2026-01-15"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-11"),
                    Map.entry("task", "补齐操作失败恢复说明"),
                    Map.entry("owner", "示例记录人乙"),
                    Map.entry("due", "2026-01-15"),
                    Map.entry("status", "进行中")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-12"),
                    Map.entry("task", "汇总资源来源与许可清单"),
                    Map.entry("owner", "示例研发丁"),
                    Map.entry("due", "2026-01-15"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-13"),
                    Map.entry("task", "组织业务验收预演"),
                    Map.entry("owner", "示例业务丙"),
                    Map.entry("due", "2026-01-16"),
                    Map.entry("status", "待开始")
                ),
                Map.ofEntries(
                    Map.entry("number", "A-14"),
                    Map.entry("task", "归档纪要和版本交付材料"),
                    Map.entry("owner", "示例记录人乙"),
                    Map.entry("due", "2026-01-16"),
                    Map.entry("status", "待开始")
                )
            )),
            Map.entry("risks", List.of(
                Map.ofEntries(
                    Map.entry("id", "R-01"),
                    Map.entry("description", "外部存储配额确认延迟，可能推迟容量验证。"),
                    Map.entry("response", "周三前确认；逾期升级项目负责人。"),
                    Map.entry("owner", "示例运维己")
                ),
                Map.ofEntries(
                    Map.entry("id", "R-02"),
                    Map.entry("description", "字段语义未冻结会导致多次接口返工。"),
                    Map.entry("response", "按字段代码确认，变化进入变更登记。"),
                    Map.entry("owner", "示例数据庚")
                )
            )),
            Map.entry("pending", List.of(
                Map.ofEntries(
                    Map.entry("id", "OPEN-01"),
                    Map.entry("question", "客户试运行阶段的每日批量上限与保留周期。"),
                    Map.entry("owner", "示例业务丙"),
                    Map.entry("due", "2026-01-14")
                ),
                Map.ofEntries(
                    Map.entry("id", "OPEN-02"),
                    Map.entry("question", "正式验收代表名单与可用演示时段。"),
                    Map.entry("owner", "示例负责人甲"),
                    Map.entry("due", "2026-01-15")
                )
            )),
            Map.entry("attachments", List.of(
                Map.ofEntries(
                    Map.entry("id", "ATT-01"),
                    Map.entry("name", "范围及接口清单 v1.2"),
                    Map.entry("purpose", "记录本轮边界及后续候选需求。")
                ),
                Map.ofEntries(
                    Map.entry("id", "ATT-02"),
                    Map.entry("name", "验收检查清单 v0.9"),
                    Map.entry("purpose", "核对正常、失败、取消及权限路径。")
                ),
                Map.ofEntries(
                    Map.entry("id", "ATT-03"),
                    Map.entry("name", "容量验证方案 v0.3"),
                    Map.entry("purpose", "固定配置、输入规模及资源测量口径。")
                ),
                Map.ofEntries(
                    Map.entry("id", "ATT-04"),
                    Map.entry("name", "缺陷与行动台账快照"),
                    Map.entry("purpose", "会议结束后按责任人与期限持续跟踪。")
                )
            ))
        );
    }

    private static Map<String, Object> weeklyReport() {
        return Map.ofEntries(
            Map.entry("report", Map.ofEntries(
                Map.entry("title", "文档工作台建设项目 · 交付周报"),
                Map.entry("week", "2026 年第 03 周"),
                Map.entry("period", "2026-01-12 至 2026-01-16"),
                Map.entry("owner", "示例项目组（虚构）"),
                Map.entry("reviewer", "示例项目负责人"),
                Map.entry("status", "核心路径按计划，外部依赖需关注"),
                Map.entry("progress", "65%"),
                Map.entry("summary", "本周完成三类接口首轮联调及批次结果清单，核心交付范围保持稳定。验证发现两项权限与清理边界问题，已修复一项并安排复验；外部存储配额的确认时间是下周验收预演的主要依赖。")
            )),
            Map.entry("management", List.of(
                Map.ofEntries(
                    Map.entry("text", "交付：13 / 20 个核心流程已完成，较上周增加 3 个；完成不等于验收，仍需按检查清单收集真实输出与异常路径证据。")
                ),
                Map.ofEntries(
                    Map.entry("text", "质量：本周 40 项验证中 38 项通过，剩余项分别涉及过期下载授权和取消后的临时资源清理，均有责任人与复验日期。")
                ),
                Map.ofEntries(
                    Map.entry("text", "需要管理层协调：周二前冻结试运行存储配额；若未确认，则容量验收与试运行日期需要一起调整。")
                )
            )),
            Map.entry("health", List.of(
                Map.ofEntries(
                    Map.entry("dimension", "范围"),
                    Map.entry("status", "稳定"),
                    Map.entry("change", "无变化"),
                    Map.entry("evidence", "新增扫描识别请求已登记，未进入本轮。"),
                    Map.entry("owner", "示例负责人甲")
                ),
                Map.ofEntries(
                    Map.entry("dimension", "进度"),
                    Map.entry("status", "需关注"),
                    Map.entry("change", "较上周改善"),
                    Map.entry("evidence", "主链路推进，验收仍依赖存储配额。"),
                    Map.entry("owner", "示例协调乙")
                ),
                Map.ofEntries(
                    Map.entry("dimension", "质量"),
                    Map.entry("status", "需关注"),
                    Map.entry("change", "较上周改善"),
                    Map.entry("evidence", "高优先问题由 4 项降至 2 项。"),
                    Map.entry("owner", "示例测试戊")
                ),
                Map.ofEntries(
                    Map.entry("dimension", "预算"),
                    Map.entry("status", "受控"),
                    Map.entry("change", "无变化"),
                    Map.entry("evidence", "累计消耗低于批准预算，保留依赖缓冲。"),
                    Map.entry("owner", "示例财务己")
                ),
                Map.ofEntries(
                    Map.entry("dimension", "资源"),
                    Map.entry("status", "受控"),
                    Map.entry("change", "短期紧张"),
                    Map.entry("evidence", "测试与运维在验收周需错峰安排。"),
                    Map.entry("owner", "示例负责人甲")
                )
            )),
            Map.entry("metrics", List.of(
                Map.ofEntries(
                    Map.entry("name", "核心流程完成数"),
                    Map.entry("previous", "10 / 20"),
                    Map.entry("actual", "13 / 20"),
                    Map.entry("target", "20 / 20"),
                    Map.entry("trend", "增加 3 项"),
                    Map.entry("status", "按计划")
                ),
                Map.ofEntries(
                    Map.entry("name", "本周验证通过数"),
                    Map.entry("previous", "30 / 36"),
                    Map.entry("actual", "38 / 40"),
                    Map.entry("target", "40 / 40"),
                    Map.entry("trend", "通过率提高"),
                    Map.entry("status", "待复核 2 项")
                ),
                Map.ofEntries(
                    Map.entry("name", "待关闭问题数"),
                    Map.entry("previous", "7"),
                    Map.entry("actual", "3"),
                    Map.entry("target", "0"),
                    Map.entry("trend", "减少 4 项"),
                    Map.entry("status", "均已分配责任人")
                ),
                Map.ofEntries(
                    Map.entry("name", "阻断及高优先问题"),
                    Map.entry("previous", "4"),
                    Map.entry("actual", "2"),
                    Map.entry("target", "0"),
                    Map.entry("trend", "减少 2 项"),
                    Map.entry("status", "验收前必须关闭")
                ),
                Map.ofEntries(
                    Map.entry("name", "接口联调完成数"),
                    Map.entry("previous", "1 / 3"),
                    Map.entry("actual", "3 / 3"),
                    Map.entry("target", "3 / 3"),
                    Map.entry("trend", "增加 2 类"),
                    Map.entry("status", "待业务确认")
                ),
                Map.ofEntries(
                    Map.entry("name", "交付文档就绪数"),
                    Map.entry("previous", "4 / 9"),
                    Map.entry("actual", "7 / 9"),
                    Map.entry("target", "9 / 9"),
                    Map.entry("trend", "增加 3 份"),
                    Map.entry("status", "恢复与运维说明待补")
                )
            )),
            Map.entry("milestones", List.of(
                Map.ofEntries(
                    Map.entry("name", "需求范围确认"),
                    Map.entry("due", "2026-01-09"),
                    Map.entry("progress", "已完成"),
                    Map.entry("owner", "示例成员甲"),
                    Map.entry("acceptance", "范围清单及字段字典已确认。")
                ),
                Map.ofEntries(
                    Map.entry("name", "核心流程验证"),
                    Map.entry("due", "2026-01-16"),
                    Map.entry("progress", "95%"),
                    Map.entry("owner", "示例成员乙"),
                    Map.entry("acceptance", "剩余边界问题复验中。")
                ),
                Map.ofEntries(
                    Map.entry("name", "接口业务确认"),
                    Map.entry("due", "2026-01-20"),
                    Map.entry("progress", "待确认"),
                    Map.entry("owner", "示例业务丙"),
                    Map.entry("acceptance", "接口样例及业务口径签认。")
                ),
                Map.ofEntries(
                    Map.entry("name", "容量与恢复演练"),
                    Map.entry("due", "2026-01-21"),
                    Map.entry("progress", "准备中"),
                    Map.entry("owner", "示例运维丁"),
                    Map.entry("acceptance", "实际配置及恢复记录齐备。")
                ),
                Map.ofEntries(
                    Map.entry("name", "验收预演"),
                    Map.entry("due", "2026-01-22"),
                    Map.entry("progress", "未开始"),
                    Map.entry("owner", "示例测试戊"),
                    Map.entry("acceptance", "关键缺陷关闭、材料可追溯。")
                ),
                Map.ofEntries(
                    Map.entry("name", "交付验收"),
                    Map.entry("due", "2026-01-23"),
                    Map.entry("progress", "准备中"),
                    Map.entry("owner", "示例项目负责人"),
                    Map.entry("acceptance", "交付版本及接收清单确认。")
                )
            )),
            Map.entry("workstreams", List.of(
                Map.ofEntries(
                    Map.entry("name", "模板与生成"),
                    Map.entry("done", "完成模板目录和字段校验，补齐批次逐条结果及执行标识。"),
                    Map.entry("evidence", "六类文档样例和失败结果清单已归档，可按标识复查。"),
                    Map.entry("next", "修复边界问题后执行全量回归，核对取消及旧结果保护。"),
                    Map.entry("blocker", "本轮无新增范围；外部数据定义若变更需重新评估。"),
                    Map.entry("owner", "示例研发乙")
                ),
                Map.ofEntries(
                    Map.entry("name", "接口与数据"),
                    Map.entry("done", "三类接口完成技术联调，虚构数据覆盖正常及空值路径。"),
                    Map.entry("evidence", "请求响应契约和字段字典使用相同代码，差异逐项登记。"),
                    Map.entry("next", "组织业务代表确认字段语义，补齐过期权限与错误输入场景。"),
                    Map.entry("blocker", "接口依赖方需提供最终错误码及授权到期规则。"),
                    Map.entry("owner", "示例数据丙")
                ),
                Map.ofEntries(
                    Map.entry("name", "验证与容量"),
                    Map.entry("done", "完成 40 项验证，确认固定配置下的单任务基准。"),
                    Map.entry("evidence", "通过项有输入摘要、输出版本及异常说明，未测项独立列示。"),
                    Map.entry("next", "按最终存储配置执行批量容量验证，报告进程资源及临时盘。"),
                    Map.entry("blocker", "存储配额尚未冻结；不得将单任务基准替代批量验收。"),
                    Map.entry("owner", "示例测试戊")
                ),
                Map.ofEntries(
                    Map.entry("name", "运维与移交"),
                    Map.entry("done", "完成部署检查清单初稿，识别两项恢复操作缺口。"),
                    Map.entry("evidence", "已在测试环境执行一次回退，恢复结果已逐项核对。"),
                    Map.entry("next", "补齐恢复说明和清理策略，安排验收期值守与培训。"),
                    Map.entry("blocker", "测试与运维时间窗口需要协调，避免同时占用环境。"),
                    Map.entry("owner", "示例运维丁")
                )
            )),
            Map.entry("budget", List.of(
                Map.ofEntries(
                    Map.entry("category", "需求与方案"),
                    Map.entry("approved", "24000.00"),
                    Map.entry("spent", "21000.00"),
                    Map.entry("committed", "0.00"),
                    Map.entry("remaining", "3000.00"),
                    Map.entry("note", "范围已确认，预留必要澄清。")
                ),
                Map.ofEntries(
                    Map.entry("category", "开发与集成"),
                    Map.entry("approved", "76000.00"),
                    Map.entry("spent", "45000.00"),
                    Map.entry("committed", "16000.00"),
                    Map.entry("remaining", "15000.00"),
                    Map.entry("note", "阶段款按已完成工作登记。")
                ),
                Map.ofEntries(
                    Map.entry("category", "测试与交付"),
                    Map.entry("approved", "28000.00"),
                    Map.entry("spent", "11000.00"),
                    Map.entry("committed", "7000.00"),
                    Map.entry("remaining", "10000.00"),
                    Map.entry("note", "容量、恢复及验收仍在后续。")
                ),
                Map.ofEntries(
                    Map.entry("category", "风险准备"),
                    Map.entry("approved", "12000.00"),
                    Map.entry("spent", "0.00"),
                    Map.entry("committed", "0.00"),
                    Map.entry("remaining", "12000.00"),
                    Map.entry("note", "使用前需说明影响并审批。")
                )
            )),
            Map.entry("budgetSummary", Map.ofEntries(
                Map.entry("approved", "140000.00"),
                Map.entry("spent", "77000.00"),
                Map.entry("committed", "23000.00"),
                Map.entry("remaining", "40000.00"),
                Map.entry("basis", "金额为虚构未税管理口径；剩余预算 = 已批预算 − 已发生 − 已承诺，不等同可直接付款金额。")
            )),
            Map.entry("resources", List.of(
                Map.ofEntries(
                    Map.entry("role", "研发"),
                    Map.entry("planned", "3.0 人周"),
                    Map.entry("actual", "2.8 人周"),
                    Map.entry("next", "2.5 人周"),
                    Map.entry("constraint", "保留缺陷修复容量。")
                ),
                Map.ofEntries(
                    Map.entry("role", "测试"),
                    Map.entry("planned", "2.0 人周"),
                    Map.entry("actual", "2.0 人周"),
                    Map.entry("next", "2.5 人周"),
                    Map.entry("constraint", "验收前回归与预演集中。")
                ),
                Map.ofEntries(
                    Map.entry("role", "运维"),
                    Map.entry("planned", "1.0 人周"),
                    Map.entry("actual", "0.8 人周"),
                    Map.entry("next", "1.2 人周"),
                    Map.entry("constraint", "容量与恢复共用测试环境。")
                ),
                Map.ofEntries(
                    Map.entry("role", "业务与数据"),
                    Map.entry("planned", "1.0 人周"),
                    Map.entry("actual", "0.9 人周"),
                    Map.entry("next", "1.0 人周"),
                    Map.entry("constraint", "字段确认需要固定时间窗口。")
                )
            )),
            Map.entry("risks", List.of(
                Map.ofEntries(
                    Map.entry("id", "R-01"),
                    Map.entry("level", "中"),
                    Map.entry("description", "外部存储配额确认延迟可能推迟容量验收。"),
                    Map.entry("response", "周二前冻结配置，逾期升级并调整联动计划。"),
                    Map.entry("owner", "示例运维丁"),
                    Map.entry("due", "2026-01-20")
                ),
                Map.ofEntries(
                    Map.entry("id", "R-02"),
                    Map.entry("level", "中"),
                    Map.entry("description", "接口字段在业务确认后变化可能导致返工。"),
                    Map.entry("response", "逐字段签认，变更记录影响、费用与日期。"),
                    Map.entry("owner", "示例数据丙"),
                    Map.entry("due", "2026-01-20")
                ),
                Map.ofEntries(
                    Map.entry("id", "R-03"),
                    Map.entry("level", "低"),
                    Map.entry("description", "操作说明反馈不完整可能增加试运行咨询。"),
                    Map.entry("response", "集中收集业务问题并完成失败恢复章节。"),
                    Map.entry("owner", "示例协调乙"),
                    Map.entry("due", "2026-01-21")
                ),
                Map.ofEntries(
                    Map.entry("id", "R-04"),
                    Map.entry("level", "中"),
                    Map.entry("description", "验收周人员和环境冲突可能压缩回归时间。"),
                    Map.entry("response", "按半天安排环境窗口，关键验证优先。"),
                    Map.entry("owner", "示例负责人甲"),
                    Map.entry("due", "2026-01-19")
                )
            )),
            Map.entry("dependencies", List.of(
                Map.ofEntries(
                    Map.entry("id", "DEP-01"),
                    Map.entry("need", "试运行存储配额及保留周期"),
                    Map.entry("provider", "示例客户运维"),
                    Map.entry("due", "2026-01-20"),
                    Map.entry("fallback", "先跑已确认小规模控制组，不能替代最终验收。")
                ),
                Map.ofEntries(
                    Map.entry("id", "DEP-02"),
                    Map.entry("need", "业务字段与错误码签认"),
                    Map.entry("provider", "示例业务代表"),
                    Map.entry("due", "2026-01-20"),
                    Map.entry("fallback", "保留差异清单，未确认部分不标完成。")
                ),
                Map.ofEntries(
                    Map.entry("id", "DEP-03"),
                    Map.entry("need", "正式验收参与人员及时间"),
                    Map.entry("provider", "示例项目协调"),
                    Map.entry("due", "2026-01-21"),
                    Map.entry("fallback", "提前预演并提供可复现材料。")
                )
            )),
            Map.entry("issues", List.of(
                Map.ofEntries(
                    Map.entry("id", "ISS-01"),
                    Map.entry("issue", "过期下载授权仍可复用"),
                    Map.entry("priority", "高"),
                    Map.entry("owner", "示例研发乙"),
                    Map.entry("state", "修复完成，待独立复验。")
                ),
                Map.ofEntries(
                    Map.entry("id", "ISS-02"),
                    Map.entry("issue", "取消后临时目录清理不完整"),
                    Map.entry("priority", "高"),
                    Map.entry("owner", "示例运维丁"),
                    Map.entry("state", "处理中，周二复验。")
                ),
                Map.ofEntries(
                    Map.entry("id", "ISS-03"),
                    Map.entry("issue", "一份说明遗漏失败恢复步骤"),
                    Map.entry("priority", "一般"),
                    Map.entry("owner", "示例协调乙"),
                    Map.entry("state", "文档补齐中。")
                )
            )),
            Map.entry("changes", List.of(
                Map.ofEntries(
                    Map.entry("id", "CHG-01"),
                    Map.entry("request", "增加扫描件识别入口"),
                    Map.entry("impact", "涉及新输入类型、依赖及独立验证预算。"),
                    Map.entry("decision", "登记候选需求，本轮不实施。")
                ),
                Map.ofEntries(
                    Map.entry("id", "CHG-02"),
                    Map.entry("request", "延长试运行结果保留时间"),
                    Map.entry("impact", "增加存储配额及清理策略负担。"),
                    Map.entry("decision", "等待配额确认后评估。")
                ),
                Map.ofEntries(
                    Map.entry("id", "CHG-03"),
                    Map.entry("request", "增加验收失败恢复演示"),
                    Map.entry("impact", "属于已确认的交付证据补齐。"),
                    Map.entry("decision", "纳入本轮，不变更范围。")
                )
            )),
            Map.entry("nextWeek", List.of(
                Map.ofEntries(
                    Map.entry("text", "周一确认人员与环境窗口，周二冻结配额及业务字段签认，保留双方确认记录。")
                ),
                Map.ofEntries(
                    Map.entry("text", "关闭两项高优先问题并独立复验，执行生成、权限、取消、恢复和批次结果回归。")
                ),
                Map.ofEntries(
                    Map.entry("text", "基于最终配置执行容量与恢复演练，附输入规模、进程资源和未覆盖边界。")
                ),
                Map.ofEntries(
                    Map.entry("text", "周四组织验收预演，周五确认正式交付版本、资源许可、操作资料及后续支持责任。")
                )
            )),
            Map.entry("decisions", List.of(
                Map.ofEntries(
                    Map.entry("id", "ASK-01"),
                    Map.entry("decision", "确认外部配额未按期就绪时是否顺延试运行。"),
                    Map.entry("owner", "示例项目负责人"),
                    Map.entry("due", "2026-01-20")
                ),
                Map.ofEntries(
                    Map.entry("id", "ASK-02"),
                    Map.entry("decision", "批准验收周测试与运维的错峰资源安排。"),
                    Map.entry("owner", "示例部门负责人"),
                    Map.entry("due", "2026-01-19")
                ),
                Map.ofEntries(
                    Map.entry("id", "ASK-03"),
                    Map.entry("decision", "确认新增需求仅进入候选清单，不挤占缺陷关闭容量。"),
                    Map.entry("owner", "示例业务负责人"),
                    Map.entry("due", "2026-01-20")
                )
            ))
        );
    }
}
