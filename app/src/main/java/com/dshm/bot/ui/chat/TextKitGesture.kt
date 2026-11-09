package com.dshm.bot.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

/**
 * Long-press + drag selection gesture for MinisTextKit. Mounted on the
 * scroll surface (the LazyColumn modifier) so the gesture handler receives
 * raw pointer events before LazyColumn's own scroll handler.
 *
 * Behavior:
 *  - Long-press over a registered text shard: clear existing selection,
 *    begin a new one collapsed at the hit-tested character.
 *  - Drag after long-press: continuously extend the selection's `end`
 *    endpoint to the current finger position. Near the viewport edge, the
 *    handler also nudges [listState] so the user can extend selection past
 *    the visible content.
 *  - Tap (down → up without long-press / drag) anywhere over a registered
 *    shard: clear the selection. Tap on non-shard area: ignore (the gesture
 *    propagates to children — LazyColumn scroll continues working normally).
 *
 * The handler intentionally does NOT consume the initial Down event: that
 * lets LazyColumn's flingable still receive scroll gestures that don't turn
 * into a long-press. Only once the long-press fires do we begin consuming.
 *
 * ## Mouse (external pointing device)
 *
 * [T-android-mouse-text-selection] A mouse takes a DIFFERENT path through
 * this same handler, because the touch contract above is wrong for it in
 * both directions:
 *
 *  - **Press-drag selects immediately — no long-press wait.** With a finger,
 *    an immediate drag has to mean "scroll", because the finger is also the
 *    only way to scroll and the gesture is ambiguous at the moment it starts.
 *    A mouse has a wheel, so drag is unambiguous and the long-press
 *    requirement is pure friction: it makes the user hold still for 400ms
 *    before a gesture whose intent was already clear.
 *  - **Drag selects CHARACTERS, not words.** [beginSelectionWord] exists
 *    because a fingertip covers several characters and cannot express a
 *    precise offset. A mouse cursor is a single pixel, so snapping to word
 *    bounds would override an intent the user CAN state exactly. Word
 *    granularity stays reachable through double-click.
 *  - **Right-click opens the menu**, at the cursor, without disturbing an
 *    existing selection — the platform convention on every desktop OS, and
 *    the only discoverable way to reach the actions when there is no
 *    long-press.
 *
 * Detection is per-gesture (`down.type`), not a global mode, so a 2-in-1 with
 * both a touchscreen and a trackpad behaves correctly under either input
 * without any setting. Stylus deliberately keeps the TOUCH path: it shares
 * the fingertip's imprecision and has no second button to open a menu with.
 */
fun Modifier.minisTextKitSelectionGesture(
    controller: SelectionController,
    listState: LazyListState,
    rootCoordinates: () -> LayoutCoordinates?,
    /** Set true when the LazyColumn uses `reverseLayout = true` (chat lists). */
    reverseLayout: Boolean = false,
    onLongPressEngaged: () -> Unit = {},
): Modifier = composed {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    pointerInput(controller, listState, reverseLayout) {
    val longPressTimeoutMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    val touchSlopPx = viewConfiguration.touchSlop
    val doubleTapTimeoutMs = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
    // Time of the last completed mouse click and where it landed, so the
    // NEXT click can decide whether it is the second half of a double-click.
    var lastMouseClickUptimeMs = 0L
    var lastMouseClickPoint = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downLocal = down.position
        var lastWindowPoint = localToWindow(downLocal, rootCoordinates)

        // ── Mouse: bypass the long-press contract entirely ───────────────
        if (down.type == PointerType.Mouse) {
            val handled = handleMouseGesture(
                controller = controller,
                down = down,
                downLocal = downLocal,
                downWindowPoint = lastWindowPoint,
                rootCoordinates = rootCoordinates,
                touchSlopPx = touchSlopPx,
                doubleTapTimeoutMs = doubleTapTimeoutMs,
                lastClickUptimeMs = lastMouseClickUptimeMs,
                lastClickPoint = lastMouseClickPoint,
            )
            lastMouseClickUptimeMs = handled.clickUptimeMs
            lastMouseClickPoint = handled.clickPoint
            return@awaitEachGesture
        }

        // ── Handle-drag short-circuit ────────────────────────────────────
        // If a selection already exists and the user pressed within reach
        // of one of its endpoint handles, skip the long-press wait and go
        // straight to handle-drag mode. This is what makes the existing
        // selection feel "grippable" — without it the user would have to
        // long-press inside the selection again before they could resize.
        // Handle grabs are detected by the handle Popups themselves (their
        // own pointerInput consumes the DOWN before it propagates here, so
        // a press inside a popup never reaches the LazyColumn). No
        // short-circuit needed in this gesture.

        // ── Phase 1: wait for the long-press timeout ─────────────────────
        val abortReason = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "lost"
                val pos = change.position
                lastWindowPoint = localToWindow(pos, rootCoordinates)
                if (!change.pressed) {
                    // Tap — clear selection if it exists and the tap landed
                    // directly inside a registered shard. Use STRICT so a
                    // tap on a user bubble / padding doesn't dismiss the
                    // active selection on an adjacent assistant message.
                    if (controller.selection.value != null &&
                        controller.hitTestStrict(lastWindowPoint) != null
                    ) {
                        controller.clearSelection()
                    }
                    return@withTimeoutOrNull "tap"
                }
                if ((pos - downLocal).getDistance() >= touchSlopPx) {
                    return@withTimeoutOrNull "scroll"
                }
            }
            @Suppress("UNREACHABLE_CODE") "unreached"
        }
        if (abortReason != null) return@awaitEachGesture

        // ── Engage selection: snap to the word under the finger ─────────
        // Use the STRICT hit-test (no nearest-shard fallback) so a long-
        // press inside a non-registered region — a user message bubble,
        // a tool call pill, an empty padding area — does NOT erroneously
        // snap to whatever assistant shard happens to be closest. The
        // press still flows through to other gesture handlers (e.g. the
        // user bubble's own long-press → action menu).
        val hit = controller.hitTestStrict(lastWindowPoint) ?: return@awaitEachGesture
        controller.selectionFromMouse.value = false
        controller.beginSelectionWord(hit)
        // Fired HERE, not at the long-press timeout: the strict hit-test above
        // returns null over a non-selectable region, and buzzing before it
        // would give feedback for a selection that never happened. This is
        // also why it lives in the gesture rather than at the call site —
        // `onLongPressEngaged` has a no-op default that every caller was
        // taking, so text selection had no haptic at all while every other
        // long-press menu in the app did.
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPressEngaged()
        // Take ownership so LazyColumn doesn't reinterpret subsequent motion
        // as a scroll.
        down.consume()

        // ── Phase 2: drag extends the END endpoint past the word ────────
        // Don't publish dragIntent until the finger actually moves past
        // touchSlop. The initial position equals the long-press point,
        // which we already used to snap to a word — if we published it
        // immediately the DragTracker would re-hit-test at that point and
        // collapse the selection back onto the original character offset.
        var dragStarted = false
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                val movedPx = (change.position - downLocal).getDistance()
                if (!dragStarted && movedPx < touchSlopPx) {
                    // Still stationary — leave the word selection alone.
                    change.consume()
                    continue
                }
                dragStarted = true
                lastWindowPoint = localToWindow(change.position, rootCoordinates)
                controller.dragIntent.value = SelectionController.DragIntent(
                    point = lastWindowPoint,
                    handle = SelectionController.Handle.End,
                )
                // [T-android-text-selection-scroll-jitter] Edge auto-scroll
                // is now driven exclusively by the [SelectionDragTracker]
                // tick loop (frame-rate ticker). Calling it from here on
                // every pointer event stacked two nudges per frame and
                // amplified the feedback loop that made the top-edge handle
                // drag jitter and stall on the upward direction.
                change.consume()
            }
        } finally {
            controller.dragIntent.value = null
        }
    }
    }
}

