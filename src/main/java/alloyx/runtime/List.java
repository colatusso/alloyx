// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex List<T> over java.util.ArrayList. Apex's add/get/set/size/isEmpty/contains
 * line up with ArrayList's, so this is a thin, faithful wrapper.
 */
public class List<T> extends java.util.ArrayList<T> {
    public List() {
        super();
    }

    public List(java.util.Collection<? extends T> source) {
        super(source);
    }
}
