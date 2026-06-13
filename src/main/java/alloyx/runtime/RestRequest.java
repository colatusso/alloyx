// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.LinkedHashMap;

/**
 * Apex {@code System.RestRequest} — the inbound request for an {@code @RestResource} method.
 *
 * <p>It's a plain DATA carrier (the platform exposes its members as public instance FIELDS, not
 * getters), so it runs LOCALLY: a test injects one via {@link RestContext} and the resource reads
 * its fields. The members are public so {@code RestContext.request.requestURI} resolves as Apex
 * writes it. {@code addHeader}/{@code addParameter} mutate the maps for symmetry with the docs.
 */
public final class RestRequest {
    public String requestURI;
    public String resourceName;
    public String httpMethod;
    public String remoteAddress;
    public Blob requestBody;
    public final java.util.Map<String, String> headers = new LinkedHashMap<>();
    public final java.util.Map<String, String> params = new LinkedHashMap<>();

    public RestRequest() {
    }

    /** Add (or replace) a request header — mirrors the documented accessor. */
    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    /** Add (or replace) a query/POST parameter. */
    public void addParameter(String name, String value) {
        params.put(name, value);
    }
}
