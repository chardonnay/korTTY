---
kortty-ai-skill: 1
kortty-builtin-id: builtin.action.ascii-art-svg
kortty-builtin-version: 1
kortty-builtin-topics: [ascii-art, svg]
name: "ASCII Picture Composition"
description: "Composition rules and one worked example for the SVG drawings korTTY converts into ASCII art."
tags: [ascii-art, svg, picture]
enabled: true
target: CHAT
---
# ASCII Picture Composition

Compose the drawing so that it still reads as the subject after every shape becomes a few characters.

- Build the subject from 3 to 6 large silhouette parts that already read as the subject on their own; add details only after the silhouette is complete.
- Let the subject span at least 60 of the 100 units in both directions. A small drawing turns into a handful of characters nobody can recognise.
- Draw layers from back to front: ground or water first, then the main body, then the details on top, because every later shape covers the earlier ones.
- Never cover the whole canvas with a background rectangle. The paper stays white; use #aaa for a ground or water band only where the subject needs one.
- Every detail must be at least 8 by 8 units — a window, an eye, a wheel. Anything smaller vanishes in the character grid.
- Draw a scene only when the subject names one, such as "a house in the forest". A single object stands alone on white.
- Prefer rect, polygon (triangles) and circle over path; use path only for a curve the basic shapes cannot express, and give it stroke-width 3.
- Choose the most recognisable viewpoint silently — side view for vehicles and animals, front view for buildings and faces — and never explain the choice.

Example for the subject "lighthouse by the sea" (11 shapes):

```svg
<svg viewBox="0 0 100 100">
  <rect x="0" y="78" width="100" height="22" fill="#aaa"/>
  <polygon points="30,80 70,80 62,70 38,70" fill="#555"/>
  <polygon points="40,70 60,70 56,30 44,30" fill="#555"/>
  <rect x="43" y="48" width="14" height="8" fill="black"/>
  <rect x="38" y="27" width="24" height="6" fill="black"/>
  <rect x="42" y="13" width="16" height="14" fill="black"/>
  <rect x="46" y="16" width="8" height="8" fill="white"/>
  <polygon points="40,13 60,13 50,4" fill="black"/>
  <line x1="42" y1="20" x2="8" y2="12" stroke="#aaa" stroke-width="3"/>
  <line x1="58" y1="20" x2="92" y2="12" stroke="#aaa" stroke-width="3"/>
  <path d="M 4 86 Q 12 80 20 86 T 36 86 T 52 86 T 68 86 T 84 86 T 100 86" fill="none" stroke="black" stroke-width="3"/>
</svg>
```

Draw the requested subject, never this example.
