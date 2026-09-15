"""Offline unit tests for play_listing.py's pure helpers (#2612 P2 Phase A).

No network, no google-auth/requests — run via test-store-sync.sh or
`python3 -m unittest discover .claude/scripts/store-sync/test`.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import play_listing as pl  # noqa: E402


class ReadListingTextTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ldir = pathlib.Path(self.tmp.name) / "en-US"
        self.ldir.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_reads_existing_fields_and_skips_missing(self):
        (self.ldir / "title.txt").write_text("SceneView Demo\n")
        body, notes = pl.read_listing_text(self.ldir)
        self.assertEqual(body, {"title": "SceneView Demo"})
        self.assertEqual(notes, [])

    def test_truncates_over_cap_with_ellipsis(self):
        (self.ldir / "title.txt").write_text("x" * 45)
        body, notes = pl.read_listing_text(self.ldir)
        self.assertEqual(len(body["title"]), 30)
        self.assertTrue(body["title"].endswith("…"))
        self.assertEqual(len(notes), 1)
        self.assertIn("truncated", notes[0])

    def test_exact_cap_untouched(self):
        (self.ldir / "short_description.txt").write_text("y" * 80)
        body, notes = pl.read_listing_text(self.ldir)
        self.assertEqual(body["shortDescription"], "y" * 80)
        self.assertEqual(notes, [])


class GraphicsForTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ldir = pathlib.Path(self.tmp.name) / "en-US"
        (self.ldir / "graphics").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_glob_is_sorted(self):
        g = self.ldir / "graphics"
        for n in ["phone-screenshot-2.png", "phone-screenshot-1.png"]:
            (g / n).write_bytes(b"png")
        files = pl.graphics_for(self.ldir, "phone-screenshot-*.png")
        self.assertEqual([f.name for f in files],
                         ["phone-screenshot-1.png", "phone-screenshot-2.png"])

    def test_list_pattern_keeps_only_existing(self):
        (self.ldir / "graphics" / "icon-512.png").write_bytes(b"png")
        files = pl.graphics_for(self.ldir, ["icon-512.png", "feature-graphic.png"])
        self.assertEqual([f.name for f in files], ["icon-512.png"])

    def test_no_graphics_dir(self):
        self.assertEqual(pl.graphics_for(pathlib.Path(self.tmp.name), "*.png"), [])


class ImageTypeTest(unittest.TestCase):
    """#2794 — an imageType Play doesn't know 400s mid-edit and rolls back the
    WHOLE atomic listing edit (text + icon + phone screenshots), while the
    sync-listing job's `continue-on-error` hides the red. `tabletScreenshots`
    /`tabletScreenshots10` did exactly that from #1710 until #2794. Pin it."""

    def test_shipped_graphics_use_only_valid_play_types(self):
        self.assertEqual(pl.unknown_image_types(), [])

    def test_allowlist_mirrors_the_v3_enum_exactly(self):
        """Transcribed from the v3 API discovery document. A guard that exists to
        be exhaustive is worthless if it allows a value Play rejects: an extra
        entry here lets a future GRAPHICS row 400 mid-edit and roll the whole
        listing back. `appImageTypeUnspecified` is excluded on purpose (proto-zero
        sentinel, never a real slot); `promoGraphic` is v2-only and must stay out."""
        self.assertEqual(pl.VALID_IMAGE_TYPES, frozenset({
            "phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots",
            "tvScreenshots", "wearScreenshots",
            "icon", "featureGraphic", "tvBanner",
        }))

    def test_apply_sync_refuses_a_bad_type_at_the_write_boundary(self):
        """main() guards the CLI; this guards a direct importer, before any edit
        is opened. Patch GRAPHICS so no network object is ever needed."""
        original = pl.GRAPHICS
        pl.GRAPHICS = [("tabletScreenshots", "tablet7-*.png")]
        try:
            with self.assertRaises(ValueError) as ctx:
                pl.apply_sync(sess=None, pkg="x", root=pathlib.Path("."))
            self.assertIn("tabletScreenshots", str(ctx.exception))
        finally:
            pl.GRAPHICS = original

    def test_tablet_slots_use_the_play_enum_names(self):
        declared = [t for t, _ in pl.GRAPHICS]
        self.assertIn("sevenInchScreenshots", declared)
        self.assertIn("tenInchScreenshots", declared)

    def test_detects_an_unknown_type(self):
        bogus = [("icon", ["icon-512.png"]), ("tabletScreenshots", "t-*.png")]
        self.assertEqual(pl.unknown_image_types(bogus), ["tabletScreenshots"])

    def test_every_committed_graphic_is_mapped(self):
        """An asset no GRAPHICS pattern matches is dead weight — committed but
        never uploaded, and invisible until someone diffs the live listing."""
        root = pathlib.Path(__file__).resolve().parents[4]
        gdir = root / "samples/android-demo/distribution/play-store/en-US/graphics"
        if not gdir.is_dir():
            self.skipTest(f"{gdir} not present in this checkout")
        ldir = gdir.parent
        mapped = {f.name for _, pat in pl.GRAPHICS for f in pl.graphics_for(ldir, pat)}
        on_disk = {f.name for f in gdir.iterdir() if f.is_file()
                   and f.suffix.lower() in {".png", ".jpg", ".jpeg"}}
        self.assertEqual(on_disk - mapped, set(),
                         "graphics files matched by no GRAPHICS pattern")


