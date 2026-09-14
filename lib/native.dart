import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'config.dart';

class InstalledApp {
  final String package;
  final String label;
  const InstalledApp({required this.package, required this.label});

  factory InstalledApp.fromMap(Map<dynamic, dynamic> m) => InstalledApp(
    package: m['package'] as String,
    label: m['label'] as String,
  );
}

/// 「默认桌面」这一步实际发生了什么。code 见 MainActivity.requestDefaultHome / onActivityResult。
class HomeSettingsResult {
  /// role        已请求系统弹角色框，结果稍后由 [Native.homeResult] 补上
  /// already     已经是默认桌面
  /// role_blocked 系统没渲染弹框就直接拒了（ROM 限制），已兜底打开设置页
  /// role_canceled 弹框弹出来了，但被取消
  /// settings    没走角色弹框，直接打开了系统设置页
  /// none        所有设置入口都打不开
  final String code;
  final String detail;

  const HomeSettingsResult({required this.code, required this.detail});

  factory HomeSettingsResult.fromMap(Map<dynamic, dynamic> m) => HomeSettingsResult(
    code: m['code'] as String? ?? 'none',
    detail: m['detail'] as String? ?? '',
  );

  String get message => switch (code) {
    'role' =>
      '已请系统弹出「是否将儿童桌面设为默认桌面」。\n\n'
          '请在弹框里选「设置」。如果屏幕上一直没出现这个框，说明系统把它直接拒了，'
          '应用会自动跳到系统设置页。',
    'already' => '已设好：系统现在把儿童桌面当作默认桌面。',
    'role_blocked' =>
      '系统没有弹出确认框就直接拒绝了这次请求，这是这台 ROM 的自定义限制。\n\n'
          '我已经帮你打开了系统的桌面设置页，请在列表里选「儿童桌面」。\n\n'
          '如果那一页里「儿童桌面」是灰的、根本点不动，那就是系统层面禁止第三方桌面，'
          '请把下面这段发给我，我换另一种方案：\n$detail',
    'role_canceled' => '系统弹框被取消了，设置没有改动。需要的话再点一次「默认桌面」。\n\n$detail',
    'settings' =>
      '已打开系统设置页：\n\n$detail\n\n'
          '请在打开的页面里选「主屏幕应用 / 默认桌面」，再选「儿童桌面」。\n\n'
          '如果那一页里「儿童桌面」是灰的、根本点不动，请把这段发给我。',
    'none' => '系统里三个设置入口都打不开。请手动进「设置 → 应用 → 默认应用 → 主屏幕」选「儿童桌面」。',
    _ => '调用系统失败：\n$detail',
  };
}

/// 与 Android 原生侧的 MethodChannel 封装。
/// iOS / 鸿蒙若要适配，只需在此层替换实现，UI 层不受影响。
class Native {
  Native._();

  static const MethodChannel _ch = MethodChannel('child_launcher/native');

  /// 按 Home 键回到桌面时自增，UI 监听它来弹挑战框
  static final ValueNotifier<int> homeKey = ValueNotifier<int>(0);

  /// 「默认桌面」的最终结局。系统弹框盖在界面上时 Dart 拿不到结果，
  /// 由原生侧在弹框/设置页那边尘埃落定后推回来。
  static final ValueNotifier<HomeSettingsResult?> homeResult =
      ValueNotifier<HomeSettingsResult?>(null);

  static void init() {
    _ch.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onHomeKey':
          homeKey.value++;
        case 'onHomeResult':
          homeResult.value = HomeSettingsResult.fromMap(
            (call.arguments as Map?) ?? const {},
          );
      }
      return null;
    });
  }

  static Future<LauncherConfig> config() async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('config') ?? {});

  /// 只提交需要修改的字段
  static Future<LauncherConfig> updateConfig(Map<String, dynamic> patch) async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('updateConfig', patch) ?? {});

  static Future<List<InstalledApp>> listApps() async {
    final raw = await _ch.invokeMethod<List<dynamic>>('listApps') ?? [];
    return raw.map((e) => InstalledApp.fromMap(e as Map)).toList();
  }

  static Future<bool> launchApp(String package) async =>
      await _ch.invokeMethod<bool>('launchApp', package) ?? false;

  static Future<bool> verifyPassword(String pw) async =>
      await _ch.invokeMethod<bool>('verifyPassword', pw) ?? false;

  /// 进系统设置专用的第二个密码（家长没单独设置时等于家长密码）
  static Future<bool> verifySettingsPassword(String pw) async =>
      await _ch.invokeMethod<bool>('verifySettingsPassword', pw) ?? false;

  /// 返回 role / already / settings / none，见 MainActivity.requestDefaultHome
  static Future<HomeSettingsResult> openHomeSettings() async {
    final m = await _ch.invokeMethod('openHomeSettings');
    return HomeSettingsResult.fromMap((m as Map?) ?? const {});
  }

  /// 桌面自检：原生侧直接拼好的整份报告，含运行日志
  static Future<String> launcherDiag() async =>
      await _ch.invokeMethod<String>('launcherDiag') ?? '（原生侧没有返回诊断信息）';


  static Future<void> openSystemSettings() => _ch.invokeMethod('openSystemSettings');

  /// 跳到系统「无障碍」页，让家长给前台守护授权
  static Future<void> openAccessibilitySettings() =>
      _ch.invokeMethod('openAccessibilitySettings');
  static Future<void> requestOverlay() => _ch.invokeMethod('requestOverlay');
  static Future<void> requestNotification() => _ch.invokeMethod('requestNotification');
  static Future<void> showLock(String reason) => _ch.invokeMethod('showLock', reason);

  static Future<LauncherConfig> resetStats() async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('resetStats') ?? {});

  static Future<LauncherConfig> setGuard(bool enabled) async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('setGuard', enabled) ?? {});

  static Future<String> defaultLauncherName() async =>
      await _ch.invokeMethod<String>('defaultLauncherName') ?? '未知';
}
