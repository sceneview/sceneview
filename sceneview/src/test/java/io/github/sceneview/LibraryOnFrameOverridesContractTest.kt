package io.github.sceneview

import com.google.android.filament.Engine
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The library node types the frame plan does **not** call `onFrame` on really do nothing there
 * (#4451).
 *
 * `SceneFrameDispatch` gives a node an every-frame step when its class overrides `onFrame`. Some
 * library classes keep an override only so that the published binary API does not change, and are
 * listed by name in [inheritedOnFrameOwners] so the plan can skip them: their per-frame work lives
 * in `onOwnFrame`, which the plan reaches only while `hasOwnFrameWork` is up. That list is a
 * promise nothing else enforces — work added to one of those bodies would compile, pass every
 * behavioural test, and silently never run on a node at rest.
 *
 * So this reads the compiled body of each listed override and requires it to be
 * `super.onFrame(frameTimeNanos)` and nothing else: load `this`, load the argument, call the
 * superclass method, return.
 */
class LibraryOnFrameOverridesContractTest {

    @Test
    fun `every listed class overrides onFrame with a call to super and nothing else`() {
        val overriding = inheritedOnFrameOwners - Node::class.java
        assertTrue("nothing to check: the list holds no subclass", overriding.isNotEmpty())

        overriding.forEach { type ->
            val superName = checkNotNull(type.superclass).name.replace('.', '/')
            assertEquals(
                "${type.simpleName}.onFrame must stay `super.onFrame(frameTimeNanos)` alone: " +
                    "the frame plan does not call it. Per-frame work goes in onOwnFrame.",
                listOf(
                    "ALOAD 0",
                    "LLOAD 1",
                    "INVOKESPECIAL $superName.onFrame(J)V",
                    "RETURN"
                ),
                onFrameBody(type)
            )
        }
    }

    @Test
    fun `the reader tells a body that does work from one that does not`() {
        // The check above is worth nothing if it passes on anything: run it on a body with work.
        val body = onFrameBody(BusyNode::class.java)
        assertTrue(body.toString(), body.size > PASS_THROUGH_SIZE)
        assertTrue(body.toString(), body.any { it.startsWith("PUTFIELD") })
    }

    /** What an override that gained work looks like. Never instantiated: only its bytecode is read. */
    @Suppress("unused")
    private class BusyNode(engine: Engine) : Node(engine, 0) {
        var ticks = 0

        override fun onFrame(frameTimeNanos: Long) {
            super.onFrame(frameTimeNanos)
            ticks++
        }
    }

    /** The instructions of `type.onFrame(long)`, line numbers and labels left out. */
    private fun onFrameBody(type: Class<*>): List<String> {
        val resource = "/" + type.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(type.getResourceAsStream(resource)) { "no class file: $resource" }
            .use { it.readBytes() }
        val instructions = ArrayList<String>()
        var found = false
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                if (name != "onFrame" || descriptor != "(J)V") return null
                found = true
                return InstructionRecorder(instructions)
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        assertTrue("${type.name} declares no onFrame(long)", found)
        return instructions
    }

    private class InstructionRecorder(private val out: MutableList<String>) :
        MethodVisitor(Opcodes.ASM9) {

        override fun visitInsn(opcode: Int) {
            out += name(opcode)
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            out += "${name(opcode)} $operand"
        }

        override fun visitVarInsn(opcode: Int, varIndex: Int) {
            out += "${name(opcode)} $varIndex"
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            out += "${name(opcode)} $type"
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            out += "${name(opcode)} $owner.$name"
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            out += "${name(opcode)} $owner.$name$descriptor"
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: org.objectweb.asm.Handle,
            vararg bootstrapMethodArguments: Any?
        ) {
            out += "INVOKEDYNAMIC $name$descriptor"
        }

        override fun visitJumpInsn(opcode: Int, label: Label) {
            out += name(opcode)
        }

        override fun visitLdcInsn(value: Any?) {
            out += "LDC $value"
        }

        override fun visitIincInsn(varIndex: Int, increment: Int) {
            out += "IINC $varIndex"
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
            out += "TABLESWITCH"
        }

        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
            out += "LOOKUPSWITCH"
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
            out += "MULTIANEWARRAY $descriptor"
        }

        private fun name(opcode: Int): String = OPCODE_NAMES[opcode] ?: "OP_$opcode"
    }

    private companion object {
        /** Instructions in a pass-through override: load `this`, load the argument, call, return. */
        const val PASS_THROUGH_SIZE = 4

        /** The opcodes a pass-through body or a small amount of added work is made of. */
        val OPCODE_NAMES = mapOf(
            Opcodes.ALOAD to "ALOAD",
            Opcodes.LLOAD to "LLOAD",
            Opcodes.ILOAD to "ILOAD",
            Opcodes.ISTORE to "ISTORE",
            Opcodes.INVOKESPECIAL to "INVOKESPECIAL",
            Opcodes.INVOKEVIRTUAL to "INVOKEVIRTUAL",
            Opcodes.INVOKESTATIC to "INVOKESTATIC",
            Opcodes.INVOKEINTERFACE to "INVOKEINTERFACE",
            Opcodes.GETFIELD to "GETFIELD",
            Opcodes.PUTFIELD to "PUTFIELD",
            Opcodes.GETSTATIC to "GETSTATIC",
            Opcodes.PUTSTATIC to "PUTSTATIC",
            Opcodes.RETURN to "RETURN"
        )
    }
}
