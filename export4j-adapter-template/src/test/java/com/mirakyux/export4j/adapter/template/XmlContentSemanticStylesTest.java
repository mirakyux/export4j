// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SourceContentParser;
import com.mirakyux.export4j.model.TextAppearance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Source parsers receive semantic defaults without treating body baselines as declarations. */
class XmlContentSemanticStylesTest {
    @Test void sourceSnapshotRetainsThemeDomainsAndCompilerTextOverrides() {
        var seen = new ArrayList<ContentOptions.Resolved>();
        var theme = theme();
        XmlTemplate.builder().theme(theme).bodyFormat(theme.bodyFormat().toBuilder().fontSize(11).build())
                .bodyText(new XmlTemplate.TextDefaults("body", 0x102030))
                .headingText(1, new XmlTemplate.TextDefaults("heading", 0xA0B0C0))
                .cellText(new XmlTemplate.TextDefaults("cell", 0x405060))
                .contentParser(parser(seen)).build().compile("""
                        <document version="1"><content id="source">Source</content></document>
                        """).render(Map.of());
        var resolved = seen.getFirst();
        assertEquals(11, resolved.paragraphFormat().fontSize());
        assertEquals("body", resolved.fontRole());
        assertEquals(0x102030, resolved.rgb());
        assertEquals(theme.headingFormat(1), resolved.styles().heading(1).paragraphFormat());
        assertEquals(new TextAppearance("heading", 0xA0B0C0), resolved.styles().heading(1).text());
        assertEquals(theme.headingText(2), resolved.styles().heading(2).text());
        assertEquals(new TextAppearance("cell", 0x405060), resolved.styles().cellText());
        assertEquals(9, resolved.cellFormat().fontSize());
    }

    @Test void sparseScopesOverrideSemanticPropertiesAndRestoreSiblingDefaults() {
        var seen = new ArrayList<ContentOptions.Resolved>();
        XmlTemplate.builder().theme(theme()).contentOptions(ContentOptions.builder().spaceAfter(0).build())
                .contentParser(parser(seen)).build().compile("""
                        <document version="1" content-format="MARKDOWN">
                          <content id="before">Before</content>
                          <scope color="#000000" size="10" keep-together="false">
                            <content id="inside">Inside</content>
                          </scope>
                          <content id="after">After</content>
                        </document>
                        """).render(Map.of());
        var before = seen.get(0);
        var inside = seen.get(1);
        assertEquals(18, before.styles().heading(1).paragraphFormat().fontSize());
        assertEquals(0xAABBCC, before.styles().heading(1).text().rgb());
        assertEquals(0x445566, before.styles().cellText().rgb());
        assertEquals(0, before.styles().heading(1).paragraphFormat().spaceAfter());
        // Ten equals the body baseline, but remains an explicit declaration for headings.
        assertEquals(10, inside.styles().heading(1).paragraphFormat().fontSize());
        assertEquals(28, inside.styles().heading(1).paragraphFormat().lineHeight());
        assertEquals(0, inside.styles().heading(1).text().rgb());
        assertEquals(0, inside.styles().cellText().rgb());
        assertFalse(inside.styles().heading(1).paragraphFormat().keepTogether());
        assertEquals(before, seen.get(2));
    }

    @Test void globalColorEqualToBodyColorStillOverridesOtherDomains() {
        var seen = new ArrayList<ContentOptions.Resolved>();
        XmlTemplate.builder().theme(theme()).contentOptions(ContentOptions.builder().rgb(0x223344).build())
                .contentParser(parser(seen)).build().compile("""
                        <document version="1"><content id="source">Source</content></document>
                        """).render(Map.of());
        assertEquals(0x223344, seen.getFirst().styles().heading(1).text().rgb());
        assertEquals(0x223344, seen.getFirst().styles().cellText().rgb());
    }

    private static SourceContentParser parser(List<ContentOptions.Resolved> seen) {
        return () -> (namespace, source, options, remaining) -> {
            seen.add(options);
            return List.of(new Paragraph(namespace, source, options.paragraphFormat()));
        };
    }

    private static DocumentTheme theme() {
        return DocumentTheme.builder().bodyRgb(0x223344).headingRgb(1, 0xAABBCC).cellRgb(0x445566)
                .bodyFormat(p -> p.fontSize(10).lineHeight(22))
                .headingFormat(1, p -> p.fontSize(18).lineHeight(28).spaceAfter(7).keepTogether(true))
                .cellFormat(c -> c.fontSize(9).lineHeight(20)).build();
    }
}