/**
 * What a completed mouse gesture leaves behind for the next one: the time and
 * place of the click, so a following click can be recognised as a double.
 * Both are zeroed when the gesture was not a plain click (a drag, or a
 * right-click), which correctly prevents those from starting a double-click
 * pair.
 */
private data class MouseGestureOutcome(
    val clickUptimeMs: Long,
    val clickPoint: Offset,
)

private val MOUSE_NO_CLICK = MouseGestureOutcome(0L, Offset.Zero)

/**
 * [T-android-mouse-text-selection] The mouse branch of
 * [minisTextKitSelectionGesture]. See that function's docs for why mouse
 * input does not go through the long-press contract.
 *
 * Recognises three gestures, decided in this order:
 *
 *  1. **Secondary button down** → open the action menu at the cursor. If the
 *     cursor is inside an existing selection, that selection is KEPT (the
 *     desktop convention: right-clicking a highlighted passage acts on the
 *     passage). Otherwise the word under the cursor is selected first, so the
 *     menu always has a subject and never appears over nothing.
 *  2. **Primary press within the double-click window, near the previous
 *     click** → select the word. This is the only route to word granularity
 *     for a mouse, drag having been given character granularity.
 *  3. **Primary press-and-drag** → character-precise range selection, live as
 *     the cursor moves.
 *
 * A primary press that neither drags nor doubles simply clears the selection
 * on release, matching the touch path's tap-to-dismiss.
 *
 * Returns the click bookkeeping the caller must carry into the next gesture.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleMouseGesture(
    controller: SelectionController,
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    downLocal: Offset,
    downWindowPoint: Offset,
    rootCoordinates: () -> LayoutCoordinates?,
    touchSlopPx: Float,
    doubleTapTimeoutMs: Long,
    lastClickUptimeMs: Long,
    lastClickPoint: Offset,
): MouseGestureOutcome {
    // Any press dismisses a menu left open by a previous right-click, before
    // that press is interpreted. The Popup itself cannot do this: it is
    // non-focusable (so it never steals focus from the composer) and therefore
    // sees no outside clicks. Dismissal is separate from clearing the
    // selection — clicking away from the menu should retire the menu while
    // leaving the highlight for the next right-click to act on.
    controller.dismissContextMenu()

    // ── 1. Right-click → context menu ───────────────────────────────────
    // Read the button state from the event that delivered this DOWN rather
    // than from the change itself: `buttons` lives on PointerEvent, and on
    // a right-press the primary button is not down at all.
    if (currentEvent.buttons.isSecondaryPressed) {
        val hit = controller.hitTestStrict(downWindowPoint) ?: return MOUSE_NO_CLICK
        // Keep an existing selection when the click lands inside it; the
        // user is targeting what they already highlighted. Only when the
        // click is outside (or there is nothing selected) do we select the
        // word under the cursor to give the menu a subject.
        if (!controller.selectionContains(hit)) {
            controller.selectionFromMouse.value = true
            controller.beginSelectionWord(hit)
        }
        controller.requestContextMenu(downWindowPoint)
        down.consume()
        // Swallow the rest of this gesture so the release does not fall
        // through to the tap-clears-selection path and dismiss the menu we
        // just opened.
        consumeUntilRelease(down.id)
        return MOUSE_NO_CLICK
    }

    // ── 2. Double-click → select the word ───────────────────────────────
    val isDoubleClick = lastClickUptimeMs != 0L &&
        (down.uptimeMillis - lastClickUptimeMs) <= doubleTapTimeoutMs &&
        (downLocal - lastClickPoint).getDistance() <= touchSlopPx * 2
    if (isDoubleClick) {
        val hit = controller.hitTestStrict(downWindowPoint)
        if (hit != null) {
            controller.selectionFromMouse.value = true
            controller.beginSelectionWord(hit)
            down.consume()
            consumeUntilRelease(down.id)
            // Not a click for double-click purposes — otherwise a third
            // click would pair with this one and re-select the word.
            return MOUSE_NO_CLICK
        }
    }

    // ── 3. Primary press-drag → character-precise selection ─────────────
    // Do NOT consume the DOWN yet, and do not begin a selection: a press
    // that never moves must remain available to whatever else wants it
    // (link clicks, LazyColumn's own handling). Selection begins only once
    // the cursor has actually travelled past slop.
    var dragging = false
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val change = ev.changes.firstOrNull { it.id == down.id }
            ?: return MOUSE_NO_CLICK
        val windowPoint = localToWindow(change.position, rootCoordinates)

        if (!change.pressed) {
            if (dragging) {
                controller.dragIntent.value = null
                return MOUSE_NO_CLICK
            }
            // A plain click: clear any selection (same rule as a touch tap —
            // strict hit-test, so clicking a user bubble or padding leaves an
            // adjacent selection alone) and record it as a double-click
            // candidate.
            if (controller.selection.value != null &&
                controller.hitTestStrict(windowPoint) != null
            ) {
                controller.clearSelection()
            }
            return MouseGestureOutcome(change.uptimeMillis, change.position)
        }

        if (!dragging) {
            if ((change.position - downLocal).getDistance() < touchSlopPx) continue
            // Anchor at the character under the ORIGINAL press, not the
            // current position — the run the user meant to select starts
            // where the button went down, and slop has since moved the
            // cursor a few pixels away from it.
            val anchor = controller.hitTestStrict(downWindowPoint)
                ?: return MOUSE_NO_CLICK
            controller.selectionFromMouse.value = true
            controller.beginSelection(anchor)
            dragging = true
            down.consume()
        }

        controller.dragIntent.value = SelectionController.DragIntent(
            point = windowPoint,
            handle = SelectionController.Handle.End,
        )
        change.consume()
    }
}

/**
 * Swallow every remaining event of the gesture identified by [pointerId], up
 * to and including the release. Used after a mouse gesture has already done
 * its work (opened the menu, selected a word) so the trailing UP cannot be
 * re-interpreted downstream as a fresh click.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilRelease(
    pointerId: androidx.compose.ui.input.pointer.PointerId,
) {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val change = ev.changes.firstOrNull { it.id == pointerId } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/**
 * Edge-distance touch slop, px. Bigger → handles are easier to grab.
 * Pixel 4a is ~2.625x density; 96 px ≈ 36 dp, a generous thumb pad. The
 * grabHandleAt() check further multiplies by 1.5x so the effective grab
 * radius is ~54 dp around the dot center.
 */
