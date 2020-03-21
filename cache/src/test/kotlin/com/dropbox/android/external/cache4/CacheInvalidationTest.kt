package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlin.time.minutes

@ExperimentalTime
class CacheInvalidationTest {

    @Test
    fun `calling invalidate(key) evicts the entry associated with the key`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        cache.invalidate(2)

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isNull()
    }

    @Test
    fun `calling invalidate(key) also evicts all expired entries`() {
        val timeSource = TestTimeSource()
        val oneMinute = 1.minutes

        val cache = Cache.Builder.newBuilder()
            .timeSource(timeSource)
            .expireAfterWrite(oneMinute)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        timeSource += oneMinute / 2

        cache.put(3, "bird")

        // first 2 entries now expire
        timeSource += oneMinute / 2

        cache.invalidate(3)

        // all 3 entries should have been evicted
        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()

        assertThat(cache.get(3))
            .isNull()
    }

    @Test
    fun `calling invalidateAll() evicts all entries in the cache`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(3, "bird")

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isEqualTo("cat")

        assertThat(cache.get(3))
            .isEqualTo("bird")

        cache.invalidateAll()

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()

        assertThat(cache.get(3))
            .isNull()
    }
}
