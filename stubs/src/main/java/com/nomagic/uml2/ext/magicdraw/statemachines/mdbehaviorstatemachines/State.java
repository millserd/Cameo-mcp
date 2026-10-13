// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;

public interface State extends Namespace, Vertex {
    Collection<Region> getRegion();
    com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior getEntry();
    void setEntry(com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior value);
    com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior getDoActivity();
    void setDoActivity(com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior value);
    com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior getExit();
    void setExit(com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior value);
}
