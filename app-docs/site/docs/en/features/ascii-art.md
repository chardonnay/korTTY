---
title: ASCII art banner
---

# ASCII art banner

Generate ASCII art text banners using FIGlet fonts with multiple stylistic options. The ASCII Art Banner dialog lets you preview your text in different styles and copy the result to your clipboard for use in terminal scripts, headers, or documentation.

## Accessing the tool

Open the ASCII art banner generator from **Tools > ASCII Art Banner** in the menu bar.

## Using the dialog

The dialog contains four main sections:

### Style selection

Choose from 11+ available FIGlet font styles using the dropdown menu:

- **Standard** — Classic block-style letters (default jfiglet style)
- **Slant** — Italicized block letters
- **3-D**, **banner**, **big**, **block**, **cosmic**, **Digital**, **lean**, **roman**, **script**, **small** — Additional bundled styles for varied visual effects

Navigate between styles using:

- The dropdown menu directly
- Arrow keys (++left++, ++right++, ++up++, ++down++) when the combo box has focus
- The navigation buttons (◀ and ▶) beside the dropdown

### Input

Type or paste the text you want to convert in the **Input** field. Multi-line text is supported — each line is converted separately.

### Preview

The **Output** area displays your text in real-time as you:

- Change the selected font style
- Type or edit your input text

The output uses a monospace font for accurate ASCII art rendering.

### Copying to clipboard

Click **Copy to Clipboard** to copy the generated ASCII art to your system clipboard. You can then paste it into terminal scripts, documentation, config files, or any other text context.

## Example

With the "banner" style and input text "Hello":

```
 _   _                 
| | | | ___  _ __ ___ 
| |_| |/ _ \| '__/ _ \
|  _  | (_) | | | (_) |
|_| |_|\___/|_|  \___/ 
                       
```

## Dialog state

The ASCII Art Banner dialog remembers its window position, size, and selected font style between sessions.

![ASCII Art banner generator](../assets/screenshots/tools/ascii-art.png)
