# Google Sign-In Setup Instructions

Your app is now correctly configured with package name: `dev.guber.gdrivebackup`

## Current Status
✅ Android OAuth Client configured with SHA-1: `D7:69:AA:73:93:B3:9A:FD:39:26:0B:75:BC:9D:4F:2A:B2:55:E4:58`
✅ Package name matches: `dev.guber.gdrivebackup`
✅ Test user added: `guberm@gmail.com`
✅ App is in Testing mode (ready for testing)

## Current Error
❌ **Error Code 10**: `PlatformException(sign_in_failed, ApiException: 10)`
This means the **Web Client ID** is missing from the Flutter app configuration.

## What's Missing
You need to:
1. **Create a Web Client ID** in Google Cloud Console
2. **Download google-services.json** file
3. **Add Web Client ID** to Flutter app

### Steps to Fix Google Sign-In:

#### Step 1: Create Web Client ID
1. Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
2. Select your project
3. Click "**+ CREATE CREDENTIALS**" → "**OAuth client ID**"
4. Choose "**Web application**" as the application type
5. Give it a name (e.g., "GDrive Backup Web Client")
6. You don't need to add any redirect URIs for this
7. Click "**CREATE**"
8. **Copy the Client ID** (it will look like: `xxxxx-xxxxx.apps.googleusercontent.com`)

#### Step 2: Download google-services.json
1. In Google Cloud Console, go to your Android OAuth client
2. Click the **Download** button (📥) next to your Android client
3. Save the `google-services.json` file
4. Place it in: `android/app/google-services.json`

### Update the App:

Once you have the Web Client ID, update `lib/main.dart` line 58:

```dart
final GoogleSignIn _googleSignIn = GoogleSignIn(
  scopes: [drive.DriveApi.driveFileScope],
  serverClientId: 'YOUR-WEB-CLIENT-ID-HERE.apps.googleusercontent.com',
);
```

### Enable Required APIs:

Make sure these APIs are enabled in your Google Cloud project:
- Google Drive API
- Google Sign-In API (People API)

Go to: https://console.cloud.google.com/apis/library
Search for and enable:
1. **Google Drive API**
2. **Google People API**

## Testing

After adding the `serverClientId`, rebuild and run:
```bash
flutter clean
flutter run
```

The Google Sign-In should now work properly!
