// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.compositestructures.mdports;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;

public interface Port extends Property {
    boolean isBehavior();
    void setBehavior(boolean value);
    boolean isConjugated();
    void setConjugated(boolean value);
    boolean isService();
    void setService(boolean value);
}
