package de.kortty.core;

import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.ScriptLanguageMixSupport.MigrationMode;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class ScriptLanguageMixSupportTest {

    // ---------------------------------------------------------------- plain scripts

    @Test
    void singleLanguageBashScriptOffersNothing() {
        String script = """
            #!/usr/bin/env bash
            set -euo pipefail
            for file in "$@"; do
              cp -- "$file" /backup/
            done
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("bash", script);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.NONE);
        assertThat(mix.embedded()).isEmpty();
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.UNAVAILABLE);
    }

    @Test
    void bashWithPerlHeredocReportsPerlBlockAndOffersWholeScriptMigration() {
        String script = """
            #!/usr/bin/env bash
            set -euo pipefail
            perl <<'PERL'
            use strict;
            print "hello\\n";
            PERL
            echo done
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("bash", script);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.NONE);
        assertThat(mix.dominantLanguage()).isEqualTo("bash");
        assertThat(mix.embeddedLanguages()).containsExactly("perl");
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.WHOLE_SCRIPT);
        // The block body sits between the heredoc opener and its terminator, both excluded.
        assertThat(mix.embedded().get(0).startLine()).isEqualTo(4);
        assertThat(mix.embedded().get(0).endLine()).isEqualTo(5);
    }

    @Test
    void inlineAwkAndPythonOneLinersAreReported() {
        String script = """
            #!/usr/bin/env bash
            awk '{ sum += $1 } END { print sum }' totals.txt
            python3 -c 'import sys; print(sys.version)'
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("bash", script);
        assertThat(mix.embeddedLanguages()).containsExactly("awk", "python").inOrder();
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.WHOLE_SCRIPT);
    }

    @Test
    void interpreterMentionsInsideCommentsAreIgnored() {
        String script = """
            #!/usr/bin/env bash
            # previously this used perl -e 'print 1' and awk '{print}'
            echo plain
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("bash", script);
        assertThat(mix.embedded()).isEmpty();
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.UNAVAILABLE);
    }

    @Test
    void aPlainExternalCallFromPythonIsNotAnEmbeddedShellProgram() {
        String script = """
            #!/usr/bin/env python3
            import subprocess
            subprocess.run("ls /tmp", shell=True)
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("python", script);
        assertThat(mix.embedded()).isEmpty();
    }

    @Test
    void aShellProgramFromPythonIsReported() {
        String script = """
            #!/usr/bin/env python3
            import subprocess
            subprocess.run("cd /tmp && tar czf backup.tgz . || exit 1", shell=True)
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("python", script);
        assertThat(mix.embeddedLanguages()).containsExactly("bash");
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.WHOLE_SCRIPT);
    }

    // ---------------------------------------------------------------- host formats

    @Test
    void azurePipelineWithOnlyBashStepsIsNotMixed() {
        String pipeline = """
            trigger:
              - main
            pool:
              vmImage: ubuntu-latest
            steps:
              - bash: |
                  set -euo pipefail
                  make build
                displayName: Build
              - bash: make test
                displayName: Test
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("yaml", pipeline);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.AZURE_PIPELINES);
        assertThat(mix.stepLanguages()).containsExactly("bash");
        // The core rule: embedded Bash in a pipeline is by design, never a migration suggestion.
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.UNAVAILABLE);
    }

    @Test
    void azurePipelineMixingBashAndPwshOffersStepUnification() {
        String pipeline = """
            trigger:
              - main
            pool:
              vmImage: ubuntu-latest
            steps:
              - bash: |
                  set -euo pipefail
                  make build
                displayName: Build
              - pwsh: |
                  Write-Host "publishing"
                displayName: Publish
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("yaml", pipeline);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.AZURE_PIPELINES);
        assertThat(mix.stepLanguages()).containsExactly("bash", "powershell").inOrder();
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.EMBEDDED_STEPS_ONLY);
        // Only step bodies are reported; the scaffold lines stay untouched.
        assertThat(mix.embedded()).hasSize(2);
        assertThat(mix.embedded().get(0).startLine()).isEqualTo(7);
        assertThat(mix.embedded().get(0).endLine()).isEqualTo(8);
        assertThat(mix.embedded().get(1).startLine()).isEqualTo(11);
        assertThat(mix.embedded().get(1).endLine()).isEqualTo(11);
    }

    @Test
    void githubActionsReadsTheStepShellKey() {
        String workflow = """
            on:
              push:
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - name: Build
                    run: |
                      make build
                  - name: Publish
                    shell: pwsh
                    run: |
                      Write-Host "publishing"
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("yaml", workflow);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.GITHUB_ACTIONS);
        assertThat(mix.stepLanguages()).containsExactly("bash", "powershell").inOrder();
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.EMBEDDED_STEPS_ONLY);
    }

    @Test
    void gitlabCiIsDetectedAndItsShellStepsAgree() {
        String ci = """
            stages:
              - build
              - test
            build-job:
              stage: build
              script:
                - make build
            test-job:
              stage: test
              script:
                - make test
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("yaml", ci);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.GITLAB_CI);
        assertThat(mix.stepLanguages()).containsExactly("bash");
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.UNAVAILABLE);
    }

    @Test
    void declarativeJenkinsfileMixingShAndBatOffersStepUnification() {
        String jenkinsfile = """
            pipeline {
              agent any
              stages {
                stage('Build') {
                  steps {
                    sh 'make build'
                  }
                }
                stage('Package') {
                  steps {
                    bat 'msbuild /t:Package'
                  }
                }
              }
            }
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("groovy", jenkinsfile);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.JENKINS_DECLARATIVE);
        assertThat(mix.stepLanguages()).containsExactly("bash", "bat").inOrder();
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.EMBEDDED_STEPS_ONLY);
    }

    @Test
    void scriptedJenkinsfileIsDetected() {
        String jenkinsfile = """
            node {
              stage('Build') {
                sh 'make build'
              }
            }
            """;
        assertThat(ScriptLanguageMixSupport.detectHostFormat("groovy", jenkinsfile))
            .isEqualTo(HostFormat.JENKINS_SCRIPTED);
    }

    @Test
    void ansiblePlaybookIsAHostFormatNotAMixedScript() {
        String playbook = """
            - hosts: web
              tasks:
                - name: Restart nginx
                  ansible.builtin.shell: systemctl restart nginx
            """;
        LanguageMix mix = ScriptLanguageMixSupport.detect("yaml", playbook);
        assertThat(mix.hostFormat()).isEqualTo(HostFormat.ANSIBLE);
        assertThat(mix.defaultMode()).isEqualTo(MigrationMode.UNAVAILABLE);
    }

    @Test
    void puppetManifestIsDetected() {
        String manifest = """
            exec { 'refresh-cache':
              command => '/usr/bin/apt-get update',
              path    => ['/usr/bin'],
            }
            """;
        assertThat(ScriptLanguageMixSupport.detectHostFormat("puppet", manifest))
            .isEqualTo(HostFormat.PUPPET);
    }

    @Test
    void dockerfileIsDetectedAndIsNotAConversionTarget() {
        String dockerfile = """
            FROM debian:stable-slim
            RUN apt-get update && apt-get install -y curl
            CMD ["/usr/bin/app"]
            """;
        assertThat(ScriptLanguageMixSupport.detectHostFormat("dockerfile", dockerfile))
            .isEqualTo(HostFormat.DOCKERFILE);
        assertThat(HostFormat.DOCKERFILE.isConversionTarget()).isFalse();
        assertThat(HostFormat.conversionTargets()).doesNotContain(HostFormat.DOCKERFILE);
        assertThat(HostFormat.conversionTargets()).doesNotContain(HostFormat.NONE);
    }

    // ---------------------------------------------------------------- invariants

    @Test
    void defaultModeNeverProposesAPlatformConversion() {
        String[] documents = {
            "#!/usr/bin/env bash\nperl -e 'print 1'\n",
            "trigger:\n  - main\npool:\n  vmImage: ubuntu-latest\nsteps:\n  - bash: make\n  - pwsh: Write-Host 1\n",
            "pipeline {\n  agent any\n  stages {\n    stage('a') {\n      steps {\n        sh 'make'\n      }\n    }\n  }\n}\n",
            "- hosts: all\n  tasks:\n    - ansible.builtin.command: uptime\n",
            "FROM debian\nRUN true\n",
            "",
        };
        for (String document : documents) {
            assertThat(ScriptLanguageMixSupport.detect(null, document).defaultMode())
                .isNotEqualTo(MigrationMode.HOST_FORMAT_CONVERSION);
        }
    }

    @Test
    void aHostDocumentIsNeverOfferedAWholeScriptMigration() {
        String[] hostDocuments = {
            "trigger:\n  - main\npool:\n  vmImage: ubuntu-latest\nsteps:\n  - bash: make\n  - pwsh: Write-Host 1\n",
            "on:\n  push:\njobs:\n  b:\n    runs-on: ubuntu-latest\n    steps:\n      - run: make\n",
            "pipeline {\n  agent any\n  stages {\n    stage('a') {\n      steps {\n        sh 'make'\n      }\n    }\n  }\n}\n",
            "- hosts: all\n  tasks:\n    - ansible.builtin.command: uptime\n",
            "stages:\n  - build\nb:\n  script:\n    - make\n",
            "FROM debian\nRUN true\n",
        };
        for (String document : hostDocuments) {
            LanguageMix mix = ScriptLanguageMixSupport.detect(null, document);
            assertThat(mix.hostFormat()).isNotEqualTo(HostFormat.NONE);
            assertThat(mix.defaultMode()).isNotEqualTo(MigrationMode.WHOLE_SCRIPT);
        }
    }

    @Test
    void migrationTargetsExcludeAnsibleAndIncludeTheMigrationOnlyLanguages() {
        assertThat(ScriptLanguage.migrationTargets()).doesNotContain(ScriptLanguage.ANSIBLE);
        assertThat(ScriptLanguage.migrationTargets())
            .containsAtLeast(ScriptLanguage.JAVASCRIPT, ScriptLanguage.GROOVY);
        assertThat(ScriptLanguage.generationTargets()).contains(ScriptLanguage.ANSIBLE);
        assertThat(ScriptLanguage.generationTargets())
            .containsNoneOf(ScriptLanguage.JAVASCRIPT, ScriptLanguage.GROOVY);
    }

    @Test
    void nullAndBlankInputAreSafe() {
        assertThat(ScriptLanguageMixSupport.detect(null, null).defaultMode())
            .isEqualTo(MigrationMode.UNAVAILABLE);
        assertThat(ScriptLanguageMixSupport.detectHostFormat(null, "   ")).isEqualTo(HostFormat.NONE);
    }
}
