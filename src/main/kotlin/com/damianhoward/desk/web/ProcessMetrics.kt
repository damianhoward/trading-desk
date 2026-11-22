package com.damianhoward.desk.web

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.RuntimeMXBean
import java.lang.management.ThreadMXBean

/**
 * What this service can usefully publish at scrape frequency, which is not its readiness.
 *
 * `/readyz` GETs every upstream's `/healthz`. That is the right readiness signal — the desk is a
 * shell whose tabs are other services, so reaching them is the only thing worth proving — and it is
 * the wrong thing to run per scrape. A collector at fifteen seconds would multiply the estate's
 * health traffic by the number of tabs and make this service a load source on the ones it fronts.
 *
 * Nor does it publish a cached copy of the last verdict. A remembered boolean is a number that can
 * disagree with `/readyz`, which is the exact failure document 17's one-snapshot rule exists to
 * prevent, and the detector for that verdict already exists: UptimeRobot probes `/readyz` on its
 * own interval and alerts on the absence of `"ready":true`. Metrics here are for trend and
 * post-incident correlation.
 *
 * Heap against its ceiling is the load-bearing one. Both boxes are 1 GB with four JVMs, and the
 * ceilings were set by guess: this service was measured once, by hand, at 10 MB live against a
 * 96 MB ceiling.
 *
 * [heapPeak] and [heapPostGc] are what replace that hand measurement. The collection being stood up
 * is off-box on a five-minute interval, and a heap sawtooths between collections, so a sampled
 * maximum finds troughs as often as crests. The JVM keeps the high-water mark itself, so the peak
 * reads correctly at any interval; the live set barely sawtooths, so five minutes suits it. Peak is
 * what this process touched, the live set what it needs.
 *
 * All five services now carry this class, and that is not the breach of a rule it once looked like.
 * This paragraph used to say two copies was the limit and a third service was the trigger to
 * extract a shared module. That rule is gone — document 02 deleted it on 2026-08-13, because a
 * consumer count answers the wrong question. The test is whether a shared copy would prevent a
 * caller getting an invariant wrong, and here it would prevent typing: this class reads MXBeans and
 * formats them, there is no seam anyone will substitute, and each copy's prose tracks its own
 * service's situation rather than drifting from the others.
 */
