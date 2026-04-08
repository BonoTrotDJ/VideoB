<?php
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function curlGet(string $url, array $headers = []): array {
    global $ua;
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_TIMEOUT        => 10,
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
        CURLOPT_USERAGENT      => $ua,
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_ENCODING       => '',
    ]);
    $body   = curl_exec($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $err    = curl_error($ch);
    unset($ch);
    return ['body' => (string)$body, 'status' => $status, 'err' => $err];
}

// Step 1: fetch source page
$source = 'https://w2.sportzsonline.click/channels/hd/hd8.php';
$r1 = curlGet($source, ["Referer: $source"]);
echo "Step 1 - Source page: HTTP {$r1['status']}, len=" . strlen($r1['body']);
echo $r1['err'] ? " ERR: {$r1['err']}" : ' OK';
echo '<br>';

// Step 2: find iframe
preg_match('/<iframe[^>]+src=["\']?(https?:\/\/[^"\'>\s]+)/i', $r1['body'], $im);
$embedUrl = $im[1] ?? '';
echo "Step 2 - Embed URL: " . ($embedUrl ?: 'NOT FOUND') . '<br>';

// Step 3: fetch embed page
if ($embedUrl) {
    $r2 = curlGet($embedUrl, ["Referer: $source"]);
    echo "Step 3 - Embed page: HTTP {$r2['status']}, len=" . strlen($r2['body']);
    echo $r2['err'] ? " ERR: {$r2['err']}" : ' OK';
    echo '<br>';

    // Step 4: find m3u8
    preg_match('/(?:var\s+src|["\']src["\'])\s*=\s*["\']?(https?:\/\/[^"\'>\s]+\.m3u8[^"\'>\s]*)/i', $r2['body'], $hls);
    $m3u8 = $hls[1] ?? '';
    echo "Step 4 - M3U8 URL: " . ($m3u8 ?: 'NOT FOUND') . '<br>';

    // Step 5: fetch m3u8
    if ($m3u8) {
        $origin = parse_url($embedUrl, PHP_URL_SCHEME).'://'.parse_url($embedUrl, PHP_URL_HOST);
        $r3 = curlGet($m3u8, ["Referer: $embedUrl", "Origin: $origin"]);
        echo "Step 5 - M3U8 fetch: HTTP {$r3['status']}, len=" . strlen($r3['body']);
        echo $r3['err'] ? " ERR: {$r3['err']}" : ' OK';
        echo '<br>';
        echo '<pre>' . htmlspecialchars(substr($r3['body'], 0, 200)) . '</pre>';
    }
}
