import 'package:flutter/material.dart';

import 'challenge.dart';
import 'config.dart';
import 'native.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  LauncherConfig? _cfg;
  List<InstalledApp> _installed = const [];

  /// 同一时刻只允许一个挑战框
  bool _challenging = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Native.homeKey.addListener(_onHomeKey);
    _reload().then((_) {
      final cfg = _cfg;
      if (cfg != null && cfg.coldStartHome && cfg.chOnHome) {
        _runChallenge('欢迎回来');
      }
    });
  }

  @override
  void dispose() {
    Native.homeKey.removeListener(_onHomeKey);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 从别的应用切回桌面时刷新一次统计数据
    if (state == AppLifecycleState.resumed) _reload();
  }

  void _onHomeKey() {
    if (_cfg?.chOnHome ?? false) _runChallenge('按 Home 键');
  }

  Future<void> _reload() async {
    final results = await Future.wait([Native.config(), Native.listApps()]);
    if (!mounted) return;
    setState(() {
      _cfg = results[0] as LauncherConfig;
      _installed = results[1] as List<InstalledApp>;
    });
  }

  Future<bool> _runChallenge(String title) async {
    final cfg = _cfg;
    if (cfg == null || _challenging) return false;
    _challenging = true;
    final ok = await runChallenge(context, cfg, title);
    _challenging = false;
    if (mounted) await _reload();
    return ok;
  }

  Future<void> _launch(InstalledApp app) async {
    final cfg = _cfg!;
    if (cfg.chOnLaunch && !await _runChallenge('准备打开「${app.label}」')) return;
    final ok = await Native.launchApp(app.package);
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('无法打开 ${app.label}')),
      );
    }
  }

  Future<void> _onBackPressed() async {
    if ((_cfg?.chOnBack ?? false)) await _runChallenge('返回键');
  }

  Future<void> _openSettings() async {
    final ok = await askParentPassword(context, title: '进入家长设置', subtitle: '请输入家长密码');
    if (!ok || !mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const SettingsScreen()),
    );
    await _reload();
  }

  Future<void> _openSystemSettings() async {
    final ok = await askParentPassword(context, title: '进入系统设置', subtitle: '请输入家长密码');
    if (!ok || !mounted) return;
    await Native.openSystemSettings();
    if (mounted) await _reload();
  }

  @override
  Widget build(BuildContext context) {
    final cfg = _cfg;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onBackPressed();
      },
      child: Scaffold(
        body: SafeArea(
          child: cfg == null
              ? const Center(child: CircularProgressIndicator())
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _header(cfg),
                    Expanded(child: _body(cfg)),
                  ],
                ),
        ),
      ),
    );
  }

  Widget _header(LauncherConfig cfg) {
    final parts = <String>[];
    final remain = cfg.remainingSeconds;
    if (remain != null) {
      parts.add('今日剩余 ${(remain / 60).ceil()} 分钟');
    }
    if (cfg.openLimitOn) {
      parts.add('打开 ${cfg.openCount}/${cfg.openLimit} 次');
    }
    if (parts.isEmpty) parts.add('今日使用 ${(cfg.usedSeconds / 60).floor()} 分钟');

    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '儿童桌面',
            style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 4),
          Text(
            parts.join(' · '),
            style: TextStyle(color: Theme.of(context).hintColor, fontSize: 14),
          ),
          if (!cfg.isDefaultLauncher)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                '⚠ 尚未设为默认桌面，Home 键不会回到这里',
                style: TextStyle(color: Colors.orange.shade800, fontSize: 13),
              ),
            ),
        ],
      ),
    );
  }

  Widget _body(LauncherConfig cfg) {
    final byPkg = {for (final a in _installed) a.package: a};
    final allowed = cfg.allowed
        .map((p) => byPkg[p])
        .whereType<InstalledApp>()
        .toList();

    final tiles = <Widget>[
      for (final a in allowed) _AppTile(app: a, onTap: () => _launch(a)),
      _IconTile(
        icon: Icons.lock_outline,
        label: '家长设置',
        onTap: _openSettings,
      ),
      _IconTile(
        icon: Icons.settings_outlined,
        label: '系统设置',
        onTap: _openSystemSettings,
      ),
    ];

    if (cfg.allowed.isEmpty) {
      return Column(
        children: [
          Expanded(
            child: Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Text(
                  '还没有添加允许孩子使用的应用。\n点下方「家长设置」添加。',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Theme.of(context).hintColor, fontSize: 16),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
            child: Column(
              children: [
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: _openSettings,
                    icon: const Icon(Icons.lock_outline),
                    label: const Text('家长设置'),
                  ),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: _openSystemSettings,
                    icon: const Icon(Icons.settings_outlined),
                    label: const Text('系统设置'),
                  ),
                ),
              ],
            ),
          ),
        ],
      );
    }

    return GridView.builder(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 140,
        mainAxisSpacing: 20,
        crossAxisSpacing: 20,
        childAspectRatio: 0.92,
      ),
      itemCount: tiles.length,
      itemBuilder: (_, i) => tiles[i],
    );
  }
}

class _AppTile extends StatelessWidget {
  const _AppTile({required this.app, required this.onTap});

  final InstalledApp app;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final initial = app.label.isEmpty ? '?' : app.label.substring(0, 1);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 68,
            height: 68,
            decoration: BoxDecoration(
              color: _colorFor(app.package),
              borderRadius: BorderRadius.circular(18),
            ),
            alignment: Alignment.center,
            child: Text(
              initial,
              style: const TextStyle(
                fontSize: 30,
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            app.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13),
          ),
        ],
      ),
    );
  }

  static Color _colorFor(String s) {
    var h = 7;
    for (final c in s.codeUnits) {
      h = (h * 31 + c) & 0x7fffffff;
    }
    return HSLColor.fromAHSL(1, (h % 360).toDouble(), 0.52, 0.52).toColor();
  }
}

class _IconTile extends StatelessWidget {
  const _IconTile({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 68,
            height: 68,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(18),
            ),
            alignment: Alignment.center,
            child: Icon(icon, size: 32),
          ),
          const SizedBox(height: 8),
          Text(label, style: const TextStyle(fontSize: 13)),
        ],
      ),
    );
  }
}
