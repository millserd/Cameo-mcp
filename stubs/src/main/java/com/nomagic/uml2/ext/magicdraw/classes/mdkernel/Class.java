// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.BehavioredClassifier;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.EncapsulatedClassifier;

public interface Class extends EncapsulatedClassifier, BehavioredClassifier {
    List<Property> getOwnedAttribute();
    List<Operation> getOwnedOperation();
    Collection<com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class> getSuperClass();
    boolean isActive();
}
