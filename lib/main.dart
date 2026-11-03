import 'package:flutter/material.dart';

import 'screens/convert_screen.dart';
import 'screens/downloads_screen.dart';
import 'screens/home_screen.dart';
import 'screens/settings_screen.dart';
import 'services/convert_service.dart';
import 'services/history_store.dart';
import 'services/queue_service.dart';
import 'services/settings_service.dart';
import 'services/model_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await HistoryStore.instance.load();
  await ModelService.instance.init();
  await ConvertService.instance.init();
  await QueueService.instance.init();
  await SettingsService.instance.init();
  runApp(const VideoDownloaderApp());
}

class VideoDownloaderApp extends StatelessWidget {
  const VideoDownloaderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'نقاء',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.indigo,
        brightness: Brightness.dark,
      ),
      builder: (context, child) =>
          Directionality(textDirection: TextDirection.rtl, child: child!),
      home: const AppShell(),
    );
  }
}

class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with WidgetsBindingObserver {
  int _index = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    HistoryStore.instance.load();
    ModelService.instance.refresh();
    QueueService.instance.refresh();
    ConvertService.instance.refresh().then((_) {
      final convert = ConvertService.instance;
      // A conversion finished while the app was closed: open its result once
      if (mounted && convert.hasFinishedResult && !convert.resultShown) {
        convert.resultShown = true;
        setState(() => _index = 2);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: [
          HomeScreen(onOpenDownloads: () => setState(() => _index = 1)),
          const DownloadsScreen(),
          ConvertScreen(onOpenSettings: () => setState(() => _index = 3)),
          const SettingsScreen(),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) {
          setState(() => _index = i);
          if (i == 1) HistoryStore.instance.load();
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.download_outlined),
            selectedIcon: Icon(Icons.download),
            label: 'تحميل',
          ),
          NavigationDestination(
            icon: Icon(Icons.history),
            selectedIcon: Icon(Icons.history),
            label: 'التحميلات',
          ),
          NavigationDestination(
            icon: Icon(Icons.music_off_outlined),
            selectedIcon: Icon(Icons.music_off),
            label: 'إزالة الموسيقى',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'الإعدادات',
          ),
        ],
      ),
    );
  }
}
