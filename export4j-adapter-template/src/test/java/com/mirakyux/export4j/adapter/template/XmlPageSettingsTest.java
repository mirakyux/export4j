// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlPageSettingsTest {
    private static final PageSettings JAVA_PAGE = new PageSettings(new PageSize(420, 300), new PageMargins(11, 12, 13, 14));

    @Test void absentAndEmptyDeclarationsKeepAllJavaDefaultsAndDoNotCreateBlocksOrConsumeIds() {
        for (String declaration : List.of("", "<page/>", "<page><!-- ignored --></page>")) {
            var template = compiler().compile(xml(declaration + "<p>${name}</p><p>Tail</p>"));
            var document = template.render(Map.of("name", "Report"));
            assertEquals(JAVA_PAGE, template.pageSettings());
            assertEquals(JAVA_PAGE, document.pageSettings());
            assertEquals(2, template.nodes().size());
            assertEquals(List.of("xml-1", "xml-2"), document.blocks().stream().map(block -> block.id()).toList());
            assertEquals(List.of("Report", "Tail"), document.paragraphs().stream().map(Paragraph::text).toList());
        }
        assertEquals(PageSettings.A4, XmlTemplate.builder().build().compile(xml("<page/>")).render(Map.of()).pageSettings());
    }

    @Test void commentsAndWhitespaceBeforeTheFirstPageAreIgnoredAndPageOnlyDocumentsRemainEmpty() {
        var compiled = compiler().compile("""
                <document version="1" title="${name}">
                  <!-- page configuration precedes content -->
                  <page size="LETTER"/>
                </document>
                """);
        var result = compiled.render(Map.of("name", "Empty report"));
        assertEquals(PageSize.LETTER, result.pageSettings().size());
        assertEquals(JAVA_PAGE.margins(), result.pageSettings().margins());
        assertEquals("Empty report", result.metadata().title());
        assertTrue(compiled.nodes().isEmpty());
        assertTrue(result.blocks().isEmpty());
    }

    @Test void namedPaperSizesKeepTheirNativePortraitDimensionsAndInheritedMargins() {
        for (var fixture : Map.of("A4", PageSize.A4, "LETTER", PageSize.LETTER).entrySet()) {
            var result = page("size='" + fixture.getKey() + "'");
            assertEquals(fixture.getValue(), result.size());
            assertEquals(JAVA_PAGE.margins(), result.margins());
        }
    }

    @Test void explicitOrientationReordersDefaultsNamedAndCustomSizesWithoutRotatingMargins() {
        for (String dimensions : List.of("", "width='420' height='300'", "width='300' height='420'")) {
            assertEquals(new PageSettings(new PageSize(300, 420), JAVA_PAGE.margins()), page(dimensions + " orientation='PORTRAIT'"));
            assertEquals(new PageSettings(new PageSize(420, 300), JAVA_PAGE.margins()), page(dimensions + " orientation='LANDSCAPE'"));
        }
        assertEquals(new PageSize(PageSize.A4.height(), PageSize.A4.width()), page("size='A4' orientation='LANDSCAPE'").size());
        assertEquals(PageSize.LETTER, page("size='LETTER' orientation='PORTRAIT'").size());
        assertEquals(new PageSize(200, 200), page("width='200' height='200' orientation='LANDSCAPE'").size());
    }

    @Test void absentOrientationPreservesCallerAndExplicitWidthHeightOrder() {
        assertEquals(new PageSize(420, 300), page("").size());
        assertEquals(new PageSize(380, 220), page("width='380' height='220'").size());
        assertEquals(new PageSize(220, 380), page("width='220' height='380'").size());
    }

    @Test void allDocumentedLengthUnitsProduceTheSamePointGeometry() {
        for (String dimensions : List.of("width='576' height='720'", "width='576pt' height='720pt'",
                "width='203.2mm' height='254mm'", "width='20.32cm' height='25.4cm'", "width='8in' height='10in'")) {
            var result = page(dimensions + " margin='0'");
            assertEquals(576, result.size().width(), 1.0e-10);
            assertEquals(720, result.size().height(), 1.0e-10);
            assertEquals(PageMargins.uniform(0), result.margins());
        }
        var margins = page("width='8in' height='10in' margin-top='25.4mm' margin-right='2.54cm' margin-bottom='1in' margin-left='72pt'");
        for (double margin : List.of(margins.margins().top(), margins.margins().right(), margins.margins().bottom(), margins.margins().left())) {
            assertEquals(72, margin, 1.0e-10);
        }
    }

    @Test void decimalLeadingAndTrailingDotsAndZeroMarginsAreLegal() {
        var result = page("width='.5in' height='1.in' margin='.0' margin-top='0.0mm' margin-right='0cm' margin-bottom='0in' margin-left='0pt'");
        assertEquals(new PageSize(36, 72), result.size());
        assertEquals(PageMargins.uniform(0), result.margins());
        assertEquals(new PageSize(1, 2), page("width='1.' height='2.' margin='0'").size());
    }

    @Test void uniformMarginsApplyBeforeSpecificEdgesRegardlessOfAttributeOrder() {
        var expected = new PageMargins(20, 18, 22, 24);
        assertEquals(expected, page("margin='18' margin-top='20' margin-bottom='22' margin-left='24'").margins());
        assertEquals(expected, page("margin-left='24' margin-bottom='22' margin-top='20' margin='18'").margins());
        assertEquals(new PageMargins(11, 0, 13, 14), page("margin-right='0'").margins());
        assertEquals(new PageMargins(0, 0, 0, 0), page("margin='0'").margins());
    }

    @Test void sizeAndExplicitDimensionsAreMutuallyExclusiveAndWidthHeightMustBePaired() {
        for (String attributes : List.of("size='A4' width='200'", "size='LETTER' height='300'",
                "size='A4' width='200' height='300'", "width='200'", "height='300'")) {
            rejected(compiler(), xml("<page " + attributes + "/>"), TemplateException.Code.INVALID_STRUCTURE, 1);
        }
    }

    @Test void namesAndOrientationAreExactStaticEnums() {
        for (String value : List.of("a4", "A3", "letter", "LEGAL", " A4", "A4 ", "", "420x300")) {
            rejected(compiler(), xml("<page size='" + value + "'/>"), TemplateException.Code.INVALID_VALUE, 1);
        }
        for (String value : List.of("portrait", "landscape", "Landscape", "AUTO", " PORTRAIT", "LANDSCAPE ", "")) {
            rejected(compiler(), xml("<page orientation='" + value + "'/>"), TemplateException.Code.INVALID_VALUE, 1);
        }
    }

    @Test void lengthsRejectJavaNumericExtensionsSignsWhitespaceAndUnknownUnits() {
        for (String value : List.of("", ".", "+100", "-1", "-0", "1e2", "1E2", "0x1.0p8", "NaN", "Infinity",
                "100f", "100d", "1_000", "1,5", "1PX", "1px", "1PT", "1MM", "1 em", "1 mm", " 100", "100 ", "١", "1..0")) {
            for (String attribute : List.of("width", "height", "margin", "margin-top", "margin-right", "margin-bottom", "margin-left")) {
                String dimensions = attribute.equals("width") ? "height='300' " : attribute.equals("height") ? "width='420' " : "";
                rejected(compiler(), xml("<page " + dimensions + attribute + "='" + value + "'/>"), TemplateException.Code.INVALID_VALUE, 1);
            }
        }
    }

    @Test void dimensionsRejectZeroUnderflowAndNonfiniteConversionButDoNotInventAnArbitraryMinimum() {
        for (String value : List.of("0", "0pt", ".0mm", "0." + "0".repeat(324) + "1", "9".repeat(309), "9".repeat(308) + "in")) {
            rejected(compiler(), xml("<page width='" + value + "' height='300' margin='0'/>"), TemplateException.Code.INVALID_VALUE, 1);
        }
        var tiny = page("width='0.000001' height='0.000002' margin='0'");
        assertEquals(0.000001, tiny.size().width());
        assertEquals(0.000002, tiny.size().height());
        rejected(compiler(), xml("<page margin='" + "9".repeat(309) + "'/>"), TemplateException.Code.INVALID_VALUE, 1);
        rejected(compiler(), xml("<page margin-top='" + "9".repeat(308) + "in'/>"), TemplateException.Code.INVALID_VALUE, 1);
    }

    @Test void everyKnownPageAttributeRejectsDataExpressionsAtCompileTime() {
        for (String attribute : List.of("size", "orientation", "width", "height", "margin", "margin-top", "margin-right", "margin-bottom", "margin-left")) {
            String dimensions = attribute.equals("width") ? "height='300' " : attribute.equals("height") ? "width='420' " : "";
            for (String expression : List.of("${secret}", "12${secret}", "${unterminated")) {
                rejected(compiler(), xml("<page " + dimensions + attribute + "='" + expression + "'/>"), TemplateException.Code.INVALID_EXPRESSION, 1);
            }
        }
    }

    @Test void allUnknownPageAttributesAreRejectedBeforeTheirValuesAreInterpreted() {
        for (String attribute : List.of("id", "paper", "landscape", "font", "marginTop", "src", "href", "unknown")) {
            rejected(compiler(), xml("<page " + attribute + "='${SECRET}'/>"), TemplateException.Code.UNSUPPORTED_ATTRIBUTE, 1);
        }
    }

    @Test void marginsMustLeaveStrictlyPositiveContentInBothDimensionsAfterOrientation() {
        for (String attributes : List.of("width='100' height='80' margin='50'", "width='100' height='80' margin='40'",
                "width='100' height='80' margin='0' margin-left='50' margin-right='50'",
                "width='100' height='80' margin='0' margin-top='79' margin-bottom='1'",
                "width='100' height='200' orientation='LANDSCAPE' margin='60'")) {
            rejected(compiler(), xml("<page " + attributes + "/>"), TemplateException.Code.INVALID_VALUE, 1);
        }
        var valid = page("width='100' height='80' margin='39.999'");
        assertTrue(valid.contentWidth() > 0);
        assertTrue(valid.contentHeight() > 0);
    }

    @Test void declarationsMustBeFirstAndUniqueEvenWhenPrecedingControlsWouldProduceNoBlocks() {
        for (String prefix : List.of("<p>Body</p>", "<page/>", "<if test='flag'><then/></if>", "<each items='items' as='item'/>")) {
            rejected(compiler(), "<document version='1'>" + prefix + "\n<page/></document>", TemplateException.Code.INVALID_STRUCTURE, 2);
        }
    }

    @Test void pageContentMustBeEmptyRatherThanSilentlyBecomingDiscardedBodyContent() {
        for (String content : List.of(" ", "<![CDATA[ ]]>", "SECRET_BODY", "<![CDATA[SECRET_BODY]]>", "<p>Body</p>", "<page/>", "<page-break/>")) {
            rejected(compiler(), xml("<page>" + content + "</page>"), TemplateException.Code.INVALID_STRUCTURE, 1);
        }
    }

    @Test void nestedDeclarationsFailInEveryConditionalBranchAndZeroIterationPrototype() {
        for (String content : List.of("<if test='flag'><then><page/></then><else><p>Safe</p></else></if>",
                "<if test='flag'><then><p>Safe</p></then><else><page/></else></if>",
                "<each items='items' as='item'><page/></each>", "<p><page/></p>",
                "<table><columns><column weight='1'/></columns><row><cell><page/></cell></row></table>",
                "<rows items='items' as='item'><columns><column weight='1'/></columns><row><cell><page/></cell></row></rows>")) {
            rejected(compiler(), xml(content), TemplateException.Code.INVALID_STRUCTURE, 1);
        }
    }

    @Test void anIncludeOccupiesTheMainSourceFirstPositionEvenWhenItExpandsToNothing() {
        for (String part : List.of("", "<p>Included</p>")) {
            var compiler = XmlTemplate.builder().fragment("part", fragment(part)).build();
            rejected(compiler, "<document version='1'><include ref='part' prefix='copy'/>\n<page/></document>",
                    TemplateException.Code.INVALID_STRUCTURE, 2);
        }
        var result = XmlTemplate.builder().fragment("part", fragment("<p id='body'>Included</p>")).build()
                .compile(xml("<page size='LETTER'/><include ref='part' prefix='copy'/>" )).render(Map.of());
        assertEquals(PageSize.LETTER, result.pageSettings().size());
        assertEquals("copy-body", result.blocks().getFirst().id());
    }

    @Test void fragmentPagesCannotBecomeTheMainDeclarationAndPreserveTheirOwnLeafLocations() {
        for (String content : List.of("<page/>", "<p>Before</p><page/>",
                "<if test='flag'><then><p>Safe</p></then><else><page/></else></if>",
                "<each items='items' as='item'><page/></each>")) {
            String part = "<fragment version='1'>\n" + content + "</fragment>";
            var compiler = XmlTemplate.builder().fragment("SECRET_part", part).build();
            for (String prefix : List.of("", "<page/>", "<p>Body</p>")) {
                var failure = rejected(compiler, xml(prefix + "<include ref='SECRET_part' prefix='copy'/>"),
                        TemplateException.Code.INVALID_STRUCTURE, 2);
                assertEquals(Optional.of("SECRET_part"), failure.fragmentName());
                assertFalse(failure.getMessage().contains("SECRET_part"));
            }
        }
    }

    @Test void unreferencedFragmentsKeepTheirExistingLazyCompilationBoundary() {
        var compiler = XmlTemplate.builder().pageSettings(JAVA_PAGE).fragment("unused", fragment("<page/>"))
                .fragment("malformed", "MALFORMED_SECRET").build();
        var result = compiler.compile(xml("<page size='A4'/><p>Body</p>")).render(Map.of());
        assertEquals(PageSize.A4, result.pageSettings().size());
        assertEquals("Body", result.paragraphs().getFirst().text());
    }

    @Test void pageFailuresRetainPageLocationWithoutExposingDimensionsOrBody() {
        var failure = rejected(compiler(), "<document version='1'>\n<!-- preceding comment -->\n<page width='SECRET_WIDTH' height='300'/>\n<p>SECRET_BODY</p></document>",
                TemplateException.Code.INVALID_VALUE, 3);
        assertEquals(Optional.empty(), failure.fragmentName());
        assertFalse(failure.getMessage().contains("SECRET_WIDTH"));
        assertFalse(failure.getMessage().contains("SECRET_BODY"));
    }

    @Test void pageCountsTowardsInputElementAndDepthBudgetsDespiteNotProducingAModelBlock() {
        String source = xml("<page width='300' height='400' margin='10'/><p>X</p>");
        var exact = new XmlTemplate.Limits(source.length(), 3, 2, 1);
        var result = XmlTemplate.builder().limits(exact).build().compile(source).render(Map.of());
        assertEquals(1, result.blocks().size());
        assertEquals(new PageSize(300, 400), result.pageSettings().size());
        for (var limits : List.of(new XmlTemplate.Limits(source.length() - 1, 3, 2, 1),
                new XmlTemplate.Limits(source.length(), 2, 2, 1), new XmlTemplate.Limits(source.length(), 3, 1, 1))) {
            rejected(XmlTemplate.builder().limits(limits).build(), source, TemplateException.Code.BUDGET_EXCEEDED, 1);
        }
    }

    @Test void pageAlsoCountsTowardsExpansionBudgetsWhenIncludesAppearAfterIt() {
        String part = fragment("<p>A</p><p>B</p><p>C</p>");
        var content = new StringBuilder("<page size='LETTER'/>");
        for (int index = 0; index < 10; index++) {
            content.append("<include ref='part' prefix='copy").append(index).append("'/>");
        }
        String source = xml(content.toString());
        var exact = XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 42, 2, 30)).build();
        var result = exact.compile(source).render(Map.of());
        assertEquals(30, result.blocks().size());
        assertEquals(PageSize.LETTER, result.pageSettings().size());
        rejected(XmlTemplate.builder().fragment("part", part).limits(new XmlTemplate.Limits(10000, 41, 2, 30)).build(), source,
                TemplateException.Code.BUDGET_EXCEEDED, 1);
    }

    @Test void pageDoesNotConsumeRenderedBlockBudgetAndBindingStillRejectsExcessRealContent() {
        var compiler = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(1000, 1, 100, 1000, 32)).build();
        var one = compiler.compile(xml("<page size='LETTER'/><p>${name}</p>"));
        assertEquals(1, one.render(Map.of("name", "Body")).blocks().size());
        var two = compiler.compile(xml("<page size='LETTER'/><each items='items' as='item'><p>${item}</p></each>"));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, assertThrows(DocumentTemplate.BindingException.class,
                () -> two.render(Map.of("items", List.of("A", "B")))).code());
    }

    @Test void builderSnapshotsAndCompiledTemplatesDoNotObserveLaterConfigurationChanges() {
        var builder = XmlTemplate.builder().pageSettings(JAVA_PAGE);
        var first = builder.build();
        var compiled = first.compile(xml("<page margin-left='15'/><p>${name}</p>"));
        var changed = PageSettings.builder().size(600, 800).margins(30).build();
        var second = builder.pageSettings(changed).build();
        assertEquals(JAVA_PAGE, first.compile(xml("<page/>")).pageSettings());
        assertEquals(changed, second.compile(xml("<page/>")).pageSettings());
        for (String name : List.of("First", "Second")) {
            var result = compiled.render(Map.of("name", name));
            assertEquals(new PageSettings(JAVA_PAGE.size(), new PageMargins(11, 12, 13, 15)), result.pageSettings());
            assertEquals(name, result.paragraphs().getFirst().text());
        }
    }

    @Test void independentCompilesAndFailedCompilesNeverChangeTheCompilerDefaults() {
        var compiler = compiler();
        var wide = compiler.compile(xml("<page size='LETTER' orientation='LANDSCAPE' margin='18'/><p>Wide</p>"));
        rejected(compiler, xml("<page margin='1000'/><p>Bad</p>"), TemplateException.Code.INVALID_VALUE, 1);
        var inherited = compiler.compile(xml("<page/><p>Default</p>"));
        var narrow = compiler.compile(xml("<page width='200' height='400' margin='0'/><p>Narrow</p>"));
        assertEquals(JAVA_PAGE, inherited.pageSettings());
        assertEquals(new PageSettings(new PageSize(792, 612), PageMargins.uniform(18)), wide.render(Map.of()).pageSettings());
        assertEquals(new PageSettings(new PageSize(200, 400), PageMargins.uniform(0)), narrow.pageSettings());
        assertEquals(JAVA_PAGE, compiler.compile(xml("<p>Legacy</p>")).pageSettings());
    }

    @Test void sharedCompilerSupportsConcurrentPageOverridesWithoutLeakingConfigurationOrData() throws Exception {
        var compiler = compiler();
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Document>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 32; index++) {
                int number = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    String declaration = number % 2 == 0 ? "<page size='LETTER' orientation='LANDSCAPE' margin='18'/>" : "<page/>";
                    return compiler.compile(xml(declaration + "<p>${name}</p>")).render(Map.of("name", "Report-" + number));
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            for (int index = 0; index < futures.size(); index++) {
                var result = futures.get(index).get(10, TimeUnit.SECONDS);
                assertEquals(index % 2 == 0 ? new PageSettings(new PageSize(792, 612), PageMargins.uniform(18)) : JAVA_PAGE, result.pageSettings());
                assertEquals("Report-" + index, result.paragraphs().getFirst().text());
                assertEquals("xml-1", result.blocks().getFirst().id());
            }
        }
    }

    @Test void borrowedReadersRemainOpenOnSuccessfulPageCompilationAndPageValidationFailure() throws IOException {
        var success = new TrackingReader(xml("<page size='LETTER'/><p>Body</p>"));
        assertEquals(PageSize.LETTER, compiler().compile(success).pageSettings().size());
        assertFalse(success.closed);
        assertEquals(-1, success.read());
        var rejected = new TrackingReader(xml("<page width='200'/><p>Body</p>"));
        assertEquals(TemplateException.Code.INVALID_STRUCTURE, assertThrows(TemplateException.class, () -> compiler().compile(rejected)).code());
        assertFalse(rejected.closed);
        assertEquals(-1, rejected.read());
        var cause = new IOException("synthetic reader failure");
        var broken = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException { throw cause; }
            @Override public void close() { throw new AssertionError("borrowed reader closed"); }
        };
        assertSame(cause, assertThrows(IOException.class, () -> compiler().compile(broken)));
    }

    @Test void boundedReaderCompilationCountsThePageSourceAndStopsBeforeReadingUnboundedInput() {
        String source = xml("<page size='LETTER'/><p>" + "X".repeat(10000) + "</p>");
        var reader = new TrackingReader(source);
        var compiler = XmlTemplate.builder().limits(new XmlTemplate.Limits(80, 10, 10, 10000)).build();
        assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class, () -> compiler.compile(reader)).code());
        assertTrue(reader.readCount <= 81);
        assertFalse(reader.closed);
    }

    @Test void cancellationDuringBorrowedPageInputRestoresScopeAndKeepsCompilerReusable() {
        var cancelled = new AtomicBoolean();
        var reader = new TrackingReader(xml("<page size='LETTER'/><p>Body</p>"));
        var triggering = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                int count = reader.read(buffer, offset, length);
                cancelled.set(true);
                return count;
            }
            @Override public void close() { throw new AssertionError("borrowed reader closed"); }
        };
        var compiler = compiler();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> compiler.compile(triggering)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertFalse(reader.closed);
        ExecutionContext.checkpoint();
        assertEquals(JAVA_PAGE, compiler.compile(xml("<page/><p>Reuse</p>")).pageSettings());
    }

    @Test void cancellationDuringCompilationAndBindingDoesNotRetainAPartialPageOverride() {
        var compiler = compiler();
        String source = xml("<page size='LETTER' orientation='LANDSCAPE'/>" + "<p>X</p>".repeat(1000));
        var checkpoints = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 100).build(), () -> compiler.compile(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checkpoints.get() > 100);
        ExecutionContext.checkpoint();
        var compiled = compiler.compile(xml("<page size='LETTER'/><p>${name}</p>"));
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> true).build(), () -> compiled.render(Map.of("name", "SECRET")))).code());
        ExecutionContext.checkpoint();
        assertEquals(PageSize.LETTER, compiled.render(Map.of("name", "Reuse")).pageSettings().size());
        assertEquals(JAVA_PAGE, compiler.compile(xml("<p>Default</p>")).pageSettings());
    }

    private static XmlTemplate compiler() { return XmlTemplate.builder().pageSettings(JAVA_PAGE).build(); }
    private static PageSettings page(String attributes) { return compiler().compile(xml("<page " + attributes + "/>")).render(Map.of()).pageSettings(); }
    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String fragment(String content) { return "<fragment version='1'>" + content + "</fragment>"; }

    private static TemplateException rejected(XmlTemplate compiler, String source, TemplateException.Code expected, int line) {
        var failure = assertThrows(TemplateException.class, () -> compiler.compile(source));
        assertEquals(expected, failure.code(), () -> "unexpected failure for " + source);
        assertEquals(line, failure.line());
        assertTrue(failure.column() > 0);
        assertFalse(failure.getMessage().contains("SECRET"));
        return failure;
    }

    private static final class TrackingReader extends Reader {
        private final StringReader input;
        private boolean closed;
        private int readCount;
        private TrackingReader(String source) { input = new StringReader(source); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = input.read(buffer, offset, length);
            if (count > 0) { readCount += count; }
            return count;
        }
        @Override public void close() { closed = true; input.close(); }
    }
}
