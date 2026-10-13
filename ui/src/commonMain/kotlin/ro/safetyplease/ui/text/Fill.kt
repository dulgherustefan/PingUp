package ro.safetyplease.ui.text

private val Placeholder = Regex("""%(\d+)\$[ds]""")

/**
 * A shared text's template with its %1$s and %1$d filled, as Compose Multiplatform fills them: for words resolved once
 * in composition and used later, by a drawing or a lambda.
 */
fun String.fill(vararg args: Any): String = Placeholder.replace(this) { m ->
    args.getOrNull(m.groupValues[1].toInt() - 1)?.toString() ?: m.value
}
