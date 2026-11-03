import 'package:flutter/material.dart';

import '../services/convert_service.dart';
import '../services/model_service.dart';

class ConvertScreen extends StatelessWidget {
  final VoidCallback onOpenSettings;

  const ConvertScreen({super.key, required this.onOpenSettings});

  static String _duration(int ms) {
    final total = ms ~/ 1000;
    final m = (total ~/ 60).toString().padLeft(2, '0');
    final s = (total % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  static String _size(int bytes) => '${(bytes / (1024 * 1024)).toStringAsFixed(1)} ميجابايت';

  static String _phaseLabel(String phase) {
    switch (phase) {
      case 'separate':
        return 'جاري إزالة الموسيقى...';
      case 'save':
        return 'جاري الحفظ...';
      default:
        return 'جاري تجهيز الملف...';
    }
  }

  static String _errorMessage(String code) {
    if (code.contains('MODEL_MISSING')) {
      return 'مكوّن إزالة الموسيقى غير مثبّت. ثبّته من الإعدادات.';
    }
    if (code.contains('NO_AUDIO_TRACK')) {
      return 'هذا الملف لا يحتوي على صوت.';
    }
    if (code.contains('OUT_OF_MEMORY')) {
      return 'ذاكرة جهازك لا تكفي لمعالجة هذا الملف.';
    }
    if (code.contains('BUSY')) {
      return 'هناك عملية جارية الآن. انتظر حتى تنتهي.';
    }
    return 'تعذّرت إزالة الموسيقى من هذا الملف. جرّب ملفًا آخر.';
  }

  Future<void> _openLibrary(BuildContext context) async {
    final svc = ConvertService.instance;
    await svc.loadLibrary();
    if (!context.mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) {
        final files = svc.library;
        return SafeArea(
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * 0.7),
            child: files.isEmpty
                ? const Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(child: Text('لا توجد ملفات محمّلة من التطبيق بعد')),
                  )
                : ListView.separated(
                    shrinkWrap: true,
                    itemCount: files.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final f = files[i];
                      return ListTile(
                        leading: Icon(f.isVideo ? Icons.videocam : Icons.music_note),
                        title: Text(f.name, maxLines: 2, overflow: TextOverflow.ellipsis),
                        subtitle: Text('${_duration(f.durationMs)} • ${_size(f.size)}'),
                        onTap: () {
                          svc.select(f);
                          Navigator.pop(ctx);
                        },
                      );
                    },
                  ),
          ),
        );
      },
    );
  }

  Future<void> _start(BuildContext context) async {
    final svc = ConvertService.instance;
    final file = svc.selected;
    if (file == null) return;

    if (file.durationMs > 600000) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('مقطع طويل'),
          content: const Text(
            'إزالة الموسيقى من مقطع طويل قد تستغرق وقتًا طويلًا وتستهلك البطارية. هل تريد المتابعة؟',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('إلغاء'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('متابعة'),
            ),
          ],
        ),
      );
      if (confirmed != true) return;
    }

    final error = await svc.start();
    if (error != null && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_errorMessage(error))),
      );
    }
  }

  Widget _intro(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.music_off_outlined),
                const SizedBox(width: 10),
                Text('إزالة الموسيقى', style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'اختر فيديو أو ملفًا صوتيًا لإزالة الموسيقى منه وإبقاء الصوت البشري فقط. '
              'يُحفظ الناتج تلقائيًا، ويمكنك مغادرة التطبيق وسيصلك إشعار عند الانتهاء.',
            ),
            const SizedBox(height: 8),
            Text(
              'قد تستغرق المعالجة وقتًا أطول من مدة المقطع، وقد يتبقى أثر بسيط للموسيقى في بعض المقاطع.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }

  Widget _notInstalled(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('لاستخدام هذه الميزة ثبّت مكوّن إزالة الموسيقى مرة واحدة من الإعدادات.'),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: onOpenSettings,
              icon: const Icon(Icons.settings),
              label: const Text('الذهاب إلى الإعدادات'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _running(BuildContext context, ConvertService svc) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(svc.runningName, maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 12),
            Text(_phaseLabel(svc.phase)),
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: svc.percent > 0 ? (svc.percent / 100).clamp(0.0, 1.0) : null,
              minHeight: 8,
              borderRadius: BorderRadius.circular(4),
            ),
            const SizedBox(height: 8),
            Text('${svc.percent}%'),
            const SizedBox(height: 8),
            Text(
              'يمكنك مغادرة التطبيق، وسيصلك إشعار عند الانتهاء.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: svc.cancel,
              icon: const Icon(Icons.close),
              label: const Text('إلغاء'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _result(BuildContext context, ConvertService svc) {
    final data = svc.result!;
    final isDone = data['type'] == 'done';
    String message;
    if (isDone) {
      switch (data['output']) {
        case 'video':
          message = 'تم حفظ الفيديو بدون موسيقى في مجلد الأفلام (VideoDownloader).';
          break;
        case 'audio_fallback':
          message =
              'تعذّر حفظ الفيديو بهذه الصيغة، فتم حفظ الصوت بدون موسيقى في مجلد الموسيقى (VideoDownloader).';
          break;
        default:
          message = 'تم حفظ الصوت بدون موسيقى في مجلد الموسيقى (VideoDownloader).';
      }
    } else {
      message = _errorMessage(data['error']?.toString() ?? '');
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isDone ? Icons.check_circle : Icons.error_outline,
                  color: isDone ? Colors.green : Theme.of(context).colorScheme.error,
                ),
                const SizedBox(width: 10),
                Expanded(child: Text(message)),
              ],
            ),
            if (isDone && (data['name']?.toString().isNotEmpty ?? false)) ...[
              const SizedBox(height: 8),
              Text(
                data['name'].toString(),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 12),
            FilledButton(
              onPressed: svc.acknowledge,
              child: const Text('حسنًا'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _picker(BuildContext context, ConvertService svc) {
    final file = svc.selected;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FilledButton.tonalIcon(
          onPressed: () => _openLibrary(context),
          icon: const Icon(Icons.download_done_outlined),
          label: const Text('اختيار من تحميلات التطبيق'),
        ),
        const SizedBox(height: 10),
        FilledButton.tonalIcon(
          onPressed: svc.pick,
          icon: const Icon(Icons.folder_open_outlined),
          label: const Text('اختيار ملف من جهازك'),
        ),
        if (file != null) ...[
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(file.isVideo ? Icons.videocam : Icons.music_note),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(file.name, maxLines: 2, overflow: TextOverflow.ellipsis),
                      ),
                      IconButton(
                        tooltip: 'إزالة الاختيار',
                        icon: const Icon(Icons.close),
                        onPressed: svc.clearSelection,
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${_duration(file.durationMs)} • ${_size(file.size)}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (file.isVideo) ...[
                    const SizedBox(height: 16),
                    const Text('الناتج'),
                    const SizedBox(height: 8),
                    SegmentedButton<bool>(
                      segments: const [
                        ButtonSegment(value: true, label: Text('فيديو بدون موسيقى')),
                        ButtonSegment(value: false, label: Text('صوت فقط')),
                      ],
                      selected: {svc.outputVideo},
                      onSelectionChanged: (s) => svc.setOutputVideo(s.first),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => _start(context),
            icon: const Icon(Icons.play_arrow),
            label: const Text('بدء إزالة الموسيقى'),
          ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('إزالة الموسيقى'), centerTitle: true),
      body: ListenableBuilder(
        listenable: Listenable.merge([ConvertService.instance, ModelService.instance]),
        builder: (context, _) {
          final svc = ConvertService.instance;
          final installed = ModelService.instance.installed;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _intro(context),
              const SizedBox(height: 16),
              if (svc.running)
                _running(context, svc)
              else if (svc.result != null)
                _result(context, svc)
              else if (!installed)
                _notInstalled(context)
              else
                _picker(context, svc),
            ],
          );
        },
      ),
    );
  }
}
