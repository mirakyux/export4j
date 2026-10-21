// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Self-authored, fixed-font text corpus. Logical text and reader behavior are separate evidence. */
public final class TextCorpusExample {
    private static final Path OUTPUT = Path.of("examples/build/text-corpus");
    private static final PageSettings PAGE = PageSettings.builder().size(320, 480).margins(24).build();
    private static final ParagraphFormat BODY = ParagraphFormat.builder().fontSize(16).lineHeight(40).build();
    private record Sample(String id, String font, String text, String query) { }
    private TextCorpusExample() { }

    /** Generates separate semantic and component documents for six writing systems. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var samples = List.of(
                new Sample("latin", "LiberationSans-Regular.ttf",
                        "Cafe\u0301 a\u0300 n\u0303 A\u0308 ffi AVATAR. ".repeat(3) + "\n123 e\u0301 FINISH", "Cafe\u0301"),
                new Sample("hebrew", "LiberationSans-Regular.ttf",
                        "עברית שָׁלוֹם עולם 123 English ".repeat(3) + "\n123 English סוף", "עברית"),
                new Sample("arabic", "NotoNaskhArabic-Regular.ttf",
                        "العَرَبِيَّة لا سلام 123 English ".repeat(3) + "\n123 English نهاية", "سلام"),
                new Sample("devanagari", "NotoSansDevanagari-Regular.ttf",
                        "हिन्दी क्षि प्रार्थना कर्म 123 English ".repeat(3) + "\n123 English समाप्त", "हिन्दी"),
                new Sample("thai", "NotoSansThai-Regular.ttf",
                        "ภาษาไทย กำลังทดสอบ การจัดวางข้อความ ".repeat(3) + "\n123 English จบ", "ภาษาไทย"),
                new Sample("cjk", "LXGWWenKaiTC-Regular.ttf",
                        "中文排版（标点测试）和英文 English。".repeat(3) + "\n123 结束", "中文排版"));
        var manifest = new ArrayList<String>();
        manifest.add("variant\tid\tfile\tfont\tsourceBase64\tqueryBase64\tpages");
        for (var sample : samples) {
            var primary = AuthoringExample.face(sample.font());
            var group = FontGroup.strict(sample.font().equals("LiberationSans-Regular.ttf") ? List.of(primary)
                    : List.of(primary, AuthoringExample.face("LiberationSans-Regular.ttf")));
            var engine = DocumentEngine.builder(group).build();
            for (var variant : List.of("semantic", "component")) {
                var document = Document.builder().pageSettings(PAGE).bodyFormat(BODY).heading("label", sample.id(), 1, BODY);
                if (variant.equals("semantic")) {
                    document.paragraph(sample.text());
                } else {
                    document.add(new ComponentBlock("sample", OpenTypeText.builder(sample.text())
                            .style(new ParagraphStyle(16, 40, 0, 0, com.mirakyux.export4j.text.TextAlignment.LEFT)).build()));
                }
                String file = sample.id() + "-" + variant + ".pdf";
                var layout = engine.export(document.build()).backend(new PdfBackend()).to(OUTPUT.resolve(file));
                manifest.add(String.join("\t", variant, sample.id(), file, sample.font(), encoded(sample.text()),
                        encoded(sample.query()), Integer.toString(layout.display().pages().size())));
            }
        }
        Files.write(OUTPUT.resolve("manifest.tsv"), manifest, StandardCharsets.UTF_8);
        System.out.println("Text corpus: " + samples.size() * 2 + " fixed-font PDF fixtures");
    }

    private static String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
}
