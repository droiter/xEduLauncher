import 'package:flutter/material.dart';

import 'native.dart';

/// 从已安装应用里挑选允许孩子访问的清单。
class AppPickerScreen extends StatefulWidget {
  const AppPickerScreen({super.key, required this.selected});

  final List<String> selected;

  @override
  State<AppPickerScreen> createState() => _AppPickerScreenState();
}

class _AppPickerScreenState extends State<AppPickerScreen> {
  List<InstalledApp>? _apps;
  late Set<String> _picked;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _picked = widget.selected.toSet();
    Native.listApps().then((v) {
      if (mounted) setState(() => _apps = v);
    });
  }

  @override
  Widget build(BuildContext context) {
    final apps = _apps
        ?.where((a) =>
            a.label.toLowerCase().contains(_query.toLowerCase()) ||
            a.package.toLowerCase().contains(_query.toLowerCase()))
        .toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('允许访问的应用'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(_picked.toList()..sort()),
            child: const Text('保存'),
          ),
        ],
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
                      return CheckboxListTile(
                        value: _picked.contains(a.package),
                        title: Text(a.label),
                        subtitle: Text(
                          a.package,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11),
                        ),
                        onChanged: (v) => setState(() {
                          if (v == true) {
                            _picked.add(a.package);
                          } else {
                            _picked.remove(a.package);
                          }
                        }),
                      );
                    },
                  ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '已选 ${_picked.length} 个应用',
                style: TextStyle(color: Theme.of(context).hintColor),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
