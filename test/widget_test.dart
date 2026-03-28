import 'package:flutter_test/flutter_test.dart';
import 'package:vb_google/main.dart';

void main() {
  testWidgets('VideoB home renders', (WidgetTester tester) async {
    await tester.pumpWidget(const VideoBApp());

    expect(find.text('VideoB'), findsOneWidget);
    expect(find.text('Apri Nel Player'), findsOneWidget);
  });
}
