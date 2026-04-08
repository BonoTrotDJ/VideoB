<?php
declare(strict_types=1);

$url = trim($_GET['url'] ?? '');

$isValidUrl = filter_var($url, FILTER_VALIDATE_URL) !== false
    && preg_match('/^https?:\/\//i', $url) === 1;
?>
<!DOCTYPE html>
<html lang="it">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Video</title>
    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest/dist/hls.min.js"></script>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
        video { width: 100%; height: 100%; display: block; background: #000; }
        #msg { position: fixed; top: 50%; left: 50%; transform: translate(-50%,-50%); color: #888; font: 1rem sans-serif; }
    </style>
</head>
<body>
<?php if ($isValidUrl): ?>
    <video id="player" controls autoplay playsinline></video>
    <div id="msg">Caricamento...</div>
    <script>
        (async () => {
            const source = <?= json_encode($url) ?>;
            const msg    = document.getElementById('msg');
            const video  = document.getElementById('player');

            // Ask PHP to resolve source → embed → m3u8 URL (only ports 80/443, no 8443)
            let data;
            try {
                const res = await fetch('get_stream.php?source=' + encodeURIComponent(source));
                data = await res.json();
            } catch (e) {
                msg.textContent = 'Errore di rete.';
                return;
            }

            if (data.error || !data.m3u8) {
                msg.textContent = 'Stream non trovato.';
                return;
            }

            msg.remove();

            // Load m3u8 directly from browser (browser CAN reach port 8443)
            if (Hls.isSupported()) {
                const hls = new Hls();
                hls.loadSource(data.m3u8);
                hls.attachMedia(video);
                hls.on(Hls.Events.ERROR, (e, d) => {
                    if (d.fatal) {
                        document.body.innerHTML = '<div id="msg">Errore: ' + d.details + '</div>';
                    }
                });
            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                video.src = data.m3u8;
            }
        })();
    </script>
<?php endif; ?>
</body>
</html>
