package com.musheer360.swiftslate.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one vertical rhythm every tab is laid out on.
 *
 * Each tab fits a single viewport with no root scroll and its last card anchored
 * flush [screenPaddingV] above the nav bar, so the four cards share a fixed height
 * budget that varies by roughly 120 dp between phones: a Galaxy S23 gives the
 * content area ~697 dp, a Nothing Phone 2a ~821 dp. One rigid set of tokens cannot
 * serve both — at 14 dp padding the S23 was ~43 dp short and clipped the bottom
 * card, while the 2a had ~81 dp spare that piled up into a single void.
 *
 * So the tokens interpolate against the available height: short viewports compact
 * every gap until everything fits, tall viewports let all cards breathe by the
 * same amount. Because a single instance is provided for the whole app through
 * [LocalSlateRhythm], every tab necessarily agrees on padding, gaps and type — the
 * tabs cannot drift apart the way they do with per-screen hardcoded dp values.
 *
 * Deliberately fixed, because they are structural rather than cosmetic:
 * [screenPaddingH]/[screenPaddingV] (they set the shared page frame and the
 * bottom-card anchor), [cardGap] and [listGap] (8 dp, the app's baseline step),
 * and the body type sizes (13/15 sp is already the readable floor — compacting
 * type to win layout height trades legibility for space).
 *
 * Note that Android's official window *height* size classes are too coarse to use
 * here: compact is < 480 dp and medium is 480-900 dp, so both phones land in the
 * same bucket despite the 120 dp gap between them. Hence the continuous ramp.
 */
@Immutable
data class SlateRhythm(
    /** Page side padding. Aligns card edges with the nav bar items. */
    val screenPaddingH: Dp,
    /** Page top/bottom padding. Also the last card's gap above the nav bar. */
    val screenPaddingV: Dp,
    /** Screen title type size. */
    val titleSize: TextUnit,
    /** Title to first card. */
    val titleGap: Dp,
    /** Inner padding of a [SlateCard]. */
    val cardPadding: Dp,
    /** Gap between sibling cards. */
    val cardGap: Dp,
    /** Inner padding of a [SlateItemCard]. */
    val itemPadding: Dp,
    /** Gap between rows inside a scrolling list. */
    val listGap: Dp,
    /** Label-to-field and field-to-label gaps in a form. */
    val formGap: Dp,
    /** Around a [SlateDivider], and description-to-button-row. */
    val groupGap: Dp,
    /** Between two tightly coupled lines of text. */
    val tightGap: Dp,
    /** Readout row to slider. */
    val sliderGap: Dp,
    val sliderHeight: Dp,
    /** Secondary/supporting text. */
    val bodySize: TextUnit,
    /** Primary/emphasised text. */
    val emphasisSize: TextUnit,
    /** Labels under a statistic. */
    val captionSize: TextUnit,
    /** Large statistic readouts. */
    val statSize: TextUnit
) {
    companion object {
        /** At or below this content height, everything is fully compacted. */
        private const val COMPACT_HEIGHT = 700f

        /** At or above this content height, everything is fully relaxed. */
        private const val RELAXED_HEIGHT = 820f

        fun forHeight(available: Dp): SlateRhythm {
            val t = ((available.value - COMPACT_HEIGHT) / (RELAXED_HEIGHT - COMPACT_HEIGHT))
                .coerceIn(0f, 1f)
            fun flexDp(compact: Float, relaxed: Float): Dp = (compact + (relaxed - compact) * t).dp
            fun flexSp(compact: Float, relaxed: Float): TextUnit = (compact + (relaxed - compact) * t).sp
            return SlateRhythm(
                screenPaddingH = 20.dp,
                screenPaddingV = 16.dp,
                // Flexed so the title reads at a consistent *physical* size. A fixed
                // 32 sp looks noticeably larger on the denser, shorter S23 than on the
                // 2a; ramping it keeps the two within a couple of percent and buys back
                // height on exactly the viewport that needs it.
                titleSize = flexSp(28f, 32f),
                titleGap = flexDp(12f, 20f),
                cardPadding = flexDp(10f, 16f),
                cardGap = 8.dp,
                itemPadding = flexDp(10f, 12f),
                listGap = 8.dp,
                formGap = flexDp(6f, 12f),
                groupGap = flexDp(10f, 12f),
                tightGap = flexDp(2f, 6f),
                sliderGap = flexDp(8f, 12f),
                // Never below the 48 dp interactive floor once Compose's minimum touch
                // target is applied around it.
                sliderHeight = flexDp(24f, 26f),
                bodySize = 13.sp,
                emphasisSize = 15.sp,
                captionSize = 12.sp,
                statSize = 24.sp
            )
        }

        /** Fallback for previews and any subtree without a provider. */
        val Relaxed: SlateRhythm = forHeight(RELAXED_HEIGHT.dp)
    }
}

/**
 * Provided once for the whole app in `SwiftSlateMainScreen`, from the height the
 * tab content area actually gets. Read it instead of hardcoding dp so the tabs
 * stay in agreement.
 */
val LocalSlateRhythm = staticCompositionLocalOf { SlateRhythm.Relaxed }
