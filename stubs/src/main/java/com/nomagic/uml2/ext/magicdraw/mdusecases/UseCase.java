// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.mdusecases;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.BehavioredClassifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;

public interface UseCase extends BehavioredClassifier {
    Collection<Classifier> getSubject();
}
