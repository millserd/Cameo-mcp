// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.mdusecases;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.DirectedRelationship;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

public interface Include extends NamedElement, DirectedRelationship {
    UseCase getAddition();
    void setAddition(UseCase value);
    UseCase getIncludingCase();
    void setIncludingCase(UseCase value);
}
