// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.ParagraphFormat;

class XmlNumberedLevelsTest {
    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String item(String level, String restart) {
        return "<numbered id='item' list-id='steps' level='" + level + "' restart='" + restart + "'>Text</numbered>";
    }
    private static XmlTemplate compiler() { return XmlTemplate.builder().bodyFormat(ParagraphFormat.of(10)).build(); }

    @Test void everyStaticLevelOneThroughNineCompilesWithoutPrematurelyRequiringRenderedParents() {
        for (int level = 1; level <= 9; level++) {
            var document = compiler().compile(xml(item(Integer.toString(level), "0"))).render(Map.of());
            var item = assertInstanceOf(NumberedItem.class, document.blocks().getFirst());
            assertEquals(level, item.level()); assertEquals("steps", item.listId()); assertEquals("Text", item.text());
            assertEquals(ParagraphFormat.of(10), item.format());
        }
    }

    @Test void omittedLevelAndRestartRetainTheCompleteLegacyLevelOneModel() {
        var compiler = compiler();
        String implicit = xml("<numbered id='item' list-id='steps'><span color='#224488'>${text}</span></numbered>");
        String explicit = implicit.replace("list-id='steps'", "list-id='steps' level='1' restart='0'");
        assertEquals(compiler.compile(implicit).render(Map.of("text", "Legacy")), compiler.compile(explicit).render(Map.of("text", "Legacy")));
        var item = (NumberedItem) compiler.compile(implicit).render(Map.of("text", "Legacy")).blocks().getFirst();
        assertEquals(1, item.level()); assertEquals(0, item.restartAt()); assertEquals(0x224488, item.inlines().getFirst().style().rgb());
    }

    @Test void invalidStaticOrDynamicLevelsFailAtTheNumberedLeafInEveryControlPrototype() {
        for (String level : List.of("0", "10", "-1", "2147483648", "-2147483649", "99999999999999999999", "${level}", "2.0", "NaN", "SECRET")) {
            for (String content : List.of(item(level, "0"), "<if test='visible'><then>" + item(level, "0") + "</then><else><p>Safe</p></else></if>",
                    "<if test='visible'><then><p>Safe</p></then><else>" + item(level, "0") + "</else></if>",
                    "<each items='items' as='item'>" + item(level, "0") + "</each>")) {
                var failure = rejected(compiler(), xml(content), TemplateException.Code.INVALID_VALUE);
                assertEquals(Optional.empty(), failure.fragmentName());
            }
        }
        var located = rejected(compiler(), "<document version='1'>\n<p>Safe</p>\n" + item("10", "0") + "\n</document>", TemplateException.Code.INVALID_VALUE);
        assertEquals(3, located.line()); assertEquals(item("10", "0").indexOf('>') + 2, located.column());
    }

    @Test void invalidIncludedLevelReportsTheFragmentLeafRatherThanTheMainIncludeCallsite() {
        String part = "<fragment version='1'>\n<p id='before'>Safe</p>\n<if test='visible'><then><p>Safe</p></then><else>\n"
                + item("10", "0") + "\n</else></if>\n</fragment>";
        var compiler = XmlTemplate.builder().fragment("SECRET_part", part).build();
        var failure = rejected(compiler, "<document version='1'>\n<include ref='SECRET_part' prefix='copy'/>\n</document>", TemplateException.Code.INVALID_VALUE);
        assertEquals(Optional.of("SECRET_part"), failure.fragmentName()); assertEquals(4, failure.line());
        assertEquals(item("10", "0").indexOf('>') + 2, failure.column());
    }

    @Test void multiLevelNumberedContentRetainsItsExistingInlineAndContainerWhitelist() {
        for (String content : List.of("<page-number width='20'/>",
                "<number-reference target='parent' width='20'/>", "<image ref='logo' width='10' height='10'/>", "<p>Text</p>")) {
            rejected(compiler(), xml("<numbered id='item' list-id='steps' level='2'>" + content + "</numbered>"), TemplateException.Code.UNSUPPORTED_ELEMENT);
        }
        var linked = (NumberedItem) compiler().compile(xml("<numbered list-id='steps'><link href='https://example.com'>Text</link></numbered>"))
                .render(Map.of()).blocks().getFirst();
        assertEquals("https://example.com", linked.inlines().getFirst().hyperlink());
        rejected(compiler(), xml("<bullet level='10'>Text</bullet>"), TemplateException.Code.INVALID_VALUE);
        rejected(compiler(), xml("<table><columns><column weight='1'/></columns><row><cell>"
                + item("2", "0") + "</cell></row></table>"), TemplateException.Code.UNSUPPORTED_ELEMENT);
    }

    @Test void restartZeroAndSignedIntMaximumRemainValidWhileInvalidRestartsFailDuringCompile() {
        for (String restart : List.of("0", "1", "2147483647")) {
            var value = (NumberedItem) compiler().compile(xml(item("9", restart))).render(Map.of()).blocks().getFirst();
            assertEquals(Integer.parseInt(restart), value.restartAt());
        }
        for (String restart : List.of("-1", "2147483648", "${restart}", "1.5", "SECRET")) {
            rejected(compiler(), xml(item("2", restart)), TemplateException.Code.INVALID_VALUE);
        }
    }

    @Test void validHierarchicalSyntaxInHiddenAndZeroIterationBranchesIsPreservedUntilBinding() {
        String content = "<p id='safe'>Safe</p><if test='visible'><then>" + item("9", "0") + "</then></if>";
        var compiled = compiler().compile(xml(content));
        assertEquals(1, compiled.render(Map.of("visible", false)).blocks().size());
        assertEquals(2, compiled.render(Map.of("visible", true)).blocks().size());
        var loop = compiler().compile(xml("<p id='safe'>Safe</p><each items='items' as='item'>" + item("2", "0") + "</each>"));
        assertEquals(1, loop.render(Map.of("items", List.of())).blocks().size());
        var rendered = loop.render(Map.of("items", List.of("A", "B")));
        assertEquals(List.of("safe", "item@0", "item@1"), rendered.blocks().stream().map(value -> value.id()).toList());
        assertEquals(List.of("steps", "steps"), rendered.blocks().stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).map(NumberedItem::listId).toList());
    }

    @Test void hierarchicalLoopBindingStillEnforcesItsOwnOutputBlockBudgetBeforeLayout() {
        String source = xml("<numbered id='parent' list-id='steps'>Parent</numbered><each items='items' as='item'>"
                + "<numbered id='child' list-id='steps' level='2'>${item}</numbered></each>");
        var data = Map.of("items", List.of("Child"));
        var exact = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(1000, 2, 100, 1000, 32)).build().compile(source).render(data);
        assertEquals(List.of("parent", "child@0"), exact.blocks().stream().map(value -> value.id()).toList());
        var limited = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(1000, 1, 100, 1000, 32)).build().compile(source);
        var failure = assertThrows(DocumentTemplate.BindingException.class, () -> limited.render(data));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, failure.code()); assertEquals("blocks", failure.path());
    }

    private static TemplateException rejected(XmlTemplate compiler, String source, TemplateException.Code code) {
        var failure = assertThrows(TemplateException.class, () -> compiler.compile(source));
        assertEquals(code, failure.code()); assertTrue(failure.line() > 0); assertTrue(failure.column() > 0);
        assertFalse(failure.getMessage().contains("SECRET")); return failure;
    }
}