class DiffTextTest(unittest.TestCase):
    def test_no_drift(self):
        self.assertEqual(pl.diff_text("en-US", {"title": "A"}, {"title": "A"}), [])

    def test_field_differs(self):
        drift = pl.diff_text("en-US", {"title": "A"}, {"title": "B"})
        self.assertEqual(len(drift), 1)
        self.assertIn("title differs", drift[0])

    def test_field_missing_remotely(self):
        drift = pl.diff_text("en-US", {"title": "A"}, {})
        self.assertEqual(len(drift), 1)
        self.assertIn("missing on the live listing", drift[0])

    def test_unmanaged_remote_field_is_not_drift(self):
        # No local file for fullDescription → the live value is unmanaged.
        self.assertEqual(
            pl.diff_text("en-US", {"title": "A"},
                         {"title": "A", "fullDescription": "live-only"}),
            [])


class DiffImagesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        self.f1 = self.dir / "phone-screenshot-1.png"
        self.f2 = self.dir / "phone-screenshot-2.png"
        self.f1.write_bytes(b"one")
        self.f2.write_bytes(b"two")

    def tearDown(self):
        self.tmp.cleanup()

    def test_identical_sequence_no_drift(self):
        shas = [pl.sha256_of(self.f1), pl.sha256_of(self.f2)]
        self.assertEqual(
            pl.diff_images("en-US", "phoneScreenshots", [self.f1, self.f2], shas), [])

    def test_order_mismatch_is_drift(self):
        shas = [pl.sha256_of(self.f2), pl.sha256_of(self.f1)]
        drift = pl.diff_images("en-US", "phoneScreenshots", [self.f1, self.f2], shas)
        self.assertEqual(len(drift), 1)
        self.assertIn("ORDER", drift[0])

    def test_missing_and_extra(self):
        drift = pl.diff_images("en-US", "phoneScreenshots", [self.f1, self.f2],
                               [pl.sha256_of(self.f1), "0" * 64])
        self.assertEqual(len(drift), 1)
        self.assertIn("phone-screenshot-2.png", drift[0])
        self.assertIn("1 live image(s) not in the repo", drift[0])


