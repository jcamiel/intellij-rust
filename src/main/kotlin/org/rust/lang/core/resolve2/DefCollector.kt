/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.util.SmartList
import com.intellij.util.io.IOUtil
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.*
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.body
import org.rust.lang.core.psi.ext.bodyTextRange
import org.rust.lang.core.psi.ext.stubDescendantsOfTypeStrict
import org.rust.lang.core.resolve.DEFAULT_RECURSION_LIMIT
import org.rust.lang.core.resolve2.ImportType.GLOB
import org.rust.lang.core.resolve2.ImportType.NAMED
import org.rust.lang.core.resolve2.PartialResolvedImport.*
import org.rust.lang.core.resolve2.Visibility.Invisible
import org.rust.openapiext.findFileByMaybeRelativePath
import org.rust.openapiext.pathAsPath
import org.rust.openapiext.testAssert
import org.rust.openapiext.toPsiFile
import org.rust.stdext.HashCode
import java.io.DataOutput

private const val CONSIDER_INDETERMINATE_IMPORTS_AS_RESOLVED: Boolean = false

/** Resolves all imports (and adds to [defMap]) using fixed point iteration algorithm */
class DefCollector(
    private val project: Project,
    private val defMap: CrateDefMap,
    private val context: CollectorContext,
) {
    /**
     * Reversed glob-imports graph, that is
     * for each module (`targetMod`) store all modules which contain glob import to `targetMod`
     */
    private val globImports: MutableMap<ModData, MutableList<Pair<ModData, Visibility>>> = hashMapOf()
    private val unresolvedImports: MutableList<Import> = context.imports
    private val resolvedImports: MutableList<Import> = mutableListOf()

    private val macroCallsToExpand: MutableList<MacroCallInfo> = context.macroCalls

    /**
     * For each module records names which come from glob-imports
     * to determine whether we can override them (usual imports overrides glob-imports)
     */
    // todo optimization: хранить дополнительное поле в ModData ?
    private val fromGlobImport: PerNsGlobImports = PerNsGlobImports()

    /** Created once as optimization */
    private val macroExpander: MacroExpander = MacroExpander(project)
    private val macroExpanderShared: MacroExpansionShared = MacroExpansionShared.getInstance()

    fun collect() {
        var changed = true
        while (changed) {
            resolveImports()
            changed = expandMacros()
        }
    }

    /**
     * Import resolution
     *
     * This is a fixed point algorithm. We resolve imports until no forward progress in resolving imports is made
     */
    private fun resolveImports() {
        var hasChangedImports = true
        while (hasChangedImports) {
            var hasChangedIndeterminateImports = false
            val hasResolvedImports = unresolvedImports.removeIf { import ->
                context.indicator.checkCanceled()
                when (val status = resolveImport(import)) {
                    is Indeterminate -> {
                        if (import.status is Indeterminate && import.status == status) return@removeIf false

                        import.status = status
                        val changed = recordResolvedImport(import)
                        if (changed) hasChangedIndeterminateImports = true
                        if (CONSIDER_INDETERMINATE_IMPORTS_AS_RESOLVED) {
                            resolvedImports.add(import)
                            true
                        } else {
                            false
                        }
                    }
                    is Resolved -> {
                        import.status = status
                        recordResolvedImport(import)
                        resolvedImports.add(import)
                        true
                    }
                    Unresolved -> false
                }
            }
            hasChangedImports = hasResolvedImports || hasChangedIndeterminateImports
        }
    }

    private fun resolveImport(import: Import): PartialResolvedImport {
        if (import.isExternCrate) {
            val res = defMap.resolveExternCrateAsPerNs(import.usePath, import.visibility) ?: return Unresolved
            return Resolved(res)
        }

        val result = defMap.resolvePathFp(
            import.containingMod,
            import.usePath,
            ResolveMode.IMPORT,
            withInvisibleItems = import.visibility.isInvisible
        )
        val perNs = result.resolvedDef

        if (!result.reachedFixedPoint || perNs.isEmpty) return Unresolved

        // for path `mod1::mod2::mod3::foo`
        // if any of `mod1`, ... , `mod3` is from other crate
        // then it means that defMap for that crate is already completely filled
        if (result.visitedOtherCrate) return Resolved(perNs)

        return if (perNs.types != null && perNs.values != null && perNs.macros != null) {
            Resolved(perNs)
        } else {
            Indeterminate(perNs)
        }
    }

    private fun recordResolvedImport(import: Import): Boolean {
        val containingMod = import.containingMod
        val def = when (val status = import.status) {
            is Resolved -> status.perNs
            is Indeterminate -> status.perNs
            Unresolved -> error("expected resoled import")
        }

        if (import.isGlob) {
            val types = def.types ?: return false  // todo log error "glob import {} didn't resolve as type"
            if (!types.isModOrEnum) return false  // todo log error "glob import {} from non-module/enum {}"
            val targetMod = defMap.defDatabase.tryCastToModData(types)!!
            when {
                import.isPrelude -> {
                    defMap.prelude = targetMod
                    return true
                }
                targetMod.crate == defMap.crate -> {
                    // glob import from same crate => we do an initial import,
                    // and then need to propagate any further additions
                    val items = targetMod.getVisibleItems { it.isVisibleFromMod(containingMod) }
                    val changed = update(containingMod, items, import.visibility, GLOB)

                    // record the glob import in case we add further items
                    val globImports = globImports.computeIfAbsent(targetMod) { mutableListOf() }
                    // todo globImports - Set ?
                    // todo if there are two glob imports, we should choose with widest visibility
                    if (globImports.none { (mod, _) -> mod === containingMod }) {
                        globImports += containingMod to import.visibility
                    }
                    return changed
                }
                else -> {
                    // glob import from other crate => we can just import everything once
                    val items = targetMod.getVisibleItems { it.isVisibleFromOtherCrate() }
                    return update(containingMod, items, import.visibility, GLOB)
                }
            }
        } else {
            val name = import.nameInScope

            // extern crates in the crate root are special-cased to insert entries into the extern prelude
            // https://github.com/rust-lang/rust/pull/54658
            if (import.isExternCrate && containingMod.isCrateRoot && name != "_" /* todo */) {
                val externCrateDefMap = defMap.defDatabase.tryCastToModData(def)
                externCrateDefMap?.let { defMap.externPrelude[name] = it }
            }

            val defWithAdjustedVisible = def.mapItems {
                if (it.visibility.isInvisible || it.visibility.isVisibleFromMod(containingMod)) {
                    it
                } else {
                    it.copy(visibility = Invisible)
                }
            }
            return update(containingMod, listOf(name to defWithAdjustedVisible), import.visibility, NAMED)
        }
    }

    /**
     * [resolutions] were added (imported or expanded from macro) to [modData] with [visibility]
     * we update [ModData.visibleItems] and propagate [resolutions] to modules which have glob import from [modData]
     */
    private fun update(
        modData: ModData,
        resolutions: List<Pair<String, PerNs>>,
        visibility: Visibility,
        importType: ImportType
    ): Boolean = updateRecursive(modData, resolutions, visibility, importType, depth = 0)

    private fun updateRecursive(
        modData: ModData,
        resolutions: List<Pair<String, PerNs>>,
        visibility: Visibility,
        importType: ImportType,
        depth: Int
    ): Boolean {
        check(depth <= 100) { "infinite recursion in glob imports!" }

        var changed = false
        for ((name, def) in resolutions) {
            val changedCurrent = if (name != "_") {
                pushResolutionFromImport(modData, name, def.withVisibility(visibility), importType)
            } else {
                // todo `def` не обязательно является трейтом - добавить `isTrait: Boolean` в `VisItem` ?
                // todo `withVisibility` ?
                pushTraitResolutionFromImport(modData, def)
            }

            if (changedCurrent) changed = true
        }
        if (!changed) return changed

        val globImports = globImports[modData] ?: return changed
        for ((globImportingMod, globImportVis) in globImports) {
            // we know all resolutions have the same `visibility`, so we just need to check that once
            if (!visibility.isVisibleFromMod(globImportingMod)) continue
            updateRecursive(globImportingMod, resolutions, globImportVis, GLOB, depth + 1)
        }
        return changed
    }

    private fun pushResolutionFromImport(modData: ModData, name: String, def: PerNs, importType: ImportType): Boolean {
        check(!def.isEmpty)

        // optimization: fast path
        val defExisting = modData.visibleItems.putIfAbsent(name, def)
        if (defExisting === null) {
            if (importType === GLOB) {
                val modDataToName = modData to name
                if (def.types != null) fromGlobImport.types += modDataToName
                if (def.values != null) fromGlobImport.values += modDataToName
                if (def.macros != null) fromGlobImport.macros += modDataToName
            }
            return true
        }

        return mergeResolutionFromImport(modData, name, def, defExisting, importType)
    }

    private fun mergeResolutionFromImport(
        modData: ModData,
        name: String,
        def: PerNs,
        defExisting: PerNs,
        importType: ImportType
    ): Boolean {
        // todo make inline
        fun pushOneNs(
            visItem: VisItem?,
            visItemExisting: VisItem?,
            setVisItem: (VisItem) -> Unit,
            fromGlobImport: MutableSet<Pair<ModData, String>>
        ): Boolean {
            if (visItem === null) return false

            val importTypeExisting = if (fromGlobImport.contains(modData to name)) GLOB else NAMED
            val changed = when {
                importType === NAMED && importTypeExisting === GLOB || visItemExisting === null -> true
                importType === GLOB && importTypeExisting === NAMED -> false
                importType === importTypeExisting ->
                    visItem.visibility.isStrictMorePermissive(visItemExisting.visibility)
                else -> error("unreachable")
            }

            if (changed) {
                setVisItem(visItem)
                when (importType) {
                    NAMED -> fromGlobImport.remove(modData to name)
                    GLOB -> fromGlobImport.add(modData to name)
                }
            }
            return changed
        }

        // todo refactor
        val changedTypes = pushOneNs(def.types, defExisting.types, { defExisting.types = it }, fromGlobImport.types)
        val changedValues = pushOneNs(def.values, defExisting.values, { defExisting.values = it }, fromGlobImport.values)
        val changedMacros = pushOneNs(def.macros, defExisting.macros, { defExisting.macros = it }, fromGlobImport.macros)
        return changedTypes || changedValues || changedMacros
        // todo else push to `unresolved` ?
    }

    private fun pushTraitResolutionFromImport(modData: ModData, def: PerNs): Boolean {
        check(!def.isEmpty)
        val trait = def.types?.takeIf { !it.isModOrEnum } ?: return false
        val oldVisibility = modData.unnamedTraitImports[trait.path]
        if (oldVisibility === null || trait.visibility.isStrictMorePermissive(oldVisibility)) {
            modData.unnamedTraitImports[trait.path] = trait.visibility
            return true
        }
        return false
    }

    private fun expandMacros(): Boolean {
        // todo refactor (use for loop with indexes?)
        val macroCallsCurrent = macroCallsToExpand.toMutableList()
        macroCallsToExpand.clear()
        val changed = macroCallsCurrent.removeIf { call ->
            context.indicator.checkCanceled()
            if (call.path == "include") {
                expandIncludeMacroCall(call)
                return@removeIf true
            }
            tryExpandMacroCall(call)
        }
        macroCallsToExpand.addAll(macroCallsCurrent)
        return changed
    }

    private fun tryExpandMacroCall(call: MacroCallInfo): Boolean {
        val legacyMacroDef = call.macroDef
        val def = legacyMacroDef ?: run {
            val perNs = defMap.resolvePathFp(
                call.containingMod,
                call.path,
                ResolveMode.OTHER,
                withInvisibleItems = false  // because we expand only cfg-enabled macros
            )
            val defItem = perNs.resolvedDef.macros ?: return false
            defMap.defDatabase.getMacroInfo(defItem)
        }
        val defData = RsMacroDataWithHash(RsMacroData(def.body), def.bodyHash)
        val callData = RsMacroCallDataWithHash(RsMacroCallData(call.body), call.bodyHash)
        return runReadAction {
            val useExpansionCache = true
            val (expandedText, expandedFile, ranges) = if (useExpansionCache) {
                val (expandedFile, ranges) = macroExpanderShared.createExpansionPsi(project, macroExpander, defData, callData)
                    ?: return@runReadAction true
                Triple(expandedFile.text, expandedFile, ranges)
            } else {
                val psiFactory = RsPsiFactory(project)
                val (expandedText, ranges) = macroExpander.expandMacroAsText(defData.data, callData.data)
                    ?: return@runReadAction true
                val expansion = parseExpandedTextWithContext(MacroExpansionContext.ITEM, psiFactory, expandedText)
                    ?: return@runReadAction true
                Triple(expandedText, expansion.file, ranges)
            }

            processDollarCrate(call, def, expandedText, ranges, expandedFile)

            // Note: we don't need to call [RsExpandedElement.setContext] for [expansion.elements],
            // because it is needed only for [RsModDeclItem], and we use our own resolve for [RsModDeclItem]

            processExpandedItems(call.containingMod, expandedFile, call.depth + 1, calculateHash = false)
            true
        }
    }

    /**
     * Алгоритм: В [MacroDefInfo] и [MacroCallInfo] поддерживаем мапку из позиции в тексте
     * (на которой начинается IntellijRustDollarCrate) в CrateId.
     * Когда раскрываем macro call, хотим в полученном [expandedText]
     * для каждого вхождения IntellijRustDollarCrate найти CrateId.
     * Возможны три варианта, откуда пришёл IntellijRustDollarCrate:
     *   - $crate из самого макроса (macro_rules) - знаем нужный crateId
     *   - IntellijRustDollarCrate из самого макроса (macro_rules) - используем мапку из [MacroDefInfo]
     *   - IntellijRustDollarCrate из macro call - используем мапку из [MacroCallInfo]
     */
    private fun processDollarCrate(
        call: MacroCallInfo,
        def: MacroDefInfo,
        expandedText: CharSequence,
        ranges: RangeMap,  // between `call.body` and `expandedText`
        file: RsFile
    ) {
        val occurrencesInFile = MACRO_DOLLAR_CRATE_IDENTIFIER_REGEX.findAll(expandedText).map { it.range.first }
        // для каждого occurrence IntellijRustDollarCrate в `expandedText` ищем crateId
        val rangesInFile = occurrencesInFile.associateWith { indexInExpandedText ->
            val indexInCallBody = ranges.mapOffsetFromExpansionToCallBody(indexInExpandedText)
            val crateId: CratePersistentId = if (indexInCallBody != null) {
                testAssert {
                    val fragmentInCallBody = call.body
                        .subSequence(indexInCallBody, indexInCallBody + MACRO_DOLLAR_CRATE_IDENTIFIER.length)
                    fragmentInCallBody == MACRO_DOLLAR_CRATE_IDENTIFIER
                }
                call.dollarCrateMap.mapOffsetFromExpansionToCallBody(indexInCallBody)
                // todo only log error
                    ?: error("Unexpected macro expansion. Macro call: '$call', expansion: '$expandedText'")
            } else {
                // TODO: Нужно использовать RangeMap между expandedText и телом макроса (macro_rules)
                //  в теле макроса может быть IntellijRustDollarCrate (а не только $crate)
                def.crate
            }
            crateId
        }
        if (rangesInFile.isEmpty() && !def.hasLocalInnerMacros) return

        // todo: мб лучше вместо цикла по всем descendantsOfType перебирать rangesInFile и явно искать top level element ?
        //     - для путей внутри RsUseItem и RsMacroCall можно и не искать, просто использовать их userData
        //     - для RsMacroCall body можно делать parentOfType<RsMacroCall>()
        //  нельзя - потому что findElementBy(offset) работает на psi а не на stubs
        // todo: оптимизация - написать свой stubDescendantsOfTypeStrict, которой не заходит внутрь RsItemElement
        //  то есть обрабатывает top level items и заходит внутри RsMod
        // todo оптимизация: mapOffsetFromExpansionToCallBody работает за линию, мб можно за log
        for (element in file.stubDescendantsOfTypeStrict<RsElement>()) {
            if (rangesInFile.isNotEmpty()) {
                processDollarCrateInsideExpandedElement(element, rangesInFile)
            }

            if (def.hasLocalInnerMacros && element is RsMacroCall) {
                val path = element.path
                val pathOffsetInExpansion = path.stub.startOffset
                val pathOffsetInCall = ranges.mapOffsetFromExpansionToCallBody(pathOffsetInExpansion)
                val isExpandedFromDef = pathOffsetInCall === null
                if (isExpandedFromDef) {
                    path.putUserData(RESOLVE_LOCAL_INNER_MACROS_CRATE_ID_KEY, def.crate)
                }
            }
        }
    }

    // нас интересуют три типа RsExpandedElement:
    // - UseItem - если начинается с $crate
    // - MacroCall - если начинается с $crate или если body содержит $crate
    // - Macro - если body содержит $crate
    private fun processDollarCrateInsideExpandedElement(element: RsElement, rangesInFile: Map<Int, CratePersistentId>) {
        fun filterRangesInside(range: TextRange): Map<Int, CratePersistentId> =
            rangesInFile.filterKeys { indexInFile ->
                val rangeInFile = TextRange(indexInFile, indexInFile + MACRO_DOLLAR_CRATE_IDENTIFIER.length)
                range.contains(rangeInFile)
            }

        when (element) {
            is RsUseItem -> {
                // expandedText = 'use $crate::foo;'
                // TODO: `use {$crate::foo, $crate::bar};` - `$crate` may come from different macros
                val crateId = element.stubDescendantsOfTypeStrict<RsPath>()
                    .mapNotNull { rangesInFile[it.stub.startOffset] }
                    .distinct().singleOrNull()
                    ?: return
                element.putUserData(RESOLVE_DOLLAR_CRATE_ID_KEY, crateId)
            }
            is RsMacroCall -> {
                // expandedText = 'foo! { ... $crate ... }'
                run {
                    val macroRangeInFile = element.bodyTextRange ?: return@run
                    val rangesInMacro = filterRangesInside(macroRangeInFile)
                        .map { (indexInFile, crateId) ->
                            val indexInMacro = indexInFile - macroRangeInFile.startOffset
                            MappedTextRange(crateId, indexInMacro, MACRO_DOLLAR_CRATE_IDENTIFIER.length)
                        }
                    if (rangesInMacro.isEmpty()) return@run
                    element.putUserData(RESOLVE_RANGE_MAP_KEY, RangeMap.from(SmartList(rangesInMacro)))
                }

                // expandedText = '$crate::foo! { ... }'
                run {
                    val crateId = rangesInFile[element.path.stub.startOffset] ?: return@run
                    element.path.putUserData(RESOLVE_DOLLAR_CRATE_ID_KEY, crateId)
                }
            }
        }
    }

    private fun expandIncludeMacroCall(call: MacroCallInfo) {
        val modData = call.containingMod
        val containingFile = PersistentFS.getInstance().findFileById(modData.fileId) ?: return
        val includePath = call.body
        val parentDirectory = containingFile.parent
        runReadAction {
            val includingFile = parentDirectory
                .findFileByMaybeRelativePath(includePath)
                ?.toPsiFile(project)
                ?.rustFile
            if (includingFile != null) {
                processExpandedItems(modData, includingFile, call.depth + 1, calculateHash = true)
            } else {
                val filePath = parentDirectory.pathAsPath.resolve(includePath)
                defMap.missedFiles.add(filePath)
            }
        }
    }

    private fun processExpandedItems(
        containingMod: ModData,
        expandedFile: RsFile,
        macroDepth: Int,
        calculateHash: Boolean
    ) {
        if (macroDepth > DEFAULT_RECURSION_LIMIT) return

        val onAddItem: (ModData, String, PerNs) -> Unit = { modData, name, perNs ->
            val visibility = (perNs.types ?: perNs.values ?: perNs.macros)!!.visibility
            update(modData, listOf(name to perNs), visibility, NAMED)
        }
        val collector = ModCollector(
            modData = containingMod,
            defMap = defMap,
            crateRoot = defMap.root,
            context = context,
            macroDepth = macroDepth,
            calculateHash = calculateHash,
            onAddItem = onAddItem
        )
        collector.collectFile(expandedFile)
    }
}

