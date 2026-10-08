// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;

public interface Activity extends Behavior {
    Collection<ActivityEdge> getEdge();
    Collection<ActivityGroup> getGroup();
    Collection<ActivityNode> getNode();
}
