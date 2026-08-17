---
title: ASCII art
---

# ASCII art

Create ASCII art in two ways: render text as a FIGlet banner, or let the AI draw a picture from a subject word such as "house". Both sit in one dialog behind their own tab, share a zoomable preview, and copy to the clipboard for use in terminal scripts, login banners, or documentation.

## Accessing the tool

Open the dialog from **Tools > ASCII Art...** in the menu bar, or press ++ctrl+shift+a++ (++cmd+shift+a++ on macOS).

## Text Banner tab

### Style

Choose a FIGlet font style from the dropdown. **Standard** and **Slant** come from the jfiglet library; **3-D**, **banner**, **big**, **block**, **cosmic**, **Digital**, **Lean**, **roman**, **script** and **small** are bundled FIGfonts. A style whose font file cannot be loaded is left out of the list.

Switch between styles with the dropdown, with the arrow keys (++left++, ++right++, ++up++, ++down++) while the dropdown has focus, or with the ◀ and ▶ buttons beside it.

### Text

Type or paste the text to convert into the **Text** field. Multi-line input is supported — each line is converted on its own, and blank lines stay blank.

The **Preview** re-renders as you type and whenever you change the style.

## AI Picture tab

Instead of lettering, this tab asks a model to draw the subject as a picture.

| Control | What it does |
| --- | --- |
| **Subject** | The thing to draw, for example `house`. Pressing ++enter++ starts the generation. |
| **Generate** | Requests a picture for the subject. |
| **AI profile** | Which profile handles this run. The choice is transient and does not change your default profile. |
| **New variation** | Redraws the same subject with a different treatment — viewing angle, level of detail, line style, scene context or proportions — asking for something different again on each retry. |

The tab needs at least one configured AI profile and the AI features switch enabled; otherwise its controls stay disabled and the status line says so. Errors and "no usable picture" replies are reported in the same status line.

!!! note
    The model is asked for printable ASCII only, at most 60 characters per line and 30 lines tall, so a result stays readable in the preview. Replies are cleaned before they are shown: a fenced code block is unwrapped, reasoning blocks and control characters are removed, tabs become spaces, and blank leading and trailing lines are trimmed.

## Preview zoom

Both tabs share one zoom level, so a banner and a picture are always shown at the same size.

| Action | Controls |
| --- | --- |
| Enlarge | **+** button, ++ctrl+plus++, or ++ctrl++ and scroll up over the preview |
| Shrink | **−** button, ++ctrl+minus++, or ++ctrl++ and scroll down over the preview |
| Reset | **⟲** button or ++ctrl+0++ |

The percentage between the buttons shows the current level, from 50 % to 333 %, where 100 % is the default 12 px monospace size.

## Copying to clipboard

**Copy to Clipboard** copies the preview of the tab that is currently open, so you get the banner from the Text Banner tab and the picture from the AI Picture tab.

## Example

The **banner** style with the input text "Nostromo":

```
 #     #                                                 
 ##    #  ####   ####  ##### #####   ####  #    #  ####  
 # #   # #    # #        #   #    # #    # ##  ## #    # 
 #  #  # #    #  ####    #   #    # #    # # ## # #    # 
 #   # # #    #      #   #   #####  #    # #    # #    # 
 #    ## #    # #    #   #   #   #  #    # #    # #    # 
 #     #  ####   ####    #   #    #  ####  #    #  ####  
                                                         
```

## Dialog state

The dialog remembers its window position, its size, and the preview zoom level between sessions.

![ASCII art generator](../assets/screenshots/tools/ascii-art.png)
