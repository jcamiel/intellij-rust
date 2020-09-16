/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import org.rust.cargo.project.settings.rustSettings
import org.rust.ide.utils.isEnabledByCfg
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.impl.CargoBasedCrate
import org.rust.lang.core.crate.impl.DoctestCrate
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.resolve.ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS
import org.rust.openapiext.toPsiFile

@Suppress("SimplifyBooleanWithConstants")
val Project.isNewResolveEnabled: Boolean
    get() = rustSettings.newResolveEnabled || true

fun shouldUseNewResolveIn(scope: RsMod): Boolean =
    scope.project.isNewResolveEnabled
        && scope.containingCrate is CargoBasedCrate
        && scope.modName != TMP_MOD_NAME
        && !scope.isModInsideItem
        && scope.containingCrate !== null
        && !scope.isShadowedByOtherMod()

private val RsMod.isModInsideItem: Boolean
    // todo `mod foo { mod inner; }` - так можно?)
    get() = this is RsModItem && context !is RsMod

/** "shadowed by other mod" means that [ModData] is not accessible from [CrateDefMap.root] through [ModData.childModules] */
private fun RsMod.isShadowedByOtherMod(): Boolean {
    // todo performance: `getDefMapAndModData` is called twice
    val (_, modData) = project.getDefMapAndModData(this) ?: return false
    val isShadowedByOtherFile = modData.isShadowedByOtherFile

    val isDeeplyEnabledByCfg = (containingFile as RsFile).isDeeplyEnabledByCfg && isEnabledByCfg
    val isShadowedByOtherInlineMod = isDeeplyEnabledByCfg != modData.isDeeplyEnabledByCfg

    return isShadowedByOtherFile || isShadowedByOtherInlineMod
}

fun processItemDeclarations2(
    scope: RsMod,
    ns: Set<Namespace>,
    processor: RsResolveProcessor,
    ipm: ItemProcessingMode  // todo
): Boolean {
    val project = scope.project
    val (defMap, modData) = project.getDefMapAndModData(scope) ?: return false

    modData.visibleItems.processEntriesWithName(processor.name) { name, perNs ->
        // todo inline ?
        fun VisItem.tryConvertToPsi(namespace: Namespace): List<RsNamedElement>? /* null is equivalent to empty list */ {
            if (ipm === WITHOUT_PRIVATE_IMPORTS) {
                when {
                    visibility === Visibility.Invisible -> return null
                    // todo не работает если резолвим path из дочернего модуля ?
                    // todo видимо фильтрация по visibility должна быть на уровне выше ?
                    // visibility is Visibility.Restricted && visibility.inMod === modData -> return null
                }
            }

            return toPsi(defMap.defDatabase, project, namespace)
        }

        val visItems = arrayOf(
            perNs.types to Namespace.Types,
            perNs.values to Namespace.Values,
            perNs.macros to Namespace.Macros,
        )
        // todo profile & optimize
        // We need set here because item could belong to multiple namespaces (e.g. unit struct)
        // Also we need to distinguish unit struct and e.g. mod and function with same name in one module
        val elements = visItems
            .flatMapTo(hashSetOf()) { (visItem, namespace) ->
                if (visItem === null || namespace !in ns) return@flatMapTo emptyList()
                visItem.tryConvertToPsi(namespace) ?: emptyList()
            }
        elements.any { processor(name, it) }
    } && return true

    // todo не обрабатывать отдельно, а использовать `getVisibleItems` ?
    // todo only if `processor.name == null` ?
    if (Namespace.Types in ns) {
        for ((traitPath, traitVisibility) in modData.unnamedTraitImports) {
            val trait = VisItem(traitPath, traitVisibility)
            val traitPsi = trait.toPsi(defMap.defDatabase, project, Namespace.Types)
            traitPsi.any { processor("_", it) } && return true
        }
    }

    if (ipm.withExternCrates && Namespace.Types in ns) {
        defMap.externPrelude.processEntriesWithName(processor.name) { name, externCrateModData ->
            if (modData.visibleItems[name]?.types !== null) return@processEntriesWithName false
            val externCratePsi = externCrateModData
                .asVisItem()
                // todo нет способа попроще?)
                .toPsi(defMap.defDatabase, project, Namespace.Types)
                .singleOrNull() ?: return@processEntriesWithName false
            processor(name, externCratePsi)
        } && return true
    }

    return false
}

fun processMacros(scope: RsMod, processor: RsResolveProcessor): Boolean {
    val project = scope.project
    val (defMap, modData) = project.getDefMapAndModData(scope) ?: return false

    modData.legacyMacros.processEntriesWithName(processor.name) { name, macroInfo ->
        val visItem = VisItem(macroInfo.path, Visibility.Public)
        val macros = visItem.toPsi(defMap.defDatabase, project, Namespace.Macros)
            .singleOrNull()
            ?: return@processEntriesWithName false
        processor(name, macros)
    } && return true

    modData.visibleItems.processEntriesWithName(processor.name) { name, perNs ->
        val macros = perNs.macros?.toPsi(defMap.defDatabase, project, Namespace.Macros)
            ?.singleOrNull()
            ?: return@processEntriesWithName false
        processor(name, macros)
    } && return true
    return false
}

