module.exports = { resolve: (p) => p, join: (...a) => a.join('/'), basename: (p) => String(p).split('/').pop(), extname: (p) => { const m = String(p).match(/\.[^./]+$/); return m ? m[0] : ''; } };
