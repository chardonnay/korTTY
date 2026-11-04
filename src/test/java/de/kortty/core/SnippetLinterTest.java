package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetLinterTest {

    @Test
    void luacErrorsBecomeLineMessages() {
        assertThat(SnippetLinter.luacErrors("luac: stdin:2: unexpected symbol near 'if'\n"))
            .containsExactly("Line 2: unexpected symbol near 'if'");
        assertThat(SnippetLinter.luacErrors("/opt/homebrew/bin/luac5.4: stdin:7: 'end' expected (to close 'function' at line 3) near <eof>"))
            .containsExactly("Line 7: 'end' expected (to close 'function' at line 3) near <eof>");
        assertThat(SnippetLinter.luacErrors("something else went wrong")).containsExactly("something else went wrong");
        assertThat(SnippetLinter.luacErrors("")).containsExactly("luac reported a syntax error");
    }

    @Test
    void luaIsCheckedWithAnInstalledLuac() {
        if (SnippetLinter.luac().isEmpty()) {
            assertThat(SnippetLinter.isSupported("lua")).isFalse();
            throw new SkipException("luac is not installed");
        }
        assertThat(SnippetLinter.isSupported("lua")).isTrue();

        assertThat(SnippetLinter.lint("local x = 1\nprint(x)\n", "lua").isSuccess()).isTrue();
        SnippetLinter.LintResult broken = SnippetLinter.lint("local function f()\n  return 1\n", "lua");
        assertThat(broken.isSuccess()).isFalse();
        assertThat(broken.getMessage()).startsWith("Line 3: 'end' expected");
    }

    @Test
    void jsonAndXmlStayBuiltIn() {
        assertThat(SnippetLinter.isSupported("json")).isTrue();
        assertThat(SnippetLinter.isSupported("xml")).isTrue();
        assertThat(SnippetLinter.isSupported("python")).isFalse();
        assertThat(SnippetLinter.lint("{\"a\": 1}", "json").isSuccess()).isTrue();
    }
}
