import 'dart:typed_data';

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

  /// 包名 → 图标 PNG 字节；取不到的包直接没有键，磁贴退回首字母
  Map<String, Uint8List> _icons = const {};

  /// 同一时刻只允许一个挑战框
  bool _challenging = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Native.homeKey.addListener(_onHomeKey);
    Native.homePressed.addListener(_onHomePressed);
    Native.backEscape.addListener(_onBackEscape);
    // 无障碍连上/断开时原生侧会主动推过来，顶部那行状态当场跟着变
    Native.accessibility.addListener(_onAccessibilityChanged);
    _reload().then((_) async {
      final cfg = _cfg;
      if (cfg == null) return;
      // 界面这一侧也留一条：原生日志只能说明「Activity 起来了」，
      // 说明不了「Flutter 界面拿到了配置、磁贴画出来了」
      Native.log(
        '桌面界面就绪：白名单 ${cfg.allowed.length} 个应用'
        '${cfg.hideIcon.isEmpty ? "" : "（其中 ${cfg.hideIcon.length} 个桌面不给图标）"}、'
        '回到桌面挑战=${cfg.chOnHome ? "开" : "关"}、'
        '单次上限=${cfg.singleUseMin <= 0 ? "不限" : "${cfg.singleUseMin} 分钟"}',
      );
      if (!cfg.coldStartHome || !cfg.chOnHome) return;
      // 冷启动这次是「从别处按 Home 把桌面拉起来」，判据在原生侧，这里只负责弹
      Native.log('冷启动：原生判定为「从别处回到桌面」，弹挑战框');
      if (await _runChallenge('欢迎回来')) return;
      await Native.returnToLastApp();
    });
  }

  @override
  void dispose() {
    Native.homeKey.removeListener(_onHomeKey);
    Native.homePressed.removeListener(_onHomePressed);
    Native.backEscape.removeListener(_onBackEscape);
    Native.accessibility.removeListener(_onAccessibilityChanged);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 从别的应用切回桌面时刷新一次统计数据
    if (state == AppLifecycleState.resumed) _reload();
  }

  /// 无障碍实况变了（原生侧推的，或某次刷新查到的），顶部那行跟着重画
  void _onAccessibilityChanged() {
    if (mounted) setState(() {});
  }

  /// 按 Home 键、而桌面本来就摆在自己面前：压在桌面上的页面（家长设置、选应用）退光，露出桌面。
  /// 桌面这个 Activity 一直没离开前台，系统不会重新走一遍生命周期，只有原生侧那一下 Home intent
  /// 看得见（见 MainActivity.onNewIntent）——所以退页面这件事得由它通知过来。
  ///
  /// 挑战框开着时不退：那时按 Home 的正是想跳出挑战框的孩子，退了等于放他走。
  Future<void> _onHomePressed() async {
    if (_challenging) {
      Native.log('收到「按 Home 键」，但挑战框还开着，不退页面');
      return;
    }
    if (!mounted) return;
    final nav = Navigator.of(context);
    if (!nav.canPop()) return;
    Native.log('按 Home 键：退掉压在桌面上的页面，回到桌面');
    // 用 maybePop 逐个退，不用 popUntil：栈上还压着 canPop:false 的页面时 popUntil 会原地打转
    // （它 pop 不动就再来一次），maybePop 会老实返回 false 停手
    while (nav.canPop()) {
      if (!await nav.maybePop()) return;
      if (!mounted) return;
    }
  }

  /// 原生侧只在「孩子从别的应用逃回桌面」时通知这里（守护自己弹回桌面的那次不通知），
  /// 按 Home 键和按返回键退出应用各走一个通知，弹框上要写清楚是哪一种。
  Future<void> _onHomeKey() => _escapeFromApp('按 Home 键');

  /// 在应用里按返回键一路退出来，落到了桌面上——和按 Home 一样算「从别处逃回来」
  Future<void> _onBackEscape() => _escapeFromApp('按返回键回到桌面');

  /// 孩子从别的应用逃回桌面：答对才留在桌面；答错或取消，把他送回刚才那个应用——
  /// 桌面是答对才进得去的地方。
  Future<void> _escapeFromApp(String title) async {
    if (_challenging) {
      Native.log('收到「$title」通知，但挑战框还开着，忽略这一下');
      return;
    }
    var cfg = _cfg;
    if (cfg == null) {
      // 桌面 Activity 刚被重建，配置还没取回来。这一下不能被白白放过去——
      // 等配置回来再判，否则孩子按一下键就直接进了桌面。
      Native.log('收到「$title」通知，配置还没回来，先取配置再判');
      await _reload();
      cfg = _cfg;
    }
    if (cfg == null) {
      Native.log('收到「$title」通知，配置仍取不回来，只能放过这一下');
      return;
    }
    // 「系统拦截」关着（家长自己用平板，或者「测试拦截」到点了）就不弹。
    // 原生侧判定时也会挡一道，这里再问一次是为了掐准「测试拦截」到点的那一刻：
    // 通知发出来时还在测试里，等它到 Dart 这边可能刚好过期
    if (!await Native.sysInterceptOn()) {
      Native.log('收到「$title」通知，但「系统拦截」关着（也没在测试拦截中），直接进桌面');
      return;
    }
    if (!cfg.chOnHome) {
      Native.log('收到「$title」通知，但「回到桌面时挑战」开关是关的，直接进桌面');
      return;
    }
    if (await _runChallenge(title)) return;
    await Native.returnToLastApp();
  }

  Future<void> _reload() async {
    final results = await Future.wait([
      Native.config(),
      Native.listApps(),
      Native.refreshAccessibility(),
    ]);
    final cfg = results[0] as LauncherConfig;
    // 图标跟列表一起就绪再上屏，免得磁贴先从字母闪成图标
    final icons = await Native.appIcons(cfg.allowed);
    if (!mounted) return;
    setState(() {
      _cfg = cfg;
      _installed = results[1] as List<InstalledApp>;
      _icons = icons;
    });
  }

  Future<bool> _runChallenge(String title) async {
    final cfg = _cfg;
    if (cfg == null || _challenging) return false;
    _challenging = true;
    var ok = false;
    try {
      ok = await runChallenge(context, cfg, title);
    } catch (e) {
      // 弹框这一下抛异常（桌面刚好被重建之类）也绝不能把 _challenging 卡在 true 上：
      // 卡住之后孩子再按 Home 就完全没反应了，只能靠杀掉应用恢复
      Native.log('挑战框「$title」弹失败：$e');
      ok = false;
    } finally {
      _challenging = false;
    }
    Native.log('挑战框「$title」→ ${ok ? "通过" : "没通过"}');
    if (mounted) await _reload();
    return ok;
  }

  Future<void> _launch(InstalledApp app) async {
    final cfg = _cfg!;
    Native.log('点了磁贴「${app.label}」（${app.package}）');
    // 家长在「应用白名单」里把这个应用设成免挑战时，直接打开；
    // 「系统拦截」关着（也没在测试）时同样一个题都不弹，点开就进
    if (await Native.sysInterceptOn() &&
        cfg.needsChallenge(app.package) &&
        !await _runChallenge('准备打开「${app.label}」')) {
      return;
    }
    final ok = await Native.launchApp(app.package);
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('无法打开 ${app.label}')),
      );
    }
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
    final cfg = _cfg;
    final ok = await askParentPassword(
      context,
      title: '进入系统设置',
      subtitle: cfg != null && cfg.settingsPwCustom
          ? '请输入系统设置密码'
          : '请输入家长密码（尚未单独设置系统设置密码）',
      useSettingsPassword: true,
    );
    if (!ok || !mounted) return;
    await Native.openSystemSettings();
    if (mounted) await _reload();
  }

  @override
  Widget build(BuildContext context) {
    final cfg = _cfg;
    return PopScope(
      // 桌面上的返回键就此打住：孩子本来就站在桌面上，没什么可挑战的；
      // 也绝不能放行——真让系统结束掉桌面 Activity，露出来的就是孩子上一个用的应用
      canPop: false,
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
          const SizedBox(height: 8),
          _accessibilityLine(),
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

  /// 顶部那行「无障碍还在不在」。这是家长装完之后唯一的现场提示——他不会天天进设置页，
  /// 而服务被系统悄悄杀掉时（装新版、强行停止、厂商省电休眠）桌面上本来什么都看不出来，
  /// 只会觉得"限时怎么又不管用了"。还没问到实况（[AccessibilityStatus] 为 null）时什么都不显示：
  /// 原生侧答不上来就报"权限没了"是误报。
  Widget _accessibilityLine() {
    final acc = Native.accessibility.value;
    if (acc == null) return const SizedBox.shrink();
    if (acc.ok) {
      return Text(
        '无障碍守护：已开启',
        style: TextStyle(color: Colors.green.shade700, fontSize: 13),
      );
    }
    return _BlinkingWarning(
      text: acc.enabled
          ? '⚠ 无障碍已开启，但服务没在运行：限时、不拦非白名单应用、回到桌面挑战都不会生效\n'
                '请到「家长设置 → 防绕过 → 无障碍权限」里关掉再打开一次'
          : '⚠ 无障碍权限未开启：限时、不拦非白名单应用、回到桌面挑战都不会生效\n'
                '请到「家长设置 → 防绕过 → 无障碍权限」里打开',
    );
  }

  Widget _body(LauncherConfig cfg) {
    final byPkg = {for (final a in _installed) a.package: a};
    // 家长把某个应用设成「桌面不给图标」时，它照样在白名单里（打得开、受管控、照计时），
    // 这里只是不给孩子摆出那个入口
    final allowed = cfg.allowed
        .where(cfg.showsOnDesktop)
        .map((p) => byPkg[p])
        .whereType<InstalledApp>()
        .toList();

    final tiles = <Widget>[
      for (final a in allowed)
        _AppTile(app: a, icon: _icons[a.package], onTap: () => _launch(a)),
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

/// 红字闪烁警告。
/// 用 [FadeTransition] 而不是定时 setState：桌面是常驻界面，为了闪烁每 600ms 重建一次
/// 整棵子树没必要，动画只重绘这一层。**只要它上屏，就会一直有帧在排**——写测试时
/// 别对它用 pumpAndSettle，那会永远等不到静止（用 tester.pump(时长) 往前推）。
class _BlinkingWarning extends StatefulWidget {
  const _BlinkingWarning({required this.text});

  final String text;

  @override
  State<_BlinkingWarning> createState() => _BlinkingWarningState();
}

class _BlinkingWarningState extends State<_BlinkingWarning>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 700),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      // 不闪到全透明：家长扫一眼过来的时候字得还看得见
      opacity: Tween<double>(begin: 1, end: 0.25).animate(_c),
      child: Text(
        widget.text,
        style: TextStyle(
          color: Colors.red.shade700,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _AppTile extends StatelessWidget {
  const _AppTile({required this.app, required this.icon, required this.onTap});

  final InstalledApp app;

  /// 应用自己的图标（PNG 字节），原生侧没给出来时为 null
  final Uint8List? icon;

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final bytes = icon;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 68,
            height: 68,
            child: bytes == null
                ? _letterTile()
                : ClipRRect(
                    borderRadius: BorderRadius.circular(16),
                    child: Image.memory(
                      bytes,
                      width: 68,
                      height: 68,
                      fit: BoxFit.contain,
                      gaplessPlayback: true,
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

  /// 取不到图标时的兜底：色块 + 首字母
  Widget _letterTile() {
    final initial = app.label.isEmpty ? '?' : app.label.substring(0, 1);
    return Container(
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
