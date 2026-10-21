// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Execution failure with its last stage/block/page context, without document content. */
public final class ExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code { CANCELLED, TIMEOUT, OUTPUT_LIMIT }
    private final Code code;
    private final ExecutionOptions.Progress context;

    ExecutionException(Code code, ExecutionOptions.Progress context) {
        super("document execution failed: " + code + " at " + context.stage());
        this.code = code;
        this.context = context;
    }

    public Code code() { return code; }
    public ExecutionOptions.Progress context() { return context; }
}
