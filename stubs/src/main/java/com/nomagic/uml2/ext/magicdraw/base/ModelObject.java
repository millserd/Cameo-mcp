// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.uml2.ext.magicdraw.base;

import java.util.*;
import com.nomagic.magicdraw.foundation.MDObject;

public interface ModelObject extends MDObject {
    default Object refGetValue(String featureName) { throw new UnsupportedOperationException("stub"); }
    Object refGetValue(String featureName, Object expressionValueContext);
    default void refSetValue(String featureName, Object value) { throw new UnsupportedOperationException("stub"); }
    void refSetValue(String featureName, Object value, Object expressionValueContext);
}
