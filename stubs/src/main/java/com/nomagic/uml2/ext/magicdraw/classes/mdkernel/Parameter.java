// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement;

public interface Parameter extends ConnectableElement, MultiplicityElement {
    ParameterDirectionKind getDirection();
    void setDirection(ParameterDirectionKind value);
}
