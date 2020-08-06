/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import org.rust.cargo.project.workspace.CargoWorkspace.Edition.EDITION_2015
import org.rust.cargo.util.AutoInjectedCrates.CORE
import org.rust.cargo.util.AutoInjectedCrates.STD
import org.rust.lang.RsConstants
import org.rust.lang.RsFileType
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.macros.RangeMap
import org.rust.lang.core.macros.shouldIndexFile
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.RsModDeclItemData
import org.rust.lang.core.resolve.collectResolveVariants
import org.rust.lang.core.resolve.namespaces
import org.rust.lang.core.resolve.processModDeclResolveVariants
import org.rust.openapiext.*
import java.nio.file.Path
import kotlin.test.assertEquals

// todo move to facade ?
class CollectorContext(
    val crate: Crate,
    val project: Project,
    val indicator: ProgressIndicator,
) {
    /** All explicit imports (not expanded from macros) */
    val imports: MutableList<Import> = mutableListOf()

    /** All explicit macro calls  */
    val macroCalls: MutableList<MacroCallInfo> = mutableListOf()
}

fun buildDefMapContainingExplicitItems(
    context: CollectorContext,
    dependenciesDefMaps: Map<Crate, CrateDefMap>
): CrateDefMap? {
    val crate = context.crate
    val crateId = crate.id ?: return null
    val crateRoot = crate.rootMod ?: return null

    val crateRootFile = crate.rootModFile ?: return null
    if (!shouldIndexFile(context.project, crateRootFile)) return null

    val externPrelude = getInitialExternPrelude(crate, crateRoot, dependenciesDefMaps)
    val directDependenciesDefMaps = crate.dependencies
        .mapNotNull {
            val defMap = dependenciesDefMaps[it.crate] ?: return@mapNotNull null
            it.normName to defMap
        }
        .toMap()
    // todo вынести в отдельный метод
    val allDependenciesDefMaps = crate.flatDependencies
        .mapNotNull {
            val id = it.id ?: return@mapNotNull null
            val defMap = dependenciesDefMaps[it] ?: return@mapNotNull null
            id to defMap
        }
        .toMap()
    // look for the prelude
    // If the dependency defines a prelude, we overwrite an already defined
    // prelude. This is necessary to import the "std" prelude if a crate
    // depends on both "core" and "std".
    // todo should find prelude in all dependencies or only direct ones ?
    // todo check that correct prelude is always selected (core vs std)
    val prelude: ModData? = allDependenciesDefMaps.values.map { it.prelude }.firstOrNull()

    val crateRootOwnedDirectory = crateRoot.parent
        ?: error("Can't find parent directory for crate root of $crate crate")
    val crateRootData = ModData(
        parent = null,
        crate = crateId,
        path = ModPath(crateId, emptyList()),
        isEnabledByCfg = true,
        fileId = crateRoot.virtualFile.fileId,
        fileRelativePath = "",
        ownedDirectoryId = crateRootOwnedDirectory.virtualFile.fileId
    )
    val defMap = CrateDefMap(
        crateId,
        crateRootData,
        externPrelude,
        directDependenciesDefMaps,
        allDependenciesDefMaps,
        prelude,
        CrateMetaData(crate),
        crate.toString()
    )

    val collector = ModCollector(crateRootData, defMap, crateRootData, context, calculateHash = true)
    createExternCrateStdImport(crateRoot, crateRootData)?.let {
        context.imports += it
        collector.importExternCrateMacros(it.usePath)
    }
    collector.collectFile(crateRoot)

    removeInvalidImportsAndMacroCalls(defMap, context)
    // This is a workaround for some real-project cases. See:
    // - [RsUseResolveTest.`test import adds same name as existing`]
    // - https://github.com/rust-lang/cargo/blob/875e0123259b0b6299903fe4aea0a12ecde9324f/src/cargo/util/mod.rs#L23
    context.imports.sortWith(
        // todo profile & optimize
        compareByDescending<Import> { it.nameInScope in it.containingMod.visibleItems }
            .thenBy { it.isGlob }
            .thenByDescending { it.containingMod.path.segments.size }  // imports from nested modules first
    )
    return defMap
}

