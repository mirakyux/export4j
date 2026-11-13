# Public documentation website

The website is [export4j.mirakyux.com](https://export4j.mirakyux.com), generated as static HTML with VitePress and hosted on Cloudflare Workers Static Assets. The Java engine, designer application, and Maven artifacts do not depend on it.

## Content and local preview

Use Node.js 22.12 or later. Run from the repository root in PowerShell or a common POSIX shell:

```shell
npm --prefix site ci
npm --prefix site run dev
```

Open the local address printed by the terminal. English home is `/`, Chinese home is `/zh/`; English guides use `/docs/`, Chinese guides `/zh/docs/`. Navigation, sidebar, search, and theme text follow the page language. The language menu switches between corresponding home, getting-started, FAQ, and primary-sidebar pages. For Chinese-only historical records or advanced references, its English option explicitly identifies the English documentation index as the destination. Body content does not repeat the language control.

- `docs/*.md` maintains Chinese guides; `docs/en/` maintains English translations. `site/.vitepress/navigation.mjs` defines both sidebars and page pairs. New primary-sidebar pages require both languages; missing translations fail the build. Preparation copies only Git-tracked documentation and explicitly listed new public guides, excluding local research.
- Both root READMEs generate their getting-started pages. Source, script, and font-record links point to GitHub; guide links stay on the website.
- `site/pages/index.md`, `site/pages/zh/index.md`, and `site/.vitepress/theme/` maintain the home pages/theme.
- Generated `site/pages/docs/`, `site/pages/zh/docs/`, and both `getting-started.md` files are not committed. After source edits, rerun `npm --prefix site run prepare:content`. Translated guides use `/docs/*`; Chinese-only old addresses receive per-page 301 redirects from generated `_redirects`. English links to untranslated references explicitly identify Chinese content.
- See [showcase provenance (Chinese)](../../site/pages/public/showcase/README.md) for samples and sources. Local output/screenshots do not replace Java output acceptance.

Keep dead-link checking enabled; do not hide missing pages by disabling `ignoreDeadLinks`.

Builds collect original third-party licenses from actual browser modules, styles, and icons into root `THIRD-PARTY-NOTICES.txt`. Missing licenses, installed versions differing from the lockfile, or mismatched supplemental-source hashes fail the build. The theme bundles no fonts. Dependency updates must inspect generated notices and [supplemental licenses (Chinese)](../../third-party/docs-site-licenses/README.md), not merely retain license names.

## Build and Cloudflare deployment

`DOCS_SITE_URL` is the HTTPS origin without a path, query, or fragment, defaulting to `https://export4j.mirakyux.com`. It controls canonical URLs, translation alternates, sitemap, and `robots.txt`. Override it for independent preview domains; production retains the default origin.

PowerShell:

```powershell
$env:DOCS_SITE_URL = 'https://export4j.mirakyux.com'
npm --prefix site run build
npm --prefix site run deploy:check
```

Linux/macOS:

```sh
DOCS_SITE_URL=https://export4j.mirakyux.com npm --prefix site run build
npm --prefix site run deploy:check
```

After Cloudflare login:

```shell
npm --prefix site exec -- wrangler login
npm --prefix site run deploy
```

The target is `export4j-docs` in `site/wrangler.jsonc`, serving `site/.vitepress/dist/`. No Worker runtime script, database, or server-side secret is needed. Unknown paths return a real 404 without SPA fallback. `export4j.mirakyux.com` is configured through Worker Custom Domains, with DNS/certificates managed by Cloudflare during deployment. Recheck DNS, TLS, and actual HTTP responses afterward. Do not store access tokens in the repository.

## Publication verification

1. Install from the lockfile and build production output, checking compiler/dead-link errors.
2. Use `npm --prefix site run preview` and operate the browser: English home → Guides, confirming English body/sidebar; switch the same guide to Chinese and back; open the language menu and inspect appearance, outside click, Escape, arrows, Tab, and focus; check mobile menus, overflow, search, and dark mode. Static link checks do not replace these interactions.
3. Run `deploy:check`, confirming a static-only upload.
4. After deployment, check home, deep guides, sample PDFs, unknown-path 404, canonical URLs, `sitemap.xml`, `robots.txt`, and `THIRD-PARTY-NOTICES.txt`.
5. Once the domain is confirmed, update GitHub About's Website and both README links. Website publication and search-engine indexing are verified separately.

Static builds include body content in HTML, so crawling does not require client JavaScript. Metadata must match the actual content. No search ranking or AI citation is promised. The site itself loads no analytics or remote fonts by default.

Root `llms.txt` summarizes getting started, APIs, format limits, and version information for tools supporting that convention. It does not replace body content, sitemaps, or indexing checks.

Configuration references: [VitePress](https://vuejs.github.io/vitepress/v1/), [Workers Static Assets](https://developers.cloudflare.com/workers/static-assets/), [static routing](https://developers.cloudflare.com/workers/static-assets/routing/static-site-generation/), and [Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/).
