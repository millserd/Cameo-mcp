// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.core.project;

import java.util.*;
import com.nomagic.magicdraw.core.Project;

public interface ProjectEventListener {
    void projectActivated(Project project);
    void projectActivatedFromGUI(Project project);
    void projectClosed(Project project);
    void projectCreated(Project project);
    void projectDeActivated(Project project);
    void projectOpened(Project project);
    void projectOpenedFromGUI(Project project);
    void projectPreActivated(Project project);
    void projectPreClosed(Project project);
    void projectPreClosedFinal(Project project);
    void projectPreDeActivated(Project project);
    default void projectPreOpenedFromGUI(Project project) { throw new UnsupportedOperationException("stub"); }
    void projectPreSaved(Project project, boolean savedInServer);
    void projectReplaced(Project oldProject, Project newProject);
    void projectSaved(Project project, boolean savedInServer);
}