private fun getInitialExternPrelude(
    crate: Crate,
    crateRoot: RsFile,
    dependenciesDefMaps: Map<Crate, CrateDefMap>
): MutableMap<String, ModData> {
    val attributes = crateRoot.attributes
    val shouldRemoveCore = attributes === RsFile.Attributes.NO_CORE
    val shouldRemoveStd = attributes === RsFile.Attributes.NO_STD || shouldRemoveCore
    return crate.dependencies
        .filterNot {
            shouldRemoveStd && it.normName === STD || shouldRemoveCore && it.normName === CORE
        }
        .mapNotNull {
            val defMap = dependenciesDefMaps[it.crate] ?: return@mapNotNull null
            it.normName to defMap.root
        }
        .toMap(hashMapOf())
}

/**
 * "Invalid" means it belongs to [ModData] which is no longer accessible from `defMap.root` using [ModData.childModules]
 * It could happen if there is cfg-disabled module, which we collect first (with its imports)
 * And then cfg-enabled module overrides previously created [ModData]
 */
private fun removeInvalidImportsAndMacroCalls(defMap: CrateDefMap, context: CollectorContext) {
    fun ModData.descendantsMods(): Sequence<ModData> =
        sequenceOf(this) + childModules.values.asSequence().flatMap { it.descendantsMods() }

    val allMods = defMap.root.descendantsMods().toSet()
    context.imports.removeIf { it.containingMod !in allMods }
    context.macroCalls.removeIf { it.containingMod !in allMods }
}

