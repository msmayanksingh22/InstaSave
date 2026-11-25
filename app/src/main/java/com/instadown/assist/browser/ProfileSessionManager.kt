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
                    function reportPostAuthor() {
                        try {
                            if (!window.InstaSaveBridge || !window.InstaSaveBridge.onPostAuthorDetected) return;
                            const isPost = currentPath.indexOf('/p/') !== -1 ||
                                currentPath.indexOf('/reel/') !== -1 ||
                                currentPath.indexOf('/reels/') !== -1;
                            if (!isPost) return;

                            let foundAuthor = '';
                            const reserved = ['p','reel','reels','tv','share','explore','accounts','direct','stories','about','legal','developer','terms','privacy','static','api'];
                            const headerLinks = document.querySelectorAll('article header a[href^="/"], main a[href^="/"]');
                            for (let i = 0; i < headerLinks.length; i++) {
                                const href = headerLinks[i].getAttribute('href') || '';
                                const seg = href.split('?')[0].split('/').filter(Boolean);
                                if (seg.length === 1) {
                                    const candidate = seg[0].toLowerCase();
                                    if (reserved.indexOf(candidate) === -1 && /^[a-z0-9._]{1,30}$/.test(candidate)) {
                                        foundAuthor = candidate;
                                        break;
                                    }
                                }
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
                            if (foundAuthor && window.__instasave_last_author !== currentPath + ':' + foundAuthor) {
                                window.__instasave_last_author = currentPath + ':' + foundAuthor;
                                window.InstaSaveBridge.onPostAuthorDetected(foundAuthor);
                            }

                            // Give post view bottom scroll clearance above floating pill
                            const mainEl = document.querySelector('main') || document.querySelector('article');
                            if (mainEl) {
                                mainEl.style.setProperty('padding-bottom', '140px', 'important');
                            }
                        } catch(e) {}
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
                            // Keep native logged-in modals, comments, save prompts & scroll untouched!
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
                    reportPostAuthor();

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
