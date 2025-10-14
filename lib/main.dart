import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:googleapis/drive/v3.dart' as drive;
import 'package:googleapis_auth/auth_io.dart';
import 'package:permission_handler/permission_handler.dart';
// import 'package:file_picker/file_picker.dart';  // Temporarily commented out
import 'package:workmanager/workmanager.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:io';
import 'package:path/path.dart' as path;
import 'package:http/http.dart' as http;
import 'package:connectivity_plus/connectivity_plus.dart';

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

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _themeMode = 'system';

  @override
  void initState() {
    super.initState();
    _loadThemePreference();
  }

  Future<void> _loadThemePreference() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _themeMode = prefs.getString('theme_mode') ?? 'system';
    });
  }

  Future<void> _toggleTheme() async {
    // Cycle through themes: system -> light -> dark -> system
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      switch (_themeMode) {
        case 'system':
          _themeMode = 'light';
          break;
        case 'light':
          _themeMode = 'dark';
          break;
        case 'dark':
          _themeMode = 'system';
          break;
        default:
          _themeMode = 'system';
      }
    });
    await prefs.setString('theme_mode', _themeMode);
  }

  void _updateThemeMode(String mode) async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _themeMode = mode;
    });
    await prefs.setString('theme_mode', _themeMode);
  }

  ThemeMode _getThemeMode() {
    switch (_themeMode) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      case 'system':
      default:
        return ThemeMode.system;
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Drive Backup',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      themeMode: _getThemeMode(),
      home: BackupHomePage(
        onThemeToggle: _toggleTheme,
        onThemeModeChanged: _updateThemeMode,
      ),
    );
  }
}

class BackupHomePage extends StatefulWidget {
  final VoidCallback onThemeToggle;
  final Function(String) onThemeModeChanged;
  
  const BackupHomePage({
    super.key, 
    required this.onThemeToggle,
    required this.onThemeModeChanged,
  });

  @override
  State<BackupHomePage> createState() => _BackupHomePageState();
}

class _BackupHomePageState extends State<BackupHomePage> with WidgetsBindingObserver {
  final GoogleSignIn _googleSignIn = GoogleSignIn(
    scopes: [drive.DriveApi.driveFileScope],
    serverClientId: '586439189867-mvmv4b1vlf7tm26s44aqemevc3efligs.apps.googleusercontent.com',
  );
  
  GoogleSignInAccount? _currentUser;
  String? _selectedFolderPath;
  bool _isBackupEnabled = false;
  String _status = 'Not configured';
  int _backupInterval = 24; // hours
  bool _isBackingUp = false;
  int _maxFileSizeMB = 200; // Maximum file size in MB
  
  // Progress tracking
  int _totalFiles = 0;
  int _processedFiles = 0;
  double _progress = 0.0;
  
