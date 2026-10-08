// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.RedefinableElement;

public interface Region extends Namespace, RedefinableElement {
    State getState();
    void setState(State value);
    StateMachine getStateMachine();
    void setStateMachine(StateMachine value);
    Collection<Vertex> getSubvertex();
    Collection<Transition> getTransition();
}
