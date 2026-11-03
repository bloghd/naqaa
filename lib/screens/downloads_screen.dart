import 'package:flutter/material.dart';

import '../models/download_item.dart';
import '../services/files_service.dart';
import '../services/history_store.dart';
import '../services/queue_service.dart';

class DownloadsScreen extends StatelessWidget {
  const DownloadsScreen({super.key});

  String _formatDate(DateTime d) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${d.year}/${two(d.month)}/${two(d.day)}  ${two(d.hour)}:${two(d.minute)}';
  }

  String _describe(DownloadItem item) {
    if (item.audio) return 'صوت';
    return item.height == 0 ? 'فيديو • أفضل جودة' : 'فيديو • ${item.height}p';
  }

  String _jobStatus(QueueJob job) {
    final percent = '${job.percent.toStringAsFixed(0)}%';
    switch (job.state) {
      case 'waiting':
        return 'بانتظار شبكة Wi-Fi';
      case 'queued':
        return 'في الانتظار';
      case 'running':
        return job.eta > 0 ? '$percent • المتبقي ${job.eta} ثانية' : percent;
      case 'paused':
        return 'متوقف مؤقتًا • $percent';
      default:
        return 'تعذّر التحميل';
    }
  }

  Widget _thumb(String url, bool audio) {
    final icon = Icon(audio ? Icons.music_note : Icons.videocam);
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: SizedBox(
        width: 64,
        height: 64,
        child: url.isEmpty
            ? icon
            : Image.network(url, fit: BoxFit.cover, errorBuilder: (_, __, ___) => icon),
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String text) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(text, style: Theme.of(context).textTheme.titleSmall),
    );
  }

  void _snack(BuildContext context, String message, {String? actionLabel, VoidCallback? onAction}) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        action: actionLabel == null
            ? null
            : SnackBarAction(label: actionLabel, onPressed: onAction ?? () {}),
      ),
    );
  }

  // Shows a message for a failed file action
  void _problem(BuildContext context, DownloadItem item, String code) {
    if (code == 'MISSING') {
      _snack(
        context,
        'الملف لم يعد موجودًا على جهازك',
        actionLabel: 'إزالة من السجل',
        onAction: () => HistoryStore.instance.remove(item.id),
      );
    } else if (code == 'NO_APP') {
      _snack(context, 'لا يوجد تطبيق على جهازك يمكنه فتح هذا الملف');
    } else {
      _snack(context, 'تعذّرت العملية. حاول مرة أخرى.');
    }
  }

  Future<void> _open(BuildContext context, DownloadItem item) async {
    final code = await FilesService.open(item);
    if (!context.mounted || code == 'OK') return;
    _problem(context, item, code);
  }

  Future<void> _share(BuildContext context, DownloadItem item) async {
    final code = await FilesService.share(item);
    if (!context.mounted || code == 'OK') return;
    _problem(context, item, code);
  }

  Future<void> _showLocation(BuildContext context, DownloadItem item) {
    final folder = item.audio ? 'الموسيقى' : 'الأفلام';
    final path = item.audio ? 'Music/VideoDownloader' : 'Movies/VideoDownloader';
    return showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('مكان الحفظ'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('مجلد $folder ← VideoDownloader'),
            const SizedBox(height: 8),
            Text(
              path,
              textDirection: TextDirection.ltr,
              style: Theme.of(ctx).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            const Text('يمكنك العثور عليه في تطبيق الملفات أو المعرض.'),
          ],
        ),
        actions: [
          FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('حسنًا')),
        ],
      ),
    );
  }

  Future<void> _deleteFile(BuildContext context, DownloadItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('حذف الملف'),
        content: const Text('سيتم حذف هذا الملف من جهازك نهائيًا. هل تريد المتابعة؟'),
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
    if (confirmed != true) return;

    final code = await FilesService.delete(item);
    if (!context.mounted) return;
    if (code == 'OK') {
      await HistoryStore.instance.remove(item.id);
      if (context.mounted) _snack(context, 'تم حذف الملف');
    } else if (code == 'MISSING') {
      await HistoryStore.instance.remove(item.id);
      if (context.mounted) _snack(context, 'الملف لم يعد موجودًا، تمت إزالته من السجل');
    } else if (code == 'DENIED') {
      _snack(context, 'تعذّر حذف الملف. يمكنك حذفه من تطبيق الملفات.');
    } else {
      _snack(context, 'تعذّرت العملية. حاول مرة أخرى.');
    }
  }

  Future<void> _confirmClear(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('مسح السجل'),
        content: const Text(
          'سيتم مسح قائمة التحميلات المكتملة من التطبيق فقط، والملفات المحفوظة على جهازك لن تُحذف.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('إلغاء'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('مسح'),
          ),
        ],
      ),
    );
    if (confirmed == true) await HistoryStore.instance.clear();
  }

  Widget _jobCard(BuildContext context, QueueJob job) {
    final queue = QueueService.instance;
    final failed = job.state == 'error';
    final kind = job.audio
        ? 'صوت'
        : (job.height == 0 ? 'فيديو • أفضل جودة' : 'فيديو • ${job.height}p');
    final actions = <Widget>[
      if (job.state == 'running' || job.state == 'queued' || job.state == 'waiting')
        IconButton(
          tooltip: 'إيقاف مؤقت',
          icon: const Icon(Icons.pause),
          onPressed: () => queue.pause(job.id),
        ),
      if (job.state == 'paused' || failed)
        IconButton(
          tooltip: failed ? 'إعادة المحاولة' : 'استئناف',
          icon: Icon(failed ? Icons.refresh : Icons.play_arrow),
          onPressed: () => queue.resume(job.id),
        ),
      IconButton(
        tooltip: 'إلغاء',
        icon: const Icon(Icons.close),
        onPressed: () => queue.cancel(job.id),
      ),
    ];

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            _thumb(job.thumbnail, job.audio),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(job.title, maxLines: 2, overflow: TextOverflow.ellipsis),
                  const SizedBox(height: 4),
                  Text(
                    '$kind  •  ${_jobStatus(job)}',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: failed ? Theme.of(context).colorScheme.error : null,
                        ),
                  ),
                  if (job.state == 'running' || job.state == 'paused' || job.state == 'waiting') ...[
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: job.percent > 0 ? (job.percent / 100).clamp(0.0, 1.0) : null,
                      minHeight: 6,
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ],
                ],
              ),
            ),
            Row(mainAxisSize: MainAxisSize.min, children: actions),
          ],
        ),
      ),
    );
  }

  Widget _historyTile(BuildContext context, DownloadItem item) {
    final errorBg = Theme.of(context).colorScheme.errorContainer;
    return Dismissible(
      key: ValueKey(item.id),
      background: Container(
        color: errorBg,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: const Icon(Icons.delete_outline),
      ),
      secondaryBackground: Container(
        color: errorBg,
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: const Icon(Icons.delete_outline),
      ),
      onDismissed: (_) => HistoryStore.instance.remove(item.id),
      child: ListTile(
        leading: _thumb(item.thumbnail, item.audio),
        title: Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis),
        subtitle: Text('${_describe(item)}\n${_formatDate(item.date)}'),
        isThreeLine: true,
        onTap: () => _open(context, item),
        trailing: PopupMenuButton<String>(
          tooltip: 'المزيد',
          onSelected: (value) {
            switch (value) {
              case 'play':
                _open(context, item);
                break;
              case 'share':
                _share(context, item);
                break;
              case 'location':
                _showLocation(context, item);
                break;
              case 'delete':
                _deleteFile(context, item);
                break;
              case 'remove':
                HistoryStore.instance.remove(item.id);
                break;
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'play', child: Text('تشغيل')),
            PopupMenuItem(value: 'share', child: Text('مشاركة')),
            PopupMenuItem(value: 'location', child: Text('مكان الحفظ')),
            PopupMenuItem(value: 'delete', child: Text('حذف الملف من الجهاز')),
            PopupMenuItem(value: 'remove', child: Text('إزالة من السجل')),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([HistoryStore.instance, QueueService.instance]),
      builder: (context, _) {
        final jobs = QueueService.instance.visibleJobs;
        final items = HistoryStore.instance.items;
        final isEmpty = jobs.isEmpty && items.isEmpty;

        return Scaffold(
          appBar: AppBar(
            title: const Text('التحميلات'),
            centerTitle: true,
            actions: [
              if (items.isNotEmpty)
                IconButton(
                  tooltip: 'مسح السجل',
                  icon: const Icon(Icons.delete_sweep_outlined),
                  onPressed: () => _confirmClear(context),
                ),
            ],
          ),
          body: isEmpty
              ? const Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.download_done_outlined, size: 56),
                      SizedBox(height: 12),
                      Text('لا توجد تحميلات بعد'),
                    ],
                  ),
                )
              : ListView(
                  padding: const EdgeInsets.only(bottom: 16),
                  children: [
                    if (jobs.isNotEmpty) ...[
                      _sectionTitle(context, 'قائمة التحميل (${jobs.length})'),
                      for (final job in jobs) _jobCard(context, job),
                    ],
                    if (items.isNotEmpty) ...[
                      _sectionTitle(context, 'المكتملة'),
                      for (final item in items) _historyTile(context, item),
                    ],
                  ],
                ),
        );
      },
    );
  }
}
