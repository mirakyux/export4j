// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

async function fixture(t) {
  const directory = await mkdtemp(path.join(tmpdir(), 'export4j-docs-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const put = async (name, value) => {
    await mkdir(path.dirname(path.join(directory, name)), { recursive: true });
    await writeFile(path.join(directory, name), value);
  };
  const git = args => {
    const result = spawnSync('git', args, { cwd: directory, encoding: 'utf8', timeout: 10_000 });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout;
  };
  git(['init', '--quiet']);
  await put('README.md', '# Getting started\n');
  await put('README.zh-CN.md', '# 快速开始\n');
  await put('docs/known.md', '# Known\n');
  await put('docs/verification/known.md', '# Known verification\n');
  await put('docs/en/known.md', '# Known\n');
  await put('docs/faq.md', '# FAQ\n');
  await put('docs/faq.zh-CN.md', '# 常见问题\n');
  await put('docs/documentation-site.md', '# Website\n');
  await put('site/pages/public/showcase/README.md', '# Showcase\n');
  await put('third-party/docs-site-licenses/README.md', '# Licenses\n');
  await put('tools/existing.txt', 'tracked repository resource\n');
  git(['add', 'README.md', 'README.zh-CN.md', 'docs', 'site/pages/public', 'third-party', 'tools']);
  const indexBefore = git(['ls-files', '-s']);
  await put('site/.vitepress/navigation.mjs', `
export const guideGroups = [{ items: [{ name: 'known' }, { name: 'selected-api' }] }];
export const translatedGuides = ['known', 'selected-api'];
`);
  await put('docs/selected-api.md', '# Selected\n\n## Selected section\n');
  await put('docs/en/selected-api.md', '# Selected\n');
  await put('docs/literature-pdf-integration.md', '# Literature\n');
  await put('docs/verification/inline-vectors.md', '# Vector verification\n');
  await put('docs/verification/math.md', '# Math verification\n');
  await put('docs/adr/0113-optional-bounded-math.md', '# Optional bounded math\n');
  await put('docs/verification/font-cache.md', '# Font cache verification\n');
  await put('docs/adr/0114-idle-expiring-font-caches.md', '# Idle font caches\n');
  await put('docs/verification/content-blocks.md', '# Source content verification\n');
  await put('docs/adr/0115-source-content-options.md', '# Source content options\n');
  await put('docs/adr/0116-mixed-semantic-blocks-and-static-vectors.md', '# Mixed semantic blocks and static vectors\n');
  await put('docs/adr/0117-single-source-math-representations.md', '# Single source math representations\n');
  await put('docs/verification/rich-content.md', '# Rich content verification\n');
  await mkdir(path.join(directory, 'scripts'), { recursive: true });
  await copyFile(path.join(project, 'scripts/prepare_docs_site.mjs'), path.join(directory, 'scripts/prepare_docs_site.mjs'));
  await symlink(path.join(project, 'site/node_modules'), path.join(directory, 'site/node_modules'), 'junction');
  return {
    directory, put,
    run() {
      const result = spawnSync(process.execPath, ['scripts/prepare_docs_site.mjs'], {
        cwd: directory, encoding: 'utf8', timeout: 20_000,
      });
      assert.equal(git(['ls-files', '-s']), indexBefore, 'preparing documentation must not stage files');
      return result;
    },
    read: name => readFile(path.join(directory, name), 'utf8'),
  };
}

test('previews selected new guides and Java sources without staging local notes', { timeout: 30_000 }, async t => {
  const f = await fixture(t);
  await f.put('docs/selected-api.md', '# Selected\n\n## Selected section\n\n'
    + '[Literature](literature-pdf-integration.md) [Anchor](#selected-section)\n'
    + '[Source](../export4j-model/src/main/java/example/NewType.java)\n'
    + '[Verifier](../scripts/verify_new.py)\n'
    + '[Existing](../tools/existing.txt)\n');
  await f.put('docs/en/selected-api.md', '# Selected\n\n[Chinese](../selected-api.md)\n');
  await f.put('export4j-model/src/main/java/example/NewType.java', 'package example;\n');
  await f.put('scripts/verify_new.py', '# Independent source verifier\n');
  await f.put('docs/local-private.md', '# Private local notes\n');
  await f.put('docs/ignored-api.md', '# Ignored local API notes\n');
  await f.put('.gitignore', 'docs/ignored-api.md\n');
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const chinese = await f.read('site/pages/zh/docs/selected-api.md');
  assert.match(chinese, /\[Literature\]\(\/zh\/docs\/literature-pdf-integration\.md\)/);
  assert.match(chinese, /\[Anchor\]\(#selected-section\)/);
  assert.match(chinese, /https:\/\/github\.com\/mirakyux\/export4j\/blob\/main\/export4j-model\/src\/main\/java\/example\/NewType\.java/);
  assert.match(chinese, /https:\/\/github\.com\/mirakyux\/export4j\/blob\/main\/scripts\/verify_new\.py/);
  assert.match(chinese, /https:\/\/github\.com\/mirakyux\/export4j\/blob\/main\/tools\/existing\.txt/);
  assert.match(await f.read('site/pages/docs/selected-api.md'), /\[Chinese\]\(\/zh\/docs\/selected-api\.md\)/);
  assert.match(await f.read('site/pages/zh/docs/literature-pdf-integration.md'), /# Literature/);
  assert.match(await f.read('site/pages/zh/docs/verification/inline-vectors.md'), /# Vector verification/);
  assert.match(await f.read('site/pages/zh/docs/verification/content-blocks.md'), /# Source content verification/);
  assert.match(await f.read('site/pages/zh/docs/adr/0115-source-content-options.md'), /# Source content options/);
  await assert.rejects(f.read('site/pages/zh/docs/local-private.md'), { code: 'ENOENT' });
  await assert.rejects(f.read('site/pages/zh/docs/ignored-api.md'), { code: 'ENOENT' });
});

test('rejects ignored public selections', { timeout: 30_000 }, async t => {
  const f = await fixture(t);
  await f.put('.gitignore', 'docs/selected-api.md\n');
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Public guide is missing or ignored by Git: docs\/selected-api\.md/);
});

test('rejects missing public selections', { timeout: 30_000 }, async t => {
  const f = await fixture(t);
  await rm(path.join(f.directory, 'docs/en/selected-api.md'));
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Public guide is missing or ignored by Git: docs\/en\/selected-api\.md/);
});

for (const [label, target, ignored] of [
  ['unselected local documents', 'docs/local-private.md', false],
  ['ignored Java sources', 'export4j-model/src/test/java/example/Ignored.java', true],
  ['Java files outside source directories', 'private/Secret.java', false],
]) {
  test(`rejects links to ${label}`, { timeout: 30_000 }, async t => {
    const f = await fixture(t);
    await f.put(target, target.endsWith('.md') ? '# Local\n' : 'package example;\n');
    await f.put('docs/selected-api.md', `# Selected\n\n[Local](../${target})\n`);
    if (ignored) await f.put('.gitignore', `${target}\n`);
    const result = f.run();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Documentation references an untracked repository path:/);
  });
}

test('rejects missing heading anchors on new public guides', { timeout: 30_000 }, async t => {
  const f = await fixture(t);
  await f.put('docs/en/selected-api.md', '# Selected\n\n[Missing](../selected-api.md#missing)\n');
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Missing or ambiguous heading anchor/);
});
