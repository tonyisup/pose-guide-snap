package com.tonyisup.poseguidesnap.architecture

import java.util.Locale
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtProjectionKind
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

internal class GuidedSessionTask3BSourceOracle : AutoCloseable {
    private val disposable = Disposer.newDisposable("GuidedSessionTask3BSourceOracle")
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration(),
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val psiFactory = KtPsiFactory(environment.project, markGenerated = false)
    private var closed = false

    fun validateLoader(source: String): List<String> {
        val violations = mutableListOf<String>()
        val file = parse("GuidedSessionDao.kt", source, violations) ?: return violations
        val dao = uniqueDao(file, violations) ?: return violations
        val loader = uniqueDirectFunction(dao, LOADER_NAME, violations) ?: return violations

        if (!file.hasExactImport("androidx.room.Transaction")) {
            violations += "$LOADER_NAME must use the exact unaliased Room Transaction import"
        }
        if (loader.annotationEntries.countNamed("Transaction") != 1) {
            violations += "$LOADER_NAME must have exactly one real @Transaction annotation"
        }
        if (!loader.hasModifier(KtTokens.OPEN_KEYWORD)) {
            violations += "$LOADER_NAME must be open"
        }
        if (!loader.hasExactParameter("sessionId", "String")) {
            violations += "$LOADER_NAME must have exactly one sessionId: String parameter"
        }
        if (!loader.typeReference.isSimpleType("GuidedSessionBootstrapRows")) {
            violations += "$LOADER_NAME must return GuidedSessionBootstrapRows"
        }
        val body = loader.bodyExpression as? KtBlockExpression
        if (body == null) {
            violations += "$LOADER_NAME must have a block body"
            return violations
        }

        validateClosedLoaderBody(dao, body, violations)

        return violations
    }

    private fun validateClosedLoaderBody(
        dao: KtClass,
        body: KtBlockExpression,
        violations: MutableList<String>,
    ) {
        if (body.statements.size != 2) {
            violations += "$LOADER_NAME must have exactly the session lookup and final return statements"
            return
        }

        val sessionProperty = body.statements[0] as? KtProperty
        val sessionInitializer = sessionProperty?.initializer as? KtBinaryExpression
        val absentReturn = sessionInitializer?.right as? KtReturnExpression
        val absentRows = absentReturn?.returnedExpression as? KtCallExpression
        if (sessionProperty?.name != "session" || sessionProperty.isVar ||
            sessionInitializer?.operationToken != KtTokens.ELVIS ||
            !sessionInitializer?.left.isExactDirectDaoRead("findSession") ||
            !absentRows.isExactAbsentBootstrapRows()
        ) {
            violations +=
                "$LOADER_NAME must begin with the exact conditional findSession lookup and absent return"
        }

        val finalReturn = body.statements[1] as? KtReturnExpression
        val finalRows = finalReturn?.returnedExpression as? KtCallExpression
        if (finalRows == null || !finalRows.isExactCompleteBootstrapRows()) {
            violations += "$LOADER_NAME must end with the exact complete bootstrap return"
        }

        val directMemberNames = dao.declarations.filterIsInstance<KtNamedFunction>()
            .mapNotNull(KtNamedFunction::getName)
            .toSet()
        val reads = executableCalls(body).filter { it.simpleCalleeName() in directMemberNames }
        val readNames = reads.mapNotNull { it.simpleCalleeName() }
        if (readNames != CONSTITUENT_READS || reads.any { !it.isDirectUnqualifiedCall() }) {
            violations +=
                "$LOADER_NAME must contain only the exact nine unqualified direct DAO-member reads"
        }
        CONSTITUENT_READS.forEach { name ->
            if (dao.declarations.filterIsInstance<KtNamedFunction>().count { it.name == name } != 1) {
                violations += "$DAO_NAME must declare exactly one direct $name member"
            }
        }
    }

