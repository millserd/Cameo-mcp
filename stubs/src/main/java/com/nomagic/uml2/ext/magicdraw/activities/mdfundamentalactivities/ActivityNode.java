// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.RedefinableElement;

public interface ActivityNode extends RedefinableElement {
    Collection<ActivityEdge> getIncoming();
    Collection<ActivityEdge> getOutgoing();
}
