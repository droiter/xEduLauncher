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
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final v = int.tryParse(_controller.text.trim());
    if (v == widget.answer) {
      Navigator.of(context).pop(true);
    } else {
      setState(() {
        _error = '答错了，再试一次';
        _controller.clear();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return _Shell(
      icon: Icons.calculate_outlined,
      title: widget.title,
      subtitle: '算对了才能继续哦',
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
          if (_error != null) ...[
            const SizedBox(height: 10),
            Text(_error!, style: const TextStyle(color: Colors.red)),
          ],
          const SizedBox(height: 20),
          _Actions(onOk: _submit, onCancel: () => Navigator.of(context).pop(false)),
        ],
      ),
    );
  }
}

// ---------- 家长密码 ----------

Future<bool> askParentPassword(
  BuildContext context, {
  required String title,
  String subtitle = '请输入家长密码',
}) async {
  final ok = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _PasswordDialog(title: title, subtitle: subtitle),
  );
  return ok ?? false;
}

class _PasswordDialog extends StatefulWidget {
  const _PasswordDialog({required this.title, required this.subtitle});

  final String title;
  final String subtitle;

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
    final ok = await Native.verifyPassword(_controller.text);
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
  const _Actions({required this.onOk, required this.onCancel});

  final VoidCallback? onOk;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: OutlinedButton(
            onPressed: onCancel,
            child: const Text('取消'),
          ),
        ),
        const SizedBox(width: 12),
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
