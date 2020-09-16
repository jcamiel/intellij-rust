/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.util.io.IOUtil
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.psi.RsElementTypes.INCLUDE_MACRO_ARGUMENT
import org.rust.lang.core.psi.RsForeignModItem
import org.rust.lang.core.psi.RsIncludeMacroArgument
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.Namespace
import org.rust.lang.core.resolve.namespaces
import org.rust.lang.core.resolve2.util.forEachLeafSpeck
import org.rust.lang.core.resolve2.util.getPathWithAdjustedDollarCrate
import org.rust.lang.core.resolve2.util.getRestrictedPath
import org.rust.lang.core.stubs.*
import org.rust.stdext.HashCode
import org.rust.stdext.exhaustive
import org.rust.stdext.writeHashCodeAsNullable
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
    private val isDeeplyEnabledByCfg: Boolean,
) {

    /** [itemsOwner] - [RsMod] or [RsForeignModItem] */
    private fun collectElements(itemsOwner: StubElement<out RsItemsOwner>) {
        val items = itemsOwner.childrenStubs

        // This should be processed eagerly instead of deferred to resolving.
        // `#[macro_use] extern crate` is hoisted to import macros before collecting any other items.
        for (item in items) {
            if (item is RsExternCrateItemStub) {
                collectExternCrate(item)
            }
        }
        for (item in items) {
            if (item !is RsExternCrateItemStub) {
                collectElement(item)
            }
        }
    }

    private fun collectElement(element: StubElement<out PsiElement>) {
        when (element) {
            // impls are not named elements, so we don't need them for name resolution
            is RsImplItemStub -> Unit

            is RsForeignModStub -> collectElements(element)

            is RsUseItemStub -> collectUseItem(element)
            is RsExternCrateItemStub -> error("extern crates are processed eagerly")

            is RsMacroCallStub -> collectMacroCall(element)
            is RsMacroStub -> collectMacroDef(element)

            // Should be after macro stubs (they are [RsNamedStub])
            is RsNamedStub -> collectItem(element)

            // `RsOuterAttr`, `RsInnerAttr` or `RsVis` when `itemsOwner` is `RsModItem`
            // `RsExternAbi` when `itemsOwner` is `RsForeignModItem`
            // etc
            else -> Unit
        }
    }

    private fun collectUseItem(useItem: RsUseItemStub) {
        val visibility = VisibilityLight.from(useItem)
        val hasPreludeImport = useItem.hasPreludeImport
        useItem.forEachLeafSpeck { usePath, alias, isStarImport ->
            // Ignore `use self;`
            if (alias === null && usePath.singleOrNull() == "self") return@forEachLeafSpeck

            val import = ImportLight(
                usePath = usePath,
                nameInScope = alias ?: usePath.last(),
                visibility = visibility,
                isDeeplyEnabledByCfg = isDeeplyEnabledByCfg && useItem.isEnabledByCfgSelf(crate),
                isGlob = isStarImport,
                isPrelude = hasPreludeImport
            )
            visitor.collectImport(import)
        }
    }

    private fun collectExternCrate(externCrate: RsExternCrateItemStub) {
        val import = ImportLight(
            usePath = arrayOf(externCrate.name),
            nameInScope = externCrate.nameWithAlias,
            visibility = VisibilityLight.from(externCrate),
            isDeeplyEnabledByCfg = isDeeplyEnabledByCfg && externCrate.isEnabledByCfgSelf(crate),
            isExternCrate = true,
            isMacroUse = externCrate.hasMacroUse
        )
        if (externCrate.name == "self" && import.nameInScope == "self") return
        visitor.collectImport(import)
    }

    private fun collectItem(item: RsNamedStub) {
        val name = item.name ?: return
        if (item !is RsAttributeOwnerStub) return
        if (item is RsFunctionStub && item.isProcMacroDef) return  // todo proc macros
        @Suppress("UNCHECKED_CAST")  // todo
        val itemLight = ItemLight(
            name = name,
            visibility = VisibilityLight.from(item as StubElement<out RsVisibilityOwner>),
            isDeeplyEnabledByCfg = isDeeplyEnabledByCfg && item.isEnabledByCfgSelf(crate),
            namespaces = item.namespaces
        )
        visitor.collectItem(itemLight, item)
    }

    private fun collectMacroCall(call: RsMacroCallStub) {
        fun RsMacroCallStub.getIncludeMacroArgument(): String? {
            // todo как-нибудь покрасивее??
            val includeMacroArgument = findChildStubByType(INCLUDE_MACRO_ARGUMENT as IStubElementType<*, *>)
                ?: return null
            return (includeMacroArgument.psi as RsIncludeMacroArgument).expr?.getValue(crate)
        }

        val isCallDeeplyEnabledByCfg = isDeeplyEnabledByCfg && call.isEnabledByCfgSelf(crate)
        if (!isCallDeeplyEnabledByCfg) return
        val body = call.getIncludeMacroArgument() ?: call.macroBody ?: return
        val path = call.getPathWithAdjustedDollarCrate()
        val callLight = MacroCallLight(path, body, call.bodyHash)
        visitor.collectMacroCall(callLight, call)
    }

    private fun collectMacroDef(def: RsMacroStub) {
        val isDefDeeplyEnabledByCfg = isDeeplyEnabledByCfg && def.isEnabledByCfgSelf(crate)
        if (!isDefDeeplyEnabledByCfg) return
        val defLight = MacroDefLight(
            name = def.name ?: return,
            body = def.macroBody ?: return,
            bodyHash = def.bodyHash,
            hasMacroExport = def.hasMacroExport,
            hasLocalInnerMacros = def.hasMacroExportLocalInnerMacros
        )
        visitor.collectMacroDef(defLight)
    }

    companion object {
        fun collectMod(mod: StubElement<out RsMod>, isDeeplyEnabledByCfg: Boolean, visitor: ModVisitor, crate: Crate) {
            val collector = ModCollectorBase(visitor, crate, isDeeplyEnabledByCfg)
            collector.collectElements(mod)
            collector.visitor.afterCollectMod()
        }
    }
}

