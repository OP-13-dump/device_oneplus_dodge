# Clone Devices specific repos
git clone -b 16.2-los https://github.com/OP-13-dump/device_oneplus_sm8750-common device/oneplus/sm8750-common
git clone -b 16.2 https://github.com/OP-13-dump/kernel_oneplus_sm8750 kernel/oneplus/sm8750 --depth=1
git clone -b 16.2 https://github.com/OP-13-dump/kernel_oneplus_sm8750-modules kernel/oneplus/sm8750-modules
git clone -b 16.2 https://github.com/OP-13-dump/kernel_oneplus_sm8750-devicetrees kernel/oneplus/sm8750-devicetrees
git clone -b 16.2 https://github.com/OP-13-dump/patches.git patches

# Sign keys
git clone -b 16.2 https://github.com/Lunaris-AOSP/vendor_lunaris-priv_keys.git vendor/lunaris-priv/keys

# Forked Audio & Diplay hals
rm -rf hardware/qcom-caf/sm8750/audio/primary-hal ; git clone https://github.com/OP-13-dump/android_hardware_qcom_audio-ar.git -b lineage-23.2-caf-sm8750 hardware/qcom-caf/sm8750/audio/primary-hal
rm -rf hardware/qcom-caf/sm8750/display/core ; git clone https://github.com/OP-13-dump/android_vendor_qcom_opensource_display-core.git -b lineage-23.2-caf-sm8750 hardware/qcom-caf/sm8750/display/core
rm -rf hardware/qcom-caf/sm8750/display/hal ; git clone https://github.com/OP-13-dump/android_hardware_qcom_display hardware/qcom-caf/sm8750/display/hal

echo ""
echo "Select option for dodge tree:"
echo "1) dodge"
echo "2) oscaro"
if [ -c /dev/tty ]; then
    read -p "Enter choice (dodge/oscaro) [default: oscaro]: " choice < /dev/tty
else
    read -p "Enter choice (dodge/oscaro) [default: oscaro]: " choice
fi

case "$choice" in
    [dD]*|1)
        echo "Removing existing hardware/oplus, vendor/oplus/camera, vendor/oneplus, and dolby..."
        rm -rf hardware/oplus vendor/oplus/camera vendor/oneplus hardware/dolby packages/apps/LunarisDolby
        echo "Cloning dodge repos for hardware/oplus, vendor/oplus/camera, vendor/oneplus & dolby..."
        git clone -b 16.2 https://github.com/OP-13-dump/hardware_oplus hardware/oplus
        git clone -b 16.2 https://github.com/OP-13-dump/vendor_oplus_camera.git vendor/oplus/camera
        git clone -b 16.2 https://gitlab.com/NoCache-69/vendor_oplus_camera.git vendor/oplus/camera/camera
        git clone -b 16.2 https://gitlab.com/NoCache-69/proprietary_vendor_oneplus_dodge.git vendor/oneplus/dodge
        git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_sm8750-common vendor/oneplus/sm8750-common
        git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_ir vendor/oneplus/ir
        git clone -b dolby https://gitlab.com/osm1019/proprietary_vendor_oneplus_dolby.git vendor/oneplus/dolby
        git clone -b dolby https://github.com/osm1019/packages_apps_LunarisDolby packages/apps/LunarisDolby
        ;;
    *)
        echo "Skipping dodge hardware/oplus, vendor/oplus/camera, vendor/oneplus & dolby repos."
        ;;
esac

echo ""
if [ -c /dev/tty ]; then
    read -p "Apply source-side patches? (y/n) [default: y]: " patch_choice < /dev/tty
else
    read -p "Apply source-side patches? (y/n) [default: y]: " patch_choice
fi

case "$patch_choice" in
    [yY]*|"")
        PATCH_SCRIPT="patches/apply.sh"
        if [ ! -f "$PATCH_SCRIPT" ]; then
            TOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)"
            PATCH_SCRIPT="$TOP_DIR/patches/apply.sh"
        fi

        if [ -f "$PATCH_SCRIPT" ]; then
            echo "Applying source-side patches..."
            bash "$PATCH_SCRIPT"
        else
            echo "patches/apply.sh not found!"
        fi
        ;;
    *)
        echo "Skipping source-side patches."
        ;;
esac

