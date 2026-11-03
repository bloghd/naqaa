import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/download_item.dart';

/// Actions on a saved file. Each returns a code:
/// OK | MISSING | NO_APP | DENIED | ERROR
class FilesService {
  static const MethodChannel _channel = MethodChannel('com.downloader/files');

  static Future<String> _call(String method, DownloadItem item) async {
    try {
      final result = await _channel.invokeMethod<String>(method, {
        'uri': item.uri,
        'title': item.title,
        'audio': item.audio,
      });
      return result ?? 'ERROR';
    } catch (e) {
      debugPrint('files $method error: $e');
      return 'ERROR';
    }
  }

  static Future<String> open(DownloadItem item) => _call('open', item);
  static Future<String> share(DownloadItem item) => _call('share', item);
  static Future<String> delete(DownloadItem item) => _call('delete', item);
}
