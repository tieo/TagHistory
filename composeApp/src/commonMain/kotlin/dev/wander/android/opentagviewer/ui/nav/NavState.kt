package io.github.tieo.taghistory.ui.nav

/**
 * Immutable nav stack. The root (index 0) cannot be popped off, so
 * `pop()` at depth 1 is a no-op — the host should forward that case to
 * the OS back gesture (which lets the user leave the app from the root).
 */
data class NavState(val stack: List<Screen>) {
    init {
        require(stack.isNotEmpty()) { "NavState must have at least one screen" }
    }

    val current: Screen get() = stack.last()
    val depth: Int get() = stack.size
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen): NavState = copy(stack = stack + screen)

    fun pop(): NavState = if (canGoBack) copy(stack = stack.dropLast(1)) else this

    fun popToRoot(): NavState = copy(stack = listOf(stack.first()))

    fun replaceRoot(screen: Screen): NavState = NavState(listOf(screen))

    companion object {
        fun rooted(screen: Screen): NavState = NavState(listOf(screen))
    }
}
