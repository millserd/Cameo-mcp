// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.RedefinableElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.ValueSpecification;

public interface ActivityEdge extends RedefinableElement {
    ActivityNode getSource();
    void setSource(ActivityNode value);
    ActivityNode getTarget();
    void setTarget(ActivityNode value);
    ValueSpecification getGuard();
    void setGuard(ValueSpecification value);
}