  // Settings
  bool _isDarkMode = false;
  bool _wifiOnlyBackup = true; // Default to WiFi only for data saving
  String _themeMode = 'system'; // 'light', 'dark', 'system'

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
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

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    
    switch (state) {
      case AppLifecycleState.paused:
        // App goes to background - backup continues
        if (_isBackingUp) {
          print('App paused - backup continues in background');
        }
        break;
      case AppLifecycleState.resumed:
        // App comes to foreground
        if (_isBackingUp) {
          print('App resumed - backup still running');
        }
        break;
      case AppLifecycleState.detached:
        // App is closing
        if (_isBackingUp) {
          print('App closing - backup may be interrupted');
        }
        break;
      default:
        break;
    }
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _selectedFolderPath = prefs.getString('backup_folder');
      _isBackupEnabled = prefs.getBool('backup_enabled') ?? false;
      _backupInterval = prefs.getInt('backup_interval') ?? 24;
      _maxFileSizeMB = prefs.getInt('max_file_size_mb') ?? 200;
      _wifiOnlyBackup = prefs.getBool('wifi_only_backup') ?? true;
      _themeMode = prefs.getString('theme_mode') ?? 'system';
    });
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    if (_selectedFolderPath != null) {
      await prefs.setString('backup_folder', _selectedFolderPath!);
    }
    await prefs.setBool('backup_enabled', _isBackupEnabled);
    await prefs.setInt('backup_interval', _backupInterval);
    await prefs.setInt('max_file_size_mb', _maxFileSizeMB);
    await prefs.setBool('wifi_only_backup', _wifiOnlyBackup);
    await prefs.setString('theme_mode', _themeMode);
  }

  // Wake lock functionality for background processing during backup
  Future<void> _setWakeLock(bool enabled) async {
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
      await platform.invokeMethod('setWakeLock', {'enabled': enabled});
      print('Background processing ${enabled ? 'enabled' : 'disabled'}');
    } catch (e) {
      print('Failed to set background processing: $e');
    }
  }

  // Update notification with progress (optional - keeps user informed)
  Future<void> _updateNotificationProgress(int current, int total) async {
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
      await platform.invokeMethod('updateProgress', {
        'current': current,
        'total': total,
        'status': 'Backing up... ($current/$total files)'
      });
    } catch (e) {
      // Ignore notification update errors
    }
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

  void _applyThemeMode(String mode) {
    widget.onThemeModeChanged(mode);
  }

  Future<bool> _isNetworkAvailable() async {
    final connectivityResult = await Connectivity().checkConnectivity();
    
    if (connectivityResult.contains(ConnectivityResult.none)) {
      return false;
    }
    
    if (_wifiOnlyBackup) {
      // Only allow WiFi if setting is enabled
      return connectivityResult.contains(ConnectivityResult.wifi);
    } else {
      // Allow WiFi or Mobile data
      return connectivityResult.contains(ConnectivityResult.wifi) ||
             connectivityResult.contains(ConnectivityResult.mobile);
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
    
    // String? selectedDirectory = await FilePicker.platform.getDirectoryPath();  // Temporarily commented out
    String? selectedDirectory = "/storage/emulated/0/Download";  // Temporary hardcoded path for testing
    
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

    // Check network connectivity
    if (!await _isNetworkAvailable()) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_wifiOnlyBackup 
            ? 'WiFi connection required for backup'
            : 'Internet connection required for backup'),
        ),
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
      _status = 'Initializing backup process...';
      _processedFiles = 0;
      _progress = 0.0;
    });

    // Enable background processing during backup
    await _setWakeLock(true);
    
    // Show notification that backup is running
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Backup started - will continue in background'),
        duration: Duration(seconds: 3),
        backgroundColor: Colors.green,
      ),
    );

    try {
      final folder = Directory(_selectedFolderPath!);
      
      // Count total files first
      setState(() {
        _status = 'Scanning folder for files...';
      });
      
      _totalFiles = await _countFiles(folder);
      
      setState(() {
        _status = 'Starting backup of $_totalFiles files...';
        _progress = 0.01; // Show minimal progress to indicate start
      });

      final authHeaders = await _currentUser!.authHeaders;
      final authenticateClient = GoogleAuthClient(authHeaders);
      final driveApi = drive.DriveApi(authenticateClient);

      // Get or create backup folder
      final folderId = await _getOrCreateDriveFolder(driveApi, 'AppBackup');
      
      // Backup files with progress tracking
      int fileCount = await _backupDirectory(driveApi, folder, folderId);

      setState(() {
        _status = 'Backup completed! $fileCount files synced';
        _isBackingUp = false;
        _progress = 1.0;
      });
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isBackingUp = false;
        _progress = 0.0;
      });
    } finally {
      // Release wake lock when backup is done
      await _setWakeLock(false);
    }
  }

  Future<int> _countFiles(Directory directory) async {
    int count = 0;
    try {
      await for (var entity in directory.list(recursive: false)) {
        if (entity is File) {
          final fileSize = await entity.length();
          if (fileSize <= _maxFileSizeMB * 1024 * 1024) {
            count++;
          }
        } else if (entity is Directory) {
          count += await _countFiles(entity);
        }
      }
    } catch (e) {
      print('Error counting files in ${directory.path}: $e');
    }
    return count;
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
            _processedFiles++;
            _progress = _totalFiles > 0 ? _processedFiles / _totalFiles : 0.0;
            
            // Update UI with progress and status
            if (mounted) {
              setState(() {
                _status = 'Backing up... ($_processedFiles/$_totalFiles files) ${(_progress * 100).toInt()}%';
              });
              
              // Update notification progress (optional)
              _updateNotificationProgress(_processedFiles, _totalFiles);
            }
            setState(() {
              _status = 'Backing up... ($_processedFiles/$_totalFiles files) ${(_progress * 100).toInt()}%';
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
    final fileName = path.basename(file.path);
    int retryCount = 0;
    const maxRetries = 3;
    
    while (retryCount < maxRetries) {
      try {
      final fileSize = file.lengthSync();
      final fileSizeMB = fileSize / 1024 / 1024;
      
      // Skip files larger than the configured limit to avoid timeout issues
      if (fileSize > _maxFileSizeMB * 1024 * 1024) {
        print('Skipping large file: $fileName (${fileSizeMB.toStringAsFixed(1)}MB > ${_maxFileSizeMB}MB limit)');
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
      
      // Success - break out of retry loop
      break;
      
    } catch (e) {
      retryCount++;
      
      if (retryCount >= maxRetries) {
        await _handleUploadError(fileName, e);
        break;
      } else {
        // Wait before retrying (exponential backoff)
        final waitTime = Duration(seconds: retryCount * 2);
        print('Upload failed for $fileName, retrying in ${waitTime.inSeconds} seconds... (attempt $retryCount/$maxRetries)');
        await Future.delayed(waitTime);
        
        // Check if network is still available before retry
        if (!await _isNetworkAvailable()) {
          await _handleUploadError(fileName, Exception('Network unavailable'));
          break;
        }
      }
    }
    }
  }

  Future<void> _handleUploadError(String fileName, dynamic error) async {
    print('Error uploading $fileName: $error');
    
    // Check if it's a network connectivity issue
    if (error.toString().contains('Failed host lookup') || 
        error.toString().contains('SocketException') ||
        error.toString().contains('NetworkException')) {
      setState(() {
        _status = 'Network error - check internet connection';
      });
      
      // Wait a bit and retry could be implemented here
      // For now, just log and continue with next file
    } else if (error.toString().contains('quotaExceeded')) {
      setState(() {
        _status = 'Google Drive storage quota exceeded';
      });
    } else if (error.toString().contains('authError')) {
      setState(() {
        _status = 'Authentication error - please sign in again';
      });
    } else {
      setState(() {
        _status = 'Upload error: ${error.toString().split(':').first}';
      });
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
          IconButton(
            icon: Icon(Theme.of(context).brightness == Brightness.dark 
              ? Icons.light_mode 
              : Icons.dark_mode),
            onPressed: widget.onThemeToggle,
            tooltip: Theme.of(context).brightness == Brightness.dark 
              ? 'Light Mode' 
              : 'Dark Mode',
          ),
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
                    
                    const SizedBox(height: 24),
                    // File size limit
                    Row(
                      children: [
                        Icon(Icons.storage, color: Colors.orange),
                        const SizedBox(width: 8),
                        Text(
                          'Max File Size',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    DropdownButton<int>(
                      value: _maxFileSizeMB,
                      isExpanded: true,
                      items: const [
                        DropdownMenuItem(value: 10, child: Text('10 MB')),
                        DropdownMenuItem(value: 50, child: Text('50 MB')),
                        DropdownMenuItem(value: 100, child: Text('100 MB')),
                        DropdownMenuItem(value: 200, child: Text('200 MB')),
                        DropdownMenuItem(value: 500, child: Text('500 MB')),
                        DropdownMenuItem(value: 1000, child: Text('1 GB')),
                      ],
                      onChanged: (value) {
                        setState(() {
                          _maxFileSizeMB = value!;
                        });
                        _saveSettings();
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Network preferences
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.wifi, color: Colors.blue),
                        const SizedBox(width: 8),
                        Text(
                          'Network Preferences',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    SwitchListTile(
                      secondary: Icon(_wifiOnlyBackup ? Icons.wifi : Icons.signal_cellular_4_bar),
                      title: Text(_wifiOnlyBackup ? 'WiFi Only Backup' : 'WiFi + Mobile Data'),
                      subtitle: Text(_wifiOnlyBackup 
                        ? 'Backup only when connected to WiFi'
                        : 'Backup using WiFi or mobile data'),
                      value: _wifiOnlyBackup,
                      onChanged: (value) {
                        setState(() {
                          _wifiOnlyBackup = value;
                        });
                        _saveSettings();
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Theme preferences
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.palette, color: Colors.purple),
                        const SizedBox(width: 8),
                        Text(
                          'Theme Settings',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    DropdownButton<String>(
                      value: _themeMode,
                      isExpanded: true,
                      items: const [
                        DropdownMenuItem(value: 'system', child: Row(
                          children: [
                            Icon(Icons.brightness_auto, size: 20),
                            SizedBox(width: 8),
                            Text('System Default'),
                          ],
                        )),
                        DropdownMenuItem(value: 'light', child: Row(
                          children: [
                            Icon(Icons.light_mode, size: 20),
                            SizedBox(width: 8),
                            Text('Light Theme'),
                          ],
                        )),
                        DropdownMenuItem(value: 'dark', child: Row(
                          children: [
                            Icon(Icons.dark_mode, size: 20),
                            SizedBox(width: 8),
                            Text('Dark Theme'),
                          ],
                        )),
                      ],
                      onChanged: (value) {
                        setState(() {
                          _themeMode = value!;
                        });
                        _saveSettings();
                        _applyThemeMode(value!);
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
              color: _isBackingUp ? Colors.orange.shade50 : Colors.blue.shade50,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  children: [
                    Row(
                      children: [
                        Icon(
                          _isBackingUp ? Icons.sync : Icons.info_outline, 
                          color: _isBackingUp ? Colors.orange : Colors.blue,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _status,
                            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: _isBackingUp ? FontWeight.w500 : FontWeight.normal,
                            ),
                          ),
                        ),
                      ],
                    ),
                    if (_isBackingUp) ...[
                      const SizedBox(height: 12),
                      LinearProgressIndicator(
                        value: _progress,
                        backgroundColor: Theme.of(context).colorScheme.surfaceVariant,
                        valueColor: AlwaysStoppedAnimation<Color>(
                          Theme.of(context).colorScheme.primary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '${(_progress * 100).toInt()}% completed',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
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

      // Check network connectivity for background backup
      final wifiOnlyBackup = prefs.getBool('wifi_only_backup') ?? true;
      final connectivityResult = await Connectivity().checkConnectivity();
      
      if (connectivityResult.contains(ConnectivityResult.none)) {
        return false;
      }
      
      if (wifiOnlyBackup && !connectivityResult.contains(ConnectivityResult.wifi)) {
        return false; // Skip backup if WiFi only is enabled and not on WiFi
      }

      final googleSignIn = GoogleSignIn(
        scopes: [drive.DriveApi.driveFileScope],
        serverClientId: '586439189867-mvmv4b1vlf7tm26s44aqemevc3efligs.apps.googleusercontent.com',
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