/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package com.intellij.openapiext

import com.intellij.openapi.application.ApplicationManager

val isUnitTestMode: Boolean get() = ApplicationManager.getApplication().isUnitTestMode
val isHeadlessEnvironment: Boolean get() = ApplicationManager.getApplication().isHeadlessEnvironment
val isDispatchThread: Boolean get() = ApplicationManager.getApplication().isDispatchThread
val isWriteAccessAllowed: Boolean get() = ApplicationManager.getApplication().isWriteAccessAllowed
val isReadAccessAllowed: Boolean get() = ApplicationManager.getApplication().isReadAccessAllowed
val isInternal: Boolean get() = ApplicationManager.getApplication().isInternal
