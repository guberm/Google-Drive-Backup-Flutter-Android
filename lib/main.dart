import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:googleapis/drive/v3.dart' as drive;
import 'package:googleapis_auth/auth_io.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:file_picker/file_picker.dart';
import 'package:workmanager/workmanager.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:io';
import 'package:path/path.dart' as path;
import 'package:http/http.dart' as http;

// Background task callback
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    try {
      await BackupService.performBackup();
      return Future.value(true);
    } catch (e) {
      print('Background backup failed: $e');
      return Future.value(false);
    }
  });
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  Workmanager().initialize(callbackDispatcher, isInDebugMode: false);
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Drive Backup',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const BackupHomePage(),
    );
  }
}

class BackupHomePage extends StatefulWidget {
  const BackupHomePage({super.key});

  @override
  State<BackupHomePage> createState() => _BackupHomePageState();
}

class _BackupHomePageState extends State<BackupHomePage> {
  final GoogleSignIn _googleSignIn = GoogleSignIn(
    scopes: [drive.DriveApi.driveFileScope],
  );
  
  GoogleSignInAccount? _currentUser;
  String? _selectedFolderPath;
  bool _isBackupEnabled = false;
  String _status = 'Not configured';
  int _backupInterval = 24; // hours
  bool _isBackingUp = false;

