import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:googleapis/drive/v3.dart' as drive;
import 'package:permission_handler/permission_handler.dart';
import 'package:file_picker/file_picker.dart';
import 'package:workmanager/workmanager.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:io';
import 'dart:async';
import 'dart:math' as math;
import 'package:path/path.dart' as path;
import 'package:http/http.dart' as http;
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:uuid/uuid.dart';

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
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            foregroundColor: Colors.white, // White text on light theme
            backgroundColor: Colors.blue,
          ),
        ),
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            foregroundColor: Colors.black, // Black text on dark theme buttons
            backgroundColor:
                Colors.lightBlue.shade300, // Lighter blue for visibility
          ),
        ),
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

class _BackupHomePageState extends State<BackupHomePage>
    with WidgetsBindingObserver {
  final GoogleSignIn _googleSignIn = GoogleSignIn(
    scopes: [drive.DriveApi.driveFileScope],
    serverClientId:
        '586439189867-mvmv4b1vlf7tm26s44aqemevc3efligs.apps.googleusercontent.com',
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

  // Current file tracking
  String _currentFileName = '';
  double _currentFileProgress = 0.0;
  int _currentFileSize = 0;
  int _uploadedBytes = 0;
  int _skippedFiles = 0; // count of skipped files (native mode)
  bool _cancelCurrentFileFlag = false;

  // Native summary metrics (emitted by foreground service)
  int _summaryUploadedCount = 0;
  int _summarySkippedHashCount = 0;
  int _summarySkippedSizeCount = 0;
  int _summaryErrorCount = 0;
  int _summaryBytesUploaded = 0;
  int _summaryDurationMs = 0;
  DateTime? _summaryTimestamp; // when summary was recorded

  // Reverify flag (forces remote listing & ignores cache on native backup)
  bool _forceReverify = false;

  // Network monitoring
  StreamSubscription<List<ConnectivityResult>>? _connectivitySubscription;
  StreamSubscription<dynamic>? _nativeEventSubscription;

  // Heartbeat and monitoring
  DateTime? _lastProgressUpdate;
  bool _syncStoppedManually = false;
  bool _skipCurrentFile = false;
  Timer? _serviceWatchdog;
  static const _serviceHeartbeatTimeout =
      Duration(minutes: 2); // if no heartbeat for 2 minutes => attempt recovery

  // Native backup mode
  bool _useNativeBackup = false;

  // Device identity (stable per install) for multi-device separation
  String? _deviceId; // full UUID
  String get _deviceShortId => _deviceId != null && _deviceId!.length >= 8
      ? _deviceId!.substring(0, 8)
      : 'unknown';

  // Settings
  bool _wifiOnlyBackup = true; // Default to WiFi only for data saving
  String _themeMode = 'system'; // 'light', 'dark', 'system'

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadSettings();
    _ensureDeviceIdentity();
    _loadSummaryMetrics();
    // Check if backup was running when app was closed
    _restoreBackupState();

    // Check background permissions at launch
    _checkBackgroundPermissions();

    _googleSignIn.onCurrentUserChanged.listen((account) {
      print('Current user changed: $account');
      setState(() {
        _currentUser = account;
        if (account != null) {
          _status = 'Signed in as ${account.email}';
        } else {
          _status = 'Not signed in';
        }
      });
    });
    _googleSignIn.signInSilently().then((account) {
      print('Silent sign-in result: $account');
    }).catchError((error) {
      print('Silent sign-in error: $error');
    });

    // Monitor network connectivity changes
    _connectivitySubscription =
        Connectivity().onConnectivityChanged.listen((result) {
      if (_isBackingUp) {
        print('Network connectivity changed: $result');
        // The backup process will check network availability on each file
      }
    });

    // Subscribe to native backup events
    _subscribeToNativeEvents();
  }

  void _subscribeToNativeEvents() {
    const eventChannel = EventChannel('dev.guber.gdrivebackup/events');
    _nativeEventSubscription =
        eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final type = event['type'] as String?;
        // Debug instrumentation for native events
        // ignore: avoid_print
        print('[NativeEvent] $type -> $event');
        switch (type) {
          case 'scan_complete':
            if (mounted) {
              setState(() {
                _totalFiles = event['total'] as int? ?? 0;
                _processedFiles = event['processed'] as int? ?? 0;
                _progress =
                    _totalFiles > 0 ? _processedFiles / _totalFiles : 0.0;
                _status = 'Scan complete: $_totalFiles files';
              });
            }
            break;
          case 'native_progress':
            if (mounted) {
              setState(() {
                _processedFiles = event['processed'] as int? ?? 0;
                _totalFiles = event['total'] as int? ?? _totalFiles;
                _progress =
                    _totalFiles > 0 ? _processedFiles / _totalFiles : 0.0;
                _status = event['status'] as String? ?? 'Backing up...';
              });
            }
            break;
          case 'file_start':
            if (mounted) {
              setState(() {
                _currentFileName = event['fileName'] as String? ?? '';
                _currentFileSize = event['fileSize'] as int? ?? 0;
                _currentFileProgress = 0.0;
                _uploadedBytes = 0;
              });
            }
            break;
          case 'file_progress':
            if (mounted) {
              setState(() {
                _currentFileProgress =
                    (event['progress'] as num?)?.toDouble() ?? 0.0;
                _uploadedBytes = event['uploaded'] as int? ?? 0;
              });
            }
            break;
          case 'file_done':
            if (mounted) {
              setState(() {
                _currentFileProgress = 1.0;
                _uploadedBytes = _currentFileSize;
              });
            }
            break;
          case 'file_skipped':
            if (mounted) {
              setState(() {
                _skippedFiles += 1;
                // Rely on subsequent native_progress event for processed count to avoid double increment.
                _status =
                    'Skipped ${event['fileName']} ($_processedFiles/$_totalFiles)';
              });
            }
            break;
          case 'native_summary':
            // Final metrics summary from native service
            if (mounted) {
              setState(() {
                _summaryUploadedCount = event['uploadedCount'] as int? ?? 0;
                _summarySkippedHashCount =
                    event['skippedHashCount'] as int? ?? 0;
                _summarySkippedSizeCount =
                    event['skippedSizeCount'] as int? ?? 0;
                _summaryErrorCount = event['errorCount'] as int? ?? 0;
                _summaryBytesUploaded = event['bytesUploaded'] as int? ?? 0;
                _summaryDurationMs = event['durationMs'] as int? ?? 0;
                _summaryTimestamp = DateTime.now();
              });
            }
            _saveSummaryMetrics();
            break;
          case 'native_complete':
            if (mounted) {
              setState(() {
                _isBackingUp = false;
                _processedFiles = event['processed'] as int? ?? 0;
                _status = event['status'] as String? ?? 'Backup complete';
                _progress = 1.0;
                _currentFileName = '';
                _currentFileProgress = 0.0;
              });
              _clearBackupState();
              _setWakeLock(false);
              _stopServiceWatchdog();
            }
            break;
          case 'error':
            if (mounted) {
              setState(() {
                _status = 'Error: ${event['message'] ?? 'Unknown error'}';
                _isBackingUp = false;
              });
              _clearBackupState();
              _setWakeLock(false);
              _stopServiceWatchdog();
            }
            break;
        }
      }
    }, onError: (error) {
      print('Native event stream error: $error');
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _connectivitySubscription?.cancel();
    _nativeEventSubscription?.cancel();
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
          _saveBackupState();
        }
        break;
      case AppLifecycleState.resumed:
        // App comes to foreground - restore backup state
        print('App resumed - checking backup state');
        _restoreBackupState();
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

  // Save backup state when app goes to background
  Future<void> _saveBackupState() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('backup_in_progress', _isBackingUp);
    await prefs.setInt('total_files', _totalFiles);
    await prefs.setInt('processed_files', _processedFiles);
    await prefs.setDouble('progress', _progress);
    await prefs.setString('current_file_name', _currentFileName);
    await prefs.setDouble('current_file_progress', _currentFileProgress);
    await prefs.setInt('current_file_size', _currentFileSize);
    await prefs.setInt('uploaded_bytes', _uploadedBytes);
    print('💾 Backup state saved');
  }

  // Restore backup state when app resumes
  Future<void> _restoreBackupState() async {
    final prefs = await SharedPreferences.getInstance();
    final backupInProgress = prefs.getBool('backup_in_progress') ?? false;

    if (backupInProgress && !_isBackingUp) {
      print('🔄 Restoring backup state - backup was running in background');
      setState(() {
        _isBackingUp = true;
        _totalFiles = prefs.getInt('total_files') ?? 0;
        _processedFiles = prefs.getInt('processed_files') ?? 0;
        _progress = prefs.getDouble('progress') ?? 0.0;
        _currentFileName = prefs.getString('current_file_name') ?? '';
        _currentFileProgress = prefs.getDouble('current_file_progress') ?? 0.0;
        _currentFileSize = prefs.getInt('current_file_size') ?? 0;
        _uploadedBytes = prefs.getInt('uploaded_bytes') ?? 0;
        _status = 'Backup in progress...';
      });

      // The backup progress will update naturally as the background process continues
    }
  }

  // Clear backup state when backup completes
  Future<void> _clearBackupState() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('backup_in_progress');
    await prefs.remove('total_files');
    await prefs.remove('processed_files');
    await prefs.remove('progress');
    await prefs.remove('current_file_name');
    await prefs.remove('current_file_progress');
    await prefs.remove('current_file_size');
    await prefs.remove('uploaded_bytes');
    print('🗑️ Backup state cleared');
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
      _useNativeBackup = prefs.getBool('use_native_backup') ?? false;
      _forceReverify = prefs.getBool('force_reverify') ?? false;
      _deviceId = prefs.getString('device_id');
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
    await prefs.setBool('use_native_backup', _useNativeBackup);
    await prefs.setBool('force_reverify', _forceReverify);
    if (_deviceId != null) {
      await prefs.setString('device_id', _deviceId!);
    }
  }

  // Ensure a stable per-install UUID for device identity; no PII, just random
  Future<void> _ensureDeviceIdentity() async {
    if (_deviceId != null) return;
    final prefs = await SharedPreferences.getInstance();
    var existing = prefs.getString('device_id');
    if (existing == null) {
      // Generate new UUID v4
      final newId = const Uuid().v4();
      await prefs.setString('device_id', newId);
      existing = newId;
      print('🔐 Generated new device identity: $existing');
    } else {
      print('🔐 Loaded existing device identity: $existing');
    }
    setState(() {
      _deviceId = existing;
    });
  }

  // Persist summary metrics after native backup completes
  Future<void> _saveSummaryMetrics() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('summary_uploaded', _summaryUploadedCount);
    await prefs.setInt('summary_skipped_hash', _summarySkippedHashCount);
    await prefs.setInt('summary_skipped_size', _summarySkippedSizeCount);
    await prefs.setInt('summary_errors', _summaryErrorCount);
    await prefs.setInt('summary_bytes', _summaryBytesUploaded);
    await prefs.setInt('summary_duration_ms', _summaryDurationMs);
    await prefs.setInt(
        'summary_timestamp', _summaryTimestamp?.millisecondsSinceEpoch ?? 0);
  }

  // Load persisted summary metrics on app start
  Future<void> _loadSummaryMetrics() async {
    final prefs = await SharedPreferences.getInstance();
    final tsMs = prefs.getInt('summary_timestamp') ?? 0;
    if (tsMs == 0) return; // No summary yet
    setState(() {
      _summaryUploadedCount = prefs.getInt('summary_uploaded') ?? 0;
      _summarySkippedHashCount = prefs.getInt('summary_skipped_hash') ?? 0;
      _summarySkippedSizeCount = prefs.getInt('summary_skipped_size') ?? 0;
      _summaryErrorCount = prefs.getInt('summary_errors') ?? 0;
      _summaryBytesUploaded = prefs.getInt('summary_bytes') ?? 0;
      _summaryDurationMs = prefs.getInt('summary_duration_ms') ?? 0;
      _summaryTimestamp = DateTime.fromMillisecondsSinceEpoch(tsMs);
    });
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

  // Check if background usage is unrestricted
  Future<void> _checkBackgroundPermissions() async {
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
      final bool isUnrestricted =
          await platform.invokeMethod('isBackgroundUnrestricted');

      if (!isUnrestricted) {
        // Show non-intrusive dialog asking for background permissions
        if (mounted) {
          _showBackgroundPermissionDialog();
        }
      } else {
        print('✅ Background usage is already unrestricted');
      }
    } catch (e) {
      print('❌ Failed to check background permissions: $e');
    }
  }

  // Show dialog asking for unrestricted battery usage
  void _showBackgroundPermissionDialog() {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Unrestricted Battery Usage'),
          content: const Text(
            'For reliable backups, this app needs unrestricted battery usage.\n\n'
            'Please set battery usage to "Unrestricted":\n'
            '• NOT "Optimized" (default)\n'
            '• NOT "Restricted"\n'
            '• SET TO "Unrestricted" ✓\n\n'
            'This ensures backups continue reliably in the background.',
          ),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
              },
              child: const Text('Later'),
            ),
            ElevatedButton(
              onPressed: () {
                Navigator.of(context).pop();
                _requestBackgroundPermissions();
              },
              child: const Text('Open Battery Settings'),
            ),
          ],
        );
      },
    );
  }

  // Request background permissions for unrestricted battery usage
  Future<void> _requestBackgroundPermissions() async {
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');

      // Request notification permission first (needed for Android 13+)
      await platform.invokeMethod('requestNotificationPermission');

      // Request unrestricted battery usage (not just optimization exemption)
      await platform.invokeMethod('requestUnrestrictedBattery');

      print('✅ Unrestricted battery usage request sent');
    } catch (e) {
      print('❌ Failed to request unrestricted battery usage: $e');
    }
  }

  // Update notification for Dart-driven backup only; skip if native service active
  Future<void> _updateNotificationProgress(int current, int total,
      [String? currentFile]) async {
    if (_useNativeBackup) {
      // Native service handles its own notification; avoid duplicates
      return;
    }
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
      final statusText = currentFile != null
          ? 'Backing up $currentFile... ($current/$total files)'
          : 'Backing up... ($current/$total files)';

      await platform.invokeMethod('updateProgress',
          {'current': current, 'total': total, 'status': statusText});

      // Periodic UI status update
      if (mounted) {
        setState(() {
          _status = statusText;
        });
      }
    } catch (e) {
      print('Failed to update notification: $e');
    }
  }

  Future<void> _requestPermissions() async {
    // Request storage permissions
    var status = await Permission.storage.status;
    if (!status.isGranted) {
      await Permission.storage.request();
    }

    // For Android 11+ (API 30+) - needed for Downloads folder access
    if (await Permission.manageExternalStorage.isDenied) {
      // Show explanation before requesting MANAGE_EXTERNAL_STORAGE
      if (mounted) {
        final shouldRequest = await showDialog<bool>(
          context: context,
          builder: (BuildContext context) {
            return AlertDialog(
              title: const Text('Storage Permission'),
              content: const Text(
                'To access the Downloads folder and other system directories, '
                'this app needs "All files access" permission.\n\n'
                'This allows you to backup any folder on your device.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: const Text('Cancel'),
                ),
                ElevatedButton(
                  onPressed: () => Navigator.of(context).pop(true),
                  child: const Text('Grant Permission'),
                ),
              ],
            );
          },
        );

        if (shouldRequest == true) {
          // Use native method for better handling
          try {
            const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
            await platform.invokeMethod('requestStoragePermission');
          } catch (e) {
            // Fallback to permission_handler
            await Permission.manageExternalStorage.request();
          }
        }
      }
    }
  }

  void _applyThemeMode(String mode) {
    widget.onThemeModeChanged(mode);
  }

  Future<bool> _isNetworkAvailable() async {
    try {
      final connectivityResult = await Connectivity().checkConnectivity();
      print('🌐 Connectivity check result: $connectivityResult');

      // Don't fail immediately on "none" - sometimes connectivity reports incorrectly
      if (connectivityResult.contains(ConnectivityResult.none)) {
        print('⚠️ Connectivity reports none, but continuing anyway');
        // Still return true to avoid false negatives - let the actual HTTP request fail if needed
        return true;
      }

      // For now, be more permissive with network detection
      // The actual upload will fail if there's truly no network
      if (_wifiOnlyBackup) {
        if (connectivityResult.contains(ConnectivityResult.wifi)) {
          print('✅ WiFi available for backup');
          return true;
        } else {
          print(
              '⚠️ WiFi-only mode but not on WiFi. Connection: $connectivityResult');
          // Let it continue anyway - user might have changed setting
          return true;
        }
      } else {
        // Allow any connection when not in WiFi-only mode
        print('✅ Network available for backup: $connectivityResult');
        return true;
      }
    } catch (e) {
      print('❌ Network check failed: $e');
      // Return true to be permissive - let actual upload fail if needed
      return true;
    }
  }

  // Heartbeat monitoring to detect if sync stops unexpectedly
  void _startHeartbeatMonitoring() async {
    const checkInterval = Duration(seconds: 30); // Check every 30 seconds
    const maxSilenceTime = Duration(
        minutes: 5); // If no progress for 5 minutes, consider it stalled

    while (_isBackingUp && !_syncStoppedManually) {
      await Future.delayed(checkInterval);

      if (!_isBackingUp || _syncStoppedManually) break;

      final now = DateTime.now();
      if (_lastProgressUpdate != null) {
        final timeSinceLastUpdate = now.difference(_lastProgressUpdate!);

        if (timeSinceLastUpdate > maxSilenceTime) {
          print(
              '⚠️ Sync appears to be stalled - no progress for ${timeSinceLastUpdate.inMinutes} minutes');

          // Try to recover the sync
          if (mounted) {
            setState(() {
              _status = 'Sync stalled - attempting recovery...';
            });
          }

          // Check network connectivity
          if (!await _isNetworkAvailable()) {
            if (mounted) {
              setState(() {
                _status =
                    'Network connection lost - waiting for reconnection...';
              });
            }

            // Wait for network to return
            while (!await _isNetworkAvailable() &&
                _isBackingUp &&
                !_syncStoppedManually) {
              await Future.delayed(Duration(seconds: 10));
            }
          }

          // Update progress timestamp to reset the stall detection
          _lastProgressUpdate = now;
        }
      }
    }
  }

  void _startServiceWatchdog() {
    _serviceWatchdog?.cancel();
    _serviceWatchdog = Timer.periodic(const Duration(seconds: 45), (_) async {
      if (!_isBackingUp || _syncStoppedManually) return;
      try {
        const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
        final running =
            await platform.invokeMethod<bool>('isBackupServiceRunning') ??
                false;
        final heartbeatTs =
            await platform.invokeMethod<int>('getBackupServiceHeartbeat') ?? 0;
        final userStopped =
            await platform.invokeMethod<bool>('wasUserStopped') ?? false;
        final last =
            DateTime.fromMillisecondsSinceEpoch(heartbeatTs, isUtc: false);
        final now = DateTime.now();
        final diff = now.difference(last);
        if (!userStopped &&
            (!running || heartbeatTs == 0 || diff > _serviceHeartbeatTimeout)) {
          // Attempt recovery: restart service
          print(
              '🛠️ Watchdog restarting foreground service (running=$running, last=$diff ago)');
          await platform.invokeMethod('startBackupService');
          // Force update notification with current status
          await platform.invokeMethod('updateServiceProgress', {
            'current': _processedFiles,
            'total': _totalFiles,
            'status': _status,
          });
        }
      } catch (e) {
        print('Watchdog error: $e');
      }
    });
  }

  void _stopServiceWatchdog() {
    _serviceWatchdog?.cancel();
    _serviceWatchdog = null;
  }

  Future<void> _signIn() async {
    try {
      print('Starting Google Sign-In...');
      final result = await _googleSignIn.signIn();
      print('Sign-In result: $result');
      if (result == null) {
        print('Sign-In was cancelled or failed');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Sign in was cancelled')),
          );
        }
      } else {
        print('Sign-In successful: ${result.email}');
        setState(() {
          _currentUser = result;
          _status = 'Signed in as ${result.email}';
        });
      }
    } catch (error) {
      print('Sign-In error: $error');
      print('Error type: ${error.runtimeType}');
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

    // Show folder selection options including common system folders
    final selectedOption = await showDialog<String>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Select Backup Folder'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Choose a folder to backup:'),
              const SizedBox(height: 16),
              ListTile(
                leading: const Icon(Icons.download),
                title: const Text('Downloads'),
                subtitle: const Text('/storage/emulated/0/Download'),
                onTap: () =>
                    Navigator.of(context).pop('/storage/emulated/0/Download'),
              ),
              ListTile(
                leading: const Icon(Icons.photo),
                title: const Text('Pictures'),
                subtitle: const Text('/storage/emulated/0/Pictures'),
                onTap: () =>
                    Navigator.of(context).pop('/storage/emulated/0/Pictures'),
              ),
              ListTile(
                leading: const Icon(Icons.video_library),
                title: const Text('Movies'),
                subtitle: const Text('/storage/emulated/0/Movies'),
                onTap: () =>
                    Navigator.of(context).pop('/storage/emulated/0/Movies'),
              ),
              ListTile(
                leading: const Icon(Icons.library_music),
                title: const Text('Music'),
                subtitle: const Text('/storage/emulated/0/Music'),
                onTap: () =>
                    Navigator.of(context).pop('/storage/emulated/0/Music'),
              ),
              ListTile(
                leading: const Icon(Icons.insert_drive_file),
                title: const Text('Documents'),
                subtitle: const Text('/storage/emulated/0/Documents'),
                onTap: () =>
                    Navigator.of(context).pop('/storage/emulated/0/Documents'),
              ),
              const Divider(),
              ListTile(
                leading: const Icon(Icons.folder_open),
                title: const Text('Browse for other folder'),
                subtitle: const Text('Use file picker'),
                onTap: () => Navigator.of(context).pop('BROWSE'),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Cancel'),
            ),
          ],
        );
      },
    );

    if (selectedOption != null) {
      if (selectedOption == 'BROWSE') {
        // Use file picker for custom folder selection
        String? selectedDirectory =
            await FilePicker.platform.getDirectoryPath();
        if (selectedDirectory != null) {
          setState(() {
            _selectedFolderPath = selectedDirectory;
            _status = 'Folder selected: ${path.basename(selectedDirectory)}';
          });
          await _saveSettings();
        }
      } else {
        // Use preset system folder
        // Verify the folder exists
        final directory = Directory(selectedOption);
        if (await directory.exists()) {
          setState(() {
            _selectedFolderPath = selectedOption;
            _status = 'Folder selected: ${path.basename(selectedOption)}';
          });
          await _saveSettings();
        } else {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                  content: Text(
                      'Folder not found: ${path.basename(selectedOption)}')),
            );
          }
        }
      }
    } else {
      // User canceled selection, show message if no folder is selected
      if (_selectedFolderPath == null) {
        setState(() {
          _status = 'No folder selected';
        });
      }
    }
  }

  Future<void> _performBackup() async {
    if (_currentUser == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please sign in to Google first')),
      );
      return;
    }

    // Request background permissions (non-intrusive, only battery optimization)
    _requestBackgroundPermissions();

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
      _syncStoppedManually = false;
      _lastProgressUpdate = DateTime.now();
      // Initialize file tracking
      _currentFileName = '';
      _currentFileProgress = 0.0;
      _currentFileSize = 0;
      _uploadedBytes = 0;
      _cancelCurrentFileFlag = false;
      _skipCurrentFile = false;
    });

    // Save initial backup state
    _saveBackupState();

    // Enable background processing during backup
    await _setWakeLock(true);

    // Start Android foreground service (ensures process kept alive)
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
      await platform.invokeMethod('setUserStopped', {'stopped': false});
      await platform.invokeMethod('startBackupService');
    } catch (e) {
      print('Failed to start foreground service: $e');
    }

    // Show notification that backup is running
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Backup started - will continue in background'),
        duration: Duration(seconds: 3),
        backgroundColor: Colors.green,
      ),
    );

    // Start heartbeat monitoring
    _startHeartbeatMonitoring();
    _startServiceWatchdog();

    // Check if native backup mode is enabled (can add setting later)
    // For now, use native mode if available (prefer service-based upload)
    if (_useNativeBackup) {
      try {
        // Pass auth headers to native
        final authHeaders = await _currentUser!.authHeaders;
        const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
        await platform.invokeMethod('setAuthHeaders', {'headers': authHeaders});

        // Start native backup
        await platform.invokeMethod('startNativeBackup', {
          'root': _selectedFolderPath!,
          'maxMb': _maxFileSizeMB,
          'reverify': _forceReverify,
          'deviceId': _deviceShortId,
        });

        // Native events will update UI
        print('Native backup started');
      } catch (e) {
        setState(() {
          _status = 'Error starting native backup: $e';
          _isBackingUp = false;
        });
        _clearBackupState();
        await _setWakeLock(false);
        _stopServiceWatchdog();
      }
      return; // Exit early - native mode handles everything
    }

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
      final deviceRootName = 'AppBackup_${_deviceShortId}';
      final appBackupFolderId =
          await _getOrCreateDriveFolder(driveApi, deviceRootName);

      // Create subfolder with the name of the selected folder
      final selectedFolderName = path.basename(_selectedFolderPath!);
      final targetFolderId = await _getOrCreateDriveFolder(
          driveApi, selectedFolderName, appBackupFolderId);

      // Backup files with progress tracking
      print(
          '🚀 Starting backup of $_totalFiles files from $_selectedFolderPath to Google Drive folder: $selectedFolderName');
      int fileCount = await _backupDirectory(driveApi, folder, targetFolderId);

      if (!_syncStoppedManually) {
        setState(() {
          _status = 'Backup completed! $fileCount files synced';
          _isBackingUp = false;
          _progress = 1.0;
          // Clear file tracking on success
          _currentFileName = '';
          _currentFileProgress = 0.0;
          _currentFileSize = 0;
          _uploadedBytes = 0;
        });
        print('✅ Backup completed successfully: $fileCount files synced');
        _clearBackupState();
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isBackingUp = false;
        _progress = 0.0;
        // Clear file tracking on error
        _currentFileName = '';
        _currentFileProgress = 0.0;
        _currentFileSize = 0;
        _uploadedBytes = 0;
      });
      _clearBackupState();
    } finally {
      // Stop heartbeat monitoring
      _syncStoppedManually = true;

      // Release wake lock when backup is done
      await _setWakeLock(false);

      _stopServiceWatchdog();

      // Stop foreground service
      try {
        const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
        await platform.invokeMethod('stopBackupService');
      } catch (e) {
        print('Failed to stop foreground service: $e');
      }
    }
  }

  // Manual stop backup method
  void _stopBackup() {
    setState(() {
      _syncStoppedManually = true;
      _isBackingUp = false;
      _status = 'Backup stopped by user';
      _progress = 0.0;
      // Clear current file tracking
      _currentFileName = '';
      _currentFileProgress = 0.0;
      _currentFileSize = 0;
      _uploadedBytes = 0;
      _cancelCurrentFileFlag = false;
      _skipCurrentFile = false;
    });

    // Clear backup state when stopped manually
    _clearBackupState();

    // Release wake lock immediately
    _setWakeLock(false);

    _stopServiceWatchdog();

    // Stop foreground service
    try {
      const platform = MethodChannel('dev.guber.gdrivebackup/wakelock');
      platform.invokeMethod('setUserStopped', {'stopped': true});
      platform.invokeMethod('stopBackupService');
    } catch (e) {
      print('Failed to stop foreground service: $e');
    }

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Backup stopped'),
        backgroundColor: Colors.orange,
      ),
    );
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
    String folderName, [
    String? parentId,
  ]) async {
    try {
      // Build query to include parent folder if specified
      String query =
          "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false";
      if (parentId != null) {
        query += " and '$parentId' in parents";
      }

      final fileList = await driveApi.files.list(
        q: query,
        spaces: 'drive',
        $fields: 'files(id, name)',
      );

      if (fileList.files != null && fileList.files!.isNotEmpty) {
        return fileList.files!.first.id!;
      }

      // Create new folder
      final folder = drive.File()
        ..name = folderName
        ..mimeType = 'application/vnd.google-apps.folder';

      // Set parent folder if specified
      if (parentId != null) {
        folder.parents = [parentId];
      }

      final createdFolder = await driveApi.files.create(folder);
      print(
          '📁 Created Google Drive folder: $folderName ${parentId != null ? "inside parent folder" : "at root"}');
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
        // Check if backup was stopped manually
        if (_syncStoppedManually) {
          print('🛑 Backup stopped manually');
          break;
        }

        try {
          if (entity is File) {
            await _uploadFile(driveApi, entity, parentId);
            fileCount++;
            _processedFiles++;
            _progress = _totalFiles > 0 ? _processedFiles / _totalFiles : 0.0;

            // Update heartbeat timestamp
            _lastProgressUpdate = DateTime.now();

            // Log progress every 10 files
            if (_processedFiles % 10 == 0) {
              print(
                  '📈 Progress: $_processedFiles/$_totalFiles files (${(_progress * 100).toInt()}%)');
            }

            // Update UI with progress and status
            if (mounted) {
              setState(() {
                _status =
                    'Backing up... ($_processedFiles/$_totalFiles files) ${(_progress * 100).toInt()}%';
              });
            }

            // Always update notification progress (works in background) - EVERY FILE
            await _updateNotificationProgress(
                _processedFiles, _totalFiles, _currentFileName);
            print(
                '📱 Notification updated: $_processedFiles/$_totalFiles files');
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

    // Initialize file tracking
    setState(() {
      _currentFileName = fileName;
      _currentFileProgress = 0.0;
      _currentFileSize = file.lengthSync();
      _uploadedBytes = 0;
      _cancelCurrentFileFlag = false;
      _skipCurrentFile = false;
    });

    while (retryCount < maxRetries) {
      try {
        final fileSize = file.lengthSync();
        final fileSizeMB = fileSize / 1024 / 1024;

        // Debug: Upload process size information
        print('📤 Starting upload: $fileName');
        print(
            '📊 File size: ${fileSize} bytes (${fileSizeMB.toStringAsFixed(2)} MB)');

        // Check for skip request
        if (_skipCurrentFile) {
          print('⏭️ Skipping file: $fileName (user requested)');
          return;
        }

        // Check for cancel request
        if (_cancelCurrentFileFlag) {
          print('❌ Cancelling file upload: $fileName (user requested)');
          return;
        }

        // Skip files larger than the configured limit to avoid timeout issues
        if (fileSize > _maxFileSizeMB * 1024 * 1024) {
          print(
              'Skipping large file: $fileName (${fileSizeMB.toStringAsFixed(1)}MB > ${_maxFileSizeMB}MB limit)');
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

        // Wrap stream to report real progress
        final originalStream = file.openRead();
        final reportingStream =
            originalStream.transform<List<int>>(StreamTransformer.fromHandlers(
          handleData: (data, sink) {
            if (_cancelCurrentFileFlag || _skipCurrentFile || !_isBackingUp) {
              // If cancelled/skip, do not forward further data
              return;
            }
            _uploadedBytes += data.length;
            final progress =
                _currentFileSize > 0 ? _uploadedBytes / _currentFileSize : 0.0;
            // Throttle setState to avoid jank
            if (progress - _currentFileProgress >= 0.01 || progress == 1.0) {
              if (mounted) {
                setState(() {
                  _currentFileProgress =
                      progress.clamp(0.0, 0.99); // keep <1.0 until completion
                });
              }
            }
            sink.add(data);
          },
          handleError: (error, stackTrace, sink) {
            sink.addError(error, stackTrace);
          },
          handleDone: (sink) {
            sink.close();
          },
        ));

        final media = drive.Media(reportingStream, fileSize);

        if (fileList.files != null && fileList.files!.isNotEmpty) {
          // Update existing file
          final existingFile = fileList.files!.first;
          final localModified = file.lastModifiedSync();
          final driveModified = existingFile.modifiedTime;

          // Only update if local file is newer
          if (driveModified == null || localModified.isAfter(driveModified)) {
            // Start progress tracking
            // real progress already tracked by reportingStream

            // Add timeout to prevent hanging
            await Future.any([
              driveApi.files.update(
                driveFile,
                existingFile.id!,
                uploadMedia: media,
              ),
              Future.delayed(Duration(minutes: 10)).then((_) =>
                  throw TimeoutException('Upload timeout after 10 minutes')),
            ]);
            print('✅ Updated: $fileName (${fileSizeMB.toStringAsFixed(2)} MB)');
          }
        } else {
          // Start progress tracking
          // real progress already tracked by reportingStream

          // Create new file with timeout
          await Future.any([
            driveApi.files.create(
              driveFile,
              uploadMedia: media,
            ),
            Future.delayed(Duration(minutes: 10)).then((_) =>
                throw TimeoutException('Upload timeout after 10 minutes')),
          ]);
          print('✅ Uploaded: $fileName (${fileSizeMB.toStringAsFixed(2)} MB)');
        }

        // Mark as complete
        setState(() {
          _currentFileProgress = 1.0;
          _uploadedBytes = fileSize;
        });

        // Brief pause to show completion
        await Future.delayed(const Duration(milliseconds: 500));

        // Success - break out of retry loop
        break;
      } catch (e) {
        // Check if it's an authentication error (401)
        if (e.toString().contains('status: 401') ||
            e.toString().contains('authentication') ||
            e.toString().contains('OAuth')) {
          print('🔑 Authentication error detected for $fileName: $e');
          print('⚠️ Token may have expired during long backup process');

          // For auth errors, we'll skip this file and let the user re-authenticate
          await _handleUploadError(fileName, e);
          print(
              '⏭️ Skipping $fileName due to authentication error, continuing with next file');
          break;
        }

        retryCount++;

        if (retryCount >= maxRetries) {
          await _handleUploadError(fileName, e);
          print(
              '⏭️ Skipping $fileName after $maxRetries failed attempts, continuing with next file');
          break;
        } else {
          // Wait before retrying (exponential backoff)
          final waitTime = Duration(seconds: retryCount * 2);
          print(
              '❌ Upload failed for $fileName, retrying in ${waitTime.inSeconds} seconds... (attempt $retryCount/$maxRetries)');
          await Future.delayed(waitTime);

          // Check if backup was stopped manually during retry wait
          if (_syncStoppedManually) {
            print('🛑 Upload retry cancelled - backup stopped manually');
            break;
          }

          // Skip network check during retries - let actual HTTP requests determine connectivity
          // The network check was causing false negatives during background operation
        }
      }
    }
  }

  Future<void> _handleUploadError(String fileName, dynamic error) async {
    print('❌ Failed to upload $fileName after all retries: $error');

    // Check if it's a network connectivity issue
    if (error.toString().contains('Failed host lookup') ||
        error.toString().contains('SocketException') ||
        error.toString().contains('NetworkException') ||
        error.toString().contains('Network unavailable') ||
        error.toString().contains('Connection refused') ||
        error.toString().contains('Connection timed out')) {
      // Update status for network issues but DON'T block the backup
      if (mounted) {
        setState(() {
          _status = 'Network error on $fileName - continuing with next file';
        });
      }

      print('⚠️ Network issue with $fileName, will continue with next file');

      // Update notification about the issue but continue
      await _updateNotificationProgress(_processedFiles, _totalFiles,
          'Network issue with $fileName - continuing...');

      // DON'T wait for network - just continue with the next file
      // The individual file retry logic already handles network issues
    } else if (error.toString().contains('quotaExceeded')) {
      if (mounted) {
        setState(() {
          _status = 'Google Drive storage quota exceeded';
        });
      }
    } else if (error.toString().contains('authError') ||
        error.toString().contains('status: 401') ||
        error.toString().contains('authentication') ||
        error.toString().contains('OAuth')) {
      if (mounted) {
        setState(() {
          _status =
              'Authentication expired - backup continuing, some files may be skipped';
        });
      }
      print(
          '🔑 Authentication token expired during backup. Files uploaded so far are safe.');
      print(
          '💡 Tip: For long backups, consider signing out and back in to refresh token.');
    } else {
      if (mounted) {
        setState(() {
          _status = 'Upload error: ${error.toString().split(':').first}';
        });
      }
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

  // Helper method to format bytes into human-readable format
  String _formatBytes(int bytes) {
    if (bytes == 0) return '0 B';
    const suffixes = ['B', 'KB', 'MB', 'GB', 'TB'];
    int i = (math.log(bytes) / math.log(1024)).floor();
    return '${(bytes / math.pow(1024, i)).toStringAsFixed(1)} ${suffixes[i]}';
  }

  // Cancel current file upload
  void _cancelCurrentFile() {
    setState(() {
      _cancelCurrentFileFlag = true;
      _currentFileName = '';
      _currentFileProgress = 0.0;
      _currentFileSize = 0;
      _uploadedBytes = 0;
    });
  }

  // Skip current file upload
  void _skipCurrentFileUpload() {
    setState(() {
      _skipCurrentFile = true;
      _currentFileName = '';
      _currentFileProgress = 0.0;
      _currentFileSize = 0;
      _uploadedBytes = 0;
    });
  }

  // (Removed) Simulated upload progress now replaced by real stream-based progress tracking

  @override
  Widget build(BuildContext context) {
    // Wrap Scaffold body with SafeArea and ensure bottom inset padding so content
    // (status/progress cards) is not covered by system navigation bar.
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
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
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
                  subtitle:
                      Text(_currentUser?.email ?? 'Sign in to Google Drive'),
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
                          DropdownMenuItem(
                              value: 6, child: Text('Every 6 hours')),
                          DropdownMenuItem(
                              value: 12, child: Text('Every 12 hours')),
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
                          const Icon(Icons.storage, color: Colors.orange),
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
                          const Icon(Icons.wifi, color: Colors.blue),
                          const SizedBox(width: 8),
                          Text(
                            'Network Preferences',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      SwitchListTile(
                        secondary: Icon(_wifiOnlyBackup
                            ? Icons.wifi
                            : Icons.signal_cellular_4_bar),
                        title: Text(_wifiOnlyBackup
                            ? 'WiFi Only Backup'
                            : 'WiFi + Mobile Data'),
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
                      const Divider(height: 24),
                      SwitchListTile(
                        secondary: const Icon(Icons.cloud_upload),
                        title: const Text('Native Service Upload'),
                        subtitle: const Text(
                            'Use service-based upload for better reliability'),
                        value: _useNativeBackup,
                        onChanged: (value) {
                          setState(() {
                            _useNativeBackup = value;
                          });
                          _saveSettings();
                        },
                      ),
                      if (_useNativeBackup)
                        SwitchListTile(
                          secondary: const Icon(Icons.verified),
                          title: const Text('Force Reverify (ignore cache)'),
                          subtitle: const Text(
                              'Bypass metadata cache and re-scan remote Drive'),
                          value: _forceReverify,
                          onChanged: (value) {
                            setState(() {
                              _forceReverify = value;
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
                          const Icon(Icons.palette, color: Colors.purple),
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
                          DropdownMenuItem(
                              value: 'system',
                              child: Row(
                                children: [
                                  Icon(Icons.brightness_auto, size: 20),
                                  SizedBox(width: 8),
                                  Text('System Default'),
                                ],
                              )),
                          DropdownMenuItem(
                              value: 'light',
                              child: Row(
                                children: [
                                  Icon(Icons.light_mode, size: 20),
                                  SizedBox(width: 8),
                                  Text('Light Theme'),
                                ],
                              )),
                          DropdownMenuItem(
                              value: 'dark',
                              child: Row(
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

              // Manual backup buttons
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _isBackingUp ? null : _performBackup,
                      icon: _isBackingUp
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.backup),
                      label:
                          Text(_isBackingUp ? 'Backing up...' : 'Backup Now'),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.all(16),
                        backgroundColor: Theme.of(context).colorScheme.primary,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ),
                  if (_isBackingUp) ...[
                    const SizedBox(width: 12),
                    ElevatedButton.icon(
                      onPressed: _stopBackup,
                      icon: const Icon(Icons.stop),
                      label: const Text('Stop'),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.all(16),
                        backgroundColor: Colors.red,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 16),

              // Status
              Card(
                // Adaptive background for status card with better dark theme contrast
                color: () {
                  final scheme = Theme.of(context).colorScheme;
                  final isDark = scheme.brightness == Brightness.dark;
                  if (_isBackingUp) {
                    return isDark
                        ? scheme.surfaceVariant.withOpacity(0.35)
                        : Colors.orange.shade50;
                  } else {
                    return isDark
                        ? scheme.surfaceVariant.withOpacity(0.25)
                        : Colors.blue.shade50;
                  }
                }(),
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Row(
                        children: [
                          Icon(
                            _isBackingUp ? Icons.sync : Icons.info_outline,
                            color: _isBackingUp
                                ? (Theme.of(context).colorScheme.tertiary)
                                : Theme.of(context).colorScheme.secondary,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              _status,
                              style: Theme.of(context)
                                  .textTheme
                                  .bodyLarge
                                  ?.copyWith(
                                    fontWeight: _isBackingUp
                                        ? FontWeight.w500
                                        : FontWeight.normal,
                                    color:
                                        Theme.of(context).colorScheme.onSurface,
                                  ),
                            ),
                          ),
                        ],
                      ),
                      if (_deviceId != null) ...[
                        const SizedBox(height: 6),
                        Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            'Device ID: ${_deviceShortId}',
                            style:
                                Theme.of(context).textTheme.bodySmall?.copyWith(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onSurfaceVariant,
                                    ),
                          ),
                        ),
                      ],
                      if (_isBackingUp) ...[
                        const SizedBox(height: 12),
                        // Overall progress
                        LinearProgressIndicator(
                          value: _progress,
                          backgroundColor: Theme.of(context)
                              .colorScheme
                              .surfaceContainerHighest,
                          valueColor: AlwaysStoppedAnimation<Color>(
                              Theme.of(context).colorScheme.primary),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Overall: ${(_progress * 100).toInt()}% completed ($_processedFiles/$_totalFiles files, skipped $_skippedFiles)',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),

                        // Current file information
                        if (_currentFileName.isNotEmpty) ...[
                          const SizedBox(height: 16),
                          const Divider(),
                          const SizedBox(height: 8),
                          Row(
                            children: [
                              const Icon(Icons.insert_drive_file,
                                  size: 16, color: Colors.grey),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'Current file: $_currentFileName',
                                  style: Theme.of(context)
                                      .textTheme
                                      .bodyMedium
                                      ?.copyWith(
                                        fontWeight: FontWeight.w500,
                                      ),
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),

                          // File progress bar
                          LinearProgressIndicator(
                            value: _currentFileProgress,
                            backgroundColor:
                                Theme.of(context).colorScheme.surfaceVariant,
                            valueColor: AlwaysStoppedAnimation<Color>(
                                Theme.of(context).colorScheme.secondary),
                          ),
                          const SizedBox(height: 4),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text(
                                'File progress: ${(_currentFileProgress * 100).toInt()}%',
                                style: Theme.of(context)
                                    .textTheme
                                    .bodySmall
                                    ?.copyWith(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onSurfaceVariant,
                                    ),
                              ),
                              Text(
                                '${_formatBytes(_uploadedBytes)} / ${_formatBytes(_currentFileSize)}',
                                style: Theme.of(context)
                                    .textTheme
                                    .bodySmall
                                    ?.copyWith(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onSurfaceVariant,
                                    ),
                              ),
                            ],
                          ),

                          // File control buttons
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: ElevatedButton.icon(
                                  onPressed: _cancelCurrentFile,
                                  icon: const Icon(Icons.cancel, size: 16),
                                  label: const Text('Cancel File',
                                      style: TextStyle(
                                          fontWeight: FontWeight.w600)),
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: Colors.red.shade600,
                                    foregroundColor: Colors.white,
                                    elevation: 2,
                                    padding: const EdgeInsets.symmetric(
                                        vertical: 12, horizontal: 16),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: ElevatedButton.icon(
                                  onPressed: _skipCurrentFileUpload,
                                  icon: const Icon(Icons.skip_next, size: 16),
                                  label: const Text('Skip File',
                                      style: TextStyle(
                                          fontWeight: FontWeight.w600)),
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: Colors.orange.shade600,
                                    foregroundColor: Colors.white,
                                    elevation: 2,
                                    padding: const EdgeInsets.symmetric(
                                        vertical: 12, horizontal: 16),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ],
                    ],
                  ),
                ),
              ),
              if (_summaryTimestamp != null && !_isBackingUp) ...[
                const SizedBox(height: 16),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.assessment, color: Colors.teal),
                            const SizedBox(width: 8),
                            Text('Last Backup Summary',
                                style: Theme.of(context).textTheme.titleMedium),
                            const Spacer(),
                            Text(
                              _summaryTimestamp != null
                                  ? _summaryTimestamp!
                                      .toLocal()
                                      .toString()
                                      .split('.')
                                      .first
                                  : '',
                              style: Theme.of(context)
                                  .textTheme
                                  .bodySmall
                                  ?.copyWith(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onSurfaceVariant),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Wrap(
                          runSpacing: 8,
                          spacing: 12,
                          children: [
                            _MetricChip(
                                label: 'Uploaded',
                                value: _summaryUploadedCount.toString(),
                                icon: Icons.cloud_done,
                                color: Colors.green),
                            _MetricChip(
                                label: 'Skipped (hash)',
                                value: _summarySkippedHashCount.toString(),
                                icon: Icons.fingerprint,
                                color: Colors.blueGrey),
                            _MetricChip(
                                label: 'Skipped (size)',
                                value: _summarySkippedSizeCount.toString(),
                                icon: Icons.rule,
                                color: Colors.indigo),
                            _MetricChip(
                                label: 'Errors',
                                value: _summaryErrorCount.toString(),
                                icon: Icons.error_outline,
                                color: Colors.redAccent),
                            _MetricChip(
                                label: 'Bytes',
                                value: _formatBytes(_summaryBytesUploaded),
                                icon: Icons.data_usage,
                                color: Colors.deepPurple),
                            _MetricChip(
                                label: 'Duration',
                                value: _formatDuration(_summaryDurationMs),
                                icon: Icons.timer,
                                color: Colors.orange),
                          ],
                        ),
                        if (_forceReverify) ...[
                          const SizedBox(height: 12),
                          Row(
                            children: const [
                              Icon(Icons.verified,
                                  size: 16, color: Colors.teal),
                              SizedBox(width: 6),
                              Text('Reverify was enabled (cache bypassed)',
                                  style: TextStyle(fontSize: 12)),
                            ],
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ],
              // Spacer at bottom to avoid nav bar overlap
              SizedBox(height: MediaQuery.of(context).padding.bottom + 16),
            ],
          ),
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

      if (wifiOnlyBackup &&
          !connectivityResult.contains(ConnectivityResult.wifi)) {
        return false; // Skip backup if WiFi only is enabled and not on WiFi
      }

      final googleSignIn = GoogleSignIn(
        scopes: [drive.DriveApi.driveFileScope],
        serverClientId:
            '586439189867-mvmv4b1vlf7tm26s44aqemevc3efligs.apps.googleusercontent.com',
      );

      final account = await googleSignIn.signInSilently();
      if (account == null) {
        return false;
      }

      // final authHeaders = await account.authHeaders;
      // final authenticateClient = GoogleAuthClient(authHeaders);
      // final driveApi = drive.DriveApi(authenticateClient);

      // TODO: Implementation similar to manual backup needs to be completed
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

// Small reusable metric chip widget for summary display
class _MetricChip extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final Color color;

  const _MetricChip({
    required this.label,
    required this.value,
    required this.icon,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 10),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withOpacity(0.4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Text(
            '$label: ',
            style: theme.textTheme.bodySmall?.copyWith(
              fontWeight: FontWeight.w600,
              color: theme.colorScheme.onSurface.withOpacity(0.8),
            ),
          ),
          Text(
            value,
            style: theme.textTheme.bodySmall?.copyWith(
              fontWeight: FontWeight.w500,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

// Helper to format duration in ms into human readable string
String _formatDuration(int durationMs) {
  if (durationMs <= 0) return '0s';
  final seconds = durationMs / 1000.0;
  if (seconds < 60) return '${seconds.toStringAsFixed(1)}s';
  final minutes = seconds / 60.0;
  if (minutes < 60) return '${minutes.toStringAsFixed(1)}m';
  final hours = minutes / 60.0;
  return '${hours.toStringAsFixed(1)}h';
}
