package io.github.tieo.taghistory.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Walkthrough-style tests: every `@Test` tells a story about how the
 * user moves through the app. If navigation breaks in a future refactor
 * these are the canary.
 */
class NavStateTest {

    @Test
    fun root_has_depth_one_and_cannot_go_back() {
        val nav = NavState.rooted(Screen.Map)
        assertEquals(1, nav.depth)
        assertFalse(nav.canGoBack)
        assertEquals(Screen.Map, nav.current)
    }

    @Test
    fun push_increases_depth() {
        val nav = NavState.rooted(Screen.Map).push(Screen.Settings)
        assertEquals(2, nav.depth)
        assertEquals(Screen.Settings, nav.current)
        assertTrue(nav.canGoBack)
    }

    @Test
    fun pop_at_root_is_noop() {
        val root = NavState.rooted(Screen.Map)
        // .pop() returns the same logical state (no-op) and current screen stays.
        assertEquals(root, root.pop())
        assertEquals(Screen.Map, root.pop().current)
        assertFalse(root.pop().canGoBack)
    }

    @Test
    fun walkthrough_map_to_device_info_and_back() {
        var nav = NavState.rooted(Screen.Map)
        nav = nav.push(Screen.DeviceInfo("b1"))
        assertEquals(Screen.DeviceInfo("b1"), nav.current)
        nav = nav.pop()
        assertEquals(Screen.Map, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun walkthrough_map_to_device_info_to_history_back_twice_returns_to_map() {
        var nav = NavState.rooted(Screen.Map)
        nav = nav.push(Screen.DeviceInfo("beacon-1"))
        nav = nav.push(Screen.History("beacon-1", "Keys"))
        assertEquals(3, nav.depth)
        assertEquals(Screen.History("beacon-1", "Keys"), nav.current)

        nav = nav.pop()
        assertEquals(Screen.DeviceInfo("beacon-1"), nav.current)

        nav = nav.pop()
        assertEquals(Screen.Map, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun walkthrough_settings_signout_replaces_root() {
        var nav = NavState.rooted(Screen.Map).push(Screen.Settings)
        assertTrue(nav.canGoBack)

        // On sign-out the host blows the stack away — simulated by replaceRoot.
        nav = nav.replaceRoot(Screen.Map)
        assertEquals(1, nav.depth)
        assertEquals(Screen.Map, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun pop_to_root_from_deep_stack_returns_to_map() {
        var nav = NavState.rooted(Screen.Map)
            .push(Screen.Settings)
            .push(Screen.DeviceInfo("b1"))
            .push(Screen.History("b1", "Keys"))
        assertEquals(4, nav.depth)

        nav = nav.popToRoot()
        assertEquals(1, nav.depth)
        assertEquals(Screen.Map, nav.current)
    }

    @Test
    fun pop_preserves_beacon_id_for_nested_device_info() {
        var nav = NavState.rooted(Screen.Map)
            .push(Screen.DeviceInfo("beacon-xyz"))
            .push(Screen.History("beacon-xyz", "Keys"))
        nav = nav.pop()
        // After popping history we land back on the DeviceInfo for the same beacon.
        assertEquals(Screen.DeviceInfo("beacon-xyz"), nav.current)
    }

    @Test
    fun push_never_mutates_original_state() {
        val a = NavState.rooted(Screen.Map)
        val b = a.push(Screen.Settings)
        // Immutability: `a` is unchanged.
        assertEquals(1, a.depth)
        assertEquals(2, b.depth)
        assertSame(a, a) // trivial, but asserts identity of the original instance
    }

    @Test
    fun rejects_empty_stack_construction() {
        try {
            NavState(emptyList())
            kotlin.test.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun walkthrough_map_settings_back_then_device_info() {
        // Simulates: user opens Settings, backs out, then taps a tag to
        // open DeviceInfo. Regression guard for double-push on re-entry.
        var nav = NavState.rooted(Screen.Map)
        nav = nav.push(Screen.Settings)
        nav = nav.pop()
        nav = nav.push(Screen.DeviceInfo("b1"))
        assertEquals(Screen.DeviceInfo("b1"), nav.current)
        assertEquals(2, nav.depth)
    }
}
