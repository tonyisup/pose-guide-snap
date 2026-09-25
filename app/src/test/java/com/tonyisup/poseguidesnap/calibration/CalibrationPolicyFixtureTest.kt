package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.match.MatchPolicy
import com.tonyisup.poseguidesnap.domain.match.FramingPolicy
import com.tonyisup.poseguidesnap.domain.session.ShootTimingPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CalibrationPolicyFixtureTest {
    @Test
    fun developmentPolicyFixtureExactlyMatchesProductionDevelopmentDefaults() {
        val root = resourceObject("calibration/development-policy-v1.json")
        val match = root.getValue("match").jsonObject
        val framing = root.getValue("framing").jsonObject
        val timing = root.getValue("timing").jsonObject
        val matchPolicy = MatchPolicy.developmentDefaults()
        val framingPolicy = FramingPolicy.developmentDefaults()
        val timingPolicy = ShootTimingPolicy.uncalibratedDevelopmentDefaults()

        assertEquals("uncalibrated", root.getValue("status").jsonPrimitive.content)
        assertEquals(
            framingPolicy.minimumLandmarkConfidence,
            framing.getValue("minimumLandmarkConfidence").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            framingPolicy.minimumSharedLandmarkCount,
            framing.getValue("minimumSharedLandmarkCount").jsonPrimitive.int,
        )
        assertEquals(
            framingPolicy.centerErrorAtZeroSimilarity,
            framing.getValue("centerErrorAtZeroSimilarity").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.minimumLandmarkCoverage,
            match.getValue("minimumLandmarkCoverage").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.minimumFramingScore,
            match.getValue("minimumFramingScore").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.minimumAngularSimilarity,
            match.getValue("minimumAngularSimilarity").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.minimumPositionalSimilarity,
            match.getValue("minimumPositionalSimilarity").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.minimumOverallMatch,
            match.getValue("minimumOverallMatch").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.releaseMinimumLandmarkCoverage,
            match.getValue("releaseMinimumLandmarkCoverage").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.releaseMinimumFramingScore,
            match.getValue("releaseMinimumFramingScore").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.releaseMinimumAngularSimilarity,
            match.getValue("releaseMinimumAngularSimilarity").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.releaseMinimumPositionalSimilarity,
            match.getValue("releaseMinimumPositionalSimilarity").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            matchPolicy.releaseMinimumOverallMatch,
            match.getValue("releaseMinimumOverallMatch").jsonPrimitive.double,
            0.0,
        )
        assertEquals(
            timingPolicy.acquireDwellNanos / NANOS_PER_MILLISECOND,
            timing.getValue("acquireDwellMs").jsonPrimitive.int.toLong(),
        )
        assertEquals(
            timingPolicy.releaseHysteresisNanos / NANOS_PER_MILLISECOND,
            timing.getValue("releaseHysteresisMs").jsonPrimitive.int.toLong(),
        )
    }

    @Test
    fun bundledCalibrationDatasetDeclaresSyntheticNonImageBoundary() {
        val root = resourceObject("calibration/synthetic-contract-v1.json")

        assertEquals("public-synthetic", root.getValue("authorization").jsonPrimitive.content)
        assertFalse(root.getValue("containsPrivateImages").jsonPrimitive.content.toBooleanStrict())
        assertEquals(8, root.getValue("sequences").jsonArray.size)
    }

    private fun resourceObject(name: String) = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing resource $name" }
            .readText(),
    ).jsonObject

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
