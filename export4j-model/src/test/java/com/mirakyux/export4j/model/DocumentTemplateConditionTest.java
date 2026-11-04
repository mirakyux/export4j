// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentTemplateConditionTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);

    @Test void orderedAlternativesRenderExactlyOneBranchAndAllowNestedConditions() {
        var choices = choice("status");
        var template = DocumentTemplate.builder().node(new DocumentTemplate.When("kind", TemplateCondition.Operator.EQ, "report",
                List.of(choices), List.of(text("outside", "Outside")))).build();
        for (int status = 1; status <= 4; status++) {
            assertEquals(List.of(status == 4 ? "Other" : "Value " + status), template.render(Map.of("kind", "report", "status", status))
                    .paragraphs().stream().map(Paragraph::text).toList());
        }
        assertEquals(List.of("Outside"), template.render(Map.of("kind", "other")).paragraphs().stream().map(Paragraph::text).toList());
        var firstWins = DocumentTemplate.builder().node(new DocumentTemplate.When("status", TemplateCondition.Operator.EQ, "1",
                List.of(text("first", "First")), List.of(new DocumentTemplate.When("status", TemplateCondition.Operator.EQ, "1",
                        List.of(text("second", "Second")), List.of(text("last", "Last")))))).build();
        assertEquals(List.of("First"), firstWins.render(Map.of("status", 1)).paragraphs().stream().map(Paragraph::text).toList());
        var shortCircuit = DocumentTemplate.builder().node(new DocumentTemplate.When("status", TemplateCondition.Operator.EQ, "1",
                List.of(text("first", "First")), List.of(new DocumentTemplate.When("missing", TemplateCondition.Operator.EQ, "2",
                        List.of(text("second", "Second")), List.of(text("last", "Last")))))).build();
        assertEquals("First", shortCircuit.render(Map.of("status", 1)).paragraphs().getFirst().text());
    }

    @Test void exactNumericPredicatesDoNotRoundLargeIntegersOrDecimalScales() {
        assertEquals("Yes", render(TemplateCondition.Operator.EQ, "9007199254740993", new BigInteger("9007199254740993")));
        assertEquals("No", render(TemplateCondition.Operator.EQ, "9007199254740993", new BigInteger("9007199254740992")));
        assertEquals("Yes", render(TemplateCondition.Operator.EQ, "1", new BigDecimal("1.00000000000000000000")));
        assertEquals("Yes", render(TemplateCondition.Operator.NE, "9007199254740992", new BigInteger("9007199254740993")));
        assertEquals("Yes", render(TemplateCondition.Operator.GT, "9007199254740992", new BigInteger("9007199254740993")));
        assertEquals("No", render(TemplateCondition.Operator.GT, "1", new BigDecimal("1.000")));
        assertEquals("Yes", render(TemplateCondition.Operator.GE, "1e1000", new BigDecimal("10e999")));
        assertEquals("Yes", render(TemplateCondition.Operator.LT, "1.0000000000000000001", BigDecimal.ONE));
        assertEquals("Yes", render(TemplateCondition.Operator.LE, "1", BigDecimal.ONE));
    }

    @Test void textAndBooleanComparisonsKeepTheirActualTypesAndLegacyConstruction() {
        assertEquals("Yes", render(TemplateCondition.Operator.EQ, "01", "01"));
        assertEquals("No", render(TemplateCondition.Operator.EQ, "1", "01"));
        assertEquals("Yes", render(TemplateCondition.Operator.CONTAINS, "beta", "alpha-beta"));
        assertEquals("No", render(TemplateCondition.Operator.CONTAINS, "Beta", "alpha-beta"));
        assertEquals("Yes", render(TemplateCondition.Operator.EQ, "true", true));
        assertEquals("Yes", render(TemplateCondition.Operator.FALSE, "", false));
        var source = new DocumentTemplate.When("visible", List.of(text("yes", "Yes")), List.of(text("no", "No")));
        var template = DocumentTemplate.builder().node(source).build();
        assertEquals(TemplateCondition.Operator.TRUE, source.operator());
        assertEquals("Yes", template.render(Map.of("visible", true)).paragraphs().getFirst().text());
        assertEquals("No", template.render(Map.of("visible", false)).paragraphs().getFirst().text());
        var callbacks = DocumentTemplate.builder().when("visible", yes -> yes.when("number", TemplateCondition.Operator.EQ, "2",
                nested -> nested.block(new Paragraph("nested", "Two", FORMAT)), nested -> { }), no -> { }).build();
        assertEquals("Two", callbacks.render(Map.of("visible", true, "number", 2)).paragraphs().getFirst().text());
        var direct = DocumentTemplate.builder().when("number", TemplateCondition.Operator.EQ, "2",
                yes -> yes.block(new Paragraph("yes", "Two", FORMAT)), no -> no.block(new Paragraph("no", "Other", FORMAT))).build();
        assertEquals("Other", direct.render(Map.of("number", 3)).paragraphs().getFirst().text());
        var legacyCallbacks = DocumentTemplate.builder().when("visible", yes -> yes.when("second",
                nested -> nested.block(new Paragraph("legacy", "Legacy", FORMAT)), nested -> { }), no -> { }).build();
        assertEquals("Legacy", legacyCallbacks.render(Map.of("visible", true, "second", true)).paragraphs().getFirst().text());
    }

    @Test void invalidActualTypesAndComparisonValuesFailWithoutIncludingData() {
        for (var value : List.of("secret-text", List.of("secret-list"), Map.of("private", "secret-object"))) {
            var failure = assertThrows(DocumentTemplate.BindingException.class, () -> render(TemplateCondition.Operator.GT, "1", value));
            assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, failure.code());
            assertFalse(failure.getMessage().contains("secret"));
        }
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, assertThrows(DocumentTemplate.BindingException.class,
                () -> render(TemplateCondition.Operator.EQ, "not-a-number", 1)).code());
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, assertThrows(DocumentTemplate.BindingException.class,
                () -> render(TemplateCondition.Operator.EQ, "1", true)).code());
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, assertThrows(DocumentTemplate.BindingException.class,
                () -> render(TemplateCondition.Operator.TRUE, "", 1)).code());
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, assertThrows(DocumentTemplate.BindingException.class,
                () -> render(TemplateCondition.Operator.CONTAINS, "1", 1)).code());
        for (var value : List.of("NaN", "01", "1e10000", "Infinity", "secret")) {
            assertThrows(IllegalArgumentException.class, () -> new TemplateCondition(TemplateCondition.Operator.GT, value));
        }
        assertThrows(IllegalArgumentException.class, () -> new TemplateCondition(TemplateCondition.Operator.TRUE, "true"));
        assertThrows(IllegalArgumentException.class, () -> new TemplateCondition(TemplateCondition.Operator.EQ, "x".repeat(257)));
    }

    @Test void comparisonBranchesUseLexicalLoopValuesAndShareExpansionDepthBudget() {
        var template = DocumentTemplate.builder().each("items", "item", nodes -> nodes.node(choice("item.status"))).build();
        var result = template.render(Map.of("item", Map.of("status", 1), "items", List.of(Map.of("status", 2), Map.of("status", 4))));
        assertEquals(List.of("Value 2", "Other"), result.paragraphs().stream().map(Paragraph::text).toList());
        assertEquals(List.of("value-2@0", "other@1"), result.blocks().stream().map(Block::id).toList());
        var bounded = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(choice("status")),
                new DocumentTemplate.Limits(100, 100, 100, 1000, 1));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, assertThrows(DocumentTemplate.BindingException.class,
                () -> bounded.render(Map.of("status", 4))).code());
    }

    private static DocumentTemplate.When choice(String path) {
        return new DocumentTemplate.When(path, TemplateCondition.Operator.EQ, "1", List.of(text("value-1", "Value 1")),
                List.of(new DocumentTemplate.When(path, TemplateCondition.Operator.EQ, "2", List.of(text("value-2", "Value 2")),
                        List.of(new DocumentTemplate.When(path, TemplateCondition.Operator.EQ, "3", List.of(text("value-3", "Value 3")),
                                List.of(text("other", "Other")))))));
    }

    private static DocumentTemplate.ValueBlock text(String id, String value) {
        return new DocumentTemplate.ValueBlock(new Paragraph(id, value, FORMAT));
    }

    private static String render(TemplateCondition.Operator operator, String expected, Object actual) {
        return DocumentTemplate.builder().node(new DocumentTemplate.When("field", operator, expected,
                List.of(text("yes", "Yes")), List.of(text("no", "No")))).build()
                .render(Map.of("field", actual)).paragraphs().getFirst().text();
    }
}
