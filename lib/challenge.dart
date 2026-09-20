import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'config.dart';
import 'native.dart';

/// 按当前配置弹出挑战框，全部通过才算成功。
Future<bool> runChallenge(
  BuildContext context,
  LauncherConfig cfg,
  String title,
) async {
  switch (cfg.challengeType) {
    case 'none':
      return true;
    case 'password':
      return askParentPassword(context, title: title);
    case 'add':
      return _askMath(context, title, mul: false);
    case 'mul':
    default:
      return _askMath(context, title, mul: true);
  }
}

// ---------- 算术题 ----------

Future<bool> _askMath(BuildContext context, String title, {required bool mul}) async {
  final rnd = Random();
  final int a, b, answer;
  final String prompt;
  if (mul) {
    a = 2 + rnd.nextInt(8); // 2..9，保证是一位数
    b = 2 + rnd.nextInt(8);
    answer = a * b;
    prompt = '$a × $b = ?';
  } else {
    a = 10 + rnd.nextInt(90);
    b = 10 + rnd.nextInt(90);
    answer = a + b;
    prompt = '$a + $b = ?';
  }
  final ok = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _MathDialog(title: title, prompt: prompt, answer: answer),
  );
  return ok ?? false;
}

class _MathDialog extends StatefulWidget {
  const _MathDialog({
    required this.title,
    required this.prompt,
    required this.answer,
  });

  final String title;
  final String prompt;
  final int answer;

  @override
  State<_MathDialog> createState() => _MathDialogState();
}

class _MathDialogState extends State<_MathDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  /// 只有一次机会：算对放行，算错就直接算没通过（没有第二次，也没有取消可退）
  void _submit() {
    final v = int.tryParse(_controller.text.trim());
    Navigator.of(context).pop(v == widget.answer);
  }

  @override
  Widget build(BuildContext context) {
    // 返回键不许把这一页 pop 掉：对话框被 pop 就等于返回 null，也就是「没通过」，
    // 孩子会被直接送回应用里——而这一页本来就没有「取消」这条退路（见 _submit）。
    // 这一下返回键未必是孩子按的：守护退「最近任务」那一屏时会发一个 GLOBAL_ACTION_BACK，
    // 它落在哪个窗口上不由我们决定，2026-09-20 模拟器实测就打在过这个对话框上。
    return PopScope(
      canPop: false,
      child: _Shell(
        icon: Icons.calculate_outlined,
        title: widget.title,
        subtitle: '算对了才能继续，只有一次机会',
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.prompt,
              style: const TextStyle(fontSize: 44, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 20),
            TextField(
              controller: _controller,
              autofocus: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 30),
              decoration: const InputDecoration(
                hintText: '答案',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (_) => _submit(),
            ),
            const SizedBox(height: 20),
            _Actions(onOk: _submit),
          ],
        ),
      ),
    );
  }
}

// ---------- 家长密码 ----------

Future<bool> askParentPassword(
  BuildContext context, {
  required String title,
  String subtitle = '请输入家长密码',
  bool useSettingsPassword = false,
}) async {
  final ok = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _PasswordDialog(
      title: title,
      subtitle: subtitle,
      useSettingsPassword: useSettingsPassword,
    ),
  );
  return ok ?? false;
}

class _PasswordDialog extends StatefulWidget {
  const _PasswordDialog({
    required this.title,
    required this.subtitle,
    this.useSettingsPassword = false,
  });

  final String title;
  final String subtitle;
  final bool useSettingsPassword;

  @override
  State<_PasswordDialog> createState() => _PasswordDialogState();
}

class _PasswordDialogState extends State<_PasswordDialog> {
  final _controller = TextEditingController();
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() => _busy = true);
    final ok = widget.useSettingsPassword
        ? await Native.verifySettingsPassword(_controller.text)
        : await Native.verifyPassword(_controller.text);
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok) {
      Navigator.of(context).pop(true);
    } else {
      setState(() {
        _error = '密码不正确';
        _controller.clear();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return _Shell(
      icon: Icons.lock_outline,
      title: widget.title,
      subtitle: widget.subtitle,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _controller,
            autofocus: true,
            obscureText: true,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 28),
            decoration: const InputDecoration(
              hintText: '密码',
              border: OutlineInputBorder(),
            ),
            onSubmitted: (_) => _submit(),
          ),
          if (_error != null) ...[
            const SizedBox(height: 10),
            Text(_error!, style: const TextStyle(color: Colors.red)),
          ],
          const SizedBox(height: 20),
          _Actions(
            onOk: _busy ? null : _submit,
            onCancel: () => Navigator.of(context).pop(false),
          ),
        ],
      ),
    );
  }
}

// ---------- 公共外壳 ----------

class _Shell extends StatelessWidget {
  const _Shell({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.child,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 28, 24, 20),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 40, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 12),
              Text(
                title,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                subtitle,
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(context).hintColor),
              ),
              const SizedBox(height: 20),
              child,
            ],
          ),
        ),
      ),
    );
  }
}

class _Actions extends StatelessWidget {
  const _Actions({required this.onOk, this.onCancel});

  final VoidCallback? onOk;

  /// 为 null 时只有「确定」一个按钮（算术挑战没有退路，答错就是没通过）
  final VoidCallback? onCancel;

  @override
  Widget build(BuildContext context) {
    final cancel = onCancel;
    return Row(
      children: [
        if (cancel != null) ...[
          Expanded(
            child: OutlinedButton(
              onPressed: cancel,
              child: const Text('取消'),
            ),
          ),
          const SizedBox(width: 12),
        ],
        Expanded(
          child: FilledButton(
            onPressed: onOk,
            child: const Text('确定'),
          ),
        ),
      ],
    );
  }
}
