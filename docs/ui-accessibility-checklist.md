# UI Accessibility Checklist (Phase 7)

Manual checklist applied to all six workbench screens. Verified during the
Phase 7 walkthrough on Chromium; results recorded in
`docs/phase-7-verification.md`.

## Structure & semantics

- [x] One `<h1>` per screen; logical heading order (h1 → h2 → h3).
- [x] Landmarks: `<header>` for the product/case header, `<nav>` for the
      workflow navigation, `<main>` for the workspace.
- [x] Workflow navigation exposes `aria-current="page"` on the active step.
- [x] Status badges use text, not colour alone (e.g. `FAILED`, `READY FOR
      REVIEW` are labelled).

## Forms & controls

- [x] Every input/select/textarea has a programmatic `<label for=…>`.
- [x] Required fields marked visually and with `required`.
- [x] Field-level errors rendered adjacent to the control and announced in
      an error summary (`role="alert"`).
- [x] Radio groups (dispositions) wrapped in `<fieldset>` + `<legend>`.
- [x] Checkboxes (acknowledgements) individually labelled.
- [x] All controls reachable and operable by keyboard only; no keyboard
      traps.

## Focus & interaction

- [x] Visible focus indicator on all interactive elements
      (`:focus-visible` outline in `app.css`).
- [x] Focus order follows reading order; skip behaviour not needed given
      the short nav.
- [x] Buttons use real `<button>`/links use real `<a>`; no clickable divs.
- [x] Double-submit guard does not remove keyboard operability.

## Visual

- [x] Text/background contrast meets WCAG AA (checked palette in
      `app.css`: body `#1a2333` on `#f4f6f9`, badges white on dark).
- [x] Layout responsive down to ~360 px: nav collapses above content at
      the defined breakpoint; tables/lists reflow.
- [x] No information conveyed by colour alone; state shown as text too.
- [x] No auto-playing motion or timed content.

## Content & safety

- [x] Advisory "human review required" and "synthetic training data"
      notices are real text (screen-reader accessible).
- [x] Abstention/empty/error states announced as text, not hidden.
- [x] Masked identifiers carry a text "masked" label, not just bullet
      glyphs.
- [x] Untrusted text (customer statements, policy passages) escaped —
      also a correctness property verified by tests.
