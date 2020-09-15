/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2.util

import org.rust.lang.core.stubs.RsPathStub
import org.rust.lang.core.stubs.RsUseItemStub
import org.rust.lang.core.stubs.RsUseSpeckStub

fun interface RsLeafUseSpeckConsumer {
    fun consume(usePath: Array<String>, alias: String?, isStarImport: Boolean)
}

fun RsUseItemStub.forEachLeafSpeck(consumer: RsLeafUseSpeckConsumer) {
    val rootUseSpeck = findChildStubByType(RsUseSpeckStub.Type) ?: return
    val segments = arrayListOf<String>()
    rootUseSpeck.forEachLeafSpeck(consumer, segments, isRootSpeck = true)
}

private fun RsUseSpeckStub.forEachLeafSpeck(consumer: RsLeafUseSpeckConsumer, segments: ArrayList<String>, isRootSpeck: Boolean) {
    val path = path
    val useGroup = useGroup
    val isStarImport = isStarImport

    if (path === null && isRootSpeck) {
        // e.g. `use ::*;` and `use ::{aaa, bbb};`
        if (hasColonColon) segments += "crate"
        // We ignore `use *;` - https://github.com/sfackler/rust-openssl/blob/0a0da84f939090f72980c77f40199fc76245d289/openssl-sys/src/asn1.rs#L3
        // Note that `use ::*;` is correct in 2015 edition
        if (!hasColonColon && useGroup === null) return
    }

    val numberSegments = segments.size
    if (path !== null) addPathSegments(path, segments)

    if (useGroup === null) {
        if (!isStarImport && segments.size > 1 && segments.last() == "self") segments.removeAt(segments.lastIndex)
        val alias = if (isStarImport) "_" else alias?.name
        consumer.consume(segments.toTypedArray(), alias, isStarImport)
    } else {
        for (childSpeck in useGroup.childrenStubs) {
            (childSpeck as RsUseSpeckStub).forEachLeafSpeck(consumer, segments, isRootSpeck = false)
        }
    }

    while (segments.size > numberSegments) {
        segments.removeAt(segments.lastIndex)
    }
}

private fun addPathSegments(path: RsPathStub, segments: ArrayList<String>) {
    val subpath = path.path
    if (subpath !== null) {
        addPathSegments(subpath, segments)
    } else if (path.hasColonColon) {
        // absolute path: `::foo::bar`
        //                 ~~~~~ this
        segments += ""
    }
    segments += path.referenceName
}
