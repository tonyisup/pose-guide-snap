package com.tonyisup.poseguidesnap.architecture

import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

internal class KotlinDomainBoundaryAnalyzer : AutoCloseable {
    private val disposable = Disposer.newDisposable("KotlinDomainBoundaryAnalyzer")
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration(),
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val psiFactory = KtPsiFactory(environment.project, markGenerated = false)
    private var closed = false

    fun analyze(sourceRoot: File): List<String> {
        check(!closed) { "KotlinDomainBoundaryAnalyzer is closed" }

        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .flatMap { sourceFile ->
                val relativePath = sourceFile.relativeTo(sourceRoot).invariantSeparatorsPath
                analyzeFile(relativePath, psiFactory.createFile(sourceFile.name, sourceFile.readText()))
                    .asSequence()
            }
            .toSortedSet()
            .toList()
    }

    override fun close() {
        if (!closed) {
            closed = true
            Disposer.dispose(disposable)
        }
    }

    private fun analyzeFile(relativePath: String, file: KtFile): Set<String> {
        val violations = sortedSetOf<String>()
        val imports = VisibleImports.from(file)

        file.importDirectives.forEach { directive ->
            val importPath = directive.importPath ?: return@forEach
            val importedPath = importPath.pathStr
            canonicalWallClockImport(importedPath)?.let { clockImport ->
                violations += "$relativePath: forbidden wall-clock import $clockImport"
            }
            if (isForbiddenDependency(importedPath)) {
                violations += "$relativePath: forbidden import $importedPath"
            }
        }

        fun recordReference(path: String?) {
            val resolvedPath = path?.let(imports::resolve) ?: return
            canonicalWallClockCall(resolvedPath)?.let { call ->
                violations += "$relativePath: forbidden wall-clock call $call"
                return
            }
            canonicalDependencyReference(resolvedPath)?.let { dependency ->
                violations += "$relativePath: forbidden dependency reference $dependency"
            }
        }

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitImportDirective(importDirective: KtImportDirective) = Unit

            override fun visitCallExpression(expression: KtCallExpression) {
                recordReference(expression.outerQualifiedPath())
                super.visitCallExpression(expression)
            }

            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                if (expression.parent !is KtQualifiedExpression) {
                    recordReference(expression.referencePath())
                }
                super.visitDotQualifiedExpression(expression)
            }

