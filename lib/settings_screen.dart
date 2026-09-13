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
                  value: cfg.chOnHome,
                  onChanged: (v) => _patch({'chOnHome': v}),
                ),
                SwitchListTile(
                  title: const Text('启动应用时挑战'),
                  value: cfg.chOnLaunch,
                  onChanged: (v) => _patch({'chOnLaunch': v}),
                ),
                SwitchListTile(
                  title: const Text('按返回键时挑战'),
                  value: cfg.chOnBack,
                  onChanged: (v) => _patch({'chOnBack': v}),
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
                        : '已允许 ${cfg.allowed.length} 个应用',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _pickApps,
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

                _section('家长密码'),
                ListTile(
                  title: const Text('修改家长密码'),
                  subtitle: const Text('默认密码 123456，请尽快修改'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _changePassword,
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
    final res = await Navigator.of(context).push<List<String>>(
      MaterialPageRoute(builder: (_) => AppPickerScreen(selected: cfg.allowed)),
    );
    if (res != null) await _patch({'allowed': res});
  }

  Future<void> _changePassword() async {
    final ctrl = TextEditingController();
    final confirm = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改家长密码'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: ctrl,
              autofocus: true,
              obscureText: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(labelText: '新密码（至少 4 位）'),
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
    if (ok != true) return;

    final pw = ctrl.text.trim();
    if (pw.length < 4) {
      _toast('密码至少 4 位');
      return;
    }
    if (pw != confirm.text.trim()) {
      _toast('两次输入不一致');
      return;
    }
    await _patch({'password': pw});
    _toast('密码已更新');
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
