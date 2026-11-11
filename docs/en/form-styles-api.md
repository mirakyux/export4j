# Editable Form Style API

`FormStyle` provides reusable explicit appearance values for PDF editable text fields, dropdowns, checkboxes and radio groups. It belongs to the backend-neutral graphics API; `Navigation` still defines field targets, current values and font resources. This capability is independent of body [static forms](static-forms-api.md); static check graphics are not editable controls.

## Defining and Reusing Styles

~~~java
var ink = new RgbColor(.1, .2, .6);
var visual = FormStyle.builder()
        .color(ink)
        .background(new RgbColor(.9, .95, 1))
        .border(1, ink)
        .build();
var textStyle = visual.toBuilder().fontSize(13)
        .alignment(FormStyle.Alignment.CENTER).build();

var name = Navigation.TextField.builder()
        .target(target).name("reviewer").value("初始姓名")
        .fonts(List.of(chineseFont.data()))
        .style(textStyle).build();
var accepted = Navigation.CheckBox.builder()
        .target(checkTarget).name("accepted").checked(true)
        .style(visual).build();
~~~

All four field Builders provide `.style(FormStyle)` and synchronous `.style(style -> ...)`. Once the callback returns, its immutable result is saved without retaining the child Builder. Legacy constructors still use `FormStyle.DEFAULT`. Complete constructors accept style after the old parameters. Layout passes it unchanged into `DocumentExtras`, without depending on PDFBox types.

Legacy constructor calls and Builders remain. The four Navigation/DocumentExtras record types gain components, requiring changes to record deconstruction and reflection calls. equals, hashCode and toString also include style; overall binary compatibility is not claimed.

## Explicit Values and Default Compatibility

An empty Builder and `FormStyle.DEFAULT` mean no explicit overrides and retain each field type's original appearance. Original text-field white backgrounds/light thin borders, original dropdown behavior and original selection boxes/check polylines are not unified into another default skin.

`fontSize`, colors, background, border and alignment each record explicit intent. Black, transparent and zero-width borders are explicit values too; whether a value equals a default does not determine whether it was set. `toBuilder()` retains these choices and modifies a new value without rewriting built fields.

| Method | Behavior |
| --- | --- |
| `.fontSize(pt)` | Positive points; fixed text-field/dropdown font size; does not use 0 for automatic sizing |
| `.color(rgb)` | Text-field/dropdown text color; checkbox/radio check-polyline color |
| `.background(rgb)` | Opaque RGB background |
| `.noBackground()` | Draws no background, revealing existing page content underneath; this is not translucent alpha |
| `.border(widthPt, rgb)` | Fixed nonnegative border width; explicit 0 draws no border |
| `.adaptiveBorder(maxPt, rgb)` | Width is `min(maxPt, min(widgetWidth, widgetHeight) / 10)`, calculated per widget |
| `.noBorder()` | Disables the border while retaining the current check mark |
| `.alignment(LEFT/CENTER/RIGHT)` | Horizontal text-field/dropdown alignment |

Checkboxes and radio groups have no adjustable text font size or alignment. Explicit `fontSize` or `alignment` on them is rejected at the field entry point instead of being silently ignored. Reuse a base style containing only visual properties first, then derive font size/alignment for text controls.

Dimensions cannot be NaN/infinite. The minimum positive font size, positive border width and mark stroke width representable by the PDF backend is 0.0001 pt. Smaller values report `INVALID_GEOMETRY`; adaptive widths must meet the same bound after calculation for the actual widget. Explicit zero borders are valid, draw no border and are not written as PDF hairlines. Fixed borders do not automatically shrink to fit undersized controls; use adaptive borders or larger target rectangles.

## Fonts, States and Positions

An empty font list continues to use ASCII Helvetica. Unicode text fields and choice fields still require one explicit TrueType font, embedded in full so users can save characters beyond the initial value. Styles neither load fonts nor change missing-glyph policy, and initial missing glyphs remain forbidden. Fixed font sizes do not shrink automatically. Field text is currently not measured for complete fit, so excessively long or tall content may be clipped by its appearance. Callers must choose sufficiently large controls and inspect actual output.

Field current value `/V` retains original text; appearance-text extraction is affected by same-glyph mappings in the embedded font. In the sample LXGW font, “值” (U+503C) and “値” (U+5024) map to one GID. PDFBox's generated full-font `ToUnicode` can extract the former as the latter. Independent verification retains this actual extraction result and checks source-font cmap, original appearance CIDs and GIDs character by character. This does not mean PDF-form search/copy can reproduce every Unicode source character exactly.

`BlockTarget` positions relative to the source block's first actual fragment, with final pages including the contents offset. `PageTarget` directly uses final page indices. Style changes only internal control drawing; it does not decide body line breaks or pagination again.

Checkbox/radio Off and each option appearance all apply background, border and mark color. `noBorder()` does not erase the check polyline. Field trees, page widgets, current/default values and selected AP states must agree. Defaults remain editable without requiring readers to rebuild appearances temporarily.

## Example and Independent Verification

[FormStyleExample](../../examples/src/main/java/com/mirakyux/export4j/examples/FormStyleExample.java) uses two reusable visual styles to generate three pages: contents, ASCII controls, and full Unicode fonts with default-compatibility comparisons. It includes explicit black, page backgrounds visible through transparent controls, fixed zero-width borders, dropdowns, checkboxes/radio buttons, small controls, adaptive borders and side-by-side comparisons of four legacy defaults against explicitly empty styles.

~~~powershell
.\gradlew.bat :examples:formStyles
build/verification/python-phase13/Scripts/python.exe scripts/verify_form_styles.py
~~~

Independent verification reads field trees, widgets, all AP states, `DA`/`Q`/`MK`/`BS`, checking fixed font sizes/colors, complete embedded fonts, actual border/background drawing, final contents offsets and check marks. Default comparisons inspect actual dictionaries and all appearances rather than whole PDF bytes. The isolated consumer reads the successful-build directory pointer; `--consumer-build-directory` can also be supplied.

The script modifies fields with pypdf, saves and reopens them, then checks current values and all styles. Text-appearance updates are limited to this small verification sample: background, border and clipping segments from the original AP remain, and text is repositioned using original DA/Q and embedded fonts. This is independent automated form-filling verification, not manual reader filling or proof that every reader retains these styles when regenerating appearances. All original and saved pages are rendered with Poppler and PDFium and visually inspected page by page, without updating visual baselines.

This capability adds no button actions, JavaScript, password input, rich-text input, arbitrary border shapes or PDF/UA. SVG/Raster still reject editable fields; DOCX does not turn PDF AcroForm into Word content controls.
