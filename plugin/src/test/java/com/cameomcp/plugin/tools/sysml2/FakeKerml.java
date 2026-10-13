// family: sysml2
package com.cameomcp.plugin.tools.sysml2;

import com.dassault_systemes.modeler.kerml.model.kerml.Element;

import java.util.ArrayList;
import java.util.List;

/** Builds small KerML trees. The implementation class is package-private, like Cameo's. */
final class FakeKerml {

    private FakeKerml() {
    }

    static Node node(String type, String name, Node... children) {
        Node n = new Node(type, name);
        for (Node c : children) {
            c.owner = n;
            n.children.add(c);
        }
        return n;
    }

    static final class Node implements Element {
        final String type;
        final String name;
        final List<Element> children = new ArrayList<>();
        Node owner;
        boolean library;
        private static int next;
        private final String id = "local-" + (++next);
        private final String elementId = "uuid-" + next;

        Node(String type, String name) {
            this.type = type;
            this.name = name;
        }

        Node library() {
            library = true;
            return this;
        }

        @Override
        public String getID() {
            return id;
        }

        @Override
        public String getElementId() {
            return elementId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDeclaredName() {
            return name;
        }

        @Override
        public String getShortName() {
            return null;
        }

        @Override
        public String getQualifiedName() {
            return owner == null || owner.name == null ? name : owner.getQualifiedName() + "::" + name;
        }

        @Override
        public List<Element> getOwnedElement() {
            return children;
        }

        @Override
        public Element getOwner() {
            return owner;
        }

        @Override
        public boolean isLibraryElement() {
            return library;
        }

        @Override
        public MetaClass eClass() {
            return () -> type;
        }
    }
}