private const val HANDLE_TOUCH_SLOP_PX = 96f

/** Edge auto-scroll zone, px. Within this many px of the viewport edge a drag triggers a nudge. */
private const val EDGE_AUTO_SCROLL_ZONE_PX = 80f

/** Max nudge per pointer event when finger is at the very edge. */
private const val EDGE_AUTO_SCROLL_MAX_PX = 14f

private fun edgeAutoScrollNudge(
    localY: Float,
    viewportH: Int,
    listState: LazyListState,
    reverseLayout: Boolean,
) {
    // Sign meaning here is "viewport reveals content in the direction the
    // finger is pulling": at the BOTTOM edge the user wants to see content
    // BELOW the viewport, at the TOP edge content ABOVE.
    //
    // For a normal LazyColumn that maps to:
    //   bottom edge → scrollBy(+) (move viewport down)
    //   top edge    → scrollBy(-)
    // For a reverseLayout=true LazyColumn (chat, index 0 at the bottom),
    // dispatchRawDelta's sign is FLIPPED relative to user intent:
    //   bottom edge → scrollBy(-) (reveal newer / lower-index items)
    //   top edge    → scrollBy(+)
    val rawMagnitude = when {
        localY < EDGE_AUTO_SCROLL_ZONE_PX ->
            -((EDGE_AUTO_SCROLL_ZONE_PX - localY) / EDGE_AUTO_SCROLL_ZONE_PX) * EDGE_AUTO_SCROLL_MAX_PX
        localY > viewportH - EDGE_AUTO_SCROLL_ZONE_PX ->
            ((localY - (viewportH - EDGE_AUTO_SCROLL_ZONE_PX)) / EDGE_AUTO_SCROLL_ZONE_PX) * EDGE_AUTO_SCROLL_MAX_PX
        else -> 0f
    }
    if (rawMagnitude == 0f) return
    val signed = if (reverseLayout) -rawMagnitude else rawMagnitude
    listState.dispatchRawDelta(signed)
}

