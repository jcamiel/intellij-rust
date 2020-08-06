/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.util.io.IOUtil
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.RsEnumVariant
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.containingCrate
import org.rust.lang.core.psi.ext.superMods
import org.rust.lang.core.resolve.Namespace
import org.rust.openapiext.fileId
import org.rust.openapiext.testAssert
import org.rust.stdext.HashCode
import java.io.DataInputStream
import java.io.DataOutput
import java.nio.file.Path

class DefDatabase(
    /** `DefMap`s for some crate and all its dependencies (including transitive) */
    val allDefMaps: Map<CratePersistentId, CrateDefMap>
) {
    fun getModData(modPath: ModPath): ModData? {
        val defMap = allDefMaps[modPath.crate]
            ?: error("todo")
        return defMap.getModData(modPath)
    }

    fun tryCastToModData(perNs: PerNs): ModData? {
        val types = perNs.types ?: return null
        return tryCastToModData(types)
    }

    // todo оптимизация: хранить в DefMap map из String (modPath) в ModData
    fun tryCastToModData(types: VisItem): ModData? {
        if (!types.isModOrEnum) return null
        return getModData(types.path)
    }

    fun getMacroInfo(macroDef: VisItem): MacroDefInfo {
        val defMap = allDefMaps[macroDef.crate]!!
        return defMap.getMacroInfo(macroDef)
    }
}

typealias FileId = Int

class FileInfo(
    /**
     * Result of [FileViewProvider.getModificationStamp].
     *
     * Here are possible (other) methods to use:
     * - [PsiFile.getModificationStamp]
     * - [FileViewProvider.getModificationStamp]
     * - [VirtualFile.getModificationStamp]
     * - [VirtualFile.getModificationCount]
     * - [Document.getModificationStamp]  // todo ?
     *
     * Notes:
     * - [VirtualFile] methods is updated only after file is saved to disk
     * - Only [VirtualFile.getModificationCount] survives IDE restart
     */
    val modificationStamp: Long,
    /** Optimization for [CrateDefMap.getModData] */
    val modData: ModData,
    val hash: HashCode,
)

// todo вынести поля нужные только на этапе построения в collector ?
class CrateDefMap(
    val crate: CratePersistentId,
    val root: ModData,

    val externPrelude: MutableMap<String, ModData>,
    // used only by `extern crate crate_name;` declarations
    val directDependenciesDefMaps: Map<String, CrateDefMap>,
    allDependenciesDefMaps: Map<CratePersistentId, CrateDefMap>,
    var prelude: ModData?,
    val metaData: CrateMetaData,
    val crateDescription: String,  // only for debug
) {
    // todo сделать DefDatabase интерфейсом и использовать другую реализацию (которая вызывает `crate.defMap`) после построения текущей DefMap ?
    val defDatabase: DefDatabase = DefDatabase(allDependenciesDefMaps + (crate to this))

    /**
     * File included via `include!` macro has same [FileInfo.modData] as main file,
     * but different [FileInfo.hash] and [FileInfo.modificationStamp]
     */
    val fileInfos: MutableMap<FileId, FileInfo> = hashMapOf()

    /**
     * Files which currently do not exist, but could affect resolve if created:
     * - for unresolved mod declarations - `.../name.rs` and `.../name/mod.rs`
     * - for unresolved `include!` macro - corresponding file
     *
     * Note: [missedFiles] should be empty if compilation is successful.
     */
    val missedFiles: MutableList<Path> = mutableListOf()

    /** For tests */
    val timestamp: Long = System.nanoTime()

    fun getModData(modPath: ModPath): ModData? {
        check(crate == modPath.crate)
        return modPath.segments
            // todo assert not null ?
            .fold(root as ModData?) { modData, segment -> modData?.childModules?.get(segment) }
    }

    fun getModData(mod: RsMod): ModData? {
        if (isUnitTestMode) {
            // todo не очень полезная проверка?)
            mod.containingCrate?.id?.let { check(it == crate) }
        }
        return getModDataFast(mod)
    }

    private fun getModDataFast(mod: RsMod): ModData? {
        if (mod is RsFile) {
            val virtualFile = mod.originalFile.virtualFile ?: run {
                check(isUnitTestMode)  // todo
                return getModDataSlow(mod)
            }
            val fileInfo = fileInfos[virtualFile.fileId]
            // todo если здесь возникнет exception, то RsBuildDefMapTest всё равно пройдёт
            // Note: we don't expand cfg-disabled macros (it can contain mod declaration)
            testAssert { fileInfo != null || !mod.isDeeplyEnabledByCfg }
            return fileInfo?.modData
        }
        val parentMod = mod.`super` ?: return null
        val parentModData = getModDataFast(parentMod) ?: return null
        return parentModData.childModules[mod.modName]
    }

    private fun getModDataSlow(mod: RsMod): ModData? {
        val modPath = ModPath.fromMod(mod, crate) ?: return null
        return getModData(modPath)
    }

    // todo - создать ещё одну Map вроде legacyMacros, в которой будут записаны только явно объявленные макросы?
    fun getMacroInfo(macroDef: VisItem): MacroDefInfo {
        val containingMod = getModData(macroDef.containingMod)!!
        return containingMod.legacyMacros[macroDef.name]!!
    }

    /**
     * Import all exported macros from another crate.
     *
     * Exported macros are just all macros in the root module scope.
     * Note that it contains not only all `#[macro_export]` macros, but also all aliases
     * created by `use` in the root module, ignoring the visibility of `use`.
     */
    fun importAllMacrosExported(from: CrateDefMap) {
        for ((name, def) in from.root.visibleItems) {
            val macroDef = def.macros ?: continue
            // `macro_use` only bring things into legacy scope.
            root.legacyMacros[name] = defDatabase.getMacroInfo(macroDef)
        }
    }

    fun addVisitedFile(file: RsFile, modData: ModData, fileHash: HashCode) {
        val fileId = file.virtualFile.fileId
        // TOdO: File included in module tree multiple times ?
        // testAssert { fileId !in fileInfos }
        fileInfos[fileId] = FileInfo(file.modificationStampForResolve, modData, fileHash)
    }

    override fun toString(): String = crateDescription
}

