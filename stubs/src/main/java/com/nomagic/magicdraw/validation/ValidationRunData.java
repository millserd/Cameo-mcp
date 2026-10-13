// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.validation;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;

public class ValidationRunData {
    public ValidationRunData(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package suite, EnumerationLiteral severity) { }
    public ValidationRunData(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package suite, Collection<? extends Element> elements, EnumerationLiteral severity, boolean excludeReadOnly) { }
    public ValidationRunData(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package suite, boolean wholeProject, Collection<? extends Element> elements, EnumerationLiteral severity, boolean excludeReadOnly) { }  // @Deprecated in 2024x
    public ValidationRunData(Collection<Constraint> constraints, String name, Collection<? extends Element> elements, EnumerationLiteral severity, boolean excludeReadOnly) { }
    public ValidationRunData(Collection<Constraint> constraints, String name, boolean wholeProject, Collection<? extends Element> elements, EnumerationLiteral severity, boolean excludeReadOnly) { }  // @Deprecated in 2024x
    public void setAddElementsRecursively(boolean addElementsRecursively) { throw new UnsupportedOperationException("stub"); }
    public void setEnableSettingsDialog(boolean enableSettingsDialog) { throw new UnsupportedOperationException("stub"); }
}
