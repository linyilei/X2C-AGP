package io.github.linyilei.x2c.compiler;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.File;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.xml.parsers.DocumentBuilderFactory;

final class LayoutParser {

    private LayoutParser() {
    }

    static LayoutSpec parse(File file, String layoutName, String qualifier, Messager messager) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            disableExternalEntities(factory);
            Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
            LayoutNode rootNode = parseElement(root);
            LayoutSpec spec = new LayoutSpec(layoutName, qualifier, file, rootNode);
            collectIncludes(rootNode, spec.includes);
            spec.unsupported = containsUnsupported(rootNode);
            return spec;
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "X2C skipped " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static LayoutNode parseElement(Element element) {
        LayoutNode node = new LayoutNode();
        node.tag = element.getTagName();
        node.className = element.getAttribute("class");
        if ("include".equals(node.tag)) {
            node.includeName = normalizeLayoutName(element.getAttribute("layout"));
        }
        Node child = element.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                node.children.add(parseElement((Element) child));
            }
            child = child.getNextSibling();
        }
        return node;
    }

    private static void collectIncludes(LayoutNode node, java.util.Set<String> includes) {
        if ("include".equals(node.tag) && node.includeName != null && !node.includeName.isEmpty()) {
            includes.add(node.includeName);
        }
        for (LayoutNode child : node.children) {
            collectIncludes(child, includes);
        }
    }

    private static boolean containsUnsupported(LayoutNode node) {
        if ("fragment".equals(node.tag) || "requestFocus".equals(node.tag)) {
            return true;
        }
        for (LayoutNode child : node.children) {
            if (containsUnsupported(child)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeLayoutName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("@layout/")) {
            return value.substring("@layout/".length());
        }
        if (value.startsWith("@+layout/")) {
            return value.substring("@+layout/".length());
        }
        return null;
    }

    private static void disableExternalEntities(DocumentBuilderFactory factory) {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Parser implementation does not expose every feature on every JDK.
        }
        String[] features = new String[]{
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities"
        };
        for (String feature : features) {
            try {
                factory.setFeature(feature, false);
            } catch (Exception ignored) {
                // Parser implementation does not expose every feature on every JDK.
            }
        }
    }
}
