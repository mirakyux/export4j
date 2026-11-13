// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SourceContentParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The explicit Markdown HTML switch must retain lexical inheritance without changing siblings. */
class XmlRawHtmlContentTest {
    @Test void sourceScopesInheritRawHtmlIndependentlyOfFormatAndFormulaOptions() {
        var seen = new ArrayList<ContentOptions.Resolved>();
        SourceContentParser parser = () -> (namespace, source, options, remaining) -> {
            seen.add(options);
            return List.of(new Paragraph(namespace, source, options.paragraphFormat()));
        };
        var template = XmlTemplate.builder().contentParser(parser).build().compile("""
                <document version="1" content-format="MARKDOWN" raw-html="true" formulas="false">
                  <content id="one">one</content>
                  <scope raw-html="false"><content id="two">two</content></scope>
                  <content id="three">three</content>
                </document>
                """);
        template.render(Map.of());
        assertEquals(List.of(true, false, true), seen.stream().map(ContentOptions.Resolved::rawHtml).toList());
        assertEquals(List.of(ContentOptions.Format.MARKDOWN, ContentOptions.Format.MARKDOWN, ContentOptions.Format.MARKDOWN),
                seen.stream().map(ContentOptions.Resolved::format).toList());
        assertEquals(List.of(false, false, false), seen.stream().map(ContentOptions.Resolved::formulas).toList());
    }
}
