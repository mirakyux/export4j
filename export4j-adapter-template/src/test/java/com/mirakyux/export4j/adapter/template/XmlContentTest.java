// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SourceContentParser;
import com.mirakyux.export4j.model.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Source binding must neither reinterpret ordinary data nor leak lexical defaults to siblings. */
class XmlContentTest {
    @Test void savingResolvedContentPreservesCaptionAndReferenceAppearance() throws Exception {
        var original = XmlTemplate.builder().build().compile("""
                <document version="1" role="accent" color="#224466">
                  <caption id="figure" kind="Figure">Styled caption</caption>
                  <cross-reference id="ref" kind="NUMBER" target="figure">See </cross-reference>
                </document>
                """).render(Map.of());
        var prepared = XmlTemplateWriter.from(original).prepare();
        assertTrue(prepared.preview().issues().isEmpty());
        var restored = prepared.bundle().compile().render(Map.of());
        assertEquals(original, restored);
        assertEquals("accent", ((Caption) restored.blocks().getFirst()).appearance().fontRole());
        assertEquals(0x224466, ((CrossReference) restored.blocks().get(1)).appearance().rgb());
    }

    @Test void staticAndFieldSourcesBindExactlyOnce() {
        var template = XmlTemplate.builder().build().compile("""
                <document version="1">
                  <content id="static">${secret}</content>
                  <content id="field" source="body"/>
                  <p id="old">${body}</p>
                </document>
                """);
        var document = template.render(Map.of("body", "literal ${secret}"));
        assertEquals("${secret}", ((Paragraph) document.blocks().get(0)).text());
        assertEquals("literal ${secret}", ((Paragraph) document.blocks().get(1)).text());
        assertEquals("literal ${secret}", ((Paragraph) document.blocks().get(2)).text());
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
    }

    @Test void eachPropertyInheritsAndExplicitBlackZeroAndFalseOverride() {
        var template = XmlTemplate.builder().contentOptions(options -> options.fontRole("body").rgb(0x123456)
                .fontSize(14).lineHeight(28).spaceAfter(9).keepTogether(true)).build().compile("""
                <document version="1">
                  <scope color="#000000" space-after="0" keep-together="false">
                    <content id="first">First</content>
                    <scope role="accent"><content id="nested">Nested</content></scope>
                  </scope>
                  <content id="sibling">Sibling</content>
                </document>
                """);
        var blocks = template.render(Map.of()).blocks();
        var first = (Paragraph) blocks.get(0);
        assertEquals("body", first.inlines().getFirst().style().fontRole());
        assertEquals(0, first.inlines().getFirst().style().rgb());
        assertEquals(14, first.format().fontSize());
        assertEquals(28, first.format().lineHeight());
        assertEquals(0, first.format().spaceAfter());
        assertFalse(first.format().keepTogether());
        assertEquals("accent", ((Paragraph) blocks.get(1)).inlines().getFirst().style().fontRole());
        var sibling = (Paragraph) blocks.get(2);
        assertEquals(0x123456, sibling.inlines().getFirst().style().rgb());
        assertEquals(9, sibling.format().spaceAfter());
        assertTrue(sibling.format().keepTogether());
    }

    @Test void nativeTextBlocksShareScopeWhileTheirExplicitValuesWin() {
        var document = XmlTemplate.builder().build().compile("""
                <document version="1" role="body" color="#112233">
                  <scope color="#224466" space-after="13">
                    <p id="p" role="" color="#000000">Paragraph</p>
                    <heading id="h" level="2">Heading</heading>
                    <caption id="figure" kind="Figure">Caption</caption>
                    <cross-reference id="ref" kind="NUMBER" target="figure">See </cross-reference>
                  </scope>
                </document>
                """).render(Map.of());
        var paragraph = (Paragraph) document.blocks().get(0);
        assertEquals("", paragraph.inlines().getFirst().style().fontRole());
        assertEquals(0, paragraph.inlines().getFirst().style().rgb());
        assertEquals(13, paragraph.format().spaceAfter());
        var heading = (Heading) document.blocks().get(1);
        assertEquals("body", heading.inlines().getFirst().style().fontRole());
        assertEquals(0x224466, heading.inlines().getFirst().style().rgb());
        assertEquals(0x224466, ((Caption) document.blocks().get(2)).appearance().rgb());
        assertEquals("body", ((CrossReference) document.blocks().get(3)).appearance().fontRole());
    }

    @Test void sourceModeAndIndependentParsingSwitchesAreResolvedBeforeCallingParser() {
        var observed = new ArrayList<ContentOptions.Resolved>();
        SourceContentParser parser = () -> (id, source, options, remaining) -> {
            observed.add(options);
            return List.of(new Paragraph(id, source, options.paragraphFormat()));
        };
        XmlTemplate.builder().contentParser(parser).build().compile("""
                <document version="1" content-format="MARKDOWN" formulas="true" tables="false">
                  <content id="one">A</content>
                  <scope content-format="HTML" tables="ENABLED">
                    <content id="two" formulas="DISABLED">B</content>
                  </scope>
                  <content id="three" content-format="TEXT" formulas="false">C</content>
                </document>
                """).render(Map.of());
        assertEquals(3, observed.size());
        assertEquals(ContentOptions.Format.MARKDOWN, observed.get(0).format());
        assertTrue(observed.get(0).formulas());
        assertFalse(observed.get(0).tables());
        assertEquals(ContentOptions.Format.HTML, observed.get(1).format());
        assertFalse(observed.get(1).formulas());
        assertTrue(observed.get(1).tables());
        assertEquals(ContentOptions.Format.TEXT, observed.get(2).format());
        assertFalse(observed.get(2).tables());
    }

    @Test void sourceCellsUseTheSameLexicalBindingAndDistinctLoopNamespaces() {
        var document = XmlTemplate.builder().build().compile("""
                <document version="1" color="#123456">
                  <rows id="rows" items="items" as="item" role="tableText" cell-size="11">
                    <columns><column weight="1"/></columns>
                    <row><cell color="#000000"><scope space-after="0"><content id="body" source="item.text"/></scope></cell></row>
                  </rows>
                </document>
                """).render(Map.of("items", List.of(Map.of("text", "One ${secret}"), Map.of("text", "Two"))));
        var table = (Table) document.blocks().getFirst();
        assertEquals(2, table.rows().size());
        var first = (Paragraph) table.rows().get(0).cells().getFirst().blocks().getFirst();
        var second = (Paragraph) table.rows().get(1).cells().getFirst().blocks().getFirst();
        assertEquals("One ${secret}", first.text());
        assertNotEquals(first.id(), second.id());
        assertEquals("tableText", first.inlines().getFirst().style().fontRole());
        assertEquals(0, first.inlines().getFirst().style().rgb());
        assertEquals(11, first.format().fontSize());
        assertEquals(0, first.format().spaceAfter());
    }

    @Test void unsupportedParserAndMixedOrUnknownSourceMarkupFailExplicitly() {
        var xml = XmlTemplate.builder().build();
        assertThrows(TemplateException.class, () -> xml.compile("""
                <document version="1"><content content-format="MARKDOWN">**Bold**</content></document>
                """));
        assertThrows(TemplateException.class, () -> xml.compile("""
                <document version="1"><content><span>Mixed markup</span></content></document>
                """));
        assertThrows(TemplateException.class, () -> xml.compile("""
                <document version="1"><content source="body">Unexpected literal</content></document>
                """));
        assertThrows(TemplateException.class, () -> xml.compile("""
                <document version="1"><scope unknown="true"><p>Body</p></scope></document>
                """));
    }
}
