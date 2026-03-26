# UI Debug Console Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a standalone `ui/` React + Vite debug console that connects to the current backend, displays assistant stream timing, and plays realtime PCM audio chunks from the teacher pipeline.

**Architecture:** The UI stays as a separate Vite development server and talks directly to the Spring Boot backend over HTTP and WebSocket. The page is intentionally single-screen and optimized for observability rather than product UX, with a small PCM audio player, an event timeline, and visible TTFT/TTFA metrics.

**Tech Stack:** React, TypeScript, Vite, native fetch, native WebSocket, Web Audio API

---

## Impacted Modules

- `ui/package.json`
- `ui/tsconfig.json`
- `ui/tsconfig.node.json`
- `ui/vite.config.ts`
- `ui/index.html`
- `ui/src/main.tsx`
- `ui/src/App.tsx`
- `ui/src/styles.css`
- `ui/src/types.ts`
- `ui/src/api.ts`
- `ui/src/ws.ts`
- `ui/src/audio/pcmPlayer.ts`
- `ui/src/components/*`

## Acceptance Criteria

- `ui/` can be started as a standalone dev server.
- The page loads article list from the backend.
- Creating a session opens a WebSocket and starts receiving assistant events.
- Assistant text deltas appear incrementally.
- Audio chunks are decoded and played in the browser.
- TTFT and TTFA are visible.
- A student text box can submit the next turn to the backend.
- Event timeline clearly shows stream order and timestamps.

## Validation Approach

- Install deps in `ui/`
- Run `npm run dev`
- Open the page and verify the full happy path manually
- Confirm PCM playback starts when `assistant.audio.chunk` arrives
- Confirm TTFT and TTFA update after each turn

### Task 1: Create Vite React app skeleton

**Files:**
- Create: `ui/package.json`
- Create: `ui/tsconfig.json`
- Create: `ui/tsconfig.node.json`
- Create: `ui/vite.config.ts`
- Create: `ui/index.html`
- Create: `ui/src/main.tsx`
- Create: `ui/src/styles.css`

**Step 1:** Add the minimal React + TypeScript + Vite package scaffold.
**Step 2:** Configure the dev server to target the backend during local development.
**Step 3:** Verify the front-end project structure is runnable.

### Task 2: Define transport and event types

**Files:**
- Create: `ui/src/types.ts`
- Create: `ui/src/api.ts`
- Create: `ui/src/ws.ts`

**Step 1:** Model article, session, submit-turn, and assistant-event payloads.
**Step 2:** Add API helpers for loading articles and creating/submitting sessions.
**Step 3:** Add a WebSocket helper for session-bound assistant events.

### Task 3: Implement PCM audio playback

**Files:**
- Create: `ui/src/audio/pcmPlayer.ts`

**Step 1:** Add base64 PCM decoding.
**Step 2:** Convert `Int16` PCM to `Float32` samples.
**Step 3:** Queue audio chunks into `AudioContext` in playback order.
**Step 4:** Expose queue depth and playback state for the UI.

### Task 4: Build the debug console screen

**Files:**
- Create: `ui/src/components/ArticleList.tsx`
- Create: `ui/src/components/SessionPanel.tsx`
- Create: `ui/src/components/AssistantStreamPanel.tsx`
- Create: `ui/src/components/EventTimeline.tsx`
- Create: `ui/src/components/StudentInputPanel.tsx`
- Modify: `ui/src/App.tsx`

**Step 1:** Build a single-screen console layout.
**Step 2:** Add article selection and session creation controls.
**Step 3:** Add assistant text stream rendering and event timeline.
**Step 4:** Add a student input form for submitting the next turn.

### Task 5: Add timing metrics and diagnostics

**Files:**
- Modify: `ui/src/App.tsx`
- Modify: `ui/src/components/SessionPanel.tsx`
- Modify: `ui/src/components/EventTimeline.tsx`

**Step 1:** Track the important timestamps from session creation to turn completion.
**Step 2:** Compute and render TTFT and TTFA.
**Step 3:** Surface connection, playback, and error state visibly.

### Task 6: Validate the standalone UI

**Files:**
- Optional notes: `docs/plans/2026-03-26-ui-debug-console-design.md`

**Step 1:** Install dependencies in `ui/`.
**Step 2:** Run the Vite dev server.
**Step 3:** Manually verify article selection, session creation, text streaming, audio playback, and next-turn submission.
**Step 4:** Capture any remaining protocol mismatches between frontend expectations and current backend payloads.

## Notes

- Keep the UI intentionally narrow and diagnostic-first.
- Do not add browser-side ASR in this milestone.
- Do not optimize visuals over observability.
- Prefer real timing instrumentation over assumptions about responsiveness.