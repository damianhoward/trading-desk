package com.damianhoward.desk.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage
import java.lang.management.RuntimeMXBean
import java.lang.management.ThreadMXBean
import javax.management.ObjectName

class ProcessMetricsTest {
    private fun metrics(
        uptimeMillis: Long = 90_000,
        heapUsed: Long = 10L * 1024 * 1024,
        heapCommitted: Long = 32L * 1024 * 1024,
        heapMax: Long = 96L * 1024 * 1024,
        threadCount: Int = 21,
        collectors: List<GarbageCollectorMXBean> = listOf(FakeCollector("G1 Young Generation", 12, 340)),
    ) = ProcessMetrics(
        runtime = FakeRuntime(uptimeMillis),
        memory = FakeMemory(MemoryUsage(0, heapUsed, heapCommitted, heapMax)),
        threads = FakeThreads(threadCount),
        collectors = collectors,
    ).render()

    @Test
    fun `heap is published against its ceiling`() {
        // The pair this exists for. One manual jcmd said 10 MB live against a 96 MB ceiling; a
        // series is what turns that into evidence for sizing rather than one reading during an audit.
        val body = metrics()
        assertTrue(body.contains("trading_desk_jvm_heap_used_bytes ${10L * 1024 * 1024}"), body)
        assertTrue(body.contains("trading_desk_jvm_heap_max_bytes ${96L * 1024 * 1024}"), body)
    }

    @Test
    fun `an unset heap ceiling is omitted rather than published as minus one`() {
        // getMax returns -1 when no ceiling is configured. Published, it reads as a real limit of
        // minus one byte, and every "used against max" expression built on it is nonsense.
        val body = metrics(heapMax = -1)
        assertFalse(body.contains("trading_desk_jvm_heap_max_bytes"), body)
        assertTrue(body.contains("trading_desk_jvm_heap_used_bytes"), body)
    }

    @Test
    fun `uptime and durations are published in seconds`() {
        val body = metrics(uptimeMillis = 90_500)
        assertTrue(body.contains("trading_desk_process_uptime_seconds 90.500"), body)
        assertTrue(body.contains("""trading_desk_jvm_gc_seconds_total{gc="G1 Young Generation"} 0.340"""), body)
    }

    @Test
    fun `each collector is labelled by name`() {
        val body =
            metrics(
                collectors =
                    listOf(
                        FakeCollector("G1 Young Generation", 12, 340),
                        FakeCollector("G1 Old Generation", 1, 90),
                    ),
            )
        assertTrue(body.contains("""trading_desk_jvm_gc_collections_total{gc="G1 Young Generation"} 12"""), body)
        assertTrue(body.contains("""trading_desk_jvm_gc_collections_total{gc="G1 Old Generation"} 1"""), body)
    }

    @Test
    fun `no upstream is probed`() {
        // The load-bearing property. /readyz GETs every upstream's /healthz, which is right per
        // probe and wrong per scrape: a collector at fifteen seconds would make this service a load
        // source on the ones it fronts. This class is given no gateway and no client, and that is
        // the guarantee rather than a convention.
        val body = metrics()
        assertFalse(body.contains("ready"), body)
        assertFalse(body.contains("upstream"), body)
    }

