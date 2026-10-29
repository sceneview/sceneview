"""Offline tests for app_store_catchup.decide() — no network, no secrets.

Run: python3 -m unittest discover -s .github/scripts -p 'test_app_store_catchup.py'
The catch-up workflow runs this before its probe, so a broken decision stops
the job before it can submit anything.
"""
import contextlib
import datetime
import io
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from app_store_catchup import (  # noqa: E402
    decide, get_all, parse_builds, split_active_runs, tag_build_numbers)

# App Store Connect as measured on 2026-09-28, before 4.42.0 was withdrawn.
LIVE_440 = [("4.40.0", "READY_FOR_SALE"), ("4.38.0", "REPLACED_WITH_NEW_VERSION")]
BUILDS = [("4.42.0", "202609262020"), ("4.47.0", "202609280405"), ("4.47.0", "202609281200")]


class DecideTest(unittest.TestCase):
    def test_up_to_date_does_nothing(self):
        d = decide("v4.40.0", LIVE_440, BUILDS)
        self.assertEqual(d["action"], "none")
        self.assertIn("is live", d["reason"])

    def test_older_version_in_review_waits(self):
        d = decide("v4.47.0", LIVE_440 + [("4.42.0", "WAITING_FOR_REVIEW")], BUILDS)
        self.assertEqual(d["action"], "none")
        self.assertIn("waiting", d["reason"])
        self.assertIn("supersede=true", d["reason"])

    def test_supersede_replaces_an_older_version_in_review(self):
        d = decide("v4.47.0", LIVE_440 + [("4.42.0", "WAITING_FOR_REVIEW")], BUILDS,
                   supersede=True)
        self.assertEqual((d["action"], d["supersede"]), ("submit", "true"))

    def test_supersede_never_touches_an_approved_version(self):
        d = decide("v4.47.0", LIVE_440 + [("4.42.0", "PENDING_APPLE_RELEASE")], BUILDS,
                   supersede=True)
        self.assertEqual((d["action"], d["supersede"]), ("none", "false"))

    def test_target_already_with_apple(self):
        d = decide("v4.47.0", LIVE_440 + [("4.47.0", "IN_REVIEW")], BUILDS, supersede=True)
        self.assertEqual((d["action"], d["supersede"]), ("none", "false"))

    def test_withdrawn_version_does_not_block(self):
        # Thomas removed 4.42.0 from review on 2026-09-28: DEVELOPER_REJECTED is
        # editable, app_store_submit.py retargets it, so the latest release goes.
        d = decide("v4.47.0", LIVE_440 + [("4.42.0", "DEVELOPER_REJECTED")], BUILDS)
        self.assertEqual(d["action"], "submit")
        self.assertEqual(d["version"], "4.47.0")

    def test_reuses_the_oldest_build_of_the_release(self):
        # Oldest first = the tag run's upload, not a later manual run from main.
        d = decide("v4.47.0", LIVE_440, BUILDS)
        self.assertEqual((d["action"], d["build"]), ("submit", "202609280405"))

    def test_no_build_rebuilds_on_macos(self):
        d = decide("v4.48.0", LIVE_440, BUILDS)
        self.assertEqual((d["action"], d["build"]), ("rebuild", ""))

    def test_rebuild_gives_up_after_repeated_runs(self):
        d = decide("v4.48.0", LIVE_440, BUILDS, tag_runs=3)
        self.assertEqual((d["action"], d["level"]), ("none", "warning"))

    def test_running_deploy_owns_the_release(self):
        d = decide("v4.47.0", LIVE_440, BUILDS, deploy_running=True)
        self.assertEqual(d["action"], "none")

    def test_version_needing_a_human_is_a_warning(self):
        d = decide("v4.47.0", LIVE_440 + [("4.42.0", "PENDING_DEVELOPER_RELEASE")], BUILDS)
        self.assertEqual((d["action"], d["level"]), ("none", "warning"))

    def test_non_semver_tag_is_ignored(self):
        d = decide("v4.47.0-rc1", LIVE_440, BUILDS)
        self.assertEqual((d["action"], d["level"]), ("none", "warning"))

    def test_versions_compare_numerically(self):
        # 4.100.0 > 4.99.0 — a string compare would call it older.
        d = decide("v4.100.0", [("4.99.0", "READY_FOR_SALE")], [("4.100.0", "1")])
        self.assertEqual(d["action"], "submit")
        d = decide("v4.99.0", [("4.100.0", "READY_FOR_SALE")], [])
        self.assertEqual(d["action"], "none")

    def test_app_version_state_vocabulary(self):
        # appVersionState says READY_FOR_DISTRIBUTION where appStoreState said READY_FOR_SALE.
        d = decide("v4.40.0", [("4.40.0", "READY_FOR_DISTRIBUTION")], [])
        self.assertEqual(d["action"], "none")

    # ── A human's verdict is never undone by the cron ──────────────────────
    def test_rejected_target_is_not_resubmitted(self):
        d = decide("v4.47.0", LIVE_440 + [("4.47.0", "REJECTED")], BUILDS)
        self.assertEqual((d["action"], d["level"]), ("none", "warning"))
        self.assertIn("rejected or withdrawn by a human", d["reason"])
        self.assertIn("not resubmitting automatically", d["reason"])

    def test_withdrawn_target_is_not_resubmitted(self):
        # Thomas pulled the release itself out of review: that is a decision.
        d = decide("v4.47.0", LIVE_440 + [("4.47.0", "DEVELOPER_REJECTED")], BUILDS)
        self.assertEqual((d["action"], d["level"]), ("none", "warning"))
        self.assertIn("rejected or withdrawn by a human", d["reason"])

    def test_stopped_newer_version_blocks_too(self):
        for state in ("METADATA_REJECTED", "INVALID_BINARY"):
            d = decide("v4.47.0", LIVE_440 + [("4.48.0", state)], BUILDS)
            self.assertEqual((d["action"], d["level"]), ("none", "warning"), state)

    def test_newer_draft_is_never_renamed_down(self):
        d = decide("v4.47.0", LIVE_440 + [("4.48.0", "PREPARE_FOR_SUBMISSION")], BUILDS)
        self.assertEqual((d["action"], d["level"]), ("none", "warning"))

    def test_unknown_state_is_an_error(self):
        for state in ("SOMETHING_APPLE_ADDED", "?"):
            d = decide("v4.47.0", LIVE_440 + [("4.42.0", state)], BUILDS)
            self.assertEqual((d["action"], d["level"]), ("none", "error"), state)
            self.assertIn(state, d["reason"])

    # ── The tag's own build, not a later run from main ─────────────────────
    def test_prefers_the_build_uploaded_by_a_run_on_the_tag(self):
        utc = datetime.timezone.utc
        builds = [("4.47.0", "202609271900"), ("4.47.0", "202609280405")]
        # The tag run started at 04:03; the 19:00 build the day before came from
        # a manual run on main that already carried 4.47.0.
        windows = [(datetime.datetime(2026, 9, 28, 4, 3, 40, tzinfo=utc),
                    datetime.datetime(2026, 9, 28, 4, 3, 45, tzinfo=utc))]
        tag_builds = tag_build_numbers(builds, windows)
        self.assertEqual(tag_builds, {"202609280405"})
        d = decide("v4.47.0", LIVE_440, builds, tag_builds=tag_builds)
        self.assertEqual((d["action"], d["build"]), ("submit", "202609280405"))

    def test_falls_back_to_the_oldest_build_when_no_tag_run_matches(self):
        d = decide("v4.47.0", LIVE_440, BUILDS, tag_builds=set())
        self.assertEqual((d["action"], d["build"]), ("submit", "202609280405"))
        self.assertIn("not tied to a run on the tag", d["reason"])


