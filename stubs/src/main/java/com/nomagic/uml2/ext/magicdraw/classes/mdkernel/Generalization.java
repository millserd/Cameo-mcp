// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;

public interface Generalization extends DirectedRelationship {
    Classifier getGeneral();
    void setGeneral(Classifier value);
    Classifier getSpecific();
    void setSpecific(Classifier value);
}
