package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.ReconstructionBench
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Exact IEEE float bits and insertion order recorded and verified on origin/main. */
class ScanDenseDeterminismTest {
    @Test
    fun `the same noisy room retains baseline positions colours and normals`() {
        assertEquals(BASELINE, fingerprints())
    }

    companion object {
        private val BASELINE = listOf(
            "600000/12/all 6a1c956feb4f99401f0b4eb56af1549e10b1e97b988b2725a3a2e7912a40c7e8",
            "600000/12/saved 58ccbe28574dafaac9e5c66dc243524870f5788241ebbc119e5d35cac54ced75",
            "600000/12/live 56b707d4c31270b87749f0acef8de00ae374103528fa6786c4c5465d9e394a78",
            "600000/24/all 8d72e60804c8adc0f41dfe0760841c2a337a7f02f8c81c8a273bd8a202c55753",
            "600000/24/saved e636ce1edeb883896ca76c6dac2b4bdad3838cb561c4a669c11267a81d685a17",
            "600000/24/live 344c54f99a6d40542559035d549cdaa4fd45e4992f827f4ac455d00e1b1a3f56",
            "600000/36/all 035037fb36a0beae4f0b9b07f2ec320e6a75b9b59d71525cda2ac9709a79a81c",
            "600000/36/saved 76888faa93a71a5044eff2ed879d82f36ff37e6ca4689e398f529e16ad09d1c4",
            "600000/36/live 9aa4053ba17ee7dbc6e2073419ca2dc8e15b0221400560447f3b08d710ecc5ef",
            "2000/12/all 3daa3e5e95d15454f82484c8c121ad37f70f5136495e2b0b1ddb177bc9fceae3",
            "2000/12/saved c3e4a4a226a335940408c7e086b9dc3a428c3be3bf171238c6e1f131a79090bd",
            "2000/12/live 6b34951063bd33305241bf3f9b4f2b0ffc37338482a15d4d655f5ec8448d0bb9",
            "2000/24/all 63d8dec5dd369e89b8bda03b63c5a33786e7464d0f1ffe591bcdf1123c998a10",
            "2000/24/saved 456ffe4fae170d9adb91bb98e39c1c099894d41f2d09521b860b2066f281e090",
            "2000/24/live f159280659cdce523492359514c2affde7603d819084fb412a9791659dfd166f",
            "2000/36/all 63d8dec5dd369e89b8bda03b63c5a33786e7464d0f1ffe591bcdf1123c998a10",
            "2000/36/saved 456ffe4fae170d9adb91bb98e39c1c099894d41f2d09521b860b2066f281e090",
            "2000/36/live f5841b1cece156f9cef879e9a2e769cb3498085a9e9b75891125246dadecfdfd",
        )

        @JvmStatic
        fun main(args: Array<String>) { fingerprints().forEach(::println) }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun fingerprints(): List<String> = buildList {
            runTest {
                for (budget in listOf(600_000, 2_000)) {
                    val map = ScanDenseMap(liveDispatcher = StandardTestDispatcher(testScheduler),
                        maxPoints = budget, clock = { testScheduler.currentTime * 1_000_000L })
                    ReconstructionBench.QUICK.frames().forEachIndexed { i, frame ->
                        advanceTimeBy(100)
                        map.add(map.project(frame, testScheduler.currentTime * 1_000_000L))
                        runCurrent()
                        if ((i + 1) % 12 == 0) {
                            add("$budget/${i + 1}/all ${digest(map.fusion.cloud())}")
                            add("$budget/${i + 1}/saved ${digest(map.savedCloud())}")
                            add("$budget/${i + 1}/live ${digest(checkNotNull(map.live).cloud)}")
                        }
                    }
                    if (budget == 2_000) assertTrue(map.fusion.compactions > 0)
                    map.discardPendingLive()
                }
            }
        }

        internal fun digest(cloud: DenseCloud): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun word(value: Int) {
                repeat(4) { digest.update((value ushr (it * 8)).toByte()) }
            }
            word(cloud.count)
            cloud.positions.forEach { word(it.toRawBits()) }
            cloud.colors.forEach(::word)
            cloud.normals?.forEach { word(it.toRawBits()) }
            cloud.confidences?.let { digest.update(it) }
            cloud.levels?.let { digest.update(it) }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
