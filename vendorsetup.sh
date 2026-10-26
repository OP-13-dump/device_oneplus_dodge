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
if [ -c /dev/tty ]; then
    read -p "Building for dodge?: (y/n) [default: n]: " choice < /dev/tty
else
    read -p "Building for dodge?: (y/n) [default: n]: " choice
fi

case "$choice" in
    [yY]*|1)
        echo "Removing existing Dolby,hardware/oplus and vendor/oplus/camera..."
        rm -rf hardware/oplus vendor/oplus/camera hardware/dolby vendor/oneplus/
        echo "Cloning dodge repos for Dolby,hardware/oplus, vendor/oplus/camera, vendor/oneplus/*..."
	git clone -b 16.2 https://gitlab.com/NoCache-69/proprietary_vendor_oneplus_dodge.git vendor/oneplus/dodge
	git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_sm8750-common vendor/oneplus/sm8750-common
	git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_ir vendor/oneplus/ir
        git clone -b 16.2 https://github.com/OP-13-dump/hardware_oplus hardware/oplus
	git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_dolby.git vendor/oneplus/dolby
	git clone -b 16.2 https://github.com/OP-13-dump/packages_apps_LunarisDolby packages/apps/LunarisDolby
        git clone -b 16.2 https://github.com/OP-13-dump/vendor_oplus_camera.git vendor/oplus/camera
        git clone -b 16.2 https://gitlab.com/NoCache-69/vendor_oplus_camera.git vendor/oplus/camera/camera
        ;;
    *)
        echo "Skipping dodge hardware/oplus & vendor/ repos."
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
