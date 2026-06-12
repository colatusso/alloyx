// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schedulable}: a class scheduled via {@code System.schedule(...)}. It's a real
 * Apex platform interface, so it maps to a real Java interface (a class that
 * {@code implements Schedulable} must satisfy {@code execute(SchedulableContext)}) — otherwise
 * javac sees a class where it expects an interface. The scheduler that fires execute() is an
 * org-runtime concern and isn't modeled locally; the method body can still be unit-tested.
 */
public interface Schedulable {
    void execute(SchedulableContext sc);
}
