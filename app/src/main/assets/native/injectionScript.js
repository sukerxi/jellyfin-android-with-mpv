(() => {
    const native = window.NativeInterface;
    const mainBundleUrl = document.currentScript.src.concat('?deferred=true&ts=', Date.now());
    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        mainBundleUrl
    ];
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        scriptElement.setAttribute('defer', '');
        if (script === mainBundleUrl) {
            // 这份脚本才是真正的 webapp 入口，页面能否出现完全取决于它。网络不稳定时
            // 它可能加载失败或长时间卡住，必须上报原生层，否则 WebView 会停在
            // 一片什么都没画的黑色页面上，用户既没有提示也没有重试入口。
            scriptElement.addEventListener('load', () => native && native.onWebappBundleLoaded());
            scriptElement.addEventListener('error', () => native && native.onWebappBundleFailed(mainBundleUrl));
        }
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();

    // 首屏渲染探测：index.html 的 #reactRoot 里只有一个静态 splashLogo，登录页/首页
    // 由 React 挂载后才会出现文字、输入框或按钮。探测到实质内容后才收起加载圈，
    // 避免「bundle 下载完但界面还没画出来」的那一段黑屏。
    const renderProbe = setInterval(() => {
        const root = document.getElementById('reactRoot');
        const rendered = (document.body && (document.body.innerText || '').trim().length > 0) ||
            !!document.querySelector('input, button') ||
            !!(root && (root.children.length > 1 || !root.querySelector('.splashLogo')));
        if (rendered) {
            clearInterval(renderProbe);
            native && native.onWebappRendered();
        }
    }, 300);
    setTimeout(() => clearInterval(renderProbe), 30000);
})();