private fun Project.getDefMapAndModData(mod: RsMod): Pair<CrateDefMap, ModData>? {
    val crate = mod.containingCrate ?: return null
    val defMap = getDefMap(crate) ?: return null
    val modData = defMap.getModData(mod) ?: return null
    return Pair(defMap, modData)
}

private fun Project.getDefMap(crate: Crate): CrateDefMap? {
    check(crate !is DoctestCrate) { "doc test crates are not supported by CrateDefMap" }
    if (crate.id === null) return null
    val defMap = defMapService.getOrUpdateIfNeeded(crate)
    if (defMap === null) {
        // todo
        // if (isUnitTestMode) error("DefMap is null for $crate during resolve")
        RESOLVE_LOG.error("DefMap is null for $crate during resolve")
    }
    return defMap
}

// todo make inline? (станет удобнее делать `&& return true`)
private fun <T> Map<String, T>.processEntriesWithName(name: String?, f: (String, T) -> Boolean): Boolean {
    if (name === null) {
        for ((key, value) in this) {
            f(key, value) && return true
        }
        return false
    } else {
        val value = this[name] ?: return false
        return f(name, value)
    }
}

// todo оптимизация: возвращать null вместо emptyList()
private fun VisItem.toPsi(defDatabase: DefDatabase, project: Project, ns: Namespace): List<RsNamedElement> {
    if (isModOrEnum) return path.toRsModOrEnum(defDatabase, project)
    val containingModOrEnum = containingMod.toRsModOrEnum(defDatabase, project).singleOrNull() ?: return emptyList()
    val isEnabledByCfg = isEnabledByCfg
    // todo refactor? (code repetition)
    return when (containingModOrEnum) {
        is RsMod -> {
            if (ns === Namespace.Macros) {
                // todo expandedItemsIncludingMacros
                val macros = containingModOrEnum.itemsAndMacros
                    .filterIsInstance<RsNamedElement>()
                    .filter { (it is RsMacro || it is RsMacro2) && it.name == name && it.isEnabledByCfg == isEnabledByCfg }
                // todo это же корректно только для legacy textual macros? а для macro 2.0 может быть мультирезолв?
                // todo вообще мб сделать отдельный метод для toPsi(ns=Macros), который возвращает не list а single item?
                val macro = macros.lastOrNull()
                listOfNotNull(macro)
            } else {
                containingModOrEnum.expandedItemsExceptImplsAndUses
                    .filterIsInstance<RsNamedElement>()
                    .filter { it.name == name && ns in it.namespaces && it.isEnabledByCfg == isEnabledByCfg }
            }
        }
        is RsEnumItem -> {
            containingModOrEnum.variants
                .filter { it.name == name && ns in it.namespaces && it.isEnabledByCfg == isEnabledByCfg }
        }
        else -> error("Expected mod or enum, got: $containingModOrEnum")
    }
}

private val VisItem.isEnabledByCfg: Boolean get() = visibility !== Visibility.CfgDisabled

// todo multiresolve
private inline fun <reified T : RsElement> List<T>.singleOrCfgEnabled(): T? =
    singleOrNull() ?: singleOrNull { it.isEnabledByCfg }

private fun ModPath.toRsModOrEnum(defDatabase: DefDatabase, project: Project): List<RsNamedElement /* RsMod or RsEnumItem */> {
    val modData = defDatabase.getModData(this) ?: return emptyList()
    return if (modData.isEnum) {
        modData.toRsEnum(project)
    } else {
        val mod = modData.toRsMod(project)
        listOfNotNull(mod)
    }
}

private fun ModData.toRsEnum(project: Project): List<RsEnumItem> {
    if (!isEnum) return emptyList()
    val containingMod = parent?.toRsMod(project) ?: return emptyList()
    val isEnabledByCfg = asVisItem().isEnabledByCfg
    return containingMod.expandedItemsExceptImplsAndUses
        // todo combine `filter` ?
        .filterIsInstance<RsEnumItem>()
        .filter { it.name == path.name && it.isEnabledByCfg == isEnabledByCfg }
}

// todo assert not null / log warning
private fun ModData.toRsMod(project: Project, useExpandedItems /* todo remove (always true) */: Boolean = true): RsMod? {
    if (isEnum) return null
    val file = PersistentFS.getInstance().findFileById(fileId)
        ?.toPsiFile(project) as? RsFile
        ?: return null
    val fileRelativeSegments = fileRelativePath.split("::")
    return fileRelativeSegments
        .subList(1, fileRelativeSegments.size)
        .fold(file as RsMod) { mod, segment ->
            val items = if (useExpandedItems) mod.expandedItemsExceptImplsAndUses else mod.itemsAndMacros.toList()
            items
                .filterIsInstance<RsModItem>()
                .filter { it.modName == segment }
                .singleOrCfgEnabled()
                ?: return null
        }
}