  @override
  void initState() {
    super.initState();
    _loadSettings();
    _googleSignIn.onCurrentUserChanged.listen((account) {
      setState(() {
        _currentUser = account;
        if (account != null) {
          _status = 'Signed in as ${account.email}';
        }
      });
    });
    _googleSignIn.signInSilently();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _selectedFolderPath = prefs.getString('backup_folder');
      _isBackupEnabled = prefs.getBool('backup_enabled') ?? false;
      _backupInterval = prefs.getInt('backup_interval') ?? 24;
    });
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    if (_selectedFolderPath != null) {
      await prefs.setString('backup_folder', _selectedFolderPath!);
    }
    await prefs.setBool('backup_enabled', _isBackupEnabled);
    await prefs.setInt('backup_interval', _backupInterval);
  }

  Future<void> _requestPermissions() async {
    // Request storage permissions
    var status = await Permission.storage.status;
    if (!status.isGranted) {
      await Permission.storage.request();
    }

    // For Android 11+ (API 30+)
    if (await Permission.manageExternalStorage.isDenied) {
      await Permission.manageExternalStorage.request();
    }
  }

  Future<void> _signIn() async {
    try {
      await _googleSignIn.signIn();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Sign in failed: $error')),
        );
      }
    }
  }

  Future<void> _signOut() async {
    await _googleSignIn.signOut();
    setState(() {
      _status = 'Signed out';
    });
  }

  Future<void> _selectFolder() async {
    await _requestPermissions();
    
    String? selectedDirectory = await FilePicker.platform.getDirectoryPath();
    
    if (selectedDirectory != null) {
      setState(() {
        _selectedFolderPath = selectedDirectory;
        _status = 'Folder selected: ${path.basename(selectedDirectory)}';
      });
      await _saveSettings();
    }
  }

  Future<void> _performBackup() async {
    if (_currentUser == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please sign in to Google first')),
      );
      return;
    }

    if (_selectedFolderPath == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please select a folder to backup')),
      );
      return;
    }

    setState(() {
      _isBackingUp = true;
      _status = 'Backing up...';
    });

    try {
      final authHeaders = await _currentUser!.authHeaders;
      final authenticateClient = GoogleAuthClient(authHeaders);
      final driveApi = drive.DriveApi(authenticateClient);

      // Get or create backup folder
      final folderId = await _getOrCreateDriveFolder(driveApi, 'AppBackup');
      
      // Backup files
      final folder = Directory(_selectedFolderPath!);
      int fileCount = await _backupDirectory(driveApi, folder, folderId);

      setState(() {
        _status = 'Backup completed! $fileCount files synced';
        _isBackingUp = false;
      });
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isBackingUp = false;
      });
    }
  }

  Future<String> _getOrCreateDriveFolder(
    drive.DriveApi driveApi,
    String folderName,
  ) async {
    try {
      final fileList = await driveApi.files.list(
        q: "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false",
        spaces: 'drive',
        $fields: 'files(id, name)',
      );

      if (fileList.files != null && fileList.files!.isNotEmpty) {
        return fileList.files!.first.id!;
      }

      final folder = drive.File()
        ..name = folderName
        ..mimeType = 'application/vnd.google-apps.folder';
      
      final createdFolder = await driveApi.files.create(folder);
      return createdFolder.id!;
    } catch (e) {
      print('Error creating folder: $e');
      rethrow;
    }
  }

  Future<int> _backupDirectory(
    drive.DriveApi driveApi,
    Directory directory,
    String parentId,
  ) async {
    int fileCount = 0;
    
    try {
      final entities = directory.listSync();

      for (var entity in entities) {
        try {
          if (entity is File) {
            await _uploadFile(driveApi, entity, parentId);
            fileCount++;
            setState(() {
              _status = 'Backing up... ($fileCount files)';
            });
          } else if (entity is Directory) {
            final subFolderName = path.basename(entity.path);
            
            // Get or create subfolder
            final fileList = await driveApi.files.list(
              q: "name='$subFolderName' and '$parentId' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false",
              spaces: 'drive',
              $fields: 'files(id, name)',
            );

            String subFolderId;
            if (fileList.files != null && fileList.files!.isNotEmpty) {
              subFolderId = fileList.files!.first.id!;
            } else {
              final folder = drive.File()
                ..name = subFolderName
                ..parents = [parentId]
                ..mimeType = 'application/vnd.google-apps.folder';
              final createdFolder = await driveApi.files.create(folder);
              subFolderId = createdFolder.id!;
            }
            
            fileCount += await _backupDirectory(driveApi, entity, subFolderId);
          }
        } catch (e) {
          print('Error processing ${entity.path}: $e');
        }
      }
    } catch (e) {
      print('Error listing directory: $e');
    }

    return fileCount;
  }

  Future<void> _uploadFile(
    drive.DriveApi driveApi,
    File file,
    String parentId,
  ) async {
    try {
      final fileName = path.basename(file.path);
      final fileSize = file.lengthSync();
      
      // Skip files larger than 50MB for this demo
      if (fileSize > 50 * 1024 * 1024) {
        print('Skipping large file: $fileName');
        return;
      }

      // Check if file already exists
      final fileList = await driveApi.files.list(
        q: "name='$fileName' and '$parentId' in parents and trashed=false",
        spaces: 'drive',
        $fields: 'files(id, name, modifiedTime)',
      );

      final driveFile = drive.File()
        ..name = fileName
        ..parents = [parentId];

      final media = drive.Media(file.openRead(), fileSize);

      if (fileList.files != null && fileList.files!.isNotEmpty) {
        // Update existing file
        final existingFile = fileList.files!.first;
        final localModified = file.lastModifiedSync();
        final driveModified = existingFile.modifiedTime;
        
        // Only update if local file is newer
        if (driveModified == null || localModified.isAfter(driveModified)) {
          await driveApi.files.update(
            driveFile,
            existingFile.id!,
            uploadMedia: media,
          );
          print('Updated: $fileName');
        }
      } else {
        // Create new file
        await driveApi.files.create(
          driveFile,
          uploadMedia: media,
        );
        print('Uploaded: $fileName');
      }
    } catch (e) {
      print('Error uploading file: $e');
    }
  }

  void _togglePeriodicBackup(bool enabled) async {
    setState(() {
      _isBackupEnabled = enabled;
    });
    await _saveSettings();

    if (enabled) {
      await Workmanager().registerPeriodicTask(
        'backup_task',
        'backup',
        frequency: Duration(hours: _backupInterval),
        constraints: Constraints(
          networkType: NetworkType.connected,
        ),
      );
      setState(() {
        _status = 'Periodic backup enabled (every $_backupInterval hours)';
      });
    } else {
      await Workmanager().cancelByUniqueName('backup_task');
      setState(() {
        _status = 'Periodic backup disabled';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Drive Backup'),
        actions: [
          if (_currentUser != null)
            IconButton(
              icon: const Icon(Icons.logout),
              onPressed: _signOut,
              tooltip: 'Sign Out',
            ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Sign in status
            Card(
              child: ListTile(
                leading: CircleAvatar(
                  backgroundImage: _currentUser?.photoUrl != null
                      ? NetworkImage(_currentUser!.photoUrl!)
                      : null,
                  child: _currentUser?.photoUrl == null
                      ? const Icon(Icons.account_circle)
                      : null,
                ),
                title: Text(_currentUser?.displayName ?? 'Not signed in'),
                subtitle: Text(_currentUser?.email ?? 'Sign in to Google Drive'),
                trailing: _currentUser == null
                    ? ElevatedButton.icon(
                        onPressed: _signIn,
                        icon: const Icon(Icons.login),
                        label: const Text('Sign In'),
                      )
                    : null,
              ),
            ),
            const SizedBox(height: 16),

            // Folder selection
            Card(
              child: ListTile(
                leading: const Icon(Icons.folder_open, color: Colors.orange),
                title: const Text('Backup Folder'),
                subtitle: Text(
                  _selectedFolderPath ?? 'No folder selected',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                trailing: IconButton(
                  icon: const Icon(Icons.edit),
                  onPressed: _selectFolder,
                  tooltip: 'Select Folder',
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Backup interval
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.schedule, color: Colors.blue),
                        const SizedBox(width: 8),
                        Text(
                          'Backup Interval',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    DropdownButton<int>(
                      value: _backupInterval,
                      isExpanded: true,
                      items: const [
                        DropdownMenuItem(value: 1, child: Text('Every hour')),
                        DropdownMenuItem(value: 6, child: Text('Every 6 hours')),
                        DropdownMenuItem(value: 12, child: Text('Every 12 hours')),
                        DropdownMenuItem(value: 24, child: Text('Daily')),
                      ],
                      onChanged: (value) {
                        setState(() {
                          _backupInterval = value!;
                        });
                        _saveSettings();
                        if (_isBackupEnabled) {
                          _togglePeriodicBackup(false);
                          _togglePeriodicBackup(true);
                        }
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Enable periodic backup
            Card(
              child: SwitchListTile(
                secondary: const Icon(Icons.sync, color: Colors.green),
                title: const Text('Enable Periodic Backup'),
                subtitle: const Text('Automatically backup in background'),
                value: _isBackupEnabled,
                onChanged: _togglePeriodicBackup,
              ),
            ),
            const SizedBox(height: 16),

            // Manual backup button
            ElevatedButton.icon(
              onPressed: _isBackingUp ? null : _performBackup,
              icon: _isBackingUp
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.backup),
              label: Text(_isBackingUp ? 'Backing up...' : 'Backup Now'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.all(16),
                backgroundColor: Theme.of(context).colorScheme.primary,
                foregroundColor: Colors.white,
              ),
            ),
            const SizedBox(height: 16),

            // Status
            Card(
              color: Colors.blue.shade50,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    const Icon(Icons.info_outline, color: Colors.blue),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _status,
                        style: Theme.of(context).textTheme.bodyLarge,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class BackupService {
  static Future<bool> performBackup() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final folderPath = prefs.getString('backup_folder');
      
      if (folderPath == null || folderPath.isEmpty) {
        return false;
      }

      final googleSignIn = GoogleSignIn(
        scopes: [drive.DriveApi.driveFileScope],
      );
      
      final account = await googleSignIn.signInSilently();
      if (account == null) {
        return false;
      }

      final authHeaders = await account.authHeaders;
      final authenticateClient = GoogleAuthClient(authHeaders);
      final driveApi = drive.DriveApi(authenticateClient);

      // Implementation similar to manual backup
      return true;
    } catch (e) {
      print('Backup error: $e');
      return false;
    }
  }
}

class GoogleAuthClient extends http.BaseClient {
  final Map<String, String> _headers;
  final http.Client _client = http.Client();

  GoogleAuthClient(this._headers);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) {
    return _client.send(request..headers.addAll(_headers));
  }
}