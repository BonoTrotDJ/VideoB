<?php
declare(strict_types=1);

// ── Helpers ──────────────────────────────────────────────────────────────────

function httpGet(string $url, string $referer = ''): string
{
    $ref    = $referer !== '' ? $referer : $url;
    $scheme = parse_url($ref, PHP_URL_SCHEME) ?? 'https';
    $host   = parse_url($ref, PHP_URL_HOST)   ?? '';
    $origin = "$scheme://$host";
    $ua     = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

    // ── cURL (preferred) ─────────────────────────────────────────────────────
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 5,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_TIMEOUT        => 15,
            CURLOPT_ENCODING       => '',
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => 0,
            CURLOPT_USERAGENT      => $ua,
            CURLOPT_HTTPHEADER     => [
                'Accept: */*',
                "Origin: $origin",
                "Referer: $ref",
            ],
        ]);
        $body   = curl_exec($ch);
        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        unset($ch);
        if (is_string($body) && $body !== '' && $status < 400) {
            return $body;
        }
    }

    // ── file_get_contents fallback ────────────────────────────────────────────
    $ctx = stream_context_create([
        'http' => [
            'timeout'         => 15,
            'user_agent'      => $ua,
            'header'          => ['Accept: */*', "Origin: $origin", "Referer: $ref"],
            'follow_location' => 1,
            'max_redirects'   => 5,
            'ignore_errors'   => true,
        ],
        'ssl'  => ['verify_peer' => false, 'verify_peer_name' => false],
    ]);

    $body = @file_get_contents($url, false, $ctx);
    return is_string($body) ? $body : '';
}

/**
 * Extract the first m3u8/mp4 URL from an HTML page.
 * Returns ['url' => string, 'type' => 'hls'|'mp4'] or null.
 */
function extractStream(string $html): ?array
{
    if (preg_match(
        '/(?:["\']?(?:file|src|source|hlsUrl|streamUrl)["\']?\s*[:=]\s*|var\s+src\s*=\s*)["\']?(https?:\/\/[^"\'>\s]+\.m3u8[^"\'>\s]*)/i',
        $html, $m
    )) {
        return ['url' => $m[1], 'type' => 'hls'];
    }
    if (preg_match(
        '/(?:["\']?(?:file|src|source)["\']?\s*[:=]\s*)["\']?(https?:\/\/[^"\'>\s]+\.mp4[^"\'>\s]*)/i',
        $html, $m
    )) {
        return ['url' => $m[1], 'type' => 'mp4'];
    }
    return null;
}

function rewriteM3u8(string $content, string $baseUrl, string $embedUrl): string
{
    $proxyBase = 'proxy.php?referer=' . urlencode($embedUrl) . '&url=';
    $lines     = explode("\n", $content);

    foreach ($lines as &$line) {
        $trimmed = trim($line);
        if ($trimmed === '') continue;

        if (str_starts_with($trimmed, '#')) {
            $line = preg_replace_callback('/URI="([^"]+)"/', static function (array $m) use ($baseUrl, $proxyBase): string {
                $abs = str_starts_with($m[1], 'http') ? $m[1] : $baseUrl . $m[1];
                return 'URI="' . $proxyBase . urlencode($abs) . '"';
            }, $line) ?? $line;
            continue;
        }

        $abs  = str_starts_with($trimmed, 'http') ? $trimmed : $baseUrl . $trimmed;
        $line = $proxyBase . urlencode($abs);
    }
    unset($line);

    return implode("\n", $lines);
}

// ── Route ────────────────────────────────────────────────────────────────────

$source  = trim($_GET['source']  ?? '');   // resolve full chain fresh
$url     = trim($_GET['url']     ?? '');   // proxy a known URL
$referer = trim($_GET['referer'] ?? '');

header('Access-Control-Allow-Origin: *');
header('Cache-Control: no-store');

// ── MODE A: ?source=<page_url>  — resolve chain live and serve m3u8 ──────────
if ($source !== '') {
    if (!filter_var($source, FILTER_VALIDATE_URL) || !preg_match('/^https?:\/\//i', $source)) {
        http_response_code(400); exit('Invalid source URL');
    }

    // Level 1: source page → find iframe
    $html1 = httpGet($source, $source);
    if ($html1 === '') { http_response_code(502); exit('Cannot fetch source page'); }

    preg_match('/<iframe[^>]+src=["\']?(https?:\/\/[^"\'>\s]+)/i', $html1, $im);
    $embedUrl = $im[1] ?? '';

    // Check if stream is directly in level 1
    $stream = extractStream($html1);

    // Level 2: fetch embed page
    if ($stream === null && $embedUrl !== '') {
        $html2  = httpGet($embedUrl, $source);
        $stream = extractStream($html2);
    }

    if ($stream === null) { http_response_code(502); exit('No stream found'); }

    if ($stream['type'] === 'mp4') {
        // Redirect to proxy for the mp4
        header('Location: proxy.php?referer=' . urlencode($embedUrl) . '&url=' . urlencode($stream['url']));
        exit;
    }

    // Fetch the m3u8 fresh and serve it with rewritten segment URLs
    $m3u8Url  = $stream['url'];
    $m3u8Body = httpGet($m3u8Url, $embedUrl);

    if ($m3u8Body === '' || str_contains($m3u8Body, '<html')) {
        http_response_code(502); exit('Cannot fetch m3u8');
    }

    $baseUrl  = preg_replace('/\/[^\/]*$/', '/', $m3u8Url) ?? '';
    $rewritten = rewriteM3u8($m3u8Body, $baseUrl, $embedUrl);

    header('Content-Type: application/vnd.apple.mpegurl');
    echo $rewritten;
    exit;
}

// ── MODE B: ?url=<resource_url>&referer=<referer>  — proxy a segment/key ─────
if ($url !== '') {
    if (!filter_var($url, FILTER_VALIDATE_URL) || !preg_match('/^https?:\/\//i', $url)) {
        http_response_code(400); exit('Invalid URL');
    }

    $body = httpGet($url, $referer);
    if ($body === '') { http_response_code(502); exit('Upstream fetch failed'); }

    $ext = strtolower(explode('?', pathinfo((string) parse_url($url, PHP_URL_PATH), PATHINFO_EXTENSION))[0]);
    $contentType = match ($ext) {
        'm3u8'        => 'application/vnd.apple.mpegurl',
        'ts'          => 'video/mp2t',
        'aac'         => 'audio/aac',
        'mp4','fmp4'  => 'video/mp4',
        default       => 'application/octet-stream',
    };

    // If it's a sub-playlist (m3u8), rewrite its segments too
    if ($ext === 'm3u8' && !str_contains($body, '<html')) {
        $baseUrl = preg_replace('/\/[^\/]*$/', '/', $url) ?? '';
        $body    = rewriteM3u8($body, $baseUrl, $referer);
    }

    header("Content-Type: $contentType");
    echo $body;
    exit;
}

http_response_code(400);
exit('Missing parameters');
