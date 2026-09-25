/// 与原生侧 Store.configMap() 一一对应的配置快照。
class LauncherConfig {
  String password;

  /// 专门用来进系统设置的第二个密码；未单独设置时等于 [password]
  String settingsPassword;

  /// 家长是否单独设过系统设置密码（没设过时界面提示「沿用家长密码」）
  bool settingsPwCustom;
  String challengeType; // none | mul | add | password
  bool chOnHome;
  bool chOnLaunch;
  List<String> allowed;

  /// [allowed] 里「点开直接进、不弹挑战」的那部分
  List<String> noChallenge;

  /// [allowed] 里「从这个应用退回桌面时不弹挑战」的那部分：孩子在里面按 Home / 返回键
  /// 退出来就直接落到桌面，不用答题
  List<String> freeExit;

  /// [allowed] 里「桌面上不给图标入口」的那部分：照样是白名单应用（能进、算受控、照计时），
  /// 只是孩子的桌面上看不到、点不着它的图标
  List<String> hideIcon;
  int dailyLimitMin;

  /// 单次使用时长上限（分钟，0 = 不限）：孩子在同一个白名单应用里用满这么久就弹乘法题。
  /// 离开应用只是停表、剩余保留（原生侧按包名存着），只有答对乘法题才归零重新给满
  int singleUseMin;
  int graceMin;
  int openLimit;
  int usedSeconds;
  int extraSeconds;
  int openCount;
  bool isDefaultLauncher;
  bool hasOverlay;
  bool guardEnabled;

  /// 「不让非白名单应用启动」：非白名单应用一进前台就送回桌面（需无障碍权限）。
  /// **缺省关着**，和 [launchGuard] 互相独立
  bool frontGuard;

  /// 「系统拦截」。**缺省关着**：关着时点开应用、从应用回桌面、超时（时长/次数用满）
  /// 这些框一个都不弹，任务键也能看到最近任务
  bool launchGuard;

  /// 「测试拦截」还剩多少秒（0 = 没在测试）。测试期间两个开关都按打开算，到时自动关
  int testGuardLeftSec;

  /// 「允许应用跳转」。**缺省关着**（＝照旧拦）。打开后，从白名单应用里点开的另一个应用
  /// 不再被弹回桌面（文件管理器里点 apk 弹出的安装界面、应用里点链接拉起的浏览器…）。
  /// 只认一跳：从那个应用再往外点开的第三个应用照旧拦
  bool allowChildLaunch;

  /// 无障碍服务当前是否已在系统里启用
  bool accessibilityOn;

  /// 家长外出系统设置时的放行时长（分钟）
  int settingsFreeMin;

  /// 文件传输服务：浏览器连上本机下载日志、上传文件（每台新设备要在设备上点一次「同意」）
  bool fileServerOn;

  /// 服务实际跑起来的访问地址，形如 http://192.168.1.23:8080；服务没开时是空串
  String fileServerUrl;

  /// 本次进程是否因「按 Home 键」而启动
  bool coldStartHome;

  LauncherConfig({
    this.password = '123456',
    this.settingsPassword = '123456',
    this.settingsPwCustom = false,
    this.challengeType = 'mul',
    this.chOnHome = true,
    this.chOnLaunch = true,
    this.allowed = const [],
    this.noChallenge = const [],
    this.freeExit = const [],
    this.hideIcon = const [],
    this.dailyLimitMin = 0,
    this.singleUseMin = 10,
    this.graceMin = 10,
    this.openLimit = 0,
    this.usedSeconds = 0,
    this.extraSeconds = 0,
    this.openCount = 0,
    this.isDefaultLauncher = false,
    this.hasOverlay = false,
    this.guardEnabled = false,
    this.frontGuard = false,
    this.launchGuard = false,
    this.testGuardLeftSec = 0,
    this.allowChildLaunch = false,
    this.accessibilityOn = false,
    this.settingsFreeMin = 10,
    this.fileServerOn = false,
    this.fileServerUrl = '',
    this.coldStartHome = false,
  });

