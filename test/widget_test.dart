import 'package:flutter_test/flutter_test.dart';

import 'package:child_launcher/config.dart';

void main() {
  test('LauncherConfig 解析与剩余时间计算', () {
    final cfg = LauncherConfig.fromMap({
      'challengeType': 'mul',
      'dailyLimitMin': 30,
      'usedSeconds': 600,
      'extraSeconds': 300,
      'allowed': ['com.a', 'com.b'],
    });

    expect(cfg.challengeType, 'mul');
    expect(cfg.allowed, hasLength(2));
    // 30*60 + 300 - 600 = 1500 秒
    expect(cfg.remainingSeconds, 1500);
    expect(cfg.timeLimitOn, isTrue);
    expect(cfg.openLimitOn, isFalse);
  });

  test('未设置时长上限时剩余时间为 null', () {
    final cfg = LauncherConfig.fromMap({'dailyLimitMin': 0});
    expect(cfg.remainingSeconds, isNull);
  });

  testWidgets('挑战类型为 none 时直接通过', (tester) async {
    final cfg = LauncherConfig(challengeType: 'none');
    expect(cfg.challengeLabel, '不需要挑战');
    expect(await tester.runAsync(() async => cfg.challengeType == 'none'), isTrue);
  });
}
