/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustPerformanceTests

import com.intellij.openapi.util.Disposer
import org.rust.lang.core.macros.MacroExpansionScope
import org.rust.lang.core.macros.macroExpansionManager

class RsBuildDefMapTest : RsRealProjectTestBase() {

    /** Don't run it on Rustc! It's a kind of stress-test */
    // fun `test build rustc`() = doTest(RUSTC)

    fun `test build empty`() = doTest(EMPTY)
    fun `test build Cargo`() = doTest(CARGO)
    fun `test build mysql_async`() = doTest(MYSQL_ASYNC)
    fun `test build tokio`() = doTest(TOKIO)
    fun `test build amethyst`() = doTest(AMETHYST)
    fun `test build clap`() = doTest(CLAP)
    fun `test build diesel`() = doTest(DIESEL)
    fun `test build rust_analyzer`() = doTest(RUST_ANALYZER)
    fun `test build xi_editor`() = doTest(XI_EDITOR)
    fun `test build juniper`() = doTest(JUNIPER)

    private fun doTest(info: RealProjectInfo) {
        val disposable = project.macroExpansionManager.setUnitTestExpansionModeAndDirectory(MacroExpansionScope.ALL, name)
        try {
            openRealProject(info)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    // It is a performance test, but we don't want to waste time measuring CPU performance
    override fun isPerformanceTest(): Boolean = false
}
