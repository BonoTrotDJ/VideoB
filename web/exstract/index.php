<?php
declare(strict_types=1);

const SOURCE_URL = 'https://sportsonline.st/prog.txt';
const WEEKDAYS_IT = [
    'Domenica',
    'Lunedì',
    'Martedì',
    'Mercoledì',
    'Giovedì',
    'Venerdì',
    'Sabato',
];
const HD_LANGUAGE_MAP = [
    'hd1' => 'Inglese',
    'hd2' => 'Inglese',
    'hd4' => 'Francese',
    'hd5' => 'Inglese',
    'hd6' => 'Spagnolo',
    'hd7' => 'Italiano',
    'hd8' => 'Italiano',
    'hd9' => 'Italiano',
    'hd10' => 'Italiano e spagnolo',
    'hd11' => 'Inglese e spagnolo',
];

/**
 * @return array{
 *   events: array<int, array{
 *     title: string,
 *     time: string,
 *     sport: string,
 *     day: string,
 *     languages: array<int, string>,
 *     channels: array<int, array{url: string, label: string, language: string}>
 *   }>,
 *   error: ?string,
 *   updatedAt: string
 * }
 */
function loadSchedule(): array
{
    $raw = fetchRemoteText(SOURCE_URL);
    $updatedAt = date('Y-m-d H:i:s');

    if ($raw === false || trim($raw) === '') {
        return [
            'events' => [],
            'error' => 'Impossibile leggere il palinsesto remoto in questo momento.',
            'updatedAt' => $updatedAt,
        ];
    }

    return [
        'events' => parseSchedule($raw),
        'error' => null,
        'updatedAt' => $updatedAt,
    ];
}

function fetchRemoteText(string $url): string|false
{
    $context = stream_context_create([
        'http' => [
            'timeout' => 10,
            'user_agent' => 'Sport Schedule Reader/1.0',
        ],
        'ssl' => [
            'verify_peer' => true,
            'verify_peer_name' => true,
        ],
    ]);

    $raw = @file_get_contents($url, false, $context);
    if (is_string($raw) && $raw !== '') {
        return $raw;
    }

    if (!function_exists('curl_init')) {
        return false;
    }

    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_USERAGENT => 'Sport Schedule Reader/1.0',
    ]);

    $response = curl_exec($ch);
    $statusCode = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    unset($ch);

    if (!is_string($response) || $response === '' || $statusCode >= 400) {
        return false;
    }

    return $response;
}

/**
 * @return array<int, array{
 *   title: string,
 *   time: string,
 *   sport: string,
 *   sportIcon: string,
 *   day: string,
 *   languages: array<int, string>,
 *   channels: array<int, array{url: string, label: string, language: string}>
 * }>
 */
function parseSchedule(string $raw): array
{
    // Keep only lines that contain a URL — strips header/footer junk
    $lines = preg_split('/\r?\n/', $raw) ?: [];
    $filtered = array_filter($lines, static fn(string $l) => str_contains($l, '|') && str_contains($l, 'https://'));
    $input = preg_replace('/\s+/', ' ', trim(implode(' ', $filtered))) ?? '';

    $pattern = '/(.+?)\s*\|\s*(https?:\/\/\S+)\s+(\d{2}:\d{2})(?=\s+.+?\s*\|\s*https?:\/\/|\s*$)/';
    preg_match_all($pattern, $input, $matches, PREG_SET_ORDER);

    $grouped = [];
    $orderedKeys = [];

    foreach ($matches as $match) {
        $title = trim($match[1]);
        $url = trim($match[2]);
        $rawTime = trim($match[3]);
        [$h, $m] = explode(':', $rawTime);
        $time = sprintf('%02d:%02d', ((int)$h + 1) % 24, (int)$m);
        $key = lower($time . '|' . $title);

        if (!isset($grouped[$key])) {
            $sport = detectSport($title);
            $grouped[$key] = [
                'title' => $title,
                'time' => $time,
                'sport' => $sport,
                'sportIcon' => sportIcon($sport),
                'day' => '',
                'languages' => [],
                'channels' => [],
            ];
            $orderedKeys[] = $key;
        }

        $language = channelLanguage($url);
        $grouped[$key]['channels'][] = [
            'url' => $url,
            'label' => channelLabel($url),
            'language' => $language,
        ];

        if ($language !== 'Lingua non indicata' && !in_array($language, $grouped[$key]['languages'], true)) {
            $grouped[$key]['languages'][] = $language;
        }
    }

    $events = [];
    $dayIndex = currentDayIndex();
    $previousTime = null;

    foreach ($orderedKeys as $key) {
        $event = $grouped[$key];

        if ($previousTime !== null && strcmp($event['time'], $previousTime) < 0) {
            $dayIndex = ($dayIndex + 1) % 7;
        }

        sort($event['languages']);
        $event['day'] = WEEKDAYS_IT[$dayIndex];
        $events[] = $event;
        $previousTime = $event['time'];
    }

    return $events;
}

