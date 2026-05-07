package com.serenegiant.utils;

import android.os.Handler;
import android.os.HandlerThread;

public final class HandlerThreadHandler {
    private HandlerThreadHandler() {
    }

    public static Handler createHandler(String name) {
        HandlerThread thread = new HandlerThread(name);
        thread.start();
        return new Handler(thread.getLooper());
    }
}
