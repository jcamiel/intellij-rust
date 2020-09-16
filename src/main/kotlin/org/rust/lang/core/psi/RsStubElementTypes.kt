/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType

/** Contains constants from [RsElementTypes] with correct types */
object RsStubElementTypes {
    val INCLUDE_MACRO_ARGUMENT = cast<RsIncludeMacroArgument>(RsElementTypes.INCLUDE_MACRO_ARGUMENT)
    val USE_GROUP = cast<RsUseGroup>(RsElementTypes.USE_GROUP)
    val ENUM_BODY = cast<RsEnumBody>(RsElementTypes.ENUM_BODY)
    val VIS_RESTRICTION = cast<RsVisRestriction>(RsElementTypes.VIS_RESTRICTION)
}

@Suppress("UNCHECKED_CAST")
private fun <T : PsiElement?> cast(type: IElementType): IStubElementType<StubElement<T>, T> {
    return type as IStubElementType<StubElement<T>, T>
}
