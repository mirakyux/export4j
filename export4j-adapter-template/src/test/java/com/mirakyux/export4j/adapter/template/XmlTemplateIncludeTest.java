// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableFormat;

class XmlTemplateIncludeTest {
    private static final String COLUMNS = "<columns><column weight='1'/></columns>";
    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String fragment(String content) { return "<fragment version='1'>" + content + "</fragment>"; }
    private static String include(String name, String prefix) { return "<include ref='" + name + "' prefix='" + prefix + "'/>"; }
    private static String table(String id, String content) {
        return "<table id='" + id + "'>" + COLUMNS + "<row><cell>" + content + "</cell></row></table>";
    }
    private static Document render(String part, String content, Map<String, ?> data) {
        return XmlTemplate.builder().fragment("part", fragment(part)).build().compile(xml(content)).render(data);
    }

    @Test
    void unusedFragmentRegistrationDoesNotChangeTheCompleteLegacyModelOrAutomaticIds() {
        String source = xml("<p>  A<span> B </span></p><p id='xml-1'>${value}</p>"
                + "<numbered id='step' list-id='checks'>Step</numbered><p><number-reference target='step' width='20'/></p>"
                + table("outer", "<p id='leaf'>Leaf</p>"));
        var expected = XmlTemplate.builder().build().compile(source).render(Map.of("value", "😀"));
        var actual = XmlTemplate.builder().fragment("unused", "<not-even-closed SECRET")
                .build().compile(source).render(Map.of("value", "😀"));
        assertEquals(expected, actual);
        assertEquals(expected.blocks().stream().map(Block::id).toList(), actual.blocks().stream().map(Block::id).toList());
    }

