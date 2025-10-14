# Drive Backup App

An automatic folder backup application for Google Drive built with Flutter. This app allows users to automatically sync and backup selected folders to their Google Drive account with configurable schedules.

## Features

- 🔐 **Google Sign-In Integration** - Secure authentication with Google accounts
- 📁 **Folder Selection** - Choose specific folders to backup
- ⏰ **Automatic Scheduling** - Set backup intervals (hourly, daily, weekly)
- 🔄 **Background Sync** - Continues backing up even when app is closed
- 📱 **Cross-Platform** - Works on Android, iOS, Windows, macOS, and Linux
- 🔍 **File Management** - View and manage backed up files
- 📊 **Backup Status** - Monitor backup progress and history

## Screenshots

*Screenshots coming soon...*

## Getting Started

### Prerequisites

- Flutter SDK (>= 3.0.0)
- Dart SDK
- Google Cloud Console project with Drive API enabled
- Valid Google OAuth credentials

### Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/guberm/drive_backup_app.git
   cd drive_backup_app
   ```

2. **Install dependencies:**
   ```bash
   flutter pub get
   ```

3. **Configure Google Sign-In:**
   
   Follow the detailed setup instructions in [`GOOGLE_SIGNIN_SETUP.md`](GOOGLE_SIGNIN_SETUP.md) to configure OAuth credentials.

4. **Run the app:**
   ```bash
   flutter run
   ```

## Configuration

### Google Drive API Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing one
3. Enable Google Drive API
4. Create OAuth 2.0 credentials (both Android and Web client)
5. Update the app configuration with your client IDs

See [`GOOGLE_SIGNIN_SETUP.md`](GOOGLE_SIGNIN_SETUP.md) for detailed instructions.

### Permissions

The app requires the following permissions:

- **Storage Access** - To read files from selected folders
- **Internet Access** - To communicate with Google Drive API
- **Background Processing** - To perform scheduled backups

## Usage

1. **Sign in** with your Google account
2. **Select folders** you want to backup
3. **Configure schedule** for automatic backups
4. **Monitor progress** in the app dashboard
5. **Manage files** through the file browser

## Architecture

- **Frontend**: Flutter/Dart
- **Authentication**: Google Sign-In
- **Cloud Storage**: Google Drive API v3
- **Background Tasks**: WorkManager
- **Local Storage**: SharedPreferences
- **File System**: path_provider, file_picker

## Dependencies

Key packages used in this project:

- `googleapis`: Google Drive API integration
- `google_sign_in`: Google authentication
- `workmanager`: Background task scheduling
- `file_picker`: File and folder selection
- `permission_handler`: Runtime permissions
- `path_provider`: File system paths

## Troubleshooting

Common issues and solutions:

- **Google Sign-In Error (Code 10)**: See [`TROUBLESHOOTING_GOOGLE_SIGNIN.md`](TROUBLESHOOTING_GOOGLE_SIGNIN.md)
- **OAuth Configuration**: Check setup in [`GOOGLE_SIGNIN_SETUP.md`](GOOGLE_SIGNIN_SETUP.md)
- **Backup not working**: Verify Google Drive API permissions
- **File access denied**: Ensure storage permissions are granted

See [`ERROR_10_TROUBLESHOOTING.md`](ERROR_10_TROUBLESHOOTING.md) for additional troubleshooting.

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

If you encounter any issues or have questions:

1. Check the troubleshooting guides in the docs
2. Search existing [GitHub Issues](https://github.com/guberm/drive_backup_app/issues)
3. Create a new issue with detailed information

## Acknowledgments

- Flutter team for the amazing framework
- Google for Drive API and authentication services
- Open source community for the fantastic packages
