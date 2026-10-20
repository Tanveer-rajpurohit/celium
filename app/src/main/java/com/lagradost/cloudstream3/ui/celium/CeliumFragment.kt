package com.lagradost.cloudstream3.ui.celium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.activityViewModels
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
open class CeliumFragment : BaseComposeFragment() {
    private val model: CeliumViewModel by activityViewModels()
    protected open val searchMode: Boolean = false

    override fun onStart() {
        super.onStart()
        // Plugin events are handled by the view model for its whole lifetime (Requirement 16.4).
        model.sourcesChanged()
        arguments?.getString("search_query")?.takeIf { it.isNotBlank() }?.let { query ->
            model.query(query)
            arguments?.remove("search_query")
        }
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsState()
        val randomEnabled by CeliumPrefs.randomButton(requireContext()).collectAsState()
        CeliumScreen(
            state = state, searchMode = searchMode, randomEnabled = randomEnabled,
            onSection = model::selectSection, onSource = model::selectSource,
            onHome = model::selectHome,
            onQuery = model::query, onRefresh = model::forceRefresh,
            onMore = model::more, onMoreSearch = model::moreSearch,
            onSections = model::moreSections, onRetry = model::retry,
            onRetryPaging = model::retryPaging, onRetrySlot = model::retrySlot,
            onOpen = { card, play ->
                if (card is DataStoreHelper.ResumeWatchingResult) {
                    SearchHelper.handleSearchClickCallback(SearchClickCallback(SEARCH_ACTION_PLAY_FILE, requireView(), -1, card))
                } else if (card.apiName == "PornGameHub" || card.url.contains("porngamehub", true)) {
                    val intent = android.content.Intent(requireContext(), com.lagradost.cloudstream3.ui.celium.game.CeliumGameDetailActivity::class.java).apply {
                        putExtra(com.lagradost.cloudstream3.ui.celium.game.CeliumGameDetailActivity.EXTRA_GAME_URL, card.url)
                        putExtra(com.lagradost.cloudstream3.ui.celium.game.CeliumGameDetailActivity.EXTRA_GAME_TITLE, card.name)
                        putExtra(com.lagradost.cloudstream3.ui.celium.game.CeliumGameDetailActivity.EXTRA_POSTER_URL, card.posterUrl)
                    }
                    startActivity(intent)
                } else loadSearchResult(card, if (play) START_ACTION_RESUME_LATEST else 0)
            },
        )
    }
}

class CeliumSearchFragment : CeliumFragment() {
    override val searchMode: Boolean = true
}
