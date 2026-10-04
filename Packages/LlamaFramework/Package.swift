// swift-tools-version:5.9
//
// Локальный Swift Package, который подключает готовый llama.xcframework
// из официального релиза llama.cpp (ggml-org/llama.cpp, тег b11380).
//
// Xcode скачивает архив ОДИН РАЗ при первой сборке на Mac (нужен интернет
// только на этапе сборки). Само приложение на iPhone в сеть не ходит.
//
// Если хотите обновить llama.cpp: замените тег в URL и пересчитайте checksum:
//   swift package compute-checksum llama-bXXXX-xcframework.zip
//
// Важно: в официальном релизе есть только срезы iOS-устройства (arm64) и macOS.
// Симулятора там нет — запускайте на реальном iPhone (или соберите
// xcframework сами: ./build-xcframework.sh ios-sim ios-device).

import PackageDescription

let package = Package(
    name: "LlamaFramework",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(name: "LlamaFramework", targets: ["llama"])
    ],
    targets: [
        .binaryTarget(
            name: "llama",
            url: "https://github.com/ggml-org/llama.cpp/releases/download/b11380/llama-b11380-xcframework.zip",
            checksum: "2996a031d09314bc268c9494d1fd06cfdbbf25e525d29f88ca97fe7ea9337cce"
        )
    ]
)
