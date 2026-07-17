/**
 * SceneView Web Demo — source-agnostic model catalog layer.
 *
 * Web port of the Android `ModelSource` abstraction (#2645 / PR #2685) and its
 * iOS sibling (#2721), closing the platform trio (#2722). The Explore/Models tab
 * is source-agnostic: it browses whichever catalog the user picks — the bundled
 * SceneView samples, Icosa Gallery, Poly Haven, or (when an API key is
 * configured) Sketchfab — rendering source-agnostic `GalleryModel`s.
 *
 * Unlike iOS (RealityKit loads only USDZ), the web renders glTF natively through
 * Filament.js, so the Creative-Commons catalogs render **in-app** here. Each
 * source maps its own wire format onto `GalleryModel`; the UI never references a
 * concrete source.
 *
 * Behavioural parity with the Android reference (kept deliberately in lock-step):
 *   - Switching source RESETS browse + search.
 *   - Sketchfab "Trending" maps to the featured/likes endpoint, NOT most-popular.
 *   - Sketchfab is HIDDEN unless an API key is configured; Icosa + Poly Haven are
 *     keyless and always available.
 *   - A single degraded source (or one degraded feed) never blanks the tab.
 *   - Poly Haven has a single-flight TTL-cached models index (no editorial
 *     "staff picks", no server-side search — client-side filtered).
 *   - Bounded JSON reads (32 MB) and a per-model streaming size cap (512 MB) are
 *     enforced DURING transfer, aborting the fetch — never a post-buffer check.
 *   - Untrusted, server-supplied ids are sanitized before they become cache keys.
 *
 * No API keys are committed. This module is plain ES5-compatible JS validated by
 * `node -c` (repo rule) and consumed both in the browser (as `window.SceneViewSources`)
 * and, for unit testing, via `module.exports`.
 */
