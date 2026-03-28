import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const VideoBApp());
}

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
  static const _appDisplayName = 'Video BonoTrot';
  static const _appVersion = '1.0.0+1';

  final TextEditingController _entryNameController = TextEditingController();
  final TextEditingController _entryUrlController = TextEditingController();

  List<_VideoList> _videoLists = const <_VideoList>[];
  String? _selectedListId;
  String? _selectedSportFilter;
  String? _editingEntryId;
  String? _activeEntryId;
  String? _activeEntryName;
  bool _isLoading = true;
  bool _isBusy = false;
  String? _status;

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

  List<_VideoEntry> get _filteredEntries {
    final selectedList = _selectedList;
    if (selectedList == null) {
      return const <_VideoEntry>[];
    }

    final selectedSport = _selectedSportFilter;
    if (selectedSport == null || selectedSport.isEmpty) {
      return selectedList.entries;
    }

    return selectedList.entries
        .where((_VideoEntry entry) => entry.sportLabel == selectedSport)
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
    super.dispose();
  }

  Future<void> _loadLists() async {
    final preferences = await SharedPreferences.getInstance();
    final rawLists = preferences.getString(_listsKey);
    final selectedListId = preferences.getString(_selectedListKey);

    List<_VideoList> parsedLists = <_VideoList>[];
    if (rawLists != null && rawLists.isNotEmpty) {
      final decoded = jsonDecode(rawLists);
      if (decoded is List<dynamic>) {
        parsedLists = decoded
            .whereType<Map<String, dynamic>>()
            .map(_VideoList.fromJson)
            .toList();
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

  Future<void> _persistLists() async {
    final preferences = await SharedPreferences.getInstance();
    final payload = jsonEncode(
      _videoLists.map((_VideoList list) => list.toJson()).toList(),
    );
    await preferences.setString(_listsKey, payload);
    if (_selectedListId != null) {
      await preferences.setString(_selectedListKey, _selectedListId!);
    }
  }

  void _selectList(String id) {
    setState(() {
      _selectedListId = id;
      if (_selectedSportFilter != null &&
          !_availableSports.contains(_selectedSportFilter)) {
        _selectedSportFilter = null;
      }
      _editingEntryId = null;
      _entryNameController.clear();
      _entryUrlController.clear();
      _status = null;
    });
    _persistLists();
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
      <String, dynamic>{'url': url},
    );

    return (response as List<dynamic>? ?? <dynamic>[])
        .map((dynamic item) => item.toString())
        .where((String item) => item.isNotEmpty)
        .toList();
  }

  Future<List<_VideoEntry>> _loadEntriesFromSource(String sourceUrl) async {
    final uri = Uri.tryParse(sourceUrl);
    if (uri == null) {
      throw const FormatException('URL sorgente non valido.');
    }

    final looksLikePlainText = uri.path.toLowerCase().endsWith('.txt');
    if (looksLikePlainText) {
      return _loadEntriesFromPlainTextSource(uri);
    }

    final links =
        (await _extractLinksFromUrl(sourceUrl)).where(_isPhpUrl).toList();
    return <_VideoEntry>[
      for (var i = 0; i < links.length; i++)
        _VideoEntry(
          id: '${DateTime.now().microsecondsSinceEpoch}-$i',
          name: _buildImportedEntryName(links[i], i),
          url: links[i],
          language: _extractLanguageFromUrl(links[i]),
          sportLabel:
              _detectSportFromName(_buildImportedEntryName(links[i], i)),
        ),
    ];
  }

  Future<List<_VideoEntry>> _loadEntriesFromPlainTextSource(Uri uri) async {
    final response = await http.get(uri);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw PlatformException(
        code: 'plain_text_fetch_failed',
        message:
            'Impossibile leggere la lista testuale (${response.statusCode}).',
      );
    }

    final body = response.body.replaceAll('\n', ' ').replaceAll('\r', ' ');
    final urlPattern = RegExp(r'https?://\S+');
    final matches = urlPattern.allMatches(body).toList();
    final entries = <_VideoEntry>[];
    var previousEnd = 0;
    String? currentDay;

    for (var i = 0; i < matches.length; i++) {
      final match = matches[i];
      final rawUrl = match.group(0)?.trim() ?? '';
      if (rawUrl.isEmpty || !_isPhpUrl(rawUrl)) {
        previousEnd = match.end;
        continue;
      }

      final contextChunk = body.substring(previousEnd, match.start).trim();
      final dayFromChunk = _extractDayFromContext(contextChunk);
      if (dayFromChunk != null) {
        currentDay = dayFromChunk;
      }

      final metadata = _extractPlainTextMetadata(
        contextChunk,
        rawUrl,
        i,
        currentDay: currentDay,
      );
      entries.add(
        _VideoEntry(
          id: '${DateTime.now().microsecondsSinceEpoch}-$i',
          name: metadata.name,
          url: rawUrl,
          eventTime: metadata.eventTime,
          dayLabel: metadata.dayLabel,
          language: metadata.language,
          sportLabel: metadata.sportLabel,
        ),
      );
      previousEnd = match.end;
    }

    return entries;
  }

  bool _isPhpUrl(String url) {
    final uri = Uri.tryParse(url);
    final path = uri?.path.toLowerCase() ?? '';
    return path.endsWith('.php');
  }

  _ImportedEntryMetadata _extractPlainTextMetadata(
    String rawContext,
    String url,
    int index, {
    String? currentDay,
  }) {
    var cleaned = _normalizeContextText(rawContext);
    final inlineDay = _extractDayFromContext(cleaned);
    final effectiveDay = inlineDay ?? currentDay;
    if (inlineDay != null) {
      cleaned = _removeDayMarkers(cleaned).trim();
    }

    final timeMatch = RegExp(r'(\d{1,2}:\d{2})\s*$').firstMatch(cleaned);
    final eventTime = timeMatch?.group(1);
    if (timeMatch != null && eventTime != null) {
      cleaned = cleaned.substring(0, timeMatch.start).trim();
    }

    if (cleaned.isEmpty) {
      cleaned = 'Link ${index + 1}';
    }

    return _ImportedEntryMetadata(
      name: cleaned,
      eventTime: eventTime,
      dayLabel: effectiveDay,
      language: _extractLanguageFromUrl(url),
      sportLabel: _detectSportFromName(cleaned),
    );
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

  String _normalizeContextText(String rawContext) {
    var cleaned = rawContext.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (cleaned.endsWith('|')) {
      cleaned = cleaned.substring(0, cleaned.length - 1).trim();
    }
    return cleaned;
  }

  String? _extractDayFromContext(String rawContext) {
    final normalized = rawContext.toUpperCase();
    if (normalized.contains('SATURDAY')) {
      return 'Sabato';
    }
    if (normalized.contains('SUNDAY')) {
      return 'Domenica';
    }
    return null;
  }

  String _removeDayMarkers(String rawContext) {
    return rawContext
        .replaceAll(RegExp(r'\bSATURDAY\b', caseSensitive: false), '')
        .replaceAll(RegExp(r'\bSUNDAY\b', caseSensitive: false), '');
  }

  String? _extractLanguageFromUrl(String url) {
    final uri = Uri.tryParse(url);
    final filename = uri?.pathSegments.isNotEmpty == true
        ? uri!.pathSegments.last.toLowerCase()
        : '';
    final channelCode = filename.replaceAll('.php', '');

    const channelLanguageMap = <String, String>{
      'hd1': 'English',
      'hd2': 'English',
      'hd3': 'German',
      'hd4': 'French',
      'hd5': 'English',
      'hd6': 'Spanish',
      'hd7': 'Italian',
      'hd8': 'English',
      'hd9': 'Arabic & Spanish',
      'hd10': 'Spanish',
      'hd11': 'English & Spanish',
      'br1': 'Brazilian',
      'br2': 'Brazilian',
      'br3': 'Brazilian',
      'br4': 'Brazilian',
      'br5': 'Brazilian',
      'br6': 'Brazilian',
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
        return 'Portuguese';
      case 'bra':
        return 'Brazilian';
      default:
        return null;
    }
  }

  Future<void> _showCreateListDialog() async {
    var sourceType = _VideoListSourceType.manual;
    var listName = '';
    var sourceUrl = '';
    final sourceUrlController = TextEditingController();

    await showDialog<void>(
      context: context,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context,
              void Function(void Function()) setModalState) {
            return AlertDialog(
              title: const Text('Nuova Lista'),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    TextFormField(
                      decoration: const InputDecoration(
                        labelText: 'Nome lista',
                        hintText: 'Es. Calcio, Film, Sport',
                      ),
                      onChanged: (String value) {
                        listName = value;
                      },
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
                      selected: <_VideoListSourceType>{sourceType},
                      onSelectionChanged: (Set<_VideoListSourceType> value) {
                        setModalState(() {
                          sourceType = value.first;
                        });
                      },
                    ),
                    if (sourceType ==
                        _VideoListSourceType.imported) ...<Widget>[
                      const SizedBox(height: 16),
                      TextFormField(
                        controller: sourceUrlController,
                        decoration: const InputDecoration(
                          labelText: 'URL da importare',
                          hintText: 'https://...',
                          suffixIcon: Icon(Icons.content_paste_rounded),
                        ),
                        onChanged: (String value) {
                          sourceUrl = value;
                        },
                        onTapOutside: (_) {
                          FocusManager.instance.primaryFocus?.unfocus();
                        },
                      ),
                      const SizedBox(height: 10),
                      Align(
                        alignment: Alignment.centerRight,
                        child: FilledButton.tonalIcon(
                          onPressed: () async {
                            final clipboardData = await Clipboard.getData(
                              Clipboard.kTextPlain,
                            );
                            final pastedText =
                                clipboardData?.text?.trim() ?? '';
                            if (pastedText.isEmpty) {
                              return;
                            }

                            setModalState(() {
                              sourceUrl = pastedText;
                              sourceUrlController.text = pastedText;
                              sourceUrlController.selection =
                                  TextSelection.collapsed(
                                offset: pastedText.length,
                              );
                            });
                          },
                          icon: const Icon(Icons.content_paste_go_rounded),
                          label: const Text('Incolla'),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              actions: <Widget>[
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Annulla'),
                ),
                FilledButton(
                  onPressed: () async {
                    final name = listName.trim();
                    final importUrl = sourceUrl.trim();

                    if (name.isEmpty) {
                      return;
                    }
                    if (sourceType == _VideoListSourceType.imported &&
                        importUrl.isEmpty) {
                      return;
                    }

                    Navigator.of(context).pop();
                    await _createList(
                      name: name,
                      sourceType: sourceType,
                      sourceUrl: sourceType == _VideoListSourceType.imported
                          ? importUrl
                          : null,
                    );
                  },
                  child: const Text('Crea'),
                ),
              ],
            );
          },
        );
      },
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

  Future<void> _refreshImportedList(String listId) async {
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

    try {
      final importedEntries = await _loadEntriesFromSource(sourceUrl);

      final updatedList = list.copyWith(
        entries: importedEntries,
        updatedAt: DateTime.now(),
      );

      if (!mounted) {
        return;
      }

      setState(() {
        _videoLists = _videoLists
            .map((_VideoList item) => item.id == list.id ? updatedList : item)
            .toList();
        _status = importedEntries.isEmpty
            ? 'Nessun link trovato in "${list.name}".'
            : 'Lista "${list.name}" aggiornata con ${importedEntries.length} link.';
      });

      await _persistLists();
    } on PlatformException catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _status =
            error.message ?? 'Errore durante l\'aggiornamento della lista.';
      });
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

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final selectedList = _selectedList;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
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
                    const SizedBox(height: 8),
                    Text(
                      'Crea liste manuali o liste importate da URL.',
                      style: theme.textTheme.bodyLarge,
                    ),
                    const SizedBox(height: 16),
                    FilledButton.icon(
                      onPressed: _showCreateListDialog,
                      icon: const Icon(Icons.add_rounded),
                      label: const Text('Nuova Lista'),
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
                    return ListTile(
                      selected: isSelected,
                      leading: Icon(
                        list.sourceType == _VideoListSourceType.manual
                            ? Icons.edit_note_rounded
                            : Icons.cloud_download_rounded,
                      ),
                      title: Text(list.name),
                      subtitle: Text(
                        '${list.sourceType.label} • ${list.entries.length} link',
                      ),
                      trailing: IconButton(
                        tooltip: 'Elimina lista',
                        onPressed: () => _deleteList(list),
                        icon: const Icon(Icons.delete_outline_rounded),
                      ),
                      onTap: () {
                        Navigator.of(context).pop();
                        _selectList(list.id);
                      },
                    );
                  },
                ),
              ),
              const Divider(height: 1),
              Padding(
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
            ],
          ),
        ),
      ),
      body: Container(
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
                  : ListView(
                      padding: const EdgeInsets.all(24),
                      children: <Widget>[
                        _SectionCard(
                          title: selectedList.name,
                          subtitle: selectedList.sourceType ==
                                  _VideoListSourceType.manual
                              ? 'Lista manuale: inserisci URL e nomi a mano.'
                              : 'Lista importata: aggiorna quando vuoi rileggendo l\'URL sorgente.',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: <Widget>[
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
                                _buildManualEditor(theme)
                              else
                                _buildImportedControls(selectedList),
                              if (_status != null) ...<Widget>[
                                const SizedBox(height: 16),
                                Text(
                                  _status!,
                                  style: theme.textTheme.bodyLarge?.copyWith(
                                    color: const Color(0xFFCFD9E6),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                        const SizedBox(height: 20),
                        _SectionCard(
                          title: 'Contenuti',
                          subtitle: selectedList.sourceType ==
                                  _VideoListSourceType.manual
                              ? 'Ogni voce puo essere modificata, aperta o eliminata.'
                              : 'Le voci arrivano dalla pagina sorgente e si aggiornano su richiesta.',
                          child: _filteredEntries.isEmpty
                              ? Text(
                                  selectedList.entries.isEmpty
                                      ? (selectedList.sourceType ==
                                              _VideoListSourceType.manual
                                          ? 'Nessun link ancora. Aggiungi una voce dal form sopra.'
                                          : 'Nessun link importato ancora. Usa "Aggiorna Lista".')
                                      : 'Nessun risultato per il filtro sport selezionato.',
                                  style: theme.textTheme.bodyLarge?.copyWith(
                                    color: Colors.white70,
                                  ),
                                )
                              : Column(
                                  children: _filteredEntries
                                      .map(
                                        (_VideoEntry entry) => _buildEntryTile(
                                            selectedList, entry),
                                      )
                                      .toList(),
                                ),
                        ),
                      ],
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
              label: Text(
                  'Aggiornata ${_formatDateTime(selectedList.updatedAt!)}')),
      ],
    );
  }

  Widget _buildManualEditor(ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        TextField(
          controller: _entryNameController,
          decoration: const InputDecoration(
            labelText: 'Nome voce',
            hintText: 'Es. Sky Sport 1',
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          controller: _entryUrlController,
          decoration: InputDecoration(
            labelText: 'URL video',
            hintText: 'https://...',
            suffixIcon: IconButton(
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
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: <Widget>[
            FilledButton.icon(
              onPressed: _saveManualEntry,
              icon: const Icon(Icons.save_rounded),
              label: Text(
                  _editingEntryId == null ? 'Aggiungi Voce' : 'Aggiorna Voce'),
            ),
            FilledButton.tonal(
              onPressed: () => _openUrl(_entryUrlController.text),
              child: const Text('Apri URL'),
            ),
            OutlinedButton(
              onPressed: _clearEntryForm,
              child: const Text('Pulisci'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildImportedControls(_VideoList selectedList) {
    final sourceUrl = selectedList.sourceUrl ?? '';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        SelectableText(
          sourceUrl,
          style: const TextStyle(color: Colors.white70),
        ),
        const SizedBox(height: 18),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: <Widget>[
            FilledButton.icon(
              onPressed:
                  _isBusy ? null : () => _refreshImportedList(selectedList.id),
              icon: const Icon(Icons.refresh_rounded),
              label: Text(_isBusy ? 'Aggiornamento...' : 'Aggiorna Lista'),
            ),
            FilledButton.tonal(
              onPressed: () => _openUrl(sourceUrl),
              child: const Text('Apri URL Sorgente'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildEntryTile(_VideoList list, _VideoEntry entry) {
    final sportBackground = _sportBackground(entry.sportLabel);
    final isActive = _activeEntryId == entry.id;

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        onTap: () => _openUrl(
          entry.url,
          entryId: entry.id,
          entryName: entry.name,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(
            color: isActive
                ? const Color(0xFFF4B942)
                : Colors.white.withValues(alpha: 0.08),
            width: isActive ? 2 : 1,
          ),
        ),
        tileColor: sportBackground,
        title: Text(entry.name),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            if (entry.eventTime != null ||
                entry.dayLabel != null ||
                entry.language != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: <Widget>[
                    if (entry.eventTime != null)
                      Chip(
                        visualDensity: VisualDensity.compact,
                        label: Text(entry.eventTime!),
                      ),
                    if (entry.dayLabel != null)
                      Chip(
                        visualDensity: VisualDensity.compact,
                        label: Text(entry.dayLabel!),
                      ),
                    if (entry.language != null)
                      Chip(
                        visualDensity: VisualDensity.compact,
                        label: Text(entry.language!),
                      ),
                    if (entry.sportLabel != null)
                      Chip(
                        visualDensity: VisualDensity.compact,
                        avatar: Icon(
                          _sportIcon(entry.sportLabel!),
                          size: 16,
                        ),
                        label: Text(entry.sportLabel!),
                      ),
                  ],
                ),
              ),
            Text(
              entry.url,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
        leading: Icon(
          entry.sportLabel == null
              ? Icons.play_circle_outline_rounded
              : _sportIcon(entry.sportLabel!),
        ),
        trailing: Wrap(
          spacing: 8,
          children: <Widget>[
            IconButton(
              tooltip: 'Elimina',
              onPressed: () => _deleteEntry(entry),
              icon: const Icon(Icons.delete_outline_rounded),
            ),
          ],
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

class _VideoEntry {
  const _VideoEntry({
    required this.id,
    required this.name,
    required this.url,
    this.eventTime,
    this.dayLabel,
    this.language,
    this.sportLabel,
  });

  final String id;
  final String name;
  final String url;
  final String? eventTime;
  final String? dayLabel;
  final String? language;
  final String? sportLabel;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'url': url,
      'eventTime': eventTime,
      'dayLabel': dayLabel,
      'language': language,
      'sportLabel': sportLabel,
    };
  }

  factory _VideoEntry.fromJson(Map<String, dynamic> json) {
    return _VideoEntry(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      url: json['url'] as String? ?? '',
      eventTime: json['eventTime'] as String?,
      dayLabel: json['dayLabel'] as String?,
      language: json['language'] as String?,
      sportLabel: json['sportLabel'] as String?,
    );
  }
}

class _ImportedEntryMetadata {
  const _ImportedEntryMetadata({
    required this.name,
    required this.eventTime,
    required this.dayLabel,
    required this.language,
    required this.sportLabel,
  });

  final String name;
  final String? eventTime;
  final String? dayLabel;
  final String? language;
  final String? sportLabel;
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

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.title,
    required this.subtitle,
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
            const SizedBox(height: 6),
            Text(
              subtitle,
              style: theme.textTheme.bodyLarge?.copyWith(
                color: Colors.white70,
              ),
            ),
            const SizedBox(height: 18),
            child,
          ],
        ),
      ),
    );
  }
}
