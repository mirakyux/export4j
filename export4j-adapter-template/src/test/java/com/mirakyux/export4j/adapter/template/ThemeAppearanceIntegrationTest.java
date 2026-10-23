// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Table;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThemeAppearanceIntegrationTest {
    private static final String SOURCE = """
            <document version='1'>
              <header height='30'><p>Header</p></header><footer height='30'><p>Footer</p></footer>
              <p>Body <span>Span</span><page-number width='20'/></p>
              <heading level='1'>Title</heading><numbered list-id='list'>Number</numbered>
              <table><columns><column weight='1'/></columns><row><cell>Cell</cell></row></table>
              <p role='' color='#000000'>Reset</p><p><span style='explicit'>Explicit</span></p>
            </document>
            """;

    private static DocumentTheme theme() {
        return DocumentTheme.builder().fontRole("brand").rgb(0x224488).headingFontRole(1, "title")
                .cellRgb(0x446622).footerRgb(0x666666).inlineStyle("explicit", InlineStyle.of(9)).build();
    }

    @Test void themeAppearanceReachesRunsFieldsMarkersCellsAndBandsWithoutErasingLocalOverrides() {
        var theme = theme();
        var value = XmlTemplate.builder().theme(theme).build().compile(SOURCE).renderWithPageRegions(Map.of());
        var paragraph = value.document().paragraphs().getFirst();
        assertTrue(paragraph.inlines().stream().allMatch(run -> run.style().equals(theme.bodyText().style(12))));
        assertEquals(theme.bodyText().style(12), paragraph.fields().getFirst().field().style());
        assertEquals(theme.headingText(1).fontRole(), ((Heading) value.document().blocks().get(1)).inlines().getFirst().style().fontRole());
        assertEquals(theme.bodyText().style(12), ((NumberedItem) value.document().blocks().get(2)).markerStyle());
        assertEquals(theme.cellText().style(12), ((Table) value.document().blocks().get(3)).rows().getFirst().cells().getFirst().inlines().getFirst().style());
        assertEquals(InlineStyle.of(12), value.document().paragraphs().get(1).inlines().getFirst().style());
        assertEquals(InlineStyle.of(9), value.document().paragraphs().get(2).inlines().getFirst().style());
        assertEquals(theme.text().style(10), value.pageRegions().header().paragraphs().getFirst().inlines().getFirst().style());
        assertEquals(0x666666, value.pageRegions().footer().paragraphs().getFirst().inlines().getFirst().style().rgb());
    }

    @Test void explicitXmlDefaultsAreCompleteOverridesInEitherSetterOrderAndMayBeRestored() {
        var first = XmlTemplate.builder().bodyText(XmlTemplate.TextDefaults.DEFAULT).cellText(XmlTemplate.TextDefaults.DEFAULT)
                .headingText(1, XmlTemplate.TextDefaults.DEFAULT).theme(theme());
        var second = XmlTemplate.builder().theme(theme()).bodyText(XmlTemplate.TextDefaults.DEFAULT)
                .cellText(XmlTemplate.TextDefaults.DEFAULT).headingText(1, XmlTemplate.TextDefaults.DEFAULT);
        var snapshot = first.build();
        var explicit = snapshot.compile(SOURCE).renderWithPageRegions(Map.of());
        assertEquals(explicit, second.build().compile(SOURCE).renderWithPageRegions(Map.of()));
        assertEquals(InlineStyle.of(12), explicit.document().paragraphs().getFirst().inlines().getFirst().style());
        var restored = first.inheritBodyText().inheritCellText().inheritHeadingText(1).build().compile(SOURCE).renderWithPageRegions(Map.of());
        assertEquals(theme().bodyText().style(12), restored.document().paragraphs().getFirst().inlines().getFirst().style());
        assertEquals(explicit, snapshot.compile(SOURCE).renderWithPageRegions(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> first.inheritHeadingText(0));
    }

    @Test void explicitCallbackSnapshotsCurrentResolvedAppearanceBeforeLaterThemeEdits() {
        var compiler = XmlTemplate.builder().theme(theme()).bodyText(value -> value.rgb(0))
                .theme(theme().toBuilder().fontRole("later").rgb(0x112233).build()).build();
        var value = compiler.compile(SOURCE).renderWithPageRegions(Map.of());
        assertEquals("brand", value.document().paragraphs().getFirst().inlines().getFirst().style().fontRole());
        assertEquals(0, value.document().paragraphs().getFirst().inlines().getFirst().style().rgb());
        assertEquals("later", value.pageRegions().header().paragraphs().getFirst().inlines().getFirst().style().fontRole());
    }
}
