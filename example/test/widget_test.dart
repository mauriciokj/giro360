import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture_example/main.dart';

void main() {
  testWidgets('shows the reusable capture experience', (tester) async {
    await tester.pumpWidget(const Giro360ExampleApp());

    expect(find.text('Verificando este aparelho'), findsOneWidget);
    expect(find.text('Verificando compatibilidade'), findsOneWidget);
  });
}
