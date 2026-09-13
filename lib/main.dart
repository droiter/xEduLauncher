import 'package:flutter/material.dart';

import 'home_screen.dart';
import 'native.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  Native.init();
  runApp(const ChildLauncherApp());
}

class ChildLauncherApp extends StatelessWidget {
  const ChildLauncherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '儿童桌面',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF4C8DFF)),
      ),
      home: const HomeScreen(),
    );
  }
}
