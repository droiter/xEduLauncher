import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:child_launcher/home_screen.dart';
import 'package:child_launcher/native.dart';

const _channel = MethodChannel('child_launcher/native');

/// 每个用例用各自的应用包名：Native 里的图标缓存是静态的，同一个包名会串味
Map<String, Object?> _config(String pkg, {bool coldStartHome = false}) => {
  'challengeType': 'mul',
  'chOnLaunch': true,
  'chOnHome': true,
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
}) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_channel, (call) async {
        calls?.add(call.method);
        switch (call.method) {
          case 'config':
            return _config(pkg, coldStartHome: coldStartHome);
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

  testWidgets('按 Home 键挑战取消：把孩子送回刚才那个应用', (tester) async {
    final calls = <String>[];
    _mock('com.home.cancel', const {}, calls: calls);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressHome(tester);
    expect(find.textContaining('×'), findsOneWidget, reason: '该弹出乘法挑战框');

    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();

    expect(find.byType(Dialog), findsNothing);
    expect(calls, contains('returnToLastApp'));
  });

  testWidgets('按 Home 键挑战答错：框留着重答，只有放弃才送回', (tester) async {
    final calls = <String>[];
    _mock('com.home.wrong', const {}, calls: calls);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    await _pressHome(tester);
    // 两位乘数都在 2..9，乘积不可能是 0
    await tester.enterText(find.byType(TextField), '0');
    await tester.tap(find.text('确定'));
    await tester.pumpAndSettle();

    expect(find.text('答错了，再试一次'), findsOneWidget);
    expect(calls, isNot(contains('returnToLastApp')), reason: '还能接着答，不该把人送走');

    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(calls, contains('returnToLastApp'));
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

  testWidgets('冷启动（进程被杀后按 Home）挑战取消：同样送回刚才那个应用', (tester) async {
    final calls = <String>[];
    _mock('com.home.cold', const {}, calls: calls, coldStartHome: true);
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pumpAndSettle();

    expect(find.textContaining('×'), findsOneWidget, reason: '冷启动该直接弹挑战框');

    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(calls, contains('returnToLastApp'));
  });
}
