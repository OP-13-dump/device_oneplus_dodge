git clone -b 16.2 https://github.com/OP-13-dump/device_oneplus_sm8750-common device/oneplus/sm8750-common
git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_dodge vendor/oneplus/dodge
git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_sm8750-common vendor/oneplus/sm8750-common
git clone -b 16.2 https://github.com/OP-13-dump/kernel_oneplus_sm8750 kernel/oneplus/sm8750 --depth=1
git clone -b 16.2 https://github.com/OP-13-dump/kernel_oneplus_sm8750-modules kernel/oneplus/sm8750-modules
git clone -b 16.2 https://github.com/OP-13-dump/kernel_oneplus_sm8750-devicetrees kernel/oneplus/sm8750-devicetrees
git clone -b 16.2 https://github.com/OP-13-dump/vendor_oneplus_ir vendor/oneplus/ir
git clone -b 16.2 https://github.com/Lunaris-AOSP/vendor_lunaris-priv_keys.git vendor/lunaris-priv/keys
git clone -b dolby https://gitlab.com/osm1019/proprietary_vendor_oneplus_dolby.git vendor/oneplus/dolby
git clone -b dolby https://github.com/osm1019/packages_apps_LunarisDolby packages/apps/LunarisDolby
git clone -b 16.2 https://gitlab.com/osm1019/vendor_oplus_fusionlight.git vendor/oplus/fusionlight

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
        echo "Removing existing hardware/oplus and vendor/oplus/camera..."
        rm -rf hardware/oplus vendor/oplus/camera
        echo "Cloning dodge repos for hardware/oplus & vendor/oplus/camera..."
        git clone -b 16.2 https://github.com/OP-13-dump/hardware_oplus hardware/oplus
        git clone -b 16.2 https://github.com/dodgecameraport/vendor_oplus_camera.git vendor/oplus/camera
        git clone -b 16.2 https://gitlab.com/NoCache-69/dodge_vendor_oplus_camera.git vendor/oplus/camera/camera
        ;;
    *)
        echo "Skipping dodge hardware/oplus & vendor/oplus/camera repos."
        ;;
esac