# Fetch and cherry-pick hardware/lineage/compat commits
(
    if [ -d "hardware/lineage/compat/.git" ]; then
        COMPAT_DIR="hardware/lineage/compat"
    else
        TOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)"
        COMPAT_DIR="${TOP_DIR}/hardware/lineage/compat"
    fi

    if [ ! -d "$COMPAT_DIR/.git" ]; then
        echo "hardware/lineage/compat repository not found, skipping."
    else
        REMOTE_URL="https://github.com/LineageOS/android_hardware_lineage_compat"
        REMOTE_BRANCH="lineage-24.0"
        COMMITS=(
            "56fd9f0ed9a36a80a518d1e22ea17196719bd39d|compat: Adapt Bluetooth audio configurations for V3 callers"
            "07768b889b53278ab70ae0d35e8a3bca0b810691|compat: Provide libui-v32"
            "b4c6b007f02164ba1d814fef50b662c16d61f4da|compat: Also adapt Bluetooth audio configurations for V4 callers"
            "8d1f79edefa48fd848b3e306fc7361fb944041b3|compat: Share Bluetooth audio configuration conversion logic"
            "590c94850e079642136a4577305d02e4174caabc|libcodec2: Shim legacy Codec2 WorkBundle"
            "70abb57ff28a6af13837ad41acfe55d34773bd19|compat: Add legacy Codec2 allocator and block pool wrappers"
        )

        is_commit_applied() {
            local commit="$1"
            local fallback_subject="$2"

            if git -C "$COMPAT_DIR" merge-base --is-ancestor "$commit" HEAD 2>/dev/null; then
                return 0
            fi
            if git -C "$COMPAT_DIR" log --grep="$commit" -n 1 --format="%H" HEAD 2>/dev/null | grep -q .; then
                return 0
            fi
            local subject
            subject=$(git -C "$COMPAT_DIR" log -1 --format="%s" "$commit" 2>/dev/null)
            [ -z "$subject" ] && subject="$fallback_subject"
            if [ -n "$subject" ] && git -C "$COMPAT_DIR" log --fixed-strings --grep="$subject" -n 1 --format="%H" HEAD 2>/dev/null | grep -q .; then
                return 0
            fi
            return 1
        }

        NEED_FETCH=0
        for item in "${COMMITS[@]}"; do
            commit="${item%%|*}"
            subject="${item#*|}"
            if ! is_commit_applied "$commit" "$subject"; then
                NEED_FETCH=1
                break
            fi
        done

        if [ "$NEED_FETCH" -eq 1 ]; then
            echo "Fetching hardware/lineage/compat commits..."
            git -C "$COMPAT_DIR" fetch "$REMOTE_URL" "$REMOTE_BRANCH"

            for item in "${COMMITS[@]}"; do
                commit="${item%%|*}"
                subject="${item#*|}"
                if is_commit_applied "$commit" "$subject"; then
                    echo "hardware/lineage/compat: Commit $commit already applied, skipping."
                else
                    echo "hardware/lineage/compat: Cherry-picking $commit..."
                    if ! git -C "$COMPAT_DIR" cherry-pick -x --no-edit "$commit"; then
                        echo "hardware/lineage/compat: Failed to cherry-pick $commit! Aborting cherry-pick."
                        git -C "$COMPAT_DIR" cherry-pick --abort 2>/dev/null
                    fi
                fi
            done
        else
            echo "hardware/lineage/compat: Required commits already applied."
        fi
    fi
)

# Revert specific commits in bionic
(
    if [ -d "bionic/.git" ]; then
        BIONIC_DIR="bionic"
    else
        TOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)"
        BIONIC_DIR="${TOP_DIR}/bionic"
    fi

    if [ ! -d "$BIONIC_DIR/.git" ]; then
        echo "bionic repository not found, skipping."
    else
        if [ "$(git -C "$BIONIC_DIR" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
            echo "Unshallowing bionic repository..."
            git -C "$BIONIC_DIR" fetch --unshallow
        fi

        REVERT_COMMITS=(
            "f89aff25882bcfc16f9407a994b401b1902b47d0|Revert \"[dual_allocator] Fix -Wunused-function when display jemalloc is off (#2)\""
            "deed08e37ee5f654b77b007d3167077deba47ba6|Revert \"[dual_allocator] adding soong flag for display stack jemalloc\""
            "eef385cbce2a38e51be60a2da9ebbb1263ffd967|Revert \"[dual_allocator] using jemalloc for display stack\""
        )

        is_revert_applied() {
            local commit="$1"
            local fallback_revert_subject="$2"

            if ! git -C "$BIONIC_DIR" merge-base --is-ancestor "$commit" HEAD 2>/dev/null; then
                return 0
            fi
            if git -C "$BIONIC_DIR" log --grep="$commit" -n 1 --format="%H" HEAD 2>/dev/null | grep -q .; then
                return 0
            fi
            local orig_subject
            orig_subject=$(git -C "$BIONIC_DIR" log -1 --format="%s" "$commit" 2>/dev/null)
            local revert_subject=""
            if [ -n "$orig_subject" ]; then
                revert_subject="Revert \"$orig_subject\""
            else
                revert_subject="$fallback_revert_subject"
            fi
            if [ -n "$revert_subject" ] && git -C "$BIONIC_DIR" log --fixed-strings --grep="$revert_subject" -n 1 --format="%H" HEAD 2>/dev/null | grep -q .; then
                return 0
            fi
            return 1
        }

        NEED_REVERT=0
        for item in "${REVERT_COMMITS[@]}"; do
            commit="${item%%|*}"
            subject="${item#*|}"
            if ! is_revert_applied "$commit" "$subject"; then
                NEED_REVERT=1
                break
            fi
        done

        if [ "$NEED_REVERT" -eq 1 ]; then
            for item in "${REVERT_COMMITS[@]}"; do
                commit="${item%%|*}"
                subject="${item#*|}"
                if is_revert_applied "$commit" "$subject"; then
                    echo "bionic: Commit $commit already reverted, skipping."
                else
                    echo "bionic: Reverting $commit..."
                    if ! git -C "$BIONIC_DIR" revert --no-edit "$commit"; then
                        echo "bionic: Failed to revert $commit! Aborting revert."
                        git -C "$BIONIC_DIR" revert --abort 2>/dev/null
                    fi
                fi
            done
        else
            echo "bionic: Required commits already reverted."
        fi
    fi
)

