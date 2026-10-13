// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.mdusecases;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.DirectedRelationship;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;

public interface Extend extends NamedElement, DirectedRelationship {
    UseCase getExtendedCase();
    void setExtendedCase(UseCase value);
    UseCase getExtension();
    void setExtension(UseCase value);
}
