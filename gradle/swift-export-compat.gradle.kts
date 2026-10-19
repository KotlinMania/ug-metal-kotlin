fun normalizeSwiftBridgeUnits(source: String): String {
    val unitConversion =
        Regex("""(?m)^([ \t]*)val _result = ([^\r\n]+)\r?\n\1(return )?(run \{ _result; true \}|run<Unit> \{ _result \})$""")
    return unitConversion.replace(source) { match ->
        val indent = match.groupValues[1]
        val call = match.groupValues[2]
        val returnPrefix = match.groupValues[3]
        val value = if (match.groupValues[4] == "run { _result; true }") "true" else "Unit"
        "$indent$call\n$indent$returnPrefix$value"
    }
}

fun normalizeSwiftCoroutineSupport(source: String): String =
    source
        .replace(
            "@OptIn(InternalCoroutinesApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)",
            "@OptIn(InternalCoroutinesApi::class, InternalForInheritanceCoroutinesApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)",
        )
        .replace("as SwiftFlowIterator<kotlin.Any?>", "as SwiftFlowIterator<*>")
        .replace("arg0: SwiftFlowIterator<kotlin.Any?>.Value?", "arg0: SwiftFlowIterator<*>.Value?")
        .replace(
            "is State.Completed -> return state.error?.let { throw it } ?: null",
            "is State.Completed -> {\n                    state.error?.let { throw it }\n                    return null\n                }",
        )

val verifySwiftBridgeNormalization =
    tasks.register("verifySwiftBridgeNormalization") {
        group = "verification"
        doLast {
            val cases =
                listOf(
                    "    val _result = run { initialize() }\n    return run { _result; true }" to
                        "    run { initialize() }\n    return true",
                    "    val _result = reverseCall()\n    return run<Unit> { _result }" to
                        "    reverseCall()\n    return Unit",
                    "            val _result = callback(argument)\n            run<Unit> { _result }" to
                        "            callback(argument)\n            Unit",
                )
            for ((input, expected) in cases) {
                check(normalizeSwiftBridgeUnits(input) == expected)
                check(normalizeSwiftBridgeUnits(expected) == expected)
            }
            val valueBridge = "    val _result = compute()\n    return _result"
            check(normalizeSwiftBridgeUnits(valueBridge) == valueBridge)
            val iteratorCast = "value as SwiftFlowIterator<kotlin.Any?>"
            check(normalizeSwiftCoroutineSupport(iteratorCast) == "value as SwiftFlowIterator<*>")
            val iteratorFactory = "SwiftFlowIterator<kotlin.Any?>(flow)"
            check(normalizeSwiftCoroutineSupport(iteratorFactory) == iteratorFactory)
            val completed = "is State.Completed -> return state.error?.let { throw it } ?: null"
            val normalized = normalizeSwiftCoroutineSupport(completed)
            check(normalized.contains("state.error?.let { throw it }"))
            check(normalized.contains("return null"))
            check(normalizeSwiftCoroutineSupport(normalized) == normalized)
        }
    }

// Kotlin 2.4.20 emits a temporary followed by an unused read when converting
// Unit to the bridge Boolean ABI and back. Keep each call and its return
// conversion, without emitting the unused temporary or changing warning policy.
tasks.matching { it.name.matches(Regex(".+(Debug|Release)SwiftExport")) }.configureEach {
    dependsOn(verifySwiftBridgeNormalization)
    inputs.property("unitBridgeNormalizationVersion", 3)
    doLast {
        val generatedSources = layout.buildDirectory.dir("SwiftExport").get().asFile
        generatedSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
            val original = source.readText()
            val unitNormalized = normalizeSwiftBridgeUnits(original)
            val normalized =
                if (source.name == "KotlinCoroutineSupport.kt") {
                    normalizeSwiftCoroutineSupport(unitNormalized)
                } else {
                    unitNormalized
                }
            if (normalized != original) source.writeText(normalized)
        }
    }
}
