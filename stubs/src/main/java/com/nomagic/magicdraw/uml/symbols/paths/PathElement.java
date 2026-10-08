// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.uml.symbols.paths;

import java.util.*;
import java.awt.Point;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;

public abstract class PathElement extends PathConnector {
    public PresentationElement getClient() { throw new UnsupportedOperationException("stub"); }
    public PresentationElement getSupplier() { throw new UnsupportedOperationException("stub"); }
    public final List<Point> getBreakPoints() { throw new UnsupportedOperationException("stub"); }
    public final List<Point> getAllBreakPoints() { throw new UnsupportedOperationException("stub"); }
    public final Point getSupplierPoint() { throw new UnsupportedOperationException("stub"); }
    public final Point getClientPoint() { throw new UnsupportedOperationException("stub"); }
}
