package de.kortty.update;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class UpdateAssetSelector {

    public Optional<UpdateAsset> select(UpdateRelease release, PlatformProfile profile) {
        if (release == null || profile == null) {
            return Optional.empty();
        }
        List<UpdateAsset> assets = release.assets().stream()
            .filter(this::isInstallableAsset)
            .sorted(Comparator.comparing(UpdateAsset::name))
            .toList();
        return switch (profile.operatingSystem()) {
            case WINDOWS -> selectWindowsAsset(assets, profile);
            case MACOS -> selectMacAsset(assets, profile);
            case LINUX -> selectLinuxAsset(assets, profile);
            case OTHER -> selectJavaZip(assets);
        };
    }

    private Optional<UpdateAsset> selectWindowsAsset(List<UpdateAsset> assets, PlatformProfile profile) {
        return findAsset(assets, profile, "windows", ".msi")
            .or(() -> findAsset(assets, profile, "windows", ".zip"))
            .or(() -> selectJavaZip(assets));
    }

    private Optional<UpdateAsset> selectMacAsset(List<UpdateAsset> assets, PlatformProfile profile) {
        return findAsset(assets, profile, "macos", ".dmg")
            .or(() -> findAsset(assets, profile, "macos", ".zip"))
            .or(() -> selectJavaZip(assets));
    }

    private Optional<UpdateAsset> selectLinuxAsset(List<UpdateAsset> assets, PlatformProfile profile) {
        if (profile.flatpak()) {
            // A sandbox installation cannot safely replace itself with a distro-native package.
            // Keep the update in the same package format and let the user install the bundle from
            // a host terminal after its SHA-256-verified download completes.
            return findLinuxAsset(assets, profile, ".flatpak");
        }
        String preferredExtension = preferredLinuxExtension(profile);
        return findLinuxAsset(assets, profile, preferredExtension)
            .or(() -> preferredExtension.equals(".deb") ? Optional.empty() : findLinuxAsset(assets, profile, ".deb"))
            .or(() -> preferredExtension.equals(".rpm") ? Optional.empty() : findLinuxAsset(assets, profile, ".rpm"))
            .or(() -> preferredExtension.equals(".pkg.tar.zst") ? Optional.empty() : findLinuxAsset(assets, profile, ".pkg.tar.zst"))
            .or(() -> preferredExtension.equals(".tar.gz") ? Optional.empty() : findLinuxAsset(assets, profile, ".tar.gz"))
            .or(() -> findLinuxAsset(assets, profile, ".zip"))
            .or(() -> selectJavaZip(assets));
    }

    private String preferredLinuxExtension(PlatformProfile profile) {
        if (profile.linuxMatches("arch", "manjaro")) {
            return ".pkg.tar.zst";
        }
        if (profile.linuxMatches("debian", "ubuntu", "linuxmint", "pop")) {
            return ".deb";
        }
        if (profile.linuxMatches("fedora", "rhel", "centos", "rocky", "alma", "opensuse", "suse")) {
            return ".rpm";
        }
        return ".tar.gz";
    }

    private Optional<UpdateAsset> findAsset(
        List<UpdateAsset> assets,
        PlatformProfile profile,
        String platformToken,
        String extension
    ) {
        return assets.stream()
            .filter(asset -> {
                String name = normalizedName(asset);
                return name.contains(platformToken)
                    && name.endsWith(extension)
                    && matchesArchitecture(name, profile);
            })
            .findFirst();
    }

    private Optional<UpdateAsset> findLinuxAsset(
        List<UpdateAsset> assets,
        PlatformProfile profile,
        String extension
    ) {
        return assets.stream()
            .filter(asset -> {
                String name = normalizedName(asset);
                return isLinuxAssetName(name)
                    && name.endsWith(extension)
                    && matchesArchitecture(name, profile);
            })
            .findFirst();
    }

    private Optional<UpdateAsset> selectJavaZip(List<UpdateAsset> assets) {
        return assets.stream()
            .filter(asset -> {
                String name = normalizedName(asset);
                return name.startsWith("kortty-java-") && name.endsWith(".zip");
            })
            .findFirst();
    }

    private boolean isInstallableAsset(UpdateAsset asset) {
        String name = normalizedName(asset);
        return !name.endsWith(".sig")
            && !name.contains("-debug-")
            && !name.contains("docs-diagrams")
            && !name.contains("signing-public");
    }

    private boolean isLinuxAssetName(String name) {
        return name.startsWith("kortty-linux-") || name.endsWith(".pkg.tar.zst");
    }

    private boolean matchesArchitecture(String name, PlatformProfile profile) {
        return profile.architectureTokens().stream().anyMatch(name::contains);
    }

    private String normalizedName(UpdateAsset asset) {
        return asset.name().toLowerCase(Locale.ROOT);
    }
}
