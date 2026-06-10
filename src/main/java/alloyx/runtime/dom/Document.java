// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime.dom;

import alloyx.runtime.Unsupported;

/** Apex {@code Dom.Document} — recognized for type-checking; XML I/O doesn't run locally yet. */
public final class Document {
    public Document() {
    }

    public XmlNode createRootElement(String name, String namespace, String prefix) {
        throw Unsupported.notLocal("Dom.Document.createRootElement");
    }

    public XmlNode getRootElement() {
        throw Unsupported.notLocal("Dom.Document.getRootElement");
    }

    public void load(String xml) {
        throw Unsupported.notLocal("Dom.Document.load");
    }

    public String toXmlString() {
        throw Unsupported.notLocal("Dom.Document.toXmlString");
    }
}
