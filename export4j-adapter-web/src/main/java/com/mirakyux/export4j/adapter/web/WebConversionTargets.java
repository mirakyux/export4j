// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class WebConversionTargets {
    private WebConversionTargets() { }
    static DocumentConversion.Target target(boolean html, ExportLimits limits, Map<String, FontStyle> fonts) {
        String format = html ? "HTML" : "MARKDOWN";
        return new DocumentConversion.Target(format, (document, notes, policy, output) -> {
            var issues = new ArrayList<ProcessingIssue>();
            for (var note : notes) {
                var rule = policy.resolve(format, "NOTES", "EXPORT");
                var issue = new ProcessingIssue("EXPORT", "MODEL", format, "NOTES", "EXPORT",
                        new ProcessingIssue.Location("", "BODY", note.paragraphId(), List.of(), -1, -1),
                        "note text has no Web export mapping", rule.action(), rule.treatment(), List.of("NOTE_TEXT_REMOVED"));
                if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
                if (rule.treatment() != ProcessingPolicy.Treatment.DISCARD) { throw new IllegalArgumentException("Web notes require explicit DISCARD"); }
                if (issues.size() >= limits.maxDiagnostics()) { throw new IllegalArgumentException("Web note report budget exceeded"); }
                issues.add(issue);
            }
            var rendered = new WebRenderer(document, html, ExportPolicy.STRICT, limits, fonts, policy).render();
            if (rendered.report().issues().size() > limits.maxDiagnostics() - issues.size()) { throw new IllegalArgumentException("Web report budget exceeded"); }
            issues.addAll(rendered.report().issues()); output.write(rendered.bytes());
            return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
        });
    }
}
