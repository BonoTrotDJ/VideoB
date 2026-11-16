import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const appVersion = '1.0.14+16';
const projectUrl = 'https://github.com/BonoTrotDJ/VideoB';

Widget buildFooter({required double drawerWidth, required EdgeInsets padding, required bool useFittedBox, required double fontScale}) {
  final theme = ThemeData(useMaterial3: true, brightness: Brightness.dark);
  return MediaQuery(
    data: MediaQueryData(textScaler: TextScaler.linear(fontScale)),
    child: MaterialApp(
      theme: theme,
      home: Scaffold(
        drawer: Drawer(
          width: drawerWidth,
          child: Column(
            children: [
              const Spacer(),
              Padding(
                padding: padding,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      'Video BonoTrot',
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Versione $appVersion',
                      style: theme.textTheme.bodyMedium?.copyWith(color: Colors.white70),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Open source',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    if (useFittedBox)
                      FittedBox(
                        fit: BoxFit.scaleDown,
                        alignment: AlignmentDirectional.centerStart,
                        child: SelectableText(
                          projectUrl,
                          style: theme.textTheme.bodySmall?.copyWith(color: Colors.white70),
                        ),
                      )
                    else
                      SelectableText(
                        projectUrl,
                        style: theme.textTheme.bodySmall?.copyWith(color: Colors.white70),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        body: const SizedBox(),
      ),
    ),
  );
}

void main() {
  final paddings = <String, EdgeInsets>{
    'tv': const EdgeInsets.fromLTRB(10, 10, 10, 12),
    'big': const EdgeInsets.fromLTRB(20, 14, 20, 20),
    'small': const EdgeInsets.fromLTRB(14, 12, 14, 16),
  };
  final widths = <double>[264.0, 292.0, 320.0, 360.0];
  final scales = <double>[1.0, 1.15, 1.3, 1.5];

  for (final useFitted in [false, true]) {
    for (final entry in paddings.entries) {
      for (final width in widths) {
        for (final scale in scales) {
          testWidgets('fitted=$useFitted pad=${entry.key} w=$width scale=$scale', (tester) async {
            final errors = <FlutterErrorDetails>[];
            await tester.pumpWidget(buildFooter(
              drawerWidth: width,
              padding: entry.value,
              useFittedBox: useFitted,
              fontScale: scale,
            ));
            final overflow = tester.takeException();
            if (overflow != null) {
              debugPrint('OVERFLOW fitted=$useFitted pad=${entry.key} w=$width scale=$scale => $overflow');
            }
          });
        }
      }
    }
  }
}
