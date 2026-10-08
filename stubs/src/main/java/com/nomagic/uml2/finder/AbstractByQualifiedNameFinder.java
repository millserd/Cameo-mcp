// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.finder;

import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.project.ElementProject;

public abstract class AbstractByQualifiedNameFinder<R extends ElementProject> extends BaseFinder {
    public <T extends Element> T find(R elementProject, String qualifiedName) { throw new UnsupportedOperationException("stub"); }
    public <T extends Element> T find(R elementProject, String qualifiedName, Class<? extends Element> type) { throw new UnsupportedOperationException("stub"); }
}
