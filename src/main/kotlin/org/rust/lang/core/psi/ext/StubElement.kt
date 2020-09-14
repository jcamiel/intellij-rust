/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType

// todo хотелось бы чтобы возвращал StubBase а не PsiElement
@Suppress("UNCHECKED_CAST")
inline fun <reified E : PsiElement> StubElement<*>.getChildrenByType(type: IElementType): Array<E> =
    getChildrenByType(type) { size -> arrayOfNulls<E>(size) as Array<E> }

inline fun <reified E : PsiElement> StubElement<*>.getChildByType(type: IElementType): E? =
    getChildrenByType<E>(type).singleOrNull()