private class PerNsGlobImports {
    val types: MutableSet<Pair<ModData, String>> = hashSetOf()
    val values: MutableSet<Pair<ModData, String>> = hashSetOf()
    val macros: MutableSet<Pair<ModData, String>> = hashSetOf()
}

data class Import(
    val containingMod: ModData,
    val usePath: String,  // foo::bar::baz
    val nameInScope: String,
    val visibility: Visibility,
    val isGlob: Boolean = false,
    val isExternCrate: Boolean = false,
    // todo remove if unused
    val isMacroUse: Boolean = false,
    val isPrelude: Boolean = false,  // #[prelude_import]
) {
    var status: PartialResolvedImport = Unresolved
}

enum class ImportType { NAMED, GLOB }

sealed class PartialResolvedImport {
    /** None of any namespaces is resolved */
    object Unresolved : PartialResolvedImport()

    /** One of namespaces is resolved */
    data class Indeterminate(val perNs: PerNs) : PartialResolvedImport()

    /** All namespaces are resolved, OR it is came from other crate */
    data class Resolved(val perNs: PerNs) : PartialResolvedImport()
}

class MacroDefInfo(
    val crate: CratePersistentId,
    val path: ModPath,
    val body: RsMacroBody,
    val bodyHash: HashCode,
    val hasLocalInnerMacros: Boolean,
)

