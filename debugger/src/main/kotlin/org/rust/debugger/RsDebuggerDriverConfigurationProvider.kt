/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration

interface RsDebuggerDriverConfigurationProvider {

    fun getDebuggerDriverConfiguration(project: Project): DebuggerDriverConfiguration?

    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<RsDebuggerDriverConfigurationProvider> =
            ExtensionPointName.create("org.rust.debugger.driverConfigurationProvider")
    }
}
