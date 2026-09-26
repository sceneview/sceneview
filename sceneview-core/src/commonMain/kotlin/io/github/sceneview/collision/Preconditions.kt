// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

/**
 * Static convenience methods that help a method or constructor check whether it was invoked
 * correctly.
 */
object Preconditions {
    fun <T> checkNotNull(reference: T?): T {
        if (reference == null) {
            throw NullPointerException()
        }
        return reference
    }

    fun <T> checkNotNull(reference: T?, errorMessage: Any): T {
        if (reference == null) {
            throw NullPointerException(errorMessage.toString())
        }
        return reference
    }

    fun checkElementIndex(index: Int, size: Int) {
        checkElementIndex(index, size, "index")
    }

    fun checkElementIndex(index: Int, size: Int, desc: String) {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException(badElementIndex(index, size, desc))
        }
    }

    fun checkState(expression: Boolean) {
        if (!expression) {
            throw IllegalStateException()
        }
    }

    fun checkState(expression: Boolean, errorMessage: Any?) {
        if (!expression) {
            throw IllegalStateException(errorMessage.toString())
        }
    }

    private fun badElementIndex(index: Int, size: Int, desc: String): String {
        return if (index < 0) {
            format("%s (%s) must not be negative", desc, index)
        } else if (size < 0) {
            throw IllegalArgumentException("negative size: $size")
        } else {
            format("%s (%s) must be less than size (%s)", desc, index, size)
        }
    }

    private fun format(template: String, vararg args: Any?): String {
        val builder = StringBuilder(template.length + 16 * args.size)
        var templateStart = 0
        var i = 0
        while (i < args.size) {
            val placeholderStart = template.indexOf("%s", templateStart)
            if (placeholderStart == -1) {
                break
            }
            builder.append(template, templateStart, placeholderStart)
            builder.append(args[i++])
            templateStart = placeholderStart + 2
        }
        builder.append(template, templateStart, template.length)

        if (i < args.size) {
            builder.append(" [")
            builder.append(args[i++])
            while (i < args.size) {
                builder.append(", ")
                builder.append(args[i++])
            }
            builder.append(']')
        }

        return builder.toString()
    }
}
