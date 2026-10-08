// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;

public interface Association extends Classifier, Relationship {
    List<Property> getOwnedEnd();
    List<Property> getMemberEnd();
    Collection<Type> getEndType();
}