class ModData(
    val parent: ModData?,
    val crate: CratePersistentId,
    val path: ModPath,
    val isEnabledByCfg: Boolean,
    /** id of containing file */
    val fileId: FileId,
    // todo тип? String / List<String> / ModPath
    val fileRelativePath: String,  // starts with ::
    /** `fileId` of owning directory */
    val ownedDirectoryId: FileId?,
    val isEnum: Boolean = false,
) {
    val name: String get() = path.name
    val isCrateRoot: Boolean get() = parent === null
    val isRsFile: Boolean get() = fileRelativePath.isEmpty()
    val parents: Sequence<ModData> get() = generateSequence(this) { it.parent }

    // todo три мапы ?
    val visibleItems: MutableMap<String, PerNs> = hashMapOf()
    val childModules: MutableMap<String, ModData> = hashMapOf()

    /**
     * Macros visible in current module in legacy textual scope
     * Module scoped macros will be inserted into [visibleItems] instead of here.
     */
    // todo visibility ?
    // todo currently stores only cfg-enabled macros
    val legacyMacros: MutableMap<String, MacroDefInfo> = hashMapOf()

    /** Traits imported via `use Trait as _;` */
    val unnamedTraitImports: MutableMap<ModPath, Visibility> = hashMapOf()

    operator fun get(name: String): PerNs = visibleItems.getOrDefault(name, PerNs.Empty)

    fun getVisibleItems(filterVisibility: (Visibility) -> Boolean): List<Pair<String, PerNs>> {
        val usualItems = visibleItems.entries
            // todo use mapNotNull ?
            .map { (name, visItem) -> name to visItem.filterVisibility(filterVisibility) }
            .filterNot { (_, visItem) -> visItem.isEmpty }
        if (unnamedTraitImports.isEmpty()) return usualItems

        val traitItems = unnamedTraitImports
            .mapNotNull { (path, visibility) ->
                if (!filterVisibility(visibility)) return@mapNotNull null
                val trait = VisItem(path, visibility, isModOrEnum = false)
                "_" to PerNs(types = trait)
            }
        // todo optimize
        return usualItems + traitItems
    }

    fun addVisibleItem(name: String, perNs: PerNs) {
        val perNsExisting = visibleItems.computeIfAbsent(name) { PerNs() }
        perNsExisting.update(perNs)
    }

    fun asVisItem(visibility: Visibility = Visibility.Public): VisItem {
        val parent = parent
        return if (parent === null) {
            // crate root
            VisItem(path, visibility, true)
        } else {
            parent.visibleItems[name]?.types?.takeIf { it.isModOrEnum }
                ?: error("Inconsistent `visibleItems` and `childModules` in parent of $this")
        }
    }

    // todo check usages regarding `Public` visibility
    fun asPerNs(visibility: Visibility = Visibility.Public): PerNs = PerNs(types = asVisItem(visibility))

    fun getNthParent(n: Int): ModData? {
        check(n >= 0)
        return parents.drop(n).firstOrNull()
    }

    override fun toString(): String = "ModData($path, crate=$crate)"
}

data class PerNs(
    // todo var ?
    var types: VisItem? = null,
    var values: VisItem? = null,
    var macros: VisItem? = null,
    // todo
    // val invalid: List<ModPath>
) {
    val isEmpty: Boolean get() = types === null && values === null && macros === null

    constructor(visItem: VisItem, ns: Set<Namespace>) :
        this(
            visItem.takeIf { Namespace.Types in ns },
            visItem.takeIf { Namespace.Values in ns },
            visItem.takeIf { Namespace.Macros in ns }
        )

    fun withVisibility(visibility: Visibility): PerNs =
        PerNs(
            types?.withVisibility(visibility),
            values?.withVisibility(visibility),
            macros?.withVisibility(visibility)
        )

    fun filterVisibility(filter: (Visibility) -> Boolean): PerNs =
        PerNs(
            types?.takeIf { filter(it.visibility) },
            values?.takeIf { filter(it.visibility) },
            macros?.takeIf { filter(it.visibility) }
        )

    // todo объединить с DefCollector#pushResolutionFromImport ?
    fun update(other: PerNs) {
        // todo multiresolve
        fun merge(existing: VisItem?, new: VisItem?): VisItem? {
            if (existing === null) return new
            if (new === null) return existing
            return if (new.visibility.isStrictMorePermissive(existing.visibility)) new else existing
        }
        types = merge(types, other.types)
        values = merge(values, other.values)
        macros = merge(macros, other.macros)
    }

    fun or(other: PerNs): PerNs =
        PerNs(
            types ?: other.types,
            values ?: other.values,
            macros ?: other.macros
        )

    fun mapItems(f: (VisItem) -> VisItem): PerNs =
        PerNs(
            types?.let { f(it) },
            values?.let { f(it) },
            macros?.let { f(it) }
        )

    companion object {
        val Empty: PerNs = PerNs()
    }
}

