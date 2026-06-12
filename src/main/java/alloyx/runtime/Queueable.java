// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Queueable}: a job enqueued via {@code System.enqueueJob(...)}. It's a real Apex
 * platform interface, so it maps to a real Java interface (a class that
 * {@code implements Queueable} must satisfy {@code execute(QueueableContext)}) — otherwise
 * javac sees a class where it expects an interface. The async queue that runs execute() is an
 * org-runtime concern and isn't modeled locally; the method body can still be unit-tested.
 */
public interface Queueable {
    void execute(QueueableContext qc);
}
