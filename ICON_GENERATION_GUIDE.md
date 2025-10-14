# App Icon Generator Instructions

## Custom Drive Backup App Icon

The app icon design features:
- Blue gradient background representing the cloud/digital theme
- Google Drive-style cloud with the classic Drive triangle logo
- Sync arrows indicating backup functionality
- Folder icon and file dots showing the backup concept

## Generated Icon Sizes Needed:

### Android:
- mipmap-mdpi: 48x48px
- mipmap-hdpi: 72x72px  
- mipmap-xhdpi: 96x96px
- mipmap-xxhdpi: 144x144px
- mipmap-xxxhdpi: 192x192px

### iOS:
- 20x20, 29x29, 40x40, 58x58, 60x60, 76x76, 80x80, 87x87, 120x120, 152x152, 167x167, 180x180, 1024x1024

## How to Generate:

### Option 1: Online Icon Generators
1. Use the SVG file at `assets/app_icon.svg`
2. Upload to services like:
   - https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html
   - https://appicon.co/
   - https://makeappicon.com/

### Option 2: Manual Conversion
1. Open the SVG in any vector graphics editor (Inkscape, Adobe Illustrator, etc.)
2. Export as PNG at the required resolutions
3. Place in the appropriate Android/iOS directories

### Option 3: Flutter Icon Generation Package
Add to pubspec.yaml:
```yaml
dev_dependencies:
  flutter_launcher_icons: ^0.13.1

flutter_icons:
  android: true
  ios: true
  image_path: "assets/app_icon.png"
```

Then run:
```bash
flutter pub get
flutter pub run flutter_launcher_icons:main
```

## Current Icon Locations:
- Android: `android/app/src/main/res/mipmap-*/ic_launcher.png`
- iOS: `ios/Runner/Assets.xcassets/AppIcon.appiconset/`

## Next Steps:
1. Convert the SVG to a 512x512 PNG
2. Use an icon generator to create all required sizes
3. Replace the default Flutter icons with the generated ones
4. Test the app to see the new icon

The design reflects the app's core functionality: backing up files to Google Drive with a professional, modern look that fits well with Material Design guidelines.