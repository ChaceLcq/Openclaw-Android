package com.agenew.translate.mnn;

/**
 * Coordinates access to native MNN-backed runtimes that are not safe to use concurrently.
 *
 * Audio capture should continue independently; only native inference sections should be serialized.
 */
public final class MnnRuntimeCoordinator {
    public static final Object LOCK = new Object();

    private MnnRuntimeCoordinator() {
    }
}