/**
 * @param array<int, array{
 *   title: string,
 *   time: string,
 *   sport: string,
 *   sportIcon: string,
 *   day: string,
 *   languages: array<int, string>,
 *   channels: array<int, array{url: string, label: string, language: string}>
 * }> $events
 * @return array<string, array<int, array{
 *   title: string,
 *   time: string,
 *   sport: string,
 *   sportIcon: string,
 *   day: string,
 *   languages: array<int, string>,
 *   channels: array<int, array{url: string, label: string, language: string}>
 * }>>
 */
function groupEventsByDay(array $events): array
{
    $grouped = [];

    foreach ($events as $event) {
        $grouped[$event['day']][] = $event;
    }

    return $grouped;
}

function detectSport(string $title): string
{
    $normalized = lower($title);

    return match (true) {
        str_contains($normalized, 'tennis'),
        str_contains($normalized, 'atp'),
        str_contains($normalized, 'wta') => 'Tennis',
        str_contains($normalized, 'motogp'),
        str_contains($normalized, 'moto gp'),
        str_contains($normalized, 'moto2'),
        str_contains($normalized, 'moto3') => 'MotoGP',
        str_contains($normalized, 'formula 1'),
        preg_match('/\bf1\b/', $normalized) === 1,
        str_contains($normalized, 'grand prix') => 'F1',
        str_contains($normalized, 'nba'),
        str_contains($normalized, 'basket') => 'Basket',
        str_contains($normalized, 'boxing'),
        str_contains($normalized, 'ufc'),
        str_contains($normalized, 'mma'),
        str_contains($normalized, 'zuffa') => 'Combattimento',
        default => 'Calcio',
    };
}

function sportIcon(string $sport): string
{
    return match ($sport) {
        'Tennis' => '🎾',
        'MotoGP' => '🏍️',
        'F1' => '🏎️',
        'Basket' => '🏀',
        'Combattimento' => '🥊',
        default => '⚽',
    };
}

function channelLabel(string $url): string
{
    $path = parse_url($url, PHP_URL_PATH);

    if (!is_string($path) || $path === '') {
        return 'Canale';
    }

    $parts = array_values(array_filter(explode('/', trim($path, '/'))));
    $tail = array_slice($parts, -2);

    if ($tail === []) {
        return basename($path);
    }

    return strtoupper(implode(' / ', array_map(
        static fn(string $part): string => str_replace('.php', '', $part),
        $tail
    )));
}

function channelLanguage(string $url): string
{
    $path = parse_url($url, PHP_URL_PATH);

    if (!is_string($path) || $path === '') {
        return 'Lingua non indicata';
    }

    $basename = lower(pathinfo($path, PATHINFO_FILENAME));
    $segments = array_values(array_filter(explode('/', trim($path, '/'))));
    $category = lower($segments[count($segments) - 2] ?? '');

    if (isset(HD_LANGUAGE_MAP[$basename])) {
        return HD_LANGUAGE_MAP[$basename];
    }

    return match ($category) {
        'pt' => 'Portoghese',
        'bra' => 'Portoghese (Brasile)',
        default => 'Lingua non indicata',
    };
}

function currentDayIndex(): int
{
    return (int) date('w');
}

function h(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES, 'UTF-8');
}

function lower(string $value): string
{
    return function_exists('mb_strtolower') ? mb_strtolower($value) : strtolower($value);
}

