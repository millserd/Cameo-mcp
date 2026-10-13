package com.cameomcp.plugin;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.GUILog;

import javax.swing.SwingUtilities;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes Cameo MCP status to the places Cameo users look:
 * <ul>
 *   <li>the Notification window ("GUI log") inside Cameo,</li>
 *   <li>Cameo's log file (md.log) through MDLog, found by reflection because its logger
 *       type changed between Cameo releases,</li>
 *   <li>java.util.logging as a fallback.</li>
 * </ul>
 * Every line is prefixed with {@code [Cameo MCP]} so it is easy to filter.
 */
public final class CameoLog {

    public static final String PREFIX = "[Cameo MCP] ";
    private static final Logger JUL = Logger.getLogger("com.cameomcp");
    // Cameo's md.log logger, resolved lazily once; null if MDLog isn't available.
    private static volatile Object mdLogger;
    private static volatile boolean mdLoggerResolved;

    private CameoLog() {
    }

    /** Info that goes to md.log and the Notification window. */
    public static void info(String message) {
        write("info", message, true);
    }

    /** Info that goes to md.log only (not the Notification window) - for high-volume events. */
    public static void fileOnly(String message) {
        write("info", message, false);
    }

    /** A warning to md.log and the Notification window. */
    public static void warn(String message) {
        write("warn", message, true);
    }

    /** An error to md.log and the Notification window; the stack trace goes to JUL at FINE level. */
    public static void error(String message, Throwable t) {
        write("error", message + (t == null ? "" : ": " + t), true);
        if (t != null) {
            JUL.log(Level.FINE, message, t);
        }
    }

    // Every message reaches a log file: md.log if possible, java.util.logging otherwise.
    private static void write(String level, String message, boolean gui) {
        String line = PREFIX + message;
        if (!toMdLog(level, line)) {
            JUL.log("error".equals(level) ? Level.SEVERE : "warn".equals(level) ? Level.WARNING : Level.INFO, line);
        }
        if (gui) {
            toGuiLog(line);
        }
    }

    // The GUI log is a Swing component, so it is only touched on the event thread.
    private static void toGuiLog(String line) {
        Runnable r = () -> {
            try {
                GUILog log = Application.getInstance().getGUILog();
                if (log != null) {
                    log.log(line);
                }
            } catch (Throwable ignored) {
                // Cameo is starting up or shutting down; md.log still has the line.
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    // Returns false when md.log can't be written, so the caller falls back to JUL.
    private static boolean toMdLog(String level, String line) {
        Object logger = mdLogger();
        if (logger == null) {
            return false;
        }
        try {
            Method m = findLogMethod(logger.getClass(), level);
            if (m == null) {
                return false;
            }
            m.invoke(logger, line);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // MDLog's logger is a log4j or SLF4J type depending on the release; find info/warn/error(String) by name.
    private static Method findLogMethod(Class<?> type, String level) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(level) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(String.class)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // Double-checked lazy lookup: getPluginsLog where present, else the general log.
    private static Object mdLogger() {
        if (!mdLoggerResolved) {
            synchronized (CameoLog.class) {
                if (!mdLoggerResolved) {
                    try {
                        Class<?> c = Class.forName("com.nomagic.magicdraw.core.MDLog");
                        for (String getter : new String[]{"getPluginsLog", "getGeneralLog"}) {
                            try {
                                Object l = c.getMethod(getter).invoke(null);
                                if (l != null) {
                                    mdLogger = l;
                                    break;
                                }
                            } catch (NoSuchMethodException ignored) {
                                // try the next getter
                            }
                        }
                    } catch (Throwable ignored) {
                        mdLogger = null;
                    }
                    mdLoggerResolved = true;
                }
            }
        }
        return mdLogger;
    }
}
