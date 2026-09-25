import json
import tempfile
import unittest
from pathlib import Path

import analyze_match_reports as calibration


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "app" / "src" / "test" / "resources" / "calibration"


class CalibrationAnalyzerTest(unittest.TestCase):
    def test_synthetic_contract_replays_inclusive_gates_and_temporal_hysteresis(self) -> None:
        policy = calibration.load_policy(FIXTURES / "development-policy-v1.json")
        metadata, sequences = calibration.load_dataset(FIXTURES / "synthetic-contract-v1.json")

        report = calibration.analyze([metadata], sequences, policy)
        details = {item["sequenceId"]: item for item in report["sequences"]}

        self.assertEqual(8, report["summary"]["sequenceCount"])
        self.assertEqual(3, report["summary"]["positiveLockCount"])
        self.assertEqual(1, report["summary"]["negativeFalseLockCount"])
        self.assertEqual(1, report["summary"]["negativeFalseCaptureSequenceCount"])
        self.assertEqual(0, report["summary"]["duplicateCaptureSequenceCount"])
        self.assertEqual(500, details["positive-exact-boundary"]["firstLockMs"])
        self.assertIsNone(details["negative-transient-match"]["firstLockMs"])
        self.assertIsNone(details["positive-exact-boundary"]["evaluationStatusCounts"])
        self.assertEqual(0, details["positive-release-glitch"]["unlockTransitions"])
        self.assertEqual(13, report["policy"]["framing"]["minimumSharedLandmarkCount"])

    def test_kotlin_collector_contract_is_accepted_without_optional_runtime_evidence(self) -> None:
        metadata, sequences = calibration.load_dataset(
            FIXTURES / "derived-collector-contract-v2.json"
        )

        self.assertEqual(2, metadata["schemaVersion"])
        self.assertEqual("user-authorized-derived", metadata["authorization"])
        self.assertEqual(1, metadata["sequenceCount"])
        self.assertEqual("positive", sequences[0].fixture_class)
        self.assertEqual(2, len(sequences[0].frames))
        self.assertEqual("evaluated", sequences[0].frames[0].evaluation_status)
        self.assertEqual(17, sequences[0].frames[0].confidence_qualified_landmark_count)
        self.assertEqual(4, sequences[0].frames[0].qualified_torso_anchor_count)
        self.assertIsNone(sequences[0].frames[0].inference_latency_ms)
        self.assertIsNone(sequences[0].frames[0].cue_emitted)

        policy = calibration.load_policy(FIXTURES / "development-policy-v1.json")
        report = calibration.analyze([metadata], sequences, policy)
        detail = report["sequences"][0]
        self.assertEqual({"evaluated": 2}, detail["evaluationStatusCounts"])
        self.assertEqual(
            {"sampleCount": 2, "minimum": 17, "median": 17.0, "maximum": 17},
            detail["confidenceQualifiedLandmarkCount"],
        )
        self.assertIn("## Evaluation diagnostics", calibration.render_markdown(report))
        self.assertIn("- Dataset schema: 2", calibration.render_markdown(report))

    def test_v3_detector_scores_are_bounded_and_report_positive_negative_distributions(self) -> None:
        positive = json.loads((FIXTURES / "derived-collector-contract-v3.json").read_text())
        negative = json.loads((FIXTURES / "derived-collector-contract-v3.json").read_text())
        negative["sequences"][0].update(
            sequenceId="negative-empty-scene-a",
            fixtureClass="negative",
            caseClass="no-person-empty-scene",
        )
        for index, frame in enumerate(negative["sequences"][0]["frames"]):
            frame.update(
                detectedPersonCount=0,
                evaluationStatus="no-person",
                confidenceQualifiedLandmarkCount=0,
                qualifiedTorsoAnchorCount=0,
                maximumValidPersonScore=0.1 + index * 0.02,
                maximumValidKeypointScore=0.2 + index * 0.02,
                landmarkCoverage=0.0,
                framingScore=0.0,
                angularSimilarity=0.0,
                positionalSimilarity=0.0,
                overallMatch=0.0,
            )
        with tempfile.TemporaryDirectory() as directory:
            positive_path = Path(directory) / "positive.json"
            negative_path = Path(directory) / "negative.json"
            positive_path.write_text(json.dumps(positive), encoding="utf-8")
            negative_path.write_text(json.dumps(negative), encoding="utf-8")
            datasets, sequences = calibration._load_inputs([positive_path, negative_path])

        policy = calibration.load_policy(FIXTURES / "development-policy-v1.json")
        report = calibration.analyze(datasets, sequences, policy)
        distribution = report["detectorScoreDistributions"]["maximumValidPersonScore"]
        self.assertEqual(2, distribution["positive"]["sampleCount"])
        self.assertEqual(0.8, distribution["positive"]["p05"])
        self.assertEqual(2, distribution["negative"]["sampleCount"])
        self.assertAlmostEqual(0.119, distribution["negative"]["p95"])
        self.assertAlmostEqual(0.681, distribution["p05MinusNegativeP95"])
        self.assertIn("## Detector score distributions", calibration.render_markdown(report))
        self.assertIn("- Dataset schema: 3", calibration.render_markdown(report))

    def test_v3_detector_score_fields_are_required_nullable_and_normalized(self) -> None:
        cases = (
            (
                lambda frame: frame.pop("maximumValidPersonScore"),
                "missing fields: maximumValidPersonScore",
            ),
            (
                lambda frame: frame.__setitem__("maximumValidKeypointScore", 1.1),
                r"maximumValidKeypointScore must be finite and in \[0.0, 1.0\]",
            ),
        )
        for mutation, expected_error in cases:
            with self.subTest(expected_error=expected_error):
                raw = json.loads(
                    (FIXTURES / "derived-collector-contract-v3.json").read_text()
                )
                mutation(raw["sequences"][0]["frames"][0])
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "invalid.json"
                    path.write_text(json.dumps(raw), encoding="utf-8")
                    with self.assertRaisesRegex(calibration.ValidationError, expected_error):
                        calibration.load_dataset(path)

        nullable = json.loads((FIXTURES / "derived-collector-contract-v3.json").read_text())
        nullable["sequences"][0]["frames"][0]["maximumValidPersonScore"] = None
        nullable["sequences"][0]["frames"][0]["maximumValidKeypointScore"] = None
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nullable.json"
            path.write_text(json.dumps(nullable), encoding="utf-8")
            _, sequences = calibration.load_dataset(path)
        self.assertIsNone(sequences[0].frames[0].maximum_valid_person_score)
        self.assertIsNone(sequences[0].frames[0].maximum_valid_keypoint_score)

    def test_matching_dataset_fragments_merge_with_unique_sequences(self) -> None:
        positive = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        negative = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        negative["sequences"][0]["sequenceId"] = "negative-wrong-pose-a"
        negative["sequences"][0]["fixtureClass"] = "negative"
        negative["sequences"][0]["caseClass"] = "wrong-pose"
        with tempfile.TemporaryDirectory() as directory:
            positive_path = Path(directory) / "positive.json"
            negative_path = Path(directory) / "negative.json"
            positive_path.write_text(json.dumps(positive), encoding="utf-8")
            negative_path.write_text(json.dumps(negative), encoding="utf-8")

            datasets, sequences = calibration._load_inputs([positive_path, negative_path])

        self.assertEqual(1, len(datasets))
        self.assertEqual(2, datasets[0]["sequenceCount"])
        self.assertEqual(
            {"positive-centered-a", "negative-wrong-pose-a"},
            {sequence.sequence_id for sequence in sequences},
        )

    def test_matching_dataset_fragments_reject_conflicts_and_duplicate_sequences(self) -> None:
        first = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        conflicting = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        conflicting["provenance"] = "conflicting provenance"
        with tempfile.TemporaryDirectory() as directory:
            first_path = Path(directory) / "first.json"
            second_path = Path(directory) / "second.json"
            first_path.write_text(json.dumps(first), encoding="utf-8")
            second_path.write_text(json.dumps(conflicting), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "conflicting metadata"):
                calibration._load_inputs([first_path, second_path])

            second_path.write_text(json.dumps(first), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "duplicate sequenceId"):
                calibration._load_inputs([first_path, second_path])

            legacy = json.loads(json.dumps(first))
            legacy["schemaVersion"] = 1
            legacy["sequences"][0]["sequenceId"] = "positive-centered-legacy-a"
            for frame in legacy["sequences"][0]["frames"]:
                frame.pop("evaluationStatus")
                frame.pop("confidenceQualifiedLandmarkCount")
                frame.pop("qualifiedTorsoAnchorCount")
            second_path.write_text(json.dumps(legacy), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "conflicting metadata"):
                calibration._load_inputs([first_path, second_path])

    def test_unknown_raw_landmark_field_is_rejected(self) -> None:
        raw = json.loads((FIXTURES / "synthetic-contract-v1.json").read_text())
        raw["sequences"][0]["frames"][0]["landmarks"] = [{"x": 0.5, "y": 0.5}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "unsupported fields: landmarks"):
                calibration.load_dataset(path)

    def test_private_image_marker_is_rejected(self) -> None:
        raw = json.loads((FIXTURES / "synthetic-contract-v1.json").read_text())
        raw["containsPrivateImages"] = True
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "must not contain private images"):
                calibration.load_dataset(path)

    def test_non_increasing_elapsed_time_is_rejected(self) -> None:
        raw = json.loads((FIXTURES / "synthetic-contract-v1.json").read_text())
        raw["sequences"][0]["frames"][1]["elapsedMs"] = 0
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "strictly increasing"):
                calibration.load_dataset(path)

    def test_v2_diagnostic_fields_are_required_and_fail_closed(self) -> None:
        cases = (
            (lambda frame: frame.pop("evaluationStatus"), "missing fields: evaluationStatus"),
            (
                lambda frame: frame.__setitem__("evaluationStatus", "unknown"),
                "evaluationStatus is unsupported",
            ),
            (
                lambda frame: frame.__setitem__("confidenceQualifiedLandmarkCount", 18),
                r"confidenceQualifiedLandmarkCount must be in \[0, 17\]",
            ),
            (
                lambda frame: frame.__setitem__("qualifiedTorsoAnchorCount", 5),
                r"qualifiedTorsoAnchorCount must be in \[0, 4\]",
            ),
            (
                lambda frame: frame.update(
                    confidenceQualifiedLandmarkCount=3,
                    qualifiedTorsoAnchorCount=4,
                ),
                "qualifiedTorsoAnchorCount cannot exceed",
            ),
            (
                lambda frame: frame.update(
                    evaluationStatus="no-person",
                    detectedPersonCount=1,
                ),
                "evaluationStatus must agree",
            ),
            (
                lambda frame: frame.update(
                    evaluationStatus="evaluated",
                    qualifiedTorsoAnchorCount=3,
                ),
                "evaluated frames require four qualified torso anchors",
            ),
            (
                lambda frame: frame.update(
                    evaluationStatus="no-person",
                    detectedPersonCount=0,
                    confidenceQualifiedLandmarkCount=1,
                    qualifiedTorsoAnchorCount=0,
                ),
                "no-person frames cannot contain qualified landmarks",
            ),
        )
        for mutation, expected_error in cases:
            with self.subTest(expected_error=expected_error):
                raw = json.loads(
                    (FIXTURES / "derived-collector-contract-v2.json").read_text()
                )
                mutation(raw["sequences"][0]["frames"][0])
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "invalid.json"
                    path.write_text(json.dumps(raw), encoding="utf-8")
                    with self.assertRaisesRegex(calibration.ValidationError, expected_error):
                        calibration.load_dataset(path)

    def test_v2_unevaluated_status_cannot_acquire_a_lock_from_scores(self) -> None:
        raw = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        raw["sequences"][0]["frames"] = [
            {
                **raw["sequences"][0]["frames"][0],
                "elapsedMs": elapsed_ms,
                "evaluationStatus": "canonicalization-failed",
            }
            for elapsed_ms in (0, 500)
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unevaluated.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            metadata, sequences = calibration.load_dataset(path)

        policy = calibration.load_policy(FIXTURES / "development-policy-v1.json")
        report = calibration.analyze([metadata], sequences, policy)
        self.assertEqual(0, report["summary"]["positiveLockCount"])

    def test_unbounded_frames_and_capture_commands_are_rejected(self) -> None:
        raw = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        template = raw["sequences"][0]["frames"][0]
        raw["sequences"][0]["frames"] = [
            {**template, "elapsedMs": index} for index in range(601)
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, "at most 600"):
                calibration.load_dataset(path)

        raw = json.loads((FIXTURES / "derived-collector-contract-v2.json").read_text())
        raw["sequences"][0]["frames"][0]["captureCommands"] = 4
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, r"\[0, 3\]"):
                calibration.load_dataset(path)

    def test_release_threshold_cannot_exceed_acquisition_threshold(self) -> None:
        raw = json.loads((FIXTURES / "development-policy-v1.json").read_text())
        raw["match"]["releaseMinimumOverallMatch"] = 0.9
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid-policy.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(
                calibration.ValidationError,
                "must not exceed its acquisition threshold",
            ):
                calibration.load_policy(path)

    def test_framing_policy_rejects_values_outside_production_contract(self) -> None:
        raw = json.loads((FIXTURES / "development-policy-v1.json").read_text())
        raw["framing"]["minimumSharedLandmarkCount"] = 8
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid-policy.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, ">= 9"):
                calibration.load_policy(path)

        raw = json.loads((FIXTURES / "development-policy-v1.json").read_text())
        raw["framing"]["minimumSharedLandmarkCount"] = 18
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid-policy.json"
            path.write_text(json.dumps(raw), encoding="utf-8")
            with self.assertRaisesRegex(calibration.ValidationError, r"\[9, 17\]"):
                calibration.load_policy(path)


if __name__ == "__main__":
    unittest.main()
