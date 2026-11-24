package memore.app.utils

import memore.app.data.model.AliasGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryNodeNormalizerTest {

    @Test
    fun normalizeKeepsTrimmedNameWhenNoAliasConfigured() {
        MemoryNodeNormalizer.rebuildCache(emptyList())
        assertEquals("AI", MemoryNodeNormalizer.normalize(" AI "))
    }

    @Test
    fun normalizeMapsAliasToStandardName() {
        MemoryNodeNormalizer.rebuildCache(
            listOf(AliasGroup(standardName = "人工智能", aliases = listOf("AI", "A.I.")))
        )
        assertEquals("人工智能", MemoryNodeNormalizer.normalize("AI"))
        assertEquals("人工智能", MemoryNodeNormalizer.normalize("人工智能"))
    }

    @Test
    fun normalizeFallsBackToTrimmedInput() {
        MemoryNodeNormalizer.rebuildCache(emptyList())
        assertEquals("机器学习", MemoryNodeNormalizer.normalize(" 机器学习 "))
    }
}
