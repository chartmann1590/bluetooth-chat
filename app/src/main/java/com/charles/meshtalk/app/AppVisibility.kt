package com.charles.meshtalk.app

/**
 * Tracks just enough app state to decide whether a new DM should pop a notification:
 * whether any Activity is currently started, and which DM thread (if any) is on screen.
 * Updated by MainActivity's lifecycle callbacks and DmThreadScreen's DisposableEffect.
 */
object AppVisibility {
    @Volatile var isForeground: Boolean = false
    @Volatile var openDmPeerKeyHex: String? = null

    /** True if a DM from this peer should NOT trigger a notification right now. */
    fun isViewingDmThread(peerKeyHex: String): Boolean = isForeground && openDmPeerKeyHex == peerKeyHex
}
