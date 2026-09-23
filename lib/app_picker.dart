import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'native.dart';

/// 白名单选择结果：允许的应用 + 其中「点开直接进、不弹挑战」的那部分 +
/// 「从这个应用退回桌面时不弹挑战」的那部分 + 「桌面上不给图标入口」的那部分。
class AppPickerResult {
  const AppPickerResult({
    required this.allowed,
    required this.noChallenge,
    required this.freeExit,
    required this.hideIcon,
  });

  final List<String> allowed;
  final List<String> noChallenge;
  final List<String> freeExit;
  final List<String> hideIcon;
}

/// 从已安装应用里挑选允许孩子访问的清单，并逐个决定三件事：打开时要不要弹挑战、
/// 从这个应用退回桌面时要不要弹挑战、要不要把图标摆到孩子的桌面上。
class AppPickerScreen extends StatefulWidget {
  const AppPickerScreen({
    super.key,
    required this.selected,
    required this.noChallenge,
    required this.freeExit,
    required this.hideIcon,
  });

  final List<String> selected;
  final List<String> noChallenge;
  final List<String> freeExit;
  final List<String> hideIcon;

  @override
  State<AppPickerScreen> createState() => _AppPickerScreenState();
}

class _AppPickerScreenState extends State<AppPickerScreen> {
  List<InstalledApp>? _apps;
  Map<String, Uint8List> _icons = const {};
  late Set<String> _picked;
  late Set<String> _free;
  late Set<String> _exitFree;
  late Set<String> _hidden;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _picked = widget.selected.toSet();
    _free = widget.noChallenge.toSet();
    _exitFree = widget.freeExit.toSet();
    _hidden = widget.hideIcon.toSet();
    _load();
  }

  /// 列表先上屏，图标随后补——这台设备上一两百个应用的图标要转一会儿，
  /// 没必要让家长对着转圈等
  Future<void> _load() async {
    final apps = await Native.listApps();
    if (!mounted) return;
    setState(() => _apps = apps);

    final icons = await Native.appIcons(apps.map((a) => a.package).toList());
    if (!mounted) return;
    setState(() => _icons = icons);
  }

  void _toggle(String pkg, bool on) {
    setState(() {
      if (on) {
        _picked.add(pkg);
      } else {
        _picked.remove(pkg);
        _free.remove(pkg);
        _exitFree.remove(pkg);
        _hidden.remove(pkg);
      }
    });
  }

  void _save() {
    final allowed = _picked.toList()..sort();
    final free = _free.where(_picked.contains).toList()..sort();
    final exitFree = _exitFree.where(_picked.contains).toList()..sort();
    final hidden = _hidden.where(_picked.contains).toList()..sort();
    Navigator.of(context).pop(
      AppPickerResult(
        allowed: allowed,
        noChallenge: free,
        freeExit: exitFree,
        hideIcon: hidden,
      ),
    );
  }

  /// 一行里挤三个开关：用 10sp 小字标出各自管什么，开关本身缩到 42×30，
  /// 免得把应用名挤没了（FittedBox 连布局尺寸一起缩，不给列表撑高）
  Widget _miniSwitch(
    String label, {
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label, style: TextStyle(fontSize: 10, color: Theme.of(context).hintColor)),
        SizedBox(
          height: 30,
          width: 42,
          child: FittedBox(
            child: Switch(value: value, onChanged: onChanged),
          ),
        ),
      ],
    );
  }

  /// 应用自己的图标；还没拿到或取不到时留出同样宽度的空位，免得整列字左右跳
  Widget _icon(String pkg) {
    final bytes = _icons[pkg];
    if (bytes == null) return const SizedBox(width: 44);
    return Padding(
      padding: const EdgeInsets.only(left: 4, right: 8),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(9),
        child: Image.memory(
          bytes,
          width: 36,
          height: 36,
          fit: BoxFit.contain,
          gaplessPlayback: true,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final apps = _apps
        ?.where((a) =>
            a.label.toLowerCase().contains(_query.toLowerCase()) ||
            a.package.toLowerCase().contains(_query.toLowerCase()))
        .toList();

    final freeCount = _free.where(_picked.contains).length;
    final exitFreeCount = _exitFree.where(_picked.contains).length;
    final hiddenCount = _hidden.where(_picked.contains).length;

    return Scaffold(
      appBar: AppBar(
        title: const Text('允许访问的应用'),
        actions: [TextButton(onPressed: _save, child: const Text('保存'))],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.search),
                hintText: '搜索应用',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              onChanged: (v) => setState(() => _query = v),
            ),
          ),
          Expanded(
            child: apps == null
                ? const Center(child: CircularProgressIndicator())
                : ListView.builder(
                    itemCount: apps.length,
                    itemBuilder: (_, i) {
                      final a = apps[i];
                      final picked = _picked.contains(a.package);
                      final free = _free.contains(a.package);
                      final exitFree = _exitFree.contains(a.package);
                      final hidden = _hidden.contains(a.package);
                      return ListTile(
                        leading: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Checkbox(
                              value: picked,
                              onChanged: (v) => _toggle(a.package, v == true),
                            ),
                            _icon(a.package),
                          ],
                        ),
                        title: Text(a.label),
                        subtitle: Text(
                          picked
                              ? '${free ? '打开免挑战' : '打开要挑战'} · '
                                    '${exitFree ? '可随意退到桌面' : '退回桌面要挑战'} · '
                                    '${hidden ? '桌面不显示' : '桌面显示图标'}'
                              : a.package,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: picked && (free || exitFree || hidden)
                                ? Theme.of(context).colorScheme.primary
                                : null,
                          ),
                        ),
                        trailing: picked
                            ? Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  _miniSwitch(
                                    '进入',
                                    value: !free,
                                    onChanged: (v) => setState(() {
                                      if (v) {
                                        _free.remove(a.package);
                                      } else {
                                        _free.add(a.package);
                                      }
                                    }),
                                  ),
                                  const SizedBox(width: 4),
                                  _miniSwitch(
                                    '退出',
                                    value: !exitFree,
                                    onChanged: (v) => setState(() {
                                      if (v) {
                                        _exitFree.remove(a.package);
                                      } else {
                                        _exitFree.add(a.package);
                                      }
                                    }),
                                  ),
                                  const SizedBox(width: 4),
                                  _miniSwitch(
                                    '显示',
                                    value: !hidden,
                                    onChanged: (v) => setState(() {
                                      if (v) {
                                        _hidden.remove(a.package);
                                      } else {
                                        _hidden.add(a.package);
                                      }
                                    }),
                                  ),
                                ],
                              )
                            : null,
                        onTap: () => _toggle(a.package, !picked),
                      );
                    },
                  ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '已选 ${_picked.length} 个应用：$freeCount 个打开免挑战、'
                    '$exitFreeCount 个可随意退到桌面、$hiddenCount 个不在桌面显示',
                    style: TextStyle(color: Theme.of(context).hintColor),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '「进入」开关打开 = 点开这个应用要先过挑战，关掉则直接进。\n'
                    '「退出」开关打开 = 他在这个应用里按 Home 键、或一路按返回键退回桌面时要先过挑战；'
                    '关掉则随时可以退出来、直接落到桌面。\n'
                    '「显示」开关打开 = 桌面上摆出这个应用的图标；关掉则不给入口——它照样算白名单应用'
                    '（打得开、受管控、照计时），只是孩子的桌面上看不到、点不着它。',
                    style: TextStyle(color: Theme.of(context).hintColor, fontSize: 12),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
