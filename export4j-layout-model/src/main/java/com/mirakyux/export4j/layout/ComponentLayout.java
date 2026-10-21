// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.List;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Immutable component drawing in local top-left coordinates and points; balanced commands own no live resources. */
public record ComponentLayout(double width, double height, int textLines,
        List<DrawCommand> commands, List<TextDiagnostic> diagnostics, List<SemanticPlacement> semanticPlacements) {
    public ComponentLayout {
        commands = List.copyOf(commands);
        diagnostics = List.copyOf(diagnostics);
        semanticPlacements = List.copyOf(semanticPlacements);
        new DisplayPage(new PageSize(width, height), commands);
        if (textLines < 0) {
            throw new IllegalArgumentException("text line count must be nonnegative");
        }
        if (semanticPlacements.size() > 100_000) {
            throw new IllegalArgumentException("semantic placement budget exceeded");
        }
        int previous = -1;
        for (var placement : semanticPlacements) {
            if (placement.commandIndex() < previous || placement.commandIndex() > commands.size()) {
                throw new IllegalArgumentException("semantic insertion indices must be ordered within the command list");
            }
            previous = placement.commandIndex();
        }
    }

    /** Compatibility constructor for static drawings with no deferred semantic content. */
    public ComponentLayout(double width, double height, int textLines,
            List<DrawCommand> commands, List<TextDiagnostic> diagnostics) {
        this(width, height, textLines, commands, diagnostics, List.of());
    }

    /** Returns complete drawing commands for a static component.
     * @throws IllegalStateException when deferred semantic content requires semantic composition
     */
    @Override
    public List<DrawCommand> commands() {
        if (!semanticPlacements.isEmpty()) {
            throw new IllegalStateException("semantic component content requires semantic composition");
        }
        return commands;
    }

    /** Static command snapshot, excluding semantic insertions. Use only with semanticPlacements(). */
    public List<DrawCommand> staticCommands() {
        return commands;
    }
}