/**
 * Active drag loop after the user grabs a selection handle. Adjusts the
 * grabbed endpoint (start or end) to wherever the finger goes; the other
 * endpoint stays put. Defined as an extension on AwaitPointerEventScope so
 * it inherits the restricted-suspending context awaitEachGesture provides.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleDragLoop(
    controller: SelectionController,
    listState: LazyListState,
    rootCoordinates: () -> LayoutCoordinates?,
    grabbed: SelectionController.Handle,
    pointerId: androidx.compose.ui.input.pointer.PointerId,
    reverseLayout: Boolean,
) {
    // Publish drag intent on every pointer event; the actual hit-test +
    // selection update happens in [SelectionDragTracker] which also wakes
    // on listState scroll, so stationary-finger auto-scroll still keeps
    // extending the selection.
    try {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val change = ev.changes.firstOrNull { it.id == pointerId } ?: return
            if (!change.pressed) return
            val windowPoint = localToWindow(change.position, rootCoordinates)
            controller.dragIntent.value = SelectionController.DragIntent(
                point = windowPoint,
                handle = grabbed,
            )
            // [T-android-text-selection-scroll-jitter] See matching note in
            // the long-press handler: edge auto-scroll moved out of the
            // per-pointer-event path and into [SelectionDragTracker]'s
            // frame-rate ticker. Two sources of nudges produced upward-
            // direction handle jitter / stall.
            change.consume()
        }
    } finally {
        controller.dragIntent.value = null
    }
}

/**
 * Side-effect that translates [SelectionController.dragIntent] into actual
 * selection updates. Runs in a coroutine OUTSIDE the restricted pointerInput
 * scope so it can compose hit-test reads with listState scroll observation:
 * whenever EITHER the finger position changes OR the LazyColumn scrolls,
 * we re-hit-test and update the corresponding selection endpoint.
 *
 * Without this indirection a stationary finger inside the auto-scroll edge
 * zone would "lose" the selection — pointer events stop firing, so the
 * inline hit-test in the gesture loop never re-runs against shards that
 * just scrolled into view. Mount this once near the top of the screen
 * that owns the SelectionController.
 */
