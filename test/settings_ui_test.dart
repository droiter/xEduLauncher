import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:child_launcher/app_picker.dart';
import 'package:child_launcher/settings_screen.dart';

const _channel = MethodChannel('child_launcher/native');

Map<String, Object?> _config({
  List<String> noChallenge = const [],
  List<String> freeExit = const [],
  List<String> hideIcon = const [],
}) => {
  'password': '123456',
  'settingsPassword': '123456',
  'settingsPwCustom': false,
  'challengeType': 'mul',
  'chOnHome': true,
  'chOnLaunch': true,
  'chOnBack': true,
  'allowed': ['com.a', 'com.b'],
  'noChallenge': noChallenge,
  'freeExit': freeExit,
  'hideIcon': hideIcon,
  'dailyLimitMin': 30,
  'singleUseMin': 5,
  'graceMin': 10,
  'openLimit': 0,
  'usedSeconds': 60,
  'extraSeconds': 0,
  'openCount': 2,
  'isDefaultLauncher': true,
  'hasOverlay': true,
  'guardEnabled': false,
  'frontGuard': false,
  'launchGuard': false,
  'testGuardLeftSec': 0,
  'accessibilityOn': false,
  'settingsFreeMin': 10,
  'fileServerOn': false,
  'fileServerUrl': '',
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
          case 'setFileServer':
            return config();
          case 'setTestGuard':
            return config();
        }
        return null;
      });
}

