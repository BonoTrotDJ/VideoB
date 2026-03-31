import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const VideoBApp());
}

const String _sportListName = 'Sport';
const String _sportListSourceUrl = 'https://sportsonline.st/prog.txt';

class VideoBApp extends StatelessWidget {
  const VideoBApp({super.key});

  @override
  Widget build(BuildContext context) {
    const background = Color(0xFF07111F);
    const surface = Color(0xFF11213A);
    const accent = Color(0xFFF4B942);

    return MaterialApp(
      title: 'VideoB',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: background,
        colorScheme: const ColorScheme.dark(
          primary: accent,
          secondary: Color(0xFF65D3FF),
          surface: surface,
        ),
        cardTheme: const CardThemeData(
          color: surface,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(28)),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: Colors.white.withValues(alpha: 0.08),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(20),
            borderSide: BorderSide.none,
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(20),
            borderSide: const BorderSide(color: accent, width: 2),
          ),
        ),
        segmentedButtonTheme: SegmentedButtonThemeData(
          style: ButtonStyle(
            backgroundColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.selected)) {
                  return Colors.white;
                }
                return Colors.white.withValues(alpha: 0.06);
              },
            ),
            foregroundColor: WidgetStateProperty.resolveWith<Color?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.selected)) {
                  return const Color(0xFF07111F);
                }
                return Colors.white;
              },
            ),
            side: WidgetStateProperty.resolveWith<BorderSide?>(
              (Set<WidgetState> states) {
                if (states.contains(WidgetState.selected)) {
                  return const BorderSide(color: Colors.white, width: 1.5);
                }
                return BorderSide(color: Colors.white.withValues(alpha: 0.14));
              },
            ),
          ),
        ),
      ),
      home: const VideoBHomePage(),
    );
  }
}

class VideoBHomePage extends StatefulWidget {
  const VideoBHomePage({super.key});

  @override
  State<VideoBHomePage> createState() => _VideoBHomePageState();
}

class _VideoBHomePageState extends State<VideoBHomePage> {
  static const _channel = MethodChannel('videob/channel');
  static const _listsKey = 'video_lists_v2';
  static const _selectedListKey = 'selected_video_list_v2';
  static const _backupPayloadVersion = 1;
  static const _appDisplayName = 'Video BonoTrot';
  static const _appVersion = '1.0.0+1';
  static const List<String> _weekdayLabels = <String>[
    'Domenica',
    'Lunedì',
    'Martedì',
    'Mercoledì',
    'Giovedì',
    'Venerdì',
    'Sabato',
  ];
  static const List<String> _monthLabels = <String>[
    'gennaio',
    'febbraio',
    'marzo',
    'aprile',
    'maggio',
    'giugno',
    'luglio',
    'agosto',
    'settembre',
    'ottobre',
    'novembre',
    'dicembre',
  ];

  final TextEditingController _entryNameController = TextEditingController();
  final TextEditingController _entryUrlController = TextEditingController();
  final ScrollController _mainScrollController = ScrollController();
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  final FocusNode _menuFocusNode = FocusNode();
  final FocusNode _refreshImportedListFocusNode = FocusNode();

  List<_VideoList> _videoLists = const <_VideoList>[];
  String? _selectedListId;
  String? _selectedSportFilter;
  String? _selectedLanguageFilter;
  String? _editingEntryId;
  String? _activeEntryId;
  String? _activeEntryName;
  String? _focusedEntryId;
  bool _isLoading = true;
  bool _isBusy = false;
  bool _dohEnabled = false;
  String? _status;

  static const String _dohKey = 'doh_enabled';

  _VideoList? get _selectedList {
    for (final list in _videoLists) {
      if (list.id == _selectedListId) {
        return list;
      }
    }
    return _videoLists.isEmpty ? null : _videoLists.first;
  }

  List<String> get _availableSports {
    final selectedList = _selectedList;
    if (selectedList == null) {
      return const <String>[];
    }

    final sports = selectedList.entries
        .map((_VideoEntry entry) => entry.sportLabel)
        .whereType<String>()
        .toSet()
        .toList()
      ..sort();
    return sports;
  }

  List<String> get _availableLanguages {
    final selectedList = _selectedList;
    if (selectedList == null) {
      return const <String>[];
    }

    final languages = selectedList.entries
        .expand(_entryLanguages)
        .toSet()
        .toList()
      ..sort();
    return languages;
  }

  List<_VideoEntry> get _filteredEntries {
    final selectedList = _selectedList;
    if (selectedList == null) {
      return const <_VideoEntry>[];
    }

    final selectedSport = _selectedSportFilter;
    final selectedLanguage = _selectedLanguageFilter;

    return selectedList.entries.where((_VideoEntry entry) {
      final sportMatches = selectedSport == null ||
          selectedSport.isEmpty ||
          entry.sportLabel == selectedSport;
      final languageMatches = selectedLanguage == null ||
          selectedLanguage.isEmpty ||
          _entryLanguages(entry).contains(selectedLanguage);
      return sportMatches && languageMatches;
    }).toList();
  }

  List<MapEntry<String, List<_VideoEntry>>> get _groupedFilteredEntries {
    final grouped = <String, List<_VideoEntry>>{};
    final orderedDays = <String>[];

    for (final entry in _filteredEntries) {
      final day = entry.dayLabel?.trim().isNotEmpty == true
          ? entry.dayLabel!.trim()
          : 'Altri eventi';
      if (!grouped.containsKey(day)) {
        grouped[day] = <_VideoEntry>[];
        orderedDays.add(day);
      }
      grouped[day]!.add(entry);
    }

    return orderedDays
        .map((String day) =>
            MapEntry<String, List<_VideoEntry>>(day, grouped[day]!))
        .toList();
  }

  @override
  void initState() {
    super.initState();
    _loadLists();
  }

  @override
  void dispose() {
    _entryNameController.dispose();
    _entryUrlController.dispose();
    _mainScrollController.dispose();
    _menuFocusNode.dispose();
    _refreshImportedListFocusNode.dispose();
    super.dispose();
  }

  Future<void> _loadLists() async {
    final preferences = await SharedPreferences.getInstance();
    final storedDohEnabled = preferences.getBool(_dohKey) ?? false;
    final nativeDohEnabled =
        await _channel.invokeMethod<bool>('getDnsVpnEnabled') ?? false;
    _dohEnabled = nativeDohEnabled || storedDohEnabled;
    await preferences.setBool(_dohKey, _dohEnabled);
    var rawLists = preferences.getString(_listsKey);
    var selectedListId = preferences.getString(_selectedListKey);
    var parsedLists = _parseStoredLists(rawLists);

    if (parsedLists.isEmpty) {
      final backupPayload = await _loadBackupPayload();
      if (backupPayload != null) {
        rawLists = backupPayload.rawLists;
        selectedListId = backupPayload.selectedListId;
        parsedLists = _parseStoredLists(rawLists);
      }
    }

    if (parsedLists.isEmpty) {
      parsedLists = <_VideoList>[
        _VideoList(
          id: DateTime.now().microsecondsSinceEpoch.toString(),
          name: 'Lista Principale',
          sourceType: _VideoListSourceType.manual,
          entries: const <_VideoEntry>[],
        ),
      ];
    }

    final effectiveSelectedId = parsedLists.any(
      (_VideoList list) => list.id == selectedListId,
    )
        ? selectedListId
        : parsedLists.first.id;

    if (!mounted) {
      return;
    }

    setState(() {
      _videoLists = parsedLists;
      _selectedListId = effectiveSelectedId;
      _isLoading = false;
    });

    await _persistLists();
  }

  List<_VideoList> _parseStoredLists(String? rawLists) {
    if (rawLists == null || rawLists.isEmpty) {
      return <_VideoList>[];
    }

    try {
      final decoded = jsonDecode(rawLists);
      if (decoded is! List<dynamic>) {
        return <_VideoList>[];
      }

      return decoded
          .whereType<Map<String, dynamic>>()
          .map(_VideoList.fromJson)
          .toList();
    } on FormatException {
      return <_VideoList>[];
    } on TypeError {
      return <_VideoList>[];
    }
  }

  Future<void> _persistLists() async {
    final preferences = await SharedPreferences.getInstance();
    final payload = jsonEncode(
      _videoLists.map((_VideoList list) => list.toJson()).toList(),
    );
    await preferences.setString(_listsKey, payload);
    if (_selectedListId != null) {
      await preferences.setString(_selectedListKey, _selectedListId!);
    }
    await _backupLists(payload, _selectedListId);
  }

  Future<void> _backupLists(String rawLists, String? selectedListId) async {
    final backupPayload = jsonEncode(<String, dynamic>{
      'version': _backupPayloadVersion,
      'selectedListId': selectedListId,
      'rawLists': rawLists,
    });

    try {
      await _channel.invokeMethod<void>('backupLists', <String, dynamic>{
        'payload': backupPayload,
      });
    } on PlatformException {
      // Backup esterno best-effort: la persistenza primaria resta locale.
    }
  }

