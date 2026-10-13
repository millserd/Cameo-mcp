// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;

public interface Classifier extends Type, Namespace, RedefinableElement {
    boolean isAbstract();
    void setAbstract(boolean value);
    boolean isFinalSpecialization();
    void setFinalSpecialization(boolean value);
    Collection<Classifier> getGeneral();
    Collection<Generalization> getGeneralization();
    List<Property> getAttribute();
}
