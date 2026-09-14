import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'native.dart';

/// 白名单选择结果：允许的应用 + 其中「点开直接进、不弹挑战」的那部分。
class AppPickerResult {
  const AppPickerResult({required this.allowed, required this.noChallenge});

  final List<String> allowed;
  final List<String> noChallenge;
}

/// 从已安装应用里挑选允许孩子访问的清单，并逐个决定打开时要不要弹挑战。
class AppPickerScreen extends StatefulWidget {
  const AppPickerScreen({
    super.key,
    required this.selected,
    required this.noChallenge,
  });

  final List<String> selected;
  final List<String> noChallenge;

  @override
  State<AppPickerScreen> createState() => _AppPickerScreenState();
}

class _AppPickerScreenState extends State<AppPickerScreen> {
  List<InstalledApp>? _apps;
  Map<String, Uint8List> _icons = const {};
  late Set<String> _picked;
  late Set<String> _free;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _picked = widget.selected.toSet();
    _free = widget.noChallenge.toSet();
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
      }
    });
  }

  void _save() {
    final allowed = _picked.toList()..sort();
    final free = _free.where(_picked.contains).toList()..sort();
    Navigator.of(context).pop(AppPickerResult(allowed: allowed, noChallenge: free));
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
                              ? (free ? '打开时不需要挑战，直接进入' : '打开时需要挑战')
                              : a.package,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: picked && free
                                ? Theme.of(context).colorScheme.primary
                                : null,
                          ),
                        ),
                        trailing: picked
                            ? Switch(
                                value: !free,
                                onChanged: (v) => setState(() {
                                  if (v) {
                                    _free.remove(a.package);
                                  } else {
                                    _free.add(a.package);
                                  }
                                }),
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
                    '已选 ${_picked.length} 个应用，其中 $freeCount 个打开时不弹挑战',
                    style: TextStyle(color: Theme.of(context).hintColor),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '右侧开关打开 = 打开这个应用时要先过挑战；关掉则孩子点开就直接进。',
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
