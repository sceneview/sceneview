package io.github.erkko68.filament

/**
 * Fence is used to synchronize the application main thread with filament's rendering thread.
 */
@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "unusable — Engine.createFence throws; filament.js exposes no GPU/CPU fence API.")
expect class Fence {
    /**
     * Mode controls the behavior of the command stream when calling wait().
     *
     * @note It would be unwise to call `wait(..., Mode.DONT_FLUSH)` from the same thread
     * the Fence was created, as it would most certainly create a dead-lock.
     */
    enum class Mode {
        /** The command stream is flushed */
        FLUSH,
        /** The command stream is not flushed */
        DONT_FLUSH
    }

    /** Error codes for Fence.wait() */
    enum class FenceStatus {
        ERROR,
        ALREADY_SIGNALED,
        TIMEOUT_EXPIRED,
        CONDITION_SATISFIED
    }

    /**
     * Client-side wait on the Fence.
     *
     * Blocks the current thread until the Fence signals.
     *
     * @param mode Whether the command stream is flushed before waiting or not.
     * @param timeout Wait time out in nanoseconds. Using a timeout of 0 is a way to query the state of the fence.
     * @return FenceStatus.CONDITION_SATISFIED on success,
     *         FenceStatus.TIMEOUT_EXPIRED if the time out expired, or
     *         FenceStatus.ERROR in other cases.
     */
    fun wait(mode: Mode, timeout: Long): FenceStatus

    val nativeObject: Long

    companion object {
        /**
         * Client-side wait on a Fence and destroy the Fence.
         *
         * @param fence Fence object to wait on.
         * @param mode Whether the command stream is flushed before waiting or not.
         * @return FenceStatus.CONDITION_SATISFIED on success, FenceStatus.ERROR otherwise.
         */
        fun waitAndDestroy(fence: Fence, mode: Mode): FenceStatus
    }
}
