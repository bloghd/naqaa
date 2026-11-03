import 'package:flutter/material.dart';

import 'settings_extra.dart';

import '../services/model_service.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  static String _mb(int bytes) => (bytes / (1024 * 1024)).toStringAsFixed(0);

  Future<void> _confirmInstall(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('تثبيت مكوّن إزالة الموسيقى'),
        content: const Text(
          'سيتم تحميل مكوّن حجمه حوالي 316 ميجابايت مرة واحدة فقط. '
          'يُفضّل الاتصال بشبكة Wi-Fi. هل تريد المتابعة؟',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('إلغاء'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('تحميل'),
          ),
        ],
      ),
    );
    if (confirmed == true) await ModelService.instance.download();
  }

  Future<void> _confirmDelete(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('حذف المكوّن'),
        content: const Text(
          'سيتم حذف مكوّن إزالة الموسيقى من جهازك، ويمكنك تثبيته مرة أخرى في أي وقت.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('إلغاء'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('حذف'),
          ),
        ],
      ),
    );
    if (confirmed == true) await ModelService.instance.delete();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('الإعدادات'), centerTitle: true),
      body: ListenableBuilder(
        listenable: ModelService.instance,
        builder: (context, _) {
          final model = ModelService.instance;
          final progress = model.totalBytes > 0
              ? (model.downloadedBytes / model.totalBytes).clamp(0.0, 1.0)
              : null;

          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const DownloadSettingsCard(),
              const SizedBox(height: 12),
              const EngineCard(),
              const SizedBox(height: 12),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.music_off_outlined),
                          const SizedBox(width: 10),
                          Text(
                            'إزالة الموسيقى',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      const Text(
                        'تتيح لك تحميل الفيديو أو الصوت بالصوت البشري فقط، '
                        'مع إزالة الموسيقى والآلات. تعمل على جهازك بدون إنترنت بعد التثبيت.',
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'قد يتبقى أثر بسيط للموسيقى في بعض المقاطع، وإذا كان الصوت غناءً فسيبقى الصوت البشري.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      const SizedBox(height: 16),
                      if (model.downloading) ...[
                        LinearProgressIndicator(
                          value: progress,
                          minHeight: 8,
                          borderRadius: BorderRadius.circular(4),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          model.totalBytes > 0
                              ? '${_mb(model.downloadedBytes)} من ${_mb(model.totalBytes)} ميجابايت'
                              : 'جاري التحميل...',
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton.icon(
                          onPressed: model.cancel,
                          icon: const Icon(Icons.close),
                          label: const Text('إلغاء التحميل'),
                        ),
                      ] else if (model.installed) ...[
                        const Row(
                          children: [
                            Icon(Icons.check_circle, color: Colors.green),
                            SizedBox(width: 8),
                            Text('المكوّن مثبّت'),
                          ],
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton.icon(
                          onPressed: () => _confirmDelete(context),
                          icon: const Icon(Icons.delete_outline),
                          label: const Text('حذف المكوّن'),
                        ),
                      ] else ...[
                        const Text('غير مثبّت • الحجم حوالي 316 ميجابايت'),
                        const SizedBox(height: 8),
                        FilledButton.icon(
                          onPressed: () => _confirmInstall(context),
                          icon: const Icon(Icons.download),
                          label: const Text('تثبيت المكوّن'),
                        ),
                      ],
                      if (model.errorText != null) ...[
                        const SizedBox(height: 12),
                        Text(
                          model.errorText!,
                          style: TextStyle(color: scheme.error),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
