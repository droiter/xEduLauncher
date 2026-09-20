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

/// 无障碍此刻的实况（桌面顶部那行状态显示的就是它）。
/// 两个字段分开看，因为它们坏的方式不一样：家长在系统里关掉的是 [enabled]，
/// 而装新版/强行停止/厂商省电休眠杀掉服务时，[enabled] 还显示着打开、只有 [running] 变 false。
class AccessibilityStatus {
  /// 系统「无障碍」列表里开着
  final bool enabled;

  /// 服务实例真的活着
  final bool running;

  const AccessibilityStatus({required this.enabled, required this.running});

  factory AccessibilityStatus.fromMap(Map<dynamic, dynamic> m) =>
      AccessibilityStatus(
        enabled: m['enabled'] as bool? ?? false,
        running: m['running'] as bool? ?? false,
      );

  /// 管控真的在生效吗——限时、前台守护、任务键、回到桌面挑战全靠这个权限
  bool get ok => enabled && running;
}

/// 与 Android 原生侧的 MethodChannel 封装。
/// iOS / 鸿蒙若要适配，只需在此层替换实现，UI 层不受影响。
class Native {
  Native._();

  static const MethodChannel _ch = MethodChannel('child_launcher/native');

  /// 按 Home 键回到桌面时自增，UI 监听它来弹挑战框
  static final ValueNotifier<int> homeKey = ValueNotifier<int>(0);

  /// 「从应用里按返回键退出、回到了桌面」时自增，UI 监听它来弹挑战框。
  /// 和 [homeKey] 分开是因为横幅上要写清楚孩子是怎么出来的——这一次系统连 intent 都不发，
  /// 只有无障碍守护看得见（见 GuardAccessibilityService.beforeLauncher）
  static final ValueNotifier<int> backEscape = ValueNotifier<int>(0);

  /// 「默认桌面」的最终结局。系统弹框盖在界面上时 Dart 拿不到结果，
  /// 由原生侧在弹框/设置页那边尘埃落定后推回来。
  static final ValueNotifier<HomeSettingsResult?> homeResult =
      ValueNotifier<HomeSettingsResult?>(null);

  /// 无障碍实况。[null] = 还没问到，此时界面不报警——原生侧答不上来就喊"权限没了"
  /// 是误报，家长会照着去开关一遍。原生侧在服务连上/断开的那一刻主动推（[onAccessibilityChanged]），
  /// 界面回前台时再查一次兜底。
  static final ValueNotifier<AccessibilityStatus?> accessibility =
      ValueNotifier<AccessibilityStatus?>(null);

  static void init() {
    _ch.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onHomeKey':
          homeKey.value++;
        case 'onBackEscape':
          backEscape.value++;
        case 'onHomeResult':
          homeResult.value = HomeSettingsResult.fromMap(
            (call.arguments as Map?) ?? const {},
          );
        case 'onAccessibilityChanged':
          accessibility.value = AccessibilityStatus.fromMap(
            (call.arguments as Map?) ?? const {},
          );
      }
      return null;
    });
  }

  static Future<LauncherConfig> config() async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('config') ?? {});

  /// 无障碍实况。查到就顺手写进 [accessibility]，界面监听那一个就够了。
  /// 原生侧没答上来（返回 null）时**什么都不改**：一次异常查询不该把已经知道的
  /// "开着"抹掉，更不该凭空冒出一条红字警告。
  static Future<void> refreshAccessibility() async {
    try {
      final raw = await _ch.invokeMethod<Map<dynamic, dynamic>>('accessibilityStatus');
      if (raw != null) accessibility.value = AccessibilityStatus.fromMap(raw);
    } catch (_) {
      // 查不到就维持上一次的结论。它只是界面上的一行提示，
      // 绝不能因为它把 config/listApps 那批真正要用的加载带崩
    }
  }

  /// 只提交需要修改的字段
  static Future<LauncherConfig> updateConfig(Map<String, dynamic> patch) async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('updateConfig', patch) ?? {});

  static Future<List<InstalledApp>> listApps() async {
    final raw = await _ch.invokeMethod<List<dynamic>>('listApps') ?? [];
    return raw.map((e) => InstalledApp.fromMap(e as Map)).toList();
  }

  /// 图标字节按包名缓存。存的是同一批 Uint8List 实例，Image.memory 才会命中 Flutter 的
  /// 图片缓存；每次重新下发一批新实例的话，桌面每次回前台都得把所有图标重新解一遍。
  /// 值为 null 表示这个包确实取不到图标，问过一次就不再问。
  static final Map<String, Uint8List?> _iconCache = {};

  /// 取一批应用的图标（PNG 字节），拿不到的直接不在返回里
  static Future<Map<String, Uint8List>> appIcons(List<String> packages) async {
    final todo = packages.where((p) => !_iconCache.containsKey(p)).toList();
    if (todo.isNotEmpty) {
      final raw = await _ch.invokeMethod<Map<dynamic, dynamic>>('appIcons', todo) ?? {};
      for (final p in todo) {
        _iconCache[p] = raw[p] as Uint8List?;
      }
    }
    return {
      for (final p in packages)
        if (_iconCache[p] != null) p: _iconCache[p]!,
    };
  }

  static Future<bool> launchApp(String package) async =>
      await _ch.invokeMethod<bool>('launchApp', package) ?? false;

  /// 「从应用回到桌面」的挑战没答对（按 Home 键或按返回键退出应用都算）：
  /// 把孩子送回他刚才在用的那个应用（原生侧记着是哪个）。
  /// 返回 false 表示没有可送回去的应用，孩子只能留在桌面。
  static Future<bool> returnToLastApp() async =>
      await _ch.invokeMethod<bool>('returnToLastApp') ?? false;

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

  /// 清空历史日志：运行日志、行为审计、logs/ 里的历史自检报告一并删掉，从此刻起重新记录。
  /// 返回删掉的文件数。
  static Future<int> clearDiagLogs() async =>
      await _ch.invokeMethod<int>('launcherDiagClear') ?? 0;


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

  /// 开关文件传输服务（浏览器连本机下载日志、上传文件）
  static Future<LauncherConfig> setFileServer(bool enabled) async =>
      LauncherConfig.fromMap(await _ch.invokeMethod('setFileServer', enabled) ?? {});

  /// 把关键决策写进原生那份日志文件——家长只有真机能复现的问题，事后要能从日志里看出来
  static void log(String msg) {
    _ch.invokeMethod('diagLog', msg).then((_) {}, onError: (Object _) {});
  }

  static Future<String> defaultLauncherName() async =>
      await _ch.invokeMethod<String>('defaultLauncherName') ?? '未知';
}
