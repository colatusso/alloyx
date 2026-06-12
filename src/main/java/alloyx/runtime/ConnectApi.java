// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code ConnectApi} (Chatter/Connect) namespace.
 *
 * <p>DESIGN: the ConnectApi surface is enormous — dozens of nested types and statics, all
 * org-coupled (feeds, communities, managed content). Modeling it would be huge and bring
 * zero local value, since none of it can run without a live org. So instead of stub
 * classes, the transpiler degrades ANY {@code ConnectApi.*} reference to {@link Object}
 * for type-checking: a {@code ConnectApi.X} type becomes {@code Object}, and a call rooted
 * at {@code ConnectApi} (e.g. {@code ConnectApi.ChatterFeeds.postFeedElement(...)}) becomes
 * {@code ConnectApi.unsupported("...")}, which type-checks as Object and fails clearly if
 * actually invoked locally. This is the KISS tradeoff: full recognition, no surface
 * modeling, no silent behavior.
 */
public final class ConnectApi {
    private ConnectApi() {}

    /** Placeholder for any ConnectApi call/access; type-checks as Object, throws if invoked. */
    public static Object unsupported(String what) {
        throw Unsupported.notLocal("ConnectApi." + what);
    }
}
