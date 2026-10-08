// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;

public interface MultiplicityElement extends Element {
    ValueSpecification getLowerValue();
    void setLowerValue(ValueSpecification value);
    ValueSpecification getUpperValue();
    void setUpperValue(ValueSpecification value);
    boolean isOrdered();
    void setOrdered(boolean value);
    boolean isUnique();
    void setUnique(boolean value);
    int getLower();
    int getUpper();
}
