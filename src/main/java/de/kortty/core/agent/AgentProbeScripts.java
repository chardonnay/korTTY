package de.kortty.core.agent;

/**
 * Environment-probe scripts for the AI agent. Each script prints {@code key=value} lines that
 * {@code TerminalAgentService.parseProbeOutput} understands, so the same parser serves POSIX (SSH or
 * local Unix) and Windows (local PowerShell/cmd) sessions.
 */
public final class AgentProbeScripts {

    private AgentProbeScripts() {
    }

    /** POSIX probe (sh) — used by SSH and local Unix shells. */
    public static final String POSIX = """
        sh -lc '
        sanitize() {
          printf "%s" "$1" | tr "\\r\\n\\t" "   " | sed "s/[[:space:]]\\+/ /g"
        }
        os_release=""
        if [ -f /etc/os-release ]; then
          . /etc/os-release >/dev/null 2>&1
          os_release="${PRETTY_NAME:-${NAME:-}}"
        fi
        [ -n "$os_release" ] || os_release="$(uname -s 2>/dev/null || printf unknown)"
        kernel="$(uname -sr 2>/dev/null || printf unknown)"
        architecture="$(uname -m 2>/dev/null || printf unknown)"
        shell_value="${SHELL:-}"
        current_user="$(id -un 2>/dev/null || whoami 2>/dev/null || printf unknown)"
        uid_value="$(id -u 2>/dev/null || printf unknown)"
        gid_value="$(id -g 2>/dev/null || printf unknown)"
        groups_value="$(id -nG 2>/dev/null || printf)"
        home_dir="${HOME:-}"
        current_dir="$(pwd 2>/dev/null || printf)"
        available_disk_kb="$(df -Pk . 2>/dev/null | awk "NR==2 {print \\$4}")"
        available_disk_path="$(df -Pk . 2>/dev/null | awk "NR==2 {print \\$6}")"
        package_managers=""
        for candidate in apt apt-get dnf yum zypper pacman apk brew; do
          if command -v "$candidate" >/dev/null 2>&1; then
            package_managers="${package_managers},${candidate}"
          fi
        done
        service_managers=""
        for candidate in systemctl service rc-service launchctl; do
          if command -v "$candidate" >/dev/null 2>&1; then
            service_managers="${service_managers},${candidate}"
          fi
        done
        already_root=false
        [ "$uid_value" = "0" ] && already_root=true
        sudo_available=false
        passwordless_sudo=false
        sudo_non_interactive=false
        sudo_n_list_summary=""
        if command -v sudo >/dev/null 2>&1; then
          sudo_available=true
          if sudo -n true >/dev/null 2>&1; then
            passwordless_sudo=true
            sudo_non_interactive=true
          fi
          sudo_n_list_summary="$(sudo -n -l 2>&1 | tail -c 2000)"
        fi
        if [ "$already_root" = "true" ]; then
          root_mode="already_root"
        elif [ "$passwordless_sudo" = "true" ]; then
          root_mode="passwordless_sudo"
        elif [ "$sudo_available" = "true" ]; then
          root_mode="sudo_password"
        else
          root_mode="none"
        fi
        printf "osRelease=%s\\n" "$(sanitize "$os_release")"
        printf "kernel=%s\\n" "$(sanitize "$kernel")"
        printf "architecture=%s\\n" "$(sanitize "$architecture")"
        printf "shell=%s\\n" "$(sanitize "$shell_value")"
        printf "currentUser=%s\\n" "$(sanitize "$current_user")"
        printf "uid=%s\\n" "$(sanitize "$uid_value")"
        printf "gid=%s\\n" "$(sanitize "$gid_value")"
        printf "groups=%s\\n" "$(sanitize "$groups_value" | tr " " ",")"
        printf "homeDir=%s\\n" "$(sanitize "$home_dir")"
        printf "currentDir=%s\\n" "$(sanitize "$current_dir")"
        printf "availableDiskKb=%s\\n" "$(sanitize "$available_disk_kb")"
        printf "availableDiskPath=%s\\n" "$(sanitize "$available_disk_path")"
        printf "packageManagers=%s\\n" "$(printf "%s" "$package_managers" | sed "s/^,//")"
        printf "serviceManagers=%s\\n" "$(printf "%s" "$service_managers" | sed "s/^,//")"
        printf "alreadyRoot=%s\\n" "$already_root"
        printf "sudoAvailable=%s\\n" "$sudo_available"
        printf "passwordlessSudo=%s\\n" "$passwordless_sudo"
        printf "sudoNonInteractive=%s\\n" "$sudo_non_interactive"
        printf "sudoNListSummary=%s\\n" "$(sanitize "$sudo_n_list_summary")"
        printf "rootEscalationMode=%s\\n" "$root_mode"
        '
        """;

    /**
     * Windows PowerShell probe. Emits the same {@code key=value} contract as {@link #POSIX}.
     * Windows has no sudo, so the sudo/root fields are reported as absent.
     *
     * @param shellLabel the agent's command language label to report (e.g. {@code powershell.exe}
     *                   or {@code cmd.exe}) so the model generates matching commands.
     */
    public static String windowsPowerShell(String shellLabel) {
        String safeLabel = shellLabel == null ? "powershell.exe" : shellLabel.replace("'", "");
        return """
            $ErrorActionPreference='SilentlyContinue'
            function san([string]$s){ if($null -eq $s){return ''}; return ($s -replace '[\\r\\n\\t]',' ') }
            $os=''
            try { $os=(Get-CimInstance Win32_OperatingSystem).Caption } catch {}
            if([string]::IsNullOrWhiteSpace($os)){ $os=[string][System.Environment]::OSVersion.VersionString }
            $ver=[string][System.Environment]::OSVersion.Version
            $arch=$env:PROCESSOR_ARCHITECTURE
            $usr=$env:USERNAME
            $hd=$env:USERPROFILE
            $cwd=(Get-Location).Path
            $pkgs=@()
            foreach($c in 'winget','choco','scoop'){ if(Get-Command $c -ErrorAction SilentlyContinue){ $pkgs+=$c } }
            Write-Output ("osRelease=" + (san $os))
            Write-Output ("kernel=" + (san ("Windows " + $ver)))
            Write-Output ("architecture=" + (san $arch))
            Write-Output ("shell=__SHELL__")
            Write-Output ("currentUser=" + (san $usr))
            Write-Output ("uid=")
            Write-Output ("gid=")
            Write-Output ("groups=")
            Write-Output ("homeDir=" + (san $hd))
            Write-Output ("currentDir=" + (san $cwd))
            Write-Output ("availableDiskKb=")
            Write-Output ("availableDiskPath=" + (san $cwd))
            Write-Output ("packageManagers=" + (san ($pkgs -join ',')))
            Write-Output ("serviceManagers=Get-Service,sc")
            Write-Output ("alreadyRoot=false")
            Write-Output ("sudoAvailable=false")
            Write-Output ("passwordlessSudo=false")
            Write-Output ("sudoNonInteractive=false")
            Write-Output ("sudoNListSummary=")
            Write-Output ("rootEscalationMode=none")
            """.replace("__SHELL__", safeLabel);
    }
}
