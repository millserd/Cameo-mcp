// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;

public interface ConnectorEnd extends MultiplicityElement {
    Property getPartWithPort();
    void setPartWithPort(Property value);
    ConnectableElement getRole();
    void setRole(ConnectableElement value);
}
