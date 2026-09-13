/**
 * {@code kortty-cli}: the second entry point, a JavaFX-free command line client for the local
 * control API.
 *
 * <p>The package exists so a shell script or a coding agent can drive korTTY without speaking
 * JSON-RPC itself. It turns one argument vector into exactly one {@code de.kortty.control} call and
 * one line of output, and it is deliberately kept at arm's length from the application: nothing here
 * references JavaFX, {@code de.kortty.Launcher} (which configures the renderer and may relaunch the
 * JVM) or {@code de.kortty.KorTTYApplication} (whose configuration directory is resolved in a static
 * initialiser). Loading any of those into a CLI process would pay a toolkit's start-up cost, and in
 * the relauncher's case could fork a second JVM, for a program whose whole job is one socket round
 * trip.
 *
 * <p>Testability is the other reason for the shape. {@link de.kortty.cli.CliArguments#parse} and
 * {@link de.kortty.cli.CliCommands#toCall} are pure functions, so the entire argument surface —
 * every flag, every selector rule, every local key-name check — is asserted without a socket, and
 * {@link de.kortty.cli.KorttyCli#run} returns the exit code instead of calling {@code System.exit},
 * so the documented codes are asserted inside the shared test JVM.
 * {@link de.kortty.cli.KorttyCli#main} is the only {@code System.exit} caller in the package and no
 * test in this repository calls it.
 *
 * <p>The bearer token is read from {@code <configDir>/control/endpoint.json} and from nowhere else.
 * There is deliberately no {@code --token} flag and no environment variable, so the token can never
 * reach {@code argv}, {@code ps} output, shell history or a CI log, and the CLI never creates,
 * chmods or deletes anything below the configuration directory.
 *
 * <p>Thread contract: every type here is used from the one thread that runs
 * {@link de.kortty.cli.KorttyCli#run}. {@link de.kortty.cli.ControlClient} additionally owns one
 * named daemon watchdog thread that closes its socket when a deadline passes; that thread touches
 * nothing else.
 */
package de.kortty.cli;
