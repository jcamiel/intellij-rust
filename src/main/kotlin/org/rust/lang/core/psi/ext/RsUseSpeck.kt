/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.RsUseGroup
import org.rust.lang.core.psi.RsUseSpeck
import org.rust.lang.core.stubs.RsUseSpeckStub

val RsUseSpeck.isStarImport: Boolean get() = greenStub?.isStarImport ?: (mul != null) // I hate operator precedence
val RsUseSpeck.qualifier: RsPath? get() {
    val parentUseSpeck = (context as? RsUseGroup)?.parentUseSpeck ?: return null
    return parentUseSpeck.pathOrQualifier
}

val RsUseSpeck.pathOrQualifier: RsPath? get() = path ?: qualifier

val RsUseSpeck.nameInScope: String? get() = itemName(withAlias = true)

fun RsUseSpeck.itemName(withAlias: Boolean): String? {
    if (useGroup != null) return null
    if (withAlias) {
        alias?.name?.let { return it }
    }
    val baseName = path?.referenceName ?: return null
    if (baseName == "self") {
        return qualifier?.referenceName
    }
    return baseName
}

val RsUseSpeck.isIdentifier: Boolean
    get() {
        val path = path
        if (!(path != null && path == firstChild && path == lastChild)) return false
        return (path.identifier != null && path.path == null && path.coloncolon == null)
    }

fun RsUseSpeck.forEachLeafSpeck(consumer: (RsUseSpeck) -> Unit) {
    val group = useGroup
    if (group == null) consumer(this) else group.useSpeckList.forEach { it.forEachLeafSpeck(consumer) }
}

// // todo functional interface
// fun RsUseItemStub.forEachLeafSpeck(consumer: (/* usePath */ Array<String>, /* nameInScope */ String, /* isStarImport */ Boolean) -> Unit) {
//     val rootUseSpeck = findChildStubByType(RsUseSpeckStub.Type) ?: return
//     val segments = mutableListOf<String>()
//     fun go(speck: RsUseSpeckStub) {
//         val isStarImport = speck.isStarImport
//         val path = speck.path
//         val alias = if (isStarImport) null else speck.alias?.name
//         val useGroup = if (isStarImport) null else speck.useGroup
//
//     }
//
//     fun addSegments(path: RsPathStub): Int {
//         val numberSegments = if (path.hasColonColon) {
//             val subpath = path.path ?: error("Inconsistent `.hasColonColon` and `.path`")
//             addSegments(subpath)
//         } else {
//             0
//         }
//         segments += path.referenceName
//         return numberSegments
//     }
//     go(rootUseSpeck)
// }

fun RsUseSpeckStub.forEachLeafSpeck(consumer: (RsUseSpeckStub) -> Unit) {
    val group = useGroup
    if (group == null) consumer(this) else group.childrenStubs.forEach { (it as RsUseSpeckStub).forEachLeafSpeck(consumer) }
}

abstract class RsUseSpeckImplMixin : RsStubbedElementImpl<RsUseSpeckStub>, RsUseSpeck {
    constructor (node: ASTNode) : super(node)
    constructor (stub: RsUseSpeckStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)
}
