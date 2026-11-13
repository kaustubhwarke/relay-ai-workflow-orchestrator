package com.relay.engine;

/**
 * A handle on the right to execute a run. The worker's heartbeat revokes it if the queue lease is lost
 * (for example after a long GC pause or a network partition), and the executor stops between steps. The
 * {@code UNIQUE (run_id, sequence)} constraint on steps is the second line of defence.
 */
public interface WorkerLease {

    boolean isHeld();

    /** A lease that is never revoked (single-threaded tests, replays). */
    WorkerLease ALWAYS = () -> true;
}
