package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.architecture.GuidedSessionTask3BSourceOracle
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSessionDaoTransactionContractTest {
    @Test
    fun loadGuidedSessionBootstrapIsTransactionalAndReadsJournalNinth() {
        GuidedSessionTask3BSourceOracle().use { oracle ->
            assertNoViolations(
                "GuidedSessionDao bootstrap loader PSI contract",
                oracle.validateLoader(guidedSessionDaoSource()),
            )
        }
    }

    @Test
    fun journalQueryIsExactSessionJoinedAndOrdered() {
        GuidedSessionTask3BSourceOracle().use { oracle ->
            assertNoViolations(
                "GuidedSessionDao journal PSI/SQL contract",
                oracle.validateJournal(guidedSessionDaoSource()) +
                    oracle.validatePublicContract(guidedSessionContractsSource()),
            )
        }
    }

    private fun guidedSessionDaoSource(): String = sourceFile(GUIDED_SESSION_DAO_SOURCE_PATH)

    private fun guidedSessionContractsSource(): String =
        sourceFile(GUIDED_SESSION_CONTRACTS_SOURCE_PATH)

    private fun sourceFile(relativePath: String): String {
        val source = projectRoot().resolve(relativePath)
        assertTrue("Required source file does not exist: $relativePath", source.isFile)
        return source.readText()
    }

    private fun assertNoViolations(label: String, violations: List<String>) {
        assertTrue("$label violations:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { candidate -> candidate.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private companion object {
        const val GUIDED_SESSION_DAO_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/db/GuidedSessionDao.kt"
        const val GUIDED_SESSION_CONTRACTS_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/GuidedSessionContracts.kt"
    }
}

class GuidedSessionTask3BSourceOracleMutationTest {
    @Test
    fun psiOracleRejectsCommentDecoysAndConstantPropagation() {
        GuidedSessionTask3BSourceOracle().use { oracle ->
            val sql = oracle.canonicalSqlForTesting()
            val validDao = daoFixture(sql)
            assertAccepted("valid loader fixture", oracle.validateLoader(validDao))
            assertAccepted("valid journal fixture", oracle.validateJournal(validDao))
            assertAccepted(
                "valid public row fixture",
                oracle.validatePublicContract(publicContractFixture()),
            )

            assertRejected(
                "commented capture assignment/read/map",
                oracle.validateLoader(daoFixture(sql, includeCaptureArgument = false)),
            )
            assertRejected(
                "dead executable capture constructor",
                oracle.validateLoader(
                    daoFixture(
                        sql,
                        includeCaptureArgument = false,
                        includeDeadCaptureDecoy = true,
                    ),
                ),
            )
            assertRejected(
                "unconditional return before the expected loader tail",
                oracle.validateLoader(
                    daoFixture(sql).replace(
                        "val session = findSession(sessionId)",
                        "return GuidedSessionBootstrapRows(shoot = null, session = null)\n" +
                            "        val session = findSession(sessionId)",
                    ),
                ),
            )
            assertRejected(
                "tenth direct DAO read",
                oracle.validateLoader(
                    daoFixture(sql).replace(
                        "val session = findSession(sessionId)",
                        "findSessionsForShoot(sessionId)\n" +
                            "        val session = findSession(sessionId)",
                    ),
                ),
            )
            assertRejected(
                "foreign receiver with expected simple callee name",
                oracle.validateLoader(
                    daoFixture(sql).replace(
                        "shoot = findOwningShoot(sessionId)",
                        "shoot = foreign.findOwningShoot(sessionId)",
                    ),
                ),
            )
            assertRejected(
                "commented Transaction annotation",
                oracle.validateLoader(daoFixture(sql, includeTransactionAnnotation = false)),
            )
            assertRejected(
                "non-Room Transaction annotation",
                oracle.validateLoader(
                    daoFixture(sql).replace(
                        "import androidx.room.Transaction",
                        "import fake.Transaction",
                    ),
                ),
            )
            assertRejected(
                "commented Query annotation",
                oracle.validateJournal(daoFixture(sql, includeQueryAnnotation = false)),
            )
            assertRejected(
                "non-Room Query annotation",
                oracle.validateJournal(
                    daoFixture(sql).replace("import androidx.room.Query", "import fake.Query"),
                ),
            )
            assertRejected(
                "constant canonical conversion argument with comment decoy",
                oracle.validateJournal(
                    daoFixture(
                        sql,
                        canonicalConversionArgument = "true /* hasCanonicalStorage */",
                    ),
                ),
            )
            assertRejected(
                "constant stage conversion argument",
                oracle.validateJournal(
                    daoFixture(
                        sql,
                        stageConversionArgument = "\"EXPECTING_RESERVATION\"",
                    ),
                ),
            )
        }
    }

    @Test
    fun sqlParserRejectsWrappedPrefixedDuplicateAndUnsafeAliases() {
        GuidedSessionTask3BSourceOracle().use { oracle ->
            val valid = oracle.canonicalSqlForTesting()
            assertAccepted("canonical SQL fixture", oracle.validateSql(valid))

            val mutations = linkedMapOf(
                "wrapped canonical CASE" to replaceSelectExpression(
                    valid,
                    "has_canonical_storage",
                ) { expression -> "($expression)" },
                "prefixed typed expression" to replaceSelectExpression(
                    valid,
                    "burst_ordinal",
                ) { expression -> "1 OR $expression" },
                "duplicate alias" to valid.replace(
                    " AS burst_ordinal_storage_type",
                    " AS command_token",
                ),
                "unsafe first alias" to replaceSelectExpression(
                    valid,
                    "command_token",
                ) { "operation.command_token" },
                "extra top-level AS" to replaceSelectExpression(
                    valid,
                    "stage",
                ) { expression -> "$expression AS stage_shadow" },
                "SQL comment" to "$valid\n-- decoy",
                "multiple statements" to "$valid; SELECT 1",
                "changed literal case" to valid.replaceFirst("'text'", "'TEXT'"),
                "lowercase SQL NULL quote literal" to valid.replace("'NULL'", "'null'"),
                "unbalanced parentheses" to valid.replace(
                    "typeof(operation.byte_count)",
                    "typeof((operation.byte_count)",
                ),
            )
            mutations.forEach { (label, mutation) ->
                assertRejected(label, oracle.validateSql(mutation))
            }
        }
    }

    private fun daoFixture(
        sql: String,
        includeCaptureArgument: Boolean = true,
        includeDeadCaptureDecoy: Boolean = false,
        includeTransactionAnnotation: Boolean = true,
        includeQueryAnnotation: Boolean = true,
        stageConversionArgument: String = "stage",
        canonicalConversionArgument: String = "hasCanonicalStorage",
    ): String {
        val transaction = if (includeTransactionAnnotation) {
            "    @Transaction"
        } else {
            "    // @Transaction comment decoy"
        }
        val query = if (includeQueryAnnotation) {
            "    @Query(\"\"\"\n$sql\n    \"\"\")"
        } else {
            "    // @Query comment decoy"
        }
        val captureArgument = if (includeCaptureArgument) {
            "            captureFileOperations = " +
                "findCaptureFileOperations(sessionId).map(" +
                "CaptureFileOperationProjection::toAuthorityRow),"
        } else {
            "            // captureFileOperations = " +
                "findCaptureFileOperations(sessionId).map(" +
                "CaptureFileOperationProjection::toAuthorityRow),"
        }
        val projectionParameters = fixtureProjectionParameters().joinToString(",\n") { parameter ->
            "    @ColumnInfo(name = \"${parameter.column}\") " +
                "val ${parameter.property}: ${parameter.type}"
        }
        val deadCaptureDecoy = if (includeDeadCaptureDecoy) {
            """
                    if (false) GuidedSessionBootstrapRows(
                        captureFileOperations = findCaptureFileOperations(sessionId).map(
                            CaptureFileOperationProjection::toAuthorityRow,
                        ),
                    )
            """.trimIndent()
        } else {
            ""
        }
        return """
            import androidx.room.ColumnInfo
            import androidx.room.Dao
            import androidx.room.Query
            import androidx.room.Transaction

            @Dao
            internal abstract class GuidedSessionDao {
            $transaction
                open fun loadGuidedSessionBootstrap(sessionId: String): GuidedSessionBootstrapRows {
                    val session = findSession(sessionId)
                        ?: return GuidedSessionBootstrapRows(shoot = null, session = null)
            $deadCaptureDecoy
                    return GuidedSessionBootstrapRows(
                        shoot = findOwningShoot(sessionId)?.toAuthorityRow(),
                        session = session.toAuthorityRow(),
                        poses = findOwningPoses(sessionId).map(ShootPoseEntity::toAuthorityRow),
                        attempts = findAttempts(sessionId).map(CaptureAttemptEntity::toAuthorityRow),
                        privateOutputs = findPrivateOutputs(sessionId)
                            .map(PrivateCaptureOutputEntity::toAuthorityRow),
                        receipts = findReceipts(sessionId)
                            .map(CaptureConfirmationReceiptEntity::toAuthorityRow),
                        outboxes = findOutboxes(sessionId)
                            .map(CaptureExportOutboxEntity::toAuthorityRow),
                        exportOutputs = findExportOutputs(sessionId)
                            .map(CaptureExportOutputEntity::toAuthorityRow),
            $captureArgument
                    )
                }

            $query
                protected abstract fun findCaptureFileOperations(
                    sessionId: String,
                ): List<CaptureFileOperationProjection>

                protected abstract fun findSession(sessionId: String): Session?
                protected abstract fun findOwningShoot(sessionId: String): Shoot?
                protected abstract fun findOwningPoses(sessionId: String): List<Pose>
                protected abstract fun findAttempts(sessionId: String): List<Attempt>
                protected abstract fun findPrivateOutputs(sessionId: String): List<PrivateOutput>
                protected abstract fun findReceipts(sessionId: String): List<Receipt>
                protected abstract fun findOutboxes(sessionId: String): List<Outbox>
                protected abstract fun findExportOutputs(sessionId: String): List<ExportOutput>
                protected abstract fun findSessionsForShoot(sessionId: String): List<Session>
            }

            private data class CaptureFileOperationProjection(
            $projectionParameters,
            ) {
                override fun toString(): String = "CaptureFileOperationProjection(redacted)"
            }

            private fun CaptureFileOperationProjection.toAuthorityRow():
                GuidedCaptureFileOperationAuthorityRow =
                GuidedCaptureFileOperationAuthorityRow(
                    commandToken = commandToken,
                    burstOrdinal = burstOrdinal,
                    relativeFinalPath = relativeFinalPath,
                    relativeTempPath = relativeTempPath,
                    relativeQuarantinePath = relativeQuarantinePath,
                    stage = $stageConversionArgument,
                    byteCount = byteCount,
                    sha256 = sha256,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    lastFailureCode = lastFailureCode,
                    reconciliationRequired = reconciliationRequired,
                    createdAtEpochMillis = createdAtEpochMillis,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    hasCanonicalStorage = $canonicalConversionArgument,
                )
        """.trimIndent()
    }

    private fun publicContractFixture(): String = """
        data class GuidedCaptureFileOperationAuthorityRow(
            val commandToken: String,
            val burstOrdinal: Int,
            val relativeFinalPath: String,
            val relativeTempPath: String,
            val relativeQuarantinePath: String,
            val stage: String,
            val byteCount: Long?,
            val sha256: String?,
            val capturedAtEpochMillis: Long?,
            val lastFailureCode: String?,
            val reconciliationRequired: Boolean,
            val createdAtEpochMillis: Long,
            val updatedAtEpochMillis: Long,
            val hasCanonicalStorage: Boolean = true,
        )
    """.trimIndent()

    private fun fixtureProjectionParameters(): List<FixtureParameter> = buildList {
        val typed = listOf(
            FixtureParameter("commandToken", "command_token", "String"),
            FixtureParameter("burstOrdinal", "burst_ordinal", "Int"),
            FixtureParameter("relativeFinalPath", "relative_final_path", "String"),
            FixtureParameter("relativeTempPath", "relative_temp_path", "String"),
            FixtureParameter("relativeQuarantinePath", "relative_quarantine_path", "String"),
            FixtureParameter("stage", "stage", "String"),
            FixtureParameter("byteCount", "byte_count", "Long?"),
            FixtureParameter("sha256", "sha256", "String?"),
            FixtureParameter("capturedAtEpochMillis", "captured_at_epoch_millis", "Long?"),
            FixtureParameter("lastFailureCode", "last_failure_code", "String?"),
            FixtureParameter("reconciliationRequired", "reconciliation_required", "Boolean"),
            FixtureParameter("createdAtEpochMillis", "created_at_epoch_millis", "Long"),
            FixtureParameter("updatedAtEpochMillis", "updated_at_epoch_millis", "Long"),
        )
        typed.forEach { parameter ->
            add(parameter)
            add(
                FixtureParameter(
                    "${parameter.property}StorageType",
                    "${parameter.column}_storage_type",
                    "String",
                ),
            )
            add(
                FixtureParameter(
                    "${parameter.property}StorageQuote",
                    "${parameter.column}_storage_quote",
                    "String",
                ),
            )
        }
        add(FixtureParameter("hasCanonicalStorage", "has_canonical_storage", "Boolean"))
    }

    private fun replaceSelectExpression(
        sql: String,
        alias: String,
        transform: (String) -> String,
    ): String {
        val marker = " AS $alias"
        var replaced = false
        val result = sql.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trimEnd()
            val comma = if (trimmed.endsWith(',')) "," else ""
            val item = trimmed.removeSuffix(",")
            if (!replaced && item.endsWith(marker)) {
                replaced = true
                val markerIndex = item.lastIndexOf(marker)
                val indentation = line.takeWhile(Char::isWhitespace)
                indentation +
                    transform(item.substring(indentation.length, markerIndex)) +
                    marker +
                    comma
            } else {
                line
            }
        }
        check(replaced) { "Missing fixture SELECT alias $alias" }
        return result
    }

    private fun assertAccepted(label: String, violations: List<String>) {
        assertTrue("$label unexpectedly rejected:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun assertRejected(label: String, violations: List<String>) {
        assertTrue("$label bypassed the oracle", violations.isNotEmpty())
    }

    private data class FixtureParameter(
        val property: String,
        val column: String,
        val type: String,
    )
}
