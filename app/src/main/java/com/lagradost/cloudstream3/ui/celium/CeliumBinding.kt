package com.lagradost.cloudstream3.ui.celium

/** What the view model should do with the selected provider after a plugin signal or watchdog tick. */
sealed interface BindAction {
    /** The bound provider is still the resolved one. */
    object Keep : BindAction

    /** A provider resolved and differs from the bound one: bind it and load (16.4). */
    object Rebind : BindAction

    /** Not registered yet; keep showing the loading indicator (16.3). */
    object Wait : BindAction

    /** Load pass finished or the 30 s timeout passed without the provider (16.7). */
    data class Fail(val message: String) : BindAction
}

/** Binding status exposed through `CeliumState`. */
sealed interface BindStatus {
    object Waiting : BindStatus
    object Ready : BindStatus
    data class Failed(val message: String) : BindStatus
}

/** Pure provider binding decision (Requirement 16.3, 16.4, 16.7). */
object CeliumBinding {
    const val BIND_TIMEOUT_MS = 30_000L

    fun failMessage(sourceLabel: String): String =
        "$sourceLabel is not available. Retry, or choose another source."

    fun decide(
        sourceLabel: String,
        resolvedName: String?,
        boundName: String?,
        loadPassDone: Boolean,
        elapsedMs: Long,
    ): BindAction = when {
        resolvedName != null && resolvedName != boundName -> BindAction.Rebind
        resolvedName != null -> BindAction.Keep
        loadPassDone || elapsedMs >= BIND_TIMEOUT_MS -> BindAction.Fail(failMessage(sourceLabel))
        else -> BindAction.Wait
    }

    /** Maps a decision to the status shown on screen; Keep/Rebind mean the provider is bound. */
    fun status(action: BindAction): BindStatus = when (action) {
        BindAction.Keep, BindAction.Rebind -> BindStatus.Ready
        BindAction.Wait -> BindStatus.Waiting
        is BindAction.Fail -> BindStatus.Failed(action.message)
    }
}

/** Events modelled by [CeliumFeedReducer]. */
sealed interface FeedEvent<out T> {
    /** The user selected [sourceId]; every earlier request becomes stale. */
    data class Select(val sourceId: String) : FeedEvent<Nothing>

    /** A request issued under [token] for [sourceId] returned [items]. */
    data class Response<T>(val token: Int, val sourceId: String, val items: List<T>) : FeedEvent<T>
}

/** Displayed feed: the current selection, its request token, and the items shown. */
data class FeedState<T>(
    val sourceId: String? = null,
    val token: Int = 0,
    val items: List<T> = emptyList(),
)

/**
 * Pure model of the stale-request rule (Requirement 16.6, 16.11): a selection bumps the
 * token and clears content; a response is applied only when both its token and source
 * match the latest selection, otherwise it is discarded.
 */
object CeliumFeedReducer {
    fun <T> apply(state: FeedState<T>, event: FeedEvent<T>): FeedState<T> = when (event) {
        is FeedEvent.Select -> FeedState(sourceId = event.sourceId, token = state.token + 1)
        is FeedEvent.Response ->
            if (state.sourceId != null && event.token == state.token && event.sourceId == state.sourceId) {
                state.copy(items = state.items + event.items)
            } else {
                state
            }
    }
}
