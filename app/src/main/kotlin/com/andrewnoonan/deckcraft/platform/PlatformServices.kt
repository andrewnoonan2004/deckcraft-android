package com.andrewnoonan.deckcraft.platform

interface ClipboardBridge {
    fun setText(text: String)
    fun getText(): String?
}

interface PrintBridge {
    fun printPdfFile(jobName: String, pdfPath: String): Boolean
}

interface PreferencesStore {
    fun loadEnginePrefs(): String?
    fun saveEnginePrefs(json: String)
    fun isDesktopBarExpanded(): Boolean
    fun setDesktopBarExpanded(expanded: Boolean)
}

interface UrlLauncherBridge {
    fun openHttpsUrl(url: String): Boolean
}

interface ShareBridge {
    fun sharePresentationFile(localPath: String, title: String): Boolean
}
