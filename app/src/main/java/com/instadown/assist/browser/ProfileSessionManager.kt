package com.instadown.assist.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import com.instadown.assist.util.AppLogger

/**
 * Manages sandboxed WebView configuration, privacy hygiene, and native styling injection.
 * Uses the device's genuine mobile browser User-Agent for authenticated flows and profiles
 * so Instagram sessions remain 100% authentic and free of device-switching anomalies.
 */
object ProfileSessionManager {

    private const val TAG = "ProfileSessionManager"

    // Fallback Android Mobile Chrome UA only if WebSettings.getDefaultUserAgent fails
    private const val FALLBACK_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    // Desktop Chrome User-Agent ONLY for anonymous/logged-out post/reel preview routes
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @Volatile
    private var cachedAuthenticMobileUa: String? = null

    fun resolveAuthenticMobileUa(context: Context?): String {
        cachedAuthenticMobileUa?.let { return it }
        val resolved = try {
            if (context != null) {
                WebSettings.getDefaultUserAgent(context)
                    .replace("; wv)", ")")
                    .replace("; wv;", ";")
                    .ifBlank { FALLBACK_MOBILE_UA }
            } else {
                FALLBACK_MOBILE_UA
            }
        } catch (_: Throwable) {
            FALLBACK_MOBILE_UA
        }
        cachedAuthenticMobileUa = resolved
        return resolved
    }

    /**
     * Checks CookieManager locally in memory to detect an active Instagram login session.
     * NEVER copies, persists, logs, or forwards cookie values outside Android's WebView sandbox.
     */
    fun isInstagramLoggedIn(): Boolean {
        return try {
            val cookies = CookieManager.getInstance()
                ?.getCookie("https://www.instagram.com")
                ?: return false
            val match = Regex("""(?:^|;\s*)sessionid=([^;]+)""").find(cookies)
            val valStr = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            valStr.isNotEmpty() && valStr != "\"\"" && valStr != "deleted"
        } catch (_: Throwable) {
            false
        }
    }

    private val LOGGED_OUT_STYLING_CSS = """
        /* Hide intrusive 'Use the app' / 'Get app' sticky banners, web top bars, login modals and signup footers when logged out */
        [role="banner"],
        div[data-testid="mobile-nav-bar"],
        footer,
        header._ab15,
        div._acum,
        div._acun,
        div._acus,
        div.x1rxbfg9,
        div[role="dialog"],
        div[aria-modal="true"],
        div:has(> a[href*="itunes.apple.com"]),
        div:has(> a[href*="play.google.com"]) {
            display: none !important;
        }

        html, body, main, #mount_0_0_ {
            background-color: #0B0813 !important;
            color: #FFFFFF !important;
        }

        body *:not(input):not(textarea):not([contenteditable="true"]) {
            -webkit-touch-callout: none !important;
            -webkit-user-select: none !important;
        }

        ::-webkit-scrollbar {
            display: none !important;
        }
    """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

    private val LOGGED_IN_STYLING_CSS = """
        /* Gentle native polish when logged in: keep native modals, dialogs, comments, and infinite scroll intact */
        div:has(> a[href*="itunes.apple.com"]),
        div:has(> a[href*="play.google.com"]) {
            display: none !important;
        }

        html, body, #mount_0_0_ {
            background-color: #0B0813 !important;
        }

        body *:not(input):not(textarea):not([contenteditable="true"]) {
            -webkit-touch-callout: none !important;
        }

        ::-webkit-scrollbar {
            display: none !important;
        }
    """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

    fun optimalUserAgentFor(
        url: String?,
        context: Context? = null,
        loggedIn: Boolean = isInstagramLoggedIn()
    ): String {
        val mobileUa = resolveAuthenticMobileUa(context)
        // Strict authenticity rule: NEVER swap fingerprints when logged in or inside login/2FA/challenge flows.
        if (loggedIn || InstagramRouteParser.isAuthOrSafetyUrl(url)) {
            return mobileUa
        }
        return when (InstagramRouteParser.parse(url)) {
            is DetectedRoute.MediaPost -> DESKTOP_UA
            else -> mobileUa
        }
    }

