// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.model.PageSize;

/** Immutable page instructions with a balanced graphics-state stack. Safe to share across threads. */
public record DisplayPage(PageSize size, List<DrawCommand> commands) {
    public DisplayPage {
        Objects.requireNonNull(size, "size");
        commands = List.copyOf(commands);
        int depth = 0;
        for (DrawCommand command : commands) {
            if (command instanceof DrawCommand.Save) {
                depth++;
            } else if (command instanceof DrawCommand.Restore && --depth < 0) {
                throw new IllegalArgumentException("restore has no matching save");
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("every save requires a matching restore");
        }
    }
}
