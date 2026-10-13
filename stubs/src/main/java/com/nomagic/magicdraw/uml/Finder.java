// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.uml;

import java.util.Collection;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.finder.AbstractByQualifiedNameFinder;
import com.nomagic.uml2.finder.BaseFinder;

public class Finder extends BaseFinder {
    public static ByNameAllFinder byNameAll() { throw new UnsupportedOperationException("stub"); }
    public static ByNameAllRecursivelyFinder byNameAllRecursively() { throw new UnsupportedOperationException("stub"); }
    public static ByQualifiedNameFinder byQualifiedName() { throw new UnsupportedOperationException("stub"); }
    public static ByTypeFinder byType() { throw new UnsupportedOperationException("stub"); }
    public static ByTypeRecursivelyFinder byTypeRecursively() { throw new UnsupportedOperationException("stub"); }

    public static final class ByNameAllFinder {
        public <T extends Element> Collection<T> find(Element root, Class type, String name) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class[] type, String name) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class type, String name, boolean includeRoot) { throw new UnsupportedOperationException("stub"); }
    }

    public static final class ByNameAllRecursivelyFinder {
        public <T extends Element> Collection<T> find(Element root, Class type, String name) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class[] type, String name) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Project project, Class type, String name) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class type, String name, boolean includeRoot) { throw new UnsupportedOperationException("stub"); }
    }

    public static final class ByQualifiedNameFinder extends AbstractByQualifiedNameFinder<Project> {
    }

    public static final class ByTypeFinder {
        public <T extends Element> Collection<T> find(Element root, Class type) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class type, boolean includeRoot) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class[] types) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Project project, Class type) { throw new UnsupportedOperationException("stub"); }
    }

    public static final class ByTypeRecursivelyFinder {
        public <T extends Element> Collection<T> find(Element root, Class[] types) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Element root, Class[] types, boolean includeRoots) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Project project, Class[] types) { throw new UnsupportedOperationException("stub"); }
        public <T extends Element> Collection<T> find(Project project, Class[] types, boolean includeRoots) { throw new UnsupportedOperationException("stub"); }
    }
}