(function (global) {
  'use strict';

  // ── Constants ─────────────────────────────────────────────────────────────

  /**
   * Upper bound on a JSON body a keyless source will buffer from an index / feed
   * / detail endpoint (32 MB). The catalogs return compact JSON; a body this
   * large means a hostile or misbehaving endpoint, so we refuse it instead of
   * buffering an unbounded response into memory (mirrors Android `readBoundedBody`).
   */
  var MAX_SOURCE_JSON_BYTES = 32 * 1024 * 1024;

  /**
   * Per-model streaming ceiling (512 MB), matching the Android
   * `NetworkModelDownloader.MAX_MODEL_BYTES`. A single model file above this is
   * either hostile or a demo mistake — the download is aborted mid-stream.
   */
  var MAX_MODEL_BYTES = 512 * 1024 * 1024;

  var FeedKind = {
    TRENDING: 'trending',
    STAFF_PICKS: 'staffPicks',
    RECENTLY_ADDED: 'recentlyAdded'
  };

  var FEED_LABELS = {
    trending: 'Trending',
    staffPicks: 'Staff Picks',
    recentlyAdded: 'Recently Added'
  };

  var SourceId = {
    SCENEVIEW: 'sceneview',
    SKETCHFAB: 'sketchfab',
    ICOSA: 'icosa',
    POLY_HAVEN: 'polyhaven'
  };

  var SOURCE_DISPLAY_NAMES = {
    sceneview: 'SceneView',
    sketchfab: 'Sketchfab',
    icosa: 'Icosa Gallery',
    polyhaven: 'Poly Haven'
  };

  var USER_AGENT = 'SceneViewWebDemo';

  // ── Small pure helpers ────────────────────────────────────────────────────

  /**
   * Flatten an (untrusted, server-supplied) id into a single safe path segment
   * before it becomes a cache key. Carries forward the Android hardening from
   * #2645: a raw remote asset id must never become a traversal (`../…`) or an
   * absolute path when mapped onto a cache entry.
   */
  function sanitizeId(id) {
    if (id == null) return 'asset';
    var out = '';
    var s = String(id);
    for (var i = 0; i < s.length; i++) {
      var ch = s.charAt(i);
      out += /[A-Za-z0-9\-_]/.test(ch) ? ch : '_';
    }
    return out.length > 0 ? out : 'asset';
  }

  /**
   * Map an Icosa / Poly-style license enum onto a short human-readable label.
   * Shared by the CC sources, which use the same CC vocabulary (mirrors Android
   * `licenseDisplayName`).
   */
  function licenseDisplayName(raw) {
    if (raw == null || raw === '') return null;
    switch (String(raw).toUpperCase()) {
      case 'CREATIVE_COMMONS_BY':
      case 'CC-BY':
      case 'CC_BY':
        return 'CC BY 4.0';
      case 'CREATIVE_COMMONS_BY_ND':
      case 'CC-BY-ND':
        return 'CC BY-ND 4.0';
      case 'CREATIVE_COMMONS_BY_SA':
      case 'CC-BY-SA':
        return 'CC BY-SA 4.0';
      case 'CREATIVE_COMMONS_0':
      case 'CC0':
      case 'CC_0':
        return 'CC0';
      case 'ALL_RIGHTS_RESERVED':
      case 'RESERVED':
        return 'All rights reserved';
      default:
        var spaced = String(raw).replace(/_/g, ' ').toLowerCase();
        return spaced.charAt(0).toUpperCase() + spaced.slice(1);
    }
  }

  /**
   * A source-agnostic model entry rendered by the Explore tab. Factory that fills
   * defaults, mirroring the Android/iOS `GalleryModel` shape.
   *
   * `cardKey` is a stable, cross-source-unique key: a raw `id` is only unique
   * within one source, so the `sourceId` prefix guards against key collisions
   * when the picker switches catalogs or a model surfaces in two feeds.
   */
  function galleryModel(props) {
    var sourceId = props.sourceId;
    var id = props.id;
    return {
      sourceId: sourceId,
      id: id,
      name: props.name || 'Untitled',
      thumbnails: props.thumbnails || [],
      attribution: props.attribution || {},
      tags: props.tags || [],
      faceCount: props.faceCount || 0,
      animationCount: props.animationCount || 0,
      downloadable: props.downloadable !== false,
      // Web-only render descriptor plumbing: curated + Sketchfab carry a direct
      // URL; the CC sources resolve their download lazily via `source.download`.
      localUrl: props.localUrl || null,
      externalUrl: props.externalUrl || null,
      cardKey: sourceId + ':' + id,
      isAnimated: (props.animationCount || 0) > 0
    };
  }

  /**
   * Pick a thumbnail close to the card's render size, falling back to the largest
   * available (then the first). Mirrors the Android/iOS helper.
   */
  function preferredThumbnailUrl(model, minWidth, maxWidth) {
    minWidth = minWidth || 320;
    maxWidth = maxWidth || 640;
    var thumbs = model.thumbnails || [];
    if (thumbs.length === 0) return null;
    var sweetSpot = null;
    var largest = null;
    for (var i = 0; i < thumbs.length; i++) {
      var t = thumbs[i];
      if (t.width >= minWidth && t.width <= maxWidth && !sweetSpot) sweetSpot = t;
      if (!largest || t.width > largest.width) largest = t;
    }
    var chosen = sweetSpot || largest || thumbs[0];
    return chosen ? chosen.url : null;
  }

  /** `"by Ada · CC BY 4.0 · via Icosa Gallery"`, omitting parts a source can't fill. */
  function attributionLine(model) {
    var parts = [];
    var a = model.attribution || {};
    if (a.authorName && String(a.authorName).trim() !== '') parts.push('by ' + a.authorName);
    if (a.license && String(a.license).trim() !== '') parts.push(a.license);
    parts.push('via ' + (SOURCE_DISPLAY_NAMES[model.sourceId] || model.sourceId));
    return parts.join(' · ');
  }

  /** Compact human-readable face count: `1.2k`, `3.4M`, or the raw number. */
  function formattedFaceCount(faceCount) {
    faceCount = faceCount || 0;
    if (faceCount >= 1000000) return (faceCount / 1000000).toFixed(1) + 'M';
    if (faceCount >= 1000) return (faceCount / 1000).toFixed(1) + 'k';
    return String(faceCount);
  }

  // ── Bounded network reads ─────────────────────────────────────────────────

  /**
   * Stream a response body into a single `Uint8Array`, refusing bodies larger
   * than `cap`. The cap is enforced DURING transfer — the reader is cancelled and
   * the fetch aborted the moment the cumulative byte count exceeds the cap — so a
   * chunked / no-Content-Length response can never materialise an unbounded
   * in-memory buffer. This mirrors the okio bounded read the Android port uses
   * (#2685), not a post-buffer size check.
   */
  function streamBoundedBytes(url, options) {
    options = options || {};
    var cap = options.cap || MAX_MODEL_BYTES;
    var onProgress = options.onProgress || null;
    // Chain any caller-provided abort signal onto our own controller so a
    // superseded download (or a cap breach) aborts the socket, not just the JS.
    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (options.signal && controller) {
      if (options.signal.aborted) controller.abort();
      else options.signal.addEventListener('abort', function () { controller.abort(); });
    }
    var headers = { 'User-Agent': USER_AGENT };
    if (options.headers) {
      for (var h in options.headers) {
        if (Object.prototype.hasOwnProperty.call(options.headers, h)) headers[h] = options.headers[h];
      }
    }

    return fetch(url, { headers: headers, signal: controller ? controller.signal : undefined })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Request failed: HTTP ' + response.status + ' for ' + url);
        }
        // Fast-fail on an honestly-advertised over-cap size before reading a byte.
        var declared = parseInt(response.headers.get('content-length') || '-1', 10);
        var totalBytes = isNaN(declared) ? -1 : declared;
        if (totalBytes > cap) {
          if (controller) controller.abort();
          throw new Error('Response body ' + totalBytes + ' B exceeds ' + cap + ' B cap for ' + url);
        }
        // Without a streaming body reader we cannot enforce the cap mid-transfer
        // — refuse rather than buffer an unbounded response.
        if (!response.body || typeof response.body.getReader !== 'function') {
          throw new Error('Streaming reader unavailable — cannot bound ' + url);
        }
        var reader = response.body.getReader();
        var chunks = [];
        var received = 0;

        function pump() {
          return reader.read().then(function (result) {
            if (result.done) {
              var merged = new Uint8Array(received);
              var offset = 0;
              for (var i = 0; i < chunks.length; i++) {
                merged.set(chunks[i], offset);
                offset += chunks[i].length;
              }
              if (onProgress) onProgress(received, totalBytes);
              return merged;
            }
            received += result.value.length;
            // Enforce the cap mid-stream — cancel the reader and abort the fetch
            // so a rejected transfer never keeps streaming in the background.
            if (received > cap) {
              try { reader.cancel(); } catch (e) { /* best-effort */ }
              if (controller) controller.abort();
              throw new Error('Download exceeded ' + cap + ' B cap for ' + url);
            }
            chunks.push(result.value);
            if (onProgress) onProgress(received, totalBytes);
            return pump();
          });
        }
        return pump();
      });
  }

  /**
   * GET `url` and return its body as UTF-8 text, refusing bodies larger than
   * `cap` (default 32 MB) — the JSON analogue of `streamBoundedBytes`.
   */
  function fetchBoundedText(url, options) {
    options = options || {};
    var opts = {
      cap: options.cap || MAX_SOURCE_JSON_BYTES,
      signal: options.signal,
      headers: { 'Accept': 'application/json' }
    };
    if (options.headers) {
      for (var h in options.headers) {
        if (Object.prototype.hasOwnProperty.call(options.headers, h)) opts.headers[h] = options.headers[h];
      }
    }
    return streamBoundedBytes(url, opts).then(function (bytes) {
      return new TextDecoder('utf-8').decode(bytes);
    });
  }

  function fetchBoundedJson(url, options) {
    return fetchBoundedText(url, options).then(function (text) {
      return JSON.parse(text);
    });
  }

  /** Build a URL with query params without leaking undefined/null values. */
  function buildUrl(base, path, params) {
    var url = base.replace(/\/+$/, '') + '/' + String(path).replace(/^\/+/, '');
    var pairs = [];
    if (params) {
      for (var k in params) {
        if (Object.prototype.hasOwnProperty.call(params, k) && params[k] != null) {
          pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(params[k]));
        }
      }
    }
    return pairs.length > 0 ? url + '?' + pairs.join('&') : url;
  }

  // ── Feed resilience ───────────────────────────────────────────────────────

  /**
   * Run every declared feed of a source independently and return a section per
   * feed, catching per-feed so one degraded feed never blanks the tab (Android
   * resilience contract). A failed feed yields `{ failed: true, models: [] }`;
   * survivors render normally.
   */
  function browseFeeds(source, fetchOne, options) {
    options = options || {};
    var kinds = source.feedKinds || [];
    return Promise.all(kinds.map(function (kind) {
      return Promise.resolve()
        .then(function () { return fetchOne(kind); })
        .then(function (models) {
          return { kind: kind, title: FEED_LABELS[kind] || kind, models: models || [], failed: false };
        })
        .catch(function (err) {
          return { kind: kind, title: FEED_LABELS[kind] || kind, models: [], failed: true, error: err };
        });
    }));
  }

  // ── Curated (bundled samples) source ──────────────────────────────────────

  /**
   * The always-present, offline, self-hosted SceneView samples. This is the web
   * analogue of the "always-present curated samples" the Android resilience
   * contract keeps alongside the picker — surfaced here as the default source
   * chip so the tab is never sourceless and works with zero network. There is no
   * Android `ModelSourceId` for it (Android shows the samples separately); the
   * divergence is deliberate and documented (#2722).
   */
  function createCuratedSource(curatedModels, modelBase) {
    var CATEGORY_ORDER = ['Showcase', 'Vehicles', 'Animated', 'Characters', 'Objects'];
    return {
      id: SourceId.SCENEVIEW,
      displayName: SOURCE_DISPLAY_NAMES[SourceId.SCENEVIEW],
      isAvailable: true,
      curated: true,
      supportsSearch: false,
      supportsAnimatedFilter: false,
      rendersInApp: true,
      feedKinds: [],
      browse: function () {
        // Group the bundled models by category, preserving the display order.
        var byCategory = {};
        curatedModels.forEach(function (m) {
          var cat = m.category || 'Objects';
          if (!byCategory[cat]) byCategory[cat] = [];
          byCategory[cat].push(galleryModel({
            sourceId: SourceId.SCENEVIEW,
            id: m.file,
            name: m.name,
            tags: [m.category],
            localUrl: modelBase + m.file,
            attribution: { license: null, authorName: null }
          }));
        });
        var sections = [];
        CATEGORY_ORDER.forEach(function (cat) {
          if (byCategory[cat]) sections.push({ kind: cat, title: cat, models: byCategory[cat], failed: false });
        });
        // Any category outside the known order still renders (never drop models).
        for (var cat in byCategory) {
          if (Object.prototype.hasOwnProperty.call(byCategory, cat) && CATEGORY_ORDER.indexOf(cat) === -1) {
            sections.push({ kind: cat, title: cat, models: byCategory[cat], failed: false });
          }
        }
        return Promise.resolve(sections);
      },
      search: function () { return Promise.resolve([]); },
      download: function (model) {
        // Self-hosted, trusted, already-local — no streaming cap needed.
        return Promise.resolve({ type: 'url', url: model.localUrl });
      }
    };
  }

  // ── Icosa Gallery source ──────────────────────────────────────────────────

  /**
   * `ModelSource` for **Icosa Gallery** — the open-source Google Poly successor
   * (`api.icosa.gallery/v1`). Creative-Commons, glTF-native, no auth (#2645).
   * The best drop-in Sketchfab-shaped replacement: trending / curated / newest
   * feeds and free-text search, all as plain JSON, and — because the web renders
   * glTF natively — it renders in-app.
   */
  function createIcosaSource(options) {
    options = options || {};
    var baseUrl = options.baseUrl || 'https://api.icosa.gallery/v1/';
    var cache = {}; // sanitized cardKey → render descriptor (in-memory)

    function mapAsset(asset) {
      var resolvedId = asset.assetId || asset.id || asset.name;
      if (!resolvedId) return null;
      var formats = asset.formats || [];
      var hasRenderable = formats.some(function (f) { return f.root && f.root.url; });
      if (!hasRenderable) return null;
      var thumbs = [];
      if (asset.thumbnail && asset.thumbnail.url) {
        thumbs.push({
          url: asset.thumbnail.url,
          width: asset.thumbnail.width > 0 ? asset.thumbnail.width : 1024,
          height: asset.thumbnail.height > 0 ? asset.thumbnail.height : 1024
        });
      }
      return galleryModel({
        sourceId: SourceId.ICOSA,
        id: resolvedId,
        name: asset.displayName || asset.name || 'Untitled',
        thumbnails: thumbs,
        attribution: {
          authorName: asset.authorName || null,
          license: licenseDisplayName(asset.license),
          sourceUrl: 'https://icosa.gallery/view/' + resolvedId
        },
        tags: asset.tags || [],
        faceCount: asset.triangleCount || 0
      });
    }

    function getAssets(params, signal) {
      return fetchBoundedJson(buildUrl(baseUrl, 'assets', params), { signal: signal })
        .then(function (response) {
          return (response.assets || []).map(mapAsset).filter(function (m) { return m != null; });
        });
    }

    /** Prefer a self-contained GLB, then any GLTF2, then whatever has a root URL. */
    function isGlbUrl(url) {
      return !!url && /\.glb$/i.test(url.split('?')[0]);
    }
    /**
     * Legacy Poly-era mirrors on web.archive.org are dead for browser use:
     * they 404 and carry no CORS headers, so fetch() throws "Failed to fetch"
     * (found live on asset 5rf3YuZfJAW, whose first .glb format is an archive
     * mirror). Prefer anything hosted by Icosa's own storage; archive URLs are
     * a last resort only, surfacing an honest load error.
     */
    function isArchiveUrl(url) {
      return /^https?:\/\/web\.archive\.org\//i.test(url || '');
    }
    function preferredFormat(asset) {
      var formats = (asset.formats || []).filter(function (f) { return f.root && f.root.url; });
      var live = formats.filter(function (f) { return !isArchiveUrl(f.root.url); });
      var pool = live.length ? live : formats;
      var glb = pool.filter(function (f) { return isGlbUrl(f.root.url); })[0];
      if (glb) return glb;
      var gltf2 = pool.filter(function (f) { return (f.formatType || '').toUpperCase() === 'GLTF2'; })[0];
      if (gltf2) return gltf2;
      return pool[0] || null;
    }

    return {
      id: SourceId.ICOSA,
      displayName: SOURCE_DISPLAY_NAMES[SourceId.ICOSA],
      isAvailable: true,
      curated: false,
      supportsSearch: true,
      supportsAnimatedFilter: false,
      rendersInApp: true,
      feedKinds: [FeedKind.TRENDING, FeedKind.STAFF_PICKS, FeedKind.RECENTLY_ADDED],
      browse: function (opts) {
        opts = opts || {};
        var limit = opts.limit || 10;
        var self = this;
        return browseFeeds(self, function (kind) {
          var params = { format: 'GLTF2', pageSize: limit };
          if (kind === FeedKind.TRENDING) {
            params.orderBy = 'BEST';
          } else if (kind === FeedKind.STAFF_PICKS) {
            params.orderBy = 'BEST';
            params.curated = 'true';
          } else {
            params.orderBy = 'NEWEST';
          }
          return getAssets(params, opts.signal);
        });
      },
      search: function (query, opts) {
        opts = opts || {};
        return getAssets({ format: 'GLTF2', keywords: query, pageSize: opts.limit || 24 }, opts.signal);
      },
      download: function (model, opts) {
        opts = opts || {};
        var key = sanitizeId(SourceId.ICOSA) + ':' + sanitizeId(model.id);
        if (cache[key]) return Promise.resolve(cache[key]);
        // Re-resolve the asset so the download plan (formats + resources) is
        // never stale — the feed/search response carried display metadata only.
        return fetchBoundedJson(buildUrl(baseUrl, 'assets/' + encodeURIComponent(model.id), {}), { signal: opts.signal })
          .then(function (asset) {
            var format = preferredFormat(asset);
            if (!format || !format.root || !format.root.url) {
              throw new Error('Icosa asset ' + model.id + ' has no glTF format');
            }
            var rootUrl = format.root.url;
            if (isGlbUrl(rootUrl)) {
              return streamBoundedBytes(rootUrl, { signal: opts.signal, onProgress: opts.onProgress })
                .then(function (bytes) {
                  var d = { type: 'glb', bytes: bytes };
                  cache[key] = d;
                  return d;
                });
            }
            return downloadGltfBundle(format.root, format.resources || [], opts).then(function (d) {
              cache[key] = d;
              return d;
            });
          });
      }
    };
  }

  /**
   * Fetch a multi-file glTF root plus its resources into memory, streaming each
   * under the model cap. Returns a `{ type:'gltf', rootBytes, resources }`
   * descriptor the render layer pre-populates into `Filament.assets`.
   *
   * `root` is `{ url, relativePath }`; `resources` is an array of
   * `{ url, relativePath }` (the Icosa shape). Poly Haven passes an equivalent.
   */
  function downloadGltfBundle(root, resources, opts) {
    opts = opts || {};
    var rootRelative = (root.relativePath && root.relativePath !== '')
      ? root.relativePath
      : (root.url.split('/').pop().split('?')[0] || 'model.gltf');
    var resList = [];
    (resources || []).forEach(function (res) {
      var rel = res.relativePath;
      var url = res.url;
      if (rel && rel !== '' && url && url !== '') resList.push({ relativePath: rel, url: url });
    });
    return Promise.all(resList.map(function (res) {
      return streamBoundedBytes(res.url, { signal: opts.signal }).then(function (bytes) {
        return { relativePath: res.relativePath, bytes: bytes };
      });
    })).then(function (fetchedResources) {
      return streamBoundedBytes(root.url, { signal: opts.signal, onProgress: opts.onProgress })
        .then(function (rootBytes) {
          return { type: 'gltf', rootRelativePath: rootRelative, rootBytes: rootBytes, resources: fetchedResources };
        });
    });
  }

  // ── Poly Haven source ─────────────────────────────────────────────────────

  /**
   * `ModelSource` for **Poly Haven** — CC0 assets with pristine PBR
   * (`api.polyhaven.com`), no auth (#2645). No editorial "staff picks" and no
   * server-side keyword search, so it honestly exposes only Trending (by download
   * count) and Recently added (by publish date), and searches client-side over
   * the models index.
   *
   * The models index is one call for the whole catalog; a single-flight promise +
   * TTL cache collapses the concurrent cold callers (Trending + Recently added +
   * an early search all fire at cold open) to one GET — the JS analogue of the
   * Android `Mutex` + volatile cache.
   */
  function createPolyHavenSource(options) {
    options = options || {};
    var baseUrl = options.baseUrl || 'https://api.polyhaven.com/';
    var cdnBaseUrl = options.cdnBaseUrl || 'https://cdn.polyhaven.com/';
    var indexTtlMs = options.indexTtlMs || 5 * 60 * 1000;

    var cachedIndex = null;
    var cachedAtMs = 0;
    var inFlight = null;
    var cache = {}; // sanitized cardKey → render descriptor

    function freshIndex() {
      return (cachedIndex && (Date.now() - cachedAtMs) < indexTtlMs) ? cachedIndex : null;
    }

    function modelsIndex(signal) {
      var fresh = freshIndex();
      if (fresh) return Promise.resolve(fresh);
      // Single-flight: collapse concurrent cold callers onto one request.
      if (inFlight) return inFlight;
      inFlight = fetchBoundedJson(buildUrl(baseUrl, 'assets', { t: 'models' }), { signal: signal })
        .then(function (index) {
          cachedIndex = index;
          cachedAtMs = Date.now();
          inFlight = null;
          return index;
        })
        .catch(function (err) {
          inFlight = null;
          throw err;
        });
      return inFlight;
    }

    function mapAsset(slug, asset) {
      var thumbBase = cdnBaseUrl.replace(/\/+$/, '') + '/asset_img/thumbs/' + slug + '.png';
      var fallbackName = slug.replace(/_/g, ' ');
      fallbackName = fallbackName.charAt(0).toUpperCase() + fallbackName.slice(1);
      var authorNames = asset.authors ? Object.keys(asset.authors) : [];
      var tags = ((asset.categories || []).concat(asset.tags || []));
      var seen = {};
      var distinct = [];
      tags.forEach(function (t) { if (!seen[t]) { seen[t] = true; distinct.push(t); } });
      return galleryModel({
        sourceId: SourceId.POLY_HAVEN,
        id: slug,
        name: asset.name || fallbackName,
        thumbnails: [
          { url: thumbBase + '?height=360', width: 640, height: 360 },
          { url: thumbBase + '?height=720', width: 1280, height: 720 }
        ],
        attribution: {
          authorName: authorNames.length > 0 ? authorNames[0] : null,
          license: 'CC0', // Poly Haven is uniformly CC0.
          sourceUrl: 'https://polyhaven.com/a/' + slug
        },
        tags: distinct
      });
    }

    function sortedEntries(index, byField) {
      var entries = [];
      for (var slug in index) {
        if (Object.prototype.hasOwnProperty.call(index, slug)) entries.push([slug, index[slug]]);
      }
      entries.sort(function (a, b) { return (b[1][byField] || 0) - (a[1][byField] || 0); });
      return entries;
    }

    function matches(slug, asset, needle) {
      if ((asset.name || '').toLowerCase().indexOf(needle) >= 0) return true;
      if (slug.toLowerCase().indexOf(needle) >= 0) return true;
      var lists = (asset.tags || []).concat(asset.categories || []);
      for (var i = 0; i < lists.length; i++) {
        if (String(lists[i]).toLowerCase().indexOf(needle) >= 0) return true;
      }
      return false;
    }

    return {
      id: SourceId.POLY_HAVEN,
      displayName: SOURCE_DISPLAY_NAMES[SourceId.POLY_HAVEN],
      isAvailable: true,
      curated: false,
      supportsSearch: true,
      supportsAnimatedFilter: false,
      rendersInApp: true,
      feedKinds: [FeedKind.TRENDING, FeedKind.RECENTLY_ADDED],
      browse: function (opts) {
        opts = opts || {};
        var limit = opts.limit || 10;
        var self = this;
        return browseFeeds(self, function (kind) {
          return modelsIndex(opts.signal).then(function (index) {
            var field = kind === FeedKind.TRENDING ? 'download_count' : 'date_published';
            return sortedEntries(index, field).slice(0, limit).map(function (e) { return mapAsset(e[0], e[1]); });
          });
        });
      },
      search: function (query, opts) {
        opts = opts || {};
        var needle = String(query || '').trim().toLowerCase();
        if (needle === '') return Promise.resolve([]);
        return modelsIndex(opts.signal).then(function (index) {
          var results = [];
          for (var slug in index) {
            if (Object.prototype.hasOwnProperty.call(index, slug) && matches(slug, index[slug], needle)) {
              results.push([slug, index[slug]]);
            }
          }
          results.sort(function (a, b) { return (b[1].download_count || 0) - (a[1].download_count || 0); });
          return results.slice(0, opts.limit || 24).map(function (e) { return mapAsset(e[0], e[1]); });
        });
      },
      download: function (model, opts) {
        opts = opts || {};
        var key = sanitizeId(SourceId.POLY_HAVEN) + ':' + sanitizeId(model.id);
        if (cache[key]) return Promise.resolve(cache[key]);
        return fetchBoundedJson(buildUrl(baseUrl, 'files/' + encodeURIComponent(model.id), {}), { signal: opts.signal })
          .then(function (files) {
            var gltf = files.gltf;
            if (!gltf) throw new Error('Poly Haven asset ' + model.id + ' has no glTF files');
            // Prefer the lightest resolution for a snappy demo download.
            var resKeys = Object.keys(gltf);
            var resolution = resKeys.filter(function (k) { return k.toLowerCase() === '1k'; })[0] || resKeys[0];
            if (!resolution) throw new Error('Poly Haven asset ' + model.id + ' exposes no glTF resolution');
            var resObj = gltf[resolution];
            // Inside a resolution the file object is keyed by format name — take
            // the first entry rather than assuming the key.
            var formatKeys = Object.keys(resObj);
            var fileObj = formatKeys.length > 0 ? resObj[formatKeys[0]] : null;
            if (!fileObj || !fileObj.url) throw new Error('Poly Haven asset ' + model.id + ' glTF entry is empty');
            var rootRelative = fileObj.url.split('/').pop().split('?')[0] || 'model.gltf';
            var resources = [];
            var include = fileObj.include || {};
            for (var rel in include) {
              if (Object.prototype.hasOwnProperty.call(include, rel) && include[rel] && include[rel].url) {
                resources.push({ relativePath: rel, url: include[rel].url });
              }
            }
            return downloadGltfBundle({ url: fileObj.url, relativePath: rootRelative }, resources, opts);
          })
          .then(function (d) { cache[key] = d; return d; });
      }
    };
  }

  // ── Sketchfab source ──────────────────────────────────────────────────────

  /**
   * `ModelSource` backed by the Sketchfab Data API v3 — the original catalog, now
   * one implementation among several (#2645). HIDDEN unless an API key is
   * configured (parity with Android/iOS): `isAvailable` is `false` without a key,
   * so the chip never appears on the keyless default build. No key is committed.
   *
   * Web subset: Sketchfab's download endpoint serves a ZIP archive (not a
   * self-contained GLB), which Filament.js cannot parse in-browser, so selecting a
   * Sketchfab model opens its viewer page rather than faking an in-app render. The
   * browse/search catalog is fully functional. This mirrors the honest-degradation
   * stance used elsewhere in the demo (#2722).
   */
  function createSketchfabSource(options) {
    options = options || {};
    var apiKey = options.apiKey || null;
    var baseUrl = options.baseUrl || 'https://api.sketchfab.com/v3/';

    function authHeaders() {
      return apiKey ? { 'Authorization': 'Token ' + apiKey } : {};
    }

    function mapModel(model) {
      var thumbs = (model.thumbnails && model.thumbnails.images ? model.thumbnails.images : []).map(function (im) {
        return { url: im.url, width: im.width || 0, height: im.height || 0 };
      });
      return galleryModel({
        sourceId: SourceId.SKETCHFAB,
        id: model.uid,
        name: model.name || 'Untitled',
        thumbnails: thumbs,
        attribution: {
          authorName: model.user ? (model.user.displayName || model.user.username || null) : null,
          sourceUrl: model.viewerUrl || ('https://sketchfab.com/3d-models/' + model.uid)
        },
        tags: (model.tags || []).map(function (t) { return t.name || t; }),
        faceCount: model.faceCount || 0,
        animationCount: model.animationCount || 0,
        downloadable: model.isDownloadable !== false,
        externalUrl: 'https://sketchfab.com/3d-models/' + model.uid
      });
    }

    function listModels(params, signal) {
      return fetchBoundedJson(buildUrl(baseUrl, 'search', params), { signal: signal, headers: authHeaders() })
        .then(function (data) { return (data.results || []).map(mapModel); });
    }

    return {
      id: SourceId.SKETCHFAB,
      displayName: SOURCE_DISPLAY_NAMES[SourceId.SKETCHFAB],
      isAvailable: apiKey != null,
      curated: false,
      supportsSearch: true,
      supportsAnimatedFilter: true,
      rendersInApp: false,
      feedKinds: [FeedKind.TRENDING, FeedKind.STAFF_PICKS, FeedKind.RECENTLY_ADDED],
      browse: function (opts) {
        opts = opts || {};
        var limit = opts.limit || 10;
        var animated = opts.animatedOnly ? true : null;
        var self = this;
        return browseFeeds(self, function (kind) {
          var params = { type: 'models', downloadable: 'true', count: limit };
          if (animated) params.animated = 'true';
          if (kind === FeedKind.TRENDING) {
            // Deliberate: "Trending" maps to the featured/likes ordering, NOT
            // most-popular — strict parity with the merged Android port.
            params.sort_by = '-likeCount';
          } else if (kind === FeedKind.STAFF_PICKS) {
            params.staffpicked = 'true';
            params.sort_by = '-publishedAt';
          } else {
            params.sort_by = '-publishedAt';
          }
          return listModels(params, opts.signal);
        });
      },
      search: function (query, opts) {
        opts = opts || {};
        var params = { type: 'models', downloadable: 'true', q: query, count: opts.limit || 24 };
        if (opts.animatedOnly) params.animated = 'true';
        return listModels(params, opts.signal);
      },
      download: function (model) {
        // ZIP-archive download can't be parsed by Filament.js in-browser — open
        // the model's viewer page instead of faking an in-app render.
        return Promise.resolve({ type: 'external', url: model.externalUrl });
      }
    };
  }

  // ── Selected-source persistence ───────────────────────────────────────────

  /**
   * localStorage-backed memory of the last source picked, so the tab reopens on
   * the same catalog (mirrors Android `SelectedSourceStore`). Degrades to a no-op
   * when localStorage is unavailable (private mode / disabled).
   */
  function createSelectedSourceStore(storageKey) {
    var KEY = storageKey || 'sceneview.explore.selectedSource';
    function storage() {
      try { return global.localStorage || null; } catch (e) { return null; }
    }
    return {
      load: function () {
        var s = storage();
        try { return s ? s.getItem(KEY) : null; } catch (e) { return null; }
      },
      save: function (id) {
        var s = storage();
        try { if (s) s.setItem(KEY, id); } catch (e) { /* best-effort */ }
      }
    };
  }

  // ── Registry ──────────────────────────────────────────────────────────────

  /**
   * Build the available-source list in display order, dropping unavailable
   * sources (Sketchfab without a key). SceneView (curated), Icosa and Poly Haven
   * are always available, so the list is never empty and the tab is never
   * sourceless.
   */
  function buildSources(config) {
    config = config || {};
    var all = [
      createCuratedSource(config.curatedModels || [], config.modelBase || 'models/'),
      createIcosaSource({ baseUrl: config.icosaBaseUrl }),
      createPolyHavenSource({ baseUrl: config.polyHavenBaseUrl, cdnBaseUrl: config.polyHavenCdnBaseUrl }),
      createSketchfabSource({ apiKey: config.sketchfabApiKey, baseUrl: config.sketchfabBaseUrl })
    ];
    return all.filter(function (s) { return s.isAvailable; });
  }

  /**
   * Resolve the initial selection: the persisted choice when still available,
   * else the first available source (SceneView curated).
   */
  function resolveInitialSource(sources, savedId) {
    var saved = sources.filter(function (s) { return s.id === savedId; })[0];
    return saved || sources[0];
  }

  // ── Export ────────────────────────────────────────────────────────────────

  var api = {
    MAX_SOURCE_JSON_BYTES: MAX_SOURCE_JSON_BYTES,
    MAX_MODEL_BYTES: MAX_MODEL_BYTES,
    FeedKind: FeedKind,
    FEED_LABELS: FEED_LABELS,
    SourceId: SourceId,
    SOURCE_DISPLAY_NAMES: SOURCE_DISPLAY_NAMES,
    sanitizeId: sanitizeId,
    licenseDisplayName: licenseDisplayName,
    galleryModel: galleryModel,
    preferredThumbnailUrl: preferredThumbnailUrl,
    attributionLine: attributionLine,
    formattedFaceCount: formattedFaceCount,
    streamBoundedBytes: streamBoundedBytes,
    fetchBoundedText: fetchBoundedText,
    fetchBoundedJson: fetchBoundedJson,
    buildUrl: buildUrl,
    createCuratedSource: createCuratedSource,
    createIcosaSource: createIcosaSource,
    createPolyHavenSource: createPolyHavenSource,
    createSketchfabSource: createSketchfabSource,
    createSelectedSourceStore: createSelectedSourceStore,
    buildSources: buildSources,
    resolveInitialSource: resolveInitialSource
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
  global.SceneViewSources = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this));
