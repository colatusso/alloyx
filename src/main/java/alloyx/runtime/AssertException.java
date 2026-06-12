// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Raised when a {@code System.assert*} / {@code Assert.*} check fails (mirrors Apex assert
 * failures). Deliberately NOT an {@link ApexException}: on the platform an assertion failure
 * is fatal and cannot be caught by an Apex {@code try/catch} — transpiled
 * {@code catch (Exception e)} maps to {@code catch (ApexException e)}, so staying outside
 * that hierarchy preserves the can't-catch semantics locally.
 */
public class AssertException extends RuntimeException {
    public AssertException(String message) {
        super(message);
    }
}
