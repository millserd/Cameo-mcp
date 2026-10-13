// Compile-only stub of the Cameo OpenAPI. Not packaged.
package com.nomagic.magicdraw.core;

import java.util.*;
import com.nomagic.magicdraw.core.options.EnvironmentOptions;
import com.nomagic.magicdraw.ui.MainFrame;
import com.nomagic.magicdraw.core.project.ProjectEventListener;
import com.nomagic.magicdraw.core.project.ProjectsManager;

public class Application {
    public static Application getInstance() { throw new UnsupportedOperationException("stub"); }
    public Project getProject() { throw new UnsupportedOperationException("stub"); }
    public GUILog getGUILog() { throw new UnsupportedOperationException("stub"); }
    public MainFrame getMainFrame() { throw new UnsupportedOperationException("stub"); }
    public ProjectsManager getProjectsManager() { throw new UnsupportedOperationException("stub"); }
    public final void addProjectEventListener(ProjectEventListener listener) { throw new UnsupportedOperationException("stub"); }
    public void removeProjectEventListener(ProjectEventListener listener) { throw new UnsupportedOperationException("stub"); }
    public EnvironmentOptions getEnvironmentOptions() { throw new UnsupportedOperationException("stub"); }
    public static Application.Environment environment() { throw new UnsupportedOperationException("stub"); }

    public static class Environment {
        public Environment() { }
        public String getInstallRoot() { throw new UnsupportedOperationException("stub"); }
        public String getDataDirectory() { throw new UnsupportedOperationException("stub"); }
        public String getConfigDir() { throw new UnsupportedOperationException("stub"); }
        public String getUserHomeDirectory() { throw new UnsupportedOperationException("stub"); }
        public java.io.File getUserDataDirectory() { throw new UnsupportedOperationException("stub"); }
        public java.io.File getTempDir(String path) { throw new UnsupportedOperationException("stub"); }
    }
}
