---
title: Serial Connections
---

# Serial Connections

A **Serial** connection opens a terminal on a serial port of this computer instead of a network host, like PuTTY's serial sessions: the console port of a router or switch, a microcontroller board, a modem or any device behind a USB-to-serial adapter. It is selectable as a protocol in both **Quick Connect** and the **Connection Manager** on Windows, macOS and Linux.

There is no login and no password prompt: what you type goes out on the line, and what the device sends is shown in the tab. Host, port, username and authentication are not used and are disabled in the dialogs.

## Settings

Choose **Serial** as the protocol, then fill in the serial rows below it.

| Setting | Description |
| --- | --- |
| **Serial port:** | The port to open: `COM3` on Windows, `/dev/ttyUSB0` or `/dev/ttyACM0` on Linux, `/dev/cu.usbserial-…` on macOS. Pick it from the list or type it; a port that is not plugged in yet can be typed too. See [Finding the right port](#finding-the-right-port). |
| **Speed (baud):** | 300 to 921600 from the list, or any other rate typed in (up to 20000000). Default 9600. |
| **Data bits / parity / stop bits:** | 5–8 data bits; **No parity**, **Odd**, **Even**, **Mark** or **Space**; **1 stop bit**, **1.5 stop bits** or **2 stop bits**. Default 8 data bits, no parity, 1 stop bit (8N1). |
| **Flow control:** | **None** (default), **RTS/CTS (hardware)**, **XON/XOFF (software)** or **DSR/DTR**. |
| **Enter sends:** | **CR** (default), **LF** or **CR LF**: what the ++enter++ key is sent as, for devices that expect a line feed. Other keys are sent unchanged. |
| **Line options:** | **Raise DTR on connect** and **Raise RTS on connect** (both on, as in PuTTY): some boards, Arduino among them, reset when DTR rises, so turn it off to keep them running. A flow-control mode that uses a line keeps it to itself, and its box is disabled. **Local echo** shows what you type yourself, for devices that do not echo it back. |

The settings are stored with the connection and survive duplicating, exporting and importing; picking a saved serial connection in Quick Connect shows them in the form. A connection without a name is called after its port and speed, for example `COM3 @ 115200`.

**Character encoding** in the connection editor also applies to serial connections: **Use default** follows *Settings → Terminal → Encoding*; many devices send plain ASCII or ISO-8859-1.

## Finding the right port

The list under **Serial port:** shows every port of this computer with its driver's description, for example `FT232R USB UART (/dev/cu.usbserial-A50285BI)`, and below it what the USB adapter reports about itself: its manufacturer, its serial number (**S/N**) and its USB vendor and product id (**USB 0403:6001**); pointing at a port shows the USB location it is plugged into. The serial number tells two adapters of the same kind apart. On macOS each port appears once, as its `cu.` device, which opens without waiting for a carrier signal.

While the dialog is open and **Serial** is selected, korTTY looks for ports again every two seconds. A port that appears after the dialog opened is marked **New**, and when no port is entered yet it is picked right away, so the simplest way to find an adapter is to plug it in with the dialog open. **Refresh** looks again at once.

## Working in a serial tab

**Send Break** holds the line in the break condition for a quarter second, which drops a Cisco device into ROMMON or sends a Linux console's SysRq. It is in the terminal's context menu of a serial pane and in **Connections → Send Break** for the focused pane. The menu item has no key by default; give it one in *Settings → Keyboard*.

A serial line has no window size, so resizing the tab tells the device nothing. When the adapter is unplugged or its driver gives up, the tab stays open with the message *Serial port … disconnected* and the reconnect offer, as for a dropped network connection.

Closing a serial tab asks *Close the serial port?* unless the connection may close without confirmation.

In the Connection Manager a serial connection shows a plug symbol, its port and line settings (`COM3 · 9600 8N1`), and **Serial port** and **Line** in the inspector. SFTP, tunnels, the jump server, the host-key check, the AI Swarm and scheduled headless runs need a network host and are not offered for serial connections.

## Import

Serial sessions in a **MobaXterm** session file (`.mxtsessions`, `MobaXterm.ini`) and a **PuTTY Connection Manager** CSV export are imported as serial connections with their port and speed; the other line settings start at 8N1 without flow control. From MobaXterm, a Windows port shown as `COM3 (USB Serial Port (COM3))` becomes `COM3`.

## Permissions and troubleshooting

| Message | What to do |
| --- | --- |
| *Serial port … was not found.* | Plug in the adapter with the dialog open and pick the port marked **New**; USB adapters can get a different name after re-plugging. On Windows the Device Manager lists the port under *Ports (COM & LPT)*. |
| *Serial port … is in use by another program.* | Close the other terminal program or tab that has the port open. korTTY opens a port exclusively. |
| *You may not open serial port ….* | On Linux, add your user to the `dialout` group (`uucp` on Arch Linux) and log in again. |
| *… rejected the line settings.* | The adapter does not support the speed; pick a standard rate. |

The Flatpak package has device access (`--device=all`), so serial ports under `/dev` are visible inside the sandbox; the `dialout` group membership on the host still applies.

## Enterprise policy

An organization can forbid serial connections with `serial-connections = "deny"` in its [enterprise policy](../reference/enterprise-policy.md#rulefeatures). The connection editor and Quick Connect then no longer offer **Serial** for new connections, and opening a saved or imported serial connection is refused with *Your organization's policy does not allow serial connections.* The server allowlist of the policy does not apply to serial ports, as they have no host.

!!! note
    A serial session runs inside korTTY itself. It cannot be [isolated](session-isolation.md) in a process or sandbox of its own; when your organization requires isolation, korTTY does not open the port.