class MacroCallInfo(
    val containingMod: ModData,
    val path: String,
    val body: String,
    val bodyHash: HashCode?,  // null for `include!` macro
    val depth: Int,
    val macroDef: MacroDefInfo?,  // for textual scoped macros
    /**
     * `srcOffset` - [CratePersistentId]
     * `dstOffset` - index of [MACRO_DOLLAR_CRATE_IDENTIFIER] in [body]
     */
    val dollarCrateMap: RangeMap = RangeMap.EMPTY,
) {
    override fun toString(): String = "${containingMod.path}:  $path! { $body }"

    fun writeTo(data: DataOutput) {
        containingMod.path.writeTo(data, withCrate = false)
        IOUtil.writeUTF(data, path)
        IOUtil.writeUTF(data, body)
    }
}

// todo переместить куда-нибудь
/** For each expanded [RsMacroCall] we store [RangeMap] - see [MacroCallInfo.dollarCrateMap] */
val RESOLVE_RANGE_MAP_KEY: Key<RangeMap> = Key("RESOLVE_RANGE_MAP_KEY")

/**
 * For [RsUseItem] and [RsMacroCall] we store `crateId`,
 * if `path` (path to macro or use path) starts with [MACRO_DOLLAR_CRATE_IDENTIFIER]
 */
val RESOLVE_DOLLAR_CRATE_ID_KEY: Key<CratePersistentId> = Key("RESOLVE_CRATE_ID_KEY")

// todo docs
val RESOLVE_LOCAL_INNER_MACROS_CRATE_ID_KEY: Key<CratePersistentId> = Key("RESOLVE_LOCAL_INNER_MACROS_CRATE_ID_KEY")
