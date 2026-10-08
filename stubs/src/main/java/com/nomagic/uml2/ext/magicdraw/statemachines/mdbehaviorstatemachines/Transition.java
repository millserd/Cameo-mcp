// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.RedefinableElement;

public interface Transition extends Namespace, RedefinableElement {
    Region getContainer();
    void setContainer(Region value);
    Constraint getGuard();
    void setGuard(Constraint value);
    Vertex getSource();
    void setSource(Vertex value);
    Vertex getTarget();
    void setTarget(Vertex value);
    Collection<com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Trigger> getTrigger();
}
