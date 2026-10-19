package com.lagradost.cloudstream3.ui.celium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import kotlinx.coroutines.launch

open class CeliumFragment : BaseComposeFragment() {
    private val model: CeliumViewModel by activityViewModels()
    protected open val searchMode: Boolean = false

    override fun onStart() {
        super.onStart()
        afterPluginsLoadedEvent += ::pluginsChanged
        model.sourcesChanged()
        arguments?.getString("search_query")?.takeIf { it.isNotBlank() }?.let { query ->
            model.query(query)
            arguments?.remove("search_query")
        }
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::pluginsChanged
        super.onStop()
    }

    private fun pluginsChanged(@Suppress("UNUSED_PARAMETER") force: Boolean) {
        lifecycleScope.launch { model.sourcesChanged() }
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsState()
        CeliumScreen(
            state = state, searchMode = searchMode,
            onSection = model::selectSection, onSource = model::selectSource,
            onQuery = model::query, onRefresh = model::forceRefresh,
            onMore = model::more, onMoreSearch = model::moreSearch,
            onSections = model::moreSections,
            onOpen = { card, play ->
                if (card is DataStoreHelper.ResumeWatchingResult) {
                    SearchHelper.handleSearchClickCallback(SearchClickCallback(SEARCH_ACTION_PLAY_FILE, requireView(), -1, card))
                } else loadSearchResult(card, if (play) START_ACTION_RESUME_LATEST else 0)
            },
        )
    }
}

class CeliumSearchFragment : CeliumFragment() {
    override val searchMode: Boolean = true
}
