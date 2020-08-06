/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.util.io.IOUtil
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.MACRO_DOLLAR_CRATE_IDENTIFIER
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.Namespace
import org.rust.lang.core.resolve.namespaces
import org.rust.openapiext.testAssert
import java.io.DataOutput

/**
 * This class is used:
 * - When collecting explicit items: filling ModData + calculating hash
 * - When collecting expanded items: filling ModData
 * - When checking if file was changed: calculating hash
 */
class ModCollectorBase private constructor(
    private val visitor: ModVisitor,
    private val crate: Crate,
    private val isModEnabledByCfg: Boolean,
) {

    /** [itemsOwner] - [RsMod] or [RsForeignModItem] */
    private fun collectElements(itemsOwner: RsItemsOwner) {
        val items = itemsOwner.itemsAndMacros.toList()

        // This should be processed eagerly instead of deferred to resolving.
        // `#[macro_use] extern crate` is hoisted to import macros before collecting any other items.
        for (item in items) {
            if (item is RsExternCrateItem) {
                collectExternCrate(item)
            }
        }
        for (item in items) {
            if (item !is RsExternCrateItem) {
                collectElement(item)
            }
        }
    }

    private fun collectElement(element: RsElement) {
        when (element) {
            // impls are not named elements, so we don't need them for name resolution
            is RsImplItem -> Unit

            is RsForeignModItem -> collectElements(element)

            is RsUseItem -> collectUseItem(element)
            is RsExternCrateItem -> error("extern crates are processed eagerly")

            is RsItemElement -> collectItem(element)

            is RsMacroCall -> collectMacroCall(element)
            is RsMacro -> collectMacroDef(element)

            // `RsOuterAttr`, `RsInnerAttr` or `RsVis` when `itemsOwner` is `RsModItem`
            // `RsExternAbi` when `itemsOwner` is `RsForeignModItem`
            // etc
            else -> Unit
        }
    }

    private fun collectUseItem(useItem: RsUseItem) {
        val isEnabledByCfg = isModEnabledByCfg && useItem.isEnabledByCfgSelf(crate)
        val visibility = VisibilityLight.from(useItem)
        val hasPreludeImport = useItem.hasPreludeImport
        // todo move dollarCrateId from RsUseItem to RsPath
        val dollarCrateId = useItem.getUserData(RESOLVE_DOLLAR_CRATE_ID_KEY)  // for `use $crate::`
        useItem.useSpeck?.forEachLeafSpeck { speck ->
            val (usePath, nameInScope) = speck.getFullPathAndNameInScope() ?: return@forEachLeafSpeck
            val import = ImportLight(
                usePath = adjustPathWithDollarCrate(usePath, dollarCrateId),
                nameInScope = nameInScope,
                visibility = visibility,
                isEnabledByCfg = isEnabledByCfg,
                isGlob = speck.isStarImport,
                isPrelude = hasPreludeImport
            )
            visitor.collectImport(import)
        }
    }

    private fun collectExternCrate(externCrate: RsExternCrateItem) {
        val import = ImportLight(
            usePath = externCrate.referenceName,
            nameInScope = externCrate.nameWithAlias,
            visibility = VisibilityLight.from(externCrate),
            isEnabledByCfg = isModEnabledByCfg && externCrate.isEnabledByCfgSelf(crate),
            isExternCrate = true,
            isMacroUse = externCrate.hasMacroUse
        )
        if (import.usePath == "self" && import.nameInScope == "self") return
        visitor.collectImport(import)
    }

    private fun collectItem(item: RsItemElement) {
        val name = item.name ?: return
        if (item !is RsNamedElement) return
        if (item is RsFunction && item.isProcMacroDef) return  // todo proc macros
        val itemLight = ItemLight(
            name = name,
            visibility = VisibilityLight.from(item),
            isEnabledByCfg = isModEnabledByCfg && item.isEnabledByCfgSelf(crate),
            namespaces = item.namespaces
        )
        visitor.collectItem(itemLight, item)
    }

    private fun collectMacroCall(call: RsMacroCall) {
        val isEnabledByCfg = isModEnabledByCfg && call.isEnabledByCfgSelf(crate)
        if (!isEnabledByCfg) return
        val body = call.includeMacroArgument?.expr?.getValue(crate) ?: call.macroBody ?: return
        val path = getMacroCallPath(call)
        val callLight = MacroCallLight(path, body)
        visitor.collectMacroCall(callLight, call)
    }

    private fun collectMacroDef(def: RsMacro) {
        // check(def.stub != null)  // todo
        val isEnabledByCfg = isModEnabledByCfg && def.isEnabledByCfgSelf(crate)
        if (!isEnabledByCfg) return  // todo
        val defLight = MacroDefLight(
            name = def.name ?: return,
            macroBodyText = def.greenStub?.macroBody ?: def.macroBodyStubbed?.text ?: return,
            macroBody = def.macroBodyStubbed ?: return,
            hasMacroExport = def.hasMacroExport,
            hasLocalInnerMacros = def.hasMacroExportLocalInnerMacros
        )
        visitor.collectMacroDef(defLight, def)
    }

    companion object {
        fun collectMod(mod: RsMod, isEnabledByCfg: Boolean, visitor: ModVisitor, crate: Crate) {
            val collector = ModCollectorBase(visitor, crate, isEnabledByCfg)
            collector.collectElements(mod)
            collector.visitor.afterCollectMod(mod)
        }
    }
}

