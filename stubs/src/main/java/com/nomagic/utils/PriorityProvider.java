// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.utils;

import java.util.*;

public interface PriorityProvider {
    int HIGH_PRIORITY = Integer.valueOf(0);  // UNVERIFIED value; deliberately not a compile-time constant so the real value is read at runtime
    int MEDIUM_PRIORITY = Integer.valueOf(0);  // UNVERIFIED value; see above
    int LOW_PRIORITY = Integer.valueOf(0);  // UNVERIFIED value; see above
    int getPriority();
    static void sort(List<? extends PriorityProvider> providers) { throw new UnsupportedOperationException("stub"); }
}
