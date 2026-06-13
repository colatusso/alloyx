// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.LinkedHashMap;

/**
 * Apex {@code System.RestResponse} — the outbound response an {@code @RestResource} method writes.
 *
 * <p>Like {@link RestRequest}, the platform exposes its members as public instance FIELDS, so a
 * resource sets {@code RestContext.response.statusCode}/{@code responseBody} directly and a test
 * reads them back. Pure local data; {@code addHeader} mutates the header map per the docs.
 */
public final class RestResponse {
    public Blob responseBody;
    public Integer statusCode;
    public String responseURI;
    public final java.util.Map<String, String> headers = new LinkedHashMap<>();

    public RestResponse() {
    }

    /** Add (or replace) a response header. */
    public void addHeader(String name, String value) {
        headers.put(name, value);
    }
}