interface ModVisitor {
    fun collectItem(item: ItemLight, itemPsi: RsItemElement)
    fun collectImport(import: ImportLight)
    fun collectMacroCall(call: MacroCallLight, callPsi: RsMacroCall)
    fun collectMacroDef(def: MacroDefLight, defPsi: RsMacro)
    fun afterCollectMod(mod: RsMod) {}
}

class CompositeModVisitor(
    private val visitor1: ModVisitor,
    private val visitor2: ModVisitor,
) : ModVisitor {
    override fun collectItem(item: ItemLight, itemPsi: RsItemElement) {
        visitor1.collectItem(item, itemPsi)
        visitor2.collectItem(item, itemPsi)
    }

    override fun collectImport(import: ImportLight) {
        visitor1.collectImport(import)
        visitor2.collectImport(import)
    }

    override fun collectMacroCall(call: MacroCallLight, callPsi: RsMacroCall) {
        visitor1.collectMacroCall(call, callPsi)
        visitor2.collectMacroCall(call, callPsi)
    }

    override fun collectMacroDef(def: MacroDefLight, defPsi: RsMacro) {
        visitor1.collectMacroDef(def, defPsi)
        visitor2.collectMacroDef(def, defPsi)
    }

    override fun afterCollectMod(mod: RsMod) {
        visitor1.afterCollectMod(mod)
        visitor2.afterCollectMod(mod)
    }
}

sealed class VisibilityLight : Writeable {
    object Public : VisibilityLight()
    class Restricted(val inPath: String) : VisibilityLight()

    override fun writeTo(data: DataOutput) {
        when (this) {
            Public -> data.writeBoolean(true)
            is Restricted -> {
                data.writeBoolean(false)
                IOUtil.writeUTF(data, inPath)
            }
        }
    }

    companion object {
        val CRATE = Restricted("crate")
        val PRIVATE = Restricted("self")

        fun from(visibility: RsVisibilityOwner): VisibilityLight {
            val vis = visibility.vis ?: return PRIVATE
            // todo optimization: use `vis.stub.findChildStubByType(RsVisStub.Type)`
            return when (vis.stubKind) {
                RsVisStubKind.PUB -> Public
                RsVisStubKind.CRATE -> CRATE
                RsVisStubKind.RESTRICTED -> {
                    val path = vis.visRestriction!!.path
                    val pathText = path.fullPath.removePrefix("::")  // 2015 edition, absolute paths
                    if (pathText.isEmpty() || pathText == "crate") return CRATE
                    Restricted(pathText)
                }
            }
        }
    }
}