  factory LauncherConfig.fromMap(Map<dynamic, dynamic> m) => LauncherConfig(
    password: m['password'] as String? ?? '123456',
    settingsPassword: m['settingsPassword'] as String? ?? '123456',
    settingsPwCustom: m['settingsPwCustom'] as bool? ?? false,
    challengeType: m['challengeType'] as String? ?? 'mul',
    chOnHome: m['chOnHome'] as bool? ?? true,
    chOnLaunch: m['chOnLaunch'] as bool? ?? true,
    allowed: (m['allowed'] as List?)?.cast<String>() ?? const [],
    noChallenge: (m['noChallenge'] as List?)?.cast<String>() ?? const [],
    freeExit: (m['freeExit'] as List?)?.cast<String>() ?? const [],
    hideIcon: (m['hideIcon'] as List?)?.cast<String>() ?? const [],
    dailyLimitMin: (m['dailyLimitMin'] as num?)?.toInt() ?? 0,
    singleUseMin: (m['singleUseMin'] as num?)?.toInt() ?? 10,
    graceMin: (m['graceMin'] as num?)?.toInt() ?? 10,
    openLimit: (m['openLimit'] as num?)?.toInt() ?? 0,
    usedSeconds: (m['usedSeconds'] as num?)?.toInt() ?? 0,
    extraSeconds: (m['extraSeconds'] as num?)?.toInt() ?? 0,
    openCount: (m['openCount'] as num?)?.toInt() ?? 0,
    isDefaultLauncher: m['isDefaultLauncher'] as bool? ?? false,
    hasOverlay: m['hasOverlay'] as bool? ?? false,
    guardEnabled: m['guardEnabled'] as bool? ?? false,
    frontGuard: m['frontGuard'] as bool? ?? false,
    launchGuard: m['launchGuard'] as bool? ?? false,
    testGuardLeftSec: (m['testGuardLeftSec'] as num?)?.toInt() ?? 0,
    allowChildLaunch: m['allowChildLaunch'] as bool? ?? false,
    accessibilityOn: m['accessibilityOn'] as bool? ?? false,
    settingsFreeMin: (m['settingsFreeMin'] as num?)?.toInt() ?? 10,
    fileServerOn: m['fileServerOn'] as bool? ?? false,
    fileServerUrl: m['fileServerUrl'] as String? ?? '',
    coldStartHome: m['coldStartHome'] as bool? ?? false,
  );

  /// 这个应用点开时要弹挑战吗（挑战总开关关掉时一律不弹）
  bool needsChallenge(String package) =>
      chOnLaunch && !noChallenge.contains(package);

  /// 这个应用的图标要不要出现在孩子的桌面上
  bool showsOnDesktop(String package) => !hideIcon.contains(package);

  /// 当日剩余可用秒数；未设置上限时返回 null
  int? get remainingSeconds {
    if (dailyLimitMin <= 0) return null;
    final total = dailyLimitMin * 60 + extraSeconds;
    final left = total - usedSeconds;
    return left < 0 ? 0 : left;
  }

  bool get timeLimitOn => dailyLimitMin > 0;
  bool get openLimitOn => openLimit > 0;

  /// 「系统拦截」此刻生不生效（开关开着，或在「测试拦截」的几分钟里）。
  /// 注意这只是配置快照：到点那一刻前后要拿准，用 Native.sysInterceptOn() 现问原生侧
  bool get sysInterceptOn => launchGuard || testGuardLeftSec > 0;

  /// 「不让非白名单应用启动」此刻生不生效（同样含「测试拦截」的几分钟）
  bool get appBlockOn => frontGuard || testGuardLeftSec > 0;

  String get challengeLabel => switch (challengeType) {
    'none' => '不需要挑战',
    'add' => '两位数加法',
    'password' => '家长密码',
    _ => '一位数乘法',
  };
}
