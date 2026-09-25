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
  'allowChildLaunch': false,
  'testGuardLeftSec': 0,
  'accessibilityOn': false,
  'settingsFreeMin': 10,
  'fileServerOn': false,
  'fileServerUrl': '',
};

void _mock(Map<String, Object?> Function() config, {List<String>? calls}) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_channel, (call) async {
        // 打开应用这类要核对参数的调用连参数一起记下来
        calls?.add(
          call.method == 'launchApp'
              ? '${call.method}:${call.arguments}'
              : call.method,
        );
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
          case 'launchApp':
            return true;
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
    expect(find.text('不让非白名单应用启动'), findsOneWidget);
    expect(find.textContaining('都要靠它知道前台是哪个应用'), findsOneWidget);
    expect(find.text('修改家长控制密码'), findsOneWidget);
    expect(find.text('修改系统设置密码'), findsOneWidget);
    expect(find.textContaining('未单独设置，目前沿用家长控制密码'), findsOneWidget);
  });

  testWidgets('拦截是两个互相独立的开关，缺省都关着', (tester) async {
    _mock(_config);
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('系统拦截'), findsOneWidget);
    expect(find.text('不让非白名单应用启动'), findsOneWidget);
    expect(find.text('测试拦截（3 分钟）'), findsOneWidget);
    // 两个开关各自的说明里都要写明缺省是关的
    expect(find.textContaining('缺省是关的'), findsNWidgets(2));
  });

  testWidgets('只开「不让非白名单应用启动」时不说「等于没拦」（两开关独立）', (tester) async {
    _mock(() => {..._config(), 'frontGuard': true});
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    // 系统拦截关着，但它不影响这一条：这里只提示缺无障碍，而不是「等于没拦」
    expect(find.textContaining('还没启用本应用，现在拦不住'), findsOneWidget);
    expect(find.textContaining('等于没拦'), findsNothing);
  });

  testWidgets('「允许应用跳转」缺省关着，打开后写明「只认一跳」', (tester) async {
    var on = false;
    _mock(() => {
      ..._config(),
      'launchGuard': true,
      'frontGuard': true,
      'allowChildLaunch': on,
    });
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();

    const title = '允许应用跳转（白名单应用里点开的其它应用）';
    expect(find.text(title), findsOneWidget);
    // 缺省关着：说明里要讲清「照旧弹回桌面」，以及家长自己装 apk 时怎么绕
    expect(find.textContaining('照旧被弹回桌面'), findsOneWidget);
    expect(find.textContaining('放行时长'), findsWidgets);

    on = true;
    await tester.tap(find.widgetWithText(SwitchListTile, title));
    await tester.pumpAndSettle();

    // 两个开关都开着时：写明已生效、只认一跳、以及那一跳不计入单次时长
    expect(find.textContaining('只认一跳'), findsOneWidget);
    expect(find.textContaining('不计入单次时长'), findsOneWidget);
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

  testWidgets('白名单里已勾选的应用可以逐个设「不弹挑战」/「可随意退到桌面」/「桌面不给图标」，改一下就生效', (tester) async {
    _mock(_config);
    AppPickerResult? result;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (ctx) => ElevatedButton(
          onPressed: () => Navigator.of(ctx).push(
            MaterialPageRoute(
              builder: (_) => AppPickerScreen(
                selected: const ['com.b'],
                noChallenge: const [],
                freeExit: const [],
                hideIcon: const [],
                onChanged: (r) => result = r,
              ),
            ),
          ),
          child: const Text('打开'),
        ),
      ),
    ));
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    // 没有保存按钮：还没动过任何开关，一次都还没回调
    expect(find.text('保存'), findsNothing);
    expect(result, isNull);

    // 默认三个开关都是开的（打开要挑战、退回桌面要挑战、桌面显示图标），三份例外名单都是空的
    expect(find.text('打开要挑战 · 退回桌面要挑战 · 桌面显示图标'), findsOneWidget);
    expect(
      find.textContaining('已选 1 个应用：0 个打开免挑战、0 个可随意退到桌面、0 个不在桌面显示'),
      findsOneWidget,
    );

    // 第一个开关管「进入」，第二个管「退出」，第三个管「显示」。
    // 每拨一下就立刻回调一次，四个清单一起给
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    expect(find.text('打开免挑战 · 退回桌面要挑战 · 桌面显示图标'), findsOneWidget);
    expect(result!.allowed, ['com.b']);
    expect(result!.noChallenge, ['com.b']);
    expect(result!.freeExit, isEmpty);

    await tester.tap(find.byType(Switch).at(1));
    await tester.pumpAndSettle();
    expect(find.text('打开免挑战 · 可随意退到桌面 · 桌面显示图标'), findsOneWidget);
    expect(
      find.textContaining('已选 1 个应用：1 个打开免挑战、1 个可随意退到桌面、0 个不在桌面显示'),
      findsOneWidget,
    );
    expect(result!.freeExit, ['com.b']);

    // 关掉「显示」＝ 这个应用不进孩子的桌面，但仍是白名单应用
    await tester.tap(find.byType(Switch).at(2));
    await tester.pumpAndSettle();
    expect(find.text('打开免挑战 · 可随意退到桌面 · 桌面不显示'), findsOneWidget);
    expect(
      find.textContaining('已选 1 个应用：1 个打开免挑战、1 个可随意退到桌面、1 个不在桌面显示'),
      findsOneWidget,
    );
    expect(result!.hideIcon, ['com.b']);
  });

  testWidgets('取消勾选时「可随意退到桌面」「桌面不给图标」的配置跟着一起清掉，不用保存', (tester) async {
    _mock(_config);
    AppPickerResult? result;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (ctx) => ElevatedButton(
          onPressed: () => Navigator.of(ctx).push(
            MaterialPageRoute(
              builder: (_) => AppPickerScreen(
                selected: const ['com.b'],
                noChallenge: const ['com.b'],
                freeExit: const ['com.b'],
                hideIcon: const ['com.b'],
                onChanged: (r) => result = r,
              ),
            ),
          ),
          child: const Text('打开'),
        ),
      ),
    ));
    await tester.tap(find.text('打开'));
    await tester.pumpAndSettle();

    // 点整行 = 取消勾选这个应用：一取消就回一次，三份例外名单跟着清空
    await tester.tap(find.text('数学练习'));
    await tester.pumpAndSettle();
    expect(result!.allowed, isEmpty);
    expect(result!.noChallenge, isEmpty);
    expect(result!.freeExit, isEmpty);
    expect(result!.hideIcon, isEmpty);
  });

  testWidgets('窄屏（360dp）一行要同时放下勾选框、图标、三个开关和「打开」按钮，不溢出', (tester) async {
    _mock(_config);
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      home: AppPickerScreen(
        selected: const ['com.a', 'com.b'],
        noChallenge: const ['com.a'],
        freeExit: const [],
        hideIcon: const [],
        onChanged: (_) {},
      ),
    ));
    await tester.pumpAndSettle();

    // 溢出的话渲染时会抛「A RenderFlex overflowed」，这里就接住了
    expect(tester.takeException(), isNull);
    expect(find.text('计算器'), findsOneWidget);
    expect(find.text('数学练习'), findsOneWidget);
  });

  testWidgets('白名单页每行的「打开」按钮直接拉起那个应用，不弹挑战', (tester) async {
    final calls = <String>[];
    _mock(_config, calls: calls);
    await tester.pumpWidget(MaterialApp(
      home: AppPickerScreen(
        selected: const [],
        noChallenge: const [],
        freeExit: const [],
        hideIcon: const [],
        onChanged: (_) {},
      ),
    ));
    await tester.pumpAndSettle();

    // 没勾选的行也有这个按钮
    expect(find.byTooltip('直接打开「计算器」'), findsOneWidget);
    await tester.tap(find.byTooltip('直接打开「计算器」'));
    await tester.pumpAndSettle();

    expect(calls, contains('launchApp:com.a'));
    expect(find.byType(Dialog), findsNothing);
  });
}
