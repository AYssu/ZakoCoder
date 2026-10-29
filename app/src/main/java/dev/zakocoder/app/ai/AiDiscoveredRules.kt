package dev.zakocoder.app.ai

/** One task's bounded rule working set. Blocking refresh runs on the controller's IO dispatcher. */
internal class AiDiscoveredRules(private val loader: AiTaskContext, private val project: String, private val references: List<String>) {
    data class Delivery(val versions: Map<String, String>, val targets: Set<String>)
    private val recent = linkedSetOf<String>()
    var current = AiProjectRules("", emptyList(), false)
        private set
    private var delivered = Delivery(emptyMap(), emptySet())
    private var retired = emptyList<String>()
    private var active = false

    fun refresh(paths: List<String>) {
        require(paths.all { it.length <= 4096 })
        active = true
        paths.filter(String::isNotBlank).takeLast(200).forEach { recent.remove(it); recent.add(it) }
        while (recent.size > 24) recent.remove(recent.first())
        // The active target comes first so old exploration cannot consume all directory slots.
        val focus = paths.lastOrNull() ?: recent.lastOrNull().orEmpty()
        current = loader.rules(project, (listOf(focus) + references + recent.toList().asReversed()).distinct())
        retired = delivered.versions.keys.filter { source -> scope(source) in current.inspectedScopes && source !in current.versions }
    }
    fun clear() { active = false; current = AiProjectRules("", emptyList(), false); retired = emptyList() }
    fun delivery() = Delivery(current.versions.toMap(), if (active) recent.toSet() else emptySet())
    /** A completed normal model request is evidence of receipt; discovery and summary calls are not. */
    fun received(value: Delivery) { delivered = value }
    private fun scope(source: String) = source.substringBeforeLast('/', "")
    private fun applies(source: String, target: String): Boolean {
        val directory = scope(source)
        return directory.isEmpty() || target == directory || target.startsWith("$directory/")
    }
    fun needsReview(target: String): Boolean =
        current.versions.any { (source, digest) -> applies(source, target) && delivered.versions[source] != digest } ||
            retired.any { applies(it, target) } || (current.limited && target !in delivered.targets)

    fun text(): String = buildString {
        if (retired.isNotEmpty()) append("[Project rules refreshed: previously supplied sources no longer active in these inspected scopes: ")
            .append(retired.joinToString(", ").take(4000)).append(". This source list is bounded. Discard their earlier instructions and use the current scoped rules below.]\n")
        if (current.limited) append("[Project rule loading is limited. Missing/truncated content is not proof that no rules apply. Read applicable rule files explicitly before editing.]\n")
        append(current.text)
    }
}
