// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime.dom;

import alloyx.runtime.List;
import alloyx.runtime.Unsupported;

/** Apex {@code Dom.XmlNode} — recognized for type-checking; XML I/O doesn't run locally yet. */
public final class XmlNode {
    public XmlNode addChildElement(String name, String namespace, String prefix) {
        throw Unsupported.notLocal("Dom.XmlNode.addChildElement");
    }

    public XmlNode addCommentNode(String text) {
        throw Unsupported.notLocal("Dom.XmlNode.addCommentNode");
    }

    public XmlNode addTextNode(String text) {
        throw Unsupported.notLocal("Dom.XmlNode.addTextNode");
    }

    public String getAttribute(String key, String keyNamespace) {
        throw Unsupported.notLocal("Dom.XmlNode.getAttribute");
    }

    public String getAttributeValue(String key, String keyNamespace) {
        throw Unsupported.notLocal("Dom.XmlNode.getAttributeValue");
    }

    public XmlNode getChildElement(String name, String namespace) {
        throw Unsupported.notLocal("Dom.XmlNode.getChildElement");
    }

    public List<XmlNode> getChildElements() {
        throw Unsupported.notLocal("Dom.XmlNode.getChildElements");
    }

    public List<XmlNode> getChildren() {
        throw Unsupported.notLocal("Dom.XmlNode.getChildren");
    }

    public String getName() {
        throw Unsupported.notLocal("Dom.XmlNode.getName");
    }

    public String getNamespace() {
        throw Unsupported.notLocal("Dom.XmlNode.getNamespace");
    }

    public XmlNodeType getNodeType() {
        throw Unsupported.notLocal("Dom.XmlNode.getNodeType");
    }

    public XmlNode getParent() {
        throw Unsupported.notLocal("Dom.XmlNode.getParent");
    }

    public String getText() {
        throw Unsupported.notLocal("Dom.XmlNode.getText");
    }

    public void setAttribute(String key, String value) {
        throw Unsupported.notLocal("Dom.XmlNode.setAttribute");
    }

    public void setNamespace(String prefix, String namespace) {
        throw Unsupported.notLocal("Dom.XmlNode.setNamespace");
    }
}
