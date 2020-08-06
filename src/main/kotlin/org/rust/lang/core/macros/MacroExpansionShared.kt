/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.persistent.FlushingDaemon
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.source.tree.FileElement
import com.intellij.psi.stubs.*
import com.intellij.testFramework.ReadOnlyLightVirtualFile
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import com.intellij.util.io.PersistentHashMap
import org.rust.lang.RsFileType
import org.rust.lang.RsLanguage
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsMacro
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.bodyHash
import org.rust.lang.core.stubs.RsFileStub
import org.rust.stdext.HashCode
import java.io.DataInput
import java.io.DataOutput
import java.nio.file.Path
import java.util.*

@Suppress("UnstableApiUsage")
@Service
class MacroExpansionShared : Disposable {
    private val expansions: PersistentHashMap<HashCode, ExpansionResult> = persistentHashMap(
        getBaseMacroDir().resolve("cache").resolve("expansion-cache"),
        HashCodeKeyDescriptor,
        ExpansionResultExternalizer,
        1 * 1024 * 1024,
        MacroExpander.EXPANDER_VERSION
    )

    private val sm: SerializationManagerEx = SerializationManagerEx.getInstanceEx() // SerializationManagerImpl(getBaseMacroDir().resolve("expansion-stubs-cache.names"), true)
    private val ex: StubForwardIndexExternalizer<*> = StubForwardIndexExternalizer.createFileLocalExternalizer()

    private val stubs: PersistentHashMap<HashCode, SerializedStubTree> = persistentHashMap(
        getBaseMacroDir().resolve("cache").resolve("expansion-stubs-cache"),
        HashCodeKeyDescriptor,
        SerializedStubTreeDataExternalizer(
            /* includeInputs = */ true,
            sm,
            ex
        ),
        1 * 1024 * 1024,
        MacroExpander.EXPANDER_VERSION + RsFileStub.Type.stubVersion
    )

    private val flusher = FlushingDaemon.everyFiveSeconds {
        expansions.force()
        stubs.force()
    }

    override fun dispose() {
        flusher.cancel(false)
        expansions.close()
        stubs.close()
    }

    fun cachedExpand(expander: MacroExpander, def: RsMacro, call: RsMacroCall): Pair<CharSequence, RangeMap>? {
        val defData = RsMacroDataWithHash(def)
        val callData = RsMacroCallDataWithHash(call)
        return cachedExpand(expander, defData, callData)
    }

    fun cachedExpand(
        expander: MacroExpander,
        def: RsMacroDataWithHash,
        call: RsMacroCallDataWithHash
    ): Pair<CharSequence, RangeMap>? {
        val hash = HashCode.mix(def.bodyHash ?: return null, call.bodyHash ?: return null)
        return cachedExpand(expander, def, call, hash)
    }

    /** Cache passed as optimization for [createExpansionPsi] */
    fun cachedExpand(
        expander: MacroExpander,
        def: RsMacroDataWithHash,
        call: RsMacroCallDataWithHash,
        hash: HashCode
    ): Pair<CharSequence, RangeMap>? {
        val cached: ExpansionResult? = expansions.get(hash)
        return if (cached == null) {
            val result = expander.expandMacroAsText(def.data, call.data) ?: return null
            expansions.put(hash, ExpansionResult(result.first.toString(), result.second))
            result
        } else {
            Pair(cached.text, cached.ranges)
        }
    }

    fun cachedBuildStub(fileContent: FileContent, hash: HashCode): SerializedStubTree? {
        return cachedBuildStub(hash) { fileContent }
    }

    private fun cachedBuildStub(hash: HashCode, fileContent: () -> FileContent?): SerializedStubTree? {
        val cached: SerializedStubTree? = stubs.get(hash)
        return if (cached == null) {
            val stub = StubTreeBuilder.buildStubTree(fileContent() ?: return null) ?: return null
            val serializedStubTree = SerializedStubTree.serializeStub(
                stub,
                sm,
                ex
            )
            stubs.put(hash, serializedStubTree)
            serializedStubTree
        } else {
            cached
        }
    }

    fun createExpansionPsi(
        project: Project,
        expander: MacroExpander,
        def: RsMacroDataWithHash,
        call: RsMacroCallDataWithHash
    ): Pair<RsFile, RangeMap>? {
        val hash = HashCode.mix(def.bodyHash ?: return null, call.bodyHash ?: return null)
        val (text, ranges) = cachedExpand(expander, def, call, hash) ?: return null
        val file = ReadOnlyLightVirtualFile("macro.rs", RsLanguage, text).apply {
            charset = Charsets.UTF_8
        }
        val stub = cachedBuildStub(hash) {
            FileContentImpl(file, text, file.modificationStamp).also { it.project = project }
        } ?: return null

        val psiManager = PsiManager.getInstance(project)
        val viewProvider = SingleRootFileViewProvider(psiManager, file, true, RsFileType)
        val psiFile = RsPreloadedStubPsiFile(viewProvider, StubTree(stub.stub as PsiFileStub<*>))
        return Pair(psiFile, ranges)
    }

    companion object {
        fun getInstance(): MacroExpansionShared = service()

        private fun <K, V> persistentHashMap(
            file: Path,
            keyDescriptor: KeyDescriptor<K>,
            valueExternalizer: DataExternalizer<V>,
            initialSize: Int,
            version: Int
        ): PersistentHashMap<K, V> {
            return IOUtil.openCleanOrResetBroken({
                PersistentHashMap(
                    file,
                    keyDescriptor,
                    valueExternalizer,
                    initialSize,
                    version
                )
            }, file)
        }
    }
}

class RsMacroDataWithHash(val data: RsMacroData, val bodyHash: HashCode?) {
    constructor(def: RsMacro) : this(RsMacroData(def), def.bodyHash)
}

class RsMacroCallDataWithHash(val data: RsMacroCallData, val bodyHash: HashCode?) {
    constructor(call: RsMacroCall) : this(RsMacroCallData(call), call.bodyHash)
}

private class RsPreloadedStubPsiFile(fileViewProvider: FileViewProvider, private val stubTree: StubTree) : RsFile(fileViewProvider) {
    init {
        (stubTree.root as RsFileStub).psi = this
    }
    override fun getStubTree(): StubTree? = stubTree
    override fun getTreeElement(): FileElement? = null
    override fun createFileElement(docText: CharSequence?): FileElement {
        throw UnsupportedOperationException("Switching to AST is forbidden for $this")
    }
    override val containingMod: RsMod
        get() = this
    override val crateRoot: RsMod?
        get() = null
}

object HashCodeKeyDescriptor : KeyDescriptor<HashCode> {
    override fun getHashCode(value: HashCode): Int {
        return value.hashCode()
    }

    override fun isEqual(val1: HashCode?, val2: HashCode?): Boolean {
        return Objects.equals(val1, val2)
    }

    override fun save(out: DataOutput, value: HashCode) {
        out.write(value.toByteArray())
    }

    override fun read(inp: DataInput): HashCode {
        val bytes = ByteArray(HashCode.ARRAY_LEN)
        inp.readFully(bytes)
        return HashCode.fromByteArray(bytes)
    }
}

data class ExpansionResult(
    val text: String,
    val ranges: RangeMap
)

object ExpansionResultExternalizer : DataExternalizer<ExpansionResult> {
    override fun save(out: DataOutput, value: ExpansionResult) {
        IOUtil.writeUTF(out, value.text)
        value.ranges.writeTo(out)
    }

    override fun read(inp: DataInput): ExpansionResult {
        return ExpansionResult(
            IOUtil.readUTF(inp),
            RangeMap.readFrom(inp)
        )
    }
}