void main() {
  /// 把测试画布放高，整页设置一次全部渲染出来，免得靠滚动去够下面的条目
  void useTallScreen(WidgetTester tester) {
    tester.view.physicalSize = const Size(1200, 5600);
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
    expect(find.textContaining('都要靠它知道前台是哪个应用'), findsOneWidget);
    expect(find.text('修改家长控制密码'), findsOneWidget);
    expect(find.text('修改系统设置密码'), findsOneWidget);
    expect(find.textContaining('未单独设置，目前沿用家长控制密码'), findsOneWidget);
  });

  testWidgets('拦截总闸缺省关着，说明里写清「关掉就整机不设防」', (tester) async {
    _mock(_config);
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('启动拦截（总闸）'), findsOneWidget);
    expect(find.text('测试拦截（3 分钟）'), findsOneWidget);
    expect(find.textContaining('整机不设防'), findsWidgets);
    expect(find.textContaining('缺省是关的'), findsOneWidget);
  });

  testWidgets('前台守护开着但总闸关着时，说明里点出「等于没拦」', (tester) async {
    _mock(() => {..._config(), 'frontGuard': true});
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.textContaining('等于没拦'), findsOneWidget);
  });

  testWidgets('开「测试拦截」后倒计时往下走，到点自己关掉', (tester) async {
    var left = 0;
    _mock(() => {..._config(), 'testGuardLeftSec': left});
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();
    expect(find.textContaining('测试中'), findsNothing);

    left = 3; // 原生侧返回「还剩 3 秒」，省得在测试里等三分钟
    await tester.tap(find.widgetWithText(SwitchListTile, '测试拦截（3 分钟）'));
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('还剩 0:03'), findsOneWidget);

    await tester.pump(const Duration(seconds: 1));
    expect(find.textContaining('还剩 0:02'), findsOneWidget);
    await tester.pump(const Duration(seconds: 1));
    expect(find.textContaining('还剩 0:01'), findsOneWidget);

    // 到点：再读一次配置（原生侧此刻已经返回 0），开关自己弹回去
    left = 0;
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    await tester.pump();
    expect(find.textContaining('测试中，还剩'), findsNothing);
    expect(find.textContaining('到时自动关掉，不用改'), findsOneWidget);
  });

  testWidgets('单次使用时长上限：可调，且说明里写明「离开只是暂停、剩余留着」', (tester) async {
    _mock(_config);
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('单次使用时长上限'), findsOneWidget);
    expect(find.text('5 分钟'), findsOneWidget);
    expect(find.textContaining('答错了把他送回儿童桌面'), findsOneWidget);
    expect(find.textContaining('只是暂停计时，剩余时间留着'), findsOneWidget);
  });

  testWidgets('单次使用时长上限可以设成「不限」', (tester) async {
    _mock(() => {..._config(), 'singleUseMin': 0, 'openLimit': 3});
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('不限'), findsOneWidget);
    expect(find.text('3 次'), findsOneWidget);
  });

  testWidgets('文件传输：默认关着，打开后显示访问地址', (tester) async {
    var on = false;
    _mock(
      () => {
        ..._config(),
        'fileServerOn': on,
        'fileServerUrl': on ? 'http://192.168.1.23:8080' : '',
      },
    );
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('文件传输服务'), findsOneWidget);
    expect(find.textContaining('同一 Wi-Fi 下的电脑浏览器'), findsOneWidget);
    expect(find.text('复制访问地址'), findsNothing, reason: '没开就没什么地址可复制');

    on = true;
    await tester.tap(find.widgetWithText(SwitchListTile, '文件传输服务'));
    await tester.pumpAndSettle();
    expect(find.text('开启文件传输'), findsOneWidget, reason: '要先跟家长说清楚同意机制');
    await tester.tap(find.text('开启'));
    await tester.pump();
    // 代码里等 800ms 再读一次地址（端口要等原生侧真的 bind 上才有）
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();

    expect(find.text('复制访问地址'), findsOneWidget);
    expect(find.textContaining('http://192.168.1.23:8080'), findsWidgets);
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

  testWidgets('白名单里已勾选的应用可以逐个设「不弹挑战」/「可随意退到桌面」/「桌面不给图标」', (tester) async {
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
                  freeExit: [],
                  hideIcon: [],
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

    // 默认三个开关都是开的（打开要挑战、退回桌面要挑战、桌面显示图标），三份例外名单都是空的
    expect(find.text('打开要挑战 · 退回桌面要挑战 · 桌面显示图标'), findsOneWidget);
    expect(
      find.textContaining('已选 1 个应用：0 个打开免挑战、0 个可随意退到桌面、0 个不在桌面显示'),
      findsOneWidget,
    );

    // 第一个开关管「进入」，第二个管「退出」，第三个管「显示」
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    expect(find.text('打开免挑战 · 退回桌面要挑战 · 桌面显示图标'), findsOneWidget);

    await tester.tap(find.byType(Switch).at(1));
    await tester.pumpAndSettle();
    expect(find.text('打开免挑战 · 可随意退到桌面 · 桌面显示图标'), findsOneWidget);
    expect(
      find.textContaining('已选 1 个应用：1 个打开免挑战、1 个可随意退到桌面、0 个不在桌面显示'),
      findsOneWidget,
    );

    // 关掉「显示」＝ 这个应用不进孩子的桌面，但仍是白名单应用
    await tester.tap(find.byType(Switch).at(2));
    await tester.pumpAndSettle();
    expect(find.text('打开免挑战 · 可随意退到桌面 · 桌面不显示'), findsOneWidget);
    expect(
      find.textContaining('已选 1 个应用：1 个打开免挑战、1 个可随意退到桌面、1 个不在桌面显示'),
      findsOneWidget,
    );

    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(result!.allowed, ['com.b']);
    expect(result!.noChallenge, ['com.b']);
    expect(result!.freeExit, ['com.b']);
    expect(result!.hideIcon, ['com.b']);
  });

  testWidgets('取消勾选时「可随意退到桌面」「桌面不给图标」的配置跟着一起清掉', (tester) async {
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
                  noChallenge: ['com.b'],
                  freeExit: ['com.b'],
                  hideIcon: ['com.b'],
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

    // 点整行 = 取消勾选这个应用
    await tester.tap(find.text('数学练习'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(result!.allowed, isEmpty);
    expect(result!.noChallenge, isEmpty);
    expect(result!.freeExit, isEmpty);
    expect(result!.hideIcon, isEmpty);
  });
}
