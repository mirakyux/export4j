# PDF standards, accessibility, and offline signatures

Export4j provides separate standard-output configurations for PDF/UA-1, PDF/A-1b/2b/3b/4f, and PAdES B/T/LT/LTA. They have different input conditions and acceptance requirements. A standard identifier in configuration cannot replace independent compliance checks of the final file. See [phase 96 (Chinese)](../verification/phase-96-pdf-standards.md) for evidence.

## PDF/UA-1 accessible output

PDF/UA output consumes explicitly declared logical reading order. `ActualText`, selectable text, and coordinate sorting cannot replace a structure tree.

Existing semantic documents can first be laid out, then tagged using their source model:

```java
var result = engine.layout(document);
var display = DocumentPainter.paintAccessible(document, result.body(), "zh-CN");
var options = PdfOptions.DEFAULT.withAccessibility(PdfAccessibility.PDF_UA_1);
try (var output = Files.newOutputStream(Path.of("output/accessible.pdf"))) {
    new PdfBackend(PdfLimits.DEFAULT, options).write(display, output);
}
```
`engine` is a `DocumentEngine` configured with explicit fonts, and `document` is the corresponding source `Document`. Source model and layout must agree. Current automatic mapping covers headings, ordinary paragraphs, simple item lists, standalone inline images, text-cell tables, and their continuation pages. List markers and bodies map separately to `Lbl`/`LBody`. Images use `InlineImage` alternative text. Physical header cells have stable IDs; data cells reference corresponding headers. Repeated continuation-page headers are artifacts rather than duplicate logical content.

Block cells, mixed text/images, custom components, and other complex sources require explicit caller bindings and are rejected by automatic mapping. Sources with text or image external links are also rejected to avoid silently dropping interactions. The current PDF/UA backend does not support OBJR tag associations for interactive objects. Existing ordinary `paint`/export APIs continue producing ordinary PDFs without inferring accessibility semantics.

When using Display Lists directly, the fourth `DisplayDocument` argument is a backend-neutral `AccessibilityTree`. Each `Node` declares a role, stable ID, optional language, image alternative text, table attributes, and ordered children. `CommandRange` specifies half-open ranges using zero-based page/command indices. Nonsemantic decoration must be included in `artifacts`. The model checks:

- Every actual drawing operation belongs to one logical node or artifact; bindings cannot overlap or extend beyond pages.
- Heading levels do not skip, and lists follow `L → LI → Lbl/LBody`.
- Images have nonempty alternative text; table headers declare `Scope`, and data-cell `headers` point to actual `TH` IDs.
- Nodes, depth, and text have fixed limits; coordinates are not used to guess semantics.

The PDF backend generates `StructTreeRoot`, `ParentTree`, `IDTree`, page MCIDs, table attributes, document language, title-display preferences, and PDF/UA-1 XMP identification. It requires a nonempty document title and explicitly embedded fonts/outline text with logical text. Unembedded standard fonts are rejected. Interactive objects with OBJR tags are not currently generated, so nonempty interactive extras fail. Page-streaming sessions lack a complete logical tree and therefore cannot produce PDF/UA output.

Both a semantic tree and `PDF_UA_1` configuration are required. Omitting either fails. SVG/PNG backends also reject nonempty semantic trees to avoid silently losing them.

### Existing tagged files

```java
var limits = PdfTaggedDocument.Limits.DEFAULT;
var original = PdfTaggedDocument.inspect(Path.of("input/tagged.pdf"), limits);
var revised = PdfTaggedDocument.metadata(
        Path.of("input/tagged.pdf"), Path.of("output/tagged.pdf"),
        "修订后的文档标题", "zh-CN", limits);
```
`inspect` returns actual roles, alternative text, header IDs, and page/MCID provenance, checking structural parent-child relationships, ParentTree, unique ownership, and drawing coverage. `metadata` changes only the title and document default language while retaining page content, resources, and logical structure; output atomically replaces the target. It rejects signatures, encryption, interactive objects, OBJR, and structurally owned Form content. It does not repair or retag arbitrary external PDFs.