@Composable
fun SelectionDragTracker(
    controller: SelectionController,
    listState: LazyListState,
    listRootCoordinates: () -> LayoutCoordinates?,
    reverseLayout: Boolean = false,
) {
    // [T-android-text-selection-scroll-jitter] Two responsibilities split
    // into two LaunchedEffects:
    //
    // 1) Hit-test re-run on every dragIntent change so the grabbed
    //    endpoint stays under the finger as it moves OR as auto-scroll
    //    shifts content underneath. Triggered by snapshotFlow on
    //    dragIntent.value.
    //
    // 2) Edge auto-scroll driven by a frame-rate tick loop while a drag
    //    is active. Pre-fix, the nudge was called both from the gesture
    //    handlers (every pointer event) AND from inside the hit-test
    //    collect block above. That stacked two nudges per pointer event
    //    AND coupled selection layout recompose to scroll dispatch, which
    //    created a feedback loop on the upward-direction drag: scroll
    //    shifted content under the handle → next pointer event hit-tested
    //    against a new shard → dragIntent updated → another nudge piled
    //    on → handle position visually jumped → user micro-corrected →
    //    new pointer event → repeat. Symptom was "constant jitter + cannot scroll up"
    //    (TG35696-35699 LeeeSe/𝙓𝙄𝙉).
    //
    // The tick loop reads the latest dragIntent point each frame
    // independently of pointer events, so a stationary finger inside the
    // edge zone still drives the list AND the nudge cadence is fixed at
    // ~60Hz regardless of how often the OS delivers MOVE events.
    LaunchedEffect(controller, listState, reverseLayout) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value }
            .collect { intent ->
                if (intent == null) return@collect
                val target = controller.hitTest(intent.point) ?: return@collect
                when (intent.handle) {
                    SelectionController.Handle.Start -> controller.replaceStart(target)
                    SelectionController.Handle.End -> controller.replaceEnd(target)
                }
            }
    }

    // Frame-rate edge auto-scroll ticker. Activated while dragIntent is
    // non-null and finger is inside the top/bottom edge zone.
    LaunchedEffect(controller, listState, reverseLayout) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value != null }
            .collect { dragActive ->
                if (!dragActive) return@collect
                // While the drag is active, tick at ~60Hz and re-evaluate
                // the edge condition. The inner loop exits as soon as
                // dragIntent becomes null (gesture handler clears it on
                // pointer-up).
                while (controller.dragIntent.value != null) {
                    val intent = controller.dragIntent.value
                    val rootCoords = listRootCoordinates()
                    if (intent != null && rootCoords != null && rootCoords.isAttached) {
                        val origin = rootCoords.positionInWindow()
                        val localY = intent.point.y - origin.y
                        val viewportH = rootCoords.size.height
                        edgeAutoScrollNudge(localY, viewportH, listState, reverseLayout)
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
    }
}

/** Convert a local-space pointer point into window-space coordinates. */
private fun localToWindow(
    local: Offset,
    rootCoordinates: () -> LayoutCoordinates?,
): Offset {
    val coords = rootCoordinates() ?: return local
    if (!coords.isAttached) return local
    val origin = coords.positionInWindow()
    return Offset(local.x + origin.x, local.y + origin.y)
}

/**
 * iOS-style draggable selection handles — a small filled circle below each
 * endpoint of the active selection. The handles are visual only; the
 * actual drag logic lives in [minisTextKitSelectionGesture] which routes
 * pointer events through [SelectionController.grabHandleAt]. Rendering
 * them as separate Popups means they can extend BELOW the LazyColumn item
 * (matching system text-selection handle UX) without being clipped.
 */
/**
 * Total hit area for a handle. The visible dot inside is ~10dp, but the
 * Popup body is much larger to forgive imprecise touches — matches what
 * Android's system text-selection handles do.
 */
private val HANDLE_HIT_SIZE_DP = 56.dp

/** Visible dot diameter. */
private val HANDLE_DOT_SIZE_DP = 14.dp

@Composable
fun MinisSelectionHandlesHost(
    controller: SelectionController,
    listState: LazyListState,
    reverseLayout: Boolean = false,
) {
    val sel by controller.selection
    if (sel == null) return
    // [T-android-mouse-text-selection] No handle dots for a mouse selection —
    // see SelectionController.selectionFromMouse.
    if (controller.selectionFromMouse.value) return
    // Recompute handle anchors every frame so the Popups follow the
    // underlying shards as the LazyColumn scrolls. The shards' positions
    // change inside their parent's layout pass, which doesn't invalidate
    // this composable on its own — we need the explicit frame tick.
    val tick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(controller) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { /* no-op */ }
            tick.value = tick.value + 1
        }
    }
    // Read the tick so the next two anchor reads invalidate every frame.
    @Suppress("UNUSED_VARIABLE") val t = tick.value
    // FREEZE handle positions while a drag is active. Otherwise the popup
    // chases the selection endpoint as the user drags, which keeps moving
    // the popup window's content under the finger — popup-local pointer
    // deltas then no longer equal screen-space deltas and the drag
    // computation diverges (finger goes one way, selection goes the
    // opposite way). Compose's own SelectionManager avoids this because
    // its drag handler lives in the selection container, not inside the
    // popup. Our drag lives in the popup; freezing the popup during drag
    // restores the invariant "popup-local delta == window delta".
    val dragging = controller.dragIntent.value != null
    val frozenStart = remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    val frozenEnd = remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    // Each handle's anchor is computed independently — if ONE endpoint's
    // shard scrolled out of the viewport (off-screen, disposed by
    // LazyColumn), the OTHER end's shard may still be visible and its
    // handle should still render. Previously a single `?: return` aborted
    // the whole host, hiding even the in-viewport handle.
    val liveStart = controller.handleAnchor(SelectionController.Handle.Start)
    val liveEnd = controller.handleAnchor(SelectionController.Handle.End)
    if (dragging) {
        if (frozenStart.value == null && liveStart != null) frozenStart.value = liveStart
        if (frozenEnd.value == null && liveEnd != null) frozenEnd.value = liveEnd
    } else {
        frozenStart.value = null
        frozenEnd.value = null
    }
    val startAnchor = frozenStart.value ?: liveStart
    val endAnchor = frozenEnd.value ?: liveEnd
    if (startAnchor != null) {
        SelectionHandleDot(
            anchorWindow = startAnchor,
            isStart = true,
            controller = controller,
            listState = listState,
            reverseLayout = reverseLayout,
        )
    }
    if (endAnchor != null) {
        SelectionHandleDot(
            anchorWindow = endAnchor,
            isStart = false,
            controller = controller,
            listState = listState,
            reverseLayout = reverseLayout,
        )
    }
}

