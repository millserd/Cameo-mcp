// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;

public interface Constraint extends PackageableElement {
    ValueSpecification getSpecification();
    void setSpecification(ValueSpecification value);
    Namespace getContext();
    void setContext(Namespace value);
    List<Element> getConstrainedElement();
}