    fun applyOptimalUserAgent(webView: WebView, url: String?): Boolean {
        val desired = optimalUserAgentFor(url, webView.context)
        if (webView.settings.userAgentString != desired) {
            webView.settings.userAgentString = desired
            return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureSafeWebView(
        webView: WebView,
        client: ProfileBrowserClient
    ) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = resolveAuthenticMobileUa(webView.context)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // Prevent white flashes by forcing dark background
        webView.setBackgroundColor(Color.parseColor("#0B0813"))

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = client
    }

    /**
     * Injects CSS and a MutationObserver JavaScript to strip web chrome,
     * annihilate "See full profile in the app" / "Get the full experience" popup modals and dim scrims,
     * and render a clean native-style grid below the creator bio when logged out.
     */
    fun injectNativeStyling(webView: WebView) {
        val isLoggedIn = isInstagramLoggedIn()
        val cssToInject = if (isLoggedIn) LOGGED_IN_STYLING_CSS else LOGGED_OUT_STYLING_CSS
        val js = """
            (function() {
                try {
                    const isLoggedIn = $isLoggedIn;
                    const currentPath = window.location.pathname || '/';
                    const isAuthRoute = currentPath.indexOf('/accounts/') !== -1 ||
                        currentPath.indexOf('/challenge/') !== -1 ||
                        currentPath.indexOf('/two_factor') !== -1 ||
                        currentPath.indexOf('/consent/') !== -1 ||
                        currentPath.indexOf('/oauth/') !== -1;

                    if (isAuthRoute) {
                        const prevStyle = document.getElementById('instasave-native-style');
                        if (prevStyle) prevStyle.remove();
                        const prevGrid = document.getElementById('instasave-injected-grid');
                        if (prevGrid) prevGrid.remove();
                        return;
                    }

                    // 1. Inject Stylesheet (update if logged-in mode changed)
                    let style = document.getElementById('instasave-native-style');
                    const desiredMode = isLoggedIn ? 'in' : 'out';
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'instasave-native-style';
                        style.type = 'text/css';
                        style.setAttribute('data-session-mode', desiredMode);
                        style.innerHTML = "$cssToInject";
                        (document.head || document.documentElement).appendChild(style);
                    } else if (style.getAttribute('data-session-mode') !== desiredMode) {
                        style.setAttribute('data-session-mode', desiredMode);
                        style.innerHTML = "$cssToInject";
                    }

                    // Report post creator handle back to native toolbar/pill when viewing any post/reel
                    function extractMediaFromHref(href) {
                        if (!href) return null;
                        const cleanPath = href.replace(/^https?:\/\/[^/]+/i, '').split('?')[0];
                        const m = cleanPath.match(/^\/(?:([A-Za-z0-9._]+)\/)?(p|reel|reels\/videos|reels|tv)\/([A-Za-z0-9_-]+)(?:\/|$)/i);
                        if (!m) return null;
                        const code = m[3];
                        if (!code || ['audio','videos','effects','camera'].indexOf(code.toLowerCase()) !== -1) return null;
                        const kind = m[2].toLowerCase();
                        const isReel = kind.indexOf('reel') === 0;
                        const prefixUser = (m[1] || '').toLowerCase();
                        const reserved = ['p','reel','reels','tv','share','explore','accounts','direct','stories','about','legal','developer','terms','privacy','static','api'];
                        const author = (prefixUser && reserved.indexOf(prefixUser) === -1) ? prefixUser : '';
                        return {
                            url: 'https://www.instagram.com/' + (isReel ? 'reel' : 'p') + '/' + code + '/',
                            shortcode: code,
                            isReel: isReel,
                            author: author
                        };
                    }

                    function extractAuthorFromContainer(rootEl) {
                        if (!rootEl) return '';
                        const reserved = ['p','reel','reels','tv','share','explore','accounts','direct','stories','about','legal','developer','terms','privacy','static','api'];
                        const links = rootEl.querySelectorAll('header a[href^="/"], a[href^="/"]');
                        for (let i = 0; i < links.length; i++) {
                            const href = links[i].getAttribute('href') || '';
                            const seg = href.split('?')[0].split('/').filter(Boolean);
                            if (seg.length === 1) {
                                const candidate = seg[0].toLowerCase();
                                if (reserved.indexOf(candidate) === -1 && /^[a-z0-9._]{1,30}$/.test(candidate)) {
                                    return candidate;
                                }
                            }
                        }
                        return '';
                    }

                    function findMediaMetaInFiber(el) {
                        try {
                            if (!el) return null;
                            const visited = new Set();
                            const nodes = [el].concat(Array.from(el.querySelectorAll('a, time, video, img')).slice(0, 12));
                            for (let ni = 0; ni < nodes.length; ni++) {
                                const node = nodes[ni];
                                const key = Object.keys(node).find(k => k.startsWith('__reactFiber$') || k.startsWith('__reactProps$'));
                                if (!key) continue;
                                let curr = node[key];
                                for (let step = 0; step < 12 && curr; step++) {
                                    const props = curr.memoizedProps || curr;
                                    if (props && typeof props === 'object' && !visited.has(props)) {
                                        visited.add(props);
                                        const media = props.media || props.post || props.item || (props.code && props.pk ? props : null);
                                        if (media && typeof media === 'object' && typeof media.code === 'string' && media.code.length >= 5) {
                                            const isReel = media.media_type === 2 || media.product_type === 'clips' || !!media.video_versions;
                                            const u = media.user && media.user.username ? media.user.username : '';
                                            return {
                                                url: 'https://www.instagram.com/' + (isReel ? 'reel' : 'p') + '/' + media.code + '/',
                                                shortcode: media.code,
                                                isReel: isReel,
                                                author: u
                                            };
                                        }
                                    }
                                    curr = curr.return || null;
                                }
                            }
                        } catch(e) {}
                        return null;
                    }

                    function reportPostAuthor() {
                        try {
                            if (!window.InstaSaveBridge || !window.InstaSaveBridge.onPostAuthorDetected) return;
                            const livePath = window.location.pathname || '/';
                            const pathMedia = extractMediaFromHref(livePath);
                            if (!pathMedia) return;

                            let foundAuthor = pathMedia.author || '';
                            const reserved = ['p','reel','reels','tv','share','explore','accounts','direct','stories','about','legal','developer','terms','privacy','static','api'];
                            if (!foundAuthor && pathMedia.isReel) {
                                // Scope strictly to the currently centered active <video> slide so hidden feed tabs don't leak prior authors
                                const vpH = window.innerHeight || 800;
                                const vids = Array.from(document.querySelectorAll('video'));
                                for (let vi = 0; vi < vids.length && !foundAuthor; vi++) {
                                    const r = vids[vi].getBoundingClientRect();
                                    if (r.height > vpH * 0.35 && r.top < vpH * 0.65 && r.bottom > vpH * 0.35) {
                                        let box = vids[vi].parentElement;
                                        for (let d = 0; d < 7 && box && !foundAuthor; d++) {
                                            foundAuthor = extractAuthorFromContainer(box);
                                            box = box.parentElement;
                                        }
                                    }
                                }
                            }
                            if (!foundAuthor) {
                                foundAuthor = extractAuthorFromContainer(document.querySelector('article')) || extractAuthorFromContainer(document.querySelector('main'));
                            }
                            if (!foundAuthor) {
                                const scripts = document.querySelectorAll('script');
                                for (let i = 0; i < scripts.length; i++) {
                                    const txt = scripts[i].textContent || '';
                                    if (txt.indexOf('xig_polaris_media') !== -1 || txt.indexOf('"owner"') !== -1) {
                                        const m = txt.match(/"username"\s*:\s*"([A-Za-z0-9._]{1,30})"/);
                                        if (m && reserved.indexOf(m[1].toLowerCase()) === -1) {
                                            foundAuthor = m[1];
                                            break;
                                        }
                                    }
                                }
                            }
                            if (foundAuthor && window.__instasave_last_author !== livePath + ':' + foundAuthor) {
                                window.__instasave_last_author = livePath + ':' + foundAuthor;
                                window.InstaSaveBridge.onPostAuthorDetected(foundAuthor);
                            }

                            const mainEl = document.querySelector('main') || document.querySelector('article');
                            if (mainEl) {
                                mainEl.style.setProperty('padding-bottom', '145px', 'important');
                            }
                        } catch(e) {}
                    }

                    function reportBottomNavState() {
                        try {
                            if (!window.InstaSaveBridge || !window.InstaSaveBridge.onNavBarStateChanged) return;
                            let navHeight = 0;
                            const candidates = document.querySelectorAll('nav, div[role="tablist"], div');
                            for (let i = 0; i < candidates.length; i++) {
                                const el = candidates[i];
                                const rect = el.getBoundingClientRect();
                                if (rect.height >= 42 && rect.height <= 90 && rect.width >= window.innerWidth * 0.85 &&
                                    rect.bottom >= window.innerHeight - 16 && rect.top >= window.innerHeight - 110) {
                                    const hasNavLinks = el.querySelector('a[href="/"], a[href="/explore/"], a[href="/reels/"]');
                                    if (hasNavLinks) {
                                        navHeight = Math.max(navHeight, Math.round(rect.height));
                                    }
                                }
                            }
                            const key = (navHeight > 0 ? '1:' : '0:') + navHeight;
                            if (window.__instasave_last_nav_state !== key) {
                                window.__instasave_last_nav_state = key;
                                window.InstaSaveBridge.onNavBarStateChanged(navHeight > 0, navHeight);
                            }
                        } catch(e) {}
                    }

                    function scanVisibleFeedMedia() {
                        try {
                            if (!window.InstaSaveBridge || !window.InstaSaveBridge.onFocusedMediaDetected) return;
                            const path = window.location.pathname || '/';
                            if (extractMediaFromHref(path)) return;
                            if (path.indexOf('/accounts/') !== -1 || path.indexOf('/direct/') !== -1) {
                                window.InstaSaveBridge.onFocusedMediaDetected('', '', '', false);
                                return;
                            }

                            const vpH = window.innerHeight || 800;
                            const focusY = vpH * 0.42;
                            let bestInfo = null;

                            // 1. Check active feed <article> cards (Home Feed / Profile Feed)
                            const articles = Array.from(document.querySelectorAll('article'));
                            let bestArticle = null;
                            let bestScore = -1;
                            for (let i = 0; i < articles.length; i++) {
                                const r = articles[i].getBoundingClientRect();
                                const visibleH = Math.min(r.bottom, vpH - 56) - Math.max(r.top, 0);
                                if (visibleH < 120) continue;
                                const dist = Math.abs((r.top + r.bottom) / 2 - focusY);
                                const score = visibleH - dist * 0.45;
                                if (score > bestScore) {
                                    bestScore = score;
                                    bestArticle = articles[i];
                                }
                            }

                            if (bestArticle) {
                                const links = bestArticle.querySelectorAll('a[href]');
                                for (let i = 0; i < links.length; i++) {
                                    const parsed = extractMediaFromHref(links[i].getAttribute('href') || '');
                                    if (parsed) {
                                        bestInfo = parsed;
                                        break;
                                    }
                                }
                                if (!bestInfo) {
                                    bestInfo = findMediaMetaInFiber(bestArticle);
                                }
                                if (bestInfo && !bestInfo.author) {
                                    bestInfo.author = extractAuthorFromContainer(bestArticle);
                                }
                            }

                            // 2. Check active fullscreen Reel on /reels/ when <article> isn't used
                            if (!bestInfo && path.indexOf('/reels') === 0) {
                                const vids = Array.from(document.querySelectorAll('video'));
                                for (let i = 0; i < vids.length; i++) {
                                    const r = vids[i].getBoundingClientRect();
                                    if (r.height > vpH * 0.35 && r.top < vpH * 0.65 && r.bottom > vpH * 0.35) {
                                        let box = vids[i].parentElement;
                                        for (let depth = 0; depth < 8 && box && !bestInfo; depth++) {
                                            const links = box.querySelectorAll('a[href]');
                                            for (let li = 0; li < links.length; li++) {
                                                const parsed = extractMediaFromHref(links[li].getAttribute('href') || '');
                                                if (parsed) { bestInfo = parsed; break; }
                                            }
                                            if (!bestInfo) bestInfo = findMediaMetaInFiber(box);
                                            if (bestInfo && !bestInfo.author) {
                                                bestInfo.author = extractAuthorFromContainer(box);
                                            }
                                            box = box.parentElement;
                                        }
                                        if (bestInfo) break;
                                    }
                                }
                            }

                            const sig = bestInfo ? (bestInfo.shortcode + ':' + (bestInfo.author || '')) : 'NONE';
                            if (window.__instasave_last_focused !== sig) {
                                window.__instasave_last_focused = sig;
                                if (bestInfo) {
                                    window.InstaSaveBridge.onFocusedMediaDetected(bestInfo.url, bestInfo.shortcode, bestInfo.author || '', !!bestInfo.isReel);
                                } else {
                                    window.InstaSaveBridge.onFocusedMediaDetected('', '', '', false);
                                }
                            }
                        } catch(e) {}
                    }

                    // Hook SPA history transitions (pushState, replaceState, popstate) once for instant route capture
                    if (!window.__instasave_history_hooked) {
                        window.__instasave_history_hooked = true;
                        const notifyRoute = function() {
                            try {
                                if (window.InstaSaveBridge && window.InstaSaveBridge.onRouteDetected) {
                                    window.InstaSaveBridge.onRouteDetected(window.location.href);
                                }
                                setTimeout(function() {
                                    reportBottomNavState();
                                    reportPostAuthor();
                                    scanVisibleFeedMedia();
                                }, 180);
                            } catch(e) {}
                        };
                        const origPush = history.pushState;
                        const origReplace = history.replaceState;
                        history.pushState = function() {
                            const ret = origPush.apply(this, arguments);
                            notifyRoute();
                            return ret;
                        };
                        history.replaceState = function() {
                            const ret = origReplace.apply(this, arguments);
                            notifyRoute();
                            return ret;
                        };
                        window.addEventListener('popstate', notifyRoute);
                        window.addEventListener('scroll', function() {
                            clearTimeout(window.__instasave_scroll_timer);
                            window.__instasave_scroll_timer = setTimeout(function() {
                                reportBottomNavState();
                                scanVisibleFeedMedia();
                            }, 180);
                        }, { passive: true });
                    }

                    // 2. Dismiss Instagram App Promotion Modals, Backdrop Scrims & Signup Bars
                    function cleanupModals() {
                        // Neutralize native-app / Play Store deep links so clicks inside profile/posts stay safely inside WebView
                        document.querySelectorAll('a[href^="intent:"], a[href^="instagram:"], a[href*="play.google.com"]').forEach(function(a) {
                            a.setAttribute('data-blocked-href', a.getAttribute('href') || '');
                            a.removeAttribute('href');
                            a.style.pointerEvents = 'none';
                        });

                        if (isLoggedIn) {
                            // Hide only Instagram's sticky "Use the app" promo banner at the bottom; keep native modals/nav untouched
                            document.querySelectorAll('div, section').forEach(function(el) {
                                if (el.closest && (el.closest('nav') || el.closest('article') || el.closest('[role="dialog"]'))) return;
                                if (el.querySelector && el.querySelector('article, nav, video')) return;
                                const r = el.getBoundingClientRect();
                                if (r.height < 28 || r.height > 84 || r.top < window.innerHeight - 170) return;
                                const t = (el.innerText || '').trim().toLowerCase();
                                if (t === 'use the app' || t.indexOf('use the app') === 0 || t === 'open app' || t === 'open instagram') {
                                    el.style.setProperty('display', 'none', 'important');
                                }
                            });
                            return;
                        }

                        // Remove 70% dark backdrop scrims & their fixed overlay wrappers (never touch carousel media wrappers inside article!)
                        document.querySelectorAll('div.x1ey2m1c').forEach(function(scrim) {
                            if (scrim.closest && scrim.closest('article')) return;
                            if (scrim.querySelector && scrim.querySelector('img, video, ul')) return;
                            const wrapper = scrim.parentElement;
                            const scrimStyle = getComputedStyle(scrim);
                            const wrapperStyle = wrapper ? getComputedStyle(wrapper) : null;
                            if (scrim.getBoundingClientRect().height < 400 && (!wrapper || wrapper.getBoundingClientRect().height < 400)) return;
                            if (wrapper && wrapperStyle && wrapperStyle.position === 'fixed') {
                                wrapper.remove();
                            } else if (scrimStyle.position === 'fixed') {
                                scrim.remove();
                            }
                        });

                        // Dismiss 'Get the full experience' / 'See full profile in the app' dialogs & sheets
                        const modals = document.querySelectorAll('div[role="dialog"], div[aria-modal="true"]');
                        modals.forEach(function(m) {
                            m.remove();
                        });

                        // Hide mobile web header (_ab15) and desktop top Instagram / Log In / Sign Up header (_acum / _acun / _acus)
                        document.querySelectorAll('header._ab15, div._acum, div._acun, div._acus').forEach(function(h) {
                            h.style.setProperty('display', 'none', 'important');
                        });
                        document.querySelectorAll('div, header').forEach(function(el) {
                            if (el.querySelector && el.querySelector('article, main, video')) return;
                            const r = el.getBoundingClientRect();
                            if (r.top < 0 || r.top > 25 || r.height < 20 || r.height > 110) return;
                            const txt = el.innerText || '';
                            const hasAuthLinks = txt.length > 0 && txt.length < 60 && txt.indexOf('Log In') !== -1 && txt.indexOf('Sign Up') !== -1;
                            const hasLogoOnly = el.querySelector && el.querySelector('svg[aria-label="Instagram"]') && r.height <= 80;
                            if (hasAuthLinks || hasLogoOnly) {
                                el.style.setProperty('display', 'none', 'important');
                            }
                        });

                        // Hide 'Join <creator> on Instagram' / 'Sign up for Instagram' footer upsells
                        document.querySelectorAll('div.x1rxbfg9').forEach(function(j) {
                            j.style.setProperty('display', 'none', 'important');
                        });

                        // Also remove any overlay elements containing promotional copy
                        document.querySelectorAll('div, section').forEach(function(el) {
                            const text = el.innerText || '';
                            if (text.indexOf('Get the full experience') !== -1 || text.indexOf('Sign up for Instagram') !== -1) {
                                if (el.closest) {
                                    let container = el.closest('div.xg7h5cd') || el.closest('div[role="dialog"]') || el.closest('div[aria-modal="true"]') || el.closest('div.x1rxbfg9');
                                    if (container) {
                                        container.remove();
                                    }
                                }
                            }
                        });

                        // Remove any remaining fixed-position promo overlays (while keeping nav interactive)
                        const fixedDivs = document.querySelectorAll('div[style*="position: fixed"], div[style*="position:fixed"]');
                        fixedDivs.forEach(function(f) {
                            const text = f.innerText || f.textContent || "";
                            if (text.indexOf("See full profile") !== -1 ||
                                text.indexOf("Open Instagram") !== -1 ||
                                text.indexOf("Get the app") !== -1 ||
                                text.indexOf("Get the full experience") !== -1) {
                                f.remove();
                            }
                        });

                        // Neutralize Instagram native-app / Play Store deep links so clicks inside the profile never hijack navigation with intent:// aborts
                        document.querySelectorAll('a[href^="intent:"], a[href^="instagram:"], a[href*="play.google.com"]').forEach(function(a) {
                            a.setAttribute('data-blocked-href', a.getAttribute('href') || '');
                            a.removeAttribute('href');
                            a.style.pointerEvents = 'none';
                        });

                        // Remove top offset left behind by fixed header._ab15 / div._acum so content aligns cleanly under native app bar
                        document.querySelectorAll('div').forEach(function(d) {
                            if (d.closest && d.closest('article')) return;
                            const st = getComputedStyle(d);
                            if (st.paddingTop === '60px' || st.paddingTop === '64px' || st.paddingTop === '65px' ||
                                st.marginTop === '60px' || st.marginTop === '64px' || st.marginTop === '65px') {
                                d.style.setProperty('padding-top', '0px', 'important');
                                d.style.setProperty('margin-top', '0px', 'important');
                            }
                        });

                        // Ensure body and html are scrollable
                        if (document.body) {
                            document.body.style.setProperty('overflow', 'auto', 'important');
                            document.body.style.setProperty('position', 'static', 'important');
                        }
                        if (document.documentElement) {
                            document.documentElement.style.setProperty('overflow', 'auto', 'important');
                            document.documentElement.style.setProperty('position', 'static', 'important');
                        }
                    }

                    cleanupModals();
                    reportBottomNavState();
                    reportPostAuthor();
                    scanVisibleFeedMedia();

                    // 3. Build & Render Native Post Grid directly below bio when logged out
                    function renderProfileGrid() {
                        const existingGrid = document.getElementById('instasave-injected-grid');
                        if (isLoggedIn) {
                            if (existingGrid) existingGrid.remove();
                            return;
                        }
                        const path = window.location.pathname || '/';
                        const isPostOrNonProfile = path.indexOf('/p/') !== -1 ||
                            path.indexOf('/reel/') !== -1 ||
                            path.indexOf('/reels/') !== -1 ||
                            path.indexOf('/stories/') !== -1 ||
                            path.indexOf('/explore') !== -1 ||
                            path.indexOf('/accounts/') !== -1;

                        if (isPostOrNonProfile) {
                            if (existingGrid) existingGrid.remove();
                            return;
                        }

                        // If user is logged in and Instagram natively rendered post links, keep native infinite scroll!
                        if (document.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]').length > 0) {
                            if (existingGrid) existingGrid.remove();
                            return;
                        }

                        if (existingGrid) {
                            if (existingGrid.getAttribute('data-profile-path') === path) {
                                return;
                            }
                            existingGrid.remove();
                        }

                        // Wait until React mounts the creator bio <header> so the grid ALWAYS appears BELOW the bio!
                        const bioHeader = Array.from(document.querySelectorAll('header')).find(function(h) {
                            if (h.classList && h.classList.contains('_ab15')) return false;
                            const t = h.innerText || '';
                            return t.indexOf('followers') !== -1 || t.indexOf('posts') !== -1 || !!h.querySelector('h1, h2');
                        });
                        if (!bioHeader || !bioHeader.parentElement) {
                            return;
                        }

                        function pkToShortcode(pkStr) {
                            let pk = BigInt(pkStr);
                            const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
                            let shortcode = '';
                            while (pk > 0n) {
                                let remainder = Number(pk % 64n);
                                pk = pk / 64n;
                                shortcode = alphabet[remainder] + shortcode;
                            }
                            return shortcode;
                        }

                        function findKey(obj, key) {
                            if (!obj || typeof obj !== 'object') return null;
                            if (obj[key]) return obj[key];
                            for (let k in obj) {
                                let res = findKey(obj[k], key);
                                if (res) return res;
                            }
                            return null;
                        }

                        const pathUsername = path.split('/').filter(Boolean)[0] || '';
                        const scripts = Array.from(document.querySelectorAll('script'));
                        const s = scripts.find(sc => sc.innerText && sc.innerText.indexOf('polaris_timeline_connection') !== -1);
                        if (!s) return;

                        // If an SPA pushState switched creators without updating SSR script tags, force clean reload for the new profile
                        if (pathUsername && s.innerText.toLowerCase().indexOf(pathUsername.toLowerCase()) === -1) {
                            if (!window.__instasave_reloading_for_path || window.__instasave_reloading_for_path !== path) {
                                window.__instasave_reloading_for_path = path;
                                window.location.replace(window.location.href);
                            }
                            return;
                        }

                        let edges = [];
                        try {
                            const data = JSON.parse(s.innerText);
                            const timeline = findKey(data, 'polaris_timeline_connection');
                            if (timeline && timeline.edges) {
                                edges = timeline.edges;
                            }
                        } catch(e) { return; }

                        if (edges.length === 0) return;

                        // Release any 100vh / fixed height locks on bio ancestors so bio + grid scroll smoothly together
                        let anc = bioHeader.parentElement;
                        while (anc && anc !== document.documentElement) {
                            anc.style.setProperty('height', 'auto', 'important');
                            anc.style.setProperty('min-height', '0', 'important');
                            anc = anc.parentElement;
                        }

                        const wrapper = document.createElement('div');
                        wrapper.id = 'instasave-injected-grid';
                        wrapper.setAttribute('data-profile-path', path);
                        wrapper.style.cssText = 'width: 100%; background-color: #0B0813; box-sizing: border-box; padding-bottom: 40px; position: relative; z-index: 5;';
                        // Prevent Instagram React touch/pointer/click listeners on ancestors from firing intent:// redirects
                        ['click', 'touchstart', 'touchend', 'pointerdown', 'pointerup'].forEach(function(evtName) {
                            wrapper.addEventListener(evtName, function(ev) { ev.stopPropagation(); }, false);
                        });

                        const gridContainer = document.createElement('div');
                        gridContainer.style.cssText = 'display: grid; grid-template-columns: repeat(3, 1fr); gap: 3px; padding: 8px 4px 16px 4px; width: 100%; box-sizing: border-box;';

                        edges.forEach(e => {
                            const n = e.node || {};
                            const pk = n.pk;
                            if (!pk) return;
                            const shortcode = pkToShortcode(pk);
                            const isVideo = n.__typename === 'XIGPolarisVideoMedia';
                            const isCarousel = n.__typename === 'XIGPolarisCarouselMedia';

                            let thumbUrl = '';
                            if (n.image_versions2 && n.image_versions2.candidates && n.image_versions2.candidates.length > 0) {
                                const cands = n.image_versions2.candidates;
                                thumbUrl = (cands.find(c => c.width >= 300 && c.width <= 640) || cands[0]).url;
                            }

                            const item = document.createElement('div');
                            item.style.cssText = 'position: relative; width: 100%; padding-top: 100%; background-color: #1A1626; cursor: pointer; overflow: hidden; border-radius: 4px;';

                            const img = document.createElement('img');
                            img.src = thumbUrl;
                            img.loading = 'eager';
                            img.style.cssText = 'position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: cover;';
                            item.appendChild(img);

                            if (isCarousel || isVideo) {
                                const badge = document.createElement('div');
                                badge.style.cssText = 'position: absolute; top: 6px; right: 6px; background: rgba(0, 0, 0, 0.65); backdrop-filter: blur(4px); border-radius: 4px; padding: 3px 5px; font-size: 11px; color: #FFFFFF; font-family: sans-serif;';
                                badge.innerText = isCarousel ? '⧉' : '▶';
                                item.appendChild(badge);
                            }

                            item.onclick = function(ev) {
                                if (ev) {
                                    ev.preventDefault();
                                    ev.stopPropagation();
                                    if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                                }
                                const targetPath = isVideo ? '/reel/' + shortcode + '/' : '/p/' + shortcode + '/';
                                window.location.assign('https://www.instagram.com' + targetPath);
                                return false;
                            };

                            gridContainer.appendChild(item);
                        });

                        wrapper.appendChild(gridContainer);

                        // Add clean native-style footer prompt for optional local sign-in to load older posts beyond 12
                        const footerCard = document.createElement('div');
                        footerCard.style.cssText = 'margin: 6px 12px 16px 12px; padding: 14px 16px; border-radius: 14px; background-color: #161422; border: 1px solid #2A253F; text-align: center; font-family: sans-serif;';
                        const footerTitle = document.createElement('div');
                        footerTitle.style.cssText = 'color: #E2DDF0; font-size: 13px; font-weight: 600;';
                        footerTitle.innerText = 'Showing latest ' + edges.length + ' public posts';
                        const footerSub = document.createElement('div');
                        footerSub.style.cssText = 'color: #9A93B2; font-size: 11.5px; margin-top: 4px; line-height: 1.4;';
                        footerSub.innerText = 'Instagram restricts older history without a session. Sign in locally on this device to unlock full profile scroll.';
                        const loginBtn = document.createElement('button');
                        loginBtn.type = 'button';
                        loginBtn.style.cssText = 'margin-top: 10px; padding: 8px 16px; border-radius: 10px; background-color: #25213D; color: #E1306C; border: 1px solid #E1306C; font-size: 12.5px; font-weight: 700; cursor: pointer;';
                        loginBtn.innerText = 'Sign in on Instagram (Local Sandbox)';
                        loginBtn.onclick = function(ev) {
                            if (ev) {
                                ev.preventDefault();
                                ev.stopPropagation();
                                if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                            }
                            const nextEnc = encodeURIComponent(window.location.pathname || '/');
                            window.location.assign('https://www.instagram.com/accounts/login/?next=' + nextEnc);
                            return false;
                        };
                        footerCard.appendChild(footerTitle);
                        footerCard.appendChild(footerSub);
                        footerCard.appendChild(loginBtn);
                        wrapper.appendChild(footerCard);

                        // Insert IMMEDIATELY below bio header so bio stays at the top
                        bioHeader.insertAdjacentElement('afterend', wrapper);

                        document.body.style.setProperty('overflow', 'auto', 'important');
                        document.documentElement.style.setProperty('overflow', 'auto', 'important');
                    }

                    renderProfileGrid();

                    // 4. Setup MutationObserver to kill dialogs, dim scrims, and mount grid cleanly
                    if (!window.__instasave_observer_set) {
                        window.__instasave_observer_set = true;
                        const observer = new MutationObserver(function(mutations) {
                            cleanupModals();
                            reportPostAuthor();
                            clearTimeout(window.__instasave_mut_timer);
                            window.__instasave_mut_timer = setTimeout(function() {
                                reportBottomNavState();
                                scanVisibleFeedMedia();
                            }, 320);
                            renderProfileGrid();
                        });
                        observer.observe(document.documentElement || document.body, {
                            childList: true,
                            subtree: true
                        });
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Read-only extraction of currently rendered post/carousel media URLs inside the visible WebView DOM.
     * Used ONLY as a fallback when a user explicitly taps "Save" on a private/follow-only feed item
     * that isolated anonymous resolvers cannot access. Zero network requests or cookie reads.
     */
    fun buildVisibleDomMediaExtractorScript(targetShortcode: String): String {
        val safeCode = targetShortcode.replace("\"", "").replace("'", "")
        return """
            (function() {
                try {
                    const code = "$safeCode";
                    const vpH = window.innerHeight || 800;
                    let container = null;
                    if (code) {
                        const links = document.querySelectorAll('a[href*="' + code + '"]');
                        for (let i = 0; i < links.length; i++) {
                            const art = links[i].closest('article');
                            if (art) { container = art; break; }
                        }
                    }
                    if (!container) {
                        const articles = Array.from(document.querySelectorAll('article'));
                        let bestScore = -1;
                        articles.forEach(function(art) {
                            const r = art.getBoundingClientRect();
                            const vis = Math.min(r.bottom, vpH) - Math.max(r.top, 0);
                            if (vis > bestScore) { bestScore = vis; container = art; }
                        });
                    }
                    if (!container) container = document.querySelector('main') || document.body;
                    if (!container) return JSON.stringify({urls: []});

                    const urls = [];
                    const seen = new Set();
                    function pushUrl(u, isVideo) {
                        if (!u || typeof u !== 'string') return;
                        if (u.indexOf('http') !== 0 || u.indexOf('blob:') === 0) return;
                        if (!seen.has(u)) {
                            seen.add(u);
                            urls.push({url: u, isVideo: !!isVideo});
                        }
                    }

                    container.querySelectorAll('video').forEach(function(v) {
                        const src = v.currentSrc || v.src || '';
                        if (src && src.indexOf('blob:') !== 0) pushUrl(src, true);
                        v.querySelectorAll('source').forEach(function(s) {
                            if (s.src && s.src.indexOf('blob:') !== 0) pushUrl(s.src, true);
                        });
                    });

                    container.querySelectorAll('img').forEach(function(img) {
                        const w = img.naturalWidth || img.width || 0;
                        const h = img.naturalHeight || img.height || 0;
                        if (w < 280 || h < 280) return;
                        if (Math.abs(w - h) <= 4 && w <= 320 && img.closest && img.closest('header')) return;
                        let bestUrl = img.currentSrc || img.src || '';
                        const ss = img.getAttribute('srcset') || '';
                        if (ss) {
                            let maxW = 0;
                            ss.split(',').forEach(function(part) {
                                const seg = part.trim().split(/\s+/);
                                if (seg.length >= 2) {
                                    const pw = parseInt(seg[1], 10) || 0;
                                    if (pw > maxW && seg[0].indexOf('http') === 0) {
                                        maxW = pw;
                                        bestUrl = seg[0];
                                    }
                                }
                            });
                        }
                        pushUrl(bestUrl, false);
                    });

                    return JSON.stringify({urls: urls});
                } catch(e) {
                    return JSON.stringify({urls: []});
                }
            })();
        """.trimIndent()
    }

    /**
     * Wipes all local session cookies, DOM storage, and cache for complete privacy hygiene.
     */
    fun clearLocalSession(webView: WebView, onCleared: (() -> Unit)? = null) {
        AppLogger.i(TAG, "Clearing local profile browser session and cookies...")
        try {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()

            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies {
                cookieManager.flush()
                onCleared?.invoke()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error clearing session: ${e.message}")
            onCleared?.invoke()
        }
    }

    /**
     * Signs out cleanly from non-WebView screens (Settings / Home / Account Sheet) without creating a WebView.
     */
    fun clearCookiesOnly(onCleared: (() -> Unit)? = null) {
        AppLogger.i(TAG, "Signing out of local Instagram WebView session...")
        try {
            WebStorage.getInstance().deleteAllData()
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies {
                cookieManager.flush()
                onCleared?.invoke()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error clearing cookies: ${e.message}")
            onCleared?.invoke()
        }
    }
}
