// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocumentTemplateObjectBindingTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);

    @Test void allRenderEntrypointsBindMixedObjectsExactlyLikeMaps() {
        record Line(String name) { }
        record Order(String title, Customer customer, List<Object> lines, Map<String, ?> attributes, boolean visible) { }
        var template = DocumentTemplate.builder().metadata(new DocumentMetadata("${title}", ""))
                .when("visible", yes -> yes.each("lines", "line", rows -> rows.block(
                        new Paragraph("line", "${customer.name}: ${line.name} ${attributes.unit}", FORMAT))), no -> { }).build();
        var object = new Order("Invoice", new Customer("Ada"), List.of(new Line("one"), Map.of("name", "two")),
                Map.of("unit", "kg"), true);
        var map = Map.of("title", "Invoice", "customer", Map.of("name", "Ada"),
                "lines", List.of(Map.of("name", "one"), Map.of("name", "two")), "attributes", Map.of("unit", "kg"), "visible", true);
        assertEquals(template.render(map), template.render(object));
        assertEquals(template.renderWithPageRegions(map), template.renderWithPageRegions(object));
        assertEquals(template.renderSections(map), template.renderSections(object));
        assertEquals(template.renderComplete(map), template.renderComplete(object));
        assertEquals(List.of("Ada: one kg", "Ada: two kg"), template.render(object).paragraphs().stream().map(Paragraph::text).toList());
    }

    @Test void mapsThatAlsoImplementIterableRetainTheirExistingMapBindingSemantics() {
        class IterableMap extends HashMap<String, Object> implements Iterable<Object> {
            private static final long serialVersionUID = 1L;
            @Override public java.util.Iterator<Object> iterator() {
                throw new AssertionError("a Map must be traversed through its entries");
            }
        }
        record Box(Map<String, Object> data) { }
        var data = new IterableMap();
        data.put("value", "compatible");
        assertEquals("compatible", text("${value}").render(data).paragraphs().getFirst().text());
        assertEquals("compatible", text("${value}").render((Object) data).paragraphs().getFirst().text());
        assertEquals("compatible", text("${data.value}").render(new Box(data)).paragraphs().getFirst().text());
    }

    @Test void beanPropertiesUsePublicAccessorsInheritanceAcronymsAndBooleanPrecedence() {
        var bean = new Accessors();
        assertEquals("child/parent/https://example.com/false", text("${name}/${inherited}/${URL}/${ready}").render(bean)
                .paragraphs().getFirst().text());
        for (String excluded : List.of("class", "field", "static", "private", "argument", "fake", "nothing")) {
            assertCode(DocumentTemplate.BindingException.Code.MISSING_VALUE, () -> text("${" + excluded + "}").render(bean));
        }
    }

    @Test void recordUsesOnlyDeclaredComponentsAndReadsAnOverriddenComponentAccessor() {
        record Data(String value) {
            @Override public String value() { return value.toUpperCase(java.util.Locale.ROOT); }
            public String getUnexpected() { throw new AssertionError("not a record component"); }
        }
        assertEquals("VALUE", text("${value}").render(new Data("value")).paragraphs().getFirst().text());
        assertCode(DocumentTemplate.BindingException.Code.MISSING_VALUE, () -> text("${unexpected}").render(new Data("value")));
        record Empty() { }
        assertEquals("literal", text("literal").render(new Empty()).paragraphs().getFirst().text());
    }

    @Test void nonPublicDeclaringTypesCanExposePublicAccessorsWithoutReadingPrivateFields() {
        record Local(String value) { }
        assertEquals("record", text("${value}").render(new Local("record")).paragraphs().getFirst().text());
        assertEquals("bean", text("${name}").render(new Customer("bean")).paragraphs().getFirst().text());
    }

    @Test void gettersAreReadOncePerSnapshotAndLaterMutationsDoNotChangeReturnedDocuments() {
        var calls = new AtomicInteger();
        var values = new ArrayList<>(List.of("first"));
        var bean = new Object() {
            public List<String> getValues() { calls.incrementAndGet(); return values; }
        };
        var template = DocumentTemplate.builder().each("values", "value", rows -> rows.block(
                new Paragraph("row", "${value}/${value}", FORMAT))).build();
        var first = template.render(bean);
        assertEquals(1, calls.get());
        values.set(0, "second");
        assertEquals("first/first", first.paragraphs().getFirst().text());
        assertEquals("second/second", template.render(bean).paragraphs().getFirst().text());
        assertEquals(2, calls.get());
    }

    @Test void cyclesAcrossObjectsMapsAndListsFailExplicitly() {
        var bean = new Object() { public Object getSelf() { return this; } };
        var map = new HashMap<String, Object>();
        var list = new ArrayList<Object>();
        map.put("list", list); list.add(map);
        for (Object value : List.of(bean, map)) {
            assertCode(DocumentTemplate.BindingException.Code.INVALID_VALUE, () -> text("literal").render(value));
        }
    }

    @Test void sharedObjectsAreAllowedButEveryOccurrenceConsumesBudgetAndReadsItsOwnSnapshot() {
        var calls = new AtomicInteger();
        var child = new Object() { public String getValue() { return "v" + calls.incrementAndGet(); } };
        record Pair(Object a, Object b) { }
        var data = new Pair(child, child);
        var exact = text("${a.value}/${b.value}", new DocumentTemplate.Limits(5, 1, 1, 100, 8));
        assertEquals("v1/v2", exact.render(data).paragraphs().getFirst().text());
        calls.set(0);
        assertCode(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                () -> text("literal", new DocumentTemplate.Limits(4, 1, 1, 100, 8)).render(data));
        assertEquals(1, calls.get());
    }

    @Test void nodeBudgetIsCheckedBeforeInvokingTheNextGetter() {
        var calls = new AtomicInteger();
        var bean = new Object() { public String getValue() { calls.incrementAndGet(); return "value"; } };
        assertCode(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                () -> text("literal", new DocumentTemplate.Limits(1, 1, 1, 100, 8)).render(bean));
        assertEquals(0, calls.get());
    }

    @Test void depthBudgetIsCheckedBeforeInvokingTheNextNestedGetter() {
        var calls = new AtomicInteger();
        var bean = new Object() { public String getValue() { calls.incrementAndGet(); return "value"; } };
        record Box(Object child) { }
        assertCode(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                () -> text("literal", new DocumentTemplate.Limits(100, 1, 1, 100, 1)).render(new Box(bean)));
        assertEquals(0, calls.get());
        assertEquals("value", text("${child.value}", new DocumentTemplate.Limits(100, 1, 1, 100, 2))
                .render(new Box(bean)).paragraphs().getFirst().text());
    }

    @Test void propertyNamesConsumeTextBudgetBeforeTheNextGetterInStableOrder() {
        var order = new ArrayList<String>();
        var bean = new Object() {
            public String getZ() { order.add("z"); return "V"; }
            public String getA() { order.add("a"); return "V"; }
        };
        assertCode(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                () -> text("", new DocumentTemplate.Limits(100, 1, 1, 2, 8)).render(bean));
        assertEquals(List.of("a"), order);
        order.clear();
        text("", new DocumentTemplate.Limits(100, 1, 1, 4, 8)).render(bean);
        assertEquals(List.of("a", "z"), order);
    }

    @Test void allReadablePropertiesAreValidatedEvenWhenUnusedAndUnsupportedTypesDoNotGainConversion() {
        record Box(Object value) { }
        var values = new ArrayList<Object>(List.of(LocalDate.of(2026, 1, 1), Choice.ONE, new String[] { "x" }, Set.of("x"),
                Double.NaN, Float.POSITIVE_INFINITY, new UnknownNumber()));
        values.add(null);
        for (Object value : values) {
            assertCode(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, () -> text("literal").render(new Box(value)));
        }
        var calls = new AtomicInteger();
        var opaque = new Object() { @Override public String toString() { calls.incrementAndGet(); return "secret"; } };
        assertCode(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, () -> text("literal").render(new Box(opaque)));
        assertEquals(0, calls.get());
    }

    @Test void scalarAndCollectionRootsFailWithBindingErrors() {
        for (Object value : List.of("value", true, 1, List.of(Map.of("value", "x")), LocalDate.of(2026, 1, 1))) {
            assertCode(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, () -> text("literal").render(value));
        }
    }

    @Test void collectionRootsAreRejectedBeforeReadingTheirChildrenOrFollowingCycles() {
        var calls = new AtomicInteger();
        var child = new Object() {
            public String getValue() { calls.incrementAndGet(); throw new AssertionError("root children must not be read"); }
        };
        var cyclic = new ArrayList<Object>();
        cyclic.add(cyclic);
        assertCode(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, () -> text("literal").render(List.of(child)));
        assertCode(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, () -> text("literal").render(cyclic));
        assertEquals(0, calls.get());
    }

    @Test void applicationDateSubclassesRemainUnsupportedAtTheRootAndInNestedObjects() {
        var calls = new AtomicInteger();
        var date = new java.util.Date(0) {
            private static final long serialVersionUID = 1L;
            public String getValue() { calls.incrementAndGet(); throw new AssertionError("date must not be read as a Bean"); }
        };
        record Box(Object value) { }
        for (Object input : List.of(date, new Box(date), Map.of("value", date))) {
            assertCode(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, () -> text("literal").render(input));
        }
        assertEquals(0, calls.get());
    }

    @Test void runtimeAndCheckedGetterFailuresAreSanitizedWithoutRetainingTheApplicationCause() {
        var runtime = new Object() { public String getValue() { throw new IllegalStateException("private-document-text"); } };
        var checked = new Object() { public String getValue() throws IOException { throw new IOException("private-document-text"); } };
        for (Object bean : List.of(runtime, checked)) {
            var failure = assertCode(DocumentTemplate.BindingException.Code.INVALID_VALUE, () -> text("${value}").render(bean));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("private-document-text"));
            assertFalse(failure.toString().contains(bean.getClass().getName()));
        }
    }

    @Test void getterErrorsAndExecutionFailuresRetainTheirIdentity() {
        var error = new AssertionError("trusted application error");
        var execution = new ExecutionException(ExecutionException.Code.CANCELLED,
                new ExecutionOptions.Progress("BIND", "", -1, 0, 0));
        var broken = new Object() { public String getValue() { throw error; } };
        var cancelled = new Object() { public String getValue() { throw execution; } };
        assertSame(error, assertThrows(AssertionError.class, () -> text("${value}").render(broken)));
        assertSame(execution, assertThrows(ExecutionException.class, () -> text("${value}").render(cancelled)));
    }

    @Test void cancellationRequestedByAGetterIsObservedBeforeTheFollowingGetter() {
        var cancelled = new AtomicBoolean();
        var laterCalls = new AtomicInteger();
        var bean = new Object() {
            public String getA() { cancelled.set(true); return "value"; }
            public String getZ() { laterCalls.incrementAndGet(); return "later"; }
        };
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> text("literal").render(bean)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(0, laterCalls.get());
        ExecutionContext.checkpoint();
    }

    private static DocumentTemplate text(String expression) { return text(expression, DocumentTemplate.Limits.DEFAULT); }
    private static DocumentTemplate text(String expression, DocumentTemplate.Limits limits) {
        return DocumentTemplate.builder().limits(limits).block(new Paragraph("text", expression, FORMAT)).build();
    }
    private static DocumentTemplate.BindingException assertCode(DocumentTemplate.BindingException.Code code, Runnable action) {
        var failure = assertThrows(DocumentTemplate.BindingException.class, action::run);
        assertEquals(code, failure.code());
        return failure;
    }

    private static class Customer {
        private final String name;
        private Customer(String name) { this.name = name; }
        public String getName() { return name; }
    }
    private static class ParentAccessors {
        public Object getName() { return "parent-name"; }
        public String getInherited() { return "parent"; }
    }
    private static final class Accessors extends ParentAccessors {
        public String field = "ignored";
        @Override public String getName() { return "child"; }
        public String getURL() { return "https://example.com"; }
        public boolean isReady() { return false; }
        public Boolean getReady() { throw new AssertionError("isReady must win"); }
        public static String getStatic() { throw new AssertionError("static getter"); }
        private String getPrivate() { throw new AssertionError("private getter"); }
        public String getArgument(String argument) { throw new AssertionError(argument); }
        public String isFake() { throw new AssertionError("not boolean"); }
        public void getNothing() { throw new AssertionError("void"); }
    }
    private enum Choice { ONE }
    private static final class UnknownNumber extends Number {
        private static final long serialVersionUID = 1L;
        @Override public int intValue() { throw new AssertionError("no conversion"); }
        @Override public long longValue() { throw new AssertionError("no conversion"); }
        @Override public float floatValue() { throw new AssertionError("no conversion"); }
        @Override public double doubleValue() { throw new AssertionError("no conversion"); }
        @Override public String toString() { throw new AssertionError("no conversion"); }
    }
}
