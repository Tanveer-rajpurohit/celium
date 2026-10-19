package com.lagradost.cloudstream3.plugins

/**
 * Pure popup attribution and suppression policy (Celium 6, Requirement 2).
 * No Android dependencies: the Android adapters (PluginOrigin, PopupGuard) feed it data.
 */

/** Where a stack frame (or a whole call) comes from. */
sealed interface FrameOrigin {
    /** Platform, library, or transparent app helper frames. Skipped by attribution. */
    object Framework : FrameOrigin {
        override fun toString() = "Framework"
    }

    /** Celium's own code. */
    object App : FrameOrigin {
        override fun toString() = "App"
    }

    /** Code loaded from the plugin with this internal name. */
    data class Plugin(val name: String) : FrameOrigin
}

/** Kinds of popups gated by PopupGuard. */
enum class PopupType { Dialog, Toast, Snackbar, ExternalLink }

/** Outcome of [PopupPolicy.decide]. */
sealed interface Decision {
    object Show : Decision {
        override fun toString() = "Show"
    }

    data class Suppress(val plugin: String) : Decision
}

object PopupAttribution {
    /**
     * The nearest non-framework caller decides. [frames] are class names ordered most
     * recent first. Returns [FrameOrigin.App] when every frame is Framework (or none exist).
     */
    fun attribute(frames: List<String>, classify: (String) -> FrameOrigin): FrameOrigin {
        for (frame in frames) {
            val origin = classify(frame)
            if (origin != FrameOrigin.Framework) return origin
        }
        return FrameOrigin.App
    }
}

object PopupPolicy {
    /** Expiry value meaning "code is running inside userAction(plugin)": always active. */
    const val USER_ACTION_SCOPE: Long = Long.MAX_VALUE

    /** A plugin UI opened under userAction may show popups only this soon after user input. */
    const val INPUT_WINDOW_MS: Long = 1000L

    /**
     * Show for App and Framework origins. For Plugin(p), Show only when p has an active
     * user-action allowance, otherwise Suppress(p).
     *
     * [allowedPlugins] maps plugin name to allowance expiry:
     * - [USER_ACTION_SCOPE]: the call runs inside `userAction(p)`, always active.
     * - any other value: a plugin UI opened under `userAction(p)` is still attached until
     *   that time; active while `nowMs < expiry` and the last user input was less than
     *   [INPUT_WINDOW_MS] ago.
     */
    fun decide(
        origin: FrameOrigin,
        allowedPlugins: Map<String, Long>,
        lastInputMs: Long,
        nowMs: Long,
    ): Decision {
        val plugin = (origin as? FrameOrigin.Plugin)?.name ?: return Decision.Show
        val expiry = allowedPlugins[plugin] ?: return Decision.Suppress(plugin)
        val active = expiry == USER_ACTION_SCOPE ||
                (nowMs < expiry && nowMs - lastInputMs in 0 until INPUT_WINDOW_MS)
        return if (active) Decision.Show else Decision.Suppress(plugin)
    }
}