    fun validateJournal(source: String): List<String> {
        val violations = mutableListOf<String>()
        val file = parse("GuidedSessionDao.kt", source, violations) ?: return violations
        val dao = uniqueDao(file, violations) ?: return violations
        val queryFunction = uniqueDirectFunction(dao, QUERY_NAME, violations) ?: return violations

        listOf("androidx.room.Query", "androidx.room.ColumnInfo").forEach { requiredImport ->
            if (!file.hasExactImport(requiredImport)) {
                violations += "$QUERY_NAME must use exact unaliased import $requiredImport"
            }
        }
        if (!queryFunction.hasModifier(KtTokens.PROTECTED_KEYWORD) ||
            !queryFunction.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        ) {
            violations += "$QUERY_NAME must be protected and abstract"
        }
        if (!queryFunction.hasExactParameter("sessionId", "String")) {
            violations += "$QUERY_NAME must have exactly one sessionId: String parameter"
        }
        val projectionType = queryFunction.listElementType()
        if (projectionType != PROJECTION_TYPE) {
            violations += "$QUERY_NAME must return List<$PROJECTION_TYPE>"
        }

        val queryAnnotations = queryFunction.annotationEntries.filterNamed("Query")
        if (queryAnnotations.size != 1) {
            violations += "$QUERY_NAME must have exactly one real @Query annotation"
        } else {
            val sql = queryAnnotations.single().singleRawLiteralArgument()
            if (sql == null) {
                violations += "@Query must have one interpolation-free raw string SQL argument"
            } else {
                violations += validateSql(sql)
            }
        }

        val projection = uniqueDirectProjection(file, violations)
        if (projection != null) {
            validateProjection(projection, violations)
        }
        validateConversion(file, violations)

        return violations
    }

    fun validatePublicContract(source: String): List<String> {
        val violations = mutableListOf<String>()
        val file = parse("GuidedSessionContracts.kt", source, violations) ?: return violations
        val matches = file.declarations.filterIsInstance<KtClass>()
            .filter { it.name == PUBLIC_ROW_TYPE }
        if (matches.size != 1) {
            violations += "contracts must declare exactly one public $PUBLIC_ROW_TYPE data class"
            return violations
        }
        val row = matches.single()
        if (!row.hasModifier(KtTokens.DATA_KEYWORD) || row.isExplicitlyNonPublic()) {
            violations += "$PUBLIC_ROW_TYPE must be a public data class"
        }
        val parameters = row.primaryConstructorParameters
        val final = parameters.lastOrNull()
        if (final == null ||
            final.name != "hasCanonicalStorage" ||
            !final.hasValOrVar() ||
            !final.typeReference.isSimpleType("Boolean") ||
            !final.defaultValue.isTrueReference()
        ) {
            violations +=
                "$PUBLIC_ROW_TYPE must end with val hasCanonicalStorage: Boolean = true"
        }
        return violations
    }

    fun validateSql(sql: String): List<String> {
        val parsed = try {
            parseSql(sql)
        } catch (failure: IllegalArgumentException) {
            return listOf("SQL structure: ${failure.message}")
        }
        val violations = mutableListOf<String>()
        val expected = expectedSelectExpressions()

        if (parsed.items.size != expected.size) {
            violations += "SELECT must contain exactly ${expected.size} structurally split items"
        }
        val aliases = parsed.items.map { it.alias }
        val duplicateAliases = aliases.groupingBy { it }.eachCount().filterValues { it != 1 }.keys
        if (duplicateAliases.isNotEmpty()) {
            violations += "SELECT aliases must be unique: ${duplicateAliases.sorted().joinToString()}"
        }
        if (aliases.toSet() != expected.keys) {
            violations += "SELECT aliases must exactly equal the 40-column journal contract"
        }
        parsed.items.forEach { item ->
            val expectedExpression = expected[item.alias]
            if (expectedExpression != null &&
                normalizeSql(item.expression) != normalizeSql(expectedExpression)
            ) {
                violations += "SELECT expression for ${item.alias} is not exact"
            }
        }

        if (normalizeSql(parsed.fromClause) != normalizeSql(EXPECTED_FROM)) {
            violations += "FROM/JOIN must use exact byte-correlated command-token authority"
        }
        if (normalizeSql(parsed.whereClause) != normalizeSql(EXPECTED_WHERE)) {
            violations += "WHERE must contain only the exact byte-correlated session predicate"
        }
        val normalizedOrder = normalizeSql(parsed.orderClause)
        if (normalizedOrder != normalizeSql(EXPECTED_ORDER) &&
            normalizedOrder != normalizeSql("$EXPECTED_ORDER, operation.rowid ASC")
        ) {
            violations += "ORDER BY must match the journal ordering contract"
        }
        if (normalizeSql(parsed.selectClause).contains("cast(")) {
            violations += "CAST is forbidden in SELECT expressions"
        }
        if (normalizeSql(parsed.orderClause).contains("cast(")) {
            violations += "CAST is forbidden in ORDER BY"
        }
        if (normalizeSql(sql).contains("astext")) {
            violations += "AS TEXT casts are forbidden"
        }
        if (normalizeSql(sql).windowed("cast(".length).count { it == "cast(" } != 4) {
            violations += "CAST is reserved for the two exact byte-correlation expressions"
        }

        return violations
    }

