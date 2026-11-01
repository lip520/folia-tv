// Folia TV 遥控器焦点导航：方向键几何寻焦、OK 点击、返回关弹层
(function () {
    if (window.tvNav) return;

    var FOCUS_CLASS = 'tv-focus';
    var current = null;

    var css = [
        '.' + FOCUS_CLASS + '{',
        '  outline: 3px solid #8B5CF6 !important;',
        '  outline-offset: 2px !important;',
        '  box-shadow: 0 0 18px rgba(139,92,246,0.75) !important;',
        '  border-radius: 8px !important;',
        '  position: relative !important;',
        '  z-index: 1 !important;',
        '}',
        '* { cursor: none !important; }'
    ].join('\n');

    function injectCss() {
        if (document.getElementById('tvnav-style')) return;
        var style = document.createElement('style');
        style.id = 'tvnav-style';
        style.textContent = css;
        (document.head || document.documentElement).appendChild(style);
    }

    function isVisible(el) {
        if (!el || !el.getBoundingClientRect) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) return false;
        if (r.bottom < 0 || r.right < 0) return false;
        if (r.top > window.innerHeight || r.left > window.innerWidth) return false;
        var cs = window.getComputedStyle(el);
        return cs.visibility !== 'hidden' && cs.display !== 'none' &&
               cs.pointerEvents !== 'none' && parseFloat(cs.opacity) > 0.05;
    }

    function collect() {
        var found = [];
        var seen = new Set();
        var sel = 'a[href],button,input,textarea,select,summary,' +
                  '[role="button"],[role="link"],[role="tab"],[role="menuitem"],' +
                  '[role="slider"],[role="switch"],[tabindex]:not([tabindex="-1"])';
        document.querySelectorAll(sel).forEach(function (el) {
            if (!seen.has(el) && isVisible(el)) { seen.add(el); found.push(el); }
        });
        // React 常用 div onClick：用 cursor:pointer 兜底识别
        document.querySelectorAll('div,span,li,img').forEach(function (el) {
            if (seen.has(el)) return;
            if (window.getComputedStyle(el).cursor === 'pointer' && isVisible(el)) {
                seen.add(el); found.push(el);
            }
        });
        // 容器与内部子项都可聚焦时，只留最内层，避免重复
        return found.filter(function (el) {
            var p = el.parentElement;
            while (p) { if (seen.has(p) && found.indexOf(p) !== -1) {
                var rs = p.getBoundingClientRect(), re = el.getBoundingClientRect();
                if (Math.abs(rs.width - re.width) < 4 && Math.abs(rs.height - re.height) < 4) return false;
            } p = p.parentElement; }
            return true;
        });
    }

    function setFocus(el) {
        if (current === el) return;
        if (current) current.classList.remove(FOCUS_CLASS);
        current = el;
        if (current) {
            current.classList.add(FOCUS_CLASS);
            current.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
            try { current.focus({ preventScroll: true }); } catch (e) {}
        }
    }

    function center(el) {
        var r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2, r: r };
    }

    function move(dir) {
        injectCss();
        var items = collect();
        if (!items.length) return;
        if (!current || !document.contains(current) || !isVisible(current)) {
            // 初始焦点：优先当前播放控件区域，否则页面中部最近元素
            setFocus(items[0]);
            return;
        }
        var c = center(current), best = null, bestScore = Infinity;
        items.forEach(function (el) {
            if (el === current) return;
            var t = center(el);
            var dx = t.x - c.x, dy = t.y - c.y;
            var primary, cross;
            if (dir === 'left')  { if (dx > -4) return; primary = -dx; cross = Math.abs(dy); }
            if (dir === 'right') { if (dx <  4) return; primary =  dx; cross = Math.abs(dy); }
            if (dir === 'up')    { if (dy > -4) return; primary = -dy; cross = Math.abs(dx); }
            if (dir === 'down')  { if (dy <  4) return; primary =  dy; cross = Math.abs(dx); }
            // 同轴优先：偏离主轴越多惩罚越大
            var score = primary + cross * 2.5;
            if (score < bestScore) { bestScore = score; best = el; }
        });
        if (best) setFocus(best);
    }

    function fireClick(el) {
        var r = el.getBoundingClientRect();
        var x = r.left + r.width / 2, y = r.top + r.height / 2;
        ['pointerover', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']
            .forEach(function (type) {
                var Ctor = type.indexOf('pointer') === 0 ? PointerEvent : MouseEvent;
                el.dispatchEvent(new Ctor(type, {
                    bubbles: true, cancelable: true, view: window,
                    clientX: x, clientY: y, button: 0, pointerId: 1, isPrimary: true
                }));
            });
    }

    function click() {
        injectCss();
        if (!current || !document.contains(current)) { move('down'); return; }
        var tag = current.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA') {
            current.focus();
            // 触发系统输入法
            current.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
            return;
        }
        fireClick(current);
    }

    function togglePlay() {
        var video = document.querySelector('video, audio');
        if (video) { video.paused ? video.play() : video.pause(); }
    }

    function escape() {
        // 检测常见弹层，存在则派发 Escape 让页面自己关闭
        var modal = document.querySelector(
            '[role="dialog"],[aria-modal="true"],.modal,.Modal,.overlay.show,[data-open="true"]');
        document.dispatchEvent(new KeyboardEvent('keydown', {
            key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
            bubbles: true, cancelable: true
        }));
        document.body.dispatchEvent(new KeyboardEvent('keydown', {
            key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
            bubbles: true, cancelable: true
        }));
        return modal ? 'handled' : 'none';
    }

    // 页面是 SPA，路由变化后 DOM 替换，重新注入样式并清掉失效焦点
    var lastUrl = location.href;
    new MutationObserver(function () {
        injectCss();
        if (location.href !== lastUrl) {
            lastUrl = location.href;
            if (current && !document.contains(current)) current = null;
            // 搜索提交后路由进入 #search/...，blur 输入框收起系统 IME，
            // 否则软键盘会一直盖住搜索结果（真实遥控器流程实测复现）
            if (location.hash.indexOf('#search/') === 0) {
                var ae = document.activeElement;
                if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) {
                    ae.blur();
                }
            }
        }
        if (current && !document.contains(current)) current = null;
    }).observe(document.documentElement, { childList: true, subtree: true });

    window.tvNav = { move: move, click: click, escape: escape, togglePlay: togglePlay };
    injectCss();
    return 'tvnav-ready';
})();
