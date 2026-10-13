// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.RedefinableElement;

public interface Vertex extends RedefinableElement {
    Region getContainer();
    void setContainer(Region value);
    Collection<Transition> getIncoming();
    Collection<Transition> getOutgoing();
}
