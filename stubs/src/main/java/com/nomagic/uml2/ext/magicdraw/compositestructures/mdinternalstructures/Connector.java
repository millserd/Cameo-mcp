// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Association;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Feature;

public interface Connector extends Feature {
    List<ConnectorEnd> getEnd();
    Association getType();
}