class ModCollector(
    private val modData: ModData,
    private val defMap: CrateDefMap,
    private val crateRoot: ModData,
    private val context: CollectorContext,
    private val macroDepth: Int = 0,
    private val calculateHash: Boolean,
    parentHashCalculator: HashCalculator? = null,
    /**
     * called when new [RsItemElement] is found
     * default behaviour: just add it to [ModData.visibleItems]
     * behaviour when processing expanded items:
     * add it to [ModData.visibleItems] and propagate to modules which have glob import from [ModData]
     */
    private val onAddItem: (ModData, String, PerNs) -> Unit =
        { containingMod, name, perNs -> containingMod.addVisibleItem(name, perNs) }
) : ModVisitor {

    private var hashCalculator: HashCalculator? = parentHashCalculator

    private val crate: Crate get() = context.crate
    private val project: Project get() = context.project

    fun collectFile(file: RsFile) {
        if (calculateHash) {
            hashCalculator = HashCalculator()
        }
        collectMod(file)
        if (calculateHash) {
            val fileHash = hashCalculator!!.getFileHash()
            defMap.addVisitedFile(file, modData, fileHash)
        }

        if (isUnitTestMode) {
            modData.checkChildModulesAndVisibleItemsConsistency()
        }
    }

    private fun collectMod(mod: RsMod) {
        val visitor = if (calculateHash) {
            val hashVisitor = hashCalculator!!.getVisitor(crate, modData.fileRelativePath)
            CompositeModVisitor(hashVisitor, this)
        } else {
            this
        }
        ModCollectorBase.collectMod(mod, modData.isEnabledByCfg, visitor, crate)
    }

    override fun collectImport(import: ImportLight) {
        context.imports += Import(
            containingMod = modData,
            usePath = import.usePath,
            nameInScope = import.nameInScope,
            visibility = convertVisibility(import.visibility, import.isEnabledByCfg),
            isGlob = import.isGlob,
            isExternCrate = import.isExternCrate,
            isMacroUse = import.isPrelude
        )

        if (import.isEnabledByCfg && import.isExternCrate && import.isMacroUse) {
            importExternCrateMacros(import.usePath)
        }
    }

    // `#[macro_use] extern crate <name>;` - import macros
    fun importExternCrateMacros(externCrateName: String) {
        val externCrateDefMap = defMap.resolveExternCrateAsDefMap(externCrateName)
        if (externCrateDefMap != null) {
            defMap.importAllMacrosExported(externCrateDefMap)
        }
    }

    override fun collectItem(item: ItemLight, itemPsi: RsItemElement) {
        val name = item.name
        if (itemPsi !is RsNamedElement) return

        // could be null if `.resolve()` on `RsModDeclItem` returns null
        val childModData = tryCollectChildModule(item, itemPsi)

        val visItem = convertToVisItem(item, itemPsi) ?: return
        val perNs = PerNs(visItem, item.namespaces)
        if (visItem.isModOrEnum && childModData === null) {
            perNs.types = null
            if (perNs.isEmpty) return
        }
        onAddItem(modData, name, perNs)

        // we have to check `modData[name]` to be sure that `childModules` and `visibleItems` are consistent
        if (childModData != null && perNs.types === modData[name].types) {
            modData.childModules[name] = childModData
        }
    }

    // todo причём здесь RsFile ?
    /** [name] passed for performance reason, because [RsFile.modName] is slow */
    private fun convertToVisItem(item: ItemLight, itemPsi: RsItemElement): VisItem? {
        val visibility = convertVisibility(item.visibility, item.isEnabledByCfg)
        val itemPath = modData.path.append(item.name)
        val isModOrEnum = itemPsi is RsMod || itemPsi is RsModDeclItem || itemPsi is RsEnumItem
        return VisItem(itemPath, visibility, isModOrEnum)
    }

    private fun tryCollectChildModule(itemLight: ItemLight, item: RsItemElement): ModData? {
        if (item is RsEnumItem) return collectEnumAsModData(itemLight, item)

        val (childMod, hasMacroUse, pathAttribute) = when (item) {
            is RsModItem -> Triple(item, item.hasMacroUse, item.pathAttribute)
            is RsModDeclItem -> {
                val (childMod, childModPossiblePaths) = item.resolveAndGetPossiblePaths(modData, project)
                    ?: return null
                if (childMod === null) {
                    defMap.missedFiles += childModPossiblePaths
                    return null
                }
                Triple(childMod as RsMod, item.hasMacroUse, item.pathAttribute)
            }
            else -> return null
        }
        val isEnabledByCfg = itemLight.isEnabledByCfg
        val childModData = collectChildModule(childMod, itemLight.name, isEnabledByCfg, pathAttribute)
        if (hasMacroUse && isEnabledByCfg) modData.legacyMacros += childModData.legacyMacros
        return childModData
    }

    /**
     * We have to pass [childModName], because we can't use [RsMod.modName] -
     * if mod declaration is expanded from macro, then [RsFile.declaration] will be null
     */
    private fun collectChildModule(
        childMod: RsMod,
        childModName: String,
        isEnabledByCfg: Boolean,
        pathAttribute: String?
    ): ModData {
        context.indicator.checkCanceled()
        val childModPath = modData.path.append(childModName)
        val (fileId, fileRelativePath) = if (childMod is RsFile) {
            childMod.virtualFile.fileId to ""
        } else {
            modData.fileId to "${modData.fileRelativePath}::$childModName"
        }
        val childModData = ModData(
            parent = modData,
            crate = modData.crate,
            path = childModPath,
            isEnabledByCfg = isEnabledByCfg,
            fileId = fileId,
            fileRelativePath = fileRelativePath,
            ownedDirectoryId = childMod.getOwnedDirectory(modData, pathAttribute)?.virtualFile?.fileId
        )
        // todo не делать если вызывается из expandMacros ?
        childModData.legacyMacros += modData.legacyMacros

        val collector = ModCollector(
            modData = childModData,
            defMap = defMap,
            crateRoot = crateRoot,
            context = context,
            calculateHash = calculateHash || childMod is RsFile,
            parentHashCalculator = hashCalculator
        )
        if (childMod is RsFile) {
            collector.collectFile(childMod)
        } else {
            collector.collectMod(childMod)
        }
        return childModData
    }

    private fun collectEnumAsModData(enum: ItemLight, enumPsi: RsEnumItem): ModData {
        val enumName = enum.name
        val enumPath = modData.path.append(enumName)
        val enumData = ModData(
            parent = modData,
            crate = modData.crate,
            path = enumPath,
            isEnabledByCfg = enum.isEnabledByCfg,
            fileId = modData.fileId,
            fileRelativePath = "${modData.fileRelativePath}::$enumName",
            ownedDirectoryId = modData.ownedDirectoryId,  // actually can use any value here
            isEnum = true
        )
        for (variantPsi in enumPsi.variants) {
            val variantName = variantPsi.name ?: continue
            val variantPath = enumPath.append(variantName)
            val variantVisibility = if (enumData.isEnabledByCfg) Visibility.Public else Visibility.CfgDisabled
            val variant = VisItem(variantPath, variantVisibility)
            enumData.visibleItems[variantName] = PerNs(variant, variantPsi.namespaces)
        }
        return enumData
    }

    override fun collectMacroCall(call: MacroCallLight, callPsi: RsMacroCall) {
        check(modData.isEnabledByCfg) { "for performance reasons cfg-disabled macros should not be collected" }
        val bodyHash = callPsi.bodyHash
        if (bodyHash === null && call.path != "include") return
        val macroDef = if (call.path.contains("::")) null else modData.legacyMacros[call.path]
        val dollarCrateMap = callPsi.getUserData(RESOLVE_RANGE_MAP_KEY) ?: RangeMap.EMPTY
        context.macroCalls += MacroCallInfo(modData, call.path, call.body, bodyHash, macroDepth, macroDef, dollarCrateMap)
    }

    override fun collectMacroDef(def: MacroDefLight, defPsi: RsMacro) {
        val bodyHash = defPsi.bodyHash ?: return
        val macroPath = modData.path.append(def.name)

        val defInfo = MacroDefInfo(modData.crate, macroPath, def.macroBody, bodyHash, def.hasLocalInnerMacros)
        modData.legacyMacros[def.name] = defInfo

        if (def.hasMacroExport) {
            val visItem = VisItem(macroPath, Visibility.Public)
            val perNs = PerNs(macros = visItem)
            onAddItem(crateRoot, def.name, perNs)
        }
    }

    private fun convertVisibility(visibility: VisibilityLight, isEnabledByCfg: Boolean): Visibility {
        if (!isEnabledByCfg) return Visibility.CfgDisabled
        return when (visibility) {
            VisibilityLight.Public -> Visibility.Public
            is VisibilityLight.Restricted -> resolveRestrictedVisibility(visibility.inPath, crateRoot, modData)
        }
    }
}