// todo add `elementType` or at least `isModOrEnum` ?
// todo add `hasMacroUse`, `pathAttribute` if item is mod ?
data class ItemLight(
    val name: String,
    val visibility: VisibilityLight,
    val isEnabledByCfg: Boolean,
    val namespaces: Set<Namespace>,
) : Writeable {
    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, name)
        visibility.writeTo(data)

        // todo use one byte
        data.writeBoolean(isEnabledByCfg)
        data.writeBoolean(Namespace.Types in namespaces)
        data.writeBoolean(Namespace.Values in namespaces)
    }
}

data class ImportLight(
    val usePath: String,  // foo::bar::baz
    val nameInScope: String,
    val visibility: VisibilityLight,
    val isEnabledByCfg: Boolean,
    val isGlob: Boolean = false,
    val isExternCrate: Boolean = false,
    val isMacroUse: Boolean = false,
    val isPrelude: Boolean = false,  // #[prelude_import]
) : Writeable {

    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, usePath)
        IOUtil.writeUTF(data, nameInScope)
        visibility.writeTo(data)
        // todo use one byte
        data.writeBoolean(isEnabledByCfg)
        data.writeBoolean(isGlob)
        data.writeBoolean(isExternCrate)
        data.writeBoolean(isMacroUse)
        data.writeBoolean(isPrelude)
    }
}

data class MacroCallLight(val path: String, val body: String) : Writeable {

    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, path)
        IOUtil.writeUTF(data, body)
    }
}

data class MacroDefLight(
    val name: String,
    val macroBodyText: String,
    // todo: если [RsMacroBody] получается из строки, то он парсится каждый раз заново
    //  но необязательно данный макрос будет использован
    //  поэтому мб парсить лениво (хранить или строку или RsMacroBody)?
    val macroBody: RsMacroBody,
    val hasMacroExport: Boolean,
    val hasLocalInnerMacros: Boolean,
) : Writeable {

    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, name)
        IOUtil.writeUTF(data, macroBodyText)
        // todo one byte
        data.writeBoolean(hasMacroExport)
        data.writeBoolean(hasLocalInnerMacros)
    }
}

private fun RsUseSpeck.getFullPathAndNameInScope(): Pair<String, String>? {
    return if (isStarImport) {
        val usePath = getFullPath() ?: return null
        val nameInScope = "_"  // todo
        usePath to nameInScope
    } else {
        testAssert { useGroup === null }
        val path = path ?: return null
        val nameInScope = nameInScope ?: return null
        path.fullPath to nameInScope
    }
}

private fun RsUseSpeck.getFullPath(): String? {
    path?.let { return it.fullPath }
    return when (val parent = parent) {
        // `use ::*;`  (2015 edition)
        //        ^ speck
        is RsUseItem -> "crate"
        // `use aaa::{self, *};`
        //                  ^ speck
        // `use aaa::{{{*}}};`
        //              ^ speck
        is RsUseGroup -> (parent.parent as? RsUseSpeck)?.getFullPath()
        else -> null
    }
}

private fun getMacroCallPath(call: RsMacroCall): String {
    val path = call.path.fullPath

    val crateIdFromLocalInnerMacros = call.path.getUserData(RESOLVE_LOCAL_INNER_MACROS_CRATE_ID_KEY)
    if (crateIdFromLocalInnerMacros != null) {
        return "$MACRO_DOLLAR_CRATE_IDENTIFIER::$crateIdFromLocalInnerMacros::$path"
    }

    val crateIdFromDollarCrate = call.path.getUserData(RESOLVE_DOLLAR_CRATE_ID_KEY)
    return adjustPathWithDollarCrate(path, crateIdFromDollarCrate)
}

// before: `IntellijRustDollarCrate::foo;`
// after:  `IntellijRustDollarCrate::12345::foo;`
//                                   ~~~~~ crateId
private fun adjustPathWithDollarCrate(path: String, crateId: CratePersistentId?): String {
    if (!path.startsWith(MACRO_DOLLAR_CRATE_IDENTIFIER)) return path

    if (crateId === null) {
        RESOLVE_LOG.error("Can't find crate for path starting with \$crate: '$path'")
        return path
    }
    return path.replaceFirst(MACRO_DOLLAR_CRATE_IDENTIFIER, "$MACRO_DOLLAR_CRATE_IDENTIFIER::$crateId")
}
