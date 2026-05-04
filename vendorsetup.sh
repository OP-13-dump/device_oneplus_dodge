# Clone common trees
git clone --depth=1 https://github.com/OP-13-dump/device_oneplus_sm8750-common.git -b 16 device/oneplus/sm8750-common
git clone --depth=1 https://github.com/OP-13-dump/vendor_oneplus_sm8750-common.git -b 16 vendor/oneplus/sm8750-common

# Clone vendor tree
git clone --depth=1 https://github.com/OP-13-dump/vendor_oneplus_dodge.git -b 16 vendor/oneplus/dodge

# Clone kernel trees
git clone --depth=1 https://github.com/OP-13-dump/kernel_oneplus_sm8750.git -b 16 kernel/oneplus/sm8750
git clone --depth=1 https://github.com/OP-13-dump/kernel_oneplus_sm8750-modules.git -b 16 kernel/oneplus/sm8750-modules
git clone --depth=1 https://github.com/OP-13-dump/kernel_oneplus_sm8750-devicetrees.git -b 16 kernel/oneplus/sm8750-devicetrees

# Clone hardware oplus
rm -rf hardware/oplus
git clone https://github.com/OP-13-dump/hardware_oplus.git -b 16 hardware/oplus

# Setup submodule
cd kernel/oneplus/sm8750
git submodule init
git submodule update
cd ../../..

# Clone sign keys
git clone https://github.com/ProjectInfinity-X/vendor_infinity-priv_keys.git -b 16 vendor/infinity-priv/keys
