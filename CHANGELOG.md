## 0.1.1

### Android - QR Scanning Optimization
* **Improved scanning distance**: Increase ImageAnalysis resolution target to 1920×1080 using `ResolutionStrategy` with fallback for better small QR code detection at distance
* **Enhanced auto-focus**: Force `AF_MODE_CONTINUOUS_PICTURE` via `Camera2Interop` for stable continuous auto-focus
* **Faster frame processing**: Remove scan delay throttling, use `@Volatile isProcessing` flag for maximum frame throughput
* **Better image format**: Use `OUTPUT_IMAGE_FORMAT_YUV_420_888` for more efficient ML Kit processing
* **Camera stability**: Replace deprecated `setTargetResolution()` with `ResolutionSelector` to prevent camera configuration timeouts
* **Accurate barcode coordinates**: Send actual camera image dimensions with rotation handling for correct bounding box mapping
* **User interaction**: Add pinch-to-zoom and tap-to-focus support

## 0.1.0
* Fix bug 16kb

## 0.0.4
* Update flutter sdk version to >=3.0.0 <4.0.0

## 0.0.3

* Add back button to scanner overlay (configurable, default enabled)
* Add `showBackButton`, `onBackPressed`, `backButtonIcon`, `backButtonColor`, `backButtonBackgroundColor`, and `backButtonSize` to `ScannerOverlayConfig`
* Improve bottom widget layout in scanner overlay
* Update default values: `cornerLength` (50.0 → 30.0), `showToggleTorchButton` (false → true)

## 0.0.2

* Rename package from `flutter_qrcode` to `lumi_qr_scanner`
* Update all class names: `FlutterQrcode` → `LumiQrScanner`, `FlutterQrcodePlatform` → `LumiQrScannerPlatform`, etc.

## 0.0.1

* Initial release
* Real-time camera scanning with QRScannerView widget
* Image scanning from file path and bytes
* Support for multiple barcode formats (QR Code, Aztec, Code128, EAN13, etc.)
* Camera permission handling
* Scanner controls (torch, pause/resume, switch camera)
* Bounding box and corner points detection
* Value type detection (URL, email, WiFi, phone, etc.)
* Platform support: Android (CameraX + ML Kit), iOS/macOS (AVFoundation + Vision)
* Customizable scanner overlay with ScannerOverlayConfig
  * Title, descriptions, and custom widgets
  * Configurable border colors, width, corner length, and border radius
  * Animated scan line (horizontal or vertical)
  * Toggle torch button with customizable position and styling
