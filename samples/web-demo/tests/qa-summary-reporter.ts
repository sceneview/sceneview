import type {
  Reporter,
  TestCase,
  TestResult,
  FullResult,
} from '@playwright/test/reporter';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Machine-readable QA summary reporter for the SceneView Web Demo.
 *
 * Emits `test-results/web-qa-summary.json` — a flat, stable shape the
 * autonomous device-QA orchestrator runner (slice 5, issue #1566) consumes
 * to decide whether the web platform passed its release-checkpoint QA.
 *
 * Shape:
 * {
 *   "platform": "web",
 *   "tool": "playwright",
 *   "status": "passed" | "failed",
 *   "startedAt": "<ISO>",
 *   "durationMs": <number>,
 *   "totals": { "passed": n, "failed": n, "skipped": n, "flaky": n },
 *   "tests": [ { "title", "status", "durationMs", "error"? }, ... ]
 * }
 */
export default class QaSummaryReporter implements Reporter {
  private startedAt = new Date();
  private tests: Array<{
    title: string;
    status: string;
    durationMs: number;
    error?: string;
  }> = [];

  onTestEnd(test: TestCase, result: TestResult): void {
    this.tests.push({
      title: test.titlePath().slice(1).join(' › '),
      status: result.status,
      durationMs: result.duration,
      error: result.error?.message?.split('\n')[0],
    });
  }

  onEnd(result: FullResult): void {
    const totals = {
      passed: this.tests.filter((t) => t.status === 'passed').length,
      failed: this.tests.filter(
        (t) => t.status === 'failed' || t.status === 'timedOut',
      ).length,
      skipped: this.tests.filter((t) => t.status === 'skipped').length,
      flaky: this.tests.filter((t) => t.status === 'interrupted').length,
    };

    const summary = {
      platform: 'web',
      tool: 'playwright',
      status: result.status === 'passed' ? 'passed' : 'failed',
      startedAt: this.startedAt.toISOString(),
      durationMs: Date.now() - this.startedAt.getTime(),
      totals,
      tests: this.tests,
    };

    const outDir = path.join(__dirname, '..', 'test-results');
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(
      path.join(outDir, 'web-qa-summary.json'),
      JSON.stringify(summary, null, 2),
    );
  }
}
