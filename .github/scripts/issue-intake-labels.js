'use strict';

// Deterministic issue-form -> label mapping for the issue-intake workflow
// (`.github/workflows/issue-intake.yml`, #2734). No LLM involved.
//
// SECURITY: `body` is UNTRUSTED external input -- any GitHub user can open
// an issue with an arbitrary body/title. This module only ever reads it as
// a plain JS string and does string matching against a fixed allow-list. It
// must NEVER be passed to a shell command, `eval`'d, or used to construct
// one -- see the workflow file for how it is safely handed in via
// `actions/github-script`'s `context.payload.issue.body` (a JS value, not a
// shell interpolation).
//
// Every label on the right-hand side below MUST already exist in the repo
// (verify with `gh label list -R sceneview/sceneview`). An unrecognized
// dropdown value -- or a value with no mapping -- is a silent no-op. This
// workflow never creates labels on the fly.

// Platform dropdown option (as rendered by bug_report.yml, `id: platform`)
// -> existing `platform: *` label.
//
// iOS, macOS and visionOS intentionally collapse onto the single
// "platform: ios" label -- its description is literally "iOS/macOS/visionOS
// (RealityKit + SwiftUI)", there is no separate macOS/visionOS label in the
// repo. "Desktop" and "Android TV" are valid dropdown options with no
// matching platform label yet, so they resolve to a no-op by omission.
const PLATFORM_LABELS = {
  Android: 'platform: android',
  iOS: 'platform: ios',
  macOS: 'platform: ios',
  visionOS: 'platform: ios',
  Web: 'platform: web',
  Flutter: 'platform: flutter',
  'React Native': 'platform: react-native',
};

// Module dropdown option (as rendered by bug_report.yml, `id: module`) ->
// existing `module: *` label.
//
// "SceneViewSwift (Apple)" and "sceneview-web (Web)" have no module label
// yet, so they resolve to a no-op by omission (the Platform dropdown still
// contributes "platform: ios" / "platform: web" independently). "Other" is
// intentionally unmapped.
const MODULE_LABELS = {
  'sceneview (3D only)': 'module: sceneview',
  'arsceneview (AR)': 'module: arsceneview',
  'sceneview-core (KMP)': 'module: core',
  'MCP server': 'module: mcp',
};

/**
 * Parse the `### <Header>` markdown sections GitHub renders for an
 * issue-form submission into a `{ header: value }` map.
 *
 * @param {string} body Raw issue body (untrusted).
 * @returns {Record<string, string>}
 */
function parseFormSections(body) {
  const sections = {};
  if (typeof body !== 'string' || body.length === 0) return sections;

  const headerRe = /^### (.+?)\s*$/gm;
  const matches = [...body.matchAll(headerRe)];
  for (let i = 0; i < matches.length; i++) {
    const header = matches[i][1].trim();
    const start = matches[i].index + matches[i][0].length;
    const end = i + 1 < matches.length ? matches[i + 1].index : body.length;
    sections[header] = body.slice(start, end).trim();
  }
  return sections;
}

/**
 * Compute the deterministic set of labels to apply to a newly opened issue
 * from its raw (untrusted) body. Only ever returns labels that already
 * exist in the repo (see PLATFORM_LABELS / MODULE_LABELS above).
 *
 * @param {string} body Raw issue body (untrusted).
 * @returns {string[]} Labels to apply -- empty when nothing matched.
 */
function computeLabels(body) {
  const sections = parseFormSections(body);
  const labels = new Set();

  const platform = sections['Platform'];
  if (platform && Object.prototype.hasOwnProperty.call(PLATFORM_LABELS, platform)) {
    labels.add(PLATFORM_LABELS[platform]);
  }

  const module_ = sections['Module'];
  if (module_ && Object.prototype.hasOwnProperty.call(MODULE_LABELS, module_)) {
    labels.add(MODULE_LABELS[module_]);
  }

  return [...labels];
}

module.exports = { computeLabels, parseFormSections, PLATFORM_LABELS, MODULE_LABELS };
