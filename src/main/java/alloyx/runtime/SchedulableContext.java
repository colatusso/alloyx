// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Handle passed to {@link Schedulable#execute} (Apex {@code SchedulableContext}). The trigger
 * id is an org-runtime concern, so reading it fails clearly rather than returning a fake id.
 */
public interface SchedulableContext {
    default String getTriggerId() {
        throw Unsupported.notLocal("SchedulableContext.getTriggerId()");
    }
}
