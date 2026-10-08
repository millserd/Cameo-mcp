// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.dependencymatrix.persistence;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public abstract class FilterSettings {
    public void setConvertedElementTypes(List<Object> types) { throw new UnsupportedOperationException("stub"); }
    public void setScope(Collection<Element> scope) { throw new UnsupportedOperationException("stub"); }
    public Collection<Element> getScope() { throw new UnsupportedOperationException("stub"); }  // @Deprecated in 2024x: use getScopeRoots()
    public Collection<Element> getScopeRoots() { throw new UnsupportedOperationException("stub"); }
    public List<Element> getElementTypes() { throw new UnsupportedOperationException("stub"); }
    public void setScopeDefined(boolean confirmed) { throw new UnsupportedOperationException("stub"); }
    public boolean isScopeDefined() { throw new UnsupportedOperationException("stub"); }
    public void setTypesIncludeCustomTypes(boolean includeCustomTypes) { throw new UnsupportedOperationException("stub"); }
    public boolean isTypesIncludeCustomTypes() { throw new UnsupportedOperationException("stub"); }
    public void setTypesIncludeSubtypes(boolean includeSubtypes) { throw new UnsupportedOperationException("stub"); }
    public boolean isTypesIncludeSubtypes() { throw new UnsupportedOperationException("stub"); }
}
