# Project Brief: Distraction-Free KJV Bible Reader

This is a complete rewrite of a personal King James Version Bible reading web app (original: https://readthekjv.com/).

## Core Purpose
Provide the most focused, peaceful, and reverent Bible reading experience possible in a modern web browser. The app must feel like reading from a physical open Bible — calm, spacious, and completely centered on the scripture text itself.

Primary target: desktop/laptop screens. Mobile support is a bonus, not required.

## Key Principles
- Absolute minimalism: zero clutter, zero distractions
- No horizontal or vertical scrolling ever in the reading area
- The scripture text is the sole focus — everything else serves it silently
- UX should evoke turning pages in a traditional printed Bible

## Core UX Requirements

1. **Two-Column Classic Bible Layout**
    - Exactly two columns using CSS Multi-Column Layout
    - Verses flow down column 1 first, then down column 2
    - Chapter title spans both columns at top, larger centered font
    - Verse numbers bold and slightly larger, inline before text
    - Each verse as its own paragraph/block with generous spacing

2. **Dynamic Viewport-Fitted Pages**
    - Dynamically load exactly the consecutive verses that perfectly fit the current viewport height
    - No scrolling — users "turn the page" instead
    - On navigation to a specific verse, center it near the top of the page

3. **Current Verse & Navigation**
    - Always track a "current verse" with subtle highlight (soft background or left margin bar)
    - Persist current verse in LocalStorage — resume on return
    - Keyboard navigation:
        - j / ↓ : next verse
        - k / ↑ : previous verse
        - l / → : next page
        - h / ← : previous page
        - , / . : previous/next chapter
        - < / > : previous/next book
        - / : focus search bar
        - Esc : blur search bar
    - Verse navigation moves highlight only — page turns only at boundaries

4. **Smart Search Bar**
    - Submit on Enter only
    - Detect Bible references (e.g., "john 3:16", "ps 23", "gen1") → jump directly and center verse
    - Non-reference or quoted text → full-text Lucene search → show results overlay → click to jump

5. **Controls**
    - Minimal header: cascading Book → Chapter → Verse dropdowns
    - Font size adjustment (+ / –)
    - Search bar (top right)
    - Optional hidden menu for controls/help

6. **URL Support**
    - ?vid=N → open centered on that verse (Gen 1:1 = vid 1)

## Tech Stack
- Frontend: Pure vanilla HTML5, CSS3, JavaScript (ES6+)
- Backend: Java 21 + Spring Boot 3 (minimal) + in-memory Apache Lucene
- Fonts: Google Fonts — high-legibility serif (EB Garamond preferred)
- KJV data: Full public-domain text loaded at startup

## Non-Negotiables
- No scrolling of any kind
- Perfect two-column top-to-bottom flow
- Distraction-free, reverent presentation
- Seamless page-turning feel with keyboard

This app exists to honor the text by removing every possible barrier between the reader and the Word.