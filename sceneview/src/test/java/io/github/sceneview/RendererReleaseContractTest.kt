package io.github.sceneview

import io.github.sceneview.utils.SurfaceMirrorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile

/**
 * No renderer of this library is destroyed on the spot: every release waits for an idle backend
 * through [destroyRendererWhenBackendIdle] (#3799).
 *
 * `Engine.destroyRenderer` is not a plain handle release. Filament's `FRenderer::terminate`
 * (v1.72.1 `Renderer.cpp`) first waits, with no bound, for every command already queued on the
 * backend (`Fence::waitAndDestroy(engine.createFence())`). A scene disposed while that queue is
 * long — its shader programs still being linked right after it appeared, say — parks the main
 * thread on the whole backlog, and the ANR report shows `Engine.destroyRenderer` on the main
 * thread. [destroyRendererWhenBackendIdle] polls a fence once per frame instead and releases the
 * renderer once everything queued before the teardown has executed; `terminate` then waits only
 * for what was queued since.
 *
 * The scene's own renderer has gone that way since #3854. The one [SurfaceMirrorer] creates for its
 * mirror passes (in-app recording) did not: the mirrorer's `destroy()`, run when a recorded scene
 * is disposed, called `Engine.destroyRenderer` itself.
 *
 * The real wait needs a Filament engine and a GPU, so this reads the module's compiled classes
 * instead: only [safeDestroyRenderer] itself and the deferred release may reach
 * `Engine.destroyRenderer`, and the mirrorer still releases its renderer — through the deferral.
 */
class RendererReleaseContractTest {

    @Test
    fun `every renderer release in the library waits for an idle backend`() {
        val sites = releaseSites()
        assertTrue(
            "the scan found no call to Engine.destroyRenderer in safeDestroyRenderer — it read " +
                "nothing of the module. Sites seen: $sites",
            sites.any { it.caller.startsWith("$ENGINE_KT.safeDestroyRenderer") },
        )
        assertEquals(
            "Engine.destroyRenderer waits for the whole backend queue before it returns: called " +
                "on the main thread while that queue is long, it is an ANR (#3799). Release a " +
                "renderer with engine.destroyRendererWhenBackendIdle(renderer) instead.",
            emptyList<ReleaseSite>(),
            sites.filterNot { it.isDeferred },
        )
    }

    @Test
    fun `SurfaceMirrorer releases its mirror renderer once the backend is idle`() {
        // Any method of the class may make the call: renaming or splitting destroy() must not
        // break this, while going back to Engine.destroyRenderer leaves no such call at all.
        val calls = calledMethods(SurfaceMirrorer::class.java)
        assertTrue(
            "SurfaceMirrorer must still release the renderer it created for its mirror passes, " +
                "through destroyRendererWhenBackendIdle — dropping the release would leak it " +
                "until the engine goes. Release calls seen: " +
                calls.filter { (_, call) -> call.contains("destroy", ignoreCase = true) }
                    .map { (caller, call) -> "$caller -> $call" },
            calls.any { (_, call) -> call.startsWith("$ENGINE_KT.destroyRendererWhenBackendIdle") },
        )
    }

    /** A call reaching `Engine.destroyRenderer`, directly or through [safeDestroyRenderer]. */
    private data class ReleaseSite(val caller: String, val callee: String) {
        /** The wrapper itself, or the backend-idle deferral (a lambda inside it, once compiled). */
        val isDeferred: Boolean
            get() = caller.startsWith("$ENGINE_KT.safeDestroyRenderer") ||
                caller.startsWith("$ENGINE_KT.destroyRendererWhenBackendIdle") ||
                caller.startsWith("$ENGINE_KT\$destroyRendererWhenBackendIdle")

        override fun toString() = "$caller -> $callee"
    }

    private fun releaseSites(): List<ReleaseSite> = libraryClasses().flatMap { bytes ->
        val sites = ArrayList<ReleaseSite>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            private lateinit var className: String

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                className = name
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val caller = "$className.$name"
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean
                    ) {
                        val direct = owner == FILAMENT_ENGINE && name == "destroyRenderer"
                        val wrapped = owner == ENGINE_KT && name.startsWith("safeDestroyRenderer")
                        if (direct || wrapped) sites += ReleaseSite(caller, "$owner.$name")
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        sites
    }.toList()

    /** Every call `type` makes, as (calling method name, `owner.name(descriptor)`). */
    private fun calledMethods(type: Class<*>): List<Pair<String, String>> {
        val resource = "/" + type.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(type.getResourceAsStream(resource)) { "no class file: $resource" }
            .use { it.readBytes() }
        val calls = ArrayList<Pair<String, String>>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val caller = name
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean
                    ) {
                        calls += caller to "$owner.$name$descriptor"
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return calls
    }

    /** The class files compiled from this module's main sources — a directory or a jar. */
    private fun libraryClasses(): Sequence<ByteArray> {
        val codeLocation = checkNotNull(
            SurfaceMirrorer::class.java.protectionDomain?.codeSource?.location
        ) { "no code source for SurfaceMirrorer" }
        val location = File(codeLocation.toURI())
        return if (location.isDirectory) {
            location.walk().filter { it.isFile && it.extension == "class" }.map { it.readBytes() }
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { entry -> jar.getInputStream(entry).use { it.readBytes() } }
                    .toList()
            }.asSequence()
        }
    }

    private companion object {
        const val FILAMENT_ENGINE = "com/google/android/filament/Engine"
        const val ENGINE_KT = "io/github/sceneview/EngineKt"
    }
}
