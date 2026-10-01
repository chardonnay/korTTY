package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.nio.file.attribute.PosixFilePermissions;

import static com.google.common.truth.Truth.assertThat;

class SnippetExecutableSupportTest {

    @Test
    void knownScriptExtensionsAreExecutableByDefault() {
        for (String name : new String[] {"a.sh", "b.py", "c.pl", "d.rb", "e.bash", "f.zsh", "g.groovy", "h.ps1"}) {
            assertThat(SnippetExecutableSupport.defaultExecutable(name, "x")).isTrue();
        }
        for (String name : new String[] {"a.txt", "b.json", "c.pm", "d.yml", "e.md", "noext"}) {
            assertThat(SnippetExecutableSupport.defaultExecutable(name, "x")).isFalse();
        }
    }

    @Test
    void aShebangMakesAnyFileExecutable() {
        assertThat(SnippetExecutableSupport.defaultExecutable("tool", "#!/usr/bin/env node\n")).isTrue();
        assertThat(SnippetExecutableSupport.defaultExecutable("tool.txt", "﻿#!/bin/sh\n")).isTrue();
    }

    @Test
    void theSnippetsLanguageGivesTheExtensionAndAnExplicitFlagWins() {
        Snippet python = new Snippet("tool", "print(1)", "python");
        assertThat(SnippetExecutableSupport.fileNameOf(python)).isEqualTo("tool.py");
        assertThat(SnippetExecutableSupport.isExecutable(python)).isTrue();
        python.setExecutable(Boolean.FALSE);
        assertThat(SnippetExecutableSupport.isExecutable(python)).isFalse();
        assertThat(SnippetExecutableSupport.fileMode(python)).isEqualTo(0644);

        Snippet module = new Snippet("tool/lib", "x = 1", "python");
        module.setFileName("helpers.py");
        assertThat(SnippetExecutableSupport.fileNameOf(module)).isEqualTo("helpers.py");

        Snippet notes = new Snippet("notes", "hello", "plain");
        assertThat(SnippetExecutableSupport.isExecutable(notes)).isFalse();
        notes.setExecutable(Boolean.TRUE);
        assertThat(SnippetExecutableSupport.fileMode(notes)).isEqualTo(0755);
    }

    @Test
    void modesMapToPosixPermissions() {
        assertThat(PosixFilePermissions.toString(SnippetExecutableSupport.posixPermissions(0755)))
            .isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(SnippetExecutableSupport.posixPermissions(0644)))
            .isEqualTo("rw-r--r--");
    }
}
