// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;

public interface CallBehaviorAction extends CallAction {
    Behavior getBehavior();
    void setBehavior(Behavior value);
}
