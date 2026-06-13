// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code System.RestContext} — the per-invocation holders for the current REST request and
 * response, exposed (as in the platform) through the public static FIELDS {@code request} and
 * {@code response}.
 *
 * <p>An {@code @RestResource} method reads {@code RestContext.request} and writes
 * {@code RestContext.response}. Locally there's no servlet driving the resource, so a test injects
 * a {@link RestRequest}/{@link RestResponse} by assigning these static fields directly (exactly
 * what Apex unit tests do) and then reads the response back after calling the method.
 */
public final class RestContext {
    public static RestRequest request;
    public static RestResponse response;

    private RestContext() {
    }
}