            override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression) {
                if (expression.parent !is KtQualifiedExpression) {
                    recordReference(expression.referencePath())
                }
                super.visitSafeQualifiedExpression(expression)
            }

            override fun visitUserType(type: KtUserType) {
                if (type.parent !is KtUserType) {
                    recordReference(type.referencePath())
                }
                super.visitUserType(type)
            }

            override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
                val receiver = expression.receiverExpression?.referencePath()
                val callable = expression.callableReference.getReferencedName()
                recordReference(listOfNotNull(receiver, callable).joinToString("."))
                super.visitCallableReferenceExpression(expression)
            }
        })

        return violations
    }

    private fun KtCallExpression.outerQualifiedPath(): String? {
        var outerExpression: KtExpression = this
        while (true) {
            val qualifiedParent = outerExpression.parent as? KtQualifiedExpression ?: break
            if (
                qualifiedParent.receiverExpression !== outerExpression &&
                qualifiedParent.selectorExpression !== outerExpression
            ) {
                break
            }
            outerExpression = qualifiedParent
        }
        return outerExpression.referencePath()
    }

    private fun KtExpression.referencePath(): String? = when (this) {
        is KtNameReferenceExpression -> getReferencedName()
        is KtCallExpression -> calleeExpression?.referencePath()
        is KtParenthesizedExpression -> expression?.referencePath()
        is KtQualifiedExpression -> {
            val receiver = receiverExpression.referencePath()
            val selector = selectorExpression?.referencePath()
            if (receiver == null || selector == null) null else "$receiver.$selector"
        }
        else -> null
    }

    private fun KtUserType.referencePath(): String? {
        val referencedName = referenceExpression?.getReferencedName() ?: return null
        val qualifierPath = qualifier?.referencePath()
        return if (qualifierPath == null) referencedName else "$qualifierPath.$referencedName"
    }

    private fun canonicalWallClockCall(path: String): String? {
        if (path.references("java.lang.System.currentTimeMillis")) {
            return "System.currentTimeMillis"
        }
        if (path.references("java.lang.System.nanoTime")) {
            return "System.nanoTime"
        }

        WALL_CLOCK_NOW_TYPES.firstOrNull { path.references("$it.now") }?.let { type ->
            return "${type.substringAfterLast('.')}.now"
        }

        if (path.startsWith("java.time.Clock.")) {
            val provider = path.removePrefix("java.time.Clock.").substringBefore('.')
            if (provider.isNotEmpty()) return "Clock.$provider"
        }

        if (
            path.references("kotlin.time.TimeSource.markNow") ||
            path.references("kotlin.time.TimeSource.Monotonic.markNow")
        ) {
            return "TimeSource.markNow"
        }

        return null
    }

    private fun canonicalWallClockImport(path: String): String? {
        if (path == "java.lang.System.currentTimeMillis") return "System.currentTimeMillis"
        if (path == "java.lang.System.nanoTime") return "System.nanoTime"

        WALL_CLOCK_NOW_TYPES.firstOrNull { path == "$it.now" }?.let { type ->
            return "${type.substringAfterLast('.')}.now"
        }

        if (path == "kotlin.time.TimeSource" || path == "kotlin.time.TimeSource.Monotonic") {
            return "TimeSource"
        }
        if (
            path == "kotlin.time.TimeSource.markNow" ||
            path == "kotlin.time.TimeSource.Monotonic.markNow"
        ) {
            return "TimeSource.markNow"
        }

        return null
    }

    private fun String.references(canonicalPath: String): Boolean =
        this == canonicalPath || startsWith("$canonicalPath.")

    private fun canonicalDependencyReference(path: String): String? {
        if (path.startsWith("java.time.Clock.")) return null
        return FORBIDDEN_DEPENDENCY_PREFIXES
            .firstOrNull { prefix -> path == prefix || path.startsWith("$prefix.") }
            ?.let { path }
    }

    private fun isForbiddenDependency(path: String): Boolean =
        FORBIDDEN_DEPENDENCY_PREFIXES.any { prefix ->
            path == prefix || path.startsWith("$prefix.")
        }

    private class VisibleImports(
        private val direct: Map<String, String>,
        private val wildcardPackages: Set<String>,
    ) {
        fun resolve(path: String): String {
            val firstSegment = path.substringBefore('.')
            val suffix = path.removePrefix(firstSegment)
            direct[firstSegment]?.let { return it + suffix }

            DEFAULT_VISIBLE_NAMES[firstSegment]?.let { return it + suffix }
            wildcardPackages.forEach { packageName ->
                if (packageName == "java.time" && firstSegment in JAVA_TIME_TYPES) {
                    return "$packageName.$path"
                }
                if (packageName == "java.lang" && firstSegment == "System") {
                    return "$packageName.$path"
                }
                if (packageName == "kotlin.time" && firstSegment == "TimeSource") {
                    return "$packageName.$path"
                }
            }
            return path
        }

        companion object {
            fun from(file: KtFile): VisibleImports {
                val direct = linkedMapOf<String, String>()
                val wildcardPackages = linkedSetOf<String>()
                file.importDirectives.forEach { directive ->
                    val importPath = directive.importPath ?: return@forEach
                    val path = importPath.pathStr
                    if (importPath.isAllUnder) {
                        wildcardPackages += path
                    } else {
                        direct[directive.aliasName ?: path.substringAfterLast('.')] = path
                    }
                }
                return VisibleImports(direct, wildcardPackages)
            }
        }
    }

    private companion object {
        val FORBIDDEN_DEPENDENCY_PREFIXES = listOf(
            "android",
            "androidx",
            "com.google.mediapipe",
            "java.io",
            "java.nio.file",
            "java.time.Clock",
            "kotlin.io.path",
        )
        val WALL_CLOCK_NOW_TYPES = listOf(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
        )
        val JAVA_TIME_TYPES = WALL_CLOCK_NOW_TYPES
            .mapTo(linkedSetOf()) { it.substringAfterLast('.') }
            .plus("Clock")
        val DEFAULT_VISIBLE_NAMES = mapOf(
            "System" to "java.lang.System",
            "TimeSource" to "kotlin.time.TimeSource",
        )
    }
}
