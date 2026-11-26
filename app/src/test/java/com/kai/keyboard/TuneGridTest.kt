package com.kai.keyboard

import org.junit.Test

/** Перебор силы зон. Запуск вручную: ./gradlew testDebugUnitTest --tests '*TuneGridTest*' -Dgrid=1 */
class TuneGridTest {
    @Test
    fun grid() {
        if (System.getProperty("grid") == null && System.getenv("GRID") == null) return
        val ru = TypingSim.loadRu()
        val all = TypingSim.loadSim()
        val eval = all.subList(3000, 3600)
        for (lam in listOf(0.6f, 0.8f, 1.0f, 1.2f)) for (ex in listOf(0.7f, 0.9f)) for (sx in listOf(0.32f, 0.38f)) {
            TouchModel.LAMBDA = lam; TouchModel.MAX_EXPAND_X = ex; TouchModel.MAX_EXPAND_Y = ex * 0.7f
            TouchModel.SIGMA_X = sx; TouchModel.SIGMA_Y = sx * 0.93f
            val e = TypingSim.freshEngine(ru); val st = TypingSim.Stats()
            TypingSim(e, 1).type(eval, true, false, 0.0, { !e.known(Langs.RU, it) }, st)
            println(st.line("GRID λ=$lam exp=$ex σ=$sx"))
        }
    }
}
