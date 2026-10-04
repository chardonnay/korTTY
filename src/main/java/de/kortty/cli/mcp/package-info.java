/**
 * {@code kortty-cli mcp}: korTTY as a Model Context Protocol server over stdio.
 *
 * <p>{@link de.kortty.cli.mcp.McpStdioServer} speaks the MCP stdio transport on stdin and stdout and
 * reaches korTTY through the ordinary control API with {@code client_kind = "mcp"};
 * {@link de.kortty.cli.mcp.McpToolCatalog} is the static list of tools it offers. Like the rest of
 * {@code de.kortty.cli}, nothing here loads JavaFX or the application class, and nothing opens a
 * network listener. The app, not this package, enforces the MCP gate, the allowlist, the masking and
 * the caps.
 *
 * <p>Thread contract: everything runs on the thread that calls
 * {@link de.kortty.cli.mcp.McpStdioServer#serve}; each control connection owns its own watchdog.
 */
package de.kortty.cli.mcp;
