// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reproducible templates, source-preserving edits, measured costs and real failure boundaries. */
public final class DesignerScenarios {
    private DesignerScenarios() { }
    /** Writes to a new output directory; timings and memory observations are environment-specific. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) { throw new IllegalArgumentException("provide one new output directory"); }
        Path output = Path.of(arguments[0]); Files.createDirectory(output);
        var scenarios = new ArrayList<Object>();
        for (int sample = 0; sample < DesignerSamples.NAMES.size(); sample++) {
            long start = System.nanoTime();
            var project = DesignerSamples.create(sample); Path directory = Files.createDirectory(output.resolve("scenario-" + (sample + 1)));
            project.bundle().write(directory.resolve("template.zip")); DesignerService.saveText(directory.resolve("template.xml"), project.bundle().source());
            var reopened = new DesignerProject(XmlTemplateBundle.read(Files.readAllBytes(directory.resolve("template.zip"))), project.data());
            var old = reopened.bundle().source();
            if (!old.equals(project.bundle().source()) || !reopened.bundle().fragments().equals(project.bundle().fragments())) { throw new IOException("template source did not survive ZIP reopen"); }
            // Change one visible source marker while retaining variables, conditionals and loops.
            String edited = old.replace("REPORT-START", "REPORT-EDITED").replace("FORM-START", "FORM-EDITED")
                    .replace("LEDGER-START", "LEDGER-EDITED").replace("REFERENCE-START", "REFERENCE-EDITED")
                    .replace("病例编号*", "报告编号*");
            project = reopened.source(edited, project.data().replace("REPORT-START", "REPORT-EDITED"));
            DesignerService.saveText(directory.resolve("data.json"), project.data());
            project.bundle().write(directory.resolve("edited.zip"));
            var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
            var image = preview.image(0); try { javax.imageio.ImageIO.write(image, "png", directory.resolve("preview.png").toFile()); } finally { image.flush(); }
            var exports = new ArrayList<Object>();
            for (var format : DesignerService.Format.values()) {
                long exportStart = System.nanoTime();
                var rules = DesignerService.policy(format, ProcessingPolicy.Action.WARN, "RECOMMENDED");
                var prepared = DesignerService.prepare(project, format, rules, DesignerService.execution(() -> false));
                String name = "document." + format.extension; var result = prepared.to(directory.resolve(name));
                if (!prepared.preview().issues().equals(result.report().issues())) { throw new IOException("preflight and actual loss report differ"); }
                exports.add(BindingJson.object("format", format.name(), "file", name, "bytes", result.bytesWritten(), "milliseconds", elapsed(exportStart),
                        "losses", result.report().losses().size(), "warnings", result.report().warnings().size()));
            }
            scenarios.add(BindingJson.object("name", DesignerSamples.NAMES.get(sample), "directory", directory.getFileName().toString(),
                    "pages", preview.layout().display().pages().size(), "milliseconds", elapsed(start), "observedHeapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                    "previewBytes", Files.size(directory.resolve("preview.png")), "exports", exports));
        }
        var failures = failures(output);
        DesignerService.saveText(output.resolve("report.json"), BindingJson.write(BindingJson.object("status", "passed", "jdk", System.getProperty("java.version"),
                "scenarios", scenarios, "failureControls", failures, "scope", "finite business scenarios; elapsed wall time and observed heap, not RSS or capacity guarantee")));
        System.out.println("Designer: " + scenarios.size() + " edited/reopened business templates, "
                + scenarios.size() * DesignerService.Format.values().length + " exports, " + failures.size() + " failure controls");
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static List<String> failures(Path directory) throws Exception {
        var checked = new ArrayList<String>();
        var project = DesignerSamples.create(0); String original = project.bundle().source();
        reject("malformed XML", () -> project.source("<document>", project.data()), checked);
        reject("external entity", () -> project.source("<!DOCTYPE document [<!ENTITY ex SYSTEM 'file:///denied'>]><document version='1'><p>&ex;</p></document>", "{}"), checked);
        reject("unknown template element", () -> project.source(original.replace("<p id=\"report-end\"", "<unknown id=\"report-end\"").replace("REPORT-END · 所有业务数据均为虚构样例。</p>", "REPORT-END · 所有业务数据均为虚构样例。</unknown>"), project.data()), checked);
        reject("missing binding", () -> DesignerService.preview(new DesignerProject(project.bundle(), "{}"), DesignerService.execution(() -> false)), checked);
        reject("JSON duplicate key", () -> BindingJson.parse("{\"value\":1,\"value\":2}"), checked);
        reject("JSON null", () -> BindingJson.parse("{\"value\":null}"), checked);
        reject("cancelled preview", () -> DesignerService.preview(project, DesignerService.execution(() -> true)), checked);
        reject("source budget", () -> DesignerXml.parse(" ".repeat(1_000_001)), checked);
        var references = DesignerSamples.create(3);
        reject("strict navigation export", () -> DesignerService.prepare(references, DesignerService.Format.HTML, ProcessingPolicy.STRICT, DesignerService.execution(() -> false)), checked);
        var wrongTreatment = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        reject("unsupported static treatment", () -> DesignerService.prepare(references, DesignerService.Format.PNG, wrongTreatment, DesignerService.execution(() -> false)), checked);
        Path target = directory.resolve("old-target.bin"); byte[] before = new byte[]{1, 2, 3, 4}; Files.write(target, before);
        reject("output budget keeps target", () -> DesignerService.prepare(project, DesignerService.Format.PDF, ProcessingPolicy.STRICT,
                DesignerService.execution(() -> false).toBuilder().maxOutputBytes(16).build()).to(target), checked);
        if (!java.util.Arrays.equals(before, Files.readAllBytes(target))) { throw new IOException("old target changed on failure"); }
        Files.delete(target);
        return List.copyOf(checked);
    }
    @FunctionalInterface private interface Check { Object run() throws Exception; }
    private static void reject(String name, Check check, List<String> passed) throws Exception {
        try { check.run(); } catch (RuntimeException | IOException | org.xml.sax.SAXException expected) { passed.add(name); return; }
        throw new IOException("failure control unexpectedly accepted: " + name);
    }
}