  Future<_BackupPayload?> _loadBackupPayload() async {
    try {
      final rawPayload = await _channel.invokeMethod<String>('loadBackupLists');
      if (rawPayload == null || rawPayload.isEmpty) {
        return null;
      }

      final decoded = jsonDecode(rawPayload);
      if (decoded is! Map<String, dynamic>) {
        return null;
      }

      final rawLists = decoded['rawLists'] as String?;
      if (rawLists == null || rawLists.isEmpty) {
        return null;
      }

      return _BackupPayload(
        rawLists: rawLists,
        selectedListId: decoded['selectedListId'] as String?,
      );
    } on PlatformException {
      return null;
    } on FormatException {
      return null;
    }
  }

  void _selectList(String id) {
    setState(() {
      _selectedListId = id;
      if (_selectedSportFilter != null &&
          !_availableSports.contains(_selectedSportFilter)) {
        _selectedSportFilter = null;
      }
      if (_selectedLanguageFilter != null &&
          !_availableLanguages.contains(_selectedLanguageFilter)) {
        _selectedLanguageFilter = null;
      }
      _editingEntryId = null;
      _entryNameController.clear();
      _entryUrlController.clear();
      _status = null;
    });
    _persistLists();
  }

  Future<void> _askPlayerMode(String url, String? entryId, String entryName) async {
    await _openUrl(url, entryId: entryId, entryName: entryName);
  }

