/**
 * CSRF helpers for cookie-authenticated API calls (H3).
 *
 * Spring Security writes a non-HttpOnly XSRF-TOKEN cookie; mutating requests
 * must echo it as X-XSRF-TOKEN. Loaded by login/register/reader/dashboard/notes.
 *
 * Prefer KjvCsrf.init(opts) or KjvCsrf.fetch(url, opts) over hand-rolling headers.
 */
(function (global) {
    'use strict';

    function token() {
        const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    function headers(base) {
        if (typeof Headers !== 'undefined' && base instanceof Headers) {
            const h = new Headers(base);
            const t = token();
            if (t) h.set('X-XSRF-TOKEN', t);
            return h;
        }
        const out = Object.assign({}, base || {});
        const t = token();
        if (t) out['X-XSRF-TOKEN'] = t;
        return out;
    }

    function isMutating(method) {
        const m = (method || 'GET').toUpperCase();
        return m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS' && m !== 'TRACE';
    }

    /**
     * Merge credentials + CSRF header into a fetch init object for mutating methods.
     */
    function init(initOpts) {
        const opts = Object.assign({ credentials: 'include' }, initOpts || {});
        if (isMutating(opts.method)) {
            opts.headers = headers(opts.headers);
        }
        return opts;
    }

    function fetchWithCsrf(input, initOpts) {
        return global.fetch(input, init(initOpts));
    }

    global.KjvCsrf = { token: token, headers: headers, init: init, fetch: fetchWithCsrf };
})(window);
