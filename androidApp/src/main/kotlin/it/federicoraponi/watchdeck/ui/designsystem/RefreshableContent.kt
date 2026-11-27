package it.federicoraponi.watchdeck.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.federicoraponi.watchdeck.R

// The indicator's bottom edge sits at `fraction × threshold`. The page moves a little further than that, at every
// point of the pull, so the two never overlap.
private val INDICATOR_GAP = 12.dp

/**
 * Content that is refreshed by dragging it down: the page follows the finger and the Expressive loading
 * indicator appears above it. The content must scroll, or the drag is not noticed.
 */
@Composable
fun RefreshableContent(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pull = rememberPullToRefreshState()
    val refreshLabel = stringResource(R.string.refresh)
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pull,
        modifier =
            modifier.semantics {
                // A drag is not available to screen readers.
                customActions =
                    listOf(
                        CustomAccessibilityAction(refreshLabel) {
                            onRefresh()
                            true
                        },
                    )
            },
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pull,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                maxDistance = PullToRefreshDefaults.PositionalThreshold,
            )
        },
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    val distance = PullToRefreshDefaults.PositionalThreshold + INDICATOR_GAP
                    translationY = pull.distanceFraction.coerceAtLeast(0f) * distance.toPx()
                },
            content = content,
        )
    }
}
