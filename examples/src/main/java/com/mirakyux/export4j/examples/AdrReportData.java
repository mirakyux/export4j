// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Shared fictional data and explicit fonts for the Java and XML adverse-reaction report examples. */
final class AdrReportData {
    private AdrReportData() { }

    static DocumentEngine engine() throws IOException {
        return engine("ZCOOLXiaoWei-Regular.ttf");
    }

    static DocumentEngine engine(String chineseFont) throws IOException {
        var chinese = AuthoringExample.face(chineseFont);
        return DocumentEngine.builder(FontGroup.strict(List.of(
                        AuthoringExample.face("LiberationSans-Regular.ttf"), chinese)))
                .fontRole("bold", FontGroup.strict(List.of(
                        AuthoringExample.face("LiberationSans-Bold.ttf"), chinese)))
                .build();
    }

    /** Immutable binding tree; all people, products, institutions, dates and identifiers are fictional. */
    static Map<String, Object> data() {
        return Map.of(
                "report", Map.ofEntries(
                        Map.entry("serious", "□"),
                        Map.entry("overseas", "□"),
                        Map.entry("initial", "√"),
                        Map.entry("followup", "□"),
                        Map.entry("caseId", "DEMO-ADR-001"),
                        Map.entry("source", "医疗机构（虚构）"),
                        Map.entry("concomitantSummary", "未报告合并用药。"),
                        Map.entry("devices", "暂无"),
                        Map.entry("country", "中国"),
                        Map.entry("firstReceived", "2025-06-08"),
                        Map.entry("lastReceived", "2025-06-10"),
                        Map.entry("holder", "示例制药有限公司（虚构）"),
                        Map.entry("contact", "示例药物警戒部门"),
                        Map.entry("contactDetails", "未提供"),
                        Map.entry("remarks", "所有病例、药品、机构和时间均为虚构，仅展示可编辑表单版式。"),
                        Map.entry("medicalSource", "√"),
                        Map.entry("address", "示例地址（虚构）")),
                "patient", Map.ofEntries(
                        Map.entry("name", "示例患者甲"),
                        Map.entry("sex", "男"),
                        Map.entry("birthDate", "1960-01-01"),
                        Map.entry("age", "65 岁"),
                        Map.entry("country", "中国"),
                        Map.entry("ethnicity", "汉族"),
                        Map.entry("height", "170"),
                        Map.entry("weight", "68"),
                        Map.entry("telephone", "未提供"),
                        Map.entry("institution", "示例医院"),
                        Map.entry("recordId", "DEMO-001"),
                        Map.entry("smoking", "无"),
                        Map.entry("drinking", "无"),
                        Map.entry("allergy", "不详"),
                        Map.entry("otherHistory", "未报告"),
                        Map.entry("noSmoking", "√"),
                        Map.entry("noDrinking", "√"),
                        Map.entry("allergyUnknown", "√")),
                "diseases", List.of(Map.of(
                        "number", "1",
                        "name", "示例疾病甲（DEMO01）",
                        "start", "2025-05-01",
                        "end", "未结束",
                        "ongoing", "是")),
                "suspectDrugs", List.of(Map.ofEntries(
                        Map.entry("number", "1"),
                        Map.entry("approval", "虚构批准文号DEMO000001"),
                        Map.entry("brand", ""),
                        Map.entry("name", "示例胶囊甲"),
                        Map.entry("form", "胶囊"),
                        Map.entry("strength", "每粒装0.25g"),
                        Map.entry("holder", "示例制药有限公司"),
                        Map.entry("batch", "250601"),
                        Map.entry("expiry", ""),
                        Map.entry("route", "口服"),
                        Map.entry("dose", "0.5g"),
                        Map.entry("frequency", "每日两次"),
                        Map.entry("start", "2025-06-01"),
                        Map.entry("end", "2025-06-03"),
                        Map.entry("duration", "3 天"),
                        Map.entry("indication", "【示例疾病甲（DEMO01）】"),
                        Map.entry("special", ""),
                        Map.entry("action", ""))),
                "reaction", Map.ofEntries(
                        Map.entry("term", "示例皮疹"),
                        Map.entry("start", "2025 年 06 月 03 日"),
                        Map.entry("end", "2025 年 06 月 06 日"),
                        Map.entry("duration", "3（分 / 小时 / 天）"),
                        Map.entry("seriousness", "非严重"),
                        Map.entry("unexpected", "√"),
                        Map.entry("dechallenge", "是"),
                        Map.entry("rechallenge", "未再次使用"),
                        Map.entry("outcome", "痊愈"),
                        Map.entry("reporterAssessment", "可能有关"),
                        Map.entry("holderAssessment", "可能有关"),
                        Map.entry("description", "患者因示例疾病甲使用示例胶囊甲后出现散在皮疹，停药后经对症处理好转。此处为虚构经过，仅供模板演示。"),
                        Map.entry("nonSerious", "√")),
                "tests", List.of(),
                "reporter", Map.of(
                        "name", "示例报告人",
                        "occupation", "医生",
                        "institution", "示例医院（虚构）",
                        "contact", "未提供",
                        "doctor", "√"));
    }
}