private fun createExternCrateStdImport(crateRoot: RsFile, crateRootData: ModData): Import? {
    // Rust injects implicit `extern crate std` in every crate root module unless it is
    // a `#![no_std]` crate, in which case `extern crate core` is injected. However, if
    // there is a (unstable?) `#![no_core]` attribute, nothing is injected.
    //
    // https://doc.rust-lang.org/book/using-rust-without-the-standard-library.html
    // The stdlib lib itself is `#![no_std]`, and the core is `#![no_core]`
    val name = when (crateRoot.attributes) {
        RsFile.Attributes.NONE -> STD
        RsFile.Attributes.NO_STD -> CORE
        RsFile.Attributes.NO_CORE -> return null
    }
    return Import(
        crateRootData,
        name,
        nameInScope = if (crateRoot.edition === EDITION_2015) name else "_",
        visibility = Visibility.Restricted(crateRootData),
        isExternCrate = true,
        isMacroUse = true
    )
}

// https://doc.rust-lang.org/reference/visibility-and-privacy.html#pubin-path-pubcrate-pubsuper-and-pubself
private fun resolveRestrictedVisibility(
    pathText: String,
    crateRoot: ModData,
    containingMod: ModData
): Visibility.Restricted {
    val segments = pathText.split("::")
    val initialModData = when (segments.first()) {
        "super", "self" -> containingMod
        else -> crateRoot
    }
    val pathTarget = segments
        .fold(initialModData) { modData, segment ->
            val nextModData = when (segment) {
                "self" -> modData
                "super" -> modData.parent
                else -> modData.childModules[segment]
            }
            nextModData ?: return Visibility.Restricted(crateRoot)
        }
    return Visibility.Restricted(pathTarget)
}

private fun ModData.checkChildModulesAndVisibleItemsConsistency() {
    for ((name, childMod) in childModules) {
        assertEquals(name, childMod.name, "Inconsistent name of $childMod")
        check(visibleItems[name]?.types?.isModOrEnum === true)
        { "Inconsistent `visibleItems` and `childModules` in $this for name $name" }
    }
}

private fun ModData.getOwnedDirectory(project: Project): PsiDirectory? {
    val ownedDirectoryId = ownedDirectoryId ?: return null
    return PersistentFS.getInstance()
        .findFileById(ownedDirectoryId)
        ?.toPsiDirectory(project)
}

