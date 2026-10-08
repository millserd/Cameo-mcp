package com.cameomcp.plugin.cameo;

import com.cameomcp.mcp.ToolException;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;

import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs model operations on the right thread.
 * <ul>
 *   <li>{@link #read} runs on the calling thread (model reads are thread-safe in Cameo).</li>
 *   <li>{@link #onEdt} runs on the Swing EDT without a model session (UI, export, save).</li>
 *   <li>{@link #write} runs on the EDT inside a {@link SessionManager} session, so the change is
 *       one undoable step named after the tool; the session is cancelled if the action throws.
 *       Writes are serialized: a second write while one runs fails fast instead of queueing.</li>
 * </ul>
 */
public final class Edt {

    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final Semaphore WRITE_PERMIT = new Semaphore(1);
    private static volatile String activeWrite;
    private static volatile long activeWriteStarted;

    @FunctionalInterface
    public interface ModelAction<T> {
        T execute(Project project) throws Exception;
    }

    private Edt() {
    }

    /** The open project, or a NO_PROJECT tool error. */
    public static Project project() {
        Project p = Application.getInstance().getProject();
        if (p == null) {
            throw ToolException.noProject();
        }
        return p;
    }

    public static <T> T read(ModelAction<T> action) throws Exception {
        return action.execute(project());
    }

    public static <T> T onEdt(String name, ModelAction<T> action) throws Exception {
        return onEdt(name, action, DEFAULT_TIMEOUT_SECONDS);
    }

    public static <T> T onEdt(String name, ModelAction<T> action, long timeoutSeconds) throws Exception {
        Project project = project();
        if (SwingUtilities.isEventDispatchThread()) {
            return action.execute(project);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                future.complete(action.execute(project));
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return await(future, name, timeoutSeconds, false);
    }

    /** Run a UI action that does not need an open project. */
    public static <T> T onEdtNoProject(String name, java.util.concurrent.Callable<T> action, long timeoutSeconds) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return await(future, name, timeoutSeconds, false);
    }

    public static <T> T write(String sessionName, ModelAction<T> action) throws Exception {
        return write(sessionName, action, DEFAULT_TIMEOUT_SECONDS);
    }

    public static <T> T write(String sessionName, ModelAction<T> action, long timeoutSeconds) throws Exception {
        Project project = project();
        if (!WRITE_PERMIT.tryAcquire(2, TimeUnit.SECONDS)) {
            String active = activeWrite;
            long ms = System.currentTimeMillis() - activeWriteStarted;
            throw new ToolException("WRITE_IN_PROGRESS", "Another model change is still running"
                    + (active == null ? "" : " (" + active + ", " + ms + " ms)")
                    + ". Wait for it to finish, then retry.");
        }
        activeWrite = sessionName;
        activeWriteStarted = System.currentTimeMillis();
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable work = () -> {
            SessionManager sm = SessionManager.getInstance();
            boolean created = false;
            try {
                if (sm.isSessionCreated(project)) {
                    throw new ToolException("SESSION_OPEN", "A Cameo editing session is already open (another "
                            + "operation or a crashed macro left it). Call cameo_reset_session, then retry.");
                }
                sm.createSession(project, "MCP: " + sessionName);
                created = true;
                T result = action.execute(project);
                sm.closeSession(project);
                created = false;
                future.complete(result);
            } catch (Throwable e) {
                if (created) {
                    try {
                        sm.cancelSession(project);
                    } catch (Throwable ignored) {
                        // keep the original error
                    }
                }
                future.completeExceptionally(e);
            } finally {
                activeWrite = null;
                WRITE_PERMIT.release();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
        } else {
            SwingUtilities.invokeLater(work);
        }
        return await(future, sessionName, timeoutSeconds, true);
    }

    public static String activeWrite() {
        return activeWrite;
    }

    private static <T> T await(CompletableFuture<T> future, String name, long timeoutSeconds, boolean write)
            throws Exception {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ToolException("TIMEOUT", "Timed out after " + timeoutSeconds + " s waiting for Cameo to finish '"
                    + name + "'. " + (write ? "It may still complete; new changes are blocked until it does. "
                    : "") + "Cameo may be showing a modal dialog - check the Cameo window.");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw e;
        }
    }
}