class ResolveCredentialsTest(unittest.TestCase):
    def test_content_env(self):
        creds = pl.resolve_credentials({"SERVICE_ACCOUNT_JSON": '{"type": "sa"}'})
        self.assertEqual(creds, {"type": "sa"})

    def test_alias_env(self):
        creds = pl.resolve_credentials({"PLAY_STORE_SERVICE_ACCOUNT_JSON": '{"a": 1}'})
        self.assertEqual(creds, {"a": 1})

    def test_path_env(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json") as f:
            f.write('{"from": "path"}')
            f.flush()
            creds = pl.resolve_credentials(
                {"PLAY_STORE_SERVICE_ACCOUNT_JSON_PATH": f.name})
        self.assertEqual(creds, {"from": "path"})

    def test_absent(self):
        self.assertIsNone(pl.resolve_credentials({}))


class SsotDefaultsTest(unittest.TestCase):
    """The defaults must keep pointing at the real repo SSOT dirs."""

    def repo_root(self):
        return pathlib.Path(__file__).resolve().parents[4]

    def test_listing_dir_exists_with_locales(self):
        root = self.repo_root() / pl.DEFAULT_LISTING_DIR
        self.assertTrue(root.is_dir(), f"missing {root}")
        self.assertTrue(pl.locales_under(root), "no locale dirs under the SSOT")

    def test_every_graphics_pattern_matches_something_in_en_us(self):
        ldir = self.repo_root() / pl.DEFAULT_LISTING_DIR / "en-US"
        for image_type, pattern in pl.GRAPHICS:
            self.assertTrue(pl.graphics_for(ldir, pattern),
                            f"{image_type} pattern matches no committed file")


# ── Opt-in default-locale switch + locale prune (#3658) ─────────────────────
#
# `apply_sync` lazily imports `requests` and catches `requests.HTTPError`, but
# this suite's contract is that it runs with no third-party dependency
# installed. A stub module in `sys.modules` keeps that true AND lets the tests
# assert the exact call ORDER, which is the whole risk of these two flags: the
# store must never end up with its default language pointing at a listing that
# was deleted, nor with the default locale deleted out from under it.


class _FakeResponse:
    def __init__(self, payload=None, status_code=200):
        self._payload = payload if payload is not None else {}
        self.status_code = status_code
        self.url = "https://fake/"

    def json(self):
        return self._payload

    def raise_for_status(self):
        return None


class _FakeSession:
    """Records every call as (verb, path-tail, json-body), in order."""

    def __init__(self, listings=(), details=None):
        self.calls = []
        self._listings = list(listings)
        self._details = details or {"defaultLanguage": "en-GB",
                                    "contactEmail": "keep@me.example"}

    def _tail(self, url):
        return url.split("/applications/pkg", 1)[-1]

    def post(self, url, json=None, **kw):
        self.calls.append(("POST", self._tail(url), json))
        if url.endswith("/edits"):
            return _FakeResponse({"id": "EDIT1"})
        return _FakeResponse()

    def put(self, url, json=None, **kw):
        self.calls.append(("PUT", self._tail(url), json))
        return _FakeResponse()

    def patch(self, url, json=None, **kw):
        self.calls.append(("PATCH", self._tail(url), json))
        return _FakeResponse()

    def delete(self, url, **kw):
        self.calls.append(("DELETE", self._tail(url), None))
        return _FakeResponse()

    def get(self, url, **kw):
        self.calls.append(("GET", self._tail(url), None))
        if url.endswith("/listings"):
            return _FakeResponse(
                {"listings": [{"language": l} for l in self._listings]})
        if url.endswith("/details"):
            return _FakeResponse(dict(self._details))
        return _FakeResponse({}, status_code=404)

    def verbs_on(self, needle):
        return [(v, p) for v, p, _b in self.calls if needle in p]


class _StubRequests:
    class HTTPError(Exception):
        pass


class LocalesToPruneTest(unittest.TestCase):
    def test_keeps_the_named_locale(self):
        self.assertEqual(
            pl.locales_to_prune(["en-US", "en-GB", "fr-FR"], "en-US"),
            ["en-GB", "fr-FR"])

    def test_empty_when_only_the_kept_locale_is_live(self):
        self.assertEqual(pl.locales_to_prune(["en-US"], "en-US"), [])

    def test_ignores_blank_entries_and_dedupes(self):
        self.assertEqual(
            pl.locales_to_prune(["fr-FR", "fr-FR", None, ""], "en-US"),
            ["fr-FR"])

    def test_kept_locale_absent_from_store_is_still_never_deleted(self):
        self.assertNotIn("en-US", pl.locales_to_prune(["de-DE"], "en-US"))


class ApplySyncFlagsTest(unittest.TestCase):
    """The flags are opt-in, and their order inside the edit is asserted."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        ldir = self.root / "en-US"
        (ldir / "graphics").mkdir(parents=True)
        (ldir / "title.txt").write_text("SceneView")
        self._real_requests = sys.modules.get("requests")
        sys.modules["requests"] = _StubRequests

    def tearDown(self):
        if self._real_requests is None:
            sys.modules.pop("requests", None)
        else:
            sys.modules["requests"] = self._real_requests
        self.tmp.cleanup()

    def test_default_run_touches_neither_details_nor_other_locales(self):
        sess = _FakeSession(listings=["en-US", "en-GB", "fr-FR"])
        self.assertEqual(pl.apply_sync(sess, "pkg", self.root), 0)
        self.assertEqual(sess.verbs_on("/details"), [])
        self.assertEqual(
            [c for c in sess.calls
             if c[0] == "DELETE" and ("en-GB" in c[1] or "fr-FR" in c[1])],
            [])

    def test_set_default_locale_patches_and_does_not_replace_details(self):
        sess = _FakeSession()
        pl.apply_sync(sess, "pkg", self.root, set_default_locale="en-US")
        patches = [(v, p, b) for v, p, b in sess.calls if p.endswith("/details")]
        self.assertEqual(len(patches), 1)
        verb, _path, body = patches[0]
        # PUT would blank contactEmail/contactPhone/contactWebsite, which live
        # only on the store — nothing in this repo could restore them.
        self.assertEqual(verb, "PATCH")
        self.assertEqual(body, {"defaultLanguage": "en-US"})

    def test_prune_deletes_every_other_locale_and_never_the_kept_one(self):
        sess = _FakeSession(listings=["en-US", "en-GB", "fr-FR"])
        pl.apply_sync(sess, "pkg", self.root,
                      set_default_locale="en-US", prune_other_locales=True)
        deleted = [p for v, p, _b in sess.calls
                   if v == "DELETE" and p.startswith("/edits/EDIT1/listings/")]
        self.assertIn("/edits/EDIT1/listings/en-GB", deleted)
        self.assertIn("/edits/EDIT1/listings/fr-FR", deleted)
        self.assertNotIn("/edits/EDIT1/listings/en-US", deleted)

    def test_order_listing_then_default_then_deletes_then_commit(self):
        """The ordering trap, pinned.

        Play rejects a `defaultLanguage` whose listing does not exist, and
        refuses to delete the listing of the current default language. Only
        write -> switch -> delete satisfies both.
        """
        sess = _FakeSession(listings=["en-US", "fr-FR"])
        pl.apply_sync(sess, "pkg", self.root,
                      set_default_locale="en-US", prune_other_locales=True)
        seq = []
        for verb, path, _b in sess.calls:
            if verb == "PUT" and path.endswith("/listings/en-US"):
                seq.append("write-en-US")
            elif verb == "PATCH" and path.endswith("/details"):
                seq.append("set-default")
            elif verb == "DELETE" and path.endswith("/listings/fr-FR"):
                seq.append("delete-fr-FR")
            elif verb == "POST" and path.endswith(":commit"):
                seq.append("commit")
        self.assertEqual(
            seq, ["write-en-US", "set-default", "delete-fr-FR", "commit"])

    def test_prune_with_nothing_to_delete_is_a_no_op(self):
        sess = _FakeSession(listings=["en-US"])
        pl.apply_sync(sess, "pkg", self.root,
                      set_default_locale="en-US", prune_other_locales=True)
        self.assertEqual(
            [p for v, p, _b in sess.calls
             if v == "DELETE" and "/listings/" in p], [])


class PruneCliGuardTest(unittest.TestCase):
    """The destructive flag cannot fire without naming the survivor."""

    def test_prune_without_set_default_locale_exits_2(self):
        self.assertEqual(pl.main(["--apply", "--prune-other-locales"]), 2)

    def test_set_default_locale_must_exist_in_the_repo(self):
        with tempfile.TemporaryDirectory() as tmp:
            (pathlib.Path(tmp) / "en-US").mkdir()
            self.assertEqual(
                pl.main(["--apply", "--listing-dir", tmp,
                         "--set-default-locale", "de-DE"]), 2)


if __name__ == "__main__":
    unittest.main()
