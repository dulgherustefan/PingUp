package ro.safetyplease.core.map.pmtiles

import kotlinx.coroutines.test.runTest
import okio.use
import ro.safetyplease.core.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Wall time on the JVM only: a simulator runs many times slower, and commonTest checks the work done instead. */
class PmtilesTimingTest {
    private val tmp = TempDir()

    @AfterTest
    fun cleanUp() = tmp.delete()

    @Test
    fun planningTheShippedMapsTilesTakesUnderTwoMilliseconds() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val cold = micros { PmtilesReader.open(file).plan(COSTINESTI) }
            val reader = PmtilesReader.open(file)
            repeat(20) { reader.plan(COSTINESTI) }
            val times = List(31) { micros { reader.plan(COSTINESTI) } }.sorted()
            println("PMTiles plan of the shipped 183 tiles: ${cold}µs opening cold, ${times[15]}µs warm (median of 31)")
            assertTrue(times[15] < 2_000, "$times µs")
        }
    }

    @Test
    fun aHundredThousandTilesPlanInUnderHalfASecondAndAreWrittenInUnderTwo() = runTest {
        val source = tmp.root / "synthetic.pmtiles"
        val build = micros { syntheticArchive(source, SYNTHETIC) }
        FileRangeSource(fs, source).use { file ->
            val cold = micros { PmtilesReader.open(file).plan(SYNTHETIC) }
            val reader = PmtilesReader.open(file)
            val times = List(5) { micros { reader.plan(SYNTHETIC) } }.sorted()
            val plan = reader.plan(SYNTHETIC)
            val metadata = reader.metadata()
            val write = micros { PmtilesWriter(fs).write(tmp.root / "cut.pmtiles", plan, file, metadata) }
            println(
                "PMTiles plan of 100,313 tiles (${plan.entries.size} entries, ${reader.root.size} leaves): ${cold}µs opening cold, " +
                    "${times[2]}µs warm (median of 5); written and checked in ${write}µs, the source in ${build}µs",
            )
            assertTrue(times[2] < 500_000, "$times µs")
            assertTrue(write < 2_000_000, "$write µs")
        }
    }

    private inline fun micros(block: () -> Unit): Long {
        val start = TimeSource.Monotonic.markNow()
        block()
        return start.elapsedNow().inWholeMicroseconds
    }
}
