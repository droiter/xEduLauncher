/// 与原生侧 Store.configMap() 一一对应的配置快照。
class LauncherConfig {
  String password;
  String challengeType; // none | mul | add | password
  bool chOnHome;
  bool chOnLaunch;
  bool chOnBack;
  List<String> allowed;
  int dailyLimitMin;
  int graceMin;
  int openLimit;
  int usedSeconds;
  int extraSeconds;
  int openCount;
  bool isDefaultLauncher;
  bool hasOverlay;
  bool guardEnabled;

  /// 本次进程是否因「按 Home 键」而启动
  bool coldStartHome;

  LauncherConfig({
    this.password = '123456',
    this.challengeType = 'mul',
    this.chOnHome = true,
    this.chOnLaunch = true,
    this.chOnBack = true,
    this.allowed = const [],
    this.dailyLimitMin = 0,
    this.graceMin = 10,
    this.openLimit = 0,
    this.usedSeconds = 0,
    this.extraSeconds = 0,
    this.openCount = 0,
    this.isDefaultLauncher = false,
    this.hasOverlay = false,
    this.guardEnabled = false,
    this.coldStartHome = false,
  });

  factory LauncherConfig.fromMap(Map<dynamic, dynamic> m) => LauncherConfig(
    password: m['password'] as String? ?? '123456',
    challengeType: m['challengeType'] as String? ?? 'mul',
    chOnHome: m['chOnHome'] as bool? ?? true,
    chOnLaunch: m['chOnLaunch'] as bool? ?? true,
    chOnBack: m['chOnBack'] as bool? ?? true,
    allowed: (m['allowed'] as List?)?.cast<String>() ?? const [],
    dailyLimitMin: (m['dailyLimitMin'] as num?)?.toInt() ?? 0,
    graceMin: (m['graceMin'] as num?)?.toInt() ?? 10,
    openLimit: (m['openLimit'] as num?)?.toInt() ?? 0,
    usedSeconds: (m['usedSeconds'] as num?)?.toInt() ?? 0,
    extraSeconds: (m['extraSeconds'] as num?)?.toInt() ?? 0,
    openCount: (m['openCount'] as num?)?.toInt() ?? 0,
    isDefaultLauncher: m['isDefaultLauncher'] as bool? ?? false,
    hasOverlay: m['hasOverlay'] as bool? ?? false,
    guardEnabled: m['guardEnabled'] as bool? ?? false,
    coldStartHome: m['coldStartHome'] as bool? ?? false,
  );

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