    @Test
    void registrationNamesAreStaticBoundedUniqueAndSnapshotted() {
        String part = fragment("<p>Part</p>");
        for (String name : List.of("", "0bad", "with space", "a/b", "https://example.com", "${name}", "a".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> XmlTemplate.builder().fragment(name, part).build());
        }
        assertThrows(NullPointerException.class, () -> XmlTemplate.builder().fragment(null, part));
        assertThrows(NullPointerException.class, () -> XmlTemplate.builder().fragment("part", null));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplate.builder().fragment("part", part).fragment("part", part).build());
        var builder = XmlTemplate.builder();
        for (int index = 0; index < 128; index++) { builder.fragment("part-" + index, part); }
        var compiler = builder.build();
        assertEquals("Part", compiler.compile(xml(include("part-127", "last"))).render(Map.of()).paragraphs().getFirst().text());
        assertThrows(IllegalArgumentException.class, () -> builder.fragment("overflow", part).build());
        String maximum = "a".repeat(64);
        assertEquals("Part", XmlTemplate.builder().fragment(maximum, part).build().compile(xml(include(maximum, maximum)))
                .render(Map.of()).paragraphs().getFirst().text());
    }

    @Test
    void includeExpandsAtItsSourcePositionAndAllAllowedMainControlContainers() {
        String part = "<p id='item'>${item}</p>";
        String source = "<p id='before'>Before</p><if test='visible'><then>" + include("part", "yes")
                + "</then><else>" + include("part", "no") + "</else></if>"
                + "<each items='items' as='item'>" + include("part", "loop") + "</each><p id='after'>After</p>";
        var compiler = XmlTemplate.builder().fragment("part", fragment(part)).build();
        var document = compiler.compile(xml(source)).render(Map.of("visible", true, "item", "One", "items", List.of("Two", "Three")));
        assertEquals(List.of("before", "yes-item", "loop-item@0", "loop-item@1", "after"), document.blocks().stream().map(Block::id).toList());
        assertEquals(List.of("Before", "One", "Two", "Three", "After"), document.paragraphs().stream().map(Paragraph::text).toList());
        var alternate = compiler.compile(xml(source)).render(Map.of("visible", false, "item", "Other", "items", List.of()));
        assertEquals(List.of("before", "no-item", "after"), alternate.blocks().stream().map(Block::id).toList());
        assertEquals(List.of("Before", "Other", "After"), alternate.paragraphs().stream().map(Paragraph::text).toList());
    }

    @Test
    void everyReachableIncludeCompilesEvenInAnUnselectedBranchOrZeroIterationPrototype() {
        var compiler = XmlTemplate.builder().fragment("bad", fragment("<p>${SECRET_CALL()}</p>")).build();
        for (String content : List.of("<if test='flag'><then><p>Safe</p></then><else>" + include("bad", "hidden") + "</else></if>",
                "<each items='items' as='item'>" + include("bad", "zero") + "</each>")) {
            var failure = rejected(compiler, xml(content), TemplateException.Code.INVALID_EXPRESSION);
            assertEquals(Optional.of("bad"), failure.fragmentName());
        }
        var missing = rejected(XmlTemplate.builder().build(), xml("<each items='items' as='item'>" + include("absent", "zero") + "</each>"),
                TemplateException.Code.UNKNOWN_RESOURCE);
        assertEquals(Optional.empty(), missing.fragmentName());
    }

    @Test
    void prefixesAreUniqueAcrossAllMainBranchesAndIterationPrototypes() {
        var compiler = XmlTemplate.builder().fragment("part", fragment("<p id='item'>Part</p>")).build();
        for (String content : List.of(include("part", "same") + include("part", "same"),
                "<if test='flag'><then>" + include("part", "same") + "</then><else>" + include("part", "same") + "</else></if>",
                include("part", "same") + "<each items='items' as='item'>" + include("part", "same") + "</each>")) {
            rejected(compiler, xml(content), TemplateException.Code.INVALID_VALUE);
        }
        var document = compiler.compile(xml("<each items='items' as='item'>" + include("part", "unique") + "</each>"))
                .render(Map.of("items", List.of(1, 2)));
        assertEquals(List.of("unique-item@0", "unique-item@1"), document.blocks().stream().map(Block::id).toList());
    }

    @Test
    void automaticIdsAvoidMainAndOtherFragmentExplicitIdsBeforeAnyAllocation() {
        var compiler = XmlTemplate.builder().fragment("automatic", fragment("<p>Included automatic</p>"))
                .fragment("explicit", fragment("<p id='y-xml-1'>Included explicit</p>")).build();
        String source = xml("<p>Main automatic</p>" + include("automatic", "x-y") + include("explicit", "x")
                + "<p id='xml-1'>Main explicit</p><p id='x-y-xml-2'>Reserved after includes</p>");
        var document = compiler.compile(source).render(Map.of());
        var ids = document.blocks().stream().map(Block::id).toList();
        assertEquals(5, ids.stream().distinct().count());
        assertEquals("x-y-xml-1", document.paragraphs().get(2).id());
        assertEquals("xml-1", document.paragraphs().get(3).id());
        assertEquals("x-y-xml-2", document.paragraphs().get(4).id());
        assertTrue(document.paragraphs().getFirst().id().matches("xml-[0-9]+"));
        assertTrue(document.paragraphs().get(1).id().matches("x-y-xml-[0-9]+"));
        assertEquals(document, compiler.compile(source).render(Map.of()));
    }

    @Test
    void explicitIdCollisionsRemainFailuresAfterPrefixComposition() {
        var compiler = XmlTemplate.builder().fragment("one", fragment("<p id='p'>One</p>"))
                .fragment("two", fragment("<p id='y-p'>Two</p>")).build();
        rejected(compiler, xml(include("one", "x-y") + include("two", "x")), TemplateException.Code.INVALID_VALUE);
        rejected(compiler, xml("<p id='x-p'>Main</p>" + include("one", "x")), TemplateException.Code.INVALID_VALUE);
        var duplicate = XmlTemplate.builder().fragment("same", fragment("<if test='flag'><then><p id='p'>One</p></then><else><p id='p'>Two</p></else></if>")).build();
        var failure = rejected(duplicate, xml(include("same", "x")), TemplateException.Code.INVALID_VALUE);
        assertEquals(Optional.of("same"), failure.fragmentName());
    }

    @Test
    void composedIdsAndListIdsHonorTheExisting128CharacterLimit() {
        String accepted = "a".repeat(126);
        var document = render("<numbered id='" + accepted + "' list-id='" + accepted + "'>Step</numbered>", include("part", "p"), Map.of());
        var item = assertInstanceOf(NumberedItem.class, document.blocks().getFirst());
        assertEquals("p-" + accepted, item.id());
        assertEquals(128, item.id().length());
        assertEquals(item.id(), item.listId());
        for (String content : List.of("<p id='" + "a".repeat(64) + "'>Long</p>",
                "<numbered id='p' list-id='" + "a".repeat(64) + "'>Long</numbered>")) {
            var compiler = XmlTemplate.builder().fragment("part", fragment(content)).build();
            rejected(compiler, xml(include("part", "p".repeat(64))), TemplateException.Code.INVALID_VALUE);
        }
    }

    @Test
    void fieldTargetsRelocateOnlyTheSameFragmentsExplicitDeclarations() {
        String part = "<p id='reference'><page-reference target='later' width='20'/><number-reference target='later' width='20'/>"
                + "<page-reference target='reference' width='20'/><page-reference target='outside' width='20'/>"
                + "<page-reference target='other-later' width='20'/><page-number width='20'/><page-count width='20'/></p>"
                + "<numbered id='later' list-id='checks'>Step</numbered>";
        var document = render(part, "<p id='outside'>Outside</p>" + include("part", "copy") + "<p id='other-later'>Other</p>", Map.of());
        var reference = document.paragraphs().get(1);
        assertEquals(List.of("copy-later", "copy-later", "copy-reference", "outside", "other-later", "", ""),
                reference.fields().stream().map(span -> span.field().targetId()).toList());
        assertEquals("copy-checks", ((NumberedItem) document.blocks().get(2)).listId());
    }

    @Test
    void conditionalDeclarationsAreCollectedForRelocationEvenWhenTheirBranchDoesNotRender() {
        String part = "<p id='reference'><page-reference target='hidden' width='20'/></p>"
                + "<if test='flag'><then><p id='visible'>Visible</p></then><else><p id='hidden'>Hidden</p></else></if>";
        var document = render(part, "<p id='hidden'>Main must not become the fallback</p>" + include("part", "copy"), Map.of("flag", true));
        assertEquals("copy-hidden", document.paragraphs().get(1).fields().getFirst().field().targetId());
        assertEquals(List.of("hidden", "copy-reference", "copy-visible"), document.blocks().stream().map(Block::id).toList());
    }

    @Test
    void nestedIterationKeepsCurrentOuterAndInnerTargetsAndExternalMainTargets() {
        String part = "<p id='outer'>${item.name}</p><each items='item.children' as='child'><p id='inner'>${child}"
                + "<page-reference target='outer' width='20'/><page-reference target='inner' width='20'/><page-reference target='main' width='20'/></p></each>";
        var document = render(part, "<p id='main'>Main</p><each items='items' as='item'>" + include("part", "copy") + "</each>",
                Map.of("items", List.of(Map.of("name", "A", "children", List.of("A1", "A2")), Map.of("name", "B", "children", List.of("B1")))));
        assertEquals(List.of("main", "copy-outer@0", "copy-inner@0@0", "copy-inner@0@1", "copy-outer@1", "copy-inner@1@0"),
                document.blocks().stream().map(Block::id).toList());
        for (var paragraph : document.paragraphs().stream().filter(p -> p.id().startsWith("copy-inner")).toList()) {
            String suffix = paragraph.id().substring("copy-inner".length());
            String outerSuffix = suffix.substring(0, suffix.lastIndexOf('@'));
            assertEquals(List.of("copy-outer" + outerSuffix, paragraph.id(), "main"),
                    paragraph.fields().stream().map(span -> span.field().targetId()).toList());
        }
    }

    @Test
    void fragmentListIdsAreNamespacedOnceWhileExistingIterationContinuityRemains() {
        String part = "<numbered id='step' list-id='checks'>Step</numbered>";
        var document = render(part, "<numbered id='main-first' list-id='checks'>Main</numbered>"
                + "<each items='items' as='item'>" + include("part", "copy") + "<numbered id='legacy' list-id='checks'>Legacy</numbered></each>"
                + include("part", "other") + "<numbered id='main-last' list-id='checks'>Main</numbered>", Map.of("items", List.of(1, 2)));
        var items = document.blocks().stream().map(block -> assertInstanceOf(NumberedItem.class, block)).toList();
        assertEquals(List.of("checks", "copy-checks", "checks", "copy-checks", "checks", "other-checks", "checks"),
                items.stream().map(NumberedItem::listId).toList());
        assertEquals(List.of("main-first", "copy-step@0", "legacy@0", "copy-step@1", "legacy@1", "other-step", "main-last"),
                items.stream().map(NumberedItem::id).toList());
    }

    @Test
    void themeFormatsAndDeepBlockCellDeclarationsArePreservedAndPrefixed() {
        var body = ParagraphFormat.builder().fontSize(11).lineHeight(24).spaceBefore(2).spaceAfter(4).build();
        var note = ParagraphFormat.builder().fontSize(10).lineHeight(22).spaceAfter(3).build();
        var cell = new CellFormat(9, 20, ParagraphAlignment.CENTER);
        var ink = new InlineStyle("report-bold", 10, 0x224488, 2, true, false);
        var format = TableFormat.builder().padding(4).spaceBefore(2).spaceAfter(5).build();
        var theme = DocumentTheme.builder().bodyFormat(body).cellFormat(cell).tableFormat(format).paragraphStyle("note", note).inlineStyle("ink", ink).build();
        String part = "<p id='body'>Body</p>" + table("outer", "<p id='before' style='note'><span style='ink'>Before</span></p>"
                + table("inner", "<p id='leaf'><page-reference target='before' width='20'/><page-reference target='inner' width='20'/></p>"));
        var document = XmlTemplate.builder().theme(theme).fragment("part", fragment(part)).build().compile(xml(include("part", "copy"))).render(Map.of());
        assertEquals(body, document.paragraphs().getFirst().format());
        var outer = assertInstanceOf(Table.class, document.blocks().getLast());
        assertEquals("copy-outer", outer.id()); assertEquals(format, outer.format());
        var before = assertInstanceOf(Paragraph.class, outer.rows().getFirst().cells().getFirst().blocks().getFirst());
        assertEquals("copy-before", before.id()); assertEquals(note, before.format()); assertEquals(ink, before.inlines().getFirst().style());
        var inner = assertInstanceOf(Table.class, outer.rows().getFirst().cells().getFirst().blocks().get(1));
        assertEquals("copy-inner", inner.id()); assertEquals(format, inner.format());
        assertEquals(cell, inner.rows().getFirst().cells().getFirst().format());
        var leaf = assertInstanceOf(Paragraph.class, inner.rows().getFirst().cells().getFirst().blocks().getFirst());
        assertEquals("copy-leaf", leaf.id());
        assertEquals(List.of("copy-before", "copy-inner"), leaf.fields().stream().map(span -> span.field().targetId()).toList());
    }

    @Test
    void sourceBudgetIncludesAllRegisteredUtf16EvenForUnusedMalformedFragments() {
        String source = xml("<p>😀</p>");
        String unused = "<MALFORMED_SECRET😀";
        int exact = source.length() + unused.length();
        assertEquals("😀", XmlTemplate.builder().fragment("unused", unused).limits(new XmlTemplate.Limits(exact, 2, 2, 1))
                .build().compile(source).render(Map.of()).paragraphs().getFirst().text());
        rejected(XmlTemplate.builder().fragment("unused", unused).limits(new XmlTemplate.Limits(exact - 1, 2, 2, 1)).build(), source,
                TemplateException.Code.BUDGET_EXCEEDED);
    }

    @Test
    void compilerAndCompiledTemplatesRetainFragmentRegistryThemeAndLimitSnapshots() {
        String part = fragment("<p id='body'>${value}</p>");
        String source = xml(include("part", "copy"));
        var original = ParagraphFormat.of(10);
        var builder = XmlTemplate.builder().fragment("part", part).bodyFormat(original)
                .limits(new XmlTemplate.Limits(source.length() + part.length(), 10, 10, 100));
        var compiler = builder.build(); var compiled = compiler.compile(source);
        builder.fragment("unused", "SECRET".repeat(100)).bodyFormat(ParagraphFormat.of(18));
        var first = compiled.render(Map.of("value", "First"));
        assertEquals(original, first.paragraphs().getFirst().format());
        assertEquals("copy-body", first.paragraphs().getFirst().id());
        assertEquals(original, compiler.compile(source).render(Map.of("value", "Second")).paragraphs().getFirst().format());
        rejected(builder.build(), source, TemplateException.Code.BUDGET_EXCEEDED);
    }

    @Test
    void includeDoesNotBypassExpandedRuntimeBlockBudgets() {
        String part = fragment("<p id='body'>${item}</p>");
        String source = xml("<each items='items' as='item'>" + include("part", "copy") + "</each>");
        var data = Map.of("items", List.of("A", "B"));
        var exact = XmlTemplate.builder().fragment("part", part).bindingLimits(new DocumentTemplate.Limits(1000, 2, 100, 1000, 32))
                .build().compile(source).render(data);
        assertEquals(2, exact.blocks().size());
        var bounded = XmlTemplate.builder().fragment("part", part).bindingLimits(new DocumentTemplate.Limits(1000, 1, 100, 1000, 32)).build().compile(source);
        var failure = assertThrows(DocumentTemplate.BindingException.class, () -> bounded.render(data));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("blocks", failure.path());
    }

    @Test
    void readerOwnershipIoFailureAndCancellationRemainStrictAndReusable() throws IOException {
        String source = xml(include("part", "copy"));
        var compiler = XmlTemplate.builder().fragment("part", fragment("<p id='body'>${value}</p>")).build();
        var reader = new StringReader(source) { @Override public void close() { throw new AssertionError("borrowed reader closed"); } };
        var compiled = compiler.compile(reader);
        assertEquals(-1, reader.read());
        var sentinel = new IOException("SECRET_IO");
        var broken = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException { throw sentinel; }
            @Override public void close() { throw new AssertionError("borrowed reader closed"); }
        };
        assertSame(sentinel, assertThrows(IOException.class, () -> compiler.compile(broken)));
        var cancelled = ExecutionOptions.builder().cancelled(() -> true).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(cancelled, () -> compiler.compile(source))).code());
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(cancelled, () -> compiled.render(Map.of("value", "SECRET")))).code());
        ExecutionContext.checkpoint();
        assertEquals("OK", compiled.render(Map.of("value", "OK")).paragraphs().getFirst().text());
        assertEquals("Reuse", compiler.compile(source).render(Map.of("value", "Reuse")).paragraphs().getFirst().text());
    }

    @Test
    void includeHasOnlyTwoRequiredStaticAttributesAndMustBeStrictlyEmpty() {
        var compiler = XmlTemplate.builder().fragment("part", fragment("<p>Part</p>")).build();
        for (String content : List.of("<include/>", "<include ref='part'/>", "<include prefix='copy'/>",
                "<include ref='' prefix='copy'/>", "<include ref='part' prefix=''/>",
                "<include ref='part' prefix='copy'> </include>",
                "<include ref='part' prefix='copy'><p>SECRET</p></include>")) {
            rejected(compiler, xml(content), TemplateException.Code.INVALID_STRUCTURE);
        }
        for (String attribute : List.of("href='https://SECRET.invalid/x'", "id='SECRET'", "style='SECRET'")) {
            rejected(compiler, xml("<include ref='part' prefix='copy' " + attribute + "/>"), TemplateException.Code.UNSUPPORTED_ATTRIBUTE);
        }
        for (String value : List.of("0bad", "a/b", "with space", "a".repeat(65), "${SECRET}")) {
            rejected(compiler, xml(include("part", value)), TemplateException.Code.INVALID_VALUE);
            rejected(compiler, xml(include(value, "copy")), TemplateException.Code.INVALID_VALUE);
        }
    }

    @Test
    void includeIsRejectedOutsideTheFourMainBlockContainers() {
        var compiler = XmlTemplate.builder().fragment("part", fragment("<p>Part</p>")).build();
        String call = include("part", "copy");
        for (String content : List.of("<p>" + call + "</p>", "<p><span>" + call + "</span></p>",
                "<p><link href='https://example.com'>" + call + "</link></p>",
                "<p><page-number width='20'>" + call + "</page-number></p>",
                "<if test='flag'>" + call + "</if>",
                "<table>" + COLUMNS + call + "<row><cell>Safe</cell></row></table>",
                "<table>" + COLUMNS + "<row>" + call + "<cell>Safe</cell></row></table>",
                table("outer", call), table("outer", "<p>Safe</p>" + call))) {
            rejected(compiler, xml(content), TemplateException.Code.UNSUPPORTED_ELEMENT);
        }
    }

    @Test
    void fragmentWrapperRequiresOnlyVersionOneAndBlockContent() {
        for (String part : List.of("<document version='1'><p>SECRET</p></document>",
                "<fragment><p>SECRET</p></fragment>", "<fragment version='1'>SECRET</fragment>")) {
            var failure = rejected(XmlTemplate.builder().fragment("part", part).build(), xml(include("part", "copy")),
                    TemplateException.Code.INVALID_STRUCTURE);
            assertEquals(Optional.of("part"), failure.fragmentName());
        }
        for (String version : List.of("0", "2", "${SECRET}")) {
            rejected(XmlTemplate.builder().fragment("part", "<fragment version='" + version + "'><p>Text</p></fragment>").build(),
                    xml(include("part", "copy")), TemplateException.Code.INVALID_VALUE);
        }
        for (String attribute : List.of("title='SECRET'", "id='SECRET'", "prefix='SECRET'")) {
            rejected(XmlTemplate.builder().fragment("part", "<fragment version='1' " + attribute + "><p>Text</p></fragment>").build(),
                    xml(include("part", "copy")), TemplateException.Code.UNSUPPORTED_ATTRIBUTE);
        }
        assertTrue(render("", include("part", "copy"), Map.of()).blocks().isEmpty());
    }

    @Test
    void everyIncludeInsideAFragmentIsRejectedEvenWhenHiddenOrInAnIllegalInlinePosition() {
        String call = include("part", "nested");
        for (String content : List.of(call,
                "<if test='flag'><then><p>Safe</p></then><else>" + call + "</else></if>",
                "<each items='items' as='item'>" + call + "</each>",
                "<p><span>" + call + "</span></p>", table("outer", call))) {
            var failure = rejected(XmlTemplate.builder().fragment("part", fragment(content)).build(), xml(include("part", "copy")),
                    TemplateException.Code.UNSUPPORTED_ELEMENT);
            assertEquals(Optional.of("part"), failure.fragmentName());
        }
    }

    @Test
    void fragmentXmlSecurityRejectsExternalEntitiesNamespacesAndInstructionsWithoutNetworkAccess() throws IOException {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/SECRET", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET";
            for (String part : List.of("<!DOCTYPE fragment SYSTEM '" + url + "'>" + fragment("<p>Safe</p>"),
                    "<!DOCTYPE fragment [<!ENTITY ext SYSTEM '" + url + "'>]>" + fragment("<p>&ext;</p>"),
                    "<?SECRET fetch='" + url + "'?>" + fragment("<p>Safe</p>"),
                    "<fragment version='1' xmlns='urn:SECRET'><p>Safe</p></fragment>",
                    "<fragment version='1' xmlns:x='urn:SECRET'><p x:value='bad'>Safe</p></fragment>")) {
                var failure = rejected(XmlTemplate.builder().fragment("part", part).build(), xml(include("part", "copy")),
                        TemplateException.Code.FORBIDDEN_XML);
                assertEquals(Optional.of("part"), failure.fragmentName());
            }
            rejected(XmlTemplate.builder().fragment("part", fragment("<script>SECRET</script>")).build(), xml(include("part", "copy")),
                    TemplateException.Code.UNSUPPORTED_ELEMENT);
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test
    void diagnosticsDistinguishMainCallsitesFromIncludedLeafLocationsWithoutDisclosingSource() {
        String main = "<document version='1'>\n<include ref='SECRET_missing' prefix='copy'/>\n</document>";
        var missing = rejected(XmlTemplate.builder().build(), main, TemplateException.Code.UNKNOWN_RESOURCE);
        assertEquals(2, missing.line()); assertEquals(Optional.empty(), missing.fragmentName());
        String part = "<fragment version='1'>\n<p>Before</p>\n<p style='SECRET_style'>SECRET body</p>\n</fragment>";
        var leaf = rejected(XmlTemplate.builder().fragment("SECRET_part", part).build(),
                "<document version='1'>\n<include ref='SECRET_part' prefix='copy'/>\n</document>", TemplateException.Code.INVALID_VALUE);
        assertEquals(3, leaf.line()); assertEquals(Optional.of("SECRET_part"), leaf.fragmentName());
        var duplicate = rejected(XmlTemplate.builder().fragment("part", fragment("<p>Part</p>")).build(),
                "<document version='1'>\n" + include("part", "copy") + "\n" + include("part", "copy") + "\n</document>",
                TemplateException.Code.INVALID_VALUE);
        assertEquals(3, duplicate.line()); assertEquals(Optional.empty(), duplicate.fragmentName());
        var malformed = rejected(XmlTemplate.builder().fragment("SECRET_part", "<fragment version='1'>\n<p>SECRET</fragment>").build(),
                xml(include("SECRET_part", "copy")), TemplateException.Code.MALFORMED_XML);
        assertEquals(2, malformed.line()); assertEquals(Optional.of("SECRET_part"), malformed.fragmentName());
    }

    @Test
    void repeatedIncludesChargeExpansionEveryTimeButParseEachRegisteredSourceOnlyOnce() {
        String part = fragment("<p>A</p><p>B</p><p>C</p>");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 10; index++) { content.append(include("part", "copy" + index)); }
        String source = xml(content.toString());
        var exact = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 41, 2, 30)).build();
        assertEquals(30, exact.compile(source).render(Map.of()).blocks().size());
        var lowElements = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 40, 2, 30)).build();
        var elementFailure = rejected(lowElements, source, TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(Optional.empty(), elementFailure.fragmentName());
        var lowText = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 41, 2, 29)).build();
        var textFailure = rejected(lowText, source, TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(Optional.empty(), textFailure.fragmentName());
        // Parse once would use 15 elements, while parsing ten copies would use 51 and exceed 41.
    }

    @Test
    void distinctReferencedSourcesShareParseBudgetsIncludingTheirDiscardedWrappers() {
        String source = xml(include("one", "a") + include("two", "b"));
        String part = fragment("<p>X</p>");
        var exact = XmlTemplate.builder().fragment("one", part).fragment("two", part)
                .limits(new XmlTemplate.Limits(10000, 7, 2, 2)).build();
        assertEquals(2, exact.compile(source).render(Map.of()).blocks().size());
        var limited = XmlTemplate.builder().fragment("one", part).fragment("two", part)
                .limits(new XmlTemplate.Limits(10000, 6, 2, 2)).build();
        var failure = rejected(limited, "<document version='1'>" + include("one", "a") + "\n" + include("two", "b") + "</document>",
                TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(2, failure.line()); assertEquals(Optional.empty(), failure.fragmentName());
    }

    @Test
    void finalDepthCountsExpandedBlocksAndControlsButNotRemovedIncludeOrFragmentWrappers() {
        String part = fragment("<if test='flag'><then><p id='body'>${item}</p></then></if>");
        String source = xml("<each items='items' as='item'>" + include("part", "copy") + "</each>");
        var exact = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 20, 5, 100)).build();
        assertEquals("X", exact.compile(source).render(Map.of("items", List.of("X"), "flag", true)).paragraphs().getFirst().text());
        var limited = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 20, 4, 100)).build();
        var failure = rejected(limited, source, TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(Optional.empty(), failure.fragmentName());
        var empty = XmlTemplate.builder().fragment("part", fragment("")).limits(new XmlTemplate.Limits(1000, 3, 1, 1)).build();
        assertTrue(empty.compile(xml(include("part", "copy"))).render(Map.of()).blocks().isEmpty());
        rejected(XmlTemplate.builder().fragment("part", fragment("")).limits(new XmlTemplate.Limits(1000, 2, 1, 1)).build(),
                xml(include("part", "copy")), TemplateException.Code.BUDGET_EXCEEDED);
    }

    @Test
    void readerUsesOnlyTheRemainingSourceAllowancePlusOneAndRegistryOverflowReadsNothing() {
        String part = fragment("<p>Part</p>");
        int remaining = 7;
        var consumed = new AtomicInteger();
        var reader = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) {
                assertTrue(length > 0 && length <= remaining + 1 - consumed.get());
                java.util.Arrays.fill(buffer, offset, offset + length, 'x');
                consumed.addAndGet(length); return length;
            }
            @Override public void close() { throw new AssertionError("borrowed reader closed"); }
        };
        var compiler = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(part.length() + remaining, 10, 10, 100)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class, () -> compiler.compile(reader)).code());
        assertEquals(remaining + 1, consumed.get());
        var unreadable = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) { throw new AssertionError("registry overflow must fail before reading"); }
            @Override public void close() { throw new AssertionError("borrowed reader closed"); }
        };
        var overflow = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(part.length() - 1, 10, 10, 100)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class, () -> overflow.compile(unreadable)).code());
    }

    @Test
    void cancellationDuringLargeFragmentCompilationRestoresTheScopeAndCompiler() {
        String part = fragment("<p>Part</p>".repeat(1000));
        String source = xml(include("part", "copy"));
        var compiler = XmlTemplate.builder().fragment("part", part).build();
        var checkpoints = new AtomicInteger();
        var cancelled = ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 500).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(cancelled, () -> compiler.compile(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checkpoints.get() > 500);
        ExecutionContext.checkpoint();
        assertEquals(1000, compiler.compile(source).render(Map.of()).blocks().size());
    }

    @Test
    void includedDynamicRowsPreserveImagesLinksBindingUtf16AndOnlyStructuralTargetNamesAreRelocated() throws IOException {
        var resource = image();
        String part = "<p id='local'>Local</p><rows id='data' items='items' as='item'>" + COLUMNS
                + "<header><cell>HEAD</cell></header><row><cell><span>${item.name}</span>"
                + "<link href='https://example.com/local'><image ref='local' width='8' height='9' alt='local ${item.name}'/></link>"
                + "<page-reference target='local' width='20'/><span>${local}</span></cell></row></rows>";
        var builder = XmlTemplate.builder().fragment("part", fragment(part)).image("local", resource);
        var compiled = builder.build().compile(xml(include("part", "copy")));
        builder.image("local", image());
        var document = compiled.render(Map.of("items", List.of(Map.of("name", "A😀")), "local", "DATA"));
        var data = assertInstanceOf(Table.class, document.blocks().getLast());
        assertEquals("copy-data", data.id());
        var cell = data.rows().getLast().cells().getFirst();
        assertEquals("A😀\ufffc\ufffcDATA", cell.text());
        assertEquals(3, cell.images().getFirst().sourceIndex());
        assertEquals(4, cell.fields().getFirst().sourceIndex());
        assertSame(resource, cell.images().getFirst().image().resource());
        assertEquals("local A😀", cell.images().getFirst().image().altText());
        assertEquals("https://example.com/local", cell.images().getFirst().image().hyperlink());
        assertEquals("copy-local", cell.fields().getFirst().field().targetId());
    }

    @Test
    void includedBlockCellsStillRespectAtomicThemeSplitAndNestedDynamicSyntaxBoundaries() {
        var theme = DocumentTheme.builder().tableFormat(format -> format.allowRowSplit(true)).build();
        String block = table("outer", "<p id='leaf'>Leaf</p>");
        var failure = rejected(XmlTemplate.builder().theme(theme).fragment("part", fragment(block)).build(), xml(include("part", "copy")),
                TemplateException.Code.INVALID_VALUE);
        assertEquals(Optional.of("part"), failure.fragmentName());
        String atomic = block.replace("<table id='outer'>", "<table id='outer' row-split='false'>");
        assertEquals(1, XmlTemplate.builder().theme(theme).fragment("part", fragment(atomic)).build()
                .compile(xml(include("part", "copy"))).render(Map.of()).blocks().size());
        for (String content : List.of("<each items='items' as='item'><p>Leaf</p></each>",
                "<rows items='items' as='item'>" + COLUMNS + "<row><cell>Leaf</cell></row></rows>")) {
            rejected(XmlTemplate.builder().fragment("part", fragment(table("outer", content))).build(), xml(include("part", "copy")),
                    TemplateException.Code.UNSUPPORTED_ELEMENT);
        }
    }

    @Test
    void unchangedMainSyntaxStillReportsTheFirstDepthOverflowBeforeLaterMalformedXml() {
        String source = "<document version='1'>\n<p>\n<span><span>SECRET</span></span></p><MALFORMED";
        for (var compiler : List.of(XmlTemplate.builder().limits(new XmlTemplate.Limits(1000, 100, 2, 100)).build(),
                XmlTemplate.builder().fragment("unused", "MALFORMED_SECRET").limits(new XmlTemplate.Limits(1000, 100, 2, 100)).build())) {
            var failure = rejected(compiler, source, TemplateException.Code.BUDGET_EXCEEDED);
            assertEquals(3, failure.line()); assertEquals(7, failure.column()); assertEquals(Optional.empty(), failure.fragmentName());
        }
    }

    @Test
    void derivedListIdCollisionsAcrossFragmentOwnersFailAtTheSecondIncludedLeaf() {
        var compiler = XmlTemplate.builder().fragment("one", fragment("<numbered id='one' list-id='b-c'>One</numbered>"))
                .fragment("two", "<fragment version='1'>\n<p id='safe'>Before</p>\n<numbered id='two' list-id='c'>Two</numbered>\n</fragment>").build();
        var failure = rejected(compiler, xml(include("one", "a") + include("two", "a-b")), TemplateException.Code.INVALID_VALUE);
        assertEquals(3, failure.line()); assertEquals(Optional.of("two"), failure.fragmentName());
        var hidden = rejected(compiler, xml("<if test='flag'><then>" + include("one", "a") + "</then><else>"
                + include("two", "a-b") + "</else></if>"), TemplateException.Code.INVALID_VALUE);
        assertEquals(Optional.of("two"), hidden.fragmentName());
    }

    @Test
    void derivedListIdsCannotMergeWithMainListsRegardlessOfDeclarationOrder() {
        var compiler = XmlTemplate.builder().fragment("part", fragment("<numbered id='step' list-id='checks'>Part</numbered>")).build();
        var includedLeaf = rejected(compiler, xml("<numbered id='main' list-id='copy-checks'>Main</numbered>" + include("part", "copy")),
                TemplateException.Code.INVALID_VALUE);
        assertEquals(Optional.of("part"), includedLeaf.fragmentName());
        var mainLeaf = rejected(compiler, "<document version='1'>\n" + include("part", "copy")
                + "\n<numbered id='main' list-id='copy-checks'>Main</numbered>\n</document>", TemplateException.Code.INVALID_VALUE);
        assertEquals(3, mainLeaf.line()); assertEquals(Optional.empty(), mainLeaf.fragmentName());
    }

    @Test
    void multipleItemsWithinOneFragmentOwnerShareTheirListAcrossRuntimeIterations() {
        String part = "<numbered id='first' list-id='checks'>First</numbered><numbered id='second' list-id='checks'>Second</numbered>";
        var document = render(part, "<each items='items' as='item'>" + include("part", "copy") + "</each>", Map.of("items", List.of(1, 2)));
        var items = document.blocks().stream().map(value -> assertInstanceOf(NumberedItem.class, value)).toList();
        assertEquals(List.of("copy-checks", "copy-checks", "copy-checks", "copy-checks"), items.stream().map(NumberedItem::listId).toList());
        assertEquals(List.of("copy-first@0", "copy-second@0", "copy-first@1", "copy-second@1"), items.stream().map(NumberedItem::id).toList());
    }

    @Test
    void emptyIncludeAtSourceDepth65IsRemovedWhileA66LevelSourceStillFailsTheDepthBudget() {
        var compiler = XmlTemplate.builder().fragment("part", fragment(""))
                .limits(new XmlTemplate.Limits(10000, 1000, 64, 1000)).build();
        String open = "<each items='items' as='item'>";
        String close = "</each>";
        String exact = xml(open.repeat(63) + include("part", "copy") + close.repeat(63));
        assertTrue(compiler.compile(exact).render(Map.of("items", List.of())).blocks().isEmpty());
        String tooDeep = xml(open.repeat(64) + include("part", "copy") + close.repeat(64));
        var failure = rejected(compiler, tooDeep, TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(Optional.empty(), failure.fragmentName());
        String fragmentDeep = fragment(open.repeat(65) + close.repeat(65));
        var fragmentFailure = rejected(XmlTemplate.builder().fragment("part", fragmentDeep)
                .limits(new XmlTemplate.Limits(10000, 1000, 64, 1000)).build(), xml(include("part", "copy")),
                TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(Optional.empty(), fragmentFailure.fragmentName());
    }

    @Test
    void decodedTextExpansionCountsUnicodeCodePointsForEveryOccurrence() {
        String part = fragment("<p>&#x1f600;&amp;</p>");
        String source = xml(include("part", "a") + include("part", "b"));
        var exact = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(1000, 10, 2, 4)).build();
        assertEquals(List.of("😀&", "😀&"), exact.compile(source).render(Map.of()).paragraphs().stream().map(Paragraph::text).toList());
        var limited = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(1000, 10, 2, 3)).build();
        var failure = rejected(limited, source, TemplateException.Code.BUDGET_EXCEEDED);
        assertEquals(Optional.empty(), failure.fragmentName());
    }

    private static ImageResource image() throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return ImageResource.load(output.toByteArray());
    }

    private static TemplateException rejected(XmlTemplate compiler, String source, TemplateException.Code code) {
        var failure = assertThrows(TemplateException.class, () -> compiler.compile(source));
        assertEquals(code, failure.code());
        assertTrue(failure.line() > 0); assertTrue(failure.column() > 0);
        assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getMessage().contains("https://"));
        return failure;
    }
}
