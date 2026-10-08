// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.dependencymatrix.datamodel;

import java.util.*;
import com.nomagic.magicdraw.dependencymatrix.datamodel.cell.AbstractMatrixCell;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public interface MatrixData {
    Collection<Element> getRowElements();
    Collection<Element> getColumnElements();
    AbstractMatrixCell getValue(Element row, Element column);  // @Deprecated in 2024x (use ElementNode overload)
    boolean isEmpty();
}