    fun canonicalSqlForTesting(): String {
        val select = expectedSelectExpressions().entries.joinToString(",\n") { (alias, expression) ->
            "    $expression AS $alias"
        }
        return "SELECT\n$select\n$EXPECTED_FROM\n$EXPECTED_WHERE\n$EXPECTED_ORDER"
    }

    override fun close() {
        if (!closed) {
            closed = true
            Disposer.dispose(disposable)
        }
    }

    private fun parse(name: String, source: String, violations: MutableList<String>): KtFile? {
        check(!closed) { "GuidedSessionTask3BSourceOracle is closed" }
        val file = psiFactory.createFile(name, source)
        val errors = mutableListOf<PsiErrorElement>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitErrorElement(element: PsiErrorElement) {
                errors += element
            }
        })
        if (errors.isNotEmpty()) {
            violations += "$name must parse without PSI errors"
            return null
        }
        return file
    }

    private fun uniqueDao(file: KtFile, violations: MutableList<String>): KtClass? {
        val matches = file.declarations.filterIsInstance<KtClass>()
            .filter { it.name == DAO_NAME }
        if (matches.size != 1) {
            violations += "source must contain exactly one $DAO_NAME class"
            return null
        }
        return matches.single()
    }

    private fun uniqueDirectFunction(
        owner: KtClass,
        name: String,
        violations: MutableList<String>,
    ): KtNamedFunction? {
        val matches = owner.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name }
        if (matches.size != 1) {
            violations += "$DAO_NAME must contain exactly one direct $name member"
            return null
        }
        return matches.single()
    }

    private fun uniqueDirectProjection(
        file: KtFile,
        violations: MutableList<String>,
    ): KtClass? {
        val matches = file.declarations.filterIsInstance<KtClass>()
            .filter { it.name == PROJECTION_TYPE }
        if (matches.size != 1) {
            violations += "source must contain exactly one direct $PROJECTION_TYPE class"
            return null
        }
        val projection = matches.single()
        if (!projection.hasModifier(KtTokens.DATA_KEYWORD) || !projection.isExplicitlyNonPublic()) {
            violations += "$PROJECTION_TYPE must be a non-public data class"
        }
        return projection
    }

    private fun validateProjection(projection: KtClass, violations: MutableList<String>) {
        val expected = expectedProjectionParameters()
        val parameters = projection.primaryConstructorParameters
        if (parameters.size != expected.size) {
            violations += "$PROJECTION_TYPE must have exactly ${expected.size} constructor columns"
        }
        parameters.forEachIndexed { index, parameter ->
            val contract = expected.getOrNull(index)
            if (contract == null ||
                parameter.name != contract.property ||
                !parameter.hasValOrVar() ||
                !parameter.typeReference.isSimpleType(contract.type, contract.nullable) ||
                parameter.columnName() != contract.column
            ) {
                violations += "$PROJECTION_TYPE constructor column ${parameter.name ?: index} is not exact"
            }
        }

        val toStrings = projection.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.name == "toString" }
        val expectedRedaction = "$PROJECTION_TYPE(redacted)"
        if (toStrings.size != 1) {
            violations += "$PROJECTION_TYPE must declare one fixed redacted toString"
        } else {
            val function = toStrings.single()
            val body = function.bodyExpression as? KtStringTemplateExpression
            if (!function.hasModifier(KtTokens.OVERRIDE_KEYWORD) ||
                function.valueParameters.isNotEmpty() ||
                !function.typeReference.isSimpleType("String") ||
                body?.literalValue() != expectedRedaction
            ) {
                violations += "$PROJECTION_TYPE toString must be a fixed redacted literal"
            }
        }
    }

    private fun validateConversion(file: KtFile, violations: MutableList<String>) {
        val matches = file.declarations.filterIsInstance<KtNamedFunction>().filter { function ->
            function.name == "toAuthorityRow" &&
                function.receiverTypeReference.isSimpleType(PROJECTION_TYPE) &&
                function.typeReference.isSimpleType(PUBLIC_ROW_TYPE)
        }
        if (matches.size != 1) {
            violations += "source must contain exactly one direct $PROJECTION_TYPE.toAuthorityRow conversion"
            return
        }
        val conversion = matches.single()
        if (!conversion.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
            violations += "$PROJECTION_TYPE.toAuthorityRow must be private"
        }
        val calls = conversion.bodyExpression?.let(::executableCalls).orEmpty()
            .filter { it.simpleCalleeName() == PUBLIC_ROW_TYPE }
        if (calls.size != 1 || conversion.bodyExpression !== calls.singleOrNull()) {
            violations += "conversion body must be the actual $PUBLIC_ROW_TYPE constructor call"
            return
        }
        val expectedArguments = JOURNAL_COLUMNS.mapTo(linkedSetOf()) { contract ->
            contract.column.toLowerCamelCase()
        }.apply {
            add("hasCanonicalStorage")
        }
        val arguments = calls.single().exactNamedArguments(expectedArguments)
        if (arguments == null) {
            violations += "conversion must bind the exact public authority argument set"
            return
        }
        expectedArguments.forEach { name ->
            if (!arguments.getValue(name).isNameReference(name)) {
                violations += "conversion must bind $name to the projection name reference"
            }
        }
    }

    private fun executableCalls(root: KtExpression): List<KtCallExpression> {
        val calls = mutableListOf<KtCallExpression>()
        root.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                calls += expression
                super.visitCallExpression(expression)
            }

            override fun visitNamedFunction(function: KtNamedFunction) = Unit
            override fun visitClassOrObject(classOrObject: KtClassOrObject) = Unit
            override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) = Unit
        })
        return calls
    }

    private fun KtCallExpression?.isExactAbsentBootstrapRows(): Boolean {
        if (this == null || !isDirectUnqualifiedNamedCall("GuidedSessionBootstrapRows")) return false
        val arguments = exactNamedArguments(setOf("shoot", "session")) ?: return false
        return arguments.values.all { expression ->
            expression is KtConstantExpression && expression.text == "null"
        }
    }

    private fun KtCallExpression.isExactCompleteBootstrapRows(): Boolean {
        if (!isDirectUnqualifiedNamedCall("GuidedSessionBootstrapRows")) return false
        val arguments = exactNamedArguments(COMPLETE_BOOTSTRAP_ARGUMENTS) ?: return false
        return arguments.getValue("shoot").isExactNullableAuthorityConversion("findOwningShoot") &&
            arguments.getValue("session").isExactAuthorityConversion("session") &&
            arguments.getValue("poses").isExactMappedDaoRead("findOwningPoses", "ShootPoseEntity") &&
            arguments.getValue("attempts")
                .isExactMappedDaoRead("findAttempts", "CaptureAttemptEntity") &&
            arguments.getValue("privateOutputs")
                .isExactMappedDaoRead("findPrivateOutputs", "PrivateCaptureOutputEntity") &&
            arguments.getValue("receipts")
                .isExactMappedDaoRead("findReceipts", "CaptureConfirmationReceiptEntity") &&
            arguments.getValue("outboxes")
                .isExactMappedDaoRead("findOutboxes", "CaptureExportOutboxEntity") &&
            arguments.getValue("exportOutputs")
                .isExactMappedDaoRead("findExportOutputs", "CaptureExportOutputEntity") &&
            arguments.getValue("captureFileOperations").isExactCaptureMap()
    }

    private fun KtCallExpression.exactNamedArguments(
        expectedNames: Set<String>,
    ): Map<String, KtExpression>? {
        if (valueArguments.size != expectedNames.size) return null
        val result = linkedMapOf<String, KtExpression>()
        valueArguments.forEach { argument ->
            if (argument.isSpread) return null
            val name = argument.getArgumentName()?.asName?.identifier ?: return null
            val expression = argument.getArgumentExpression() ?: return null
            if (name !in expectedNames || result.put(name, expression) != null) return null
        }
        return result.takeIf { it.keys == expectedNames }
    }

    private fun KtExpression?.isExactDirectDaoRead(name: String): Boolean =
        this is KtCallExpression &&
            isDirectUnqualifiedNamedCall(name) &&
            hasExactNameArgument("sessionId")

    private fun KtExpression?.isExactNullableAuthorityConversion(readName: String): Boolean {
        val qualified = this as? KtSafeQualifiedExpression ?: return false
        return qualified.receiverExpression.isExactDirectDaoRead(readName) &&
            qualified.selectorExpression.isExactNoArgCall("toAuthorityRow")
    }

    private fun KtExpression?.isExactAuthorityConversion(receiverName: String): Boolean {
        val qualified = this as? KtDotQualifiedExpression ?: return false
        return qualified.receiverExpression.isNameReference(receiverName) &&
            qualified.selectorExpression.isExactNoArgCall("toAuthorityRow")
    }

    private fun KtExpression?.isExactMappedDaoRead(readName: String, rowType: String): Boolean {
        val qualified = this as? KtDotQualifiedExpression ?: return false
        if (!qualified.receiverExpression.isExactDirectDaoRead(readName)) return false
        val mapCall = qualified.selectorExpression as? KtCallExpression ?: return false
        if (mapCall.simpleCalleeName() != "map" || mapCall.valueArguments.size != 1) return false
        val reference = mapCall.valueArguments.single().getArgumentExpression()
            as? KtCallableReferenceExpression ?: return false
        return reference.receiverExpression.isNameReference(rowType) &&
            reference.callableReference.getReferencedName() == "toAuthorityRow"
    }

    private fun KtExpression?.isExactNoArgCall(name: String): Boolean =
        this is KtCallExpression && simpleCalleeName() == name && valueArguments.isEmpty()

    private fun KtCallExpression.isDirectUnqualifiedNamedCall(name: String): Boolean =
        simpleCalleeName() == name && isDirectUnqualifiedCall()

    private fun KtCallExpression.isDirectUnqualifiedCall(): Boolean {
        val qualifiedParent = parent as? KtQualifiedExpression ?: return true
        return qualifiedParent.receiverExpression === this
    }

    private fun KtExpression?.isExactCaptureMap(): Boolean {
        val qualified = this as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression ?: return false
        val findCall = qualified.receiverExpression as? KtCallExpression ?: return false
        if (!findCall.isDirectUnqualifiedNamedCall(QUERY_NAME) ||
            !findCall.hasExactNameArgument("sessionId")
        ) {
            return false
        }
        val mapCall = qualified.selectorExpression as? KtCallExpression ?: return false
        if (mapCall.simpleCalleeName() != "map" || mapCall.valueArguments.size != 1) return false
        val reference = mapCall.valueArguments.single().getArgumentExpression()
            as? KtCallableReferenceExpression ?: return false
        return reference.receiverExpression.isNameReference(PROJECTION_TYPE) &&
            reference.callableReference.getReferencedName() == "toAuthorityRow"
    }

    private fun KtCallExpression.simpleCalleeName(): String? =
        (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

    private fun KtCallExpression.hasExactNameArgument(name: String): Boolean =
        valueArguments.size == 1 &&
            valueArguments.single().getArgumentName() == null &&
            !valueArguments.single().isSpread &&
            valueArguments.single().getArgumentExpression().isNameReference(name)

    private fun KtExpression?.isNameReference(name: String): Boolean =
        this is KtNameReferenceExpression && getReferencedName() == name

    private fun KtExpression?.isTrueReference(): Boolean =
        this is KtConstantExpression && text == "true"

    private fun KtNamedFunction.hasExactParameter(name: String, type: String): Boolean =
        valueParameters.size == 1 &&
            valueParameters.single().name == name &&
            valueParameters.single().typeReference.isSimpleType(type)

    private fun KtNamedFunction.listElementType(): String? {
        val userType = typeReference?.typeElement as? KtUserType ?: return null
        if (userType.qualifier != null || userType.referencedName != "List") return null
        val arguments = userType.typeArguments
        if (arguments.size != 1 || arguments.single().projectionKind != KtProjectionKind.NONE) return null
        val element = arguments.single().typeReference?.typeElement as? KtUserType ?: return null
        if (element.qualifier != null || element.typeArguments.isNotEmpty()) return null
        return element.referencedName
    }

    private fun KtTypeReference?.isSimpleType(name: String, nullable: Boolean = false): Boolean {
        val typeElement = this?.typeElement ?: return false
        val userType = if (nullable) {
            (typeElement as? KtNullableType)?.innerType as? KtUserType
        } else {
            typeElement as? KtUserType
        } ?: return false
        return userType.qualifier == null &&
            userType.typeArguments.isEmpty() &&
            userType.referencedName == name
    }

    private fun KtParameter.columnName(): String? {
        val matches = annotationEntries.filterNamed("ColumnInfo")
        if (matches.size != 1) return null
        val arguments = matches.single().valueArguments
        if (arguments.size != 1 ||
            arguments.single().getArgumentName()?.asName?.identifier != "name"
        ) {
            return null
        }
        return (arguments.single().getArgumentExpression() as? KtStringTemplateExpression)
            ?.literalValue()
    }

    private fun List<KtAnnotationEntry>.filterNamed(name: String): List<KtAnnotationEntry> =
        filter { it.shortName?.asString() == name }

    private fun List<KtAnnotationEntry>.countNamed(name: String): Int = filterNamed(name).size

    private fun KtFile.hasExactImport(path: String): Boolean =
        importDirectives.count { directive ->
            directive.importPath?.pathStr == path &&
                directive.aliasName == null &&
                directive.importPath?.isAllUnder == false
        } == 1

    private fun KtAnnotationEntry.singleRawLiteralArgument(): String? {
        if (valueArguments.size != 1 || valueArguments.single().getArgumentName() != null) return null
        val expression = valueArguments.single().getArgumentExpression()
            as? KtStringTemplateExpression ?: return null
        if (!expression.text.startsWith("\"\"\"") || !expression.text.endsWith("\"\"\"")) return null
        return expression.literalValue()
    }

    private fun KtStringTemplateExpression.literalValue(): String? {
        if (entries.any { it is KtStringTemplateEntryWithExpression }) return null
        if (entries.any { it !is KtLiteralStringTemplateEntry }) return null
        return entries.joinToString(separator = "") { it.text }
    }

    private fun KtClass.isExplicitlyNonPublic(): Boolean =
        hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.INTERNAL_KEYWORD)

    private fun parseSql(sql: String): ParsedSql {
        val words = scanSql(sql)
        val selectWords = words.filter { it.word == "select" }
        val fromWords = words.filter { it.word == "from" }
        val whereWords = words.filter { it.word == "where" }
        val orderWords = words.filter { it.word == "order" }
        require(selectWords.size == 1 && fromWords.size == 1 && whereWords.size == 1) {
            "SQL must contain one top-level SELECT, FROM, and WHERE"
        }
        require(orderWords.size == 1) { "SQL must contain one top-level ORDER BY" }
        val orderIndex = words.indexOf(orderWords.single())
        require(words.getOrNull(orderIndex + 1)?.word == "by") { "ORDER must be followed by BY" }
        val select = selectWords.single()
        val from = fromWords.single()
        val where = whereWords.single()
        val order = orderWords.single()
        require(sql.substring(0, select.start).isBlank()) { "SELECT must begin the statement" }
        require(select.end < from.start && from.end < where.start && where.end < order.start) {
            "SQL clauses must occur in SELECT/FROM/WHERE/ORDER BY order"
        }
        require(words.none { it.start > order.start && it.word in setOf("select", "from", "where", "order") }) {
            "unexpected additional top-level SQL clause"
        }

        val selectClause = sql.substring(select.end, from.start).trim()
        val fromClause = sql.substring(from.start, where.start).trim()
        val whereClause = sql.substring(where.start, order.start).trim()
        val orderClause = sql.substring(order.start).trim()
        val items = splitTopLevel(selectClause, ',').map(::parseSelectItem)
        return ParsedSql(selectClause, items, fromClause, whereClause, orderClause)
    }

    private fun scanSql(sql: String): List<SqlWord> {
        val words = mutableListOf<SqlWord>()
        var index = 0
        var depth = 0
        var quoted = false
        while (index < sql.length) {
            val char = sql[index]
            if (quoted) {
                if (char == '\'' && index + 1 < sql.length && sql[index + 1] == '\'') {
                    index += 2
                } else {
                    if (char == '\'') quoted = false
                    index += 1
                }
                continue
            }
            when {
                char == '\'' -> {
                    quoted = true
                    index += 1
                }
                char == '(' -> {
                    depth += 1
                    index += 1
                }
                char == ')' -> {
                    require(depth > 0) { "unbalanced closing parenthesis" }
                    depth -= 1
                    index += 1
                }
                char == ';' -> throw IllegalArgumentException("multiple statements are forbidden")
                char == '-' && sql.getOrNull(index + 1) == '-' ->
                    throw IllegalArgumentException("SQL comments are forbidden")
                char == '/' && sql.getOrNull(index + 1) == '*' ->
                    throw IllegalArgumentException("SQL comments are forbidden")
                depth == 0 && (char.isLetter() || char == '_') -> {
                    val start = index
                    index += 1
                    while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) {
                        index += 1
                    }
                    words += SqlWord(sql.substring(start, index).lowercase(Locale.ROOT), start, index)
                }
                else -> index += 1
            }
        }
        require(!quoted) { "unbalanced single-quoted literal" }
        require(depth == 0) { "unbalanced parentheses" }
        return words
    }

    private fun splitTopLevel(source: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var index = 0
        var depth = 0
        var quoted = false
        while (index < source.length) {
            val char = source[index]
            if (quoted) {
                if (char == '\'' && source.getOrNull(index + 1) == '\'') {
                    index += 2
                } else {
                    if (char == '\'') quoted = false
                    index += 1
                }
                continue
            }
            when (char) {
                '\'' -> quoted = true
                '(' -> depth += 1
                ')' -> {
                    require(depth > 0) { "unbalanced closing parenthesis in SELECT" }
                    depth -= 1
                }
                delimiter -> if (depth == 0) {
                    val part = source.substring(start, index).trim()
                    require(part.isNotEmpty()) { "empty SELECT item" }
                    parts += part
                    start = index + 1
                }
            }
            index += 1
        }
        require(!quoted) { "unbalanced single-quoted literal in SELECT" }
        require(depth == 0) { "unbalanced parentheses in SELECT" }
        val final = source.substring(start).trim()
        require(final.isNotEmpty()) { "empty final SELECT item" }
        parts += final
        return parts
    }

    private fun parseSelectItem(item: String): SelectItem {
        val asWords = scanSql(item).filter { it.word == "as" }
        require(asWords.size == 1) { "each SELECT item must contain exactly one top-level AS" }
        val separator = asWords.single()
        val expression = item.substring(0, separator.start).trim()
        val alias = item.substring(separator.end).trim().lowercase(Locale.ROOT)
        require(expression.isNotEmpty()) { "SELECT expression must not be empty" }
        require(alias.isSqlIdentifier()) { "SELECT alias must be one unquoted identifier" }
        return SelectItem(expression, alias)
    }

    private fun String.isSqlIdentifier(): Boolean =
        isNotEmpty() &&
            (first().isLetter() || first() == '_') &&
            drop(1).all { it.isLetterOrDigit() || it == '_' }

    private fun normalizeSql(source: String): String = buildString(source.length) {
        var index = 0
        var quoted = false
        while (index < source.length) {
            val char = source[index]
            if (quoted) {
                append(char)
                if (char == '\'' && source.getOrNull(index + 1) == '\'') {
                    append(source[index + 1])
                    index += 2
                    continue
                }
                if (char == '\'') quoted = false
            } else if (char == '\'') {
                quoted = true
                append(char)
            } else if (!char.isWhitespace()) {
                append(char.lowercaseChar())
            }
            index += 1
        }
        require(!quoted) { "unbalanced single-quoted literal during normalization" }
    }

    private fun expectedSelectExpressions(): LinkedHashMap<String, String> {
        val expressions = linkedMapOf<String, String>()
        JOURNAL_COLUMNS.forEach { contract ->
            expressions[contract.column] =
                "CASE WHEN ${contract.safePredicate} THEN operation.${contract.column} " +
                    "ELSE ${contract.fallback} END"
            expressions["${contract.column}_storage_type"] = "typeof(operation.${contract.column})"
            expressions["${contract.column}_storage_quote"] = "quote(operation.${contract.column})"
        }
        expressions["has_canonical_storage"] =
            "CASE WHEN ${JOURNAL_COLUMNS.joinToString(" AND ") { it.canonicalPredicate }} " +
                "THEN 1 ELSE 0 END"
        return expressions
    }

    private fun expectedProjectionParameters(): List<ProjectionParameter> = buildList {
        JOURNAL_COLUMNS.forEach { contract ->
            val property = contract.column.toLowerCamelCase()
            add(ProjectionParameter(property, contract.column, contract.kotlinType, contract.nullable))
            add(ProjectionParameter("${property}StorageType", "${contract.column}_storage_type", "String"))
            add(ProjectionParameter("${property}StorageQuote", "${contract.column}_storage_quote", "String"))
        }
        add(ProjectionParameter("hasCanonicalStorage", "has_canonical_storage", "Boolean"))
    }

    private fun String.toLowerCamelCase(): String =
        split('_').mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar(Char::uppercaseChar)
        }.joinToString("")

    private data class SqlWord(val word: String, val start: Int, val end: Int)
    private data class SelectItem(val expression: String, val alias: String)
    private data class ParsedSql(
        val selectClause: String,
        val items: List<SelectItem>,
        val fromClause: String,
        val whereClause: String,
        val orderClause: String,
    )
    private data class ProjectionParameter(
        val property: String,
        val column: String,
        val type: String,
        val nullable: Boolean = false,
    )
    private data class JournalColumn(
        val column: String,
        val kotlinType: String,
        val nullable: Boolean,
        val safePredicate: String,
        val fallback: String,
        val canonicalPredicate: String,
    )

    private companion object {
        const val DAO_NAME = "GuidedSessionDao"
        const val LOADER_NAME = "loadGuidedSessionBootstrap"
        const val QUERY_NAME = "findCaptureFileOperations"
        const val PROJECTION_TYPE = "CaptureFileOperationProjection"
        const val PUBLIC_ROW_TYPE = "GuidedCaptureFileOperationAuthorityRow"
        val CONSTITUENT_READS = listOf(
            "findSession",
            "findOwningShoot",
            "findOwningPoses",
            "findAttempts",
            "findPrivateOutputs",
            "findReceipts",
            "findOutboxes",
            "findExportOutputs",
            QUERY_NAME,
        )
        val COMPLETE_BOOTSTRAP_ARGUMENTS = setOf(
            "shoot",
            "session",
            "poses",
            "attempts",
            "privateOutputs",
            "receipts",
            "outboxes",
            "exportOutputs",
            "captureFileOperations",
        )
        const val EXPECTED_FROM =
            "FROM capture_file_operations AS operation " +
                "INNER JOIN capture_attempts AS attempt " +
                "ON CAST(operation.command_token AS BLOB) = " +
                "CAST(attempt.command_token AS BLOB)"
        const val EXPECTED_WHERE =
            "WHERE CAST(attempt.session_id AS BLOB) = CAST(:sessionId AS BLOB)"
        const val EXPECTED_ORDER =
            "ORDER BY attempt.attempt_number ASC, operation.burst_ordinal ASC, " +
                "operation.command_token ASC"
        val JOURNAL_COLUMNS = listOf(
            textColumn("command_token", "String"),
            integerColumn(
                "burst_ordinal",
                "Int",
                safeSuffix = " AND operation.burst_ordinal BETWEEN 0 AND 2",
                canonicalQuote = "quote(operation.burst_ordinal) IN ('0', '1', '2')",
            ),
            textColumn("relative_final_path", "String"),
            textColumn("relative_temp_path", "String"),
            textColumn("relative_quarantine_path", "String"),
            textColumn("stage", "String"),
            nullableIntegerColumn("byte_count"),
            nullableTextColumn("sha256"),
            nullableIntegerColumn("captured_at_epoch_millis"),
            nullableTextColumn("last_failure_code"),
            integerColumn(
                "reconciliation_required",
                "Boolean",
                safeSuffix = " AND operation.reconciliation_required IN (0, 1)",
                canonicalQuote =
                    "quote(operation.reconciliation_required) IN ('0', '1')",
            ),
            integerColumn("created_at_epoch_millis", "Long"),
            integerColumn("updated_at_epoch_millis", "Long"),
        )

        fun textColumn(column: String, kotlinType: String) = JournalColumn(
            column = column,
            kotlinType = kotlinType,
            nullable = false,
            safePredicate = "typeof(operation.$column) = 'text'",
            fallback = "''",
            canonicalPredicate =
                "(typeof(operation.$column) = 'text' AND quote(operation.$column) <> 'NULL')",
        )

        fun nullableTextColumn(column: String) = JournalColumn(
            column = column,
            kotlinType = "String",
            nullable = true,
            safePredicate = "typeof(operation.$column) IN ('null', 'text')",
            fallback = "NULL",
            canonicalPredicate =
                "((typeof(operation.$column) = 'null' AND quote(operation.$column) = 'NULL') OR " +
                    "(typeof(operation.$column) = 'text' AND quote(operation.$column) <> 'NULL'))",
        )

        fun integerColumn(
            column: String,
            kotlinType: String,
            safeSuffix: String = "",
            canonicalQuote: String = "quote(operation.$column) <> 'NULL'",
        ) = JournalColumn(
            column = column,
            kotlinType = kotlinType,
            nullable = false,
            safePredicate = "typeof(operation.$column) = 'integer'$safeSuffix",
            fallback = "0",
            canonicalPredicate =
                "(typeof(operation.$column) = 'integer' AND $canonicalQuote)",
        )

        fun nullableIntegerColumn(column: String) = JournalColumn(
            column = column,
            kotlinType = "Long",
            nullable = true,
            safePredicate = "typeof(operation.$column) IN ('null', 'integer')",
            fallback = "NULL",
            canonicalPredicate =
                "((typeof(operation.$column) = 'null' AND quote(operation.$column) = 'NULL') OR " +
                    "(typeof(operation.$column) = 'integer' AND quote(operation.$column) <> 'NULL'))",
        )
    }
}
