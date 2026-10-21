// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlockCellSyntaxRejectionTest {
    @Test void xmlNeverFlattensMixedInlineAndBlockContentOrUnknownBlockContainers() {
        for (String content : List.of("<p id='leaf'>Leaf</p>",
                "<table id='inner'><columns><column weight='1'/></columns><row><cell>Nested</cell></row></table>",
                "<blocks><p>Before</p><p>After</p></blocks>")) {
            for (String tag : List.of("table", "rows items='items' as='item'")) {
                String closing = tag.startsWith("rows") ? "rows" : "table";
                String source = "<document version='1'><" + tag + "><columns><column weight='1'/></columns>"
                        + "<row><cell>Before" + content + "After</cell></row></" + closing + "></document>";
                var failure = assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(source));
                assertEquals(content.startsWith("<blocks>") ? TemplateException.Code.UNSUPPORTED_ELEMENT
                        : TemplateException.Code.INVALID_STRUCTURE, failure.code());
            }
        }
    }

    @Test void markdownDoesNotAcceptHtmlOrBlockCellMarkupAsLiteralContent() {
        for (String source : List.of("<table><tr><td><p>Before</p><table><tr><td>Nested</td></tr></table></td></tr></table>",
                "Before <cell><p>Nested</p></cell> After", "<blocks><p>Before</p><p>After</p></blocks>")) {
            var failure = assertThrows(MarkdownTemplateException.class,
                    () -> MarkdownTemplate.builder().build().compile(source));
            assertEquals(MarkdownTemplateException.Code.UNSUPPORTED_SYNTAX, failure.code());
        }
    }
}
