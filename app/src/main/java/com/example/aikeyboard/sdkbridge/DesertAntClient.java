// The one place Kotlin coroutines meet Java: turns a suspend SDK call into a cancellable future.
package com.example.aikeyboard.sdkbridge;

import java.util.concurrent.CompletableFuture;

import ai.desertant.core.DesertAnt;
import ai.desertant.core.LogLevel;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.SupervisorKt;
import kotlinx.coroutines.future.FutureKt;

public final class DesertAntClient {

    /** A Kotlin suspend function as Java sees it: the continuation is its last argument. */
    public interface SuspendCall<T> {
        // Declared without a wildcard so method references such as emo::download infer T.
        Object invoke(Continuation<T> continuation);
    }

    // SupervisorJob: one failed or cancelled call must not cancel the others.
    private static final CoroutineScope SCOPE = CoroutineScopeKt.CoroutineScope(
            SupervisorKt.SupervisorJob(null).plus(Dispatchers.getDefault()));

    private DesertAntClient() {}

    /**
     * Turns the vendor SDK's own logging off. The SDK handles what the user types, so nothing
     * it might print may reach the system log. Call once at process start, before any model.
     */
    public static void silenceSdkLogs() {
        DesertAnt.setLogLevel(LogLevel.OFF);
    }

    /**
     * Starts the call and returns at once; the SDK moves the work to its own dispatchers.
     * Cancelling the future cancels the coroutine, but a native inference that is already
     * running cannot be interrupted, so callers must still drop results they no longer want.
     * A failure arrives as an ExecutionException whose cause is the vendor exception.
     */
    @SuppressWarnings("unchecked") // A continuation only consumes T, so "? super T" narrows safely.
    public static <T> CompletableFuture<T> run(SuspendCall<T> call) {
        return FutureKt.future(SCOPE, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
                (scope, continuation) -> call.invoke((Continuation<T>) continuation));
    }
}
