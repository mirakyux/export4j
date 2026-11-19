// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SourceContentParser;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XmlTemplateSyntaxTest {
    @Test void preparationDoesNotCaptureOrOpenTheConfiguredContentParser() throws Exception {
        var prepared = XmlTemplate.builder().contentParser(() -> { throw new AssertionError("preparation opened a parser"); })
                .build().prepare(new StringReader("<document version='1'><content id='body' source='body'/></document>"));
        var opened = new AtomicInteger(); var closed = new AtomicInteger();
        SourceContentParser parser = () -> {
            opened.incrementAndGet();
            return new SourceContentParser.Session() {
                @Override public List<com.mirakyux.export4j.model.Block> parse(String namespace, String source,
                        ContentOptions.Resolved options, SourceContentParser.Remaining remaining) {
                    return List.of(new Paragraph(namespace, source, options.paragraphFormat()));
                }
                @Override public void close() { closed.incrementAndGet(); }
            };
        };
        for (int size : List.of(10, 17)) {
            var compiler = XmlTemplate.builder().theme(DocumentTheme.builder().bodyFormat(p -> p.fontSize(size).lineHeight(size + 8)).build())
                    .contentParser(parser).build();
            var template = compiler.compile(prepared);
            assertEquals(size, template.render(Map.of("body", "Current")).paragraphs().getFirst().format().fontSize());
        }
        assertEquals(2, opened.get()); assertEquals(2, closed.get());
    }

    @Test void preparedIncludesRemainStaticButSemanticResourcesAreCheckedAtExecution() {
        var prepared = XmlTemplate.builder().fragment("block", "<fragment version='1'><p id='p'>Original ${name}</p></fragment>")
                .build().prepare("<document version='1'><include ref='block' prefix='first'/></document>");
        var compiler = XmlTemplate.builder().fragment("block", "<fragment version='1'><p>Replacement</p></fragment>").build();
        assertEquals("Original Current", compiler.compile(prepared).render(Map.of("name", "Current")).paragraphs().getFirst().text());
        var image = compiler.prepare("<document version='1'><p><image ref='missing' width='10' height='10'/></p></document>");
        assertEquals(TemplateException.Code.UNKNOWN_RESOURCE, assertThrows(TemplateException.class, () -> compiler.compile(image)).code());
        var invalid = compiler.prepare("<document version='1'><p size='not-a-size'>Text</p></document>");
        assertEquals(TemplateException.Code.INVALID_VALUE, assertThrows(TemplateException.class, () -> compiler.compile(invalid)).code());
        assertThrows(TemplateException.class, () -> compiler.prepare("<!DOCTYPE document SYSTEM 'file:///forbidden'><document version='1'><p>Text</p></document>"));
    }

    @Test void aPreparedSyntaxCannotBypassTighterInputParseExpansionOrBindingBudgets() {
        String source = "<document version='1'><scope><p>Budget text</p></scope></document>";
        var syntax = XmlTemplate.builder().build().prepare(source);
        for (var limits : List.of(new XmlTemplate.Limits(source.length() - 1, 100, 32, 100),
                new XmlTemplate.Limits(10_000, 1, 32, 100), new XmlTemplate.Limits(10_000, 100, 1, 100),
                new XmlTemplate.Limits(10_000, 100, 32, 1))) {
            var compiler = XmlTemplate.builder().limits(limits).build();
            assertEquals(TemplateException.Code.BUDGET_EXCEEDED, assertThrows(TemplateException.class, () -> compiler.compile(syntax)).code());
        }
        var loop = XmlTemplate.builder().build().prepare("<document version='1'><each items='rows' as='row'><p>${row}</p></each></document>");
        var limits = DocumentTemplate.Limits.DEFAULT;
        var compiler = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(limits.maxVisitedNodes(), 1,
                limits.maxRows(), limits.maxCodePoints(), limits.maxDepth())).build();
        assertThrows(DocumentTemplate.BindingException.class, () -> compiler.compile(loop).render(Map.of("rows", List.of("One", "Two"))));
    }

    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void concurrentCompilationAndCancellationLeaveTheSyntaxReusable() throws Exception {
        var compiler = XmlTemplate.builder().build();
        var syntax = compiler.prepare("<document version='1'><p>${name}</p></document>");
        var cancelled = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(cancelled, () -> compiler.compile(syntax)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<String>> tasks = List.of(
                    () -> compiler.compile(syntax).render(Map.of("name", "One")).paragraphs().getFirst().text(),
                    () -> compiler.compile(syntax).render(Map.of("name", "Two")).paragraphs().getFirst().text());
            var futures = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            assertEquals("One", futures.getFirst().get(1, TimeUnit.SECONDS));
            assertEquals("Two", futures.getLast().get(1, TimeUnit.SECONDS));
        }
    }
}
