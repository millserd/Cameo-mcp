// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.uml;

import java.util.*;

public interface BaseElement extends Comparable, Cloneable {
    Class getClassType();
    String getHumanName();
    String getHumanType();
    boolean isEditable();
}
