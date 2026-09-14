package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt

/** Children of [DayEventsLayout] declare their time and column with [eventSlot]. */
@LayoutScopeMarker
@Immutable
object DayEventsScope {
    /** Places this child over [span] of the day, in the column [position] from [layoutDay]. */
    fun Modifier.eventSlot(span: MinuteSpan, position: PositionedEvent): Modifier =
        this then EventSlotElement(EventSlot(span, position))
}

/**
 * The events column of the timeline (TODO.md §3.5): a custom [Layout] that is always
 * `24 × hourHeight` tall and places each child from its [DayEventsScope.eventSlot] —
 * top at `start / 60 × hourHeight`, height from the duration minus a 1dp gap with a 24dp
 * floor, and x/width from its [PositionedEvent] columns. Children without a slot are a
 * programming error.
 */
@Composable
fun DayEventsLayout(
    modifier: Modifier = Modifier,
    hourHeight: Dp = DayViewDefaults.HourHeight,
    content: @Composable DayEventsScope.() -> Unit,
) {
    Layout(content = { DayEventsScope.content() }, modifier = modifier) { measurables, constraints ->
        val hourPx = hourHeight.toPx()
        val height = (hourPx * 24).roundToInt()
        val width = constraints.maxWidth
        check(width != Constraints.Infinity) { "DayEventsLayout needs a bounded width" }

        val startInset = DayViewDefaults.EventsStartPadding.toPx()
        val usable = max(0f, width - startInset - DayViewDefaults.EventsEndPadding.toPx())
        val columnGap = DayViewDefaults.ColumnGap.toPx()
        val verticalGap = DayViewDefaults.ChipVerticalGap.toPx()
        val minHeight = DayViewDefaults.MinChipHeight.toPx()

        val placeables = measurables.map { measurable ->
            val slot = checkNotNull(measurable.parentData as? EventSlot) { "every DayEventsLayout child needs Modifier.eventSlot" }
            val (span, position) = slot
            val columnWidth = usable / position.colCount
            val left = startInset + position.col * columnWidth
            val reachesLastColumn = position.col + position.colSpan >= position.colCount
            val chipWidth = max(0f, position.colSpan * columnWidth - if (reachesLastColumn) 0f else columnGap)
            val chipHeight = max(span.duration / 60f * hourPx - verticalGap, minHeight)
            val placeable = measurable.measure(Constraints.fixed(chipWidth.roundToInt(), chipHeight.roundToInt()))
            Triple(placeable, left.roundToInt(), (span.start / 60f * hourPx).roundToInt())
        }
        layout(width, height) {
            placeables.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

private data class EventSlot(val span: MinuteSpan, val position: PositionedEvent)

private data class EventSlotElement(val slot: EventSlot) : ModifierNodeElement<EventSlotNode>() {
    override fun create() = EventSlotNode(slot)

    override fun update(node: EventSlotNode) {
        node.slot = slot
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "eventSlot"
        properties["span"] = slot.span
        properties["position"] = slot.position
    }
}

private class EventSlotNode(var slot: EventSlot) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = slot
}
