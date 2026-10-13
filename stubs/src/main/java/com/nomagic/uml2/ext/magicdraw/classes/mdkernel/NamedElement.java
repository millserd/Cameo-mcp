// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;

public interface NamedElement extends Element {
    String getName();
    void setName(String value);
    String getQualifiedName();
    VisibilityKind getVisibility();
    void setVisibility(VisibilityKind value);
    Namespace getNamespace();
}
