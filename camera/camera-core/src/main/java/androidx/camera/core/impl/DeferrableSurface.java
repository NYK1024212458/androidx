/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.core.impl;

import android.util.Log;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.core.impl.utils.futures.Futures;
import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class for creating and tracking use of a {@link Surface} in an asynchronous manner.
 *
 * <p>Once the deferrable surface has been closed via {@link #close()} and is no longer in
 * use ({@link #decrementUseCount() has been called equal to the number of times to
 * {@link #incrementUseCount()}, then the surface is considered terminated.
 *
 * <p>Resources managed by this class can be safely cleaned up upon completion of the {
 *
 * @link ListenableFuture} returned by {@link #getTerminationFuture()}.
 */
public abstract class DeferrableSurface {

    /**
     * The exception that is returned by the ListenableFuture of {@link #getSurface()} if the
     * deferrable surface is unable to produce a {@link Surface}.
     */
    public static final class SurfaceUnavailableException extends Exception {
        public SurfaceUnavailableException(@NonNull String message) {
            super(message);
        }
    }

    /**
     * The exception that is returned by the ListenableFuture of {@link #getSurface()} if the
     * {@link Surface} backing the DeferrableSurface has already been closed.
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    public static final class SurfaceClosedException extends Exception {
        DeferrableSurface mDeferrableSurface;

        public SurfaceClosedException(@NonNull String s, @NonNull DeferrableSurface surface) {
            super(s);
            mDeferrableSurface = surface;
        }

        /**
         * Returns the {@link DeferrableSurface} that generated the exception.
         *
         * <p>The deferrable surface will already be closed.
         */
        @NonNull
        public DeferrableSurface getDeferrableSurface() {
            return mDeferrableSurface;
        }
    }

    private static final String TAG = "DeferrableSurface";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Debug only, used to track total count of surfaces in use.
    private static AtomicInteger sUsedCount = new AtomicInteger(0);
    // Debug only, used to track total count of surfaces, including those not in use. Will be
    // decremented once surface is cleaned.
    private static AtomicInteger sTotalCount = new AtomicInteger(0);

    // Lock used for accessing states.
    private final Object mLock = new Object();

    // The use count.
    @GuardedBy("mLock")
    private int mUseCount = 0;

    @GuardedBy("mLock")
    private boolean mClosed = false;

    @GuardedBy("mLock")
    private CallbackToFutureAdapter.Completer<Void> mTerminationCompleter;
    private final ListenableFuture<Void> mTerminationFuture;

    /**
     * Creates a new DeferrableSurface which has no use count.
     */
    public DeferrableSurface() {
        mTerminationFuture = CallbackToFutureAdapter.getFuture(completer -> {
            synchronized (mLock) {
                mTerminationCompleter = completer;
            }
            return "DeferrableSurface-termination(" + DeferrableSurface.this + ")";
        });

        if (DEBUG) {
            printGlobalDebugCounts("Surface created", sTotalCount.incrementAndGet(),
                    sUsedCount.get());

            String creationStackTrace = Log.getStackTraceString(new Exception());
            mTerminationFuture.addListener(() -> {
                try {
                    mTerminationFuture.get();
                    printGlobalDebugCounts("Surface terminated", sTotalCount.decrementAndGet(),
                            sUsedCount.get());
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected surface termination for " + DeferrableSurface.this
                            + "\nStack Trace:\n" + creationStackTrace);
                    throw new IllegalArgumentException("DeferrableSurface terminated with "
                            + "unexpected exception.", e);
                }
            }, CameraXExecutors.directExecutor());
        }
    }

    private void printGlobalDebugCounts(@NonNull String prefix, int totalCount, int useCount) {
        Log.d(TAG, prefix + "[total_surfaces=" + totalCount + ", used_surfaces=" + useCount
                + "](" + this + "}");
    }

    /**
     * Returns a {@link Surface} that is wrapped in a {@link ListenableFuture}.
     *
     * @return Will return a {@link ListenableFuture} with an exception if the DeferrableSurface
     * is already closed.
     */
    @NonNull
    public final ListenableFuture<Surface> getSurface() {
        synchronized (mLock) {
            if (mClosed) {
                return Futures.immediateFailedFuture(
                        new SurfaceClosedException("DeferrableSurface already closed.", this));
            }
            return provideSurface();
        }
    }

    /**
     * Returns a {@link Surface} that is wrapped in a {@link ListenableFuture} when the
     * DeferrableSurface has not yet been closed.
     */
    @NonNull
    protected abstract ListenableFuture<Surface> provideSurface();

    /**
     * Returns a future which completes when the deferrable surface is terminated.
     *
     * <p>A deferrable surface is considered terminated once it has been closed by
     * {@link #close()} and it is marked as no longer in use via {@link #decrementUseCount()}.
     *
     * <p>Once a deferrable surface has been terminated, it is safe to release all resources
     * which may have been created for the surface.
     *
     * @return A future signalling the deferrable surface has terminated. Cancellation of this
     * future is a no-op.
     */
    @NonNull
    public ListenableFuture<Void> getTerminationFuture() {
        return Futures.nonCancellationPropagating(mTerminationFuture);
    }

    /**
     * Increments the use count of the surface.
     *
     * <p>If the surface has been closed and was not previously in use, this will fail and throw a
     * {@link SurfaceClosedException} and the use count will not be incremented.
     *
     * @throws SurfaceClosedException if the surface has been closed.
     */
    public void incrementUseCount() throws SurfaceClosedException {
        synchronized (mLock) {
            if (mUseCount == 0 && mClosed) {
                throw new SurfaceClosedException("Cannot begin use on a closed surface.", this);
            }
            mUseCount++;

            if (DEBUG) {
                if (mUseCount == 1) {
                    printGlobalDebugCounts("New surface in use", sTotalCount.get(),
                            sUsedCount.incrementAndGet());
                }
                Log.d(TAG, "use count+1, useCount=" + mUseCount + " " + this);
            }
        }
    }

    /**
     * Close the surface.
     *
     * <p>After closing, {@link #getSurface()} and {@link #incrementUseCount()} will return a
     * {@link SurfaceClosedException}.
     *
     * <p>If the surface is not being used, then this will also complete the future returned by
     * {@link #getTerminationFuture()}. If the surface is in use, then the future not be completed
     * until {@link #decrementUseCount()} has bee called the appropriate number of times.
     *
     * <p>This method is idempotent. Subsequent calls after the first invocation will have no
     * effect.
     */
    public final void close() {
        // If this gets set, then the surface will terminate
        CallbackToFutureAdapter.Completer<Void> terminationCompleter = null;
        synchronized (mLock) {
            if (!mClosed) {
                mClosed = true;

                if (mUseCount == 0) {
                    terminationCompleter = mTerminationCompleter;
                    mTerminationCompleter = null;
                }

                if (DEBUG) {
                    Log.d(TAG,
                            "surface closed,  useCount=" + mUseCount + " closed=true " + this);
                }
            }
        }

        if (terminationCompleter != null) {
            terminationCompleter.set(null);
        }
    }

    /**
     * Decrements the use count.
     *
     * <p>If this causes the use count to go to zero and the surface has been closed, this will
     * complete the future returned by {@link #getTerminationFuture()}.
     */
    public void decrementUseCount() {
        // If this gets set, then the surface will terminate
        CallbackToFutureAdapter.Completer<Void> terminationCompleter = null;
        synchronized (mLock) {
            if (mUseCount == 0) {
                throw new IllegalStateException("Decrementing use count occurs more times than "
                        + "incrementing");
            }

            mUseCount--;
            if (mUseCount == 0 && mClosed) {
                terminationCompleter = mTerminationCompleter;
                mTerminationCompleter = null;
            }

            if (DEBUG) {
                Log.d(TAG, "use count-1,  useCount=" + mUseCount + " closed=" + mClosed
                        + " " + this);

                if (mUseCount == 0) {
                    if (DEBUG) {
                        printGlobalDebugCounts("Surface no longer in use",
                                sTotalCount.get(), sUsedCount.decrementAndGet());
                    }
                }
            }
        }

        if (terminationCompleter != null) {
            terminationCompleter.set(null);
        }
    }

    /** @hide */
    @RestrictTo(Scope.TESTS)
    public int getUseCount() {
        synchronized (mLock) {
            return mUseCount;
        }
    }
}
