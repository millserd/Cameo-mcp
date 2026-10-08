package com.cameomcp.plugin.cameo;

import com.cameomcp.mcp.ToolException;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Diagram calls whose API differs between Cameo 2024x and 2026x, resolved reflectively at runtime.
 *
 * <ul>
 *   <li>Auto-layout: 2024x has {@code layout(boolean)}; 2026x removed it and lays out through
 *       {@code getDefaultDiagramLayouter().layout(dpe, CompositeCommand)}.</li>
 *   <li>Diagram window state: 2024x has {@code isDiagramWindowOpen()}/{@code isDiagramWindowActive()};
 *       2026x removed both.</li>
 * </ul>
 */
public final class DiagramCompat {

    private static final String MACRO_COMMAND = "com.dassault_systemes.modeler.foundation.editing.MacroCommand";
    private static final String COMPOSITE_COMMAND = "com.dassault_systemes.modeler.foundation.editing.CompositeCommand";
    private static final String ABSTRACT_DPE = "com.nomagic.magicdraw.uml.symbols.AbstractDiagramPresentationElement";

    private DiagramCompat() {
    }

    /** Lays out the whole diagram. Returns false when Cameo reports nothing was laid out. */
    public static boolean layout(DiagramPresentationElement dpe) {
        try {
            Method legacy = method(dpe.getClass(), "layout", boolean.class);
            if (legacy != null) {
                Object r = legacy.invoke(dpe, true);
                return !(r instanceof Boolean) || (Boolean) r;
            }
            Method getLayouter = method(dpe.getClass(), "getDefaultDiagramLayouter");
            Object layouter = getLayouter != null ? getLayouter.invoke(dpe) : null;
            if (layouter != null) {
                ClassLoader cl = dpe.getClass().getClassLoader();
                Class<?> composite = Class.forName(COMPOSITE_COMMAND, false, cl);
                Class<?> abstractDpe = Class.forName(ABSTRACT_DPE, false, cl);
                Object command = Class.forName(MACRO_COMMAND, true, cl).getConstructor().newInstance();
                Method layout = method(layouter.getClass(), "layout", abstractDpe, composite);
                Method canLayout = method(layouter.getClass(), "canLayout", abstractDpe);
                if (layout != null) {
                    if (canLayout != null && Boolean.FALSE.equals(canLayout.invoke(layouter, dpe))) {
                        throw new ToolException("UNSUPPORTED", "Cameo cannot auto-layout this kind of diagram.");
                    }
                    Object r = layout.invoke(layouter, dpe, command);
                    return !(r instanceof Boolean) || (Boolean) r;
                }
            }
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new ToolException("TOOL_FAILED", "Auto-layout failed: " + c.getClass().getSimpleName()
                    + (c.getMessage() != null ? ": " + c.getMessage() : ""));
        } catch (ToolException e) {
            throw e;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // fall through to UNSUPPORTED
        }
        throw new ToolException("UNSUPPORTED", "Automatic layout is not available through the API of this Cameo "
                + "version. Use Layout > Quick Diagram Layout in Cameo, or position shapes with cameo_move_shapes.");
    }

    /** Whether the diagram's window is open, or null when this Cameo version cannot tell. */
    public static Boolean isWindowOpen(DiagramPresentationElement dpe) {
        return bool(dpe, "isDiagramWindowOpen");
    }

    /** Whether the diagram's window is the active one, or null when this Cameo version cannot tell. */
    public static Boolean isWindowActive(DiagramPresentationElement dpe) {
        return bool(dpe, "isDiagramWindowActive");
    }

    private static Boolean bool(Object target, String name) {
        Method m = method(target.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            Object r = m.invoke(target);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }
}