class PaginationTest(unittest.TestCase):
    def test_follows_links_next(self):
        pages = {
            "p1": {"data": [1, 2], "links": {"next": "p2"}},
            "p2": {"data": [3], "links": {}},
        }
        self.assertEqual(get_all("p1", {}, get=lambda url, h: pages[url]), [1, 2, 3])

    def test_stops_after_twenty_pages(self):
        calls = []

        def endless(url, h):
            calls.append(url)
            return {"data": [url], "links": {"next": url + "+"}}

        # The guard prints a `::error::` workflow command. Captured here: on
        # the runner it would surface as a red annotation on a run that did
        # not fail — which is what every catch-up run until 2026-10-22 showed.
        out = io.StringIO()
        with contextlib.redirect_stdout(out), self.assertRaises(SystemExit) as e:
            get_all("p", {}, get=endless)
        self.assertEqual(e.exception.code, 1)
        self.assertEqual(len(calls), 20)
        self.assertIn("still paginating after 20 pages", out.getvalue())


UTC = datetime.timezone.utc
NOW = datetime.datetime(2026, 10, 22, 5, 4, tzinfo=UTC)


class ActiveRunsTest(unittest.TestCase):
    def test_ghost_run_queued_for_months_is_not_a_deploy(self):
        # Run 25906902157: `queued` since 2026-05-15 with zero jobs. It made
        # every catch-up run answer "app-store.yml is already running".
        ghost = {"id": 25906902157, "status": "queued", "created_at": "2026-05-15T07:51:54Z"}
        live, stale = split_active_runs([ghost], NOW)
        self.assertEqual(live, [])
        self.assertEqual(stale, [ghost])

    def test_recent_run_owns_the_release(self):
        run = {"id": 1, "status": "in_progress", "created_at": "2026-10-22T04:30:00Z"}
        pending = {"id": 2, "status": "pending", "created_at": "2026-10-21T06:00:00Z"}
        live, stale = split_active_runs([run, pending], NOW)
        self.assertEqual([r["id"] for r in live], [1, 2])
        self.assertEqual(stale, [])

    def test_a_run_listed_under_two_statuses_counts_once(self):
        run = {"id": 1, "status": "queued", "created_at": "2026-10-22T04:30:00Z"}
        live, _ = split_active_runs([run, dict(run)], NOW)
        self.assertEqual(len(live), 1)

    def test_unreadable_creation_time_is_stale(self):
        live, stale = split_active_runs([{"id": 3, "created_at": None}], NOW)
        self.assertEqual(live, [])
        self.assertEqual(len(stale), 1)


