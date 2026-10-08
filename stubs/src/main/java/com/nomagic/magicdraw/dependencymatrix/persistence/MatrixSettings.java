// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.dependencymatrix.persistence;

import java.util.List;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public class MatrixSettings {
    public MatrixSettings(Element matrixElement) { }
    public List<String> getDependencyCriteria() { throw new UnsupportedOperationException("stub"); }
    public void setDependencyCriteria(List<String> dependencyCriteria) { throw new UnsupportedOperationException("stub"); }
    public MatrixSettings.Direction getDirection() { throw new UnsupportedOperationException("stub"); }
    public void setDirection(MatrixSettings.Direction direction) { throw new UnsupportedOperationException("stub"); }
    public MatrixSettings.RelationOption getShowElementsOption() { throw new UnsupportedOperationException("stub"); }
    public void setShowElementsOption(MatrixSettings.RelationOption option) { throw new UnsupportedOperationException("stub"); }

    public static enum Direction { ROW_TO_COLUMN, COLUMN_TO_ROW, BOTH }

    public static enum RelationOption { WITH_RELATIONS, ALL, WITHOUT_RELATIONS, COLUMNS_WITHOUT_RELATIONS, ROWS_WITHOUT_RELATIONS }
}
