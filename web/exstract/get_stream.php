<?php
declare(strict_types=1);
header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');

$source = trim($_GET['source'] ?? '');

if ($source === '' || !filter_var($source, FILTER_VALIDATE_URL)) {
    echo json_encode(['error' => 'Invalid URL']);
    exit;
}

$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function curlGet(string $url, array $headers = []): string {
    global $ua;
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
        CURLOPT_HTTPHEADER     => $headers,
    ]);
    $body   = curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    unset($ch);
    return (is_string($body) && $status < 400) ? $body : '';
}

// Step 1: source page → iframe embed URL
$html1    = curlGet($source, ["Referer: $source"]);
preg_match('/<iframe[^>]+src=["\']?(https?:\/\/[^"\'>\s]+)/i', $html1, $im);
$embedUrl = $im[1] ?? '';

if ($embedUrl === '') {
    echo json_encode(['error' => 'Embed not found']);
    exit;
}

// Step 2: embed page → m3u8 URL
$html2 = curlGet($embedUrl, ["Referer: $source"]);
preg_match(
    '/(?:["\']?(?:file|src|source)["\']?\s*[:=]\s*|var\s+src\s*=\s*)["\']?(https?:\/\/[^"\'>\s]+\.m3u8[^"\'>\s]*)/i',
    $html2, $hls
);
$m3u8 = $hls[1] ?? '';

if ($m3u8 === '') {
    echo json_encode(['error' => 'Stream not found']);
    exit;
}

echo json_encode(['m3u8' => $m3u8, 'referer' => $embedUrl]);