interface ModVisitor {
    fun collectItem(item: ItemLight, stub: RsNamedStub)
    fun collectImport(import: ImportLight)
    fun collectMacroCall(call: MacroCallLight, stub: RsMacroCallStub)
    fun collectMacroDef(def: MacroDefLight)
    fun afterCollectMod() {}
}

class CompositeModVisitor(
    private val visitor1: ModVisitor,
    private val visitor2: ModVisitor,
) : ModVisitor {
    override fun collectItem(item: ItemLight, stub: RsNamedStub) {
        visitor1.collectItem(item, stub)
        visitor2.collectItem(item, stub)
    }

    override fun collectImport(import: ImportLight) {
        visitor1.collectImport(import)
        visitor2.collectImport(import)
    }

    override fun collectMacroCall(call: MacroCallLight, stub: RsMacroCallStub) {
        visitor1.collectMacroCall(call, stub)
        visitor2.collectMacroCall(call, stub)
    }

    override fun collectMacroDef(def: MacroDefLight) {
        visitor1.collectMacroDef(def)
        visitor2.collectMacroDef(def)
    }

    override fun afterCollectMod() {
        visitor1.afterCollectMod()
        visitor2.afterCollectMod()
    }
}

sealed class VisibilityLight : Writeable {
    object Public : VisibilityLight()

    // `pub(crate)`
    object Crate : VisibilityLight()

    class Restricted(val inPath: Array<String>) : VisibilityLight()

    object Private : VisibilityLight()

    override fun writeTo(data: DataOutput) {
        when (this) {
            Public -> data.writeByte(0)
            Crate -> data.writeByte(1)
            Private -> data.writeByte(2)
            is Restricted -> {
                data.writeByte(3)
                data.writePath(inPath)
            }
        }.exhaustive
    }

    companion object {
        fun from(item: StubElement<out RsVisibilityOwner>): VisibilityLight {
            val vis = item.findChildStubByType(RsVisStub.Type) ?: return Private
            return when (vis.kind) {
                RsVisStubKind.PUB -> Public
                RsVisStubKind.CRATE -> Crate
                RsVisStubKind.RESTRICTED -> {
                    val path = vis.getRestrictedPath()
                    if (path.isEmpty() || path.singleOrNull() == "crate") return Crate
                    Restricted(path)
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
    val isDeeplyEnabledByCfg: Boolean,
    val namespaces: Set<Namespace>,
) : Writeable {
    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, name)
        visibility.writeTo(data)

        // todo use one byte
        data.writeBoolean(isDeeplyEnabledByCfg)
        data.writeBoolean(Namespace.Types in namespaces)
        data.writeBoolean(Namespace.Values in namespaces)
    }
}

class ImportLight(
    val usePath: Array<String>,
    val nameInScope: String,
    val visibility: VisibilityLight,
    val isDeeplyEnabledByCfg: Boolean,
    val isGlob: Boolean = false,
    val isExternCrate: Boolean = false,
    val isMacroUse: Boolean = false,
    val isPrelude: Boolean = false,  // #[prelude_import]
) : Writeable {

    // todo ?
    val usePathString: String = usePath.singleOrNull() ?: usePath.joinToString("::")

    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, usePathString)
        IOUtil.writeUTF(data, nameInScope)
        visibility.writeTo(data)
        // todo use one byte
        data.writeBoolean(isDeeplyEnabledByCfg)
        data.writeBoolean(isGlob)
        data.writeBoolean(isExternCrate)
        data.writeBoolean(isMacroUse)
        data.writeBoolean(isPrelude)
    }
}

class MacroCallLight(
    val path: Array<String>,
    val body: String,
    val bodyHash: HashCode?,
) : Writeable {

    override fun writeTo(data: DataOutput) {
        data.writePath(path)
        data.writeHashCodeAsNullable(bodyHash)
    }
}

data class MacroDefLight(
    val name: String,
    val body: String,
    val bodyHash: HashCode?,
    val hasMacroExport: Boolean,
    val hasLocalInnerMacros: Boolean,
) : Writeable {

    override fun writeTo(data: DataOutput) {
        IOUtil.writeUTF(data, name)
        data.writeHashCodeAsNullable(bodyHash)
        // todo one byte
        data.writeBoolean(hasMacroExport)
        data.writeBoolean(hasLocalInnerMacros)
    }
}

// todo ?
private fun DataOutput.writePath(path: Array<String>) {
    for (segment in path) {
        IOUtil.writeUTF(this, segment)
        writeChar(','.toInt())
    }
}
