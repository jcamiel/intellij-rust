/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.rustStructureModificationTracker
import org.rust.openapiext.checkWriteAccessAllowed
import org.rust.openapiext.fileId
import org.rust.openapiext.pathAsPath
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class DefMapHolder(private val project: Project) {
    @Volatile
    var defMap: CrateDefMap? = null

    /** Value of [rustStructureModificationTracker] at the time when [defMap] started to built */
    @Volatile
    private var defMapStamp: Long = -1

    fun hasLatestStamp(): Boolean = !shouldRebuild && defMapStamp == project.structureStamp

    fun setLatestStamp() {
        defMapStamp = project.structureStamp
    }

    fun checkHasLatestStamp() {
        check(hasLatestStamp()) {
            "DefMapHolder must have latest stamp right after DefMap($defMap) was updated. " +
                "$defMapStamp vs ${project.structureStamp}"
        }
    }

    // todo проверить что всё ещё нужен
    @Volatile
    var shouldRebuild: Boolean = true
        set(value) {
            field = value
            if (value) {
                shouldRecheck = false
                changedFiles.clear()
            }
        }

    @Volatile
    var shouldRecheck: Boolean = false
    val changedFiles: MutableSet<RsFile> = hashSetOf()

    override fun toString(): String = "DefMapHolder($defMap, stamp=$defMapStamp)"

    companion object {
        private val Project.structureStamp: Long get() = rustStructureModificationTracker.modificationCount
    }
}

// todo разделить interface и impl
// todo @Synchronized
@Service
class DefMapService(val project: Project) {

    private val defMaps: ConcurrentHashMap<CratePersistentId, DefMapHolder> = ConcurrentHashMap()
    val defMapsBuildLock: Any = Any()

    /**
     * todo store [FileInfo] as values ?
     * See [FileInfo.modificationStamp].
     */
    private val fileModificationStamps: ConcurrentHashMap<FileId, Pair<Long, CratePersistentId>> = ConcurrentHashMap()

    /** Merged map of [CrateDefMap.missedFiles] for all crates */
    private val missedFiles: ConcurrentHashMap<Path, CratePersistentId> = ConcurrentHashMap()

    fun getDefMapHolder(crate: CratePersistentId): DefMapHolder {
        return defMaps.computeIfAbsent(crate) { DefMapHolder(project) }
    }

    fun afterDefMapBuilt(defMap: CrateDefMap) {
        val crate = defMap.crate

        // todo ?
        fileModificationStamps.values.removeIf { (_, it) -> it == crate }
        fileModificationStamps += defMap.fileInfos
            .mapValues { (_, info) -> info.modificationStamp to crate }

        // todo придумать что-нибудь получше вместо removeIf
        //  мб хранить в ключах defMap.modificationStamp и сравнивать его после .get() ?
        missedFiles.values.removeIf { it == crate }
        missedFiles += defMap.missedFiles.associateWith { crate }
    }

    fun onCargoWorkspaceChanged() {
        // todo как-нибудь найти изменённый крейт и установить shouldRecheck только для него ?
        for (defMap in defMaps.values) {
            defMap.shouldRecheck = true
        }
    }

    fun onFileAdded(file: RsFile) {
        checkWriteAccessAllowed()
        val path = file.virtualFile.pathAsPath
        val crate = missedFiles[path] ?: return
        onCrateChanged(crate)
    }

    fun onFileRemoved(file: RsFile) {
        checkWriteAccessAllowed()
        val crate = findCrate(file) ?: return
        onCrateChanged(crate)
    }

    fun onFileChanged(file: RsFile) {
        checkWriteAccessAllowed()
        // todo `containingCrate` вызывает
        //  "IllegalStateException: Accessing indices during PSI event processing can lead to typing performance issues"
        // val crate = file.containingCrate?.id ?: return
        val crate = findCrate(file) ?: return
        getDefMapHolder(crate).changedFiles += file
    }

    private fun findCrate(file: RsFile): CratePersistentId? {
        val fileId = file.virtualFile.fileId
        val (_, crate) = fileModificationStamps[fileId] ?: return null
        return crate
    }

    // todo inline ?
    private fun onCrateChanged(crate: CratePersistentId) {
        getDefMapHolder(crate).shouldRebuild = true
    }

    // todo needed when macro expansion is disabled
    fun rustPsiChanged() {
        if (!project.macroExpansionManager.isMacroExpansionEnabled) {
            scheduleRebuildAllDefMaps()
        }
    }

    fun scheduleRebuildAllDefMaps() {
        for (defMapHolder in defMaps.values) {
            defMapHolder.shouldRebuild = true
        }
    }
}

val Project.defMapService: DefMapService
    get() = service()
