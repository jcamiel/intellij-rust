/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import org.rust.cargo.CfgOptions
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.shouldIndexFile
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.rustFile
import org.rust.openapiext.testAssert
import org.rust.openapiext.toPsiFile

fun DefMapHolder.definitelyShouldNotRebuild(): Boolean =
    !shouldRebuild && !shouldRecheck && changedFiles.isEmpty()

fun DefMapHolder.updateShouldRebuild(crate: Crate, indicator: ProgressIndicator) {
    shouldRebuild = shouldRebuild(crate, indicator)
}

private fun DefMapHolder.shouldRebuild(crate: Crate, indicator: ProgressIndicator): Boolean {
    if (shouldRebuild) return true
    // If `shouldRebuild == false` then `defMap` was built at least once
    // If `defMap` is `null` then [crate] is odd (e.g. with null `id` or `rootMod`) and can be ignored
    val defMap = defMap ?: return false

    // We can just add all crate files to [changedFiles] if [shouldRecheck],
    // but getting all crate files can be slow
    val changedFilesCopy = if (shouldRecheck) changedFiles.toSet() else emptySet()
    processChangedFiles(indicator, crate, defMap) && return true

    if (shouldRecheck) {
        if (isCrateChanged(crate, defMap, indicator)) return true
        changedFiles += defMap.getAllChangedFiles(crate.project, ignoredFiles = changedFilesCopy) ?: return true
        shouldRecheck = false

        processChangedFiles(indicator, crate, defMap) && return true
    }
    return false
}

private fun CrateDefMap.getAllChangedFiles(project: Project, ignoredFiles: Set<RsFile>): List<RsFile>? {
    val persistentFS = PersistentFS.getInstance()
    return fileInfos.mapNotNull { (fileId, fileInfo) ->
        val file = persistentFS
            .findFileById(fileId)
            ?.toPsiFile(project)
            ?.rustFile
            ?: return null  // file was deleted - should rebuilt DefMap
        file.takeIf {
            it !in ignoredFiles
                && it.modificationStampForResolve == fileInfo.modificationStamp
        }
    }
}

private fun DefMapHolder.processChangedFiles(indicator: ProgressIndicator, crate: Crate, defMap: CrateDefMap): Boolean {
    // we are in read action
    // and [changedFiles] are modified only in write action
    val iterator = changedFiles.iterator()
    // we use iterator in order to not lose progress if [isFileChanged] throws [ProcessCanceledException]
    while (iterator.hasNext()) {
        indicator.checkCanceled()
        val file = iterator.next()
        // todo pass `indicator` ?
        if (isFileChanged(file, defMap, crate)) {
            return true
        } else {
            iterator.remove()
        }
    }
    return false
}

data class CrateMetaData(
    val edition: CargoWorkspace.Edition,
    private val features: Collection<CargoWorkspace.Feature>,
    private val cfgOptions: CfgOptions,
    private val env: Map<String, String>,
    // todo store modificationStamp of DefMap for each dependency ?
    private val dependencies: Set<CratePersistentId>,
) {
    constructor(crate: Crate) : this(
        edition = crate.edition,
        features = crate.features,
        cfgOptions = crate.cfgOptions,
        env = crate.env,
        dependencies = crate.flatDependencies.mapNotNull { it.id }.toSet()
    )
}

// todo добавить после построения DefMap `testAssert { !isCrateChanged(...) }`
private fun isCrateChanged(crate: Crate, defMap: CrateDefMap, indicator: ProgressIndicator): Boolean {
    indicator.checkCanceled()  // todo call more often ?

    val crateRootFile = crate.rootModFile ?: return false
    testAssert({ shouldIndexFile(crate.project, crateRootFile) }, { "isCrateChanged should not be called for odd crates" })

    return defMap.metaData != CrateMetaData(crate) || defMap.hasAnyMissedFileCreated()
}

// todo добавить в тест на buildDefMap проверку `missedFiles.isEmpty()`
private fun CrateDefMap.hasAnyMissedFileCreated(): Boolean {
    val fileManager = VirtualFileManager.getInstance()
    return missedFiles.any { fileManager.findFileByNioPath(it) != null }
}