$data = loadSchedule();
$groupedEvents = groupEventsByDay($data['events']);
?>
<!DOCTYPE html>
<html lang="it">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Viedo Sport Bono</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><circle cx='50' cy='50' r='50' fill='%23000'/><polygon points='35,25 80,50 35,75' fill='%23fff'/></svg>">
    <style>
        :root {
            --bg: #000000;
            --card: #1b1b1b;
            --ink: #ffffff;
            --muted: #b8b8b8;
            --line: rgba(255, 255, 255, 0.16);
            --accent: #ffffff;
            --accent-strong: #ffffff;
            --pill: #111111;
            --shadow: none;
        }

        * {
            box-sizing: border-box;
        }

        body {
            margin: 0;
            font-family: Georgia, "Times New Roman", serif;
            color: var(--ink);
            background: #000000;
        }

        .shell {
            width: 100%;
            margin: 0;
            padding: 16px;
        }

        .hero {
            padding: 20px 28px;
            border: 1px solid var(--line);
            border-radius: 28px;
            background: #000000;
            box-shadow: var(--shadow);
        }

        h1 {
            margin: 0;
            font-size: clamp(2.2rem, 5vw, 4.5rem);
            line-height: 0.98;
            max-width: none;
        }

        .toolbar {
            display: grid;
            grid-template-columns: minmax(0, 1fr) auto auto;
            gap: 12px;
            margin-top: 24px;
        }

        .field,
        .button {
            border: 1px solid var(--line);
            border-radius: 16px;
            min-height: 52px;
            font: inherit;
        }

        .field {
            width: 100%;
            padding: 0 16px;
            background: #050505;
            color: var(--ink);
        }

        .button {
            padding: 0 18px;
            background: #050505;
            color: var(--ink);
            cursor: pointer;
            text-decoration: none;
            display: inline-flex;
            align-items: center;
            justify-content: center;
        }

        .button.primary {
            background: #ffffff;
            color: #000000;
            border-color: #ffffff;
        }

        .meta {
            display: flex;
            flex-wrap: wrap;
            gap: 16px;
            margin-top: 16px;
            color: var(--muted);
            font-size: 0.95rem;
        }

        .summary {
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 16px;
            margin: 24px 2px 14px;
            color: var(--muted);
        }

        .day-section {
            margin-top: 24px;
        }

        .day-title {
            margin: 0 0 14px;
            padding: 0 2px;
            font-size: clamp(1.4rem, 3vw, 2rem);
        }

        .grid {
            display: grid;
            grid-template-columns: 1fr;
            gap: 16px;
        }

        .card {
            width: 100%;
            padding: 20px;
            border: 1px solid var(--line);
            border-radius: 22px;
            background: var(--card);
            box-shadow: var(--shadow);
            cursor: pointer;
            transition: background 0.2s ease, border-color 0.2s ease;
            overflow: hidden;
        }

        .card.sport-calcio {
            background:
                linear-gradient(135deg, rgba(17, 62, 28, 0.96), rgba(7, 15, 10, 0.99)),
                radial-gradient(circle at top right, rgba(86, 176, 96, 0.18), transparent 35%);
        }

        .card.sport-basket {
            background:
                linear-gradient(135deg, rgba(106, 54, 17, 0.96), rgba(20, 10, 3, 0.99)),
                radial-gradient(circle at top right, rgba(255, 166, 78, 0.18), transparent 35%);
        }

        .card.sport-tennis {
            background:
                linear-gradient(135deg, rgba(55, 89, 12, 0.96), rgba(10, 18, 3, 0.99)),
                radial-gradient(circle at top right, rgba(193, 255, 88, 0.18), transparent 35%);
        }

        .card.sport-motogp {
            background:
                linear-gradient(135deg, rgba(74, 74, 74, 0.96), rgba(12, 12, 12, 0.99)),
                radial-gradient(circle at top right, rgba(255, 255, 255, 0.14), transparent 35%);
        }

        .card.sport-f1 {
            background:
                linear-gradient(135deg, rgba(120, 14, 14, 0.96), rgba(18, 3, 3, 0.99)),
                radial-gradient(circle at top right, rgba(255, 73, 73, 0.22), transparent 35%);
        }

        .card.sport-combattimento {
            background:
                linear-gradient(135deg, rgba(94, 18, 18, 0.96), rgba(18, 4, 4, 0.99)),
                radial-gradient(circle at top right, rgba(255, 102, 102, 0.18), transparent 35%);
        }

        .card:hover {
            border-color: rgba(255, 255, 255, 0.3);
        }

        .card.sport-calcio:hover {
            background:
                linear-gradient(135deg, rgba(24, 82, 36, 0.98), rgba(7, 15, 10, 0.99)),
                radial-gradient(circle at top right, rgba(104, 201, 116, 0.22), transparent 35%);
        }

        .card.sport-basket:hover {
            background:
                linear-gradient(135deg, rgba(127, 67, 23, 0.98), rgba(20, 10, 3, 0.99)),
                radial-gradient(circle at top right, rgba(255, 179, 99, 0.22), transparent 35%);
        }

        .card.sport-tennis:hover {
            background:
                linear-gradient(135deg, rgba(69, 109, 17, 0.98), rgba(10, 18, 3, 0.99)),
                radial-gradient(circle at top right, rgba(208, 255, 112, 0.22), transparent 35%);
        }

        .card.sport-motogp:hover {
            background:
                linear-gradient(135deg, rgba(97, 97, 97, 0.98), rgba(12, 12, 12, 0.99)),
                radial-gradient(circle at top right, rgba(255, 255, 255, 0.18), transparent 35%);
        }

        .card.sport-f1:hover {
            background:
                linear-gradient(135deg, rgba(144, 19, 19, 0.98), rgba(18, 3, 3, 0.99)),
                radial-gradient(circle at top right, rgba(255, 95, 95, 0.26), transparent 35%);
        }

        .card.sport-combattimento:hover {
            background:
                linear-gradient(135deg, rgba(112, 23, 23, 0.98), rgba(18, 4, 4, 0.99)),
                radial-gradient(circle at top right, rgba(255, 120, 120, 0.22), transparent 35%);
        }

        .card:focus-visible {
            outline: 2px solid #ffffff;
            outline-offset: 3px;
        }

        .time {
            font-size: 2rem;
            font-weight: 700;
            letter-spacing: -0.04em;
        }

        .sport {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            margin: 12px 0 10px;
            padding: 6px 10px;
            border-radius: 999px;
            background: var(--pill);
            color: var(--accent-strong);
            font-size: 0.85rem;
        }

        .sport-icon {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 1.3rem;
        }

        .title {
            margin: 0 0 14px;
            font-size: 1.18rem;
            line-height: 1.3;
        }

        .languages {
            margin: 0 0 14px;
            color: var(--muted);
            font-size: 0.95rem;
        }

        .channels {
            display: flex;
            flex-wrap: wrap;
            gap: 10px;
        }

        .channel {
            padding: 9px 12px;
            border-radius: 12px;
            background: #050505;
            border: 1px solid var(--line);
            color: var(--accent-strong);
            text-decoration: none;
            font-size: 0.92rem;
        }

        .empty,
        .error {
            padding: 20px;
            border-radius: 18px;
            margin-top: 20px;
        }

        .empty {
            background: #050505;
            border: 1px dashed var(--line);
            color: var(--muted);
        }

        .error {
            background: #140404;
            border: 1px solid rgba(255, 80, 80, 0.3);
            color: #ff9d9d;
        }

        @media (max-width: 760px) {
            .toolbar {
                grid-template-columns: 1fr;
            }

            .summary {
                flex-direction: column;
                align-items: flex-start;
            }
        }
    </style>
