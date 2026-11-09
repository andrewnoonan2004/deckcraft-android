package com.andrewnoonan.deckcraft.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafMimeTypeMapperTest {

    @Test
    fun openMimeTypes_pptxMapsToOfficeMimeTypes() {
        val mimes = SafMimeTypeMapper.toOpenMimeTypes(listOf("pptx"))
        assertTrue(mimes.contains("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
    }

    @Test
    fun openMimeTypes_customDeckcraftExtensionAddsWildcard() {
        val mimes = SafMimeTypeMapper.toOpenMimeTypes(listOf(".DeckCraft"))
        assertTrue(mimes.contains("*/*"))
    }

    @Test
    fun openMimeTypes_emptyExtensionsFallsBackToPresentationsAndWildcard() {
        val mimes = SafMimeTypeMapper.toOpenMimeTypes(emptyList())
        assertTrue(mimes.contains("*/*"))
        assertTrue(mimes.any { it.contains("presentationml") })
    }

    @Test
    fun createMimeType_prefersSuggestedNameExtension() {
        assertEquals("application/pdf", SafMimeTypeMapper.toCreateDocumentMimeType("deck.pdf", listOf("pptx")))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            SafMimeTypeMapper.toCreateDocumentMimeType(null, listOf("pptx")),
        )
        assertEquals("application/octet-stream", SafMimeTypeMapper.toCreateDocumentMimeType("deck.deckcraft", emptyList()))
    }
}
