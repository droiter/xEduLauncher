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

  /// 前台守护：非白名单应用一进前台就送回桌面（需无障碍权限）
  bool frontGuard;

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
    accessibilityOn: m['accessibilityOn'] as bool? ?? false,
    settingsFreeMin: (m['settingsFreeMin'] as num?)?.toInt() ?? 10,
    fileServerOn: m['fileServerOn'] as bool? ?? false,
    fileServerUrl: m['fileServerUrl'] as String? ?? '',
    coldStartHome: m['coldStartHome'] as bool? ?? false,
  );

  /// 这个应用点开时要弹挑战吗（挑战总开关关掉时一律不弹）
  bool needsChallenge(String package) =>
      chOnLaunch && !noChallenge.contains(package);

  /// 当日剩余可用秒数；未设置上限时返回 null
  int? get remainingSeconds {
    if (dailyLimitMin <= 0) return null;
    final total = dailyLimitMin * 60 + extraSeconds;
    final left = total - usedSeconds;
    return left < 0 ? 0 : left;
  }

  bool get timeLimitOn => dailyLimitMin > 0;
  bool get openLimitOn => openLimit > 0;

  String get challengeLabel => switch (challengeType) {
    'none' => '不需要挑战',
    'add' => '两位数加法',
    'password' => '家长密码',
    _ => '一位数乘法',
  };
}
