export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const id = url.searchParams.get("id");

    if (!id && path !== "/") {
      return new Response("Missing ID", { status: 400 });
    }

    let title = "AirBeats";
    let subtitle = "Open this link in the AirBeats app to continue.";
    let thumbnail = "https://images.unsplash.com/photo-1614149162883-504ce4d13909?q=80&w=1000&auto=format&fit=crop"; 
    let intentUrl = `intent://play.airbeats.app${path}?id=${id}#Intent;scheme=https;package=com.darkxvenom.airbeats;end`;
    let extraDetailsHTML = "";

    if (path.startsWith("/song") && id) {
      subtitle = "Song";
      try {
        // Fetch detailed metadata from an Invidious API instance
        const apiUrl = `https://vid.puffyan.us/api/v1/videos/${id}`;
        const resp = await fetch(apiUrl, { headers: { 'Accept': 'application/json' } });
        if (resp.ok) {
          const data = await resp.json();
          title = data.title || title;
          subtitle = data.author || subtitle;
          
          if (data.videoThumbnails && data.videoThumbnails.length > 0) {
            // Get highest quality thumbnail
            thumbnail = data.videoThumbnails.reduce((prev, current) => {
              return (prev.width > current.width) ? prev : current;
            }).url;
          }

          // Format details
          const views = data.viewCount ? new Intl.NumberFormat('en-US').format(data.viewCount) + " views" : "";
          const date = data.publishedText ? data.publishedText : "";
          const likes = data.likeCount ? new Intl.NumberFormat('en-US').format(data.likeCount) + " likes" : "";
          
          let durationStr = "";
          if (data.lengthSeconds) {
            const m = Math.floor(data.lengthSeconds / 60);
            const s = data.lengthSeconds % 60;
            durationStr = `${m}:${s.toString().padStart(2, '0')}`;
          }

          const descSnippet = data.description ? data.description.substring(0, 150) + (data.description.length > 150 ? "..." : "") : "";

          extraDetailsHTML = `
            <div class="stats-row">
              ${views ? `<span class="stat-badge">👁 ${views}</span>` : ""}
              ${likes ? `<span class="stat-badge">♥ ${likes}</span>` : ""}
              ${durationStr ? `<span class="stat-badge">⏱ ${durationStr}</span>` : ""}
              ${date ? `<span class="stat-badge">📅 ${date}</span>` : ""}
            </div>
            ${descSnippet ? `<p class="desc-text">${descSnippet}</p>` : ""}
          `;
        } else {
            // Fallback to oEmbed if Invidious fails
            const oembedUrl = `https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${id}&format=json`;
            const fbResp = await fetch(oembedUrl);
            if (fbResp.ok) {
                const fbData = await fbResp.json();
                title = fbData.title;
                subtitle = fbData.author_name;
                thumbnail = fbData.thumbnail_url;
            }
        }
      } catch (e) {
        console.error("Failed to fetch detailed metadata", e);
      }
    } else if (path.startsWith("/artist") || path.startsWith("/channel") || path.startsWith("/playlist")) {
      const isPlaylist = path.startsWith("/playlist");
      subtitle = isPlaylist ? "Playlist" : (path.startsWith("/artist") ? "Artist" : "Channel");
      try {
        let ytmUrl = "";
        if (isPlaylist) {
            ytmUrl = `https://music.youtube.com/playlist?list=${id}`;
        } else {
            ytmUrl = id.startsWith("@") ? `https://music.youtube.com/${id}` : `https://music.youtube.com/channel/${id}`;
        }
        
        const ytHeaders = {
          'Referer': 'https://www.google.com/',
          'Upgrade-Insecure-Requests': '1',
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36',
          'sec-ch-ua': '"Not;A=Brand";v="8", "Chromium";v="150", "Google Chrome";v="150"',
          'sec-ch-ua-arch': '"x86"',
          'sec-ch-ua-bitness': '"64"',
          'sec-ch-ua-form-factors': '"Desktop"',
          'sec-ch-ua-full-version': '"150.0.7871.128"',
          'sec-ch-ua-full-version-list': '"Not;A=Brand";v="8.0.0.0", "Chromium";v="150.0.7871.128", "Google Chrome";v="150.0.7871.128"',
          'sec-ch-ua-mobile': '?0',
          'sec-ch-ua-model': '""',
          'sec-ch-ua-platform': '"Windows"',
          'sec-ch-ua-platform-version': '"19.0.0"',
          'sec-ch-ua-wow64': '?0'
        };

        const resp = await fetch(ytmUrl, { headers: ytHeaders });
        if (resp.ok) {
            const htmlText = await resp.text();
            
            const titleMatch = htmlText.match(/<meta\s+property="og:title"\s+content="([^"]+)"/i);
            if (titleMatch) title = titleMatch[1];
            
            const imgMatch = htmlText.match(/<meta\s+property="og:image"\s+content="([^"]+)"/i);
            if (imgMatch) thumbnail = imgMatch[1];

            let descSnippet = "";
            const descMatch = htmlText.match(/<meta\s+name="description"\s+content="([^"]+)"/i);
            if (descMatch) {
                descSnippet = descMatch[1].substring(0, 150) + (descMatch[1].length > 150 ? "..." : "");
            }

            let subs = "";
            const subsMatch = htmlText.match(/([0-9\.]+[a-zA-Z]+)\s+subscribers?/i);
            if (subsMatch) subs = subsMatch[1] + " subscribers";
            
            let audience = "";
            const audienceMatch = htmlText.match(/([0-9\.]+[a-zA-Z]+)\s+monthly audience/i);
            if (audienceMatch) audience = audienceMatch[1] + " monthly listeners";

            let songs = "";
            const songsMatch = htmlText.match(/([0-9\,]+)\s+songs?/i) || htmlText.match(/([0-9\,]+)\s+tracks?/i);
            if (songsMatch) songs = songsMatch[1] + " songs";

            extraDetailsHTML = `
              <div class="stats-row">
                ${subs ? `<span class="stat-badge">👥 ${subs}</span>` : ""}
                ${audience ? `<span class="stat-badge">🎧 ${audience}</span>` : ""}
                ${songs ? `<span class="stat-badge">🎵 ${songs}</span>` : ""}
              </div>
              ${descSnippet ? `<p class="desc-text">${descSnippet}</p>` : ""}
            `;
        }
      } catch (e) {
        console.error("Failed to fetch channel metadata", e);
      }
    }

    const html = `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title} | AirBeats</title>
    <meta name="description" content="Open ${title} in the AirBeats music player.">
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;600;800&display=swap" rel="stylesheet">
    <style>
        :root {
            --primary: #A259FF;
            --surface: rgba(255, 255, 255, 0.05);
            --border: rgba(255, 255, 255, 0.1);
            --text: #ffffff;
            --text-secondary: rgba(255, 255, 255, 0.7);
        }

        body, html {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            font-family: 'Outfit', sans-serif;
            background-color: #0f0f13;
            color: var(--text);
            display: flex;
            justify-content: center;
            align-items: center;
            overflow: hidden;
        }

        .background-blur {
            position: absolute;
            top: -10%;
            left: -10%;
            width: 120%;
            height: 120%;
            background-image: url('${thumbnail}');
            background-size: cover;
            background-position: center;
            filter: blur(80px) brightness(0.4) saturate(1.5);
            z-index: 0;
            animation: pulseBg 10s infinite alternate;
        }

        @keyframes pulseBg {
            0% { transform: scale(1); }
            100% { transform: scale(1.05); }
        }

        .glass-card {
            position: relative;
            z-index: 1;
            width: 90%;
            max-width: 420px;
            background: var(--surface);
            backdrop-filter: blur(24px);
            -webkit-backdrop-filter: blur(24px);
            border: 1px solid var(--border);
            border-radius: 32px;
            padding: 32px;
            text-align: center;
            box-shadow: 0 32px 64px rgba(0, 0, 0, 0.4), inset 0 1px 1px rgba(255,255,255,0.1);
            animation: slideUp 0.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
            transform: translateY(40px);
            opacity: 0;
        }

        @keyframes slideUp {
            to {
                transform: translateY(0);
                opacity: 1;
            }
        }

        .artwork {
            width: 180px;
            height: 180px;
            border-radius: 24px;
            object-fit: cover;
            margin: 0 auto 24px auto;
            box-shadow: 0 16px 32px rgba(0,0,0,0.5);
            border: 1px solid rgba(255,255,255,0.05);
            transition: transform 0.3s ease;
        }

        .glass-card:hover .artwork {
            transform: scale(1.05) translateY(-5px);
        }

        h1 {
            font-size: 22px;
            font-weight: 800;
            margin: 0 0 4px 0;
            line-height: 1.2;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .subtitle {
            font-size: 16px;
            font-weight: 600;
            color: var(--primary);
            margin: 0 0 24px 0;
        }

        .stats-row {
            display: flex;
            flex-wrap: wrap;
            justify-content: center;
            gap: 8px;
            margin-bottom: 16px;
        }

        .stat-badge {
            background: rgba(0,0,0,0.3);
            border: 1px solid rgba(255,255,255,0.1);
            border-radius: 8px;
            padding: 6px 10px;
            font-size: 12px;
            color: var(--text-secondary);
        }
        
        .desc-text {
            font-size: 13px;
            color: rgba(255,255,255,0.5);
            margin-bottom: 24px;
            line-height: 1.4;
            display: -webkit-box;
            -webkit-line-clamp: 3;
            -webkit-box-orient: vertical;
            overflow: hidden;
        }

        .btn {
            display: inline-block;
            background: linear-gradient(135deg, #A259FF 0%, #7A33FF 100%);
            color: white;
            text-decoration: none;
            font-size: 16px;
            font-weight: 600;
            padding: 14px 36px;
            border-radius: 100px;
            box-shadow: 0 8px 24px rgba(162, 89, 255, 0.4);
            transition: all 0.3s ease;
            position: relative;
            overflow: hidden;
        }

        .btn::after {
            content: '';
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: linear-gradient(rgba(255,255,255,0.2), transparent);
            opacity: 0;
            transition: opacity 0.3s ease;
        }

        .btn:hover {
            transform: translateY(-2px) scale(1.02);
            box-shadow: 0 12px 32px rgba(162, 89, 255, 0.6);
        }

        .btn:hover::after {
            opacity: 1;
        }
        
        .brand {
            margin-top: 24px;
            font-size: 12px;
            font-weight: 600;
            color: rgba(255,255,255,0.2);
            letter-spacing: 2px;
            text-transform: uppercase;
        }
    </style>
</head>
<body>
    <div class="background-blur"></div>
    <div class="glass-card">
        <img src="${thumbnail}" alt="Artwork" class="artwork">
        <h1>${title}</h1>
        <div class="subtitle">${subtitle}</div>
        
        ${extraDetailsHTML}

        <a href="${intentUrl}" class="btn">Open in AirBeats</a>
        <div class="brand">AirBeats Player</div>
    </div>
</body>
</html>
    `;

    return new Response(html, {
      headers: { "Content-Type": "text/html;charset=UTF-8" }
    });
  }
};