@Composable
private fun SelectionHandleDot(
    anchorWindow: Offset,
    isStart: Boolean,
    controller: SelectionController,
    listState: LazyListState,
    reverseLayout: Boolean,
) {
    val color = MaterialTheme.colorScheme.primary
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dotPx = with(density) { (HANDLE_DOT_SIZE_DP / 2).toPx() }
    val hitPx = with(density) { HANDLE_HIT_SIZE_DP.toPx() }
    val provider = remember(anchorWindow.x, anchorWindow.y, isStart, hitPx) {
        HandlePositionProvider(anchorWindow, isStart = isStart, hitSizePx = hitPx)
    }
    val handleKind = if (isStart) SelectionController.Handle.Start else SelectionController.Handle.End
    // Live window origin of the popup's content box, updated every layout
    // pass. Used by the drag gesture below to convert popup-local pointer
    // coordinates into window-absolute coordinates without accumulating
    // delta-based drift.
    var boxWindowOrigin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        // The hit-target box has its OWN pointerInput because pointer events
        // inside a Popup do NOT bubble back into the LazyColumn that owns
        // [minisTextKitSelectionGesture] — popups are separate windows. The
        // gesture here mirrors handleDragLoop's behavior: publish the latest
        // finger window-point through controller.dragIntent, and let
        // SelectionDragTracker convert that into actual selection updates.
        Box(
            modifier = Modifier
                .size(HANDLE_HIT_SIZE_DP)
                // The hit-target box sits inside a Popup that *re-positions*
                // every frame to follow the selection endpoint as the
                // LazyColumn scrolls. If we computed window coords via raw
                // delta accumulation (popup-local position - down position
                // + anchor), we'd get phantom motion: even with a stationary
                // finger the Popup moves under it during scroll, so the
                // popup-local position changes → false drag delta → fake
                // selection updates → "selection shifts on scroll" symptom.
                //
                // Capture the popup body's current windowOrigin via
                // onGloballyPositioned. The drag handler then computes
                // window-absolute = position + windowOrigin **every event**,
                // which is invariant under popup motion: when popup moves
                // by dy, the local position changes by -dy in the same
                // frame, and the sum stays put.
                .pointerInput(controller, handleKind) {
                    // Popup is its OWN window; pointer positions inside the
                    // popup are NOT in the main app window's coordinate
                    // space. positionInWindow() on anything inside the popup
                    // returns popup-local coords (0,0 = popup's top-left).
                    //
                    // We DO know where the popup body sits in main-window
                    // coords: HandlePositionProvider placed it at
                    //   x = (isStart ? anchorWindow.x - popupWidth : anchorWindow.x)
                    //   y = anchorWindow.y
                    // We don't have popupWidth at runtime, so anchor the
                    // gesture against a delta from the long-press / drag
                    // start position. Compose's detectDragGestures gives us
                    // both the absolute popup-local PointerInputChange and
                    // a per-frame delta — use the delta to accumulate a
                    // total drag, then publish (anchorWindow + total).
                    var dragTotal = Offset.Zero
                    var dragStartAnchor = anchorWindow
                    detectDragGestures(
                        onDragStart = {
                            // Snapshot the handle's current anchor at the
                            // moment drag begins. Re-snapshot here (rather
                            // than relying on `anchorWindow` from the
                            // composable's closure) because the surrounding
                            // recomposition cycle has been re-instantiating
                            // anchorWindow every frame — we want the value
                            // AS OF the user's press, then accumulate
                            // deltas from there.
                            dragStartAnchor = controller.handleAnchor(handleKind) ?: anchorWindow
                            dragTotal = Offset.Zero
                            controller.dragIntent.value = SelectionController.DragIntent(
                                point = dragStartAnchor,
                                handle = handleKind,
                            )
                        },
                        onDrag = { _, delta ->
                            dragTotal += delta
                            val windowPos = dragStartAnchor + dragTotal
                            controller.dragIntent.value = SelectionController.DragIntent(
                                point = windowPos,
                                handle = handleKind,
                            )
                        },
                        onDragEnd = { controller.dragIntent.value = null },
                        onDragCancel = { controller.dragIntent.value = null },
                    )
                },
            // Popup box is centered on anchorWindow.x; align the dot horizontally
            // to the center so the visible knob sits exactly under the selection
            // edge. The asymmetric TopEnd/TopStart from earlier was wrong once
            // HandlePositionProvider switched to centering the body.
            contentAlignment = Alignment.TopCenter,
        ) {
            // While THIS handle is being dragged, draw the dot at the
            // finger's live position (offset relative to the popup body,
            // which is itself frozen so popup-local deltas equal window
            // deltas). When not dragging, the dot stays at TopCenter as
            // before, snapping to the selection endpoint anchor.
            val live = controller.dragIntent.value
            val draggingThis = live != null && live.handle == handleKind
            val dragOffsetWindow = if (draggingThis) {
                live!!.point - anchorWindow
            } else Offset.Zero
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(HANDLE_DOT_SIZE_DP)
                    .offset {
                        IntOffset(
                            x = dragOffsetWindow.x.toInt(),
                            y = dragOffsetWindow.y.toInt(),
                        )
                    },
            ) {
                drawCircle(color = color, radius = dotPx)
            }
        }
    }
}

/**
 * Position the 40dp hit-target box so the dot inside sits flush against
 * the selection edge in window space. Start handle: box's right edge sits
 * at anchor.x. End handle: box's left edge sits at anchor.x. Vertically
 * the box's top sits at anchor.y (the bottom-of-text line), so the dot
 * hangs just below the line — matching system handles.
 */
