// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repository = path.resolve(site, '..');
const supplementalLicenses = {
  '@docsearch/css': {
    version: '3.8.2',
    file: 'third-party/docs-site-licenses/docsearch-3.8.2-LICENSE',
    sha256: '1e605499213e06118cc81abc00b32bbb33ead185d9b0e190f2662588e8806f02',
    source: 'https://github.com/algolia/docsearch/blob/v3.8.2/LICENSE'
  },
  '@iconify-json/simple-icons': {
    version: '1.2.102',
    file: 'third-party/docs-site-licenses/simple-icons-16.37.0-LICENSE.md',
    sha256: '9046848b63a5c92bff14e4accca80bd987e0623b74adf9226ce5198d312b79d5',
    source: 'https://github.com/simple-icons/simple-icons/blob/16.37.0/LICENSE.md'
  }
};

function packageDirectory(id) {
  const normalized = id.replaceAll('\\', '/').split('?')[0];
  const marker = '/node_modules/';
  const start = normalized.lastIndexOf(marker);
  if (start < 0) return;
  const parts = normalized.slice(start + marker.length).split('/');
  const name = parts[0].startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
  return normalized.slice(0, start + marker.length) + name;
}

export function thirdPartyNotices() {
  let ssr = false;
  const styles = new Set();
  return {
    name: 'export4j-third-party-notices',
    apply: 'build',
    configResolved(config) {
      ssr = Boolean(config.build.ssr);
    },
    transform(_code, id) {
      // CSS is extracted before Rollup's chunk module list is finalized.
      if (!ssr && /\.css(?:\?|$)/.test(id)) {
        const directory = packageDirectory(id);
        if (directory) styles.add(directory);
      }
    },
    async generateBundle(_options, bundle) {
      if (ssr) return;
      const directories = new Set(styles);
      for (const output of Object.values(bundle)) {
        if (output.type !== 'chunk') continue;
        for (const id of Object.keys(output.modules)) {
          const directory = packageDirectory(id);
          if (directory) directories.add(directory);
        }
      }
      // VitePress injects this icon data during rendering, outside client chunks.
      directories.add(path.join(site, 'node_modules/@iconify-json/simple-icons'));
      const lock = JSON.parse(await readFile(path.join(site, 'package-lock.json'), 'utf8'));
      const entries = [];
      for (const directory of directories) {
        const meta = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'));
        const relative = path.relative(site, directory).replaceAll('\\', '/');
        if (lock.packages[relative]?.version !== meta.version) {
          throw new Error(`Installed documentation dependency differs from lockfile: ${meta.name}`);
        }
        const files = (await readdir(directory)).filter(name =>
          /^(?:licen[sc]e|notice|copying|copyright)(?:[._-]|$)/i.test(name)).sort();
        const texts = [];
        for (const file of files) {
          texts.push(`${file}\n${await readFile(path.join(directory, file), 'utf8')}`);
        }
        const supplemental = supplementalLicenses[meta.name];
        if (supplemental) {
          if (supplemental.version !== meta.version) {
            throw new Error(`Review the supplemental license for ${meta.name} ${meta.version}`);
          }
          const bytes = await readFile(path.join(repository, supplemental.file));
          if (createHash('sha256').update(bytes).digest('hex') !== supplemental.sha256) {
            throw new Error(`Supplemental license digest mismatch: ${supplemental.file}`);
          }
          texts.push(`${supplemental.source}\n${bytes.toString('utf8')}`);
        }
        if (!texts.length) throw new Error(`Missing original license for browser dependency: ${meta.name}`);
        entries.push({ name: meta.name, version: meta.version, license: meta.license, texts });
      }
      entries.sort((left, right) => left.name.localeCompare(right.name, 'en'));
      const interLicense = await readFile(path.join(repository, 'third-party/docs-site-licenses/inter-OFL.txt'));
      if (createHash('sha256').update(interLicense).digest('hex') !== '262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a') {
        throw new Error('Inter font license digest mismatch.');
      }
      const source = [
        'Export4j documentation website: third-party notices',
        '',
        'Original licenses for packages included in browser bundles, extracted styles and social icons.',
        'Build and deployment tools are not distributed by this static website.',
        '',
        ...entries.map(entry => `${'='.repeat(72)}\n${entry.name} ${entry.version} (${entry.license})\n\n${entry.texts.join('\n\n')}`),
        `${'='.repeat(72)}\nInter font, distributed with VitePress (SIL OFL-1.1)\nhttps://github.com/rsms/inter\n\n${interLicense.toString('utf8')}`
      ].join('\n\n') + '\n';
      this.emitFile({ type: 'asset', fileName: 'THIRD-PARTY-NOTICES.txt', source });
    }
  };
}
