// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Handle passed to {@link Queueable#execute} (Apex {@code QueueableContext}). The job id is an
 * org-runtime concern, so reading it fails clearly rather than returning a fake id.
 */
public interface QueueableContext {
    default String getJobId() {
        throw Unsupported.notLocal("QueueableContext.getJobId()");
    }
}
