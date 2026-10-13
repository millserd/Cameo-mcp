// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.dependencymatrix.datamodel.cell;

import java.util.*;

public abstract class AbstractMatrixCell {
    public abstract Collection<DependencyEntry> getDependencies();
    public abstract void setDependencies(Collection<DependencyEntry> dependencies);
    public String getDescription() { throw new UnsupportedOperationException("stub"); }
}
