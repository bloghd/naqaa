import 'package:flutter/material.dart';

import '../services/settings_service.dart';

/// Default quality + Wi-Fi only.
class DownloadSettingsCard extends StatelessWidget {
  const DownloadSettingsCard({super.key});

  static const List<int> _options = [0, 1080, 720, 480, 360];

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: SettingsService.instance,
      builder: (context, _) {
        final s = SettingsService.instance;
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.download_outlined),
                    const SizedBox(width: 10),
                    Text('التحميل', style: Theme.of(context).textTheme.titleMedium),
                  ],
                ),
                const SizedBox(height: 16),
                Text('الجودة الافتراضية للفيديو', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  children: [
                    for (final h in _options)
                      ChoiceChip(
                        label: Text(h == 0 ? 'الأفضل جودة' : '${h}p'),
                        selected: s.defaultHeight == h,
                        onSelected: (_) => s.setDefaultHeight(h),
                      ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'تُختار تلقائيًا بعد تحليل أي رابط إذا كانت متاحة لهذا الفيديو.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('التحميل على شبكة Wi-Fi فقط'),
                  subtitle: const Text(
                    'عند عدم توفر Wi-Fi تنتظر التحميلات، وتكمل تلقائيًا عند الاتصال بشبكة Wi-Fi.',
                  ),
                  value: s.wifiOnly,
                  onChanged: s.setWifiOnly,
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// Manual update of the download tool.
class EngineCard extends StatefulWidget {
  const EngineCard({super.key});

  @override
  State<EngineCard> createState() => _EngineCardState();
}

class _EngineCardState extends State<EngineCard> {
  String _version = '';
  bool _busy = false;
  bool _ok = true;
  String? _message;

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    final v = await SettingsService.instance.engineVersion();
    if (mounted) setState(() => _version = v);
  }

  Future<void> _update() async {
    setState(() {
      _busy = true;
      _message = null;
    });
    final r = await SettingsService.instance.updateEngine();
    if (!mounted) return;

    String message;
    var ok = false;
    if (r['busy'] == true) {
      message = 'لا يمكن التحديث أثناء وجود تحميل جارٍ. انتظر حتى ينتهي.';
    } else if (r['ok'] == true) {
      ok = true;
      message = r['status'] == 'DONE'
          ? 'تم تحديث أداة التحميل بنجاح.'
          : 'أداة التحميل محدّثة بالفعل.';
    } else {
      message = 'تعذّر التحديث. تأكد من اتصالك بالإنترنت وحاول مرة أخرى.';
    }
    setState(() {
      _busy = false;
      _ok = ok;
      _message = message;
      final v = r['version'];
      if (v is String && v.isNotEmpty) _version = v;
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.system_update_alt),
                const SizedBox(width: 10),
                Text('تحديث أداة التحميل', style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'إذا توقف التحميل من موقع معين، جرّب تحديث الأداة. يلزم اتصال بالإنترنت.',
            ),
            if (_version.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                'الإصدار الحالي: $_version',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _busy ? null : _update,
              icon: _busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.refresh),
              label: Text(_busy ? 'جاري التحديث...' : 'تحديث الآن'),
            ),
            if (_message != null) ...[
              const SizedBox(height: 12),
              Text(
                _message!,
                style: TextStyle(color: _ok ? Colors.green : scheme.error),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
