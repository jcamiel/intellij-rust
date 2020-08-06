/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import org.rust.RsTestBase
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.psi.RsFile
import org.rust.openapiext.toPsiFile

abstract class RsDefMapCachingTestBase : RsTestBase() {

    protected fun doTest(action: () -> Unit, shouldChange: Boolean) {
        val crateRoot = myFixture.findFileInTempDir("main.rs").toPsiFile(myFixture.project) as RsFile
        val crate = crateRoot.crate!!
        val oldStamp = getDefMap(crate).timestamp
        action()
        val newStamp = getDefMap(crate).timestamp
        val changed = newStamp != oldStamp
        check(changed == shouldChange) { "DefMap should ${if (shouldChange) "" else "not "}rebuilt" }
    }

    private fun getDefMap(crate: Crate): CrateDefMap {
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        return project.defMapService.getOrUpdateIfNeeded(crate)!!
    }
}
