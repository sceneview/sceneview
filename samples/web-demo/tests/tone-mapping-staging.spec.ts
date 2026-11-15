import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { test, expect } from '@playwright/test';

// Inspect real Playwright registration in an isolated checkout. Collection needs
// no browser, so these regressions also run in the Node-only render job.
for (const staged of [false, true]) {
  test(`tone mapping ${staged ? 'enables' : 'skips'} Kotlin when the bundle is ${staged ? 'staged' : 'absent'}`, () => {
    const fixture = mkdtempSync(join(tmpdir(), 'sceneview-tone-mapping-'));
    try {
      mkdirSync(join(fixture, 'tests'));
      copyFileSync(join(__dirname, 'tone-mapping.spec.ts'), join(fixture, 'tests', 'tone-mapping.spec.ts'));
      symlinkSync(resolve(__dirname, '..', 'node_modules'), join(fixture, 'node_modules'), 'dir');
      writeFileSync(join(fixture, 'playwright.config.cjs'), 'module.exports = { testDir: "./tests" };');
      if (staged) {
        mkdirSync(join(fixture, 'site', 'kotlin-bundle'), { recursive: true });
        writeFileSync(join(fixture, 'site', 'kotlin-bundle', 'sceneview-web.js'), '// staged bundle');
      }
      const cli = join(dirname(require.resolve('@playwright/test/package.json')), 'cli.js');
      const report = JSON.parse(execFileSync(process.execPath, [
        cli, 'test', '--config', join(fixture, 'playwright.config.cjs'), '--list', '--reporter=json',
      ], { cwd: fixture, encoding: 'utf8' }));
      const specs = (suite: any): any[] => [
        ...(suite.specs || []), ...(suite.suites || []).flatMap(specs),
      ];
      const cases = report.suites.flatMap(specs);
      expect(cases).toHaveLength(2);
      expect(cases.find((s: any) => s.title.startsWith('website ')).tests[0].expectedStatus).toBe('passed');
      expect(cases.find((s: any) => s.title.startsWith('kotlin ')).tests[0].expectedStatus)
        .toBe(staged ? 'passed' : 'skipped');
      if (!staged) {
        const skipped = JSON.parse(execFileSync(process.execPath, [
          cli, 'test', '--config', join(fixture, 'playwright.config.cjs'), '--grep', 'kotlin', '--reporter=json',
        ], { cwd: fixture, encoding: 'utf8' }));
        expect(skipped.stats.skipped).toBe(1);
        expect(skipped.stats.unexpected).toBe(0);
      }
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });
}

test('the bundle smoke job stages the runtime and selects the tone-mapping spec', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'sceneview-tone-mapping-smoke-'));
  try {
    const scripts = join(fixture, '.claude', 'scripts');
    mkdirSync(scripts, { recursive: true });
    copyFileSync(resolve(__dirname, '../../../.claude/scripts/web-bundle-smoke.sh'), join(scripts, 'web-bundle-smoke.sh'));
    const write = (relative: string, content: string, mode = 0o644) => {
      const path = join(fixture, relative);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, content, { mode });
    };
    write('gradlew', '#!/bin/sh\nexit 0\n', 0o755);
    write('sceneview-web/build/kotlin-webpack/js/productionExecutable/sceneview-web.js', '// production bundle');
    write('build/js/node_modules/filament/filament.js', '// matching runtime');
    write('build/js/node_modules/filament/filament.wasm', 'matching wasm');
    write('bin/npm', '#!/bin/sh\nexit 0\n', 0o755);
    write('bin/npx', '#!/bin/sh\nprintf "%s\\n" "$*" >> "$SCENEVIEW_SMOKE_CALLS"\n', 0o755);
    const calls = join(fixture, 'calls.log');
    execFileSync('bash', [join(scripts, 'web-bundle-smoke.sh')], {
      cwd: fixture,
      env: { ...process.env, PATH: `${join(fixture, 'bin')}:${process.env.PATH}`, SCENEVIEW_SMOKE_CALLS: calls },
    });
    const stage = join(fixture, 'samples/web-demo/site/kotlin-bundle');
    expect(readFileSync(join(stage, 'sceneview-web.js'), 'utf8')).toBe('// production bundle');
    expect(readFileSync(join(stage, 'filament.js'), 'utf8')).toBe('// matching runtime');
    expect(readFileSync(join(stage, 'filament.wasm'), 'utf8')).toBe('matching wasm');
    const invocation = readFileSync(calls, 'utf8').split('\n').find((line) => line.startsWith('playwright test '));
    expect(invocation?.split(/\s+/)).toContain('tests/tone-mapping.spec.ts');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});
