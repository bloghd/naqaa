import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// App settings: default video quality and Wi-Fi only (stored natively),
/// plus the manual update of the download tool.
class SettingsService extends ChangeNotifier {
  SettingsService._();

  static final SettingsService instance = SettingsService._();

  static const MethodChannel _channel = MethodChannel('com.downloader/settings');

  int defaultHeight = 0; // 0 = best available
  bool wifiOnly = false;

  Future<void> init() async {
    try {
      final raw = await _channel.invokeMethod<String>('get');
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      defaultHeight = (data['defaultHeight'] as num?)?.toInt() ?? 0;
      wifiOnly = data['wifiOnly'] == true;
      notifyListeners();
    } catch (e) {
      debugPrint('settings load error: $e');
    }
  }

  /// The quality to preselect for a video: the highest available one that is not above the default.
  int pickHeight(List<int> available) {
    if (defaultHeight == 0) return 0;
    final candidates = available.where((h) => h <= defaultHeight).toList()..sort();
    return candidates.isEmpty ? 0 : candidates.last;
  }

  Future<void> setDefaultHeight(int value) async {
    defaultHeight = value;
    notifyListeners();
    try {
      await _channel.invokeMethod('setDefaultHeight', {'value': value});
    } catch (e) {
      debugPrint('settings save error: $e');
    }
  }

  Future<void> setWifiOnly(bool value) async {
    wifiOnly = value;
    notifyListeners();
    try {
      await _channel.invokeMethod('setWifiOnly', {'value': value});
    } catch (e) {
      debugPrint('settings save error: $e');
    }
  }

  String _clean(String version) => version.replaceFirst('yt-dlp', '').trim();

  Future<String> engineVersion() async {
    try {
      final v = await _channel.invokeMethod<String>('engineVersion');
      return _clean(v ?? '');
    } catch (e) {
      debugPrint('engine version error: $e');
      return '';
    }
  }

  /// Returns {ok, busy, status, version}
  Future<Map<String, dynamic>> updateEngine() async {
    try {
      final raw = await _channel.invokeMethod<String>('updateEngine');
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (data['version'] is String) data['version'] = _clean(data['version'] as String);
      return data;
    } catch (e) {
      debugPrint('engine update error: $e');
      return {'ok': false};
    }
  }
}
