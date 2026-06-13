// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Apex {@code System.XmlStreamWriter} — builds an XML document into a string buffer.
 *
 * <p>This is pure string-building with no org dependency, so it runs LOCALLY and faithfully:
 * a probe that drives the writer produces well-formed XML that {@link #getXmlString()} returns.
 * The writer tracks open elements on a stack so {@code writeEndElement()} can close the most
 * recent start tag, and defers closing a start tag's {@code >} until the first child (so
 * attributes and namespaces written after {@code writeStartElement} land inside the open tag).
 *
 * <p>Namespace handling is intentionally LITERAL (KISS): prefixes, namespace URIs and attribute
 * names are emitted exactly as passed — {@code writeStartElement(prefix, local, uri)} emits
 * {@code <prefix:local>} and a paired {@code xmlns:prefix="uri"} only when the caller also calls
 * {@code writeNamespace}. We do not auto-bind or de-duplicate namespace declarations; the real
 * platform's binding rules aren't needed for the SOAP/XML request-building this backs.
 */
public final class XmlStreamWriter {
    private final StringBuilder sb = new StringBuilder();
    // open elements, innermost last: each holds the qualified name to write in the end tag.
    private final Deque<String> open = new ArrayDeque<>();
    // true while a start tag is open and unterminated (its '>' not yet written), so attributes/
    // namespaces append inside it and the first child/end first closes it.
    private boolean tagOpen = false;
    private String defaultNamespace;

    public XmlStreamWriter() {
    }

    /** {@code <?xml version="1.0" encoding="UTF-8"?>} with the given encoding and version. */
    public void writeStartDocument(String encoding, String version) {
        sb.append("<?xml version=\"").append(version == null ? "1.0" : version).append('"');
        if (encoding != null && !encoding.isEmpty()) {
            sb.append(" encoding=\"").append(encoding).append('"');
        }
        sb.append("?>");
    }

    public void writeEndDocument() {
        // close any elements left open, innermost first, so the document is well-formed
        while (!open.isEmpty()) {
            writeEndElement();
        }
    }

    /** Open a start element with no prefix/namespace: {@code <localName}. */
    public void writeStartElement(String localName) {
        writeStartElement(null, localName, null);
    }

    /**
     * Open a start element. {@code prefix} (when non-blank) qualifies the name ({@code prefix:local});
     * {@code namespaceURI} is recorded only via a paired {@code writeNamespace} call (literal handling).
     */
    public void writeStartElement(String prefix, String localName, String namespaceURI) {
        closeStartTag();
        String qname = qualify(prefix, localName);
        sb.append('<').append(qname);
        open.addLast(qname);
        tagOpen = true;
    }

    /** Self-closing element: {@code <prefix:local/>} (no children, no separate end tag). */
    public void writeEmptyElement(String localName) {
        writeEmptyElement(null, localName, null);
    }

    public void writeEmptyElement(String prefix, String localName, String namespaceURI) {
        closeStartTag();
        sb.append('<').append(qualify(prefix, localName));
        // An empty element has no end-tag entry on the stack: the NEXT closeStartTag self-closes it
        // with '/>'. tagOpen keeps following attributes/namespaces inside this tag; emptyPending makes
        // the close a self-close, not a '>' that would expect a separate writeEndElement.
        tagOpen = true;
        emptyPending = true;
    }

    // set right after writeEmptyElement so the next closeStartTag self-closes with '/>'
    private boolean emptyPending = false;

    public void writeEndElement() {
        // A pending EMPTY element self-closes ('/>') and consumes NO stack entry (it never pushed
        // one): flush it, then fall through to close the innermost REAL element this call targets.
        if (tagOpen && emptyPending) {
            closeStartTag();
        }
        if (tagOpen) {
            // a real start tag with no children collapses to a self-closing '/>' (and pops the stack)
            sb.append("/>");
            tagOpen = false;
            if (!open.isEmpty()) {
                open.removeLast();
            }
            return;
        }
        if (!open.isEmpty()) {
            sb.append("</").append(open.removeLast()).append('>');
        }
    }

    /** Character data, XML-escaped (so {@code & < >} don't break the document). */
    public void writeCharacters(String text) {
        closeStartTag();
        sb.append(escape(text));
    }

    /** {@code <![CDATA[...]]>} — raw, unescaped (the platform doesn't escape inside CDATA). */
    public void writeCData(String data) {
        closeStartTag();
        sb.append("<![CDATA[").append(data == null ? "" : data).append("]]>");
    }

    public void writeComment(String comment) {
        closeStartTag();
        sb.append("<!--").append(comment == null ? "" : comment).append("-->");
    }

    /** An attribute on the currently-open start tag: {@code prefix:localName="value"}. */
    public void writeAttribute(String prefix, String namespaceURI, String localName, String value) {
        sb.append(' ').append(qualify(prefix, localName)).append("=\"").append(escape(value)).append('"');
    }

    /** A namespace declaration on the open tag: {@code xmlns:prefix="uri"} (or {@code xmlns="uri"}). */
    public void writeNamespace(String prefix, String namespaceURI) {
        if (prefix == null || prefix.isEmpty()) {
            writeDefaultNamespace(namespaceURI);
            return;
        }
        sb.append(" xmlns:").append(prefix).append("=\"").append(escape(namespaceURI)).append('"');
    }

    /** The default namespace declaration on the open tag: {@code xmlns="uri"}. */
    public void writeDefaultNamespace(String namespaceURI) {
        sb.append(" xmlns=\"").append(escape(namespaceURI)).append('"');
    }

    /** Records the default namespace; emitted only when {@link #writeDefaultNamespace} is called. */
    public void setDefaultNamespace(String namespaceURI) {
        this.defaultNamespace = namespaceURI;
    }

    public String getDefaultNamespace() {
        return defaultNamespace;
    }

    /** The XML accumulated so far. Closes any dangling open tag first so the snapshot is valid. */
    public String getXmlString() {
        closeStartTag();
        return sb.toString();
    }

    /** Releases the writer. Local string-building holds no resources, so this is a no-op. */
    public void close() {
    }

    // --- internals -------------------------------------------------------------------------------

    // Terminate a pending start tag: a self-closing '/>' for an empty element, else '>' so children
    // append after it. Called before any child/text/end so attributes already landed inside the tag.
    private void closeStartTag() {
        if (tagOpen) {
            sb.append(emptyPending ? "/>" : ">");
            tagOpen = false;
            emptyPending = false;
        }
    }

    private static String qualify(String prefix, String localName) {
        return prefix == null || prefix.isEmpty() ? localName : prefix + ":" + localName;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
