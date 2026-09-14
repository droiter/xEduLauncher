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

  test('白名单里可以单独指定「打开时不弹挑战」的应用', () {
    final cfg = LauncherConfig.fromMap({
      'allowed': ['com.a', 'com.b'],
      'noChallenge': ['com.b'],
    });
    expect(cfg.needsChallenge('com.a'), isTrue);
    expect(cfg.needsChallenge('com.b'), isFalse);
    expect(cfg.needsChallenge('com.c'), isTrue);
  });

  test('挑战总开关关掉时所有应用都直接打开', () {
    final cfg = LauncherConfig(chOnLaunch: false);
    expect(cfg.needsChallenge('com.a'), isFalse);
  });

  test('两个密码默认相同，单独设置后各管各的', () {
    expect(LauncherConfig().settingsPassword, LauncherConfig().password);
    final cfg = LauncherConfig.fromMap({
      'password': '1111',
      'settingsPassword': '2222',
      'settingsPwCustom': true,
    });
    expect(cfg.password, '1111');
    expect(cfg.settingsPassword, '2222');
    expect(cfg.settingsPwCustom, isTrue);
  });

  testWidgets('挑战类型为 none 时直接通过', (tester) async {
    final cfg = LauncherConfig(challengeType: 'none');
    expect(cfg.challengeLabel, '不需要挑战');
    expect(await tester.runAsync(() async => cfg.challengeType == 'none'), isTrue);
  });
}
