import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'history_store.dart';

class QueueJob {
  final String id;
  final String url;
  final String title;
  final String thumbnail;
  final String uploader;
  final bool audio;
  final int height;
  final String state; // queued | running | paused | done | error
  final double percent;
  final int eta;
  final String? error;

  const QueueJob({
    required this.id,
    required this.url,
    required this.title,
    required this.thumbnail,
    required this.uploader,
    required this.audio,
    required this.height,
    required this.state,
    required this.percent,
    required this.eta,
    required this.error,
  });

  factory QueueJob.fromJson(Map<String, dynamic> j) => QueueJob(
        id: j['id'] as String,
        url: (j['url'] as String?) ?? '',
        title: (j['title'] as String?) ?? '',
        thumbnail: (j['thumbnail'] as String?) ?? '',
        uploader: (j['uploader'] as String?) ?? '',
        audio: j['audio'] == true,
        height: (j['height'] as num?)?.toInt() ?? 0,
        state: (j['state'] as String?) ?? 'queued',
        percent: (j['percent'] as num?)?.toDouble() ?? 0,
        eta: (j['eta'] as num?)?.toInt() ?? 0,
        error: j['error'] as String?,
      );

  bool get isActive => state == 'queued' || state == 'running' || state == 'waiting';
}

/// The download queue. Jobs run natively one after another (even when the app is closed);
/// this service mirrors their state for the UI.
class QueueService extends ChangeNotifier {
  QueueService._();

  static final QueueService instance = QueueService._();

  static const MethodChannel _channel = MethodChannel('com.downloader/queue');
  static const EventChannel _events = EventChannel('com.downloader/queue_events');

  StreamSubscription<dynamic>? _sub;

  List<QueueJob> jobs = [];

  /// Jobs shown in the queue section (finished ones move to the history).
  List<QueueJob> get visibleJobs => jobs.where((j) => j.state != 'done').toList();

  int get activeCount => jobs.where((j) => j.isActive).length;

  Future<void> init() async {
    _sub ??= _events.receiveBroadcastStream().listen((e) => _apply(e.toString()));
    await refresh();
  }

  Future<void> refresh() async {
    try {
      final raw = await _channel.invokeMethod<String>('list');
      _apply(raw ?? '[]');
    } catch (e) {
      debugPrint('queue refresh error: $e');
    }
  }

  void _apply(String raw) {
    try {
      final list = jsonDecode(raw) as List;
      jobs = list.map((e) => QueueJob.fromJson(e as Map<String, dynamic>)).toList();
      notifyListeners();
      if (jobs.any((j) => j.state == 'done')) _finishDone();
    } catch (e) {
      debugPrint('queue parse error: $e');
    }
  }

  // A job finished: it was already saved to the history natively; reload it and clear the job
  Future<void> _finishDone() async {
    await HistoryStore.instance.load();
    try {
      await _channel.invokeMethod('clearDone');
    } catch (e) {
      debugPrint('queue clearDone error: $e');
    }
  }

  bool hasActive(String url, bool audio, int height) => jobs.any(
        (j) =>
            j.url == url &&
            j.audio == audio &&
            (audio || j.height == height) &&
            j.state != 'done' &&
            j.state != 'error',
      );

  Future<bool> enqueue({
    required String url,
    required String title,
    required String thumbnail,
    required String uploader,
    required bool audio,
    required int height,
  }) async {
    try {
      final id = await _channel.invokeMethod<String>('enqueue', {
        'url': url,
        'title': title,
        'thumbnail': thumbnail,
        'uploader': uploader,
        'audio': audio,
        'height': height,
      });
      return id != null;
    } catch (e) {
      debugPrint('queue enqueue error: $e');
      return false;
    }
  }

  Future<void> pause(String id) => _call('pause', id);
  Future<void> resume(String id) => _call('resume', id);
  Future<void> cancel(String id) => _call('cancel', id);

  Future<void> _call(String method, String id) async {
    try {
      await _channel.invokeMethod(method, {'id': id});
    } catch (e) {
      debugPrint('queue $method error: $e');
    }
  }
}
