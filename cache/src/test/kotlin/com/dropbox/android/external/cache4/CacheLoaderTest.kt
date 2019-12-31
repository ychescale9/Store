package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CacheLoaderTest {

    private val clock = TestClock(virtualTimeNanos = 0)
    private val expiryDuration = TimeUnit.MINUTES.toNanos(1)

    @Test
    fun `get(key, loader) returns value from loader when no entry with the associated key exists before and after executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        val loader = createLoader("dog")

        val value = cache.get(1, loader)

        assertThat(loader.invokeCount)
            .isEqualTo(1)

        assertThat(value)
            .isEqualTo("dog")
    }

    @Test
    fun `get(key, loader) returns existing value when an entry with the associated key exists before executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")

        val loader = createLoader("cat")

        val value = cache.get(1, loader)

        assertThat(loader.invokeCount)
            .isEqualTo(0)

        assertThat(value)
            .isEqualTo("dog")
    }

    @Test
    fun `get(key, loader) returns existing value when an entry with the associated key is absent initially but present after executing the loader`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .build<Long, String>()

            val executionTime = 50L

            val loader = createSlowLoader("dog", executionTime)

            var value: String? = null

            launch(Dispatchers.Default) {
                value = cache.get(1, loader)
            }

            launch(Dispatchers.Default) {
                delay(1)
                cache.put(1, "cat")
            }

            delay(executionTime + 10)

            assertThat(loader.invokeCount)
                .isEqualTo(1)

            // entry from loader should not be cached as an entry already exists
            // by the time loader returns
            assertThat(value)
                .isEqualTo("cat")

            assertThat(cache.get(1))
                .isEqualTo("cat")
        }

    @Test
    fun `value returned by loader is cached when value associated with the key is still absent after executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        val loader = createLoader("dog")

        cache.get(1, loader)

        assertThat(loader.invokeCount)
            .isEqualTo(1)

        assertThat(cache.get(1))
            .isEqualTo("dog")
    }

    @Test
    fun `value returned by loader is not cached when an unexpired value associated with the key exists`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")

        val loader = createLoader("cat")

        cache.get(1, loader)

        assertThat(loader.invokeCount)
            .isEqualTo(0)

        assertThat(cache.get(1))
            .isEqualTo("dog")
    }

    @Test
    fun `calling get(key, loader) executes the loader and caches the value returned when an expired value associated with the key exists`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .expireAfterWrite(expiryDuration, TimeUnit.NANOSECONDS)
                .clock(clock)
                .build<Long, String>()

            val executionTime = 50L

            val loader = createSlowLoader("dog", executionTime)

            var value: String? = null

            launch(Dispatchers.Default) {
                value = cache.get(1, loader)
            }

            launch(Dispatchers.Default) {
                delay(1)
                cache.put(1, "cat")

                // now expires
                clock.virtualTimeNanos = expiryDuration
            }

            delay(executionTime + 10)

            assertThat(loader.invokeCount)
                .isEqualTo(1)

            // entry from loader should be cached as the existing one has expired.
            assertThat(value)
                .isEqualTo("dog")

            assertThat(cache.get(1))
                .isEqualTo("dog")
        }

    @Test
    fun `calling get(key, loader) executes the loader and caches the value returned when an existing value was invalidated`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .build<Long, String>()

            val executionTime = 50L

            val loader = createSlowLoader("dog", executionTime)

            var value: String? = null

            launch(Dispatchers.Default) {
                value = cache.get(1, loader)
            }

            launch(Dispatchers.Default) {
                delay(1)
                cache.put(1, "cat")

                // invalidate the entry
                cache.invalidate(1)
            }

            delay(executionTime + 10)

            assertThat(loader.invokeCount)
                .isEqualTo(1)

            // entry from loader should be cached as previous one had been invalidated.
            assertThat(value)
                .isEqualTo("dog")

            assertThat(cache.get(1))
                .isEqualTo("dog")
        }

    @Test
    fun `only 1 loader is executed by multiple concurrent get(key, loader) calls`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .build<Long, String>()

            val executionTime = 20L

            val loader = createSlowLoader("cat", executionTime)

            repeat(3) {
                launch(Dispatchers.Default) {
                    cache.get(1, loader)
                }
            }

            delay(executionTime * 3 + 10)

            assertThat(loader.invokeCount)
                .isEqualTo(1)

            assertThat(cache.get(1))
                .isEqualTo("cat")
        }

    @Test
    fun `a loader exception is propagated to the get(key, loader) call`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        val loader = createFailingLoader<String>(IOException())

        assertThrows(IOException::class.java) {
            cache.get(1, loader)
        }

        assertThat(loader.invokeCount)
            .isEqualTo(1)

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `a blocked concurrent get(key, loader) call is unblocked and executes its own loader after the loader from an earlier concurrent call throws an exception`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .build<Long, String>()

            val executionTime = 50L

            val loader1 = createSlowFailingLoader<String>(IOException(), executionTime)
            val loader2 = createLoader("cat")

            var value: String? = null

            launch(Dispatchers.Default) {
                runCatching {
                    cache.get(1, loader1)
                }
            }

            launch(Dispatchers.Default) {
                delay(1)
                value = cache.get(1, loader2)
            }

            delay(executionTime * 2)

            assertThat(loader1.invokeCount)
                .isEqualTo(1)
            assertThat(loader2.invokeCount)
                .isEqualTo(1)

            assertThat(value)
                .isEqualTo("cat")

            assertThat(cache.get(1))
                .isEqualTo("cat")
        }
}

private class TestLoader<Value>(private val loader: () -> Value) : () -> Value {
    var invokeCount = 0
    override operator fun invoke(): Value {
        invokeCount++
        return loader()
    }
}

private fun <Value> createLoader(computedValue: Value) =
    TestLoader { computedValue }

private fun <Value> createSlowLoader(
    computedValue: Value,
    executionTime: Long
) = TestLoader {
    runBlocking { delay(executionTime) }
    computedValue
}

private fun <Value> createFailingLoader(exception: Exception) =
    TestLoader { throw exception }

@Suppress("SameParameterValue")
private fun <Value> createSlowFailingLoader(exception: Exception, executionTime: Long) =
    TestLoader {
        runBlocking { delay(executionTime) }
        throw exception
    }
