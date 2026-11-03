import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// State of the on-device "remove music" component (install / remove / progress).
/// The download runs natively and keeps going while the app is closed, so this
/// service re-attaches to it by reading the native status whenever it is refreshed.
class ModelService extends ChangeNotifier {
  ModelService._();

  static final ModelService instance = ModelService._();

  static const MethodChannel _channel = MethodChannel('com.downloader/model');
  static const EventChannel _events = EventChannel('com.downloader/model_progress');

  StreamSubscription<dynamic>? _sub;

  bool installed = false;
  bool downloading = false;
  int downloadedBytes = 0;
  int totalBytes = 0;
  String? errorText;

  Future<void> init() async {
    _sub ??= _events.receiveBroadcastStream().listen(_onEvent);
    await refresh();
  }

  void _onEvent(dynamic event) {
    try {
      final data = jsonDecode(event.toString()) as Map<String, dynamic>;
      downloadedBytes = (data['downloaded'] as num).toInt();
      totalBytes = (data['total'] as num).toInt();
      switch (data['type']) {
        case 'progress':
          downloading = true;
          break;
        case 'done':
          installed = true;
          downloading = false;
          errorText = null;
          break;
        case 'cancelled':
          downloading = false;
          break;
        case 'error':
          downloading = false;
          errorText = _messageFor(data['error']?.toString() ?? '');
          break;
      }
      notifyListeners();
    } catch (e) {
      debugPrint('model event parse error: $e');
    }
  }

  String _messageFor(String err) {
    if (err.contains('NO_SPACE')) {
      return 'المساحة المتاحة على جهازك غير كافية لتثبيت المكوّن.';
    }
    return 'تعذّر تحميل المكوّن. تأكد من اتصالك بالإنترنت وحاول مرة أخرى.';
  }

  /// Reads the real state from the native side (installed? still downloading?).
  Future<void> refresh() async {
    try {
      final raw = await _channel.invokeMethod<String>('status');
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      installed = data['installed'] == true;
      downloading = data['running'] == true;
      if (downloading) {
        downloadedBytes = (data['downloaded'] as num?)?.toInt() ?? 0;
        totalBytes = (data['total'] as num?)?.toInt() ?? 0;
        errorText = null;
      }
      notifyListeners();
    } catch (e) {
      debugPrint('model status error: $e');
    }
  }

  Future<void> download() async {
    if (downloading) return;
    downloading = true;
    errorText = null;
    downloadedBytes = 0;
    totalBytes = 0;
    notifyListeners();

    try {
      final raw = await _channel.invokeMethod<String>('download');
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (data['success'] == true) {
        installed = true;
        downloading = false;
      } else {
        final err = data['error']?.toString() ?? '';
        debugPrint('model download result: $err');
        if (err == 'BUSY') {
          // A download is already running (started before leaving the app): attach to it.
          await refresh();
          return;
        }
        downloading = false;
        // On cancel the partial file is kept, so the next try resumes from there.
        if (err != 'CANCELLED') errorText = _messageFor(err);
      }
    } catch (e) {
      debugPrint('model download exception: $e');
      downloading = false;
      errorText = 'حدث خطأ غير متوقع. حاول مرة أخرى.';
    }
    notifyListeners();
  }

  Future<void> cancel() async {
    try {
      await _channel.invokeMethod('cancel');
    } catch (e) {
      debugPrint('model cancel error: $e');
    }
  }

  Future<void> delete() async {
    try {
      await _channel.invokeMethod('delete');
    } catch (e) {
      debugPrint('model delete error: $e');
    }
    await refresh();
  }
}