/**
 * The item which can be visible in the module (either directly declared or imported)
 * Could be [RsEnumVariant] (because it can be imported)
 */
data class VisItem(
    /**
     * Full path to item, including its name.
     * Note: Can't store [containingMod] and [name] separately, because [VisItem] could be used for crate root
     */
    val path: ModPath,
    val visibility: Visibility,
    val isModOrEnum: Boolean = false,
) {
    init {
        check(isModOrEnum || path.segments.isNotEmpty())
    }

    val containingMod: ModPath get() = path.parent  // mod where item is explicitly declared
    val name: String get() = path.name
    val crate: CratePersistentId get() = path.crate

    fun withVisibility(visibilityNew: Visibility): VisItem =
        if (visibility === visibilityNew || visibility.isInvisible) this else copy(visibility = visibilityNew)

    override fun toString(): String = "$visibility $path"
}

sealed class Visibility {
    object Public : Visibility()

    /** includes private */
    data class Restricted(val inMod: ModData) : Visibility()

    /**
     * Means that we have import to private item
     * So normally we should ignore such [VisItem] (it is not accessible)
     * But we record it for completion, etc
     */
    object Invisible : Visibility()

    object CfgDisabled : Visibility()

    fun isVisibleFromOtherCrate(): Boolean = this === Public

    fun isVisibleFromMod(mod: ModData): Boolean {
        return when (this) {
            Public -> true
            // Alternative realization: `mod.parents.contains(inMod)`
            is Restricted -> inMod.path.isSubPathOf(mod.path)
            Invisible, CfgDisabled -> false
        }
    }

    fun isStrictMorePermissive(other: Visibility): Boolean {
        return if (this is Restricted && other is Restricted) {
            inMod.crate == other.inMod.crate
                && inMod !== other.inMod
                && other.inMod.parents.contains(inMod)
        } else {
            when (this) {
                Public -> other !is Public
                is Restricted -> other === Invisible || other === CfgDisabled
                Invisible -> other === CfgDisabled
                CfgDisabled -> false
            }
        }
    }

    val isInvisible: Boolean get() = this === Invisible || this === CfgDisabled

    override fun toString(): String =
        when (this) {
            Public -> "Public"
            is Restricted -> "Restricted(in ${inMod.path})"
            Invisible -> "Invisible"
            CfgDisabled -> "CfgDisabled"
        }

    fun writeTo(data: DataOutput, withCrate: Boolean) {
        when (this) {
            is Public -> data.writeByte(0)
            is Restricted -> {
                data.writeByte(1)
                if (withCrate) inMod.path.writeTo(data, withCrate)
            }
            is Invisible -> data.writeByte(2)
            is CfgDisabled -> data.writeByte(3)
        }
    }
}

/** Path to a module or an item in module */
data class ModPath(
    val crate: CratePersistentId,
    // todo оптимизация: хранить `path`, а `segments` считать на лету ?
    val segments: List<String>,
    // val fileId: FileId,  // id of containing file
    // val fileRelativePath: String  // empty for pathRsFile
) {
    val path: String get() = segments.joinToString("::")
    val name: String get() = segments.last()
    val parent: ModPath get() = ModPath(crate, segments.subList(0, segments.size - 1))

    fun append(segment: String): ModPath = ModPath(crate, segments + segment)

    override fun toString(): String = path.ifEmpty { "crate" }

    fun writeTo(data: DataOutput, withCrate: Boolean) {
        if (withCrate) data.writeInt(crate)
        IOUtil.writeUTF(data, path)
    }

    /** `mod1::mod2` isSubPathOf `mod1::mod2::mod3` */
    fun isSubPathOf(other: ModPath): Boolean {
        if (crate != other.crate) return false
        if (segments.size > other.segments.size) return false
        // todo profile & optimize
        return other.path.startsWith(path)
    }

    companion object {
        fun readFrom(data: DataInputStream): ModPath = ModPath(data.readInt(), data.readUTF().split("::"))

        // todo remove ?
        fun fromMod(mod: RsMod, crate: CratePersistentId): ModPath? {
            val segments = mod.superMods
                .asReversed().drop(1)
                .map { it.modName ?: return null }
            return ModPath(crate, segments)
        }
    }
}

val RESOLVE_LOG = Logger.getInstance("org.rust.resolve2")