  Future<void> _openEntryWithChannelPicker(_VideoEntry entry) async {
    final channels = entry.channels.isNotEmpty
        ? entry.channels
        : entry.url.isNotEmpty
            ? [_VideoChannel(url: entry.url, label: 'Canale', language: entry.language ?? '')]
            : <_VideoChannel>[];

    if (channels.isEmpty) return;

    if (channels.length == 1) {
      await _askPlayerMode(channels.first.url, entry.id, entry.name);
      return;
    }

    await showDialog<void>(
      context: context,
      builder: (BuildContext ctx) => AlertDialog(
        title: Text(entry.name),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              'Scegli la lingua:',
              style: const TextStyle(color: Colors.white70, fontSize: 13),
            ),
            const SizedBox(height: 8),
            ...List<Widget>.generate(channels.length, (int i) {
              final ch = channels[i];
              return ListTile(
                autofocus: i == 0,
                leading: const Icon(Icons.play_circle_outline_rounded),
                title: Text(ch.language.isNotEmpty ? ch.language : ch.label),
                subtitle: ch.language.isNotEmpty && ch.label.isNotEmpty
                    ? Text(ch.label, style: const TextStyle(fontSize: 11))
                    : null,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                onTap: () {
                  Navigator.of(ctx).pop();
                  _openUrl(ch.url, entryId: entry.id, entryName: entry.name);
                },
              );
            }),
          ],
        ),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Annulla'),
          ),
        ],
      ),
    );
  }

  Future<void> _openUrl(
    String url, {
    String? entryId,
    String? entryName,
  }) async {
    final rawUrl = url.trim();
    if (rawUrl.isEmpty) {
      setState(() {
        _status = 'Inserisci un URL valido.';
      });
      return;
    }

    try {
      await _channel.invokeMethod<void>('openUrl', <String, dynamic>{
        'url': rawUrl,
        'dohEnabled': false,
      });
      if (!mounted) {
        return;
      }
      setState(() {
        _activeEntryId = entryId;
        _activeEntryName = entryName;
        _status = entryName == null
            ? 'Apertura player interno...'
            : 'In riproduzione: $entryName';
      });
    } on PlatformException catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _status = error.message ?? 'Errore durante l\'apertura del player.';
      });
    }
  }

  Future<List<String>> _extractLinksFromUrl(String url) async {
    final response = await _channel.invokeMethod<dynamic>(
      'extractLinks',
      <String, dynamic>{'url': url, 'dohEnabled': false},
    );

    return (response as List<dynamic>? ?? <dynamic>[])
        .map((dynamic item) => item.toString())
        .where((String item) => item.isNotEmpty)
        .toList();
  }

  http.Client _createHttpClient() => http.Client();

  Future<void> _setDohEnabled(bool value) async {
    try {
      final applied =
          await _channel.invokeMethod<bool>('setDnsVpnEnabled', <String, dynamic>{
        'enabled': value,
      });
      if (applied != true) {
        if (!mounted) {
          return;
        }
        setState(() {
          _status = value ? 'attivazione dns annullata' : 'disattivazione dns fallita';
        });
        return;
      }

      if (!mounted) {
        return;
      }
      setState(() {
        _dohEnabled = value;
        _status = value
            ? 'dns 1.1.1.1 attivo, aggiornamento in corso...'
            : 'dns 1.1.1.1 disattivo';
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_dohKey, value);

      if (!value) {
        return;
      }

      final dnsReady = await _waitForDnsVpnState(true);
      if (!dnsReady) {
        if (!mounted) {
          return;
        }
        setState(() {
          _status = 'dns attivo ma non ancora pronto';
        });
        return;
      }

      final selectedList = _selectedList;
      if (selectedList?.sourceType != _VideoListSourceType.imported) {
        if (!mounted) {
          return;
        }
        setState(() {
          _status = 'dns 1.1.1.1 attivo';
        });
        return;
      }
      if (!mounted) {
        return;
      }
      await _refreshImportedList(
        selectedList!.id,
        keepFocusOnRefresh: true,
        showConnectionErrorOnly: true,
      );
    } on PlatformException {
      if (!mounted) {
        return;
      }
      setState(() {
        _status = 'errore attivazione dns';
      });
    }
  }

  Future<(String, List<_VideoEntry>, DateTime?)> _loadEntriesFromSource(
    String sourceUrl,
  ) async {
    final uri = Uri.tryParse(sourceUrl);
    if (uri == null) {
      throw const FormatException('URL sorgente non valido.');
    }

    final looksLikePlainText = uri.path.toLowerCase().endsWith('.txt') ||
        (uri.host.toLowerCase() == 'sportsonline.st' &&
            (uri.path.isEmpty || uri.path == '/'));
    if (looksLikePlainText) {
      return _loadEntriesFromPlainTextSource(uri);
    }

    final links =
        (await _extractLinksFromUrl(sourceUrl)).where(_isPhpUrl).toList();
    return (sourceUrl, <_VideoEntry>[
      for (var i = 0; i < links.length; i++)
        _VideoEntry(
          id: '${DateTime.now().microsecondsSinceEpoch}-$i',
          name: _buildImportedEntryName(links[i], i),
          url: links[i],
          language: _extractLanguageFromUrl(links[i]),
          sportLabel:
              _detectSportFromName(_buildImportedEntryName(links[i], i)),
        ),
    ], null);
  }

  Future<(String, List<_VideoEntry>, DateTime?)> _loadEntriesFromPlainTextSource(
    Uri uri,
  ) async {
    final client = _createHttpClient();
    final response = await client.get(uri);
    client.close();
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw PlatformException(
        code: 'plain_text_fetch_failed',
        message:
            'Impossibile leggere la lista testuale (${response.statusCode}).',
      );
    }

    final resolvedSourceUrl = response.request?.url.toString() ?? uri.toString();
    final lastUpdate = _parseLastUpdateFromText(response.body);
    final events = _parsePlainTextSchedule(response.body);
    return (resolvedSourceUrl, <_VideoEntry>[
      for (var i = 0; i < events.length; i++)
        _VideoEntry(
          id: '${DateTime.now().microsecondsSinceEpoch}-$i',
          name: events[i].title,
          url: events[i].url,
          eventTime: events[i].eventTime,
          dayLabel: events[i].dayLabel,
          language: events[i].languageLabel,
          sportLabel: events[i].sportLabel,
          channels: events[i].channels,
        ),
    ], lastUpdate);
  }

  DateTime? _parseLastUpdateFromText(String raw) {
    final match = RegExp(
      r'LAST\s+UPDATE\s*:\s*(\d{1,2})-(\d{1,2})-(\d{2,4})',
      caseSensitive: false,
    ).firstMatch(raw);
    if (match == null) return null;
    final day = int.tryParse(match.group(1) ?? '');
    final month = int.tryParse(match.group(2) ?? '');
    var year = int.tryParse(match.group(3) ?? '');
    if (day == null || month == null || year == null) return null;
    if (year < 100) year += 2000;
    return DateTime(year, month, day);
  }

  bool _isPhpUrl(String url) {
    final uri = Uri.tryParse(url);
    final path = uri?.path.toLowerCase() ?? '';
    return path.endsWith('.php');
  }

  List<_ImportedScheduleEvent> _parsePlainTextSchedule(String raw) {
    final grouped = <String, _ImportedScheduleAccumulator>{};
    final orderedKeys = <String>[];
    String? currentDayLabel;

    for (final rawLine in raw.split(RegExp(r'\r?\n'))) {
      final line = rawLine.replaceAll(RegExp(r'\s+'), ' ').trim();
      if (line.isEmpty) {
        continue;
      }

      final dayLabel = _mapEnglishDayToItalian(line);
      if (dayLabel != null) {
        currentDayLabel = dayLabel;
        continue;
      }

      final match = RegExp(
        r'^(\d{2}:\d{2})\s+(.+?)\s*\|\s*(https?:\/\/\S+)$',
      ).firstMatch(line);
      if (match == null) {
        continue;
      }

      final rawTime = (match.group(1) ?? '').trim();
      final rawTitle = (match.group(2) ?? '').trim();
      final url = (match.group(3) ?? '').trim();
      final title = _cleanImportedTitle(rawTitle);
      if (rawTime.isEmpty || title.isEmpty || url.isEmpty || !_isPhpUrl(url)) {
        continue;
      }

      final time = _shiftEventTime(rawTime);
      final key = '${time.toLowerCase()}|${title.toLowerCase()}';
      final language = _extractLanguageFromUrl(url) ?? 'Lingua non indicata';

      if (!grouped.containsKey(key)) {
        grouped[key] = _ImportedScheduleAccumulator(
          title: title,
          eventTime: time,
          sportLabel: _detectSportFromName(title),
          dayLabel: currentDayLabel,
        );
        orderedKeys.add(key);
      }

      grouped[key]!.addChannel(url, _channelLabel(url), language);
    }

    final results = <_ImportedScheduleEvent>[];
    var dayIndex = _currentDayIndex();
    String? previousTime;

    for (final key in orderedKeys) {
      final event = grouped[key];
      if (event == null) {
        continue;
      }

      if (event.dayLabel == null &&
          previousTime != null &&
          event.eventTime.compareTo(previousTime) < 0) {
        dayIndex = (dayIndex + 1) % _weekdayLabels.length;
      }

      results.add(
        _ImportedScheduleEvent(
          title: event.title,
          url: event.primaryUrl ?? '',
          eventTime: event.eventTime,
          dayLabel: event.dayLabel ?? _weekdayLabels[dayIndex],
          languageLabel: event.languageLabel,
          sportLabel: event.sportLabel,
          channels: List<_VideoChannel>.unmodifiable(event.channels),
        ),
      );
      previousTime = event.eventTime;
    }

    return results;
  }

  String _shiftEventTime(String value) {
    final parts = value.split(':');
    if (parts.length != 2) {
      return value;
    }

    final hour = int.tryParse(parts[0]);
    final minute = int.tryParse(parts[1]);
    if (hour == null || minute == null) {
      return value;
    }

    return '${((hour + 1) % 24).toString().padLeft(2, '0')}:${minute.toString().padLeft(2, '0')}';
  }

  int _currentDayIndex() {
    final weekday = DateTime.now().weekday;
    return weekday % 7;
  }

  String? _mapEnglishDayToItalian(String value) {
    switch (value.trim().toUpperCase()) {
      case 'MONDAY':
        return 'Lunedì';
      case 'TUESDAY':
        return 'Martedì';
      case 'WEDNESDAY':
        return 'Mercoledì';
      case 'THURSDAY':
        return 'Giovedì';
      case 'FRIDAY':
        return 'Venerdì';
      case 'SATURDAY':
        return 'Sabato';
      case 'SUNDAY':
        return 'Domenica';
      default:
        return null;
    }
  }

  String _cleanImportedTitle(String value) {
    var cleaned = value.replaceAll(RegExp(r'\s+'), ' ').trim();
    cleaned = cleaned
        .replaceAll(RegExp(r'\bSATURDAY\b', caseSensitive: false), '')
        .replaceAll(RegExp(r'\bSUNDAY\b', caseSensitive: false), '')
        .trim();
    cleaned = cleaned.replaceFirst(RegExp(r'^\d{2}:\d{2}\s+'), '').trim();
    return cleaned;
  }

  String? _detectSportFromName(String name) {
    final value = name.toLowerCase();

    if (value.contains('world tour')) {
      return 'Golf';
    }
    if (value.contains('grand prix')) {
      return 'Motori';
    }

    const sportMatchers = <String, String>{
      'nba': 'Basket',
      'basket': 'Basket',
      'euroleague': 'Basket',
      'boxing': 'Boxe',
      'boxing ': 'Boxe',
      'zuffa': 'Boxe',
      'ufc': 'MMA',
      'mma': 'MMA',
      'formula 1': 'Motori',
      'f1 ': 'Motori',
      'motogp': 'Motori',
      'tennis': 'Tennis',
      'atp': 'Tennis',
      'wta': 'Tennis',
      'golf': 'Golf',
      'nfl': 'Football Americano',
      'nhl': 'Hockey',
      'hockey': 'Hockey',
      'baseball': 'Baseball',
      'mlb': 'Baseball',
      'rugby': 'Rugby',
      'cricket': 'Cricket',
      'volley': 'Volley',
      'volleyball': 'Volley',
      'handball': 'Pallamano',
    };

    for (final entry in sportMatchers.entries) {
      if (value.contains(entry.key)) {
        return entry.value;
      }
    }

    if (value.contains(' x ') ||
        value.contains(' vs ') ||
        value.contains(' v ')) {
      return 'Calcio';
    }

    return null;
  }

  String _channelLabel(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null) {
      return 'Canale';
    }
    final segments =
        uri.pathSegments.where((String s) => s.isNotEmpty).toList();
    if (segments.isEmpty) {
      return 'Canale';
    }
    final tail =
        segments.length >= 2 ? segments.sublist(segments.length - 2) : segments;
    return tail
        .map((String s) => s.replaceAll('.php', '').toUpperCase())
        .join(' / ');
  }

  String? _extractLanguageFromUrl(String url) {
    final uri = Uri.tryParse(url);
    final filename = uri?.pathSegments.isNotEmpty == true
        ? uri!.pathSegments.last.toLowerCase()
        : '';
    final channelCode = filename.replaceAll('.php', '');

    const channelLanguageMap = <String, String>{
      'hd1': 'Inglese',
      'hd2': 'Inglese',
      'hd3': 'Tedesco',
      'hd4': 'Francese',
      'hd5': 'Inglese',
      'hd6': 'Spagnolo',
      'hd7': 'Italiano',
      'hd8': 'Italiano',
      'hd9': 'Italiano',
      'hd10': 'Italiano e spagnolo',
      'hd11': 'Inglese e spagnolo',
      'br1': 'Portoghese (Brasile)',
      'br2': 'Portoghese (Brasile)',
      'br3': 'Portoghese (Brasile)',
      'br4': 'Portoghese (Brasile)',
      'br5': 'Portoghese (Brasile)',
      'br6': 'Portoghese (Brasile)',
    };

    final mapped = channelLanguageMap[channelCode];
    if (mapped != null) {
      return mapped;
    }

    final segments = uri?.pathSegments ?? const <String>[];
    if (segments.length < 2) {
      return null;
    }

    final folder = segments[segments.length - 2].toLowerCase();
    switch (folder) {
      case 'pt':
        return 'Portoghese';
      case 'bra':
        return 'Portoghese (Brasile)';
      default:
        return null;
    }
  }

  Future<void> _showCreateListDialog() async {
    final result = await Navigator.of(context).push<_CreateListDialogResult>(
      MaterialPageRoute<_CreateListDialogResult>(
        builder: (BuildContext context) => const _CreateListPage(),
        fullscreenDialog: true,
      ),
    );

    if (result == null) {
      return;
    }

    await _createList(
      name: result.name,
      sourceType: result.sourceType,
      sourceUrl: result.sourceType == _VideoListSourceType.imported
          ? result.sourceUrl
          : null,
    );
  }

  Future<void> _createList({
    required String name,
    required _VideoListSourceType sourceType,
    String? sourceUrl,
  }) async {
    final newList = _VideoList(
      id: DateTime.now().microsecondsSinceEpoch.toString(),
      name: name,
      sourceType: sourceType,
      sourceUrl: sourceUrl,
      entries: const <_VideoEntry>[],
    );

    setState(() {
      _videoLists = <_VideoList>[..._videoLists, newList];
      _selectedListId = newList.id;
      _status = 'Lista "$name" creata.';
      _editingEntryId = null;
      _entryNameController.clear();
      _entryUrlController.clear();
    });

    await _persistLists();

    if (sourceType == _VideoListSourceType.imported && sourceUrl != null) {
      await _refreshImportedList(newList.id);
    }
  }

  Future<void> _ensureSportList() async {
    final existingList = _videoLists.cast<_VideoList?>().firstWhere(
          (_VideoList? item) =>
              item?.sourceType == _VideoListSourceType.imported &&
              item?.sourceUrl == _sportListSourceUrl,
          orElse: () => null,
        );

    if (existingList != null) {
      setState(() {
        _selectedListId = existingList.id;
        _status = 'Lista "$_sportListName" selezionata.';
      });
      if (mounted) {
        Navigator.of(context).pop();
      }
      return;
    }

    if (mounted) {
      Navigator.of(context).pop();
    }

    await _createList(
      name: _sportListName,
      sourceType: _VideoListSourceType.imported,
      sourceUrl: _sportListSourceUrl,
    );
  }

  Future<void> _confirmDeleteList(_VideoList list) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext ctx) => AlertDialog(
        title: const Text('Elimina lista'),
        content: Text(
            'Vuoi eliminare la lista "${list.name}"? L\'operazione non può essere annullata.'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Annulla'),
          ),
          FilledButton(
            autofocus: true,
            onPressed: () => Navigator.of(ctx).pop(true),
            style: ButtonStyle(
              backgroundColor: WidgetStateProperty.resolveWith<Color?>(
                (Set<WidgetState> states) {
                  if (states.contains(WidgetState.focused)) return Colors.white;
                  return Colors.red;
                },
              ),
              foregroundColor: WidgetStateProperty.resolveWith<Color?>(
                (Set<WidgetState> states) {
                  if (states.contains(WidgetState.focused)) return Colors.red;
                  return Colors.white;
                },
              ),
            ),
            child: const Text('Elimina'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await _deleteList(list);
    }
  }

  Future<void> _deleteList(_VideoList list) async {
    if (_videoLists.length == 1) {
      setState(() {
        _status = 'Deve rimanere almeno una lista.';
      });
      return;
    }

    final updatedLists =
        _videoLists.where((_VideoList item) => item.id != list.id).toList();
    final nextSelectedId =
        _selectedListId == list.id ? updatedLists.first.id : _selectedListId;

    setState(() {
      _videoLists = updatedLists;
      _selectedListId = nextSelectedId;
      _editingEntryId = null;
      _entryNameController.clear();
      _entryUrlController.clear();
      _status = 'Lista "${list.name}" eliminata.';
    });

    await _persistLists();
  }

  Future<void> _refreshImportedList(
    String listId, {
    bool keepFocusOnRefresh = false,
    bool showConnectionErrorOnly = false,
  }) async {
    final list = _videoLists.cast<_VideoList?>().firstWhere(
          (_VideoList? item) => item?.id == listId,
          orElse: () => null,
        );
    if (list == null || list.sourceType != _VideoListSourceType.imported) {
      return;
    }

    final sourceUrl = list.sourceUrl?.trim() ?? '';
    if (sourceUrl.isEmpty) {
      setState(() {
        _status = 'Questa lista non ha un URL sorgente valido.';
      });
      return;
    }

    setState(() {
      _isBusy = true;
      _status = 'Aggiornamento lista "${list.name}" in corso...';
    });
    if (keepFocusOnRefresh) {
      _requestRefreshImportedListFocus();
    }

    try {
      final (resolvedSourceUrl, importedEntries, parsedLastUpdate) =
          await _loadEntriesFromSource(sourceUrl);

      final updatedList = list.copyWith(
        entries: importedEntries,
        sourceUrl: resolvedSourceUrl,
        updatedAt: parsedLastUpdate ?? DateTime.now(),
      );

      if (!mounted) {
        return;
      }

      setState(() {
        _videoLists = _videoLists
            .map((_VideoList item) => item.id == list.id ? updatedList : item)
            .toList();
        _status = importedEntries.isEmpty
            ? 'errore connessione, provare ad attivare dns 1.1.1.1'
            : 'Lista "${list.name}" aggiornata con ${importedEntries.length} link.';
      });
      if (importedEntries.isEmpty) {
        _showToastStatus('errore connessione, provare ad attivare dns 1.1.1.1');
      }

      await _persistLists();
    } on PlatformException catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _status = showConnectionErrorOnly
            ? 'errore connessione, provare ad attivare dns 1.1.1.1'
            : 'errore connessione, provare ad attivare dns 1.1.1.1';
      });
      _showToastStatus('errore connessione, provare ad attivare dns 1.1.1.1');
      _requestRefreshImportedListFocus();
    } on Exception {
      if (!mounted) {
        return;
      }
      setState(() {
        _status = 'errore connessione, provare ad attivare dns 1.1.1.1';
      });
      _showToastStatus('errore connessione, provare ad attivare dns 1.1.1.1');
      _requestRefreshImportedListFocus();
    } finally {
      if (mounted) {
        setState(() {
          _isBusy = false;
        });
      }
    }
  }

  String _buildImportedEntryName(String url, int index) {
    final uri = Uri.tryParse(url);
    final host = uri?.host.trim();
    if (host != null && host.isNotEmpty) {
      return '$host ${index + 1}';
    }
    return 'Link ${index + 1}';
  }

  void _showToastStatus(String message) {
    if (!mounted) {
      return;
    }
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 4),
          margin: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        ),
      );
  }

  Future<bool> _waitForDnsVpnState(bool expectedValue) async {
    for (var i = 0; i < 12; i++) {
      final current =
          await _channel.invokeMethod<bool>('getDnsVpnEnabled') ?? false;
      if (current == expectedValue) {
        return true;
      }
      await Future<void>.delayed(const Duration(milliseconds: 350));
    }
    return false;
  }

  void _requestRefreshImportedListFocus() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _refreshImportedListFocusNode.requestFocus();
      }
    });
  }

  Future<void> _saveManualEntry() async {
    final list = _selectedList;
    if (list == null || list.sourceType != _VideoListSourceType.manual) {
      return;
    }

    final name = _entryNameController.text.trim();
    final url = _entryUrlController.text.trim();

    if (name.isEmpty || url.isEmpty) {
      setState(() {
        _status = 'Inserisci nome e URL prima di salvare.';
      });
      return;
    }

    final entryId =
        _editingEntryId ?? DateTime.now().microsecondsSinceEpoch.toString();
    final updatedEntries = <_VideoEntry>[
      for (final entry in list.entries)
        if (entry.id != entryId) entry,
      _VideoEntry(id: entryId, name: name, url: url),
    ]..sort(
        (_VideoEntry a, _VideoEntry b) =>
            a.name.toLowerCase().compareTo(b.name.toLowerCase()),
      );

    final updatedList = list.copyWith(entries: updatedEntries);

    setState(() {
      _videoLists = _videoLists
          .map((_VideoList item) => item.id == list.id ? updatedList : item)
          .toList();
      _status = _editingEntryId == null
          ? 'Link "$name" aggiunto alla lista.'
          : 'Link "$name" aggiornato.';
      _editingEntryId = null;
      _entryNameController.clear();
      _entryUrlController.clear();
    });

    await _persistLists();
  }

  Future<void> _deleteEntry(_VideoEntry entry) async {
    final list = _selectedList;
    if (list == null) {
      return;
    }

    final updatedList = list.copyWith(
      entries: list.entries
          .where((_VideoEntry item) => item.id != entry.id)
          .toList(),
    );

    setState(() {
      _videoLists = _videoLists
          .map((_VideoList item) => item.id == list.id ? updatedList : item)
          .toList();
      if (_editingEntryId == entry.id) {
        _editingEntryId = null;
        _entryNameController.clear();
        _entryUrlController.clear();
      }
      _status = 'Voce "${entry.name}" eliminata.';
    });

    await _persistLists();
  }

  void _clearEntryForm() {
    setState(() {
      _editingEntryId = null;
      _entryNameController.clear();
      _entryUrlController.clear();
      _status = null;
    });
  }

  Future<void> _editWithSystemEditor({
    required TextEditingController controller,
    required String title,
    required String hint,
    bool isUrl = false,
  }) async {
    try {
      final value = await _channel.invokeMethod<String>(
        'editText',
        <String, dynamic>{
          'title': title,
          'initialValue': controller.text,
          'hint': hint,
          'isUrl': isUrl,
        },
      );

      if (value == null || !mounted) {
        return;
      }

      setState(() {
        controller.text = value;
        controller.selection = TextSelection.collapsed(offset: value.length);
      });
    } on PlatformException catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _status = error.message ?? 'Impossibile aprire l\'editor di sistema.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final selectedList = _selectedList;

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? result) async {
        if (didPop) return;
        if (_scaffoldKey.currentState?.isDrawerOpen == true) {
          _scaffoldKey.currentState?.closeDrawer();
          return;
        }
        if (_selectedList?.sourceType == _VideoListSourceType.manual) {
          _scaffoldKey.currentState?.openDrawer();
          return;
        }
        final shouldExit = await showDialog<bool>(
          context: context,
          builder: (BuildContext ctx) => AlertDialog(
            title: const Text('Uscire dall\'app?'),
            actions: <Widget>[
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(false),
                child: const Text('Annulla'),
              ),
              FilledButton(
                autofocus: true,
                onPressed: () => Navigator.of(ctx).pop(true),
                style: ButtonStyle(
                  backgroundColor: WidgetStateProperty.resolveWith<Color?>(
                    (Set<WidgetState> states) {
                      if (states.contains(WidgetState.focused)) return Colors.white;
                      return null;
                    },
                  ),
                  foregroundColor: WidgetStateProperty.resolveWith<Color?>(
                    (Set<WidgetState> states) {
                      if (states.contains(WidgetState.focused)) return const Color(0xFF07111F);
                      return null;
                    },
                  ),
                ),
                child: const Text('Esci'),
              ),
            ],
          ),
        );
        if (shouldExit == true) {
          if (_dohEnabled) {
            final prefs = await SharedPreferences.getInstance();
            await prefs.setBool(_dohKey, false);
          }
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
      key: _scaffoldKey,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          focusNode: _menuFocusNode,
          autofocus: true,
          icon: const Icon(Icons.menu),
          onPressed: () => _scaffoldKey.currentState?.openDrawer(),
        ),
        title: Text(selectedList?.name ?? 'VideoB'),
      ),
      drawer: Drawer(
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      'Le Tue Liste',
                      style: theme.textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 16),
                    DropdownButtonFormField<String>(
                      value: _availableSports.contains(_selectedSportFilter)
                          ? _selectedSportFilter
                          : null,
                      decoration: const InputDecoration(
                        labelText: 'Filtro sport',
                      ),
                      items: <DropdownMenuItem<String>>[
                        DropdownMenuItem<String>(
                          value: '',
                          child: Row(
                            children: const <Widget>[
                              Icon(Icons.sports_rounded, size: 18),
                              SizedBox(width: 10),
                              Text('Tutti gli sport'),
                            ],
                          ),
                        ),
                        ..._availableSports.map(
                          (String sport) => DropdownMenuItem<String>(
                            value: sport,
                            child: Row(
                              children: <Widget>[
                                Icon(_sportIcon(sport), size: 18),
                                const SizedBox(width: 10),
                                Text(sport),
                              ],
                            ),
                          ),
                        ),
                      ],
                      onChanged: (String? value) {
                        setState(() {
                          _selectedSportFilter =
                              value == null || value.isEmpty ? null : value;
                        });
                        Navigator.of(context).pop();
                      },
                    ),
                    const SizedBox(height: 16),
                    DropdownButtonFormField<String>(
                      value:
                          _availableLanguages.contains(_selectedLanguageFilter)
                              ? _selectedLanguageFilter
                              : null,
                      decoration: const InputDecoration(
                        labelText: 'Filtro lingua',
                      ),
                      items: <DropdownMenuItem<String>>[
                        const DropdownMenuItem<String>(
                          value: '',
                          child: Row(
                            children: <Widget>[
                              Icon(Icons.language_rounded, size: 18),
                              SizedBox(width: 10),
                              Text('Tutte le lingue'),
                            ],
                          ),
                        ),
                        ..._availableLanguages.map(
                          (String language) => DropdownMenuItem<String>(
                            value: language,
                            child: Row(
                              children: <Widget>[
                                const Icon(Icons.language_rounded, size: 18),
                                const SizedBox(width: 10),
                                Text(language),
                              ],
                            ),
                          ),
                        ),
                      ],
                      onChanged: (String? value) {
                        setState(() {
                          _selectedLanguageFilter =
                              value == null || value.isEmpty ? null : value;
                        });
                        Navigator.of(context).pop();
                      },
                    ),
                    const SizedBox(height: 14),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: FilledButton.tonalIcon(
                        onPressed: _showCreateListDialog,
                        style: ButtonStyle(
                          padding: WidgetStateProperty.all(
                            const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 8,
                            ),
                          ),
                          minimumSize: WidgetStateProperty.all(
                            const Size(0, 36),
                          ),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          visualDensity: const VisualDensity(
                            horizontal: -2,
                            vertical: -2,
                          ),
                          backgroundColor:
                              WidgetStateProperty.resolveWith<Color?>(
                            (Set<WidgetState> states) {
                              if (states.contains(WidgetState.focused)) {
                                return Colors.white;
                              }
                              return null;
                            },
                          ),
                          foregroundColor:
                              WidgetStateProperty.resolveWith<Color?>(
                            (Set<WidgetState> states) {
                              if (states.contains(WidgetState.focused)) {
                                return const Color(0xFF07111F);
                              }
                              return null;
                            },
                          ),
                        ),
                        icon: const Icon(Icons.add_rounded, size: 18),
                        label: const Text('Aggiungi lista'),
                      ),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: ListView.builder(
                  itemCount: _videoLists.length,
                  itemBuilder: (BuildContext context, int index) {
                    final list = _videoLists[index];
                    final isSelected = list.id == _selectedListId;
                    final accent = const Color(0xFFF4B942);
                    return Padding(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 4),
                      child: Row(
                        children: <Widget>[
                          Expanded(
                            child: Focus(
                              child: Builder(
                                builder: (BuildContext ctx) {
                                  final hasFocus = Focus.of(ctx).hasFocus;
                                  return AnimatedContainer(
                                    duration: const Duration(milliseconds: 150),
                                    decoration: BoxDecoration(
                                      color: hasFocus
                                          ? Colors.white
                                          : isSelected
                                              ? accent
                                              : Colors.white
                                                  .withValues(alpha: 0.06),
                                      borderRadius: BorderRadius.circular(14),
                                    ),
                                    child: InkWell(
                                      borderRadius: BorderRadius.circular(14),
                                      onTap: () {
                                        Navigator.of(context).pop();
                                        _selectList(list.id);
                                      },
                                      child: Padding(
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 14, vertical: 10),
                                        child: Row(
                                          children: <Widget>[
                                            Icon(
                                              list.sourceType ==
                                                      _VideoListSourceType
                                                          .manual
                                                  ? Icons.edit_note_rounded
                                                  : Icons
                                                      .cloud_download_rounded,
                                              size: 18,
                                              color: (isSelected || hasFocus)
                                                  ? const Color(0xFF07111F)
                                                  : Colors.white70,
                                            ),
                                            const SizedBox(width: 10),
                                            Expanded(
                                              child: Column(
                                                crossAxisAlignment:
                                                    CrossAxisAlignment.start,
                                                children: <Widget>[
                                                  Text(
                                                    list.name,
                                                    style: TextStyle(
                                                      fontWeight:
                                                          FontWeight.w600,
                                                      color: (isSelected || hasFocus)
                                                          ? const Color(
                                                              0xFF07111F)
                                                          : Colors.white,
                                                    ),
                                                  ),
                                                  Text(
                                                    '${list.sourceType.label} • ${list.entries.length} link',
                                                    style: TextStyle(
                                                      fontSize: 11,
                                                      color: (isSelected || hasFocus)
                                                          ? const Color(
                                                                  0xFF07111F)
                                                              .withValues(
                                                                  alpha: 0.7)
                                                          : Colors.white54,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                          const SizedBox(width: 6),
                          Focus(
                            child: Builder(
                              builder: (BuildContext ctx) {
                                final hasFocus = Focus.of(ctx).hasFocus;
                                return AnimatedContainer(
                                  duration: const Duration(milliseconds: 150),
                                  decoration: BoxDecoration(
                                    color: hasFocus
                                        ? const Color(0xFFFF4444).withValues(alpha: 0.25)
                                        : Colors.white.withValues(alpha: 0.06),
                                    borderRadius: BorderRadius.circular(12),
                                  ),
                                  child: InkWell(
                                    borderRadius: BorderRadius.circular(12),
                                    onTap: () => _confirmDeleteList(list),
                                    child: Padding(
                                      padding: const EdgeInsets.all(10),
                                      child: Icon(
                                        Icons.delete_outline_rounded,
                                        size: 18,
                                        color: hasFocus
                                            ? const Color(0xFFFF4444)
                                            : Colors.white54,
                                      ),
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
              const Divider(height: 1),
              SwitchListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
                secondary: const Icon(Icons.dns_rounded),
                title: const Text('DNS 1.1.1.1 (Cloudflare)'),
                subtitle: Text(_dohEnabled ? 'Attivo' : 'Disattivo'),
                value: _dohEnabled,
                onChanged: (bool val) => _setDohEnabled(val),
              ),
              const Divider(height: 1),
              InkWell(
                onTap: _ensureSportList,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        _appDisplayName,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Versione $_appVersion',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: Colors.white70,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
      body: Focus(
        canRequestFocus: false,
        onKeyEvent: (FocusNode node, KeyEvent event) {
          if (event is KeyDownEvent &&
              event.logicalKey == LogicalKeyboardKey.arrowLeft) {
            final currentFocus = FocusManager.instance.primaryFocus;
            final box = currentFocus?.context?.findRenderObject() as RenderBox?;
            if (box != null && box.hasSize) {
              final pos = box.localToGlobal(Offset.zero);
              if (pos.dx < 280) {
                _menuFocusNode.requestFocus();
                return KeyEventResult.handled;
              }
            }
          }
          return KeyEventResult.ignored;
        },
        child: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: <Color>[
              Color(0xFF07111F),
              Color(0xFF0D1D33),
              Color(0xFF081018),
            ],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: SafeArea(
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : selectedList == null
                  ? Center(
                      child: FilledButton(
                        onPressed: _showCreateListDialog,
                        child: const Text('Crea La Prima Lista'),
                      ),
                    )
                  : Scrollbar(
                      controller: _mainScrollController,
                      thumbVisibility: true,
                      trackVisibility: true,
                      child: ListView(
                      controller: _mainScrollController,
                      padding: const EdgeInsets.only(left: 24, right: 40, top: 24, bottom: 24),
                      children: <Widget>[
                        _SectionCard(
                          title: selectedList.name,
                          subtitle: '',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: <Widget>[
                              if (selectedList.sourceType ==
                                  _VideoListSourceType.imported) ...<Widget>[
                                _buildImportedControls(selectedList),
                                const SizedBox(height: 18),
                              ],
                              _buildListMeta(theme, selectedList),
                              const SizedBox(height: 18),
                              if (_activeEntryName != null) ...<Widget>[
                                Container(
                                  width: double.infinity,
                                  padding: const EdgeInsets.all(14),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFFF4B942)
                                        .withValues(alpha: 0.14),
                                    borderRadius: BorderRadius.circular(18),
                                    border: Border.all(
                                      color: const Color(0xFFF4B942)
                                          .withValues(alpha: 0.35),
                                    ),
                                  ),
                                  child: Row(
                                    children: <Widget>[
                                      const Icon(
                                          Icons.play_circle_fill_rounded),
                                      const SizedBox(width: 10),
                                      Expanded(
                                        child: Text(
                                          'Selezionato: $_activeEntryName',
                                          style: theme.textTheme.titleMedium,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(height: 18),
                              ],
                              if (selectedList.sourceType ==
                                  _VideoListSourceType.manual)
                                _buildManualEditor(theme),
                            ],
                          ),
                        ),
                        const SizedBox(height: 20),
                        if (_filteredEntries.isEmpty)
                          Text(
                            selectedList.entries.isEmpty
                                ? (selectedList.sourceType ==
                                        _VideoListSourceType.manual
                                    ? 'Nessun link ancora. Aggiungi una voce dal form sopra.'
                                    : 'Nessun link importato ancora. Usa "Aggiorna Lista".')
                                : 'Nessun risultato per i filtri selezionati.',
                            style: theme.textTheme.bodyLarge?.copyWith(
                              color: Colors.white70,
                            ),
                          )
                        else
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: _groupedFilteredEntries.expand(
                              (MapEntry<String, List<_VideoEntry>> group) {
                                return <Widget>[
                                  Padding(
                                    padding: const EdgeInsets.only(
                                      top: 16,
                                      bottom: 10,
                                    ),
                                    child: Row(
                                      children: <Widget>[
                                        const Icon(
                                          Icons.calendar_today_rounded,
                                          size: 20,
                                        ),
                                        const SizedBox(width: 8),
                                        Text(
                                          group.key,
                                          style: theme.textTheme.headlineSmall
                                              ?.copyWith(
                                            fontWeight: FontWeight.w700,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                  _buildEntryGrid(selectedList, group.value),
                                ];
                              },
                            ).toList(),
                          ),
                      ],
                    ),
                  ),
        ),
        ),
      ),
      ),
    );
  }

  Widget _buildListMeta(ThemeData theme, _VideoList selectedList) {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: <Widget>[
        Chip(
          label: Text(selectedList.sourceType.label),
          avatar: Icon(
            selectedList.sourceType == _VideoListSourceType.manual
                ? Icons.edit_note_rounded
                : Icons.cloud_download_rounded,
            size: 18,
          ),
        ),
        Chip(label: Text('${selectedList.entries.length} link')),
        if (selectedList.updatedAt != null)
          Chip(
            avatar: const Icon(Icons.update_rounded, size: 18),
            label: Text(
              'Ultimo aggiornamento: ${_formatItalianDateTime(selectedList.updatedAt!)}',
            ),
          ),
        if (selectedList.sourceType == _VideoListSourceType.imported)
          ActionChip(
            focusNode: _refreshImportedListFocusNode,
            avatar: _isBusy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh_rounded, size: 18),
            label: Text(_isBusy ? 'Aggiornamento...' : 'Aggiorna Lista'),
            onPressed:
                _isBusy ? null : () => _refreshImportedList(selectedList.id),
          ),
      ],
    );
  }

  Widget _buildManualEditor(ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        FocusTraversalOrder(
          order: const NumericFocusOrder(1),
          child: _SystemEditorField(
            autofocus: true,
            labelText: 'Nome voce',
            hintText: 'Es. Sky Sport 1',
            value: _entryNameController.text,
            onTap: () => _editWithSystemEditor(
              controller: _entryNameController,
              title: 'Nome voce',
              hint: 'Es. Sky Sport 1',
            ),
          ),
        ),
        const SizedBox(height: 14),
        FocusTraversalOrder(
          order: const NumericFocusOrder(2),
          child: _SystemEditorField(
            labelText: 'URL video',
            hintText: 'https://...',
            value: _entryUrlController.text,
            onTap: () => _editWithSystemEditor(
              controller: _entryUrlController,
              title: 'URL video',
              hint: 'https://...',
              isUrl: true,
            ),
            trailing: IconButton(
              tooltip: 'Incolla',
              onPressed: () async {
                final clipboardData = await Clipboard.getData(
                  Clipboard.kTextPlain,
                );
                final pastedText = clipboardData?.text?.trim() ?? '';
                if (pastedText.isEmpty) {
                  return;
                }

                _entryUrlController.text = pastedText;
                _entryUrlController.selection = TextSelection.collapsed(
                  offset: pastedText.length,
                );
                setState(() {});
              },
              icon: const Icon(Icons.content_paste_go_rounded),
            ),
          ),
        ),
        const SizedBox(height: 18),
        FocusTraversalGroup(
          policy: OrderedTraversalPolicy(),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Row(
                children: <Widget>[
                  const Spacer(),
                  FocusTraversalOrder(
                    order: const NumericFocusOrder(3),
                    child: FilledButton.icon(
                      onPressed: _saveManualEntry,
                      style: ButtonStyle(
                        backgroundColor: WidgetStateProperty.resolveWith<Color?>(
                          (Set<WidgetState> states) {
                            if (states.contains(WidgetState.focused)) {
                              return Colors.white;
                            }
                            return null;
                          },
                        ),
                        foregroundColor: WidgetStateProperty.resolveWith<Color?>(
                          (Set<WidgetState> states) {
                            if (states.contains(WidgetState.focused)) {
                              return const Color(0xFF07111F);
                            }
                            return null;
                          },
                        ),
                      ),
                      icon: const Icon(Icons.save_rounded),
                      label: Text(
                        _editingEntryId == null ? 'Aggiungi Voce' : 'Aggiorna Voce',
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              FocusTraversalOrder(
                order: const NumericFocusOrder(4),
                child: FilledButton.tonal(
                  onPressed: () => _openUrl(_entryUrlController.text),
                  child: const Text('Apri URL'),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildImportedControls(_VideoList selectedList) {
    final sourceUrl = selectedList.sourceUrl ?? '';
    return SelectableText(
      sourceUrl,
      style: const TextStyle(color: Colors.white70),
    );
  }

  Widget _buildEntryGrid(_VideoList list, List<_VideoEntry> entries) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        const spacing = 14.0;
        final maxWidth = constraints.maxWidth.isFinite
            ? constraints.maxWidth
            : MediaQuery.of(context).size.width;
        final columnCount = math.max(1, (maxWidth / 270).floor());
        final cardSize =
            (maxWidth - ((columnCount - 1) * spacing)) / columnCount;

        return Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: Wrap(
            spacing: spacing,
            runSpacing: spacing,
            children: entries
                .map(
                  (_VideoEntry entry) => SizedBox(
                    width: cardSize,
                    child: Column(
                      children: <Widget>[
                        AspectRatio(
                          aspectRatio: 1,
                          child: _buildEntryTile(list, entry),
                        ),
                        if (list.sourceType == _VideoListSourceType.manual)
                          Focus(
                            child: Builder(
                              builder: (BuildContext ctx) {
                                final hasFocus = Focus.of(ctx).hasFocus;
                                return AnimatedContainer(
                                  duration: const Duration(milliseconds: 150),
                                  margin: const EdgeInsets.only(top: 6),
                                  decoration: BoxDecoration(
                                    color: hasFocus
                                        ? Colors.white
                                        : Colors.white.withValues(alpha: 0.06),
                                    borderRadius: BorderRadius.circular(12),
                                  ),
                                  child: InkWell(
                                    borderRadius: BorderRadius.circular(12),
                                    onTap: () => _deleteEntry(entry),
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(vertical: 8),
                                      child: Row(
                                        mainAxisAlignment: MainAxisAlignment.center,
                                        children: <Widget>[
                                          Icon(
                                            Icons.delete_outline_rounded,
                                            size: 18,
                                            color: hasFocus
                                                ? const Color(0xFFFF4444)
                                                : Colors.white54,
                                          ),
                                          const SizedBox(width: 6),
                                          Text(
                                            'Elimina',
                                            style: TextStyle(
                                              fontSize: 12,
                                              color: hasFocus
                                                  ? const Color(0xFFFF4444)
                                                  : Colors.white54,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                      ],
                    ),
                  ),
                )
                .toList(),
          ),
        );
      },
    );
  }

  Widget _buildEntryTile(_VideoList list, _VideoEntry entry) {
    final sportBg = _sportBackground(entry.sportLabel);
    final isFocused =
        _focusedEntryId == entry.id || _activeEntryId == entry.id;
    final borderColor = isFocused
        ? Colors.white
        : Colors.white.withValues(alpha: 0.10);
    final channels =
        entry.channels.isNotEmpty ? entry.channels : <_VideoChannel>[];
    final scheduleLabel = _formatEntrySchedule(entry);
    final hasSchedule = scheduleLabel.isNotEmpty;
    final channelSummary = channels.length == 1
        ? channels.first.label.trim().isNotEmpty
            ? channels.first.label.trim()
            : 'Canale'
        : channels.length > 1
        ? '${channels.length} canali'
        : (entry.url.isNotEmpty ? 'Streaming disponibile' : null);

    return Focus(
      onFocusChange: (bool hasFocus) {
        if (!mounted) return;
        setState(() {
          if (hasFocus) {
            _focusedEntryId = entry.id;
          } else if (_focusedEntryId == entry.id) {
            _focusedEntryId = null;
          }
        });
      },
      onKeyEvent: (FocusNode node, KeyEvent event) {
        if (event is KeyDownEvent &&
            (event.logicalKey == LogicalKeyboardKey.enter ||
             event.logicalKey == LogicalKeyboardKey.select ||
             event.logicalKey == LogicalKeyboardKey.gameButtonA)) {
          _openEntryWithChannelPicker(entry);
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      },
      child: GestureDetector(
        onTap: () => _openEntryWithChannelPicker(entry),
        child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            decoration: BoxDecoration(
              color: sportBg,
              borderRadius: BorderRadius.circular(22),
              border: Border.all(
                color: borderColor,
                width: isFocused ? 2 : 1,
              ),
            ),
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          if (hasSchedule)
                            Text(
                              scheduleLabel,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 24,
                                fontWeight: FontWeight.w800,
                                letterSpacing: -0.8,
                                color: Colors.white,
                              ),
                            ),
                          if (entry.sportLabel != null) ...<Widget>[
                            const SizedBox(height: 10),
                            Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 10, vertical: 5),
                              decoration: BoxDecoration(
                                color: Colors.white.withValues(alpha: 0.10),
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: <Widget>[
                                  Icon(
                                    _sportIcon(entry.sportLabel!),
                                    size: 16,
                                    color: Colors.white,
                                  ),
                                  const SizedBox(width: 6),
                                  Flexible(
                                    child: Text(
                                      entry.sportLabel!,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        fontSize: 13,
                                        color: Colors.white,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                Text(
                  entry.name,
                  maxLines: 4,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                    height: 1.25,
                  ),
                ),
                if (entry.language != null && entry.language!.isNotEmpty) ...<Widget>[
                  const SizedBox(height: 8),
                  Text(
                    'Lingue: ${entry.language}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: Colors.white.withValues(alpha: 0.65),
                    ),
                  ),
                ],
                const Spacer(),
                if (channelSummary != null)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.16),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Text(
                      channelSummary,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
    );
  }

  String _formatDateTime(DateTime dateTime) {
    final day = dateTime.day.toString().padLeft(2, '0');
    final month = dateTime.month.toString().padLeft(2, '0');
    final hour = dateTime.hour.toString().padLeft(2, '0');
    final minute = dateTime.minute.toString().padLeft(2, '0');
    return '$day/$month $hour:$minute';
  }

  String _formatItalianDate(DateTime dateTime) {
    final weekday = _weekdayLabels[dateTime.weekday % 7];
    final month = _monthLabels[dateTime.month - 1];
    return '$weekday ${dateTime.day} $month';
  }

  String _formatItalianDateTime(DateTime dateTime) {
    final month = _monthLabels[dateTime.month - 1];
    if (dateTime.hour == 0 && dateTime.minute == 0 && dateTime.second == 0) {
      return '${dateTime.day} $month';
    }
    final hour = dateTime.hour.toString().padLeft(2, '0');
    final minute = dateTime.minute.toString().padLeft(2, '0');
    return '${dateTime.day} $month $hour:$minute';
  }

  String _formatEntrySchedule(_VideoEntry entry) {
    final dayLabel = entry.dayLabel?.trim();
    final eventTime = entry.eventTime?.trim();
    if (dayLabel != null && dayLabel.isNotEmpty) {
      if (eventTime != null && eventTime.isNotEmpty) {
        return '$dayLabel $eventTime';
      }
      return dayLabel;
    }
    return eventTime ?? '';
  }

  Iterable<String> _entryLanguages(_VideoEntry entry) {
    final collected = <String>{};

    if (entry.language != null && entry.language!.trim().isNotEmpty) {
      final parts = entry.language!
          .split(',')
          .map((String value) => value.trim())
          .where((String value) =>
              value.isNotEmpty && value != 'Lingua non indicata');
      collected.addAll(parts);
    }

    for (final channel in entry.channels) {
      final language = channel.language.trim();
      if (language.isNotEmpty && language != 'Lingua non indicata') {
        collected.add(language);
      }
    }

    return collected;
  }

  IconData _sportIcon(String sportLabel) {
    switch (sportLabel) {
      case 'Calcio':
        return Icons.sports_soccer_rounded;
      case 'Basket':
        return Icons.sports_basketball_rounded;
      case 'Tennis':
        return Icons.sports_tennis_rounded;
      case 'Baseball':
        return Icons.sports_baseball_rounded;
      case 'Golf':
        return Icons.sports_golf_rounded;
      case 'Football Americano':
        return Icons.sports_football_rounded;
      case 'Hockey':
        return Icons.sports_hockey_rounded;
      case 'Volley':
        return Icons.sports_volleyball_rounded;
      case 'Pallamano':
        return Icons.sports_handball_rounded;
      case 'Motori':
        return Icons.sports_motorsports_rounded;
      case 'Boxe':
      case 'MMA':
        return Icons.sports_mma_rounded;
      default:
        return Icons.sports_rounded;
    }
  }

  Color _sportBackground(String? sportLabel) {
    switch (sportLabel) {
      case 'Calcio':
        return const Color(0xFF163A1F);
      case 'Basket':
        return const Color(0xFF4A2B16);
      case 'Tennis':
        return const Color(0xFF244118);
      case 'Golf':
        return const Color(0xFF183B2B);
      case 'Football Americano':
        return const Color(0xFF3B2418);
      case 'Hockey':
        return const Color(0xFF183245);
      case 'Baseball':
        return const Color(0xFF452A2A);
      case 'Rugby':
        return const Color(0xFF403018);
      case 'Volley':
        return const Color(0xFF3C2A4A);
      case 'Pallamano':
        return const Color(0xFF4A3318);
      case 'Motori':
        return const Color(0xFF4A1818);
      case 'Boxe':
      case 'MMA':
        return const Color(0xFF3E1823);
      default:
        return Colors.white.withValues(alpha: 0.05);
    }
  }
}

enum _VideoListSourceType {
  manual('Manuale'),
  imported('Importata');

  const _VideoListSourceType(this.label);

  final String label;
}

class _VideoChannel {
  const _VideoChannel({
    required this.url,
    required this.label,
    required this.language,
  });

  final String url;
  final String label;
  final String language;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'url': url,
      'label': label,
      'language': language,
    };
  }

  factory _VideoChannel.fromJson(Map<String, dynamic> json) {
    return _VideoChannel(
      url: json['url'] as String? ?? '',
      label: json['label'] as String? ?? '',
      language: json['language'] as String? ?? '',
    );
  }
}

class _VideoEntry {
  const _VideoEntry({
    required this.id,
    required this.name,
    required this.url,
    this.eventTime,
    this.dayLabel,
    this.language,
    this.sportLabel,
    this.channels = const <_VideoChannel>[],
  });

  final String id;
  final String name;
  final String url;
  final String? eventTime;
  final String? dayLabel;
  final String? language;
  final String? sportLabel;
  final List<_VideoChannel> channels;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'url': url,
      'eventTime': eventTime,
      'dayLabel': dayLabel,
      'language': language,
      'sportLabel': sportLabel,
      'channels': channels.map((c) => c.toJson()).toList(),
    };
  }

  factory _VideoEntry.fromJson(Map<String, dynamic> json) {
    final rawChannels =
        json['channels'] as List<dynamic>? ?? const <dynamic>[];
    return _VideoEntry(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      url: json['url'] as String? ?? '',
      eventTime: json['eventTime'] as String?,
      dayLabel: json['dayLabel'] as String?,
      language: json['language'] as String?,
      sportLabel: json['sportLabel'] as String?,
      channels: rawChannels
          .whereType<Map<String, dynamic>>()
          .map(_VideoChannel.fromJson)
          .toList(),
    );
  }
}

class _ImportedScheduleAccumulator {
  _ImportedScheduleAccumulator({
    required this.title,
    required this.eventTime,
    required this.sportLabel,
    required this.dayLabel,
  });

  final String title;
  final String eventTime;
  final String? sportLabel;
  final String? dayLabel;
  final List<_VideoChannel> channels = <_VideoChannel>[];
  final Set<String> _languages = <String>{};

  void addChannel(String url, String label, String language) {
    channels.add(_VideoChannel(url: url, label: label, language: language));
    if (language.trim().isNotEmpty &&
        language != 'Lingua non indicata') {
      _languages.add(language.trim());
    }
  }

  String? get primaryUrl => channels.isNotEmpty ? channels.first.url : null;

  String get languageLabel {
    if (_languages.isEmpty) {
      return 'Lingua non indicata';
    }
    final sorted = _languages.toList()..sort();
    return sorted.join(', ');
  }
}

class _ImportedScheduleEvent {
  const _ImportedScheduleEvent({
    required this.title,
    required this.url,
    required this.eventTime,
    required this.dayLabel,
    required this.languageLabel,
    required this.sportLabel,
    required this.channels,
  });

  final String title;
  final String url;
  final String eventTime;
  final String dayLabel;
  final String languageLabel;
  final String? sportLabel;
  final List<_VideoChannel> channels;
}

class _BackupPayload {
  const _BackupPayload({
    required this.rawLists,
    required this.selectedListId,
  });

  final String rawLists;
  final String? selectedListId;
}

class _CreateListDialogResult {
  const _CreateListDialogResult({
    required this.name,
    required this.sourceType,
    required this.sourceUrl,
  });

  final String name;
  final _VideoListSourceType sourceType;
  final String sourceUrl;
}

class _CreateListPage extends StatefulWidget {
  const _CreateListPage();

  @override
  State<_CreateListPage> createState() => _CreateListPageState();
}

class _CreateListPageState extends State<_CreateListPage> {
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _sourceUrlController = TextEditingController();
  _VideoListSourceType _sourceType = _VideoListSourceType.manual;

  @override
  void dispose() {
    _nameController.dispose();
    _sourceUrlController.dispose();
    super.dispose();
  }

  Future<void> _editWithSystemEditor({
    required TextEditingController controller,
    required String title,
    required String hint,
    bool isUrl = false,
  }) async {
    const channel = MethodChannel('videob/channel');

    final value = await channel.invokeMethod<String>(
      'editText',
      <String, dynamic>{
        'title': title,
        'initialValue': controller.text,
        'hint': hint,
        'isUrl': isUrl,
      },
    );

    if (value == null || !mounted) {
      return;
    }

    setState(() {
      controller.text = value;
      controller.selection = TextSelection.collapsed(offset: value.length);
    });
  }

  Future<void> _pasteUrl() async {
    final clipboardData = await Clipboard.getData(Clipboard.kTextPlain);
    final pastedText = clipboardData?.text?.trim() ?? '';
    if (pastedText.isEmpty) {
      return;
    }

    setState(() {
      _sourceUrlController.text = pastedText;
      _sourceUrlController.selection = TextSelection.collapsed(
        offset: pastedText.length,
      );
    });
  }

  void _submit() {
    final name = _nameController.text.trim();
    final sourceUrl = _sourceUrlController.text.trim();
    if (name.isEmpty) {
      return;
    }
    if (_sourceType == _VideoListSourceType.imported && sourceUrl.isEmpty) {
      return;
    }

    Navigator.of(context).pop(
      _CreateListDialogResult(
        name: name,
        sourceType: _sourceType,
        sourceUrl: sourceUrl,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final viewInsets = MediaQuery.of(context).viewInsets;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Nuova Lista'),
      ),
      body: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
        padding: EdgeInsets.only(bottom: viewInsets.bottom),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 16, 24, 24),
            children: <Widget>[
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 840),
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Text(
                          'Crea una nuova lista manuale o importata da URL.',
                          style: theme.textTheme.bodyLarge,
                        ),
                        const SizedBox(height: 20),
                        _SystemEditorField(
                          autofocus: true,
                          labelText: 'Nome lista',
                          hintText: 'Es. Calcio, Film, Sport',
                          value: _nameController.text,
                          onTap: () => _editWithSystemEditor(
                            controller: _nameController,
                            title: 'Nome lista',
                            hint: 'Es. Calcio, Film, Sport',
                          ),
                        ),
                        const SizedBox(height: 16),
                        SegmentedButton<_VideoListSourceType>(
                          segments: const <ButtonSegment<_VideoListSourceType>>[
                            ButtonSegment<_VideoListSourceType>(
                              value: _VideoListSourceType.manual,
                              label: Text('Manuale'),
                              icon: Icon(Icons.edit_note_rounded),
                            ),
                            ButtonSegment<_VideoListSourceType>(
                              value: _VideoListSourceType.imported,
                              label: Text('Da URL'),
                              icon: Icon(Icons.cloud_download_rounded),
                            ),
                          ],
                          selected: <_VideoListSourceType>{_sourceType},
                          onSelectionChanged:
                              (Set<_VideoListSourceType> value) {
                            setState(() {
                              _sourceType = value.first;
                            });
                          },
                        ),
                        if (_sourceType ==
                            _VideoListSourceType.imported) ...<Widget>[
                          const SizedBox(height: 16),
                          _SystemEditorField(
                            labelText: 'URL da importare',
                            hintText: 'https://...',
                            value: _sourceUrlController.text,
                            onTap: () => _editWithSystemEditor(
                              controller: _sourceUrlController,
                              title: 'URL da importare',
                              hint: 'https://...',
                              isUrl: true,
                            ),
                          ),
                          const SizedBox(height: 10),
                          Align(
                            alignment: Alignment.centerRight,
                            child: FilledButton.tonalIcon(
                              onPressed: _pasteUrl,
                              icon: const Icon(Icons.content_paste_go_rounded),
                              label: const Text('Incolla'),
                            ),
                          ),
                        ],
                        const SizedBox(height: 18),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.end,
                          children: <Widget>[
                            TextButton(
                              onPressed: () => Navigator.of(context).pop(),
                              child: const Text('Annulla'),
                            ),
                            const SizedBox(width: 12),
                            FilledButton(
                              onPressed: _submit,
                              style: ButtonStyle(
                                backgroundColor: WidgetStateProperty.resolveWith<Color?>(
                                  (Set<WidgetState> states) {
                                    if (states.contains(WidgetState.focused)) return Colors.white;
                                    return null;
                                  },
                                ),
                                foregroundColor: WidgetStateProperty.resolveWith<Color?>(
                                  (Set<WidgetState> states) {
                                    if (states.contains(WidgetState.focused)) return const Color(0xFF07111F);
                                    return null;
                                  },
                                ),
                              ),
                              child: const Text('Crea'),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _VideoList {
  const _VideoList({
    required this.id,
    required this.name,
    required this.sourceType,
    required this.entries,
    this.sourceUrl,
    this.updatedAt,
  });

  final String id;
  final String name;
  final _VideoListSourceType sourceType;
  final String? sourceUrl;
  final List<_VideoEntry> entries;
  final DateTime? updatedAt;

  _VideoList copyWith({
    String? id,
    String? name,
    _VideoListSourceType? sourceType,
    String? sourceUrl,
    List<_VideoEntry>? entries,
    DateTime? updatedAt,
  }) {
    return _VideoList(
      id: id ?? this.id,
      name: name ?? this.name,
      sourceType: sourceType ?? this.sourceType,
      sourceUrl: sourceUrl ?? this.sourceUrl,
      entries: entries ?? this.entries,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'sourceType': sourceType.name,
      'sourceUrl': sourceUrl,
      'updatedAt': updatedAt?.toIso8601String(),
      'entries': entries.map((_VideoEntry entry) => entry.toJson()).toList(),
    };
  }

  factory _VideoList.fromJson(Map<String, dynamic> json) {
    final rawType =
        json['sourceType'] as String? ?? _VideoListSourceType.manual.name;
    final rawEntries = json['entries'] as List<dynamic>? ?? const <dynamic>[];

    return _VideoList(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? 'Lista',
      sourceType: rawType == _VideoListSourceType.imported.name
          ? _VideoListSourceType.imported
          : _VideoListSourceType.manual,
      sourceUrl: json['sourceUrl'] as String?,
      updatedAt: json['updatedAt'] == null
          ? null
          : DateTime.tryParse(json['updatedAt'] as String),
      entries: rawEntries
          .whereType<Map<String, dynamic>>()
          .map(_VideoEntry.fromJson)
          .toList(),
    );
  }
}

class _SystemEditorField extends StatelessWidget {
  const _SystemEditorField({
    required this.labelText,
    required this.hintText,
    required this.value,
    required this.onTap,
    this.trailing,
    this.autofocus = false,
  });

  final String labelText;
  final String hintText;
  final String value;
  final VoidCallback onTap;
  final Widget? trailing;
  final bool autofocus;

  @override
  Widget build(BuildContext context) {
    final hasValue = value.trim().isNotEmpty;

    return InkWell(
      autofocus: autofocus,
      borderRadius: BorderRadius.circular(20),
      onTap: onTap,
      child: InputDecorator(
        isFocused: false,
        isEmpty: !hasValue,
        decoration: InputDecoration(
          labelText: labelText,
          hintText: hasValue ? null : hintText,
          hintStyle: const TextStyle(color: Colors.white38),
          suffixIcon: trailing ?? const Icon(Icons.edit_rounded),
        ),
        child: hasValue
            ? Text(
                value,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white),
              )
            : const SizedBox.shrink(),
      ),
    );
  }
}

class _ChannelButton extends StatelessWidget {
  const _ChannelButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: const Color(0xFF050505),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.white.withValues(alpha: 0.16)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            const Icon(Icons.play_circle_outline_rounded,
                size: 14, color: Colors.white70),
            const SizedBox(width: 6),
            Text(
              label,
              style: const TextStyle(fontSize: 12, color: Colors.white),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.title,
    this.subtitle = '',
    required this.child,
  });

  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(
              title,
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
            if (subtitle.trim().isNotEmpty) ...<Widget>[
              const SizedBox(height: 6),
              Text(
                subtitle,
                style: theme.textTheme.bodyLarge?.copyWith(
                  color: Colors.white70,
                ),
              ),
              const SizedBox(height: 18),
            ] else
              const SizedBox(height: 16),
            child,
          ],
        ),
      ),
    );
  }
}