private class HandlePositionProvider(
    private val anchorWindow: Offset,
    private val isStart: Boolean,
    private val hitSizePx: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Center the popup body horizontally on anchorWindow.x. The visible
        // dot inside the body is drawn at TopEnd (start handle) or TopStart
        // (end handle), so:
        //   - START handle: dot's right edge lines up with the box's
        //     horizontal center → dot at (anchorWindow.x − dotRadius)
        //   - END handle: dot's left edge lines up with the box's center →
        //     dot at (anchorWindow.x + dotRadius)
        // Both flavours expose roughly the same hit-target area, but the
        // visible knob sits at the selection edge instead of overhanging
        // the line by an entire 56 dp.
        val halfBox = popupContentSize.width / 2
        val x = (anchorWindow.x - halfBox).toInt()
        val y = (anchorWindow.y).toInt()
        return IntOffset(
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/**
 * Floating action toolbar rendered above the active selection. Mirrors
 * [MinisMarkdownTextToolbarHost] (which is driven by Compose's
 * SelectionContainer) but consumes our SelectionController state directly.
 *
 * Empty selection → not rendered. Whenever the selection's bounding rect is
 * available (both endpoints' shards are currently composed), the toolbar
 * floats just above it; if the selection collapses or moves off-screen the
 * toolbar disappears.
 */
/**
 * Bundle of side-effecting callbacks the toolbar surfaces as buttons.
 * Pass null to hide a button (e.g. "Add to Input" is hidden when the
 * caller has no composer to receive the text). Mirrors the action set of
 * the older [MinisMarkdownTextToolbar] so the per-button UX stays the
 * same across both selection systems.
 */
data class SelectionToolbarActions(
    /** Resolve the markdown source of the message that owns the active selection, or null on cross-message selections. */
    val resolveSelectionMarkdown: () -> String?,
    /** Append the currently-selected plain text to the chat composer. Null hides the button. */
    val onAddToInput: ((String) -> Unit)? = null,
    /**
     * [T-android-selection-readaloud] Speak the currently-selected plain text
     * through Minis TTS. Null hides the button. Mirrors iOS's "Read Selection".
     */
    val onReadAloud: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Speak the WHOLE message that
     * owns the selection, from its beginning — iOS's "Read from Start".
     *
     * Separate from [onReadAloud] because the two differ in scope, not in
     * mechanism: one speaks the highlighted range, the other restarts the
     * entire reply. Android previously offered only the first, under the
     * ambiguous label "Read Aloud", so a user who wanted the reply narrated
     * had to select the whole thing by hand.
     *
     * Receives the message's markdown source; the TTS layer sanitizes markdown
     * on enqueue (VoiceTextSanitizer), which is the same contract the existing
     * read-aloud paths rely on. Null hides the button — the caller passes null
     * while the reply is still streaming, matching iOS, where replaying a
     * half-arrived answer would narrate a truncated text.
     */
    val onReadFromStart: ((String) -> Unit)? = null,
)

/** `加入输入框` 的落点：由对话页提供（选中文字后塞进草稿）。 */
val LocalSelectionAddToInput = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

/**
 * 选中文字后的浮动条。
 *
 * 移植自 DeepSearch 的 `MinisSelectionToolbarHost`，只保留三个动作：
 * 复制 / 加入输入框 / 复制全文。选中状态由 [SelectionController] 驱动，
 * 所以代码块、表格、列表里的文字都能选。
 */
@Composable
fun MinisSelectionToolbarHost(
    controller: SelectionController,
    contentViewportBounds: () -> androidx.compose.ui.geometry.Rect? = { null },
) {
    val sel by controller.selection
    if (sel == null) return
    // 照 DeepSearch `MinisSelectionToolbarHost`：shard 的窗口坐标在
    // LazyColumn 的 layout pass 里变，不会自动触发这里重组。用每帧 tick
    // 让浮条跟着选区/把手走；否则选区扩了浮条还钉在旧位置，看起来像拖不动。
    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(controller) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { /* no-op */ }
            tick.value = tick.value + 1
        }
    }
    @Suppress("UNUSED_VARIABLE") val frame = tick.value
    val anchor = controller.visibleSelectionEndLineRect()
        ?: controller.visibleSelectionWindowRect()
        ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val addToInput = LocalSelectionAddToInput.current
    val markdown = controller.selectionMessageMarkdown()

    val anchorRect = remember(anchor, frame) {
        IntRect(
            anchor.left.toInt(),
            anchor.top.toInt(),
            anchor.right.toInt(),
            anchor.bottom.toInt(),
        )
    }
    Popup(
        popupPositionProvider = remember(anchorRect) { SelectionBarPosition(anchorRect) },
        onDismissRequest = { /* 由列表手势里的 tap 清除，别在点把手时把选区清掉 */ },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(10.dp),
            color = com.dshm.bot.core.design.Dshm.colors.surface,
            shadowElevation = 8.dp,
            border = BorderStroke(0.5.dp, com.dshm.bot.core.design.Dshm.colors.divider),
        ) {
            Row(
                modifier = Modifier.height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionBarButton(androidx.compose.ui.res.stringResource(com.dshm.bot.R.string.chat_selection_copy)) {
                    val text = controller.selectedPlainText()
                    if (text.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(text))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(com.dshm.bot.R.string.chat_copied),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    controller.clearSelection()
                }
                if (addToInput != null) {
                    SelectionBarDivider()
                    SelectionBarButton(androidx.compose.ui.res.stringResource(com.dshm.bot.R.string.chat_selection_add_to_input)) {
                        val text = controller.selectedPlainText()
                        if (text.isNotEmpty()) addToInput.invoke(text)
                        controller.clearSelection()
                    }
                }
                if (!markdown.isNullOrBlank()) {
                    SelectionBarDivider()
                    SelectionBarButton(androidx.compose.ui.res.stringResource(com.dshm.bot.R.string.chat_selection_copy_markdown)) {
                        clipboard.setText(AnnotatedString(markdown))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(com.dshm.bot.R.string.chat_copied),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        controller.clearSelection()
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBarButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = com.dshm.bot.core.design.Dshm.colors.textPrimary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun SelectionBarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(com.dshm.bot.core.design.Dshm.colors.divider),
    )
}

/** 浮条贴在选区的可见部分上方，放不下就翻到下面。 */
private class SelectionBarPosition(private val anchor: IntRect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 8
        val centerX = anchor.left + anchor.width / 2
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchor.top - popupContentSize.height - gap
        val y = if (above >= 0) {
            above
        } else {
            (anchor.bottom + gap)
                .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}
/** One entry in the selection toolbar — inline button or overflow menu row. */
/**
 * One entry in the selection bar / overflow menu.
 *
 * [children] turns the entry into a SUBMENU: tapping it expands the nested
 * actions in place instead of firing [onClick]. Used for "Copy Full Text",
 * which groups the three whole-message copy formats — see the item list.
 */
private class SelectionAction(
    val label: String,
    val children: List<SelectionAction> = emptyList(),
    val onClick: () -> Unit = {},
)

/**
 * How many actions stay on the bar before the rest move into the overflow menu.
 *
 * Three, matching what iOS's edit menu shows before its own chevron. The bar is
 * anchored to a selection the user is looking at, so it has to stay narrow
 * enough not to cover the text it belongs to.
 */
private const val MAX_INLINE_SELECTION_ACTIONS = 3

@Composable
private fun MinisToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun MinisToolbarButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * [T-android-selection-overflow-anchor] Positions the selection toolbar's
 * overflow menu directly under the "⋯" button.
 *
 * [anchorWindow] is the button's bottom-center in WINDOW coordinates, captured by
 * the caller — the menu is a sibling Popup of the toolbar, not a child, so it
 * cannot rely on `anchorBounds` (which would be measured against the wrong
 * window and is what pinned the menu to the screen corner).
 *
 * Horizontally the body is centered on the button and clamped to the screen;
 * vertically it opens downward, flipping above the button when there isn't room
 * below so it never runs off the bottom edge.
 */
private class SelectionOverflowPositionProvider(
    private val anchorWindow: Offset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 4
        val x = (anchorWindow.x - popupContentSize.width / 2f).toInt()
        val below = (anchorWindow.y + gap).toInt()
        // Flip above the button when the menu would overflow the bottom.
        val y = if (below + popupContentSize.height > windowSize.height) {
            (anchorWindow.y - popupContentSize.height - gap).toInt()
        } else {
            below
        }
        return IntOffset(
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private class FloatingSelectionToolbarPositionProvider(
    private val anchor: IntRect,
    private val viewport: androidx.compose.ui.geometry.Rect?,
    private val preferredCenterX: Float?,
    /**
     * [T-android-mouse-text-selection] True when [anchor] is a right-click
     * cursor point rather than a selection rect. Suppresses the handle-dot
     * clearance gap, and disables the "(0,0,0,0) means off-screen" sentinel —
     * a cursor at the window origin is a real position, not a missing one.
     */
    private val atCursor: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // The viewport defines the vertical band inside which the toolbar
        // may be placed (typically the LazyColumn's window bounds — keeps
        // the menu off the top app bar / composer / gesture navigation
        // pill at the screen edges). Fall back to the whole window when
        // no viewport was supplied.
        val viewTop = viewport?.top?.toInt() ?: 0
        val viewBottom = viewport?.bottom?.toInt() ?: windowSize.height
        val viewLeft = viewport?.left?.toInt() ?: 0
        val viewRight = viewport?.right?.toInt() ?: windowSize.width
        val maxY = (viewBottom - popupContentSize.height).coerceAtLeast(viewTop)
        val maxX = (viewRight - popupContentSize.width).coerceAtLeast(viewLeft)

        // Empty anchor (0,0,0,0): selection completely off-screen — pin to
        // top center of the viewport.
        if (!atCursor &&
            anchor.left == 0 && anchor.top == 0 && anchor.right == 0 && anchor.bottom == 0
        ) {
            val centerX = (viewLeft + viewRight) / 2
            val x = (centerX - popupContentSize.width / 2).coerceIn(viewLeft, maxX)
            val y = (viewTop + 12).coerceIn(viewTop, maxY)
            return IntOffset(x, y)
        }
        // Gap above the anchor — large enough to clear the selection
        // handle dot (HANDLE_DOT_SIZE_DP = 14dp ≈ 37px on Pixel 4a) plus
        // a bit of breathing room so the menu doesn't crowd the text.
        val gap = if (atCursor) 8 else 48
        // Prefer the explicit "midpoint between handles" when supplied —
        // otherwise the anchor's own horizontal center.
        val centerX = preferredCenterX?.toInt() ?: (anchor.left + anchor.width / 2)
        val x = (centerX - popupContentSize.width / 2).coerceIn(viewLeft, maxX)
        // Vertical placement. The two anchor kinds want OPPOSITE defaults:
        //
        //  - A selection rect prefers ABOVE, because the text it describes is
        //    below and a menu over it would hide the thing being acted on.
        //  - A right-click cursor prefers BELOW, the desktop convention — and
        //    there is nothing to hide, the cursor occupies no area.
        //
        // Each falls back to the other side when its preferred side has no
        // room, and both clamp to [viewTop, maxY] so the menu cannot escape
        // the chat content area.
        val aboveY = anchor.top - popupContentSize.height - gap
        val belowY = anchor.bottom + gap
        val y = if (atCursor) {
            if (belowY + popupContentSize.height <= viewBottom) belowY else aboveY
        } else {
            if (aboveY >= viewTop) aboveY else belowY
        }
        return IntOffset(x, y.coerceIn(viewTop, maxY))
    }
}







