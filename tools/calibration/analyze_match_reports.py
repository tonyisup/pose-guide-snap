#!/usr/bin/env python3
"""Analyze privacy-bounded Pose Guide Snap match-report datasets.

The accepted input contains derived scalar scores and event counts only. The schema deliberately
has no field for images, URIs, filesystem paths, or raw landmarks.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence


SCHEMA_VERSION = 1
DATASET_SCHEMA_VERSIONS = {1, 2, 3}
MAX_FRAMES_PER_SEQUENCE = 600
DATASET_KEYS = {
    "schemaVersion",
    "datasetId",
    "provenance",
    "authorization",
    "populationLimits",
    "containsPrivateImages",
    "sequences",
}
SEQUENCE_KEYS = {"sequenceId", "fixtureClass", "caseClass", "frames"}
FRAME_KEYS = {
    "elapsedMs",
    "detectedPersonCount",
    "landmarkCoverage",
    "framingScore",
    "angularSimilarity",
    "positionalSimilarity",
    "overallMatch",
    "mirrorUsed",
    "inferenceLatencyMs",
    "cueEmitted",
    "captureCommands",
}
FRAME_DIAGNOSTIC_KEYS = {
    "evaluationStatus",
    "confidenceQualifiedLandmarkCount",
    "qualifiedTorsoAnchorCount",
}
FRAME_DETECTOR_SCORE_KEYS = {
    "maximumValidPersonScore",
    "maximumValidKeypointScore",
}
POLICY_KEYS = {"schemaVersion", "policyId", "status", "framing", "match", "timing"}
FRAMING_POLICY_KEYS = {
    "minimumLandmarkConfidence",
    "minimumSharedLandmarkCount",
    "centerErrorAtZeroSimilarity",
}
MATCH_POLICY_KEYS = {
    "minimumLandmarkCoverage",
    "minimumFramingScore",
    "minimumAngularSimilarity",
    "minimumPositionalSimilarity",
    "minimumOverallMatch",
    "releaseMinimumLandmarkCoverage",
    "releaseMinimumFramingScore",
    "releaseMinimumAngularSimilarity",
    "releaseMinimumPositionalSimilarity",
    "releaseMinimumOverallMatch",
}
TIMING_POLICY_KEYS = {"acquireDwellMs", "releaseHysteresisMs"}


class ValidationError(ValueError):
    """Raised when a report violates the deliberately narrow interchange schema."""


@dataclass(frozen=True)
class MatchThresholds:
    minimum_landmark_coverage: float
    minimum_framing_score: float
    minimum_angular_similarity: float
    minimum_positional_similarity: float
    minimum_overall_match: float
    release_minimum_landmark_coverage: float
    release_minimum_framing_score: float
    release_minimum_angular_similarity: float
    release_minimum_positional_similarity: float
    release_minimum_overall_match: float


@dataclass(frozen=True)
class FramingPolicy:
    minimum_landmark_confidence: float
    minimum_shared_landmark_count: int
    center_error_at_zero_similarity: float


@dataclass(frozen=True)
class TimingPolicy:
    acquire_dwell_ms: int
    release_hysteresis_ms: int


@dataclass(frozen=True)
class Policy:
    policy_id: str
    status: str
    framing: FramingPolicy
    match: MatchThresholds
    timing: TimingPolicy


@dataclass(frozen=True)
class Frame:
    elapsed_ms: int
    detected_person_count: int
    evaluation_status: str | None
    confidence_qualified_landmark_count: int | None
    qualified_torso_anchor_count: int | None
    maximum_valid_person_score: float | None
    maximum_valid_keypoint_score: float | None
    landmark_coverage: float
    framing_score: float
    angular_similarity: float
    positional_similarity: float
    overall_match: float
    mirror_used: bool
    inference_latency_ms: float | None
    cue_emitted: bool | None
    capture_commands: int | None

    def eligible_for_acquisition(self, thresholds: MatchThresholds) -> bool:
        return (
            self.evaluation_status in {None, "evaluated"}
            and self.detected_person_count == 1
            and self.landmark_coverage >= thresholds.minimum_landmark_coverage
            and self.framing_score >= thresholds.minimum_framing_score
            and self.angular_similarity >= thresholds.minimum_angular_similarity
            and self.positional_similarity >= thresholds.minimum_positional_similarity
            and self.overall_match >= thresholds.minimum_overall_match
        )

    def eligible_for_retention(self, thresholds: MatchThresholds) -> bool:
        return (
            self.evaluation_status in {None, "evaluated"}
            and self.detected_person_count == 1
            and self.landmark_coverage >= thresholds.release_minimum_landmark_coverage
            and self.framing_score >= thresholds.release_minimum_framing_score
            and self.angular_similarity >= thresholds.release_minimum_angular_similarity
            and self.positional_similarity >= thresholds.release_minimum_positional_similarity
            and self.overall_match >= thresholds.release_minimum_overall_match
        )


@dataclass(frozen=True)
class SequenceReport:
    dataset_id: str
    sequence_id: str
    fixture_class: str
    case_class: str
    frames: tuple[Frame, ...]


@dataclass(frozen=True)
class ReplayResult:
    first_lock_ms: int | None
    lock_transitions: int
    unlock_transitions: int


def _expect_object(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValidationError(f"{context} must be a JSON object")
    return value


def _reject_unknown_keys(value: dict[str, Any], allowed: set[str], context: str) -> None:
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise ValidationError(f"{context} contains unsupported fields: {', '.join(unknown)}")


def _required_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{context} must be a nonblank string")
    return value


def _required_bool(value: Any, context: str) -> bool:
    if not isinstance(value, bool):
        raise ValidationError(f"{context} must be a boolean")
    return value


def _required_int(value: Any, context: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValidationError(f"{context} must be an integer >= {minimum}")
    return value


def _required_number(
    value: Any,
    context: str,
    *,
    minimum: float = 0.0,
    maximum: float | None = None,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"{context} must be numeric")
    result = float(value)
    if not math.isfinite(result) or result < minimum or (maximum is not None and result > maximum):
        upper = "infinity" if maximum is None else str(maximum)
        raise ValidationError(f"{context} must be finite and in [{minimum}, {upper}]")
    return result


def _optional_number(
    value: Any,
    context: str,
    *,
    maximum: float | None = None,
) -> float | None:
    if value is None:
        return None
    return _required_number(value, context, maximum=maximum)


def _optional_bool(value: Any, context: str) -> bool | None:
    if value is None:
        return None
    return _required_bool(value, context)


def _optional_int(value: Any, context: str) -> int | None:
    if value is None:
        return None
    return _required_int(value, context)


def load_policy(path: Path) -> Policy:
    raw = _expect_object(json.loads(path.read_text(encoding="utf-8")), "policy")
    _reject_unknown_keys(raw, POLICY_KEYS, "policy")
    if raw.get("schemaVersion") != SCHEMA_VERSION:
        raise ValidationError(f"policy.schemaVersion must be {SCHEMA_VERSION}")

    framing = _expect_object(raw.get("framing"), "policy.framing")
    match = _expect_object(raw.get("match"), "policy.match")
    timing = _expect_object(raw.get("timing"), "policy.timing")
    _reject_unknown_keys(framing, FRAMING_POLICY_KEYS, "policy.framing")
    _reject_unknown_keys(match, MATCH_POLICY_KEYS, "policy.match")
    _reject_unknown_keys(timing, TIMING_POLICY_KEYS, "policy.timing")
    missing_framing = sorted(FRAMING_POLICY_KEYS - set(framing))
    missing_match = sorted(MATCH_POLICY_KEYS - set(match))
    missing_timing = sorted(TIMING_POLICY_KEYS - set(timing))
    if missing_framing:
        raise ValidationError(f"policy.framing is missing fields: {', '.join(missing_framing)}")
    if missing_match:
        raise ValidationError(f"policy.match is missing fields: {', '.join(missing_match)}")
    if missing_timing:
        raise ValidationError(f"policy.timing is missing fields: {', '.join(missing_timing)}")

    status = _required_string(raw.get("status"), "policy.status")
    if status not in {"uncalibrated", "candidate", "device-calibrated"}:
        raise ValidationError("policy.status must be uncalibrated, candidate, or device-calibrated")

    framing_policy = FramingPolicy(
        minimum_landmark_confidence=_required_number(
            framing["minimumLandmarkConfidence"],
            "policy.framing.minimumLandmarkConfidence",
            maximum=1.0,
        ),
        minimum_shared_landmark_count=_required_int(
            framing["minimumSharedLandmarkCount"],
            "policy.framing.minimumSharedLandmarkCount",
            minimum=9,
        ),
        center_error_at_zero_similarity=_required_number(
            framing["centerErrorAtZeroSimilarity"],
            "policy.framing.centerErrorAtZeroSimilarity",
        ),
    )
    if framing_policy.minimum_shared_landmark_count > 17:
        raise ValidationError(
            "policy.framing.minimumSharedLandmarkCount must be an integer in [9, 17]"
        )
    if framing_policy.center_error_at_zero_similarity <= 0.0:
        raise ValidationError(
            "policy.framing.centerErrorAtZeroSimilarity must be finite and positive"
        )

    thresholds = MatchThresholds(
        minimum_landmark_coverage=_required_number(
            match["minimumLandmarkCoverage"],
            "policy.match.minimumLandmarkCoverage",
            maximum=1.0,
        ),
        minimum_framing_score=_required_number(
            match["minimumFramingScore"],
            "policy.match.minimumFramingScore",
            maximum=1.0,
        ),
        minimum_angular_similarity=_required_number(
            match["minimumAngularSimilarity"],
            "policy.match.minimumAngularSimilarity",
            maximum=1.0,
        ),
        minimum_positional_similarity=_required_number(
            match["minimumPositionalSimilarity"],
            "policy.match.minimumPositionalSimilarity",
            maximum=1.0,
        ),
        minimum_overall_match=_required_number(
            match["minimumOverallMatch"],
            "policy.match.minimumOverallMatch",
            maximum=1.0,
        ),
        release_minimum_landmark_coverage=_required_number(
            match["releaseMinimumLandmarkCoverage"],
            "policy.match.releaseMinimumLandmarkCoverage",
            maximum=1.0,
        ),
        release_minimum_framing_score=_required_number(
            match["releaseMinimumFramingScore"],
            "policy.match.releaseMinimumFramingScore",
            maximum=1.0,
        ),
        release_minimum_angular_similarity=_required_number(
            match["releaseMinimumAngularSimilarity"],
            "policy.match.releaseMinimumAngularSimilarity",
            maximum=1.0,
        ),
        release_minimum_positional_similarity=_required_number(
            match["releaseMinimumPositionalSimilarity"],
            "policy.match.releaseMinimumPositionalSimilarity",
            maximum=1.0,
        ),
        release_minimum_overall_match=_required_number(
            match["releaseMinimumOverallMatch"],
            "policy.match.releaseMinimumOverallMatch",
            maximum=1.0,
        ),
    )
    release_pairs = (
        (
            thresholds.release_minimum_landmark_coverage,
            thresholds.minimum_landmark_coverage,
            "releaseMinimumLandmarkCoverage",
        ),
        (
            thresholds.release_minimum_framing_score,
            thresholds.minimum_framing_score,
            "releaseMinimumFramingScore",
        ),
        (
            thresholds.release_minimum_angular_similarity,
            thresholds.minimum_angular_similarity,
            "releaseMinimumAngularSimilarity",
        ),
        (
            thresholds.release_minimum_positional_similarity,
            thresholds.minimum_positional_similarity,
            "releaseMinimumPositionalSimilarity",
        ),
        (
            thresholds.release_minimum_overall_match,
            thresholds.minimum_overall_match,
            "releaseMinimumOverallMatch",
        ),
    )
    for release_value, acquire_value, name in release_pairs:
        if release_value > acquire_value:
            raise ValidationError(
                f"policy.match.{name} must not exceed its acquisition threshold"
            )

    return Policy(
        policy_id=_required_string(raw.get("policyId"), "policy.policyId"),
        status=status,
        framing=framing_policy,
        match=thresholds,
        timing=TimingPolicy(
            acquire_dwell_ms=_required_int(
                timing["acquireDwellMs"],
                "policy.timing.acquireDwellMs",
            ),
            release_hysteresis_ms=_required_int(
                timing["releaseHysteresisMs"],
                "policy.timing.releaseHysteresisMs",
            ),
        ),
    )


def load_dataset(path: Path) -> tuple[dict[str, Any], tuple[SequenceReport, ...]]:
    raw = _expect_object(json.loads(path.read_text(encoding="utf-8")), f"dataset {path}")
    _reject_unknown_keys(raw, DATASET_KEYS, f"dataset {path}")
    schema_version = raw.get("schemaVersion")
    if schema_version not in DATASET_SCHEMA_VERSIONS:
        supported = ", ".join(str(value) for value in sorted(DATASET_SCHEMA_VERSIONS))
        raise ValidationError(f"{path}: schemaVersion must be one of {supported}")
    dataset_id = _required_string(raw.get("datasetId"), f"{path}: datasetId")
    provenance = _required_string(raw.get("provenance"), f"{path}: provenance")
    authorization = _required_string(raw.get("authorization"), f"{path}: authorization")
    if authorization not in {"public-synthetic", "user-authorized-derived", "team-authorized-derived"}:
        raise ValidationError(f"{path}: unsupported authorization class")
    if _required_bool(raw.get("containsPrivateImages"), f"{path}: containsPrivateImages"):
        raise ValidationError(f"{path}: calibration inputs must not contain private images")

    limits = raw.get("populationLimits")
    if not isinstance(limits, list) or not limits:
        raise ValidationError(f"{path}: populationLimits must be a nonempty string list")
    population_limits = tuple(
        _required_string(value, f"{path}: populationLimits[{index}]")
        for index, value in enumerate(limits)
    )

    raw_sequences = raw.get("sequences")
    if not isinstance(raw_sequences, list) or not raw_sequences:
        raise ValidationError(f"{path}: sequences must be a nonempty array")
    sequences = tuple(
        _parse_sequence(dataset_id, value, index, path, schema_version)
        for index, value in enumerate(raw_sequences)
    )
    if len({sequence.sequence_id for sequence in sequences}) != len(sequences):
        raise ValidationError(f"{path}: sequenceId values must be unique within a dataset")

    metadata = {
        "schemaVersion": schema_version,
        "datasetId": dataset_id,
        "provenance": provenance,
        "authorization": authorization,
        "populationLimits": list(population_limits),
        "containsPrivateImages": False,
        "sequenceCount": len(sequences),
    }
    return metadata, sequences


def _parse_sequence(
    dataset_id: str,
    value: Any,
    index: int,
    path: Path,
    schema_version: int,
) -> SequenceReport:
    context = f"{path}: sequences[{index}]"
    raw = _expect_object(value, context)
    _reject_unknown_keys(raw, SEQUENCE_KEYS, context)
    sequence_id = _required_string(raw.get("sequenceId"), f"{context}.sequenceId")
    fixture_class = _required_string(raw.get("fixtureClass"), f"{context}.fixtureClass")
    if fixture_class not in {"positive", "negative"}:
        raise ValidationError(f"{context}.fixtureClass must be positive or negative")
    case_class = _required_string(raw.get("caseClass"), f"{context}.caseClass")
    raw_frames = raw.get("frames")
    if not isinstance(raw_frames, list) or not raw_frames:
        raise ValidationError(f"{context}.frames must be a nonempty array")
    if len(raw_frames) > MAX_FRAMES_PER_SEQUENCE:
        raise ValidationError(
            f"{context}.frames must contain at most {MAX_FRAMES_PER_SEQUENCE} values"
        )
    frames = tuple(
        _parse_frame(frame, frame_index, context, schema_version)
        for frame_index, frame in enumerate(raw_frames)
    )
    elapsed = [frame.elapsed_ms for frame in frames]
    if elapsed != sorted(elapsed) or len(set(elapsed)) != len(elapsed):
        raise ValidationError(f"{context}.frames elapsedMs values must be strictly increasing")
    return SequenceReport(dataset_id, sequence_id, fixture_class, case_class, frames)


def _parse_frame(
    value: Any,
    index: int,
    sequence_context: str,
    schema_version: int,
) -> Frame:
    context = f"{sequence_context}.frames[{index}]"
    raw = _expect_object(value, context)
    allowed = set(FRAME_KEYS)
    if schema_version >= 2:
        allowed |= FRAME_DIAGNOSTIC_KEYS
    if schema_version >= 3:
        allowed |= FRAME_DETECTOR_SCORE_KEYS
    _reject_unknown_keys(raw, allowed, context)
    required = FRAME_KEYS - {"inferenceLatencyMs", "cueEmitted", "captureCommands"}
    if schema_version >= 2:
        required |= FRAME_DIAGNOSTIC_KEYS
    if schema_version >= 3:
        required |= FRAME_DETECTOR_SCORE_KEYS
    missing = sorted(required - set(raw))
    if missing:
        raise ValidationError(f"{context} is missing fields: {', '.join(missing)}")
    capture_commands = _optional_int(
        raw.get("captureCommands"),
        f"{context}.captureCommands",
    )
    if capture_commands is not None and capture_commands > 3:
        raise ValidationError(f"{context}.captureCommands must be in [0, 3]")
    evaluation_status: str | None = None
    confidence_qualified_landmark_count: int | None = None
    qualified_torso_anchor_count: int | None = None
    maximum_valid_person_score: float | None = None
    maximum_valid_keypoint_score: float | None = None
    detected_person_count = _required_int(
        raw["detectedPersonCount"],
        f"{context}.detectedPersonCount",
    )
    if schema_version >= 2:
        evaluation_status = _required_string(
            raw["evaluationStatus"],
            f"{context}.evaluationStatus",
        )
        if evaluation_status not in {
            "no-person",
            "multiple-people",
            "canonicalization-failed",
            "evaluated",
        }:
            raise ValidationError(f"{context}.evaluationStatus is unsupported")
        confidence_qualified_landmark_count = _required_int(
            raw["confidenceQualifiedLandmarkCount"],
            f"{context}.confidenceQualifiedLandmarkCount",
        )
        if confidence_qualified_landmark_count > 17:
            raise ValidationError(
                f"{context}.confidenceQualifiedLandmarkCount must be in [0, 17]"
            )
        qualified_torso_anchor_count = _required_int(
            raw["qualifiedTorsoAnchorCount"],
            f"{context}.qualifiedTorsoAnchorCount",
        )
        if qualified_torso_anchor_count > 4:
            raise ValidationError(f"{context}.qualifiedTorsoAnchorCount must be in [0, 4]")
        if qualified_torso_anchor_count > confidence_qualified_landmark_count:
            raise ValidationError(
                f"{context}.qualifiedTorsoAnchorCount cannot exceed "
                "confidenceQualifiedLandmarkCount"
            )
        if evaluation_status == "no-person":
            status_matches_person_count = detected_person_count == 0
        elif evaluation_status == "multiple-people":
            status_matches_person_count = detected_person_count > 1
        else:
            status_matches_person_count = detected_person_count == 1
        if not status_matches_person_count:
            raise ValidationError(
                f"{context}.evaluationStatus must agree with detectedPersonCount"
            )
        if evaluation_status == "evaluated" and qualified_torso_anchor_count != 4:
            raise ValidationError(
                f"{context}.evaluated frames require four qualified torso anchors"
            )
        if evaluation_status == "no-person" and confidence_qualified_landmark_count != 0:
            raise ValidationError(
                f"{context}.no-person frames cannot contain qualified landmarks"
            )
    if schema_version >= 3:
        maximum_valid_person_score = _optional_number(
            raw["maximumValidPersonScore"],
            f"{context}.maximumValidPersonScore",
            maximum=1.0,
        )
        maximum_valid_keypoint_score = _optional_number(
            raw["maximumValidKeypointScore"],
            f"{context}.maximumValidKeypointScore",
            maximum=1.0,
        )
    return Frame(
        elapsed_ms=_required_int(raw["elapsedMs"], f"{context}.elapsedMs"),
        detected_person_count=detected_person_count,
        evaluation_status=evaluation_status,
        confidence_qualified_landmark_count=confidence_qualified_landmark_count,
        qualified_torso_anchor_count=qualified_torso_anchor_count,
        maximum_valid_person_score=maximum_valid_person_score,
        maximum_valid_keypoint_score=maximum_valid_keypoint_score,
        landmark_coverage=_required_number(
            raw["landmarkCoverage"],
            f"{context}.landmarkCoverage",
            maximum=1.0,
        ),
        framing_score=_required_number(
            raw["framingScore"],
            f"{context}.framingScore",
            maximum=1.0,
        ),
        angular_similarity=_required_number(
            raw["angularSimilarity"],
            f"{context}.angularSimilarity",
            maximum=1.0,
        ),
        positional_similarity=_required_number(
            raw["positionalSimilarity"],
            f"{context}.positionalSimilarity",
            maximum=1.0,
        ),
        overall_match=_required_number(
            raw["overallMatch"],
            f"{context}.overallMatch",
            maximum=1.0,
        ),
        mirror_used=_required_bool(raw["mirrorUsed"], f"{context}.mirrorUsed"),
        inference_latency_ms=_optional_number(
            raw.get("inferenceLatencyMs"),
            f"{context}.inferenceLatencyMs",
        ),
        cue_emitted=_optional_bool(raw.get("cueEmitted"), f"{context}.cueEmitted"),
        capture_commands=capture_commands,
    )


def replay(sequence: SequenceReport, policy: Policy) -> ReplayResult:
    candidate_since: int | None = None
    release_since: int | None = None
    locked = False
    first_lock: int | None = None
    lock_transitions = 0
    unlock_transitions = 0

    for frame in sequence.frames:
        eligible = (
            frame.eligible_for_retention(policy.match)
            if locked
            else frame.eligible_for_acquisition(policy.match)
        )
        if not locked:
            release_since = None
            if not eligible:
                candidate_since = None
                continue
            if candidate_since is None:
                candidate_since = frame.elapsed_ms
            if frame.elapsed_ms - candidate_since >= policy.timing.acquire_dwell_ms:
                locked = True
                lock_transitions += 1
                first_lock = frame.elapsed_ms if first_lock is None else first_lock
                candidate_since = None
            continue

        if eligible:
            release_since = None
            continue
        if policy.timing.release_hysteresis_ms == 0:
            locked = False
            unlock_transitions += 1
            continue
        if release_since is None:
            release_since = frame.elapsed_ms
        elif frame.elapsed_ms - release_since >= policy.timing.release_hysteresis_ms:
            locked = False
            unlock_transitions += 1
            release_since = None

    return ReplayResult(first_lock, lock_transitions, unlock_transitions)


def _percentile(values: Sequence[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percentile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def _rate(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _integer_summary(values: Sequence[int]) -> dict[str, float | int | None]:
    return {
        "sampleCount": len(values),
        "minimum": min(values) if values else None,
        "median": _percentile([float(value) for value in values], 0.5),
        "maximum": max(values) if values else None,
    }


def _number_summary(values: Sequence[float]) -> dict[str, float | int | None]:
    return {
        "sampleCount": len(values),
        "minimum": min(values) if values else None,
        "p05": _percentile(values, 0.05),
        "median": _percentile(values, 0.5),
        "p95": _percentile(values, 0.95),
        "maximum": max(values) if values else None,
    }


def _detector_score_distribution(
    sequences: Sequence[SequenceReport],
    field: str,
) -> dict[str, Any]:
    positive = [
        value
        for sequence in sequences
        if sequence.fixture_class == "positive"
        for frame in sequence.frames
        if (value := getattr(frame, field)) is not None
    ]
    negative = [
        value
        for sequence in sequences
        if sequence.fixture_class == "negative"
        for frame in sequence.frames
        if (value := getattr(frame, field)) is not None
    ]
    positive_p05 = _percentile(positive, 0.05)
    negative_p95 = _percentile(negative, 0.95)
    return {
        "positive": _number_summary(positive),
        "negative": _number_summary(negative),
        "p05MinusNegativeP95": (
            positive_p05 - negative_p95
            if positive_p05 is not None and negative_p95 is not None
            else None
        ),
    }


def _score_separation(sequences: Sequence[SequenceReport], field: str) -> dict[str, float | None]:
    positive = [getattr(frame, field) for sequence in sequences if sequence.fixture_class == "positive" for frame in sequence.frames]
    negative = [getattr(frame, field) for sequence in sequences if sequence.fixture_class == "negative" for frame in sequence.frames]
    positive_floor = min(positive) if positive else None
    negative_ceiling = max(negative) if negative else None
    return {
        "positiveMinimum": positive_floor,
        "positiveMedian": statistics.median(positive) if positive else None,
        "negativeMaximum": negative_ceiling,
        "negativeMedian": statistics.median(negative) if negative else None,
        "strictMargin": (
            positive_floor - negative_ceiling
            if positive_floor is not None and negative_ceiling is not None
            else None
        ),
    }


def analyze(
    datasets: Sequence[dict[str, Any]],
    sequences: Sequence[SequenceReport],
    policy: Policy,
) -> dict[str, Any]:
    if not sequences:
        raise ValidationError("at least one sequence is required")
    identities = [(sequence.dataset_id, sequence.sequence_id) for sequence in sequences]
    if len(set(identities)) != len(identities):
        raise ValidationError("datasetId/sequenceId identities must be unique")

    replayed = [(sequence, replay(sequence, policy)) for sequence in sequences]
    positives = [(sequence, result) for sequence, result in replayed if sequence.fixture_class == "positive"]
    negatives = [(sequence, result) for sequence, result in replayed if sequence.fixture_class == "negative"]
    positive_locks = sum(result.first_lock_ms is not None for _, result in positives)
    negative_locks = sum(result.first_lock_ms is not None for _, result in negatives)
    positive_lock_times = [
        float(result.first_lock_ms)
        for _, result in positives
        if result.first_lock_ms is not None
    ]

    capture_sequences = [
        (sequence, sum(frame.capture_commands or 0 for frame in sequence.frames))
        for sequence in sequences
        if all(frame.capture_commands is not None for frame in sequence.frames)
    ]
    duplicate_capture_sequences = sum(commands > 1 for _, commands in capture_sequences)
    total_duplicate_commands = sum(
        max(0, commands - 1) for _, commands in capture_sequences
    )
    false_capture_sequences = sum(
        sequence.fixture_class == "negative" and commands > 0
        for sequence, commands in capture_sequences
    )

    frames = [frame for sequence in sequences for frame in sequence.frames]
    latencies = [frame.inference_latency_ms for frame in frames if frame.inference_latency_ms is not None]
    cue_frames = [frame for frame in frames if frame.cue_emitted is not None]
    emitted_cues = sum(frame.cue_emitted is True for frame in cue_frames)
    observed_minutes = sum(sequence.frames[-1].elapsed_ms - sequence.frames[0].elapsed_ms for sequence in sequences) / 60_000.0

    sequence_details = []
    for sequence, result in replayed:
        capture_count = (
            sum(frame.capture_commands or 0 for frame in sequence.frames)
            if all(frame.capture_commands is not None for frame in sequence.frames)
            else None
        )
        evaluation_statuses = [
            frame.evaluation_status
            for frame in sequence.frames
            if frame.evaluation_status is not None
        ]
        qualified_landmark_counts = [
            frame.confidence_qualified_landmark_count
            for frame in sequence.frames
            if frame.confidence_qualified_landmark_count is not None
        ]
        qualified_torso_counts = [
            frame.qualified_torso_anchor_count
            for frame in sequence.frames
            if frame.qualified_torso_anchor_count is not None
        ]
        maximum_person_scores = [
            frame.maximum_valid_person_score
            for frame in sequence.frames
            if frame.maximum_valid_person_score is not None
        ]
        maximum_keypoint_scores = [
            frame.maximum_valid_keypoint_score
            for frame in sequence.frames
            if frame.maximum_valid_keypoint_score is not None
        ]
        sequence_details.append(
            {
                "datasetId": sequence.dataset_id,
                "sequenceId": sequence.sequence_id,
                "fixtureClass": sequence.fixture_class,
                "caseClass": sequence.case_class,
                "frameCount": len(sequence.frames),
                "firstLockMs": result.first_lock_ms,
                "lockTransitions": result.lock_transitions,
                "unlockTransitions": result.unlock_transitions,
                "captureCommands": capture_count,
                "evaluationStatusCounts": (
                    dict(sorted(Counter(evaluation_statuses).items()))
                    if evaluation_statuses
                    else None
                ),
                "confidenceQualifiedLandmarkCount": _integer_summary(
                    qualified_landmark_counts
                ),
                "qualifiedTorsoAnchorCount": _integer_summary(qualified_torso_counts),
                "maximumValidPersonScore": _number_summary(maximum_person_scores),
                "maximumValidKeypointScore": _number_summary(maximum_keypoint_scores),
            }
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "policy": {
            "policyId": policy.policy_id,
            "status": policy.status,
            "framing": {
                "minimumLandmarkConfidence": policy.framing.minimum_landmark_confidence,
                "minimumSharedLandmarkCount": policy.framing.minimum_shared_landmark_count,
                "centerErrorAtZeroSimilarity": policy.framing.center_error_at_zero_similarity,
            },
            "match": {
                "minimumLandmarkCoverage": policy.match.minimum_landmark_coverage,
                "minimumFramingScore": policy.match.minimum_framing_score,
                "minimumAngularSimilarity": policy.match.minimum_angular_similarity,
                "minimumPositionalSimilarity": policy.match.minimum_positional_similarity,
                "minimumOverallMatch": policy.match.minimum_overall_match,
                "releaseMinimumLandmarkCoverage": policy.match.release_minimum_landmark_coverage,
                "releaseMinimumFramingScore": policy.match.release_minimum_framing_score,
                "releaseMinimumAngularSimilarity": policy.match.release_minimum_angular_similarity,
                "releaseMinimumPositionalSimilarity": policy.match.release_minimum_positional_similarity,
                "releaseMinimumOverallMatch": policy.match.release_minimum_overall_match,
            },
            "timing": {
                "acquireDwellMs": policy.timing.acquire_dwell_ms,
                "releaseHysteresisMs": policy.timing.release_hysteresis_ms,
            },
        },
        "datasets": list(datasets),
        "summary": {
            "sequenceCount": len(sequences),
            "frameCount": len(frames),
            "positiveSequenceCount": len(positives),
            "negativeSequenceCount": len(negatives),
            "positiveLockCount": positive_locks,
            "positiveLockRate": _rate(positive_locks, len(positives)),
            "negativeFalseLockCount": negative_locks,
            "negativeFalseLockRate": _rate(negative_locks, len(negatives)),
            "timeToFirstLockMs": {
                "median": _percentile(positive_lock_times, 0.5),
                "p95": _percentile(positive_lock_times, 0.95),
                "maximum": max(positive_lock_times) if positive_lock_times else None,
            },
            "captureCoverageSequenceCount": len(capture_sequences),
            "duplicateCaptureSequenceCount": duplicate_capture_sequences,
            "duplicateCaptureSequenceRate": _rate(duplicate_capture_sequences, len(capture_sequences)),
            "duplicateCaptureCommandCount": total_duplicate_commands,
            "negativeFalseCaptureSequenceCount": false_capture_sequences,
            "inferenceLatencyMs": {
                "sampleCount": len(latencies),
                "median": _percentile(latencies, 0.5),
                "p95": _percentile(latencies, 0.95),
                "maximum": max(latencies) if latencies else None,
            },
            "cueRate": {
                "observedFrameCount": len(cue_frames),
                "emittedCueCount": emitted_cues,
                "perObservedFrame": _rate(emitted_cues, len(cue_frames)),
                "perMinuteOfSequenceTime": (
                    emitted_cues / observed_minutes if observed_minutes > 0.0 else None
                ),
            },
        },
        "scoreSeparation": {
            "landmarkCoverage": _score_separation(sequences, "landmark_coverage"),
            "framingScore": _score_separation(sequences, "framing_score"),
            "angularSimilarity": _score_separation(sequences, "angular_similarity"),
            "positionalSimilarity": _score_separation(sequences, "positional_similarity"),
            "overallMatch": _score_separation(sequences, "overall_match"),
        },
        "detectorScoreDistributions": {
            "maximumValidPersonScore": _detector_score_distribution(
                sequences,
                "maximum_valid_person_score",
            ),
            "maximumValidKeypointScore": _detector_score_distribution(
                sequences,
                "maximum_valid_keypoint_score",
            ),
        },
        "sequences": sequence_details,
    }


def _format_value(value: Any, digits: int = 3) -> str:
    if value is None:
        return "unavailable"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def render_markdown(report: dict[str, Any]) -> str:
    policy = report["policy"]
    summary = report["summary"]
    lines = [
        "# Match calibration analysis",
        "",
        f"Policy: `{policy['policyId']}` ({policy['status']})",
        "",
        "This report analyzes derived scalar evidence only. It contains no images, filesystem paths, URIs, or raw landmark arrays.",
        "",
        "## Dataset boundary",
        "",
    ]
    for dataset in report["datasets"]:
        lines.extend(
            [
                f"### `{dataset['datasetId']}`",
                "",
                f"- Dataset schema: {dataset['schemaVersion']}",
                f"- Authorization: `{dataset['authorization']}`",
                f"- Sequences: {dataset['sequenceCount']}",
                f"- Provenance: {dataset['provenance']}",
                "- Population limits:",
            ]
        )
        lines.extend(f"  - {limit}" for limit in dataset["populationLimits"])
        lines.append("")

    lines.extend(
        [
            "## Results",
            "",
            "| Metric | Result |",
            "|---|---:|",
            f"| Positive sequence lock rate | {_format_value(summary['positiveLockRate'])} ({summary['positiveLockCount']}/{summary['positiveSequenceCount']}) |",
            f"| Negative false-lock rate | {_format_value(summary['negativeFalseLockRate'])} ({summary['negativeFalseLockCount']}/{summary['negativeSequenceCount']}) |",
            f"| Median time to first lock | {_format_value(summary['timeToFirstLockMs']['median'], 1)} ms |",
            f"| P95 inference latency | {_format_value(summary['inferenceLatencyMs']['p95'], 1)} ms |",
            f"| Duplicate-capture sequence rate | {_format_value(summary['duplicateCaptureSequenceRate'])} ({summary['duplicateCaptureSequenceCount']}/{summary['captureCoverageSequenceCount']}) |",
            f"| Negative false-capture sequences | {summary['negativeFalseCaptureSequenceCount']} |",
            f"| Cue rate per observed frame | {_format_value(summary['cueRate']['perObservedFrame'])} |",
            "",
            "## Score separation",
            "",
            "A positive strict margin means every positive frame scored above every negative frame for that scalar. Mandatory gates still remain independent.",
            "",
            "| Score | Positive minimum | Negative maximum | Strict margin |",
            "|---|---:|---:|---:|",
        ]
    )
    for label, values in report["scoreSeparation"].items():
        lines.append(
            f"| {label} | {_format_value(values['positiveMinimum'])} | "
            f"{_format_value(values['negativeMaximum'])} | {_format_value(values['strictMargin'])} |"
        )

    detector_distributions = report["detectorScoreDistributions"]
    if any(
        values[fixture]["sampleCount"] > 0
        for values in detector_distributions.values()
        for fixture in ("positive", "negative")
    ):
        lines.extend(
            [
                "",
                "## Detector score distributions",
                "",
                "These per-frame maxima contain no slot, keypoint identity, coordinate, or tensor. A threshold still requires multiple positive and negative sequences; the table does not select one automatically.",
                "",
                "| Score | Positive frames | Positive p05 / median | Negative frames | Negative p95 / median | p05 − negative p95 |",
                "|---|---:|---:|---:|---:|---:|",
            ]
        )
        for label, values in detector_distributions.items():
            positive = values["positive"]
            negative = values["negative"]
            lines.append(
                f"| {label} | {positive['sampleCount']} | "
                f"{_format_value(positive['p05'])} / {_format_value(positive['median'])} | "
                f"{negative['sampleCount']} | "
                f"{_format_value(negative['p95'])} / {_format_value(negative['median'])} | "
                f"{_format_value(values['p05MinusNegativeP95'])} |"
            )

    lines.extend(
        [
            "",
            "## Sequence replay",
            "",
            "| Dataset / sequence | Class | Case | First lock | Locks | Unlocks | Capture commands |",
            "|---|---|---|---:|---:|---:|---:|",
        ]
    )
    for sequence in report["sequences"]:
        identity = f"`{sequence['datasetId']} / {sequence['sequenceId']}`"
        first_lock = (
            f"{sequence['firstLockMs']} ms" if sequence["firstLockMs"] is not None else "none"
        )
        lines.append(
            f"| {identity} | {sequence['fixtureClass']} | {sequence['caseClass']} | "
            f"{first_lock} | {sequence['lockTransitions']} | {sequence['unlockTransitions']} | "
            f"{_format_value(sequence['captureCommands'])} |"
        )

    diagnostic_sequences = [
        sequence
        for sequence in report["sequences"]
        if sequence["evaluationStatusCounts"] is not None
    ]
    if diagnostic_sequences:
        lines.extend(
            [
                "",
                "## Evaluation diagnostics",
                "",
                "These are bounded counts only; no landmark identities or coordinates are retained.",
                "",
                "| Dataset / sequence | Status frames | Qualified landmarks min / median / max | Torso anchors min / median / max |",
                "|---|---|---:|---:|",
            ]
        )
        for sequence in diagnostic_sequences:
            identity = f"`{sequence['datasetId']} / {sequence['sequenceId']}`"
            statuses = ", ".join(
                f"{status}={count}"
                for status, count in sequence["evaluationStatusCounts"].items()
            )
            landmarks = sequence["confidenceQualifiedLandmarkCount"]
            torso = sequence["qualifiedTorsoAnchorCount"]
            lines.append(
                f"| {identity} | {statuses} | "
                f"{_format_value(landmarks['minimum'])} / "
                f"{_format_value(landmarks['median'], 1)} / "
                f"{_format_value(landmarks['maximum'])} | "
                f"{_format_value(torso['minimum'])} / "
                f"{_format_value(torso['median'], 1)} / "
                f"{_format_value(torso['maximum'])} |"
            )

    lines.extend(
        [
            "",
            "## Interpretation limit",
            "",
            "Synthetic contract fixtures verify the analyzer and policy boundary behavior. They cannot calibrate production thresholds or establish accuracy for any person, pose, camera, lighting condition, or population. A device-calibrated policy requires separately authorized derived reports from a bounded positive and negative collection, followed by recorded hardware performance evidence.",
            "",
        ]
    )
    return "\n".join(lines)


def _load_inputs(paths: Iterable[Path]) -> tuple[list[dict[str, Any]], list[SequenceReport]]:
    datasets_by_id: dict[str, dict[str, Any]] = {}
    sequence_ids_by_dataset: dict[str, set[str]] = {}
    sequences: list[SequenceReport] = []
    for path in paths:
        metadata, loaded = load_dataset(path)
        dataset_id = metadata["datasetId"]
        existing = datasets_by_id.get(dataset_id)
        if existing is None:
            existing = {**metadata, "sequenceCount": 0}
            datasets_by_id[dataset_id] = existing
            sequence_ids_by_dataset[dataset_id] = set()
        else:
            comparable = {key: value for key, value in metadata.items() if key != "sequenceCount"}
            existing_comparable = {
                key: value for key, value in existing.items() if key != "sequenceCount"
            }
            if comparable != existing_comparable:
                raise ValidationError(f"conflicting metadata for datasetId: {dataset_id}")
        known_sequence_ids = sequence_ids_by_dataset[dataset_id]
        duplicate_sequence_ids = sorted(
            sequence.sequence_id
            for sequence in loaded
            if sequence.sequence_id in known_sequence_ids
        )
        if duplicate_sequence_ids:
            raise ValidationError(
                f"duplicate sequenceId for datasetId {dataset_id}: "
                f"{', '.join(duplicate_sequence_ids)}"
            )
        known_sequence_ids.update(sequence.sequence_id for sequence in loaded)
        existing["sequenceCount"] += len(loaded)
        sequences.extend(loaded)
    return list(datasets_by_id.values()), sequences


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, required=True, help="Policy JSON to replay")
    parser.add_argument(
        "--input",
        type=Path,
        nargs="+",
        required=True,
        help="One or more derived-report dataset JSON files",
    )
    parser.add_argument("--json-output", type=Path, help="Write the machine-readable analysis")
    parser.add_argument("--markdown-output", type=Path, help="Write a human-readable analysis")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        policy = load_policy(args.policy)
        datasets, sequences = _load_inputs(args.input)
        report = analyze(datasets, sequences, policy)
    except (OSError, json.JSONDecodeError, ValidationError) as error:
        print(f"calibration analysis failed: {error}", file=sys.stderr)
        return 2

    json_text = json.dumps(report, indent=2, sort_keys=True) + "\n"
    markdown_text = render_markdown(report)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json_text, encoding="utf-8")
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(markdown_text, encoding="utf-8")
    if not args.json_output and not args.markdown_output:
        sys.stdout.write(markdown_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
