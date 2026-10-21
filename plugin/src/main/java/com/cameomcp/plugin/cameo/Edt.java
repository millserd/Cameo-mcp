package com.cameomcp.plugin.cameo;

import com.cameomcp.mcp.ToolException;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.SessionManager;

import javax.swing.SwingUtilities;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs model operations on the right thread.
 * <ul>
 *   <li>{@link #read} runs on the calling thread, so it can overlap a write running on the EDT;
 *       a read that trips over a concurrent change is retried once.</li>
 *   <li>{@link #onEdt} runs on the Swing EDT without a model session (UI, export, save).</li>
 *   <li>{@link #write} runs on the EDT inside a {@link SessionManager} session, so the change is
 *       one undoable step named after the tool; the session is cancelled if the action throws.
 *       Writes are serialized: a second write while one runs fails fast instead of queueing.</li>
 * </ul>
 */
public final class Edt {

    /** Time limit for {@link #onEdt} and {@link #write} when the caller gives none: 60 seconds. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    /**
     * One permit: at most one model change at a time, because Cameo allows only one open session per project.
     */
    private static final Semaphore WRITE_PERMIT = new Semaphore(1);
    /** Name of the running write, for the WRITE_IN_PROGRESS message and the status window; null when idle. */
    private static volatile String activeWrite;
    /** Start time of the running write, epoch milliseconds. */
    private static volatile long activeWriteStarted;

    /**
     * A unit of model work; receives the open project.
     *
     * @param <T> the action's result type
     */
    @FunctionalInterface
    public interface ModelAction<T> {
        /**
         * Does the work.
         *
         * @param project the open project
         * @return the action's result (a JSON-shaped value for tool handlers)
         * @throws Exception any failure; a {@link ToolException} reaches the agent as is
         */
        T execute(Project project) throws Exception;
    }

    /** Static methods only. */
    private Edt() {
    }

    /**
     * The open project, or a NO_PROJECT tool error.
     *
     * @return the active project
     * @throws ToolException NO_PROJECT if none is open
     */
    public static Project project() {
        Project p = Application.getInstance().getProject();
        if (p == null) {
            throw ToolException.noProject();
        }
        return p;
    }

    /**
     * Runs a read on the calling thread against the open project. A write on the EDT can change a
     * collection the read is walking; that read is retried once, then reported as MODEL_CHANGED.
     *
     * @param <T> the action's result type
     * @param action the read; may run twice, so it must have no side effects
     * @return the action's result
     * @throws ToolException NO_PROJECT, or MODEL_CHANGED after a second concurrent modification
     * @throws Exception whatever the action throws
     */
    public static <T> T read(ModelAction<T> action) throws Exception {
        try {
            return action.execute(project());
        } catch (ConcurrentModificationException first) {
            try {
                return action.execute(project());
            } catch (ConcurrentModificationException again) {
                throw new ToolException("MODEL_CHANGED", "The model changed while it was being read (another change "
                        + "was running). Retry the call.");
            }
        }
    }

    /**
     * Runs an action on the Swing event thread with the default time limit, without a model session.
     *
     * @param <T> the action's result type
     * @param name what the action does, used in the timeout message
     * @param action the work
     * @return the action's result
     * @throws Exception whatever the action throws, or a TIMEOUT tool error
     */
    public static <T> T onEdt(String name, ModelAction<T> action) throws Exception {
        return onEdt(name, action, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Runs an action on the Swing event thread without a model session and waits for it.
     * Used for UI work and for APIs that must run on the EDT but don't change the model.
     *
     * @param <T> the action's result type
     * @param name what the action does, used in the timeout message
     * @param action the work
     * @param timeoutSeconds how long to wait for the EDT
     * @return the action's result
     * @throws ToolException NO_PROJECT if no project is open, TIMEOUT if the EDT does not finish in time
     * (the action may still run later)
     *
     * @throws Exception whatever the action throws
     */
    public static <T> T onEdt(String name, ModelAction<T> action, long timeoutSeconds) throws Exception {
        Project project = project();
        // Already on the EDT (e.g. called from a menu action): run inline, since waiting would deadlock.
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

    /**
     * Run a UI action that does not need an open project.
     *
     * @param <T> the action's result type
     * @param name what the action does, used in the timeout message
     * @param action the work
     * @param timeoutSeconds how long to wait for the EDT
     * @return the action's result
     * @throws Exception whatever the action throws, or a TIMEOUT tool error
     */
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

    /**
     * {@link #write(String, ModelAction, long)} with the default time limit.
     *
     * @param <T> the action's result type
     * @param sessionName undo step name, shown in Cameo as "MCP: sessionName"
     * @param action the change
     * @return the action's result
     * @throws Exception whatever the action throws, or a WRITE_IN_PROGRESS, SESSION_OPEN or TIMEOUT tool
     *     error
     */
    public static <T> T write(String sessionName, ModelAction<T> action) throws Exception {
        return write(sessionName, action, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Runs a model change on the EDT inside its own session, so it is one Undo step called
     * "MCP: sessionName". If the action throws, the session is cancelled and nothing is kept.
     *
     * @param <T> the action's result type
     * @param sessionName undo step name, shown in Cameo as "MCP: sessionName"
     * @param action the change
     * @param timeoutSeconds how long to wait for the EDT to finish
     * @return the action's result
     * @throws ToolException WRITE_IN_PROGRESS if another write holds the permit for more than 2 s,
     *                       SESSION_OPEN if something else left a Cameo session open, TIMEOUT if the
     *                       change doesn't finish in time
     * @throws Exception whatever the action throws
     */
    public static <T> T write(String sessionName, ModelAction<T> action, long timeoutSeconds) throws Exception {
        return serialized(sessionName, action, timeoutSeconds, true);
    }

    /**
     * Runs a model change on the EDT without opening a session, holding the write permit so it is
     * serialized with other writes. For Cameo APIs that manage their own undoable transaction.
     *
     * @param <T> the action's result type
     * @param name what the action does, used in messages
     * @param action the change; it opens and closes its own transaction
     * @param timeoutSeconds how long to wait for the EDT to finish
     * @return the action's result
     * @throws ToolException WRITE_IN_PROGRESS, SESSION_OPEN or TIMEOUT, as for {@link #write}
     * @throws Exception whatever the action throws
     */
    public static <T> T exclusive(String name, ModelAction<T> action, long timeoutSeconds) throws Exception {
        return serialized(name, action, timeoutSeconds, false);
    }

    /**
     * Shared body of write and exclusive: take the permit (waiting at most 2 s), run on the EDT (inside a
     * session when asked), wait for the result. The permit is held until the work really ends on the
     * EDT, even if the caller timed out, so a slow change keeps blocking later writes.
     *
     * @param <T> the action's result type
     * @param name session or operation name
     * @param action the change
     * @param timeoutSeconds how long to wait for the EDT to finish
     * @param withSession whether to wrap the action in a SessionManager session
     * @return the action's result
     * @throws Exception whatever the action throws, or a WRITE_IN_PROGRESS, SESSION_OPEN or TIMEOUT tool
     *     error
     */
    private static <T> T serialized(String name, ModelAction<T> action, long timeoutSeconds, boolean withSession)
            throws Exception {
        Project project = project();
        if (!WRITE_PERMIT.tryAcquire(2, TimeUnit.SECONDS)) {
            String active = activeWrite;
            long ms = System.currentTimeMillis() - activeWriteStarted;
            throw new ToolException("WRITE_IN_PROGRESS", "Another model change is still running"
                    + (active == null ? "" : " (" + active + ", " + ms + " ms)")
                    + ". Wait for it to finish, then retry.");
        }
        activeWrite = name;
        activeWriteStarted = System.currentTimeMillis();
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable work = () -> {
            SessionManager sm = SessionManager.getInstance();
            boolean created = false;
            try {
                // Never nest into someone else's session: their cancel would roll back our change too.
                if (sm.isSessionCreated(project)) {
                    throw new ToolException("SESSION_OPEN", "A Cameo editing session is already open (another "
                            + "operation or a crashed macro left it). Call cameo_reset_session, then retry.");
                }
                if (withSession) {
                    sm.createSession(project, "MCP: " + name);
                    created = true;
                }
                T result = action.execute(project);
                if (created) {
                    sm.closeSession(project);
                    created = false;
                }
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
            // The permit is released on the EDT when the work actually ends, not when the caller stops waiting.
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
        return await(future, name, timeoutSeconds, true);
    }

    /**
     * Name of the write currently running, or null. Shown by cameo_status and the status window.
     *
     * @return the write's name, or null when none is running
     */
    public static String activeWrite() {
        return activeWrite;
    }

    /**
     * Waits for the future and rethrows the action's own exception, not the ExecutionException wrapper.
     *
     * @param <T> the result type
     * @param future the pending work
     * @param name operation name for the timeout message
     * @param timeoutSeconds how long to wait
     * @param write whether the work is a model change (adds a note that later changes stay blocked)
     * @return the result
     * @throws Exception the action's exception, or a TIMEOUT tool error
     */
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
