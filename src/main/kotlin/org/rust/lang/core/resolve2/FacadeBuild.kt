/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapiext.isWriteAccessAllowed
import org.rust.RsTask.TaskType.*
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.crateGraph
import org.rust.openapiext.checkReadAccessAllowed
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Returns `null` if [crate] has null `id` or `rootMod`,
 * or if crate should not be indexed (e.g. test/bench non-workspace crate)
 */
/* package-private */ fun buildDefMap(
    crate: Crate,
    dependenciesDefMaps: Map<Crate, CrateDefMap>,
    indicator: ProgressIndicator
): CrateDefMap? {
    // todo если dumbMod, то добавить crate в defMapService.changedCrates?
    //  и запускать updateAllDefMaps когда dumb mode завершится
    checkReadAccessAllowed()
    val project = crate.project
    check(project.isNewResolveEnabled)
    RESOLVE_LOG.info("Building DefMap for $crate")
    val context = CollectorContext(crate, project, indicator)
    val defMap = buildDefMapContainingExplicitItems(context, dependenciesDefMaps) ?: return null
    DefCollector(project, defMap, context).collect()
    project.defMapService.afterDefMapBuilt(defMap)
    return defMap
}

fun DefMapService.getOrUpdateIfNeeded(crate: Crate): CrateDefMap? {
    check(project.isNewResolveEnabled)
    val holder = getDefMapHolder(crate.id ?: return null)

    if (holder.hasLatestStamp()) return holder.defMap

    return runReadAction {
        synchronized(defMapsBuildLock) {
            if (holder.hasLatestStamp()) return@synchronized holder.defMap

            val pool = Executors.newWorkStealingPool()
            // todo проверить чему обычно равен `getGlobalProgressIndicator`
            val indicator = ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator()
            // todo выполнять вне read action ?
            doUpdateDefMapForAllCrates(pool, indicator, async = true)
            if (holder.defMap != null) holder.checkHasLatestStamp()
            return@synchronized holder.defMap
        }
    }
}

/** Called from macro expansion task */
// todo переименовать `async` в `multithread`/`parallel`
fun updateDefMapForAllCrates(project: Project, pool: Executor, indicator: ProgressIndicator, async: Boolean = true) {
    if (!project.isNewResolveEnabled) return
    val defMapService = project.defMapService
    runReadAction {
        synchronized(defMapService.defMapsBuildLock) {
            defMapService.doUpdateDefMapForAllCrates(pool, indicator, async)
        }
    }
}

/** For tests */
fun forceBuildDefMapForAllCrates(project: Project, pool: Executor, indicator: ProgressIndicator, async: Boolean) {
    project.defMapService.scheduleRebuildAllDefMaps()
    updateDefMapForAllCrates(project, pool, indicator, async)
}

/**
 * Possible modifications:
 * - After IDE restart: full recheck (for each crate compare [CrateMetaData] and `modificationStamp` of each file).
 *   Tasks [CARGO_SYNC] and [MACROS_UNPROCESSED] are executed.
 * - File changed: calculate hash and compare with hash stored in [CrateDefMap.fileInfos].
 *   Task [MACROS_WORKSPACE] is executed.
 * - File added: check whether [DefMapService.missedFiles] contains file path
 *   No task executed => we will schedule [MACROS_WORKSPACE]
 * - File deleted: todo
 *   No task executed => we will schedule [MACROS_WORKSPACE]
 * - Unknown file changed: full recheck
 *   No task executed => we will schedule [MACROS_WORKSPACE]
 * - Crate workspace changed: full recheck
 *   Tasks [CARGO_SYNC] and [MACROS_UNPROCESSED] are executed.
 */
private fun DefMapService.doUpdateDefMapForAllCrates(pool: Executor, indicator: ProgressIndicator, async: Boolean) {
    checkReadAccessAllowed()
    // если мы внутри write action - можем использовать только текущий поток (read action в других потоках не запустятся)
    DefMapUpdater(this, pool, indicator, async && !isWriteAccessAllowed).run()
}

