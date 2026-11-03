import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/queue_service.dart';
import '../services/settings_service.dart';

class HomeScreen extends StatefulWidget {
  final VoidCallback? onOpenDownloads;

  const HomeScreen({super.key, this.onOpenDownloads});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const MethodChannel _channel = MethodChannel('com.downloader/engine');

  final TextEditingController _urlController = TextEditingController();

  bool _loading = false;
  Map<String, dynamic>? _info;
  List<int> _heights = [];
  int _selectedHeight = 0; // 0 = best available
  String? _errorText;

  String? _clipboardUrl; // a link found in the clipboard and offered to the user
  String? _lastSeenClipboard; // the last clipboard link we looked at (offered or dismissed)

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Link shared while the app is already open
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onSharedText') {
        _handleSharedText(call.arguments?.toString() ?? '');
      }
      return null;
    });

    // Link shared when the app was launched from another app
    _loadInitialSharedText();
    _checkClipboard();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _urlController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkClipboard();
  }

  Future<void> _loadInitialSharedText() async {
    try {
      final text = await _channel.invokeMethod<String>('getSharedText');
      if (text != null && text.isNotEmpty) _handleSharedText(text);
    } catch (e) {
      debugPrint('getSharedText error: $e');
    }
  }

  // Extracts the first link from shared text, fills the field and starts analysis
  void _handleSharedText(String text) {
    final match = RegExp(r'https?://\S+').firstMatch(text);
    if (match == null || !mounted) return;
    setState(() => _clipboardUrl = null);
    _urlController.text = match.group(0)!;
    if (!_loading) _analyze();
  }

  // Android only lets the app in the foreground read the clipboard, so wait for the window to be ready
  Future<void> _checkClipboard() async {
    await Future<void>.delayed(const Duration(milliseconds: 400));
    if (!mounted || _loading) return;
    try {
      final data = await Clipboard.getData(Clipboard.kTextPlain);
      final match = RegExp(r'https?://\S+').firstMatch(data?.text ?? '');
      if (match == null) return;
      final url = match.group(0)!;
      if (url == _lastSeenClipboard) return;
      _lastSeenClipboard = url;
      if (url == _urlController.text.trim() || !mounted) return;
      setState(() => _clipboardUrl = url);
    } catch (e) {
      debugPrint('clipboard read error: $e');
    }
  }

  void _useClipboardUrl() {
    final url = _clipboardUrl;
    if (url == null) return;
    setState(() => _clipboardUrl = null);
    _urlController.text = url;
    _analyze();
  }

  Future<void> _pasteFromClipboard() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim() ?? '';
    if (text.isEmpty) {
      _toast('الحافظة فارغة');
      return;
    }
    final match = RegExp(r'https?://\S+').firstMatch(text);
    setState(() {
      _urlController.text = match?.group(0) ?? text;
      _clipboardUrl = null;
    });
  }

  Future<void> _analyze() async {
    final url = _urlController.text.trim();
    if (url.isEmpty) {
      setState(() => _errorText = 'من فضلك ألصق رابط الفيديو أولًا');
      return;
    }
    setState(() {
      _loading = true;
      _clipboardUrl = null;
      _info = null;
      _heights = [];
      _selectedHeight = 0;
      _errorText = null;
    });

    try {
      final raw = await _channel.invokeMethod<String>('analyzeUrl', {'url': url});
      final data = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (data['success'] == true) {
        final heights = ((data['heights'] as List?) ?? [])
            .map((e) => (e as num).toInt())
            .toList();
        setState(() {
          _info = data;
          _heights = heights;
          _selectedHeight = SettingsService.instance.pickHeight(heights);
        });
      } else {
        debugPrint('analyzeUrl error: ${data['error']}');
        setState(() => _errorText =
            'تعذّر قراءة هذا الرابط. تأكد من صحته ومن اتصالك بالإنترنت.');
      }
    } catch (e) {
      debugPrint('analyzeUrl exception: $e');
      setState(() => _errorText = 'حدث خطأ غير متوقع. حاول مرة أخرى.');
    } finally {
      setState(() => _loading = false);
    }
  }

  void _toast(String message, {bool withAction = false}) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        action: withAction
            ? SnackBarAction(label: 'عرض', onPressed: () => widget.onOpenDownloads?.call())
            : null,
      ),
    );
  }

  Future<void> _download({required bool audio}) async {
    final info = _info;
    if (info == null) return;
    final url = info['url'] as String;

    if (QueueService.instance.hasActive(url, audio, _selectedHeight)) {
      _toast('هذا الملف موجود بالفعل في قائمة التحميل');
      return;
    }
    final ok = await QueueService.instance.enqueue(
      url: url,
      title: info['title'] as String,
      thumbnail: info['thumbnail'] as String,
      uploader: info['uploader'] as String,
      audio: audio,
      height: _selectedHeight,
    );
    _toast(
      ok ? 'تمت الإضافة إلى قائمة التحميل' : 'تعذّرت الإضافة إلى القائمة. حاول مرة أخرى.',
      withAction: ok,
    );
  }

  String _formatDuration(int seconds) {
    final m = (seconds ~/ 60).toString().padLeft(2, '0');
    final s = (seconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  Widget _qualityChips() {
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      children: [
        ChoiceChip(
          label: const Text('الأفضل جودة'),
          selected: _selectedHeight == 0,
          onSelected: (_) => setState(() => _selectedHeight = 0),
        ),
        for (final h in _heights)
          ChoiceChip(
            label: Text('${h}p'),
            selected: _selectedHeight == h,
            onSelected: (_) => setState(() => _selectedHeight = h),
          ),
      ],
    );
  }

  Widget _clipboardCard(BuildContext context, String url) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      color: scheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.content_paste),
                SizedBox(width: 10),
                Expanded(child: Text('تم العثور على رابط في الحافظة')),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              url,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textDirection: TextDirection.ltr,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                FilledButton(
                  onPressed: _useClipboardUrl,
                  child: const Text('لصق وتحليل'),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: () => setState(() => _clipboardUrl = null),
                  child: const Text('تجاهل'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final info = _info;
    final scheme = Theme.of(context).colorScheme;
    final clip = _clipboardUrl;
    return Scaffold(
      appBar: AppBar(title: const Text('نقاء'), centerTitle: true),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (clip != null && !_loading) ...[
            _clipboardCard(context, clip),
            const SizedBox(height: 12),
          ],
          TextField(
            controller: _urlController,
            keyboardType: TextInputType.url,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              hintText: 'ألصق رابط الفيديو هنا',
              prefixIcon: const Icon(Icons.link),
              suffixIcon: IconButton(
                tooltip: 'لصق',
                icon: const Icon(Icons.content_paste),
                onPressed: _pasteFromClipboard,
              ),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _loading ? null : _analyze,
            icon: _loading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.search),
            label: Text(_loading ? 'جاري قراءة الرابط...' : 'تحليل الرابط'),
          ),
          if (_errorText != null) ...[
            const SizedBox(height: 16),
            Text(_errorText!, style: TextStyle(color: scheme.error)),
          ],
          if (info != null) ...[
            const SizedBox(height: 20),
            Card(
              clipBehavior: Clip.antiAlias,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if ((info['thumbnail'] as String).isNotEmpty)
                    Image.network(
                      info['thumbnail'] as String,
                      height: 200,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const SizedBox(
                        height: 200,
                        child: Icon(Icons.video_library, size: 48),
                      ),
                    ),
                  Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          info['title'] as String,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          '${info['uploader']}  •  ${_formatDuration(info['duration'] as int)}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            if (_heights.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text('جودة الفيديو', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 8),
              _qualityChips(),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () => _download(audio: false),
                    icon: const Icon(Icons.videocam),
                    label: const Text('تحميل الفيديو'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.tonalIcon(
                    onPressed: () => _download(audio: true),
                    icon: const Icon(Icons.music_note),
                    label: const Text('تحميل الصوت'),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