    @Test
    fun `every published series carries its HELP and TYPE`() {
        // A series without a TYPE is parsed as untyped, which silently costs rate() and increase().
        val body = metrics()
        val names =
            body
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.substringBefore(' ').substringBefore('{') }
                .distinct()
                .toList()
        assertTrue(names.isNotEmpty(), body)
        for (name in names) {
            assertTrue(body.contains("# HELP $name "), "$name has no HELP\n$body")
            assertTrue(body.contains("# TYPE $name "), "$name has no TYPE\n$body")
        }
    }

    @Test
    fun `counters carry the suffix every dashboard assumes`() {
        val body = metrics()
        for (line in body.lineSequence().filter { it.startsWith("# TYPE ") }) {
            val (name, type) = line.removePrefix("# TYPE ").split(' ')
            if (type == "counter") assertTrue(name.endsWith("_total"), "$name is a counter without _total")
        }
    }

    @Test
    fun `the real beans render without throwing`() {
        // The fakes prove the shape; this proves the wiring, since a JVM bean returning something
        // unexpected would otherwise only surface on the box.
        val body = ProcessMetrics().render()
        assertTrue(body.contains("trading_desk_jvm_threads"), body)
        assertEquals("text/plain; version=0.0.4; charset=utf-8", ProcessMetrics.CONTENT_TYPE)
    }

    private class FakeRuntime(
        private val uptimeMillis: Long,
    ) : RuntimeMXBean by ManagementFactory.getRuntimeMXBean() {
        override fun getUptime(): Long = uptimeMillis
    }

    private class FakeMemory(
        private val heap: MemoryUsage,
    ) : MemoryMXBean by ManagementFactory.getMemoryMXBean() {
        override fun getHeapMemoryUsage(): MemoryUsage = heap
    }

    private class FakeThreads(
        private val count: Int,
    ) : ThreadMXBean by ManagementFactory.getThreadMXBean() {
        override fun getThreadCount(): Int = count
    }

    private class FakeCollector(
        private val collectorName: String,
        private val count: Long,
        private val timeMillis: Long,
    ) : GarbageCollectorMXBean {
        override fun getCollectionCount(): Long = count

        override fun getCollectionTime(): Long = timeMillis

        override fun getName(): String = collectorName

        override fun getMemoryPoolNames(): Array<String> = emptyArray()

        override fun isValid(): Boolean = true

        override fun getObjectName(): ObjectName = ObjectName("test:type=GarbageCollector,name=$collectorName")
    }

    /**
     * The two heap gauges answer "what ceiling does this service need" without a fast series, so
     * what is worth asserting is the shape of the answer when the JVM cannot give one. Absent, not
     * zero: zero is a claim about the heap, absence is a claim about the measurement.
     */
    private class Pool(
        private val poolType: MemoryType,
        private val peak: Long,
        private val collection: MemoryUsage?,
        private val name: String = "stub",
        private val used: Long = 1,
        private val max: Long = 1,
    ) : MemoryPoolMXBean {
        override fun getType() = poolType

        override fun getPeakUsage() = MemoryUsage(0, peak, peak, peak)

        override fun getCollectionUsage() = collection

        override fun getName() = name

        override fun getUsage() = MemoryUsage(0, used, used, max)

        override fun isValid() = true

        override fun getMemoryManagerNames() = arrayOf("stub")

        override fun getUsageThreshold() = 0L

        override fun setUsageThreshold(threshold: Long) = Unit

        override fun isUsageThresholdExceeded() = false

        override fun getUsageThresholdCount() = 0L

        override fun isUsageThresholdSupported() = false

        override fun getCollectionUsageThreshold() = 0L

        override fun setCollectionUsageThreshold(threshold: Long) = Unit

        override fun isCollectionUsageThresholdExceeded() = false

        override fun getCollectionUsageThresholdCount() = 0L

        override fun isCollectionUsageThresholdSupported() = false

        override fun resetPeakUsage() = Unit

        override fun getObjectName(): ObjectName = ObjectName("java.lang:type=MemoryPool,name=stub")
    }

    private fun metricsWith(pools: List<MemoryPoolMXBean>) =
        ProcessMetrics(
            runtime = ManagementFactory.getRuntimeMXBean(),
            memory = ManagementFactory.getMemoryMXBean(),
            threads = ManagementFactory.getThreadMXBean(),
            collectors = ManagementFactory.getGarbageCollectorMXBeans(),
            pools = pools,
        ).render()

    private fun poolUsage(used: Long) = MemoryUsage(0, used, used, used)

    @Test
    fun `peak and post-collection are summed across heap pools`() {
        val body =
            metricsWith(
                listOf(
                    Pool(MemoryType.HEAP, peak = 100, collection = poolUsage(30)),
                    Pool(MemoryType.HEAP, peak = 50, collection = poolUsage(20)),
                ),
            )
        assertTrue(body.contains("trading_desk_jvm_heap_peak_bytes 150"), body)
        assertTrue(body.contains("trading_desk_jvm_heap_post_gc_bytes 50"), body)
    }

    @Test
    fun `non-heap pools are excluded from both`() {
        // Metaspace is not governed by the heap ceiling, so counting it would inflate a number
        // whose only purpose is comparison against -Xmx.
        val body =
            metricsWith(
                listOf(
                    Pool(MemoryType.HEAP, peak = 100, collection = poolUsage(30)),
                    Pool(MemoryType.NON_HEAP, peak = 900, collection = poolUsage(800)),
                ),
            )
        assertTrue(body.contains("trading_desk_jvm_heap_peak_bytes 100"), body)
        assertTrue(body.contains("trading_desk_jvm_heap_post_gc_bytes 30"), body)
    }

    @Test
    fun `a heap not yet collected publishes no live set at all`() {
        val body = metricsWith(listOf(Pool(MemoryType.HEAP, peak = 100, collection = null)))
        assertTrue(body.contains("trading_desk_jvm_heap_peak_bytes 100"), body)
        // Zero would read as an empty heap; the fact is that nothing has established a live set.
        assertFalse(body.contains("trading_desk_jvm_heap_post_gc_bytes"), body)
    }

    @Test
    fun `one silent pool withdraws the live set rather than under-reporting it`() {
        // A partial sum reads as a smaller live set, which is the direction that talks a ceiling
        // down - the error this pair of gauges exists to prevent.
        val body =
            metricsWith(
                listOf(
                    Pool(MemoryType.HEAP, peak = 100, collection = poolUsage(30)),
                    Pool(MemoryType.HEAP, peak = 50, collection = null),
                ),
            )
        assertTrue(body.contains("trading_desk_jvm_heap_peak_bytes 150"), body)
        assertFalse(body.contains("trading_desk_jvm_heap_post_gc_bytes"), body)
    }

    @Test
    fun `no heap pools publishes neither gauge`() {
        val body = metricsWith(listOf(Pool(MemoryType.NON_HEAP, peak = 900, collection = poolUsage(800))))
        assertFalse(body.contains("trading_desk_jvm_heap_peak_bytes"), body)
        assertFalse(body.contains("trading_desk_jvm_heap_post_gc_bytes"), body)
    }

    @Test
    fun `the real JVM answers both, and the peak is not below the live set`() {
        // Against the actual MXBeans rather than fakes, so the gauges are known to work on the JVM
        // this service runs on and not only against the shapes this test imagines.
        System.gc()
        val body = ProcessMetrics().render()
        val peak = seriesValue(body, "trading_desk_jvm_heap_peak_bytes")
        val live = seriesValue(body, "trading_desk_jvm_heap_post_gc_bytes")
        assertTrue(peak != null && peak > 0, body)
        assertTrue(live != null && live > 0, body)
        assertTrue(peak!! >= live!!, "peak $peak should not be below live set $live")
    }

    @Test
    fun `no series is declared twice`() {
        // This pair is the first chance to publish the same name from two places, and a duplicate
        // is what a collector reads as a series disagreeing with itself.
        val names =
            ProcessMetrics()
                .render()
                .lineSequence()
                .filter { it.startsWith("# TYPE ") }
                .map { it.split(' ')[2] }
                .toList()
        assertEquals(names.size, names.toSet().size, names.toString())
    }

    private fun seriesValue(
        body: String,
        name: String,
    ): Long? =
        body
            .lineSequence()
            .firstOrNull { it.startsWith("$name ") }
            ?.substringAfter(' ')
            ?.trim()
            ?.toLong()

    @Test
    fun `the code cache is summed across its segments, beside its ceiling`() {
        // Segmented, as every unit runs it: profiled and non-profiled nmethods plus the non-nmethod
        // heap. Metaspace is non-heap too and must not be counted.
        val metrics =
            metricsWith(
                listOf(
                    Pool(MemoryType.HEAP, peak = 100, collection = poolUsage(30)),
                    Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "CodeHeap 'profiled nmethods'", used = 7, max = 60),
                    Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "CodeHeap 'non-profiled nmethods'", used = 5, max = 60),
                    Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "CodeHeap 'non-nmethods'", used = 1, max = 8),
                    Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "Metaspace", used = 900, max = -1),
                ),
            )
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_used_bytes 13"), metrics)
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_max_bytes 128"), metrics)
    }

    @Test
    fun `an unsegmented code cache is read from its single pool`() {
        val metrics =
            metricsWith(listOf(Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "CodeCache", used = 9, max = 128)))
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_used_bytes 9"), metrics)
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_max_bytes 128"), metrics)
    }

    @Test
    fun `a code cache ceiling that is undefined is absent, not under-counted`() {
        val metrics =
            metricsWith(
                listOf(
                    Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "CodeHeap 'profiled nmethods'", used = 7, max = 60),
                    Pool(MemoryType.NON_HEAP, peak = 0, collection = null, name = "CodeHeap 'non-nmethods'", used = 1, max = -1),
                ),
            )
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_used_bytes 8"), metrics)
        assertFalse(metrics.contains("trading_desk_jvm_code_cache_max_bytes"), metrics)
    }

    @Test
    fun `a running JVM reports its code cache`() {
        val metrics = ProcessMetrics().render()
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_used_bytes "), metrics)
        assertTrue(metrics.contains("trading_desk_jvm_code_cache_max_bytes "), metrics)
    }
}
