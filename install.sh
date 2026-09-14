#!/bin/sh
set -eu

main() {
    case "$(uname -s)" in
        Darwin) platform=darwin ;;
        Linux) platform=linux ;;
        *) printf '%s\n' 'Arrodes supports macOS and glibc-based Linux.' >&2; exit 1 ;;
    esac
    case "$(uname -m)" in
        arm64|aarch64) arch=arm64 ;;
        x86_64|amd64) arch=x64 ;;
        *) printf '%s\n' 'Arrodes requires an ARM64 or x64 system.' >&2; exit 1 ;;
    esac
    if command -v sha256sum >/dev/null 2>&1; then
        checksum=sha256sum
    elif command -v shasum >/dev/null 2>&1; then
        checksum=shasum
    else
        printf '%s\n' 'Install sha256sum or shasum before installing Arrodes.' >&2
        exit 1
    fi

    repository=https://github.com/DeadMeme5441/arrodes
    release=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$repository/releases/latest")
    version=${release##*/}
    case "$version" in
        v[0-9]*) ;;
        *) printf '%s\n' 'Could not resolve the latest Arrodes release.' >&2; exit 1 ;;
    esac
    asset=arrodes-$platform-$arch
    install_dir=$HOME/.local/bin
    mkdir -p "$install_dir"
    if [ -d "$install_dir/arrodes" ]; then
        printf '%s\n' 'Installation target is a directory; refusing to replace it.' >&2
        exit 1
    fi
    work=$(mktemp -d "$install_dir/.arrodes-install.XXXXXX")
    trap 'rm -rf "$work"' EXIT
    trap 'exit 1' HUP INT TERM

    printf 'Downloading Arrodes %s (%s/%s)…\n' "$version" "$platform" "$arch"
    url=$repository/releases/download/$version/$asset
    curl -fsSL "$url" -o "$work/$asset"
    curl -fsSL "$url.sha256" -o "$work/checksum"
    read -r expected filename < "$work/checksum"
    if [ "$filename" != "$asset" ]; then
        printf '%s\n' 'Release checksum names an unexpected file.' >&2
        exit 1
    fi
    if [ "$checksum" = sha256sum ]; then
        actual=$(sha256sum "$work/$asset")
    else
        actual=$(shasum -a 256 "$work/$asset")
    fi
    if [ "${actual%% *}" != "$expected" ]; then
        printf '%s\n' 'Release checksum mismatch; existing installation left unchanged.' >&2
        exit 1
    fi
    chmod 755 "$work/$asset"
    mv -f "$work/$asset" "$install_dir/arrodes"
    printf 'Installed Arrodes %s to %s/arrodes\n' "$version" "$install_dir"
    case ":$PATH:" in
        *":$install_dir:"*) printf '%s\n' 'Run arrodes inside a project to get started.' ;;
        *) printf '%s\n' 'Add it to this terminal’s PATH, then run arrodes:' '  export PATH="$HOME/.local/bin:$PATH"' ;;
    esac
}

main "$@"
