# Google Sign-In Error 10 Troubleshooting Guide

## Current Status
- ✅ Package name: `dev.guber.gdrivebackup`
- ✅ SHA-1: `D7:69:AA:73:93:B3:9A:FD:39:26:0B:75:BC:9D:4F:2A:B2:55:E4:58`
- ✅ Test user: `guberm@gmail.com`
- ✅ Scopes added: `drive.file`, `userinfo.profile`
- ❌ Sign-in failing with error code 10

## Error Code 10 means "Developer Error"

This typically indicates a configuration mismatch. Here's the complete checklist:

### 1. Verify OAuth Client Configuration

Go to: https://console.cloud.google.com/apis/credentials

Click on your Android client "GDrive Backup" and verify:
- **Package name**: `dev.guber.gdrivebackup` ✅
- **SHA-1**: `D7:69:AA:73:93:B3:9A:FD:39:26:0B:75:BC:9D:4F:2A:B2:55:E4:58` ✅

### 2. Check if APIs are Enabled

Go to: https://console.cloud.google.com/apis/library

Make sure these are **ENABLED**:
- ✅ Google Drive API
- ✅ People API (for Google Sign-In)

### 3. OAuth Consent Screen

Go to: https://console.cloud.google.com/apis/credentials/consent

Verify:
- **User type**: External ✅
- **Publishing status**: Testing
- **Scopes**:
  - `.../auth/drive.file` ✅
  - `.../auth/userinfo.email`
  - `.../auth/userinfo.profile` ✅
- **Test users**: guberm@gmail.com ✅

### 4. IMPORTANT: Check Support Email

In the OAuth consent screen, make sure:
- **App name** is filled in
- **User support email** is set (must be your email)
- **Developer contact information** has your email

### 5. Wait Time

Google Cloud changes can take **5-30 minutes** to propagate globally. If you just made changes, wait at least 15-20 minutes.

### 6. Clear Device Cache

On your Pixel 9 Pro XL:
1. Settings → Apps → Google Play services
2. Storage & cache → Clear cache
3. Restart phone
4. Try signing in again

### 7. Try Tomorrow

Sometimes OAuth configurations need 24 hours to fully propagate, especially for new projects.

## Alternative: Check API Key Restrictions

1. Go to: https://console.cloud.google.com/apis/credentials
2. Check if you have any API keys
3. If yes, click on the API key
4. Under "Application restrictions":
   - Either set to "None"
   - OR set to "Android apps" and add your package name + SHA-1

## If Still Not Working

The issue might be that your Google Cloud project is brand new. Try:
1. Wait 24 hours for full propagation
2. OR create a new OAuth client ID and use that instead
3. Make sure you're using the correct Google account on your phone (guberm@gmail.com)

## Last Resort: Check Sign-In Account

On your phone:
- Make sure you're signed in with `guberm@gmail.com`
- Go to Settings → Google → Manage your Google Account
- Verify it's the same email you added as a test user
