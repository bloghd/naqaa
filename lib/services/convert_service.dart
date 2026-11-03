import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class PickedFile {
  final String uri;
  final String name;
  final bool isVideo;
  final int durationMs;
  final int size;

  const PickedFile({
    required this.uri,
    required this.name,
    required this.isVideo,
    required this.durationMs,
    required this.size,
  });

  factory PickedFile.fromJson(Map<String, dynamic> j) => PickedFile(
        uri: j['uri'] as String,
        name: j['name'] as String,
        isVideo: j['isVideo'] == true,
        durationMs: (j['durationMs'] as num?)?.toInt() ?? 0,
        size: (j['size'] as num?)?.toInt() ?? 0,
      );
}

/// State of the "remove music" tab. The work runs natively (even when the app is closed),
/// so this service re-attaches to it by reading the native status when refreshed.
class ConvertService extends ChangeNotifier {
  ConvertService._();

  static final ConvertService instance = ConvertService._();

  static const MethodChannel _channel = MethodChannel('com.downloader/convert');
  static const EventChannel _events = EventChannel('com.downloader/convert_progress');

  StreamSubscription<dynamic>? _sub;

  PickedFile? selected;
  List<PickedFile> library = [];
  bool outputVideo = true;

  bool running = false;
  String phase = 'prepare';
  int percent = 0;
  String runningName = '';

  /// The finished job waiting for the user to see it: {type: done|error, ...}
  Map<String, dynamic>? result;
  bool resultShown = true;

  bool get hasFinishedResult => result != null;

  Future<void> init() async {
    _sub ??= _events.receiveBroadcastStream().listen(_onEvent);
    await refresh();
  }

  void _setResult(Map<String, dynamic> data) {
    if (result == null) resultShown = false;
    result = data;
  }

  void _onEvent(dynamic event) {
    try {
      final data = jsonDecode(event.toString()) as Map<String, dynamic>;
      switch (data['type']) {
        case 'progress':
          running = true;
          phase = data['phase']?.toString() ?? phase;
          percent = (data['percent'] as num?)?.toInt() ?? percent;
          runningName = data['name']?.toString() ?? runningName;
          break;
        case 'done':
        case 'error':
          running = false;
          selected = null;
          _setResult(data);
          break;
        case 'cancelled':
          running = false;
          break;
      }
      notifyListeners();
    } catch (e) {
      debugPrint('convert event parse error: $e');
    }
  }

  Future<void> refresh() async {
    try {
      final raw = await _channel.invokeMethod<String>('status');
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      running = data['running'] == true;
      if (running) {
        phase = data['phase']?.toString() ?? phase;
        percent = (data['percent'] as num?)?.toInt() ?? percent;
        runningName = data['name']?.toString() ?? runningName;
      }
      final res = data['result'];
      if (res is Map<String, dynamic>) {
        _setResult(res);
      } else if (!running) {
        result = null;
      }
      notifyListeners();
    } catch (e) {
      debugPrint('convert status error: $e');
    }
  }

  Future<void> pick() async {
    try {
      final raw = await _channel.invokeMethod<String>('pick');
      if (raw == null) return;
      select(PickedFile.fromJson(jsonDecode(raw) as Map<String, dynamic>));
    } catch (e) {
      debugPrint('pick error: $e');
    }
  }

  Future<void> loadLibrary() async {
    try {
      final raw = await _channel.invokeMethod<String>('library');
      final list = jsonDecode(raw ?? '[]') as List;
      library = list
          .map((e) => PickedFile.fromJson(e as Map<String, dynamic>))
          .toList();
      notifyListeners();
    } catch (e) {
      debugPrint('library error: $e');
    }
  }

  void select(PickedFile file) {
    selected = file;
    outputVideo = file.isVideo;
    notifyListeners();
  }

  void clearSelection() {
    selected = null;
    notifyListeners();
  }

  void setOutputVideo(bool value) {
    outputVideo = value;
    notifyListeners();
  }

  /// Returns null when started, or an error code.
  Future<String?> start() async {
    final file = selected;
    if (file == null) return 'EMPTY_URI';
    try {
      final raw = await _channel.invokeMethod<String>('start', {
        'uri': file.uri,
        'name': file.name,
        'isVideo': file.isVideo,
        'outputVideo': file.isVideo && outputVideo,
      });
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (data['started'] == true) {
        running = true;
        phase = 'prepare';
        percent = 0;
        runningName = file.name;
        result = null;
        notifyListeners();
        return null;
      }
      return data['error']?.toString() ?? 'UNKNOWN';
    } catch (e) {
      debugPrint('convert start error: $e');
      return 'UNKNOWN';
    }
  }

  Future<void> cancel() async {
    try {
      await _channel.invokeMethod('cancel');
    } catch (e) {
      debugPrint('convert cancel error: $e');
    }
  }

  Future<void> acknowledge() async {
    result = null;
    notifyListeners();
    try {
      await _channel.invokeMethod('ack');
    } catch (e) {
      debugPrint('convert ack error: $e');
    }
  }
}
