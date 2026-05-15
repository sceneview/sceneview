import 'package:flutter/material.dart';

import 'pages/viewer_page.dart';
import 'pages/ar_page.dart';
import 'pages/features_page.dart';
import 'pages/about_page.dart';
import 'services/update_checker.dart';

/// SceneView Flutter Demo — showcases all Flutter bridge capabilities.
///
/// Architecture:
/// ```
/// Flutter (Dart)
///   +-- PlatformView --> Android: SceneView (Filament)
///   +-- PlatformView --> iOS: SceneViewSwift (RealityKit)
/// ```
class SceneViewDemoApp extends StatelessWidget {
  const SceneViewDemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SceneView Flutter',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF005BC1),
        useMaterial3: true,
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFF005BC1),
        useMaterial3: true,
        brightness: Brightness.dark,
      ),
      themeMode: ThemeMode.system,
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  int _currentIndex = 0;

  static const _pages = <Widget>[
    ViewerPage(),
    ARPage(),
    FeaturesPage(),
    AboutPage(),
  ];

  /// Cross-platform update checker — runs on every `resumed` lifecycle event.
  /// Android delegates to Play In-App Updates; iOS hits the iTunes lookup
  /// endpoint and redirects to the App Store. See [UpdateChecker].
  final UpdateChecker _updater = UpdateChecker();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Initial check at app launch — gives the user the prompt right away
    // instead of waiting for a first background→foreground transition.
    _updater.checkForUpdate();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _updater.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _updater.checkForUpdate();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          IndexedStack(
            index: _currentIndex,
            children: _pages,
          ),
          // Banner anchored to the top so it stays visible across tabs.
          // No-op when [UpdateChecker.hasUpdate] is false.
          AnimatedBuilder(
            animation: _updater,
            builder: (context, _) => _updater.hasUpdate
                ? Align(
                    alignment: Alignment.topCenter,
                    child: SafeArea(
                      child: _UpdateBanner(updater: _updater),
                    ),
                  )
                : const SizedBox.shrink(),
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _currentIndex,
        onDestinationSelected: (i) => setState(() => _currentIndex = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.view_in_ar_outlined),
            selectedIcon: Icon(Icons.view_in_ar),
            label: '3D Viewer',
          ),
          NavigationDestination(
            icon: Icon(Icons.camera_outlined),
            selectedIcon: Icon(Icons.camera),
            label: 'AR',
          ),
          NavigationDestination(
            icon: Icon(Icons.auto_awesome_outlined),
            selectedIcon: Icon(Icons.auto_awesome),
            label: 'Features',
          ),
          NavigationDestination(
            icon: Icon(Icons.info_outlined),
            selectedIcon: Icon(Icons.info),
            label: 'About',
          ),
        ],
      ),
    );
  }
}

/// Material 3 banner that surfaces a pending app update. On Android the
/// "Update" CTA triggers the Play flexible-update flow; on iOS it deep-links
/// to the App Store product page (Apple does not expose an in-app install).
class _UpdateBanner extends StatelessWidget {
  const _UpdateBanner({required this.updater});

  final UpdateChecker updater;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Material(
        elevation: 4,
        borderRadius: BorderRadius.circular(22),
        color: colors.secondaryContainer,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
          child: Row(
            children: [
              Icon(Icons.system_update, color: colors.onSecondaryContainer),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'Update available',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w600,
                            color: colors.onSecondaryContainer,
                          ),
                    ),
                    Text(
                      updater.availableVersion != null
                          ? 'Version ${updater.availableVersion} is ready.'
                          : 'A newer build is ready.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: colors.onSecondaryContainer.withValues(alpha: 0.8),
                          ),
                    ),
                  ],
                ),
              ),
              FilledButton.tonal(
                onPressed: () => updater.startUpdateFlow(),
                child: const Text('Update'),
              ),
              IconButton(
                icon: const Icon(Icons.close),
                tooltip: 'Dismiss until next release',
                color: colors.onSecondaryContainer,
                onPressed: () => updater.snooze(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
