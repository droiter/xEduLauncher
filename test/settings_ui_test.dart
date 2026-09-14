import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:child_launcher/app_picker.dart';
import 'package:child_launcher/settings_screen.dart';

const _channel = MethodChannel('child_launcher/native');

Map<String, Object?> _config({List<String> noChallenge = const []}) => {
  'password': '123456',
  'settingsPassword': '123456',
  'settingsPwCustom': false,
  'challengeType': 'mul',
  'chOnHome': true,
  'chOnLaunch': true,
  'chOnBack': true,
  'allowed': ['com.a', 'com.b'],
  'noChallenge': noChallenge,
  'dailyLimitMin': 30,
  'graceMin': 10,
  'openLimit': 0,
  'usedSeconds': 60,
  'extraSeconds': 0,
  'openCount': 2,
  'isDefaultLauncher': true,
  'hasOverlay': true,
  'guardEnabled': false,
  'frontGuard': false,
  'accessibilityOn': false,
  'settingsFreeMin': 10,
};

void _mock(Map<String, Object?> Function() config) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_channel, (call) async {
        switch (call.method) {
          case 'config':
            return config();
          case 'defaultLauncherName':
            return '儿童桌面';
          case 'listApps':
            return [
              {'package': 'com.a', 'label': '计算器'},
              {'package': 'com.b', 'label': '数学练习'},
              {'package': 'com.c', 'label': '设置'},
            ];
          case 'updateConfig':
            return config();
        }
        return null;
      });
}

void main() {
  /// 把测试画布放高，整页设置一次全部渲染出来，免得靠滚动去够下面的条目
  void useTallScreen(WidgetTester tester) {
    tester.view.physicalSize = const Size(1200, 4200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
  }

  testWidgets('家长设置页渲染出新旧全部配置项', (tester) async {
    _mock(_config);
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('启动应用时挑战（总开关）'), findsOneWidget);
    expect(find.textContaining('已允许 2 个应用'), findsOneWidget);
    expect(find.text('前台守护（防任务键切换）'), findsOneWidget);
    expect(find.textContaining('安卓只能靠它知道前台是哪个应用'), findsOneWidget);
    expect(find.text('修改家长控制密码'), findsOneWidget);
    expect(find.text('修改系统设置密码'), findsOneWidget);
    expect(find.textContaining('未单独设置，目前沿用家长控制密码'), findsOneWidget);
  });

  testWidgets('已单独设过系统设置密码时提示会变', (tester) async {
    _mock(() => {
      ..._config(),
      'settingsPwCustom': true,
      'settingsPassword': '8888',
    });
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();
    expect(find.textContaining('孩子拿不到这个密码'), findsOneWidget);
  });

  testWidgets('白名单里已勾选的应用可以单独设为「不弹挑战」', (tester) async {
    _mock(_config);
    AppPickerResult? result;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (ctx) => ElevatedButton(
          onPressed: () async {
            result = await Navigator.of(ctx).push<AppPickerResult>(
              MaterialPageRoute(
                builder: (_) => const AppPickerScreen(
                  selected: ['com.b'],
                  noChallenge: [],
                ),
              ),
            );
          },
          child: const Text('打开'),
        ),
      ),
    ));
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    // 勾选项默认「打开时需要挑战」，此时不弹挑战名单为空
    expect(find.text('打开时需要挑战'), findsOneWidget);
    expect(find.textContaining('已选 1 个应用，其中 0 个打开时不弹挑战'), findsOneWidget);

    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    expect(find.text('打开时不需要挑战，直接进入'), findsOneWidget);

    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(result!.allowed, ['com.b']);
    expect(result!.noChallenge, ['com.b']);
  });
}
