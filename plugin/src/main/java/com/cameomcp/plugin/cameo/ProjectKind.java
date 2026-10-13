package com.cameomcp.plugin.cameo;

import com.cameomcp.mcp.ToolException;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Tells UML projects (plain UML and SysML v1) from SysML v2 projects, which Cameo 2026x stores as
 * KerML models with no UML root model. Resolved reflectively: 2024x has no SysML v2 and no
 * {@code Project.isUMLProject()}.
 */
public final class ProjectKind {

    public static final String UML = "uml";
    public static final String SYSML2 = "sysml2";

    private static final String MODEL_ROOT_BRIDGE = "com.dassault_systemes.modeler.foundation.model.ModelRootBridge";
    private static final String KERML_ELEMENT = "com.dassault_systemes.modeler.kerml.model.kerml.Element";
    private static final String MODEL_ELEMENT_PROJECT = "com.dassault_systemes.modeler.foundation.project.ModelElementProject";

    private ProjectKind() {
    }

    /** {@link #SYSML2} for a SysML v2 project, otherwise {@link #UML}. */
    public static String of(Project project) {
        return isSysml2(project) ? SYSML2 : UML;
    }

    public static boolean isSysml2(Project project) {
        if (project == null) {
            return false;
        }
        try {
            Method m = project.getClass().getMethod("isUMLProject");
            Object r = m.invoke(project);
            if (Boolean.FALSE.equals(r)) {
                return true;
            }
            if (Boolean.TRUE.equals(r)) {
                return false;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // 2024x: no such method
        }
        return project.getPrimaryModel() == null && !modelRoots(project).isEmpty();
    }

    /** The UML root model, or a clear error on a SysML v2 project or a project without one. */
    public static Package primaryModel(Project project) {
        Package primary = project.getPrimaryModel();
        if (primary != null) {
            return primary;
        }
        if (isSysml2(project)) {
            throw sysml2Project();
        }
        throw new ToolException("NO_PROJECT", "The open project has no root model.");
    }

    public static ToolException sysml2Project() {
        return new ToolException("SYSML2_PROJECT", "The open project is a SysML v2 project. This tool works on "
                + "UML / SysML v1 models. Use the cameo_sysml2_* tools instead (start with cameo_sysml2_list_roots "
                + "and cameo_sysml2_get_text), or open a SysML v1 project.");
    }

    /**
     * NOT_FOUND for an id that resolved to nothing usable by a UML tool; when it resolved to a
     * SysML v2 element, says so and points at the SysML v2 tools.
     */
    public static ToolException notUmlElement(Object found, String notFoundMessage) {
        if (found != null && !(found instanceof Element) && isKerml(found)) {
            return new ToolException("SYSML2_ELEMENT", "That id belongs to a SysML v2 element ("
                    + found.getClass().getSimpleName() + "), which UML / SysML v1 tools cannot use. Use the "
                    + "cameo_sysml2_* tools for it.");
        }
        return new ToolException("NOT_FOUND", notFoundMessage);
    }

    /** Whether the object is a SysML v2 / KerML model element. */
    public static boolean isKerml(Object o) {
        for (Class<?> c = o == null ? null : o.getClass(); c != null; c = c.getSuperclass()) {
            if (implementsKerml(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean implementsKerml(Class<?> c) {
        for (Class<?> i : c.getInterfaces()) {
            if (KERML_ELEMENT.equals(i.getName()) || implementsKerml(i)) {
                return true;
            }
        }
        return false;
    }

    /** Root model elements Cameo knows for the project (2026x foundation API); empty when unavailable. */
    private static Collection<?> modelRoots(Project project) {
        try {
            ClassLoader cl = project.getClass().getClassLoader();
            Class<?> bridge = Class.forName(MODEL_ROOT_BRIDGE, true, cl);
            Class<?> mep = Class.forName(MODEL_ELEMENT_PROJECT, false, cl);
            Object roots = bridge.getMethod("getModelRoots", mep).invoke(null, project);
            if (roots instanceof Collection) {
                return (Collection<?>) roots;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // not 2026x
        }
        return java.util.Collections.emptyList();
    }
}
