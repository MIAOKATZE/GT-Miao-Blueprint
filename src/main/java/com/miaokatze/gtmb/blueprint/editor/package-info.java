/**
 * Client-authoritative, source-preserving blueprint authoring primitives.
 *
 * <p>
 * The source JSON fields owned by {@link com.miaokatze.gtmb.blueprint.editor.AuthorDocument} are the
 * sole writable truth. Source containers never escape without defensive copying. Geometry commands stage
 * fixed-size copy-on-write cell pages and persistent position/group indexes; unchanged source records and
 * exact encoded NBT strings are shared. No class in this package imports Minecraft, a registry, a server
 * session, a network codec, or a native NBT parser.
 * </p>
 *
 * <p>
 * Raw admission proves UTF-8/JSON syntax and Unicode scalar text (isolated surrogates are rejected), unambiguous names,
 * finite structural budgets, integer document
 * coordinates and membership integrity. It deliberately does not certify native NBT, assets, placement,
 * ownership, materials, or portability. Compact envelopes and schema-1 controller conversion enter through
 * explicit legacy adapters. Unknown source JSON is retained; unknown draft-envelope versions/fields fail
 * closed rather than being dropped. Native payload text is opaque until a separately budgeted native
 * consumer explicitly requests it.
 * </p>
 *
 * <p>
 * A version-1 {@code gtmb-author-draft} is a recovery document, not a schema-2 portable artifact. It stores
 * stable document/cell identity, exact source JSON, per-cell payload coordinate origins, and ordered pending
 * obligations. Geometry is already applied to source coordinates; origins identify the coordinate basis of
 * unchanged payloads, including copies of previously moved cells and frozen clipboard content. Only the
 * explicit native/portable resolver may commit {@code replaceResolvedSource}; ordinary source edits and
 * history never silently discharge these obligations.
 * </p>
 *
 * <p>
 * Every editor command publishes once or not at all. Undo/redo publish fresh monotonically increasing
 * revisions, restore selection and provenance, and never recycle abandoned cell IDs. History is bounded by
 * count and a conservative retained-source byte budget. Source occurrence bytes/nodes, draft provenance,
 * source depth, cell count and coordinate range are bounded independently of representation sharing.
 * Raw admission reserves the same identity/provenance overhead as edit commits, so an admitted source
 * can immediately be saved under the same limits; near-limit bare sources are rejected before editing.
 * Nonspatial annotations and selected payload edits preserve their coordinate basis without fabricating
 * new spatial obligations. Expanded import performs one bounded defensive container copy with shared immutable
 * scalar/payload values
 * and in-pass byte/node/depth accounting, not encode/reparse. Draft output streams the owned source without
 * constructing another full JSON tree. Import/save/explicit whole-source processing may traverse the document; a
 * selected-cell edit does not
 * rebuild a DTO, reconcile a full capture, materialize a snapshot, or scan unchanged cell pages.
 * </p>
 */
package com.miaokatze.gtmb.blueprint.editor;
