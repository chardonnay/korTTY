package de.kortty.core;

import de.kortty.core.SnippetStructureScanner.Analysis;
import de.kortty.core.SnippetStructureScanner.Container;
import de.kortty.core.SnippetStructureScanner.OutlineNode;
import de.kortty.core.SnippetStructureScanner.Status;
import de.kortty.core.SnippetStructureScanner.Variable;
import de.kortty.core.SnippetStructureScanner.VariableKind;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetStructureScannerTest {

    // ---------------------------------------------------------------- shell

    @Test
    void bashListsFunctionsAndScopesLocalsToThem() {
        String script = """
            #!/bin/bash
            readonly BASE_DIR=/opt/app
            export TARGET_HOST=web01
            declare -A config=( [a]=1 )
            hosts=(a b c)
            count=0
            deploy() {
              local target="$1"
              local -a parts
              declare -g FLAG=1
              status=ok
              echo "${target} $count"
            }
            function cleanup {
              rm -f "$tmp"
            }
            for host in "${hosts[@]}"; do deploy "$host"; done
            """;

        Analysis analysis = SnippetStructureScanner.analyze("bash", script);

        assertThat(analysis.status()).isEqualTo(Status.OK);
        assertThat(outline(analysis)).containsExactly("FUNCTION deploy 7-13", "FUNCTION cleanup 14-16").inOrder();
        assertThat(describe(variable(analysis, "BASE_DIR", ""))).isEqualTo("CONSTANT SCALAR @2");
        assertThat(describe(variable(analysis, "TARGET_HOST", ""))).isEqualTo("EXPORTED SCALAR @3");
        assertThat(describe(variable(analysis, "config", ""))).isEqualTo("HASH HASH @4");
        assertThat(describe(variable(analysis, "hosts", ""))).isEqualTo("ARRAY ARRAY @5");
        assertThat(describe(variable(analysis, "target", "deploy"))).isEqualTo("VARIABLE SCALAR @8");
        assertThat(describe(variable(analysis, "parts", "deploy"))).isEqualTo("ARRAY ARRAY @9");
        // declare -g and a plain assignment inside a function set global variables in bash.
        assertThat(describe(variable(analysis, "FLAG", ""))).isEqualTo("VARIABLE SCALAR @10");
        assertThat(describe(variable(analysis, "status", ""))).isEqualTo("VARIABLE SCALAR @11");
        assertThat(variable(analysis, "target", "deploy").references()).isEqualTo(1);
        assertThat(variable(analysis, "count", "").references()).isEqualTo(1);
        assertThat(variable(analysis, "hosts", "").references()).isEqualTo(1);
        assertThat(variable(analysis, "host", "").references()).isEqualTo(1);
    }

    @Test
    void bashIgnoresCommentsHeredocBodiesAndSingleQuotedText() {
        String script = """
            # fake() { not a function }
            cat <<EOF > /etc/app.conf
            fake_two() {
            name=value
            $count
            EOF
            echo '$count'
            count=1
            echo "$count"
            """;

        Analysis analysis = SnippetStructureScanner.analyze("bash", script);

        assertThat(analysis.outline()).isEmpty();
        assertThat(names(analysis)).containsExactly("count");
        // The unquoted heredoc expands $count, the single-quoted echo does not.
        assertThat(variable(analysis, "count", "").references()).isEqualTo(2);
    }

    @Test
    void namePositionsPointAtTheDefinitionInTheOriginalText() {
        String script = "x=1\n# comment\nmy_function() {\n  local value=2\n}\n";

        Analysis analysis = SnippetStructureScanner.analyze("bash", script);

        OutlineNode function = analysis.outline().get(0);
        assertThat(script.substring(function.nameStart(), function.nameEnd())).isEqualTo("my_function");
        Variable value = variable(analysis, "value", "my_function");
        assertThat(script.substring(value.nameStart(), value.nameEnd())).isEqualTo("value");
        assertThat(analysis.nodeAt(4).name()).isEqualTo("my_function");
        assertThat(analysis.nodeAt(1)).isNull();
    }

    @Test
    void plainTextWithAShebangUsesTheShebangLanguage() {
        Analysis analysis = SnippetStructureScanner.analyze("plain", "#!/usr/bin/env python3\ndef main():\n    pass\n");

        assertThat(analysis.language()).isEqualTo("python");
        assertThat(outline(analysis)).containsExactly("FUNCTION main 2-3");
    }

    // ---------------------------------------------------------------- python

    @Test
    void pythonNestsMethodsInClassesByIndentation() {
        String script = """
            MAX_RETRIES = 3
            class Server(Base):
                port = 8080

                def __init__(self, host,
                             port=22, *args):
                    self.host = host

                async def run(self):
                    \"\"\"def not_a_function():\"\"\"
                    for i, item in enumerate(self.items):
                        print(self.host)

            def main(argv):
                return 0
            """;

        Analysis analysis = SnippetStructureScanner.analyze("python", script);

        assertThat(outline(analysis)).containsExactly(
            "CLASS Server 2-12", "METHOD Server.__init__ 5-7", "METHOD Server.run 9-12", "FUNCTION main 14-15")
            .inOrder();
        assertThat(analysis.outline().get(0).children().get(0).detail()).isEqualTo("(host, port, args)");
        assertThat(describe(variable(analysis, "MAX_RETRIES", ""))).isEqualTo("CONSTANT SCALAR @1");
        assertThat(describe(variable(analysis, "port", "Server"))).isEqualTo("FIELD SCALAR @3");
        assertThat(describe(variable(analysis, "port", "Server.__init__"))).isEqualTo("PARAMETER SCALAR @6");
        assertThat(describe(variable(analysis, "host", "Server"))).isEqualTo("FIELD SCALAR @7");
        assertThat(describe(variable(analysis, "item", "Server.run"))).isEqualTo("VARIABLE SCALAR @11");
        assertThat(describe(variable(analysis, "argv", "main"))).isEqualTo("PARAMETER SCALAR @14");
        assertThat(variable(analysis, "host", "Server").references()).isEqualTo(1);
        assertThat(analysis.nodeAt(11).qualifiedName()).isEqualTo("Server.run");
    }

    // ---------------------------------------------------------------- perl, ruby, powershell

    @Test
    void perlGroupsSubsUnderPackagesAndSkipsPod() {
        String script = """
            package Foo::Bar;
            use constant PI => 3.14;
            our @list = (1, 2);
            my %seen;

            =pod

            sub hidden { }

            =cut

            sub process {
                my ($self, $arg) = @_;
                return $arg . $list[0];
            }
            package Other;
            sub run { }
            """;

        Analysis analysis = SnippetStructureScanner.analyze("perl", script);

        assertThat(outline(analysis)).containsExactly(
            "MODULE Foo::Bar 1-15", "FUNCTION Foo::Bar::process 12-15", "MODULE Other 16-17",
            "FUNCTION Other::run 17-17").inOrder();
        assertThat(describe(variable(analysis, "PI", ""))).isEqualTo("CONSTANT SCALAR @2");
        assertThat(describe(variable(analysis, "@list", ""))).isEqualTo("ARRAY ARRAY @3");
        assertThat(describe(variable(analysis, "$arg", "Foo::Bar::process"))).isEqualTo("VARIABLE SCALAR @13");
        assertThat(variable(analysis, "$arg", "Foo::Bar::process").references()).isEqualTo(1);
        assertThat(variable(analysis, "@list", "").references()).isEqualTo(1);
    }

    @Test
    void rubyNestsModulesClassesAndMethods() {
        String script = """
            VERSION = '1.0'
            $debug = false
            module Tools
              class Runner < Base
                def run(arg, opt = 1, *rest, key:)
                  @count = arg
                end

                def self.build
                end
              end
            end
            """;

        Analysis analysis = SnippetStructureScanner.analyze("ruby", script);

        assertThat(outline(analysis)).containsExactly(
            "MODULE Tools 3-12", "CLASS Tools.Runner 4-11", "METHOD Tools.Runner.run 5-7",
            "METHOD Tools.Runner.build 9-10").inOrder();
        assertThat(describe(variable(analysis, "VERSION", ""))).isEqualTo("CONSTANT SCALAR @1");
        assertThat(describe(variable(analysis, "$debug", ""))).isEqualTo("VARIABLE SCALAR @2");
        assertThat(describe(variable(analysis, "@count", "Tools.Runner"))).isEqualTo("FIELD SCALAR @6");
        assertThat(describe(variable(analysis, "key", "Tools.Runner.run"))).isEqualTo("PARAMETER SCALAR @5");
        assertThat(variable(analysis, "arg", "Tools.Runner.run").references()).isEqualTo(1);
    }

    @Test
    void powershellReadsParamBlocksClassesAndScopes() {
        String script = """
            param(
                [string]$Name,
                [int]$Count = 1
            )
            $script:State = 'x'
            $env:APP_HOME = 'C:\\app'
            <# function Hidden { } #>
            function Get-Thing {
                param([string]$Path)
                $result = $Path
            }
            class Widget {
                [string]$Label
                [void] Show([int]$times) {
                }
            }
            """;

        Analysis analysis = SnippetStructureScanner.analyze("powershell", script);

        assertThat(outline(analysis)).containsExactly(
            "FUNCTION Get-Thing 8-11", "CLASS Widget 12-16", "METHOD Widget.Show 14-15").inOrder();
        assertThat(describe(variable(analysis, "$Name", ""))).isEqualTo("PARAMETER SCALAR @2");
        assertThat(describe(variable(analysis, "$Count", ""))).isEqualTo("PARAMETER SCALAR @3");
        assertThat(describe(variable(analysis, "$State", ""))).isEqualTo("VARIABLE SCALAR @5");
        assertThat(describe(variable(analysis, "$APP_HOME", ""))).isEqualTo("EXPORTED SCALAR @6");
        assertThat(describe(variable(analysis, "$Path", "Get-Thing"))).isEqualTo("PARAMETER SCALAR @9");
        assertThat(describe(variable(analysis, "$Label", "Widget"))).isEqualTo("FIELD SCALAR @13");
        assertThat(describe(variable(analysis, "$times", "Widget.Show"))).isEqualTo("PARAMETER SCALAR @14");
        assertThat(variable(analysis, "$Path", "Get-Thing").references()).isEqualTo(1);
    }

    @Test
    void luaGroupsTableFunctionsAndNestsByKeywordBlocks() {
        String script = """
            #!/usr/bin/env lua
            --[[ function hidden() end ]]
            local M = {}
            M.VERSION = "1.0"
            local MAX_RETRIES <const> = 3
            local cache = {}
            local items = { "a", "b" }
            local text = [[
            function alsoHidden()
            end
            ]]
            local function helper(path, ...)
              local count = 0
              for i, v in ipairs(items) do
                count = count + i
              end
              return count
            end
            function M.load(path)
              local data = helper(path)
              cache[path] = data
              return data
            end
            local Account = {}
            function Account:deposit(amount)
              self.balance = (self.balance or 0) + amount
              local log = function(msg) print(msg) end
              log("deposit")
            end
            repeat
              total = (total or 0) + 1
            until total > 3
            """;

        Analysis analysis = SnippetStructureScanner.analyze("plain", script);

        assertThat(analysis.language()).isEqualTo("lua");
        assertThat(outline(analysis)).containsExactly(
            "MODULE M 3-23", "FUNCTION M.load 19-23", "FUNCTION helper 12-18", "CLASS Account 24-29",
            "METHOD Account.deposit 25-29", "FUNCTION Account.deposit.log 27-27").inOrder();
        assertThat(analysis.outline().get(1).detail()).isEqualTo("(path)");
        assertThat(analysis.outline().get(0).detail()).isEqualTo("table");
        // A function defined inside the table's line range is not part of the table.
        assertThat(analysis.nodeAt(14).qualifiedName()).isEqualTo("helper");
        assertThat(describe(variable(analysis, "VERSION", "M"))).isEqualTo("CONSTANT SCALAR @4");
        assertThat(describe(variable(analysis, "MAX_RETRIES", ""))).isEqualTo("CONSTANT SCALAR @5");
        assertThat(describe(variable(analysis, "cache", ""))).isEqualTo("HASH HASH @6");
        assertThat(describe(variable(analysis, "items", ""))).isEqualTo("ARRAY ARRAY @7");
        assertThat(describe(variable(analysis, "count", "helper"))).isEqualTo("VARIABLE SCALAR @13");
        assertThat(describe(variable(analysis, "v", "helper"))).isEqualTo("VARIABLE SCALAR @14");
        assertThat(describe(variable(analysis, "path", "M.load"))).isEqualTo("PARAMETER SCALAR @19");
        assertThat(describe(variable(analysis, "balance", "Account"))).isEqualTo("FIELD SCALAR @26");
        // A plain assignment in Lua sets a global variable.
        assertThat(describe(variable(analysis, "total", ""))).isEqualTo("VARIABLE SCALAR @31");
        assertThat(names(analysis)).doesNotContain("alsoHidden");
        assertThat(variable(analysis, "count", "helper").references()).isEqualTo(3);
        assertThat(variable(analysis, "path", "M.load").references()).isEqualTo(2);
        assertThat(variable(analysis, "balance", "Account").references()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- java, javascript, typescript, groovy

    @Test
    void javaListsClassesMethodsConstructorsAndFields() {
        String script = """
            public class Service {
                private static final int LIMIT = 5;
                private final List<String> names = new ArrayList<>();
                private int value;

                public Service(int value) {
                    this.value = value;
                }

                @Override
                public List<String> names() {
                    if (value > LIMIT) {
                        helper(value);
                    }
                    String type = "x";
                    return names;
                }

                abstract void run();

                interface Callback {
                    void done(String result);
                }
            }
            """;

        Analysis analysis = SnippetStructureScanner.analyze("java", script);

        assertThat(outline(analysis)).containsExactly(
            "CLASS Service 1-24", "METHOD Service.Service 6-8", "METHOD Service.names 11-17",
            "METHOD Service.run 19-19", "CLASS Service.Callback 21-23", "METHOD Service.Callback.done 22-22")
            .inOrder();
        assertThat(describe(variable(analysis, "LIMIT", "Service"))).isEqualTo("CONSTANT SCALAR @2");
        assertThat(describe(variable(analysis, "names", "Service"))).isEqualTo("FIELD ARRAY @3");
        assertThat(describe(variable(analysis, "value", "Service"))).isEqualTo("FIELD SCALAR @4");
        assertThat(describe(variable(analysis, "value", "Service.Service"))).isEqualTo("PARAMETER SCALAR @6");
        // "type" is a stop word of the completion rules; the structure view still lists it.
        assertThat(describe(variable(analysis, "type", "Service.names"))).isEqualTo("VARIABLE SCALAR @15");
        assertThat(variable(analysis, "value", "Service").references()).isEqualTo(3);
        assertThat(variable(analysis, "value", "Service.Service").references()).isEqualTo(1);
        assertThat(variable(analysis, "names", "Service").references()).isEqualTo(1);
    }

    @Test
    void javascriptFunctionsArrowsAndClassMembers() {
        String script = """
            const MAX = 10;
            let items = [];
            export const handler = async (event) => {
              return event;
            };
            const double = x => x * 2;
            function helper({ a, b }, c = 1) {
              if (c) {
              }
            }
            class Widget extends Base {
              #secret = 1;
              constructor(name) {
                this.name = name;
              }
              render() {
                return this.name;
              }
            }
            """;

        Analysis analysis = SnippetStructureScanner.analyze("javascript", script);

        assertThat(outline(analysis)).containsExactly(
            "FUNCTION handler 3-5", "FUNCTION double 6-6", "FUNCTION helper 7-10", "CLASS Widget 11-19",
            "METHOD Widget.constructor 13-15", "METHOD Widget.render 16-18").inOrder();
        assertThat(names(analysis)).doesNotContain("handler");
        assertThat(describe(variable(analysis, "MAX", ""))).isEqualTo("CONSTANT SCALAR @1");
        assertThat(describe(variable(analysis, "items", ""))).isEqualTo("ARRAY ARRAY @2");
        assertThat(describe(variable(analysis, "c", "helper"))).isEqualTo("PARAMETER SCALAR @7");
        assertThat(describe(variable(analysis, "#secret", "Widget"))).isEqualTo("FIELD SCALAR @12");
        assertThat(describe(variable(analysis, "name", "Widget"))).isEqualTo("FIELD SCALAR @14");
        assertThat(variable(analysis, "name", "Widget").references()).isEqualTo(1);
    }

    @Test
    void typescriptInterfacesNamespacesAndTypedFields() {
        String script = """
            interface Options { a: number }
            namespace Util {
              export function load(path: string): void {
              }
            }
            export class Store {
              private count: number;
              get(key: string): number {
                return this.count;
              }
            }
            """;

        Analysis analysis = SnippetStructureScanner.analyze("typescript", script);

        assertThat(outline(analysis)).containsExactly(
            "CLASS Options 1-1", "MODULE Util 2-5", "FUNCTION Util.load 3-4", "CLASS Store 6-11",
            "METHOD Store.get 8-10").inOrder();
        assertThat(analysis.outline().get(0).detail()).isEqualTo("interface");
        assertThat(describe(variable(analysis, "count", "Store"))).isEqualTo("FIELD SCALAR @7");
        assertThat(variable(analysis, "count", "Store").references()).isEqualTo(1);
    }

    @Test
    void groovyScriptFunctionsOutsideAClass() {
        Analysis analysis = SnippetStructureScanner.analyze("groovy", "def name = 'x'\ndef greet(String who) {\n  println who\n}\n");

        assertThat(outline(analysis)).containsExactly("FUNCTION greet 2-4");
        assertThat(describe(variable(analysis, "who", "greet"))).isEqualTo("PARAMETER SCALAR @2");
        assertThat(variable(analysis, "who", "greet").references()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- sql, markup, dockerfile

    @Test
    void sqlObjectsAndDeclaredVariables() {
        String script = """
            -- CREATE TABLE commented_out (id INT);
            CREATE TABLE IF NOT EXISTS users (
              id INT
            );
            CREATE OR REPLACE VIEW active_users AS SELECT * FROM users;
            DECLARE @limit INT = 5;
            SELECT TOP (@limit) * FROM users;
            """;

        Analysis analysis = SnippetStructureScanner.analyze("sql", script);

        assertThat(outline(analysis)).containsExactly("SQL_OBJECT users 2-4", "SQL_OBJECT active_users 5-5").inOrder();
        assertThat(analysis.outline().get(0).detail()).isEqualTo("table");
        assertThat(variable(analysis, "@limit", "").references()).isEqualTo(1);
    }

    @Test
    void markdownHeadingsNestByLevelAndSkipCodeFences() {
        String text = """
            # Title
            intro
            ## Install
            ```bash
            # not a heading
            ```
            ### Linux
            ## Usage
            """;

        Analysis analysis = SnippetStructureScanner.analyze("markdown", text);

        assertThat(outline(analysis)).containsExactly(
            "SECTION Title 1-9", "SECTION Title › Install 3-7", "SECTION Title › Install › Linux 7-7",
            "SECTION Title › Usage 8-9").inOrder();
        assertThat(analysis.variables()).isEmpty();
    }

    @Test
    void asciidocHeadingsSkipDelimitedBlocks() {
        String text = "= Guide\n\n== Setup\n----\n== not a heading\n----\n=== Details\n";

        Analysis analysis = SnippetStructureScanner.analyze("asciidoctor", text);

        assertThat(outline(analysis)).containsExactly(
            "SECTION Guide 1-8", "SECTION Guide › Setup 3-8", "SECTION Guide › Setup › Details 7-8").inOrder();
    }

    @Test
    void dockerfileStagesWithArgsAndEnv() {
        String text = """
            ARG BASE=alpine
            FROM ${BASE} AS build
            ENV APP_HOME=/app MODE=prod
            RUN make -C $APP_HOME
            FROM alpine
            ENV LEGACY value
            """;

        Analysis analysis = SnippetStructureScanner.analyze("dockerfile", text);

        assertThat(outline(analysis)).containsExactly("STAGE build 2-4", "STAGE alpine 5-7").inOrder();
        assertThat(describe(variable(analysis, "BASE", ""))).isEqualTo("PARAMETER SCALAR @1");
        assertThat(describe(variable(analysis, "APP_HOME", "build"))).isEqualTo("EXPORTED SCALAR @3");
        assertThat(describe(variable(analysis, "MODE", "build"))).isEqualTo("EXPORTED SCALAR @3");
        assertThat(describe(variable(analysis, "LEGACY", "alpine"))).isEqualTo("EXPORTED SCALAR @6");
        assertThat(variable(analysis, "BASE", "").references()).isEqualTo(1);
        assertThat(variable(analysis, "APP_HOME", "build").references()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- limits

    @Test
    void unsupportedAndOversizedTextsAreNotScanned() {
        assertThat(SnippetStructureScanner.analyze("json", "{\"a\": 1}").status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(SnippetStructureScanner.analyze("plain", "just text").status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(SnippetStructureScanner.supports("yaml", "a: 1")).isFalse();
        assertThat(SnippetStructureScanner.supports("sh", "x=1")).isTrue();
        String huge = "x=1\n".repeat(SnippetStructureScanner.MAX_TEXT_CHARS / 4 + 1);
        assertThat(SnippetStructureScanner.analyze("bash", huge).status()).isEqualTo(Status.TOO_LARGE);
    }

    @Test
    void manyDefinitionsAreCappedAndReportedAsTruncated() {
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < SnippetStructureScanner.MAX_ENTRIES + 10; i++) {
            script.append("f").append(i).append("() {\n  v").append(i).append("=1\n}\n");
        }

        Analysis analysis = SnippetStructureScanner.analyze("bash", script.toString());

        assertThat(analysis.truncated()).isTrue();
        assertThat(analysis.outline()).hasSize(SnippetStructureScanner.MAX_ENTRIES);
        assertThat(analysis.variables()).hasSize(SnippetStructureScanner.MAX_ENTRIES);
    }

    @Test
    void fiveThousandLinesScanQuickly() {
        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        for (int i = 0; i < 1_000; i++) {
            script.append("step_").append(i).append("() {\n")
                .append("  local value_").append(i).append("=\"$1\"\n")
                .append("  echo \"${value_").append(i).append("} ${count}\"\n")
                .append("}\n")
                .append("count=").append(i).append('\n');
        }
        String text = script.toString();
        SnippetStructureScanner.analyze("bash", text); // warm-up

        long started = System.nanoTime();
        Analysis analysis = SnippetStructureScanner.analyze("bash", text);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(analysis.outline()).hasSize(1_000);
        assertThat(millis).isLessThan(1_000L);
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> outline(Analysis analysis) {
        List<String> out = new ArrayList<>();
        flatten(analysis.outline(), out);
        return out;
    }

    private static void flatten(List<OutlineNode> nodes, List<String> out) {
        for (OutlineNode node : nodes) {
            out.add(node.kind() + " " + node.qualifiedName() + " " + node.line() + "-" + node.endLine());
            flatten(node.children(), out);
        }
    }

    private static List<String> names(Analysis analysis) {
        return analysis.variables().stream().map(Variable::name).toList();
    }

    private static Variable variable(Analysis analysis, String name, String scope) {
        return analysis.variables().stream()
            .filter(v -> v.name().equals(name) && v.scope().equals(scope))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no variable " + name + " in scope '" + scope + "' among "
                + analysis.variables()));
    }

    private static String describe(Variable variable) {
        Container container = variable.container();
        VariableKind kind = variable.kind();
        return kind + " " + container + " @" + variable.line();
    }
}
