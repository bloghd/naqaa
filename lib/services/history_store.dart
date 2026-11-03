import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/download_item.dart';

/// Keeps the list of finished downloads (newest first) and persists it on the device.
class HistoryStore extends ChangeNotifier {
  HistoryStore._();

  static final HistoryStore instance = HistoryStore._();

  static const MethodChannel _channel = MethodChannel('com.downloader/history');
  static const int _maxItems = 200;

  final List<DownloadItem> _items = [];

  List<DownloadItem> get items => List.unmodifiable(_items);

  Future<void> load() async {
    try {
      final raw = await _channel.invokeMethod<String>('load');
      if (raw == null || raw.isEmpty) return;
      final list = jsonDecode(raw) as List;
      _items
        ..clear()
        ..addAll(
          list.map((e) => DownloadItem.fromJson(e as Map<String, dynamic>)),
        );
      notifyListeners();
    } catch (e) {
      debugPrint('history load error: $e');
    }
  }

  Future<void> _save() async {
    try {
      await _channel.invokeMethod('save', {
        'json': jsonEncode(_items.map((e) => e.toJson()).toList()),
      });
    } catch (e) {
      debugPrint('history save error: $e');
    }
  }

  Future<void> add(DownloadItem item) async {
    _items.insert(0, item);
    if (_items.length > _maxItems) {
      _items.removeRange(_maxItems, _items.length);
    }
    notifyListeners();
    await _save();
  }

  Future<void> remove(String id) async {
    _items.removeWhere((e) => e.id == id);
    notifyListeners();
    await _save();
  }

  Future<void> clear() async {
    _items.clear();
    notifyListeners();
    await _save();
  }
}
