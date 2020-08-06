/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.psi.stubs.PrebuiltStubsProvider
import com.intellij.psi.stubs.SerializedStubTree
import com.intellij.util.indexing.FileContent

class MacroExpansionStubsProvider : PrebuiltStubsProvider {
    override fun findStub(fileContent: FileContent): SerializedStubTree? {
        val file = fileContent.file
        if (!MacroExpansionManager.isExpansionFile(file)) return null
        val hash = file.loadMixHash() ?: return null
        return MacroExpansionShared.getInstance().cachedBuildStub(fileContent, hash)
    }
}