class ProcessMetrics(
    private val runtime: RuntimeMXBean = ManagementFactory.getRuntimeMXBean(),
    private val memory: MemoryMXBean = ManagementFactory.getMemoryMXBean(),
    private val threads: ThreadMXBean = ManagementFactory.getThreadMXBean(),
    private val collectors: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans(),
    pools: List<MemoryPoolMXBean> = ManagementFactory.getMemoryPoolMXBeans(),
) {
    // Heap pools only. Metaspace and the code cache are memory this process occupies and neither is
    // governed by the heap ceiling, so summing them here would inflate a number whose whole purpose
    // is to be compared against -Xmx.
    private val heapPools = pools.filter { it.type == MemoryType.HEAP }

    // The code cache's pools: one per segment when it is segmented, a single "CodeCache" when not.
    private val codeCachePools =
        pools.filter { it.type == MemoryType.NON_HEAP && (it.name.startsWith("CodeHeap") || it.name == "CodeCache") }

    fun render(): String {
        val out = StringBuilder()

        fun emit(
            name: String,
            help: String,
            type: String,
            samples: List<Pair<String, Any>>,
        ) {
            if (samples.isEmpty()) return
            out
                .append("# HELP ")
                .append(name)
                .append(' ')
                .append(help)
                .append('\n')
            out
                .append("# TYPE ")
                .append(name)
                .append(' ')
                .append(type)
                .append('\n')
            for ((labels, value) in samples) {
                out
                    .append(name)
                    .append(labels)
                    .append(' ')
                    .append(value)
                    .append('\n')
            }
        }

        fun gauge(
            name: String,
            help: String,
            value: Any,
        ) = emit(name, help, "gauge", listOf("" to value))

        gauge(
            "${PREFIX}process_uptime_seconds",
            "Seconds since this process started. A reset is a restart, wanted or not.",
            seconds(runtime.uptime),
        )

        val heap = memory.heapMemoryUsage
        gauge("${PREFIX}jvm_heap_used_bytes", "Heap in use after the last collection this reading saw.", heap.used)
        gauge("${PREFIX}jvm_heap_committed_bytes", "Heap the JVM currently holds from the operating system.", heap.committed)
        // -1 means no ceiling was configured. Publishing it would read as a real limit of minus one
        // byte, and every "used against max" expression built on it would be nonsense.
        if (heap.max >= 0) {
            gauge(
                "${PREFIX}jvm_heap_max_bytes",
                "The configured heap ceiling. Used against this is what the ceilings were guessed at.",
                heap.max,
            )
        }

        // Summed across heap pools, and the sum is an over-estimate: pools reach their own peaks at
        // different moments, so adding those peaks can exceed any total that was ever simultaneously
        // live. That error has a direction, and it is the safe one for sizing a ceiling — it cannot
        // talk a ceiling down below what the process needed. Read it as an upper bound rather than a
        // measurement, which is what the pair with the post-collection gauge below is for.
        //
        // Monotonic since start, so a restart resets it. That is the same reset uptime already
        // reports and the reason it is published beside one.
        heapPeak()?.let {
            gauge(
                "${PREFIX}jvm_heap_peak_bytes",
                "Highest heap the JVM has held since start, summed across heap pools. An upper bound, not a reading.",
                it,
            )
        }

        // Usage after the most recent collection: the live set, which is what a ceiling has to hold.
        // A pool answers null until it has been collected at all, so on a young process this is
        // absent rather than zero — and absent is the honest answer, since nothing has established
        // a live set yet. Zero would read as an empty heap, which is a different claim.
        heapPostGc()?.let {
            gauge(
                "${PREFIX}jvm_heap_post_gc_bytes",
                "Heap still live after the last collection, summed across heap pools. The number a ceiling must cover.",
                it,
            )
        }

        gauge("${PREFIX}jvm_threads", "Live threads, daemon and non-daemon.", threads.threadCount)

        // The code cache, which the deployment caps. The JIT fills it for days after a start before it
        // levels off, and a full one stops compilation with nothing but a log line to say so: the
        // service keeps serving, slower. Used beside the ceiling shows how close that is.
        codeCacheUsed()?.let {
            gauge("${PREFIX}jvm_code_cache_used_bytes", "Compiled code the JIT holds, summed across code cache pools.", it)
        }
        codeCacheMax()?.let {
            gauge("${PREFIX}jvm_code_cache_max_bytes", "The code cache's ceiling, summed across code cache pools.", it)
        }

        // The collector names come from the JVM's own configuration, so the label set is fixed at
        // startup — bounded by configuration rather than by data, which is the rule that keeps a
        // series from being minted per observation.
        emit(
            "${PREFIX}jvm_gc_collections_total",
            "Collections each garbage collector has completed.",
            "counter",
            collectors.map { """{gc=${quote(it.name)}}""" to it.collectionCount },
        )
        emit(
            "${PREFIX}jvm_gc_seconds_total",
            "Seconds each garbage collector has spent collecting.",
            "counter",
            collectors.map { """{gc=${quote(it.name)}}""" to seconds(it.collectionTime) },
        )

        return out.toString()
    }

    // Null rather than a partial sum: a pool whose ceiling is undefined reports -1, and adding it in
    // would publish a ceiling lower than the one that applies.
    private fun codeCacheUsed(): Long? = codeCachePools.ifEmpty { null }?.sumOf { it.usage.used }

    private fun codeCacheMax(): Long? =
        codeCachePools
            .ifEmpty { null }
            ?.map { it.usage.max }
            ?.takeIf { maxes -> maxes.all { it >= 0 } }
            ?.sum()

    // Null rather than zero when there are no heap pools to ask. A JVM always has some, so this is
    // the substituted-bean case in a test rather than anything a live process does — but a summed
    // zero would publish "this heap has never held anything", which is a claim about the process
    // instead of about the absence of an answer.
    private fun heapPeak(): Long? = heapPools.ifEmpty { null }?.sumOf { it.peakUsage.used }

    // Null until something has been collected. getCollectionUsage is null on a pool the collector
    // has not touched, and unsupported on some pools outright, so a partial sum would silently
    // report a live set missing whichever pools stayed quiet.
    private fun heapPostGc(): Long? {
        val usages = heapPools.map { it.collectionUsage }
        return if (usages.isEmpty() || usages.any { it == null }) null else usages.sumOf { it!!.used }
    }

    // Rendered rather than divided into a Double, so a duration never reaches the endpoint in
    // exponential notation — Prometheus accepts it, humans reading a curl do not.
    private fun seconds(millis: Long): String = "%d.%03d".format(millis / 1000, millis % 1000)

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val PREFIX = "trading_desk_"

        /**
         * The Prometheus exposition content type. The version parameter is not decoration: a
         * collector reads it to decide which parser to use.
         */
        const val CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
    }
}
