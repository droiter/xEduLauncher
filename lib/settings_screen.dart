import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_picker.dart';
import 'config.dart';
import 'native.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  LauncherConfig? _cfg;
  String _defaultLauncher = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Native.homeResult.addListener(_onHomeResult);
    _load();
  }

  @override
  void dispose() {
    Native.homeResult.removeListener(_onHomeResult);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 家长可能是去系统设置页里选完桌面才回来的，回来就得重新读一次真实状态
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _onHomeResult() async {
    final r = Native.homeResult.value;
    if (r == null) return;
    await _load();
    if (!mounted) return;
    await _showCopyableDialog(title: '默认桌面', body: r.message, onCopy: _copyDiag);
  }

  Future<void> _load() async {
    final cfg = await Native.config();
    final name = await Native.defaultLauncherName();
    if (!mounted) return;
    setState(() {
      _cfg = cfg;
      _defaultLauncher = name;
    });
  }

  Future<void> _patch(Map<String, dynamic> m) async {
    final cfg = await Native.updateConfig(m);
    if (mounted) setState(() => _cfg = cfg);
  }

  Future<void> _setDefaultHome() async {
    HomeSettingsResult r;
    try {
      // 原生侧若卡住不回复，这里也要有个结果，不能点了没反应
      r = await Native.openHomeSettings().timeout(const Duration(seconds: 8));
    } catch (e) {
      r = HomeSettingsResult(code: 'error', detail: '$e');
    }
    await _load();
    if (!mounted) return;
    if (r.code == 'role') {
      // 系统弹框这会儿正盖在应用上面，用对话框去抢只会被压住；结局由 onHomeResult 补
      _toast('已请系统弹出确认框，请选「设置」');
      return;
    }
    await _showCopyableDialog(
      title: '默认桌面',
      body: r.message,
      onCopy: _copyDiag,
    );
  }

  Future<void> _showDiag() async {
    final report = await Native.launcherDiag();
    if (!mounted) return;
    await _showCopyableDialog(
      title: '桌面自检',
      body: report,
      onCopy: () async => Clipboard.setData(ClipboardData(text: report)),
    );
  }

  Future<void> _copyDiag() async {
    final report = await Native.launcherDiag();
    await Clipboard.setData(ClipboardData(text: report));
    _toast('自检日志已复制，粘贴给我即可');
  }

  /// 内容可选可滚动、带「复制」按钮的对话框
  Future<void> _showCopyableDialog({
    required String title,
    required String body,
    required Future<void> Function() onCopy,
  }) {
    return showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: SelectableText(
              body,
              style: const TextStyle(fontSize: 12.5, height: 1.45),
            ),
          ),
        ),
        actions: [
          TextButton.icon(
            onPressed: () async {
              await onCopy();
              if (ctx.mounted) Navigator.pop(ctx);
            },
            icon: const Icon(Icons.copy_all, size: 18),
            label: const Text('复制'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('知道了'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cfg = _cfg;
    return Scaffold(
      appBar: AppBar(title: const Text('家长设置')),
      body: cfg == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                _section('挑战设置'),
                ListTile(
                  title: const Text('挑战类型'),
                  subtitle: Text(cfg.challengeLabel),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _chooseChallengeType,
                ),
                SwitchListTile(
                  title: const Text('按 Home 键回到桌面时挑战'),
                  subtitle: const Text(
                    '只拦「从别的应用按 Home 逃回桌面」，孩子本来就站在桌面上时不打扰。\n'
                    '答对才回到桌面；答错或取消，就把他送回刚才那个应用',
                  ),
                  value: cfg.chOnHome,
                  onChanged: (v) => _patch({'chOnHome': v}),
                ),
                SwitchListTile(
                  title: const Text('启动应用时挑战（总开关）'),
                  subtitle: const Text('关掉则所有应用都直接打开；单独放行某个应用去白名单里设'),
                  value: cfg.chOnLaunch,
                  onChanged: (v) => _patch({'chOnLaunch': v}),
                ),
                _section('使用限制'),
                _sliderTile(
                  title: '每日使用时长上限',
                  value: cfg.dailyLimitMin,
                  min: 0,
                  max: 240,
                  divisions: 16,
                  label: cfg.dailyLimitMin == 0 ? '不限' : '${cfg.dailyLimitMin} 分钟',
                  onPreview: (v) => _cfg!.dailyLimitMin = v,
                  onCommit: (v) => _patch({'dailyLimitMin': v}),
                ),
                _sliderTile(
                  title: '输入密码后宽限时间',
                  value: cfg.graceMin,
                  min: 5,
                  max: 60,
                  divisions: 11,
                  label: '${cfg.graceMin} 分钟',
                  onPreview: (v) => _cfg!.graceMin = v,
                  onCommit: (v) => _patch({'graceMin': v}),
                ),
                _sliderTile(
                  title: '每日打开次数上限',
                  value: cfg.openLimit,
                  min: 0,
                  max: 20,
                  divisions: 20,
                  label: cfg.openLimit == 0 ? '不限' : '${cfg.openLimit} 次',
                  onPreview: (v) => _cfg!.openLimit = v,
                  onCommit: (v) => _patch({'openLimit': v}),
                ),
                SwitchListTile(
                  title: const Text('启用后台计时守护'),
                  subtitle: Text(
                    cfg.hasOverlay
                        ? '达到上限时将在任何界面弹出密码页'
                        : '需先授予「显示在其他应用上层」权限',
                  ),
                  value: cfg.guardEnabled,
                  onChanged: _toggleGuard,
                ),

                _section('允许访问的应用'),
                ListTile(
                  title: const Text('应用白名单'),
                  subtitle: Text(
                    cfg.allowed.isEmpty
                        ? '尚未添加，孩子只能看到「家长设置」'
                        : '已允许 ${cfg.allowed.length} 个应用'
                              '${cfg.noChallenge.isEmpty ? '' : '，其中 ${cfg.noChallenge.length} 个打开时不弹挑战'}',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _pickApps,
                ),

                _section('防绕过'),
                SwitchListTile(
                  title: const Text('前台守护（防任务键切换）'),
                  subtitle: Text(
                    !cfg.frontGuard
                        ? '打开后，孩子按任务键切回后台的应用会被立刻送回桌面，不弹挑战框'
                        : cfg.accessibilityOn
                        ? '已生效：非白名单应用一露头就送回桌面，任务键也不再弹挑战框'
                        : '开关已打开，但系统「无障碍」里还没启用，去下面那一项打开',
                  ),
                  value: cfg.frontGuard,
                  onChanged: _toggleFrontGuard,
                ),
                ListTile(
                  title: const Text('无障碍权限'),
                  subtitle: Text(
                    cfg.accessibilityOn
                        ? '已启用'
                        : '未启用（前台守护必需，安卓只能靠它知道前台是哪个应用）',
                  ),
                  trailing: const Icon(Icons.open_in_new),
                  onTap: () async {
                    await Native.openAccessibilitySettings();
                    await _load();
                  },
                ),
                _sliderTile(
                  title: '家长进系统设置时的放行时长',
                  value: cfg.settingsFreeMin,
                  min: 5,
                  max: 60,
                  divisions: 11,
                  label: '${cfg.settingsFreeMin} 分钟',
                  onPreview: (v) => _cfg!.settingsFreeMin = v,
                  onCommit: (v) => _patch({'settingsFreeMin': v}),
                ),

                _section('权限与桌面'),
                ListTile(
                  title: const Text('默认桌面'),
                  subtitle: Text(
                    cfg.isDefaultLauncher
                        ? '当前已是本应用'
                        : '当前是「$_defaultLauncher」，点击切换',
                  ),
                  trailing: const Icon(Icons.open_in_new),
                  onTap: _setDefaultHome,
                ),
                ListTile(
                  title: const Text('桌面自检'),
                  subtitle: const Text('设不上默认桌面时点这里，可一键复制发给开发者'),
                  trailing: const Icon(Icons.bug_report_outlined),
                  onTap: _showDiag,
                ),
                ListTile(
                  title: const Text('悬浮窗权限'),
                  subtitle: Text(cfg.hasOverlay ? '已授予' : '未授予（超时锁屏必需）'),
                  trailing: const Icon(Icons.open_in_new),
                  onTap: () async {
                    await Native.requestOverlay();
                    await _load();
                  },
                ),
                ListTile(
                  title: const Text('通知权限'),
                  subtitle: const Text('用于显示守护服务常驻通知'),
                  trailing: const Icon(Icons.open_in_new),
                  onTap: () => Native.requestNotification(),
                ),

                _section('密码'),
                ListTile(
                  title: const Text('修改家长控制密码'),
                  subtitle: const Text('默认 123456，请尽快修改；进入家长设置、解锁超时都要用它'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _changePassword,
                ),
                ListTile(
                  title: const Text('修改系统设置密码'),
                  subtitle: Text(
                    cfg.settingsPwCustom
                        ? '已单独设置，孩子拿不到这个密码就进不去系统设置'
                        : '未单独设置，目前沿用家长控制密码',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _changeSettingsPassword,
                ),

                _section('今日统计'),
                ListTile(
                  title: const Text('已使用时长'),
                  subtitle: Text('${(cfg.usedSeconds / 60).floor()} 分钟'),
                ),
                ListTile(
                  title: const Text('打开次数'),
                  subtitle: Text('${cfg.openCount} 次'),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          onPressed: () async {
                            final c = await Native.resetStats();
                            if (mounted) setState(() => _cfg = c);
                          },
                          child: const Text('重置今日统计'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: OutlinedButton(
                          onPressed: () => Native.showLock('time'),
                          child: const Text('测试锁屏'),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  // ---------- 交互 ----------

  Future<void> _toggleGuard(bool v) async {
    final cfg = _cfg!;
    if (v && !cfg.hasOverlay) {
      final go = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('需要悬浮窗权限'),
          content: const Text('后台计时到点后，需要此权限才能在其他应用之上弹出密码页。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('去授权'),
            ),
          ],
        ),
      );
      if (go != true) return;
      await Native.requestOverlay();
      await _load();
      return;
    }
    final c = await Native.setGuard(v);
    if (mounted) setState(() => _cfg = c);
  }

  Future<void> _chooseChallengeType() async {
    final cfg = _cfg!;
    const options = {
      'none': '不需要挑战',
      'mul': '一位数乘法（如 7×8）',
      'add': '两位数加法（如 34+58）',
      'password': '家长密码',
    };
    final picked = await showDialog<String>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('挑战类型'),
        children: [
          RadioGroup<String>(
            groupValue: cfg.challengeType,
            onChanged: (v) => Navigator.pop(ctx, v),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final e in options.entries)
                  RadioListTile<String>(value: e.key, title: Text(e.value)),
              ],
            ),
          ),
        ],
      ),
    );
    if (picked != null) await _patch({'challengeType': picked});
  }

  Future<void> _pickApps() async {
    final cfg = _cfg!;
    final res = await Navigator.of(context).push<AppPickerResult>(
      MaterialPageRoute(
        builder: (_) => AppPickerScreen(
          selected: cfg.allowed,
          noChallenge: cfg.noChallenge,
        ),
      ),
    );
    if (res == null) return;
    // 两个字段一起提交：原生侧要靠 allowed 清掉已经被移除应用的免挑战配置
    await _patch({'allowed': res.allowed, 'noChallenge': res.noChallenge});
  }

  Future<void> _toggleFrontGuard(bool v) async {
    final cfg = _cfg!;
    await _patch({'frontGuard': v});
    if (!v) return;

    if (!cfg.isDefaultLauncher) {
      _toast('当前还不是默认桌面，前台守护要等设成默认桌面后才生效');
    }
    if (cfg.accessibilityOn || !mounted) return;

    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('还需要打开「无障碍」'),
        content: const Text(
          '安卓上只有「无障碍」能让应用实时知道当前前台是哪个应用。\n\n'
          '打开后：孩子按任务键（最近任务）切回一个后台跑着的应用，'
          '比如之前打开过的系统设置，会被立刻送回儿童桌面。\n\n'
          '接下来会跳到系统页面，请在列表里找到「儿童桌面」并打开它。\n'
          '本应用只用它做这一件事，不读取屏幕内容、不联网。',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('稍后')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('去打开')),
        ],
      ),
    );
    if (go != true) return;
    await Native.openAccessibilitySettings();
    await _load();
  }

  Future<void> _changePassword() async {
    final pw = await _askNewPassword(title: '修改家长控制密码');
    if (pw == null) return;
    await _patch({'password': pw});
    _toast('家长控制密码已更新');
  }

  Future<void> _changeSettingsPassword() async {
    final pw = await _askNewPassword(
      title: '修改系统设置密码',
      hint: '新密码（至少 4 位，留空则沿用家长控制密码）',
      allowEmpty: true,
    );
    if (pw == null) return;
    await _patch({'settingsPassword': pw});
    _toast(pw.isEmpty ? '已改为沿用家长控制密码' : '系统设置密码已更新');
  }

  /// 两次输入的新密码框。返回 null = 取消或输入不合法；allowEmpty 时留空返回空串。
  Future<String?> _askNewPassword({
    required String title,
    String hint = '新密码（至少 4 位）',
    bool allowEmpty = false,
  }) async {
    final ctrl = TextEditingController();
    final confirm = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: ctrl,
              autofocus: true,
              obscureText: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(labelText: hint),
            ),
            TextField(
              controller: confirm,
              obscureText: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(labelText: '再输一次'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('保存')),
        ],
      ),
    );
    if (ok != true) return null;

    final pw = ctrl.text.trim();
    if (pw.isEmpty) {
      if (allowEmpty) return '';
      _toast('密码至少 4 位');
      return null;
    }
    if (pw.length < 4) {
      _toast('密码至少 4 位');
      return null;
    }
    if (pw != confirm.text.trim()) {
      _toast('两次输入不一致');
      return null;
    }
    return pw;
  }

  void _toast(String s) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s)));
  }

  Widget _section(String title) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
    child: Text(
      title,
      style: TextStyle(
        color: Theme.of(context).colorScheme.primary,
        fontWeight: FontWeight.bold,
        fontSize: 13,
      ),
    ),
  );

  Widget _sliderTile({
    required String title,
    required int value,
    required int min,
    required int max,
    required int divisions,
    required String label,
    required void Function(int) onPreview,
    required Future<void> Function(int) onCommit,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ListTile(dense: true, title: Text(title), trailing: Text(label)),
        Slider(
          value: value.toDouble().clamp(min.toDouble(), max.toDouble()),
          min: min.toDouble(),
          max: max.toDouble(),
          divisions: divisions,
          label: label,
          onChanged: (v) => setState(() => onPreview(v.round())),
          onChangeEnd: (v) => onCommit(v.round()),
        ),
      ],
    );
  }
}
