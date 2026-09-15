import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:child_launcher/home_screen.dart';
import 'package:child_launcher/native.dart';

const _channel = MethodChannel('child_launcher/native');

/// 每个用例用各自的应用包名：Native 里的图标缓存是静态的，同一个包名会串味
Map<String, Object?> _config(
  String pkg, {
  bool coldStartHome = false,
  bool chOnHome = true,
}) => {
  'challengeType': 'mul',
  'chOnLaunch': true,
  'chOnHome': chOnHome,
  'allowed': [pkg],
  'noChallenge': const <String>[],
  'dailyLimitMin': 0,
  'isDefaultLauncher': true,
  'coldStartHome': coldStartHome,
};

void _mock(
  String pkg,
  Map<String, Uint8List> icons, {
  List<String>? calls,
  bool coldStartHome = false,
  bool chOnHome = true,
  Future<void>? configGate,
}) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_channel, (call) async {
        calls?.add(call.method);
        switch (call.method) {
          case 'config':
            if (configGate != null) await configGate;
            return _config(pkg, coldStartHome: coldStartHome, chOnHome: chOnHome);
          case 'listApps':
            return [
              {'package': pkg, 'label': '计算器'},
            ];
          case 'appIcons':
            return icons;
          case 'returnToLastApp':
            return true;
        }
        return null;
      });
}

/// 原生侧通知「孩子从别的应用按 Home 逃回桌面」
Future<void> _pressHome(WidgetTester tester) async {
  Native.homeKey.value++;
  await tester.pumpAndSettle();
}

/// 原生侧通知「孩子从应用里一路按返回键退出来，落到了桌面」
Future<void> _pressBackEscape(WidgetTester tester) async {
  Native.backEscape.value++;
  await tester.pumpAndSettle();
}

/// 真画一张 PNG 出来当图标——Image.memory 会拿去解码，糊一段假字节测试会直接报错
Future<Uint8List> _png(WidgetTester tester) async {
  final bytes = await tester.runAsync(() async {
    final recorder = ui.PictureRecorder();
    Canvas(recorder).drawRect(
      const Rect.fromLTWH(0, 0, 4, 4),
      Paint()..color = const Color(0xFF00AA00),
    );
    final img = await recorder.endRecording().toImage(4, 4);
    final data = await img.toByteData(format: ui.ImageByteFormat.png);
    return data!.buffer.asUint8List();
  });
  return bytes!;
}

void main() {
  testWidgets('孩子本来就站在桌面上时，返回键不弹挑战框', (tester) async {
    _mock('com.back', const {});
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();
    expect(find.text('计算器'), findsOneWidget);

    // 等价于系统把返回键交给应用
    final dynamic app = tester.state(find.byType(WidgetsApp));
    await app.didPopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(Dialog), findsNothing);
    expect(find.textContaining('算对了'), findsNothing);
  });

  testWidgets('白名单应用显示应用自己的图标', (tester) async {
    final png = await _png(tester);
    _mock('com.icon', {'com.icon': png});
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    final image = tester.widget<Image>(find.byType(Image));
    expect(image.image, isA<MemoryImage>());
    expect((image.image as MemoryImage).bytes, equals(png));
    expect(find.text('计'), findsNothing, reason: '有图标就不该再退回首字母色块');
  });

  testWidgets('原生侧取不到图标时退回首字母色块', (tester) async {
    _mock('com.nofallback', const {});
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    expect(find.byType(Image), findsNothing);
    expect(find.text('计'), findsOneWidget);
  });

  testWidgets('乘法挑战框没有取消按钮，只有确定', (tester) async {
    _mock('com.home.nocancel', const {});
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressHome(tester);
    expect(find.textContaining('×'), findsOneWidget, reason: '该弹出乘法挑战框');
    expect(find.text('确定'), findsOneWidget);
    expect(find.text('取消'), findsNothing, reason: '算术挑战不给退路');
  });

  testWidgets('按 Home 键挑战答错：直接拒绝，把他送回刚才那个应用', (tester) async {
    final calls = <String>[];
    _mock('com.home.wrong', const {}, calls: calls);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressHome(tester);
    // 两位乘数都在 2..9，乘积不可能是 0
    await tester.enterText(find.byType(TextField), '0');
    await tester.tap(find.text('确定'));
    await tester.pumpAndSettle();

    expect(find.byType(Dialog), findsNothing, reason: '答错就结束，没有第二次');
    expect(calls, contains('returnToLastApp'), reason: '答错＝没通过，送回原来那个应用');
  });

  testWidgets('配置还没回来时按的 Home 也不能被放过', (tester) async {
    final calls = <String>[];
    final gate = Completer<void>();
    _mock('com.home.early', const {}, calls: calls, configGate: gate.future);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));

    // initState 的首次 _reload 还卡在 config 上，此刻 _cfg 仍是 null
    Native.homeKey.value++;
    gate.complete();
    await tester.pumpAndSettle();

    expect(find.textContaining('×'), findsOneWidget, reason: '这一下 Home 不能被白白放过去');
  });

  testWidgets('按 Home 键挑战答对：留在桌面，不送回应用', (tester) async {
    final calls = <String>[];
    _mock('com.home.pass', const {}, calls: calls);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressHome(tester);
    final prompt = tester.widget<Text>(find.textContaining('×')).data!;
    final m = RegExp(r'(\d+)\s*×\s*(\d+)').firstMatch(prompt)!;
    final answer = int.parse(m.group(1)!) * int.parse(m.group(2)!);
    await tester.enterText(find.byType(TextField), '$answer');
    await tester.tap(find.text('确定'));
    await tester.pumpAndSettle();

    expect(find.byType(Dialog), findsNothing);
    expect(calls, isNot(contains('returnToLastApp')));
  });

  testWidgets('在应用里按返回键退回桌面：也要弹挑战框，答错送回原应用', (tester) async {
    final calls = <String>[];
    _mock('com.back.escape', const {}, calls: calls);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressBackEscape(tester);
    expect(find.textContaining('×'), findsOneWidget, reason: '按返回键退出应用也算逃回桌面');
    expect(find.textContaining('按返回键'), findsOneWidget, reason: '要写清楚孩子是怎么出来的');

    await tester.enterText(find.byType(TextField), '0');
    await tester.tap(find.text('确定'));
    await tester.pumpAndSettle();

    expect(find.byType(Dialog), findsNothing, reason: '答错就结束，没有第二次');
    expect(calls, contains('returnToLastApp'), reason: '答错＝没通过，送回原来那个应用');
  });

  testWidgets('「从应用回到桌面时挑战」关掉后，按返回键退出来直接进桌面', (tester) async {
    final calls = <String>[];
    _mock('com.back.off', const {}, calls: calls, chOnHome: false);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressBackEscape(tester);
    expect(find.byType(Dialog), findsNothing);
    expect(calls, isNot(contains('returnToLastApp')));
  });

  testWidgets('冷启动（进程被杀后按 Home）答错：同样送回刚才那个应用', (tester) async {
    final calls = <String>[];
    _mock('com.home.cold', const {}, calls: calls, coldStartHome: true);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    expect(find.textContaining('×'), findsOneWidget, reason: '冷启动该直接弹挑战框');

    await tester.enterText(find.byType(TextField), '0');
    await tester.tap(find.text('确定'));
    await tester.pumpAndSettle();
    expect(calls, contains('returnToLastApp'));
  });
}