private fun ModData.asPsiFile(project: Project): PsiFile? =
    PersistentFS.getInstance()
        .findFileById(fileId)
        ?.toPsiFile(project)
        ?: run {
            RESOLVE_LOG.error("Can't find PsiFile for $this")
            return null
        }

private fun RsModDeclItem.resolveAndGetPossiblePaths(containingModData: ModData, project: Project): Pair<RsFile?, List<Path>>? {
    val pathAttribute = pathAttribute
    val (parentDirectory, names) = if (pathAttribute === null) {
        val name = name ?: return null
        val parentDirectory = containingModData.getOwnedDirectory(project) ?: return null
        val names = listOf("$name.rs", "$name/mod.rs")
        parentDirectory to names
    } else {
        // https://doc.rust-lang.org/reference/items/modules.html#the-path-attribute
        val parentDirectory = if (containingModData.isRsFile) {
            // For path attributes on modules not inside inline module blocks,
            // the file path is relative to the directory the source file is located.
            val containingMod = containingModData.asPsiFile(project) ?: return null
            containingMod.parent
        } else {
            // Paths for path attributes inside inline module blocks are relative to
            // the directory of file including the inline module components as directories.
            containingModData.getOwnedDirectory(project)
        } ?: return null
        val explicitPath = FileUtil.toSystemIndependentName(pathAttribute)
        parentDirectory to listOf(explicitPath)
    }

    val file = names
        .mapNotNull {
            parentDirectory.virtualFile
                .findFileByMaybeRelativePath(it)
                ?.toPsiFile(project)
                ?.rustFile
        }
        .singleOrNull()
    val paths = names.map { parentDirectory.virtualFile.pathAsPath.resolve(it) }
    return Pair(file, paths)
}

// todo remove
/**
 * We have to use our own resolve for [RsModDeclItem],
 * because sometimes we can't find `containingMod` to set as their `context`,
 * thus default resolve will not work.
 * See [RsMacroExpansionResolveTest.`test mod declared with macro inside inline expanded mod`]
 */
private fun RsModDeclItem.resolve(modData: ModData, project: Project): RsFile? {
    val name = name ?: return null
    val containingModOwnedDirectory = modData.getOwnedDirectory(project)
    val contextualFile = modData.asPsiFile(project) ?: return null
    val modDeclData = RsModDeclItemData(
        project = project,
        name = name,
        referenceName = name,
        pathAttribute = pathAttribute,
        isLocal = false,
        containingModOwnedDirectory = containingModOwnedDirectory,
        containingModName = if (modData.isCrateRoot) "" /* will not be used */ else modData.name,
        containingModIsFile = modData.isRsFile,
        contextualFile = contextualFile,
        inCrateRoot = lazy(LazyThreadSafetyMode.NONE) { modData.isCrateRoot }
    )
    val files = collectResolveVariants(name) {
        processModDeclResolveVariants(modDeclData, it)
    }
    return files.singleOrNull() as RsFile?
}

/**
 * Have to pass [pathAttribute], because [RsFile.pathAttribute] triggers resolve.
 * See also: [RsMod.getOwnedDirectory]
 */
private fun RsMod.getOwnedDirectory(parentMod: ModData, pathAttribute: String?): PsiDirectory? {
    if (this is RsFile && name == RsConstants.MOD_RS_FILE) return parent

    val (parentDirectory, path) = if (pathAttribute != null) {
        when {
            this is RsFile -> return parent
            parentMod.isRsFile -> parentMod.asPsiFile(project)?.parent to pathAttribute
            else -> parentMod.getOwnedDirectory(project) to pathAttribute
        }
    } else {
        parentMod.getOwnedDirectory(project) to name
    }
    if (parentDirectory === null || path === null) return null

    // Don't use `FileUtil#getNameWithoutExtension` to correctly process relative paths like `./foo`
    val directoryPath = FileUtil.toSystemIndependentName(path).removeSuffix(".${RsFileType.defaultExtension}")
    return parentDirectory.virtualFile
        .findFileByMaybeRelativePath(directoryPath)
        ?.let(parentDirectory.manager::findDirectory)
}
