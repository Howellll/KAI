package com.kai.keyboard

import org.junit.Test
import java.io.File

/** Сколько грузится словарь с парами слов и сколько памяти занимает (на ноутбуке — для порядка). */
class LoadCostTest {
    @Test
    fun loadCost() {
        val rt = Runtime.getRuntime()
        System.gc(); val m0 = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val d = Dictionary(Langs.RU)
        File("src/main/assets/ru.dict").inputStream().use { d.load(it) }
        val t1 = System.nanoTime()
        File("src/main/assets/ru.bi").inputStream().use { d.loadContext(it) }
        val t2 = System.nanoTime()
        System.gc(); val m1 = rt.totalMemory() - rt.freeMemory()
        val out = "словарь %.0f мс, пары %.0f мс, память ~%.0f МБ".format((t1 - t0) / 1e6, (t2 - t1) / 1e6, (m1 - m0) / 1e6)
        File("build").mkdirs(); File("build/load_cost.txt").writeText(out)
        println(out + " " + d.contains("привет"))
    }
}