class ParseBuildsTest(unittest.TestCase):
    # Both legs of app-store.yml upload build 202610191150 to the one app.
    PAYLOAD = {
        "data": [
            {"id": "B3", "attributes": {"version": "202610191150"},
             "relationships": {"preReleaseVersion": {"data": {"type": "preReleaseVersions",
                                                              "id": "PM"}}}},
            {"id": "B2", "attributes": {"version": "202610191150"},
             "relationships": {"preReleaseVersion": {"data": {"type": "preReleaseVersions",
                                                              "id": "PI"}}}},
            {"id": "B1", "attributes": {"version": "202610190737"},
             "relationships": {"preReleaseVersion": {"data": {"type": "preReleaseVersions",
                                                              "id": "PI59"}}}},
        ],
        "included": [
            {"type": "preReleaseVersions", "id": "PM",
             "attributes": {"platform": "MAC_OS", "version": "4.60.0"}},
            {"type": "preReleaseVersions", "id": "PI",
             "attributes": {"platform": "IOS", "version": "4.60.0"}},
            {"type": "preReleaseVersions", "id": "PI59",
             "attributes": {"platform": "IOS", "version": "4.59.0"}},
        ],
    }

    def test_each_platform_sees_only_its_builds_oldest_first(self):
        self.assertEqual(parse_builds(self.PAYLOAD, "IOS"),
                         [("4.59.0", "202610190737"), ("4.60.0", "202610191150")])
        self.assertEqual(parse_builds(self.PAYLOAD, "MAC_OS"), [("4.60.0", "202610191150")])


class OctoberCatchUpTest(unittest.TestCase):
    # App Store Connect on 2026-10-22: 4.58.0 live, 4.59.0 and 4.60.0 deferred
    # on 2026-10-19 with no version record, their builds on TestFlight.
    VERSIONS = [("4.58.0", "READY_FOR_SALE"), ("4.57.0", "READY_FOR_SALE")]
    BUILDS = [("4.58.0", "202610190636"), ("4.59.0", "202610190737"),
              ("4.60.0", "202610191150")]

    def test_latest_release_is_submitted_with_its_tag_build(self):
        windows = [(datetime.datetime(2026, 10, 19, 11, 50, 1, tzinfo=UTC),
                    datetime.datetime(2026, 10, 19, 11, 50, 1, tzinfo=UTC))]
        d = decide("v4.60.0", self.VERSIONS, self.BUILDS, tag_runs=1,
                   tag_builds=tag_build_numbers(self.BUILDS, windows))
        self.assertEqual((d["action"], d["build"]), ("submit", "202610191150"))


if __name__ == "__main__":
    unittest.main()
