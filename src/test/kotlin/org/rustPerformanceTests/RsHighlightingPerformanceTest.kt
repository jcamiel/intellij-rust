/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustPerformanceTests

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.util.PsiModificationTracker
import org.apache.commons.lang3.ObjectUtils
import org.rust.lang.core.macros.MacroExpansionScope
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.ext.RsReferenceElement
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.psi.rustPsiManager
import org.rust.lang.core.resolve2.forceBuildDefMapForAllCrates
import org.rust.lang.core.resolve2.isNewResolveEnabled
import org.rust.lang.core.resolve2.timesBuildDefMaps
import org.rust.stdext.Timings
import org.rust.stdext.repeatBenchmark
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

// todo другое имя ?
class RsHighlightingPerformanceTest : RsRealProjectTestBase() {
    // It is a performance test, but we don't want to waste time measuring CPU performance
    override fun isPerformanceTest(): Boolean = false

    private val cargoFilePath: String = "src/cargo/core/resolver/mod.rs"
    fun `test Cargo, measure`() = repeatTest(CARGO, cargoFilePath, ::measureResolveAndHighlight)
    fun `test Cargo, profile build DefMap`() = repeatBuildDefMaps(CARGO)
    fun `test Cargo, profile resolve`() = repeatTest(CARGO, cargoFilePath, ::profileResolve)

    private val mysqlAsyncFilePath1: String = "src/conn/mod.rs"
    private val mysqlAsyncFilePath2: String = "src/connection_like/mod.rs"
    fun `test mysql_async, measure 1`() = repeatTest(MYSQL_ASYNC, mysqlAsyncFilePath1, ::measureResolveAndHighlight)
    fun `test mysql_async, measure 2`() = repeatTest(MYSQL_ASYNC, mysqlAsyncFilePath2, ::measureResolveAndHighlight)
    fun `test mysql_async, profile resolve`() = repeatBuildDefMaps(MYSQL_ASYNC)

    fun `test empty, profile build DefMap`() = repeatBuildDefMaps(EMPTY)
    fun `test rustc, profile build DefMap`() = repeatBuildDefMaps(RUSTC)

    private fun repeatTest(info: RealProjectInfo, filePath: String, f: (Timings) -> Unit) {
        println("${name.removePrefix("test ")}:")
        repeatBenchmark {
            val disposable = project.macroExpansionManager.setUnitTestExpansionModeAndDirectory(MacroExpansionScope.ALL, name)
            openRealProject(info) ?: return@repeatBenchmark
            myFixture.configureFromTempProjectFile(filePath)
            f(it)
            Disposer.dispose(disposable)
            super.tearDown()
            super.setUp()
        }
    }

    private fun measureResolveAndHighlight(timings: Timings) {
        val modificationCount = currentPsiModificationCount()
        buildDefMaps(timings)
        val references = collectReferences(timings)
        resolveReferences(timings, references)
        highlight(timings)
        resolveReferencesCached(references, modificationCount, timings)
    }

    private fun repeatBuildDefMaps(info: RealProjectInfo) {
        println("${name.removePrefix("test ")}:")
        project.macroExpansionManager.setUnitTestExpansionModeAndDirectory(MacroExpansionScope.ALL, name)
        openRealProject(info) ?: return

        repeatBenchmark(warmupIterations = 20, iterations = 10000) {
            it.measure("buildDefMap") {
                val pool = Executors.newWorkStealingPool()
                val indicator = EmptyProgressIndicator()
                forceBuildDefMapForAllCrates(project, pool, indicator, async = false)
            }
        }
    }

    private fun profileResolve(timings: Timings) {
        val references = collectReferences(timings)
        val times = mutableListOf<Long>()
        for (i in 0..Int.MAX_VALUE) {
            val time = measureTimeMillis {
                references.forEach { it.reference?.resolve() }
            }
            times += time

            val timeMin = times.min()
            val timeMedian = ObjectUtils.median(*times.toTypedArray())
            println("Resolved all file references in $time ms  (min = $timeMin ms, median = $timeMedian ms)")
            project.rustPsiManager.incRustStructureModificationCount()
        }
    }

    private fun buildDefMaps(timings: Timings) {
        if (!project.isNewResolveEnabled) return

        // Actually [CrateDefMap]s are built two times,
        // in [openRealProject] and [CodeInsightTestFixture.configureFromTempProjectFile]
        val time = timesBuildDefMaps.last()
        timesBuildDefMaps.clear()
        timings.addMeasure("resolve2", time)
    }

    private fun collectReferences(timings: Timings): Collection<RsReferenceElement> =
        timings.measure("collecting") {
            myFixture.file.descendantsOfType()
        }

    private fun resolveReferences(timings: Timings, references: Collection<RsReferenceElement>) =
        timings.measure("resolve") {
            references.forEach { it.reference?.resolve() }
        }

    private fun highlight(timings: Timings) =
        timings.measure("highlighting") {
            myFixture.doHighlighting()
        }

    private fun resolveReferencesCached(
        references: Collection<RsReferenceElement>,
        oldModificationCount: Long,
        timings: Timings
    ) {
        check(oldModificationCount == currentPsiModificationCount()) {
            "PSI changed during resolve and highlighting, resolve might be double counted"
        }
        timings.measure("resolve_cached") {
            references.forEach { it.reference?.resolve() }
        }
    }

    private fun currentPsiModificationCount() =
        PsiModificationTracker.SERVICE.getInstance(project).modificationCount

    override val disableMissedCacheAssertions: Boolean get() = false
    private val lastDisposable = Disposer.newDisposable("RsHighlightingPerformanceTest last")

    override fun setUp() {
        super.setUp()
        RecursionManager.disableMissedCacheAssertions(lastDisposable)
    }

    override fun tearDown() {
        super.tearDown()
        Disposer.dispose(lastDisposable)
    }
}