</head>
<body>
    <main class="shell">
        <section class="hero">
            <h1>Eventi Live</h1>
            <div class="toolbar">
                <input id="search" class="field" type="search" placeholder="Cerca squadra, torneo o sport">
                <select id="sport" class="field" aria-label="Filtra per sport">
                    <option value="">Tutti gli sport</option>
                    <option value="Calcio">Calcio</option>
                    <option value="Basket">Basket</option>
                    <option value="Tennis">Tennis</option>
                    <option value="MotoGP">MotoGP</option>
                    <option value="F1">F1</option>
                    <option value="Combattimento">Combattimento</option>
                </select>
                <a class="button primary" href="index.php">Aggiorna</a>
            </div>
            <div class="meta">
<span>Aggiornato: <?= h($data['updatedAt']) ?></span>
                <span>Eventi trovati: <strong id="count"><?= count($data['events']) ?></strong></span>
            </div>
        </section>

        <?php if ($data['error'] !== null): ?>
            <div class="error"><?= h($data['error']) ?></div>
        <?php endif; ?>

        <div class="summary">
            <div id="activeFilters"></div>
        </div>

        <section id="events">
            <?php foreach ($groupedEvents as $day => $dayEvents): ?>
                <section class="day-section" data-day-section>
                    <h2 class="day-title"><?= h($day) ?></h2>
                    <div class="grid">
                        <?php foreach ($dayEvents as $event): ?>
                            <article
                                class="card sport-<?= h(lower($event['sport'])) ?>"
                                data-title="<?= h(lower($event['title'])) ?>"
                                data-sport="<?= h($event['sport']) ?>"
                                data-url="<?= h($event['channels'][0]['url'] ?? '') ?>"
                                tabindex="0"
                                role="link"
                            >
                                <div class="time"><?= h($event['time']) ?></div>
                                <div class="sport">
                                    <span class="sport-icon"><?= h($event['sportIcon']) ?></span>
                                    <span><?= h($event['sport']) ?></span>
                                </div>
                                <h3 class="title"><?= h($event['title']) ?></h3>
                                <p class="languages">
                                    Lingue:
                                    <?= h($event['languages'] !== [] ? implode(', ', $event['languages']) : 'Lingua non indicata') ?>
                                </p>
                                <div class="channels">
                                    <?php foreach ($event['channels'] as $channel): ?>
                                        <a class="channel" href="<?= h($channel['url']) ?>" target="_blank" rel="noopener noreferrer">
                                            <svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor" style="vertical-align:middle;margin-right:4px"><circle cx="7" cy="7" r="7" opacity=".25"/><polygon points="5,3.5 11,7 5,10.5"/></svg><?= h($channel['label']) ?> • <?= h($channel['language']) ?>
                                        </a>
                                    <?php endforeach; ?>
                                </div>
                            </article>
                        <?php endforeach; ?>
                    </div>
                </section>
            <?php endforeach; ?>
        </section>

        <div id="emptyState" class="empty" hidden>Nessun evento corrisponde ai filtri selezionati.</div>
    </main>

    <script>
        const searchInput = document.getElementById('search');
        const sportSelect = document.getElementById('sport');
        const cards = Array.from(document.querySelectorAll('.card'));
        const daySections = Array.from(document.querySelectorAll('[data-day-section]'));
        const count = document.getElementById('count');
        const activeFilters = document.getElementById('activeFilters');
        const emptyState = document.getElementById('emptyState');

        function applyFilters() {
            const term = searchInput.value.trim().toLowerCase();
            const sport = sportSelect.value;
            let visible = 0;

            cards.forEach((card) => {
                const title = card.dataset.title || '';
                const cardSport = card.dataset.sport || '';
                const matchesTerm = term === '' || title.includes(term);
                const matchesSport = sport === '' || cardSport === sport;
                const show = matchesTerm && matchesSport;
                card.hidden = !show;
                if (show) {
                    visible += 1;
                }
            });

            daySections.forEach((section) => {
                const sectionCards = Array.from(section.querySelectorAll('.card'));
                const hasVisibleCards = sectionCards.some((card) => !card.hidden);
                section.hidden = !hasVisibleCards;
            });

            count.textContent = String(visible);
            emptyState.hidden = visible !== 0;

            const bits = [];
            if (term) {
                bits.push(`ricerca: "${term}"`);
            }
            if (sport) {
                bits.push(`sport: ${sport}`);
            }
            activeFilters.textContent = bits.length ? `Filtri attivi, ${bits.join(' • ')}` : '';
        }

        searchInput.addEventListener('input', applyFilters);
        sportSelect.addEventListener('change', applyFilters);

        cards.forEach((card) => {
            const openCard = () => {
                const url = card.dataset.url;
                const title = card.querySelector('.title')?.textContent?.trim() || '';
                const time = card.querySelector('.time')?.textContent?.trim() || '';
                if (url) {
                    window.open(url, '_blank', 'noopener');
                }
            };

            card.addEventListener('click', (event) => {
                if (event.target.closest('.channel')) {
                    return;
                }
                openCard();
            });

            card.addEventListener('keydown', (event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    openCard();
                }
            });
        });

        applyFilters();
    </script>
</body>
</html>