Structural checks and veraPDF provide automated verification. Assistive-reader heading navigation, table navigation, alternative text, and reading order require separate evidence. See [phase 96 (Chinese)](../verification/phase-96-pdf-standards.md#无障碍阅读器) for executed and unexecuted steps on specific samples.

## PDF/A archival output

`PdfArchival` requires caller-supplied RGB ICC profile bytes and a TrueType anchor font with embedding permission. It neither discovers system fonts, reads remote resources, nor automatically substitutes missing glyphs.

| Configuration | Version and level | Color and fonts | Attachments and interactions |
| --- | --- | --- | --- |
| `A1B` | PDF/A-1b, PDF 1.4 | RGB, ICC v2; embedded fonts; rejects transparent images | No attachments or interactive extras; no object/cross-reference streams |
| `A2B` | PDF/A-2b, PDF 1.7 | RGB; embedded fonts; image alpha allowed | No attachments or interactive extras |
| `A3B` | PDF/A-3b, PDF 1.7 | RGB; embedded fonts; image alpha allowed | Explicit associated files allowed; interactive extras rejected |
| `A4F` | PDF/A-4f, PDF 2.0 | RGB; embedded fonts; XMP declares the 2020 revision | At least one associated file; interactive extras rejected; no legacy Info dictionary |

The retained two-argument `new PdfArchival(iccBytes, anchorFont)` defaults to `A2B`. This API does not select PDF/A A/U levels, PDF/A-4e, or combined PDF/UA and PDF/A certification. Existing levels do not imply those capabilities.

```java
var archive = new PdfArchival(PdfArchival.Profile.A2B, rgbIccBytes, anchorFont);
var options = PdfOptions.DEFAULT.withArchival(archive);
try (var output = Files.newOutputStream(Path.of("output/archive.pdf"))) {
    new PdfBackend(PdfLimits.DEFAULT, options).write(display, output);
}
```
`AssociatedFile` explicitly provides filename, MIME type, description, `AFRelationship`, modification time, and bytes. Filenames cannot be paths. Each file is at most 16 MiB, all files total at most 32 MiB, and the maximum count is 100. Construction and access copy bytes. The backend builds EmbeddedFiles name trees and AF associations without reading contents from URIs. Encryption and archival configuration cannot be combined.

### Converting existing PDFs

`PdfArchivalConverter` rebuilds supported existing static pages as opaque RGB images, then writes the selected archival version. It does not promise editable body text or vector structures: full-text search, vector editing, tags, interactions, original attachments, and source metadata are lost. Resolution is bounded by explicit DPI. New-document metadata and attachments are supplied separately through archival configuration.

```java
var policy = ProcessingPolicy.builder()
        .feature("PDF_ARCHIVAL_RASTERIZATION",
                ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var options = PdfArchivalConverter.Options.defaults(archive, metadata);
var report = PdfArchivalConverter.convert(
        Path.of("input/source.pdf"), Path.of("output/archive.pdf"), options, policy);
```
Default `STRICT` rejects these losses. WARN/IGNORE use identical reconstruction; IGNORE still returns actual losses. Only `DEGRADE` is accepted; `PRESERVE` cannot pretend to retain structure. The current conversion subset accepts valid embedded TrueType/Type0 fonts, ordinary solid-color drawing, opaque 8-bit RGB/Gray images, and limited ordinary Forms. It rejects signatures, encryption, forms, layers, active actions, Type3, patterns, gradients, transparency groups, and unsupported source images. Incomplete source fonts fail directly without system-font substitution.

Input bytes, pages, source/target pixels, decoded bytes, operators, state depth, and output bytes are bounded. Path output atomically replaces the old target only after successful conversion, closure, and cancellation checks. Every final file still requires an independent validator; `pdfaid` metadata alone does not establish compliance.

## PAdES and offline trust

Existing `PdfSigner`/`PdfSignatureVerifier` APIs continue to provide external CMS signing and exact-byte integrity verification separately. The newer `PdfPades` handles standard levels, explicit trust, revocation, and trusted time. CMS-byte integrity is not treated as signer trust.

| Level | Actual content |
| --- | --- |
| B | `ETSI.CAdES.detached`, SHA-256, and `SigningCertificateV2` certificate binding |
| T | Adds a trusted RFC3161 timestamp over the signature value to B |
| LT | Adds DSS/VRI, certificate chains, and OCSP/CRL to T, verifying revocation for the relevant chains |
| LTA | Adds a document timestamp over exact PDF ByteRange to LT; further evidence and timestamps may be appended |

The application explicitly supplies a JDK `PrivateKey`, DER certificate chain, trust anchors, verification time, evidence-age policy, and timestamp callback:

```java
var identity = new PdfPades.Identity(signingKey, List.of(signerDer, issuerDer));
var trust = new PdfTrust.Material(List.of(rootDer), List.of(issuerDer),
        validationTime, Duration.ofDays(7), Duration.ofMinutes(1));
var evidence = new PdfTrust.Evidence(
        List.of(signerDer, issuerDer, tsaDer), ocspResponses, crls);
var options = PdfPades.Options.defaults(
        PdfPades.Level.LTA, "Document signer", "Approval", signingTime);
var report = PdfPades.sign(sourcePath, targetPath,
        options, identity, trust, evidence, timestampSource);
```
The application owns the example's key, DER bytes, time, paths, and `TimestampSource`; the library does not encode private keys. The callback receives a SHA-256 digest and returns an RFC3161 **TimeStampToken** DER value, rather than an HTTP response or TimeStampResp. Before use, the library verifies the digest, signature, TSA certificate's dedicated EKU, chain, and time. Wrong digests and tokens dated after the explicit verification time fail.

Current content signatures use RSA/ECDSA with SHA-256. RSA public keys require at least 2048 bits; EC requires at least 256 bits. Certificates and revocation evidence use supported SHA-256/384/512 RSA or ECDSA signatures. Unsupported algorithms fail without lowering security requirements.

Trust chains use only explicit trust anchors, intermediates, CMS certificates, and evidence certificates. They do not read system trust stores or access AIA, OCSP/CRL, or TSA addresses over the network. When multiple connectable candidate chains exist, each undergoes validity, key, PKIX-constraint, and required revocation checks; a completely valid chain is accepted. An expired first chain or a CA-constraint failure does not automatically exclude other valid candidates.

Each chain contains at most 16 certificates. One chain-verification operation shares 4,096 search and evidence-verification steps across candidate traversal, issuer-signature checks, and revocation-evidence checks. Cycles cannot trigger unbounded searches. Budget exhaustion throws `IOException` without returning partial trust. If all candidates fail, the first complete candidate chain's failure type is retained. With no path to an explicit trust anchor, it reports `UNTRUSTED_CHAIN`. Search and evidence checks respond synchronously to cancellation.

OCSP checks signatures, certificate IDs, authorized responders, statuses, and times. CRL supports complete direct-issuer lists, without delta/indirect CRLs. Every candidate chain matches evidence against its actual certificates and issuers. Evidence must declare `nextUpdate` and cover the relevant verification time. Valid revocation evidence takes precedence over conflicting GOOD status for the same certificate.

B without a trusted timestamp uses the caller's verification time. T/LT/LTA use verified timestamp times for historical certificate and revocation coverage. Signature-dictionary `/M` and CMS signingTime alone do not prove trusted time. Expired evidence means it fails to cover the relevant time or exceeds an explicit age budget. The current date alone cannot invalidate historical evidence protected by long-term timestamps.

### Verification, renewal, and certification permissions

```java
var verified = PdfPades.verify(sourcePath,
        PdfSignatureVerifier.Limits.DEFAULT, trust, evidence);
boolean longTermValid = verified.meets(PdfPades.Level.LT);

var renewalOptions = PdfPades.Options.defaults(
        PdfPades.Level.LTA, "Document TSA", "Evidence renewal", renewalTime);
var renewed = PdfPades.extend(sourcePath, renewedPath,
        renewalOptions, renewalTrust, renewedEvidence, timestampSource);
```
`Signature` separately reports byte integrity, trust, actually achieved level, trusted time, and content-independent failure reasons. Missing long-term material does not automatically raise the level. Unknown/revoked status, incorrect chains, invalid timestamps, and tampering are not accepted. `extend` requires an existing valid trusted signature timestamp and cannot fabricate historical time for a bare B signature. Renewal retains existing DER streams and VRI references, appending only evidence and new timestamps.

`certify` accepts `PdfSigner.CertificationPermission` for the first signature: P1 prohibits ordinary changes, P2 permits filling/signing, and P3 additionally permits annotation changes. Document timestamps have no appearance. DSS/timestamp updates and ordinary form-appearance edits are handled separately.

`PdfDocMdp.verify` compares the certified revision with current effective content: pages and inherited resources, catalog/metadata, field definitions, values/appearances, widgets, annotations, and evidence updates. Existing default font resources cannot be removed or changed. Additional fonts needed for P2/P3 filling are classified separately. Results are `NOT_CERTIFIED`, `PERMITTED`, `FORBIDDEN`, or `INDETERMINATE`. Unknown permission structures or unrecognized additions are not considered permitted. This is modification conformance, not a replacement for cryptographic verification and trust. Readers enforce their own editing restrictions; certification declarations cannot prevent arbitrary applications from rewriting bytes.

Initial PAdES signing rejects existing signatures, encryption, and active content. Renewal-evidence paths separately verify existing signatures. Ordinary PDF editing APIs continue to identify signature impact during preflight and reject inputs without a verified preservation path; loss policies cannot bypass this. Path signing and renewal have finite input/output/evidence budgets, synchronous cancellation, and old-target protection on failure. The signing library is not a malicious-PDF parsing sandbox.
