package org.rust.lang.core.resolve2

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.EmptyProgressIndicator
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.containingCrate
import org.rust.openapiext.psiFile

class RsRebuildDefMapAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = e.dataContext.psiFile as? RsFile ?: return
        val crateId = file.containingCrate?.id ?: return
        val project = file.project
        val holder = project.defMapService.getDefMapHolder(crateId)
        holder.shouldRebuild = true
        updateDefMapForAllCrates(project, SingleThreadExecutor(), EmptyProgressIndicator(), async = false)
    }
}
