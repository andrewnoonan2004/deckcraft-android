package com.andrewnoonan.deckcraft.storage

/** Abstraction over SAF write-back so the workbench ViewModel can be unit tested. */
interface DocumentStorageManager {
    fun syncSavedPathToOrigin(savedPath: String): Boolean
}
