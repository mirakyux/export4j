// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

/** Action for an unrepresentable feature. Invalid input, I/O, limits and cancellation always fail. */
public enum UnsupportedAction {
    /** Fail before writing output bytes. */
    ERROR,
    /** Apply the documented fallback and return a structured warning. */
    WARN,
    /** Apply the same fallback without a warning. */
    IGNORE
}
