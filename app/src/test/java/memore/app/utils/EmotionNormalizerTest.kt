package memore.app.utils

import memore.app.data.model.AliasGroup
import memore.app.data.repository.AliasMappingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionNormalizerTest {

    @Test
    fun cleanFormatStripsWhitespaceAndPunctuation() {
        assertEquals("喜悦", EmotionNormalizer.cleanFormat("  喜悦。 "))
        assertEquals("平静", EmotionNormalizer.cleanFormat("平静，"))
    }

    @Test
    fun normalizeMapsAliasToStandardAndKeepsUnknown() {
        EmotionNormalizer.rebuildCache(
            listOf(
                AliasGroup(standardName = "喜悦", aliases = listOf("开心", "高兴"), kind = "emotion")
            )
        )
        assertEquals("喜悦", EmotionNormalizer.normalize("开心"))
        assertEquals("喜悦", EmotionNormalizer.normalize(" 高兴。 "))
        assertEquals("复杂情感", EmotionNormalizer.normalize("复杂情感"))
    }

    @Test
    fun defaultEmotionMappingsFollowDecidedRules() {
        val groups = AliasMappingRepository.DEFAULT_EMOTION_GROUPS
        assertTrue(groups.all { it.kind == "emotion" })
        assertEquals(12, groups.size)
        val anger = groups.first { it.standardName == "愤怒" }
        assertTrue("烦躁" in anger.aliases)
        assertTrue(groups.none { it.standardName == "幸福" })
    }
}
