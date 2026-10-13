// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;

public interface StructuredClassifier extends Classifier {
    List<Property> getOwnedAttribute();
    Collection<Connector> getOwnedConnector();
    Collection<Property> getPart();
    Collection<ConnectableElement> getRole();
}