private class DefMapUpdater(
    private val defMapService: DefMapService,
    private val pool: Executor,
    private val indicator: ProgressIndicator,
    private val async: Boolean,
) {
    private val topSortedCrates: List<Crate> = defMapService.project.crateGraph.topSortedCrates

    fun run() {
        check(defMapService.project.isNewResolveEnabled)
        if (topSortedCrates.isEmpty()) return
        indicator.checkCanceled()

        val cratesToCheck = findCratesToCheck()
        if (cratesToCheck.isEmpty()) return

        val cratesToUpdate = findCratesToUpdate(cratesToCheck)
        if (cratesToUpdate.isEmpty()) return

        val cratesToUpdateAll = cratesToUpdate.withReversedDependencies()
        val builtDefMaps = getBuiltDefMaps(cratesToUpdateAll)
        val pool = getPool(cratesToUpdateAll.size)
        AsyncDefMapBuilder(defMapService, cratesToUpdateAll, builtDefMaps, indicator, pool).build()
    }

    private fun findCratesToCheck(): List<Pair<Crate, DefMapHolder>> {
        checkReadAccessAllowed()
        val cratesToCheck = mutableListOf<Pair<Crate, DefMapHolder>>()
        for (crate in topSortedCrates) {
            val crateId = crate.id ?: continue
            val holder = defMapService.getDefMapHolder(crateId)
            if (holder.hasLatestStamp() || holder.definitelyShouldNotRebuild()) {
                holder.setLatestStamp()
            } else {
                cratesToCheck += Pair(crate, holder)
            }
        }
        return cratesToCheck
    }

    private fun findCratesToUpdate(cratesToCheck: List<Pair<Crate, DefMapHolder>>): List<Crate> {
        // todo что произойдёт если будет ProcessCheckCancelled ?
        val pool = getPool(cratesToCheck.size)
        return cratesToCheck
            .filterAsync(pool) { (crate, holder) ->
                ourRunReadAction(indicator) {
                    holder.updateShouldRebuild(crate, indicator)
                    val shouldRebuild = holder.shouldRebuild
                    if (!shouldRebuild) holder.setLatestStamp()
                    shouldRebuild
                }
            }
            .map { it.first }
    }

    private fun getBuiltDefMaps(cratesToUpdateAll: Set<Crate>): Map<Crate, CrateDefMap> {
        return topSortedCrates
            .filter { it !in cratesToUpdateAll }
            .mapNotNull {
                val crateId = it.id ?: return@mapNotNull null
                val defMap = defMapService.getDefMapHolder(crateId).defMap ?: return@mapNotNull null
                it to defMap
            }
            .toMap()
    }

    private fun getPool(size: Int) = if (async && size > 1) pool else SingleThreadExecutor()
}

private fun List<Crate>.withReversedDependencies(): Set<Crate> {
    val result = hashSetOf<Crate>()
    fun processCrate(crate: Crate) {
        if (crate.id === null || !result.add(crate)) return
        for (reverseDependency in crate.reverseDependencies) {
            processCrate(reverseDependency)
        }
    }
    for (crate in this) {
        processCrate(crate)
    }
    return result
}

class SingleThreadExecutor : Executor {
    override fun execute(action: Runnable) = action.run()
}

private fun <T> Collection<T>.filterAsync(pool: Executor, predicate: (T) -> Boolean): List<T> {
    val result = ConcurrentLinkedQueue<T>()
    val future = CompletableFuture<Unit>()
    val remainingCount = AtomicInteger(size)

    for (element in this) {
        pool.execute {
            if (future.isCompletedExceptionally) return@execute
            try {
                if (predicate(element)) {
                    result += element
                }
                if (remainingCount.decrementAndGet() == 0) {
                    future.complete(Unit)
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
    }

    future.getWithRethrow()
    return result.toList()
}
