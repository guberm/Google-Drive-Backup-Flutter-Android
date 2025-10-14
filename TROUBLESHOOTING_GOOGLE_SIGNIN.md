# Google Sign-In Troubleshooting

## Error: PlatformException(sign_in_failed, ApiException: 10)

### What This Means
Error code 10 indicates a **missing or incorrect OAuth Web Client ID** configuration in your Flutter app.

### Current Status ✅
Based on your Google Cloud Console:
- ✅ Android OAuth Client is properly configured
- ✅ Package name matches: `dev.guber.gdrivebackup`
- ✅ SHA-1 certificate is correctly set
- ✅ Test user `guberm@gmail.com` is added
- ✅ App is in Testing mode (ready for testing)
- ✅ Google Drive API is enabled

### What's Missing ❌
- ❌ **Web Client ID** is not created in Google Cloud Console
- ❌ **Web Client ID** is not added to Flutter app configuration
- ❌ `google-services.json` file may be missing

## Quick Fix Steps

### 1. Create Web Client ID
1. Go to [Google Cloud Console Credentials](https://console.cloud.google.com/apis/credentials)
2. Click **"+ CREATE CREDENTIALS"** → **"OAuth client ID"**
3. Choose **"Web application"**
4. Name: `GDrive Backup Web Client`
5. Leave redirect URIs empty
6. Click **"CREATE"**
7. **COPY THE CLIENT ID** (format: `xxxxx-xxxxx.apps.googleusercontent.com`)

### 2. Download google-services.json
1. In the same Credentials page, find your **Android** client
2. Click the **Download** button (📥)
3. Save as `android/app/google-services.json`

### 3. Update Flutter Code
In `lib/main.dart`, replace both GoogleSignIn instances:

```dart
final GoogleSignIn _googleSignIn = GoogleSignIn(
  scopes: [drive.DriveApi.driveFileScope],
  serverClientId: 'YOUR-WEB-CLIENT-ID-HERE.apps.googleusercontent.com', // Add this line
);
```

### 4. Clean and Rebuild
```bash
flutter clean
flutter pub get
flutter run
```

## Expected Result
After these changes, you should see:
- ✅ "Sign In" button works without errors
- ✅ Google account picker appears
- ✅ Successful authentication
- ✅ "Signed in as: guberm@gmail.com" appears

## Still Having Issues?

### Double-check:
1. **Web Client ID** is correctly copied (no extra spaces)
2. **google-services.json** is in the right location: `android/app/google-services.json`
3. **Package name** in google-services.json matches: `dev.guber.gdrivebackup`
4. **Internet permission** is enabled in `android/app/src/main/AndroidManifest.xml`

### Common Mistakes:
- Using Android Client ID instead of Web Client ID
- Missing `serverClientId` parameter
- Wrong file location for google-services.json
- Not rebuilding after changes

### If Still Failing:
1. Check Android logs: `flutter logs`
2. Verify all APIs are enabled in Google Cloud Console
3. Ensure your Google account is added as a test user
4. Try signing in from a different Google account

---

💡 **Tip**: The Web Client ID is specifically needed for Flutter apps, even on Android. The Android Client ID alone is not sufficient.