/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.util.io.DigestUtil
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.stubs.RsMacroCallStub
import org.rust.lang.core.stubs.RsModItemStub
import org.rust.lang.core.stubs.RsNamedStub
import org.rust.openapiext.fileId
import org.rust.stdext.HashCode
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream

interface Writeable {
    fun writeTo(data: DataOutput)
}

val RsFile.modificationStampForResolve: Long get() = viewProvider.modificationStamp

fun isFileChanged(file: RsFile, defMap: CrateDefMap, crate: Crate): Boolean {
    // todo return ?
    val fileInfo = defMap.fileInfos[file.virtualFile.fileId] ?: return false

    val hashCalculator = HashCalculator()
    // Don't use `file.isDeeplyEnabledByCfg` - it can trigger resolve (and cause infinite recursion)
    val isDeeplyEnabledByCfg = fileInfo.modData.isDeeplyEnabledByCfg
    val visitor = ModLightCollector(
        crate,
        hashCalculator,
        fileRelativePath = "",
        collectChildModules = true
    )
    val fileStub = file.getStubOrBuild() ?: return false
    ModCollectorBase.collectMod(fileStub, isDeeplyEnabledByCfg, visitor, crate)
    if (file.virtualFile == crate.rootModFile) {
        visitor.modData.attributes = file.attributes
    }
    return hashCalculator.getFileHash() != fileInfo.hash
}

private fun calculateModHash(modData: ModDataLight): HashCode {
    val digest = DigestUtil.sha1()
    val data = DataOutputStream(/* todo buffer? */ DigestOutputStream(OutputStream.nullOutputStream(), digest))

    fun writeElements(elements: List<Writeable>) {
        for (element in elements) {
            element.writeTo(data)
        }
        data.writeByte(0)  // delimiter
    }

    modData.sort()
    writeElements(modData.items)
    writeElements(modData.imports)
    writeElements(modData.macroCalls)
    writeElements(modData.macroDefs)
    data.writeByte(modData.attributes?.ordinal ?: RsFile.Attributes.values().size)

    return HashCode.fromByteArray(digest.digest())
}

private class ModDataLight {
    val items: MutableList<ItemLight> = mutableListOf()
    val imports: MutableList<ImportLight> = mutableListOf()
    val macroCalls: MutableList<MacroCallLight> = mutableListOf()
    val macroDefs: MutableList<MacroDefLight> = mutableListOf()
    var attributes: RsFile.Attributes? = null  // not null only for crate root

    fun sort() {
        items.sortBy { it.name }  // todo
        imports.sortBy { it.usePathString }  // todo
        // todo smart sort for macro calls & defs
    }
}

class HashCalculator {
    // We can't use `Map<String, HashCode>`,
    // because two modules with different cfg attributes can have same `fileRelativePath`
    private val modulesHash: MutableList<Pair<String /* fileRelativePath */, HashCode>> = mutableListOf()

    fun getVisitor(crate: Crate, fileRelativePath: String): ModVisitor =
        ModLightCollector(crate, this, fileRelativePath)

    fun onCollectMod(fileRelativePath: String, hash: HashCode) {
        modulesHash += fileRelativePath to hash
    }

    /** Called after visiting all submodules */
    fun getFileHash(): HashCode {
        val digest = DigestUtil.sha1()
        for ((fileRelativePath, modHash) in modulesHash) {
            digest.update(fileRelativePath.toByteArray())
            digest.update(modHash.toByteArray())
        }
        return HashCode.fromByteArray(digest.digest())
    }
}

private class ModLightCollector(
    private val crate: Crate,
    private val hashCalculator: HashCalculator,
    private val fileRelativePath: String,
    private val collectChildModules: Boolean = false,
) : ModVisitor {

    val modData: ModDataLight = ModDataLight()

    override fun collectItem(item: ItemLight, stub: RsNamedStub) {
        modData.items += item
        if (collectChildModules && stub is RsModItemStub) {
            collectMod(stub, item.name, item.isDeeplyEnabledByCfg)
        }
    }

    override fun collectImport(import: ImportLight) {
        modData.imports += import
    }

    override fun collectMacroCall(call: MacroCallLight, stub: RsMacroCallStub) {
        modData.macroCalls += call
    }

    override fun collectMacroDef(def: MacroDefLight) {
        modData.macroDefs += def
    }

    override fun afterCollectMod() {
        val fileHash = calculateModHash(modData)
        hashCalculator.onCollectMod(fileRelativePath, fileHash)
    }

    private fun collectMod(mod: RsModItemStub, modName: String, isDeeplyEnabledByCfg: Boolean) {
        val fileRelativePath = "$fileRelativePath::$modName"
        val visitor = ModLightCollector(
            crate,
            hashCalculator,
            fileRelativePath,
            collectChildModules = true
        )
        ModCollectorBase.collectMod(mod, isDeeplyEnabledByCfg, visitor, crate)
    }
}
