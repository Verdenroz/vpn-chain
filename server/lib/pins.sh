#!/usr/bin/env bash
# shellcheck disable=SC2034 # read by the sourcing scripts
# Versions the server and its tests are pinned to. Sourced.
# Bump XRAY_VERSION only with the e2e matrix (server/tests/matrix.sh) green.
XRAY_VERSION=v26.3.27
XRAY_SHA256_amd64=23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae
XRAY_SHA256_arm64=4d30283ae614e3057f730f67cd088a42be6fdf91f8639d82cb69e48cde80413c

# The Android libbox AAR (gradle/libs.versions.toml) and the desktop sing-box.
SINGBOX_VERSIONS_DEFAULT="1.13.14 1.14.3"
SINGBOX_SHA256_1_13_14_amd64=f48703461a15476951ac4967cdad339d986f4b8096b4eb3ff0829a500502d697
SINGBOX_SHA256_1_13_14_arm64=4742df6a4314e8ecc41736849fca6d73b8f9e91b6e8b06ee794ff17ba180579e
SINGBOX_SHA256_1_14_3_amd64=e2bdf179c15a3652955dc44867e33ca0965c9c9b91b34267dcc5a5d639a5feee
SINGBOX_SHA256_1_14_3_arm64=29dae24d76bea3bc62d07dd3e60cfc12003de7debbd6b768df9be9c68360174e
