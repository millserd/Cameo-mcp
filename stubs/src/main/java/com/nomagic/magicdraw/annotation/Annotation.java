// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.annotation;

import java.util.*;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.nomagic.magicdraw.core.Project;

public class Annotation {
    public static final String DEBUG = "debug";
    public static final String ERROR = "error";
    public static final String FATAL = "fatal";
    public static final String INFO = "info";
    public static final String WARNING = "warning";
    public String getKind() { throw new UnsupportedOperationException("stub"); }
    public EnumerationLiteral getSeverity() { throw new UnsupportedOperationException("stub"); }
    public static EnumerationLiteral getSeverityLevel(Project project, String name) { throw new UnsupportedOperationException("stub"); }
    public String getText() { throw new UnsupportedOperationException("stub"); }
    public String getToolTipText() { throw new UnsupportedOperationException("stub"); }
    public BaseElement getTarget() { throw new UnsupportedOperationException("stub"); }
}
