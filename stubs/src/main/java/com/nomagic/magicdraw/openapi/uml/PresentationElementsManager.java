// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.openapi.uml;

import java.util.*;
import com.nomagic.uml2.ext.magicdraw.activities.mdintermediateactivities.ActivityPartition;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.magicdraw.uml.symbols.paths.PathElement;
import java.awt.Point;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.nomagic.magicdraw.properties.PropertyManager;
import java.awt.Rectangle;
import com.nomagic.magicdraw.uml.symbols.shapes.ShapeElement;
import com.nomagic.magicdraw.uml.symbols.shapes.SwimlaneView;

public class PresentationElementsManager {
    public static PresentationElementsManager getInstance() { throw new UnsupportedOperationException("stub"); }
    public void changePathBreakPoints(PathElement element, List newBreakPoints) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public void changePathPoints(PathElement element, Point supplierPoint, Point clientPoint, List<Point> newBreakPoints) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public PathElement createPathElement(Element relationship, PresentationElement client, PresentationElement supplier) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public ShapeElement createShapeElement(Element element, PresentationElement parent) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public ShapeElement createShapeElement(Element element, PresentationElement parent, boolean createRelationships) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public ShapeElement createShapeElement(Element element, PresentationElement parent, boolean createRelationships, Point location) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public SwimlaneView createSwimlane(List<? extends ActivityPartition> horizontal, List<? extends ActivityPartition> vertical, DiagramPresentationElement diagram) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public void deletePresentationElement(PresentationElement element) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public void resetLabelPositions(PathElement element) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public void reshapeShapeElement(ShapeElement element, Rectangle newBounds) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
    public void setPresentationElementProperties(PresentationElement view, PropertyManager propertyManager) throws ReadOnlyElementException { throw new UnsupportedOperationException("stub"); }
}
