package eu.anifantakis.commercials.grids

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * TEMPORARY perf harness for the scheduler grid. Asserts nothing - it reports.
 *
 * Three numbers, paid at three different moments, and only one of them is what
 * makes the app feel slow:
 *
 *   OPEN   - first composition + layout + draw of a month, measured on a FRESH
 *            scene AFTER the JVM is warm. Measuring the very first render of a cold
 *            process would be measuring the JIT, not the grid.
 *   IDLE   - a repaint with nothing changed. The FLOOR: what a frame costs while the
 *            grid merely sits on screen. No optimisation removes it, so every other
 *            number is quoted against it.
 *   SCROLL - a frame driven by a real mouse wheel event, so rows genuinely leave and
 *            enter and their cells compose and lay out from scratch. This is the hot
 *            path: it runs 60 times a second while the operator drags the month.
 */
class GridPerfHarness {

    private companion object {
        const val WARMUP = 60
        const val MEASURED = 120
        const val WIDTH = 1600
        const val HEIGHT = 900
        const val FRAME = 16_000_000L
    }

    private fun breaks(): ImmutableList<BreakSlot> {
        val scaffold = (7..23).flatMap { h -> listOf(LocalTime(h, 0), LocalTime(h, 30)) }
        val real = listOf(LocalTime(0, 5), LocalTime(3, 0), LocalTime(20, 45), LocalTime(21, 15))
        return (scaffold + real).sorted().map { t ->
            BreakSlot(
                time = t,
                label = "%02d:%02d".format(t.hour, t.minute),
                zoneColor = if (t.hour in 20..23) Color(0xFFFDE7E9) else Color(0xFFE8F0FE),
                zone = if (t.hour in 20..23) BreakZone.PRIME else BreakZone.DEFAULT,
            )
        }.toPersistentList()
    }

    private fun cells(rows: List<BreakSlot>): ImmutableMap<SchedulerKey, SchedulerCellData> {
        val map = mutableMapOf<SchedulerKey, SchedulerCellData>()
        var n = 0
        rows.forEach { slot ->
            (1..31).forEach { day ->
                n++
                if (n % 5 != 0 && n % 7 != 0) {
                    map[SchedulerKey(slot.time, LocalDate(2025, 12, day))] = SchedulerCellData(
                        spotCount = (n % 12) + 1,
                        totalDurationSeconds = 30 * ((n % 12) + 1),
                        zoneColor = slot.zoneColor,
                        programName = if (n % 3 == 0) "Evening News" else null,
                    )
                }
            }
        }
        return map.toPersistentMap()
    }

    private fun allocated(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean)
            .getThreadAllocatedBytes(Thread.currentThread().id)

    private fun report(label: String, times: LongArray, allocs: LongArray, floorUs: Long?, floorKb: Long?) {
        times.sort(); allocs.sort()
        val us = times[times.size / 2] / 1000
        val kb = allocs[allocs.size / 2] / 1024
        val over = if (floorUs != null && floorKb != null) {
            "   (over the floor: ${us - floorUs} us | ${kb - floorKb} KB)"
        } else ""
        println("$label: $us us | $kb KB$over")
    }

    @Test
    fun gridFrameCost() {
        val rows = breaks()
        val cellData = cells(rows)
        println("GRID: ${rows.size} rows x 31 days = ${rows.size * 31} cells, ${cellData.size} with a Text")

        val content: @Composable () -> Unit = {
            LazySchedulerGrid(
                breaks = rows,
                cellData = cellData,
                modifiedCells = persistentSetOf(),
                year = 2025,
                month = 12,
            )
        }

        // Warm the JIT on a throwaway scene, so OPEN below measures the grid and not
        // the class loader.
        ImageComposeScene(WIDTH, HEIGHT, Density(1f), content = content).let { warm ->
            var f = 0L
            repeat(WARMUP) { f += FRAME; warm.render(f) }
            warm.close()
        }

        // OPEN: a fresh scene, JIT already hot. This is "the operator switched month".
        val openTimes = LongArray(10)
        val openAllocs = LongArray(10)
        repeat(10) { i ->
            val s = ImageComposeScene(WIDTH, HEIGHT, Density(1f), content = content)
            val a = allocated(); val t = System.nanoTime()
            s.render(0L)
            openTimes[i] = System.nanoTime() - t
            openAllocs[i] = allocated() - a
            s.close()
        }

        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f), content = content)
        try {
            var frame = 0L
            repeat(WARMUP) { frame += FRAME; scene.render(frame) }

            val it1 = LongArray(MEASURED); val ia1 = LongArray(MEASURED)
            repeat(MEASURED) { i ->
                frame += FRAME
                val a = allocated(); val t = System.nanoTime()
                scene.render(frame)
                it1[i] = System.nanoTime() - t; ia1[i] = allocated() - a
            }
            val floorUs = it1.clone().also { it.sort() }[MEASURED / 2] / 1000
            val floorKb = ia1.clone().also { it.sort() }[MEASURED / 2] / 1024

            // SCROLL: a real wheel event over the middle of the grid, one per frame.
            val centre = Offset(WIDTH / 2f, HEIGHT / 2f)
            val st = LongArray(MEASURED); val sa = LongArray(MEASURED)
            repeat(MEASURED) { i ->
                frame += FRAME
                // Alternate direction so the list never parks against an edge and
                // starts measuring "nothing happens" instead of a scroll.
                val dy = if ((i / 20) % 2 == 0) 1f else -1f
                scene.sendPointerEvent(
                    eventType = PointerEventType.Scroll,
                    position = centre,
                    scrollDelta = Offset(0f, dy),
                )
                val a = allocated(); val t = System.nanoTime()
                scene.render(frame)
                st[i] = System.nanoTime() - t; sa[i] = allocated() - a
            }

            // PIXEL PROOF, from a FRESH scene that has never been scrolled.
            //
            // Taking it from the scrolled scene above was a trap I walked into: the
            // fling that a wheel event starts settles on a scroll offset that depends
            // on frame timing, so two variants park a pixel or two apart and the diff
            // lights up the whole grid for reasons that have nothing to do with what
            // changed. An unscrolled scene is the only deterministic picture.
            val proof = ImageComposeScene(WIDTH, HEIGHT, Density(1f), content = content)
            val img = proof.render(0L)
            val bitmap = img.toComposeImageBitmap()
            val px = IntArray(WIDTH * HEIGHT)
            bitmap.readPixels(px, 0, 0, WIDTH, HEIGHT)
            val out = java.io.File(System.getProperty("gridPixelsOut") ?: "/tmp/grid-pixels.bin")
            java.io.DataOutputStream(java.io.BufferedOutputStream(out.outputStream())).use { o ->
                px.forEach { o.writeInt(it) }
            }
            println("PIXELS written       : ${out.path} (${px.size} px)")
            proof.close()

            report("OPEN   (switch month)", openTimes, openAllocs, null, null)
            report("IDLE   (floor)       ", it1, ia1, null, null)
            report("SCROLL (wheel)       ", st, sa, floorUs, floorKb)
        } finally {
            scene.close()
        }
    }
}
